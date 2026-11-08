@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList")

package rw.rutongo.mmems.modules.personnel

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.shared.api.AssignmentCreateDto
import rw.rutongo.mmems.shared.api.OperatorCreateDto
import rw.rutongo.mmems.shared.api.PersonnelTransferCreateDto
import rw.rutongo.mmems.shared.api.TechnicianCreateDto
import rw.rutongo.mmems.shared.api.TechnicianUpdateDto
import rw.rutongo.mmems.shared.enums.PersonnelType
import rw.rutongo.mmems.shared.enums.Role
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonnelFlowIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: PersonnelService
    private lateinit var equipmentTypeId: UUID
    private lateinit var shiftId: UUID

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 4
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        service = PersonnelService(dataSource)
        equipmentTypeId = createEquipmentType()
        shiftId = queryUuid("select id from shift where code='DAY'")
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `personnel writes are admin controlled and reads are supervisor scoped`() {
        val site = createSite("PERSONNEL")
        val other = createSite("OUTSIDE")
        val admin = createActor(Role.ADMINISTRATOR, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val outsider = createActor(Role.SUPERVISOR, listOf(other))
        val eid = "EID-${UUID.randomUUID().toString().take(8)}"

        val forbidden =
            assertFailsWith<AuthFailure> {
                service.createTechnician(supervisor, TechnicianCreateDto(eid, "Technician"))
            }
        assertEquals("FORBIDDEN", forbidden.code)

        val technician =
            service.createTechnician(
                admin,
                TechnicianCreateDto(eid, "Jean Technician", function = "Mechanic", phone = "+250", siteId = site.toString(), shiftId = shiftId.toString()),
            )
        assertEquals(eid, technician.eid)
        assertEquals("DAY", technician.shiftCode)
        assertEquals(0, technician.version)
        assertEquals(1, service.listTechnicians(supervisor, site).size)
        assertEquals(0, service.listTechnicians(outsider, siteId = null).size)
        val scope = assertFailsWith<AuthFailure> { service.listTechnicians(outsider, site) }
        assertEquals("SITE_NOT_AUTHORIZED", scope.code)

        val stale =
            assertFailsWith<AuthFailure> {
                service.updateTechnician(admin, UUID.fromString(technician.id), technician.version + 1, TechnicianUpdateDto("Renamed"))
            }
        assertEquals("TECHNICIAN_VERSION_CONFLICT", stale.code)
        val updated = service.updateTechnician(admin, UUID.fromString(technician.id), technician.version, TechnicianUpdateDto("Renamed", siteId = site.toString()))
        assertEquals(1, updated.version)
        assertEquals("Renamed", updated.name)
    }

    @Test
    fun `assignments close previous holders and transfers preserve history`() {
        val site = createSite("ASSIGN")
        val target = createSite("TARGET")
        val admin = createActor(Role.ADMINISTRATOR, listOf(site, target))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val equipment = createEquipment(site)
        val operator =
            service.createOperator(
                admin,
                OperatorCreateDto("OP-${UUID.randomUUID().toString().take(8)}", "Operator One", siteId = site.toString(), shiftId = shiftId.toString()),
            )
        val technician =
            service.createTechnician(
                admin,
                TechnicianCreateDto("TECH-${UUID.randomUUID().toString().take(8)}", "Technician One", siteId = site.toString(), shiftId = shiftId.toString()),
            )

        val operatorAssignment =
            service.createAssignment(
                admin,
                AssignmentCreateDto(PersonnelType.OPERATOR, operator.id, equipment.toString(), reason = "Primary operator"),
            )
        assertEquals(operator.id, operatorAssignment.personId)
        assertEquals(1, service.listAssignments(supervisor, PersonnelType.OPERATOR, equipmentId = equipment, openOnly = true).size)
        assertEquals(operator.id, equipmentAssigned("operator", equipment))

        val technicianAssignment =
            service.createAssignment(
                admin,
                AssignmentCreateDto(PersonnelType.TECHNICIAN, technician.id, equipment.toString(), reason = "Primary technician"),
            )
        assertEquals(technician.id, technicianAssignment.personId)
        assertEquals(technician.id, equipmentAssigned("technician", equipment))

        val newOperator =
            service.createOperator(
                admin,
                OperatorCreateDto("OP-${UUID.randomUUID().toString().take(8)}", "Operator Two", siteId = site.toString()),
            )
        service.createAssignment(admin, AssignmentCreateDto(PersonnelType.OPERATOR, newOperator.id, equipment.toString(), reason = "Replacement"))
        val openOperators = service.listAssignments(admin, PersonnelType.OPERATOR, equipmentId = equipment, openOnly = true)
        assertEquals(1, openOperators.size)
        assertEquals(newOperator.id, openOperators.single().personId)

        val transfer =
            service.createTransfer(
                admin,
                PersonnelTransferCreateDto(
                    PersonnelType.TECHNICIAN,
                    technician.id,
                    target.toString(),
                    toShiftId = shiftId.toString(),
                    reason = "Moved to the new site",
                ),
            )
        assertEquals(site.toString(), transfer.fromSiteId)
        assertEquals(target.toString(), transfer.toSiteId)
        assertEquals(0, service.listAssignments(admin, PersonnelType.TECHNICIAN, personId = UUID.fromString(technician.id), openOnly = true).size)
        assertNull(equipmentAssigned("technician", equipment))
        assertEquals(target.toString(), service.listTechnicians(admin, target).single { it.id == technician.id }.siteId)
        assertTrue(service.listTransfers(admin, PersonnelType.TECHNICIAN, UUID.fromString(technician.id)).isNotEmpty())
    }

    private fun equipmentAssigned(
        type: String,
        equipment: UUID,
    ): String? =
        dataSource.connection.use { c ->
            val column = if (type == "operator") "assigned_operator_id" else "assigned_technician_id"
            c.prepareStatement("select $column from equipment where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getObject(1, UUID::class.java)?.toString()
                }
            }
        }

    private fun createEquipmentType(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "Personnel type ${UUID.randomUUID()}")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createEquipment(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Personnel Equipment")
                ps.setObject(5, equipmentTypeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createSite(prefix: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state,timezone) values (?,?,?,'ACTIVE','Africa/Kigali')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "$prefix-${id.toString().take(8)}".uppercase())
                ps.setString(3, "$prefix Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(
        role: Role,
        sites: List<UUID>,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "personnel-user-$id")
                ps.setString(3, "Personnel User")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                sites.forEach {
                    ps.setObject(1, id)
                    ps.setObject(2, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return Principal(id, "personnel-user-$id", role, false)
    }

    private fun queryUuid(sql: String): UUID =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.executeQuery().use {
                    it.next()
                    it.getObject(1, UUID::class.java)
                }
            }
        }
}
