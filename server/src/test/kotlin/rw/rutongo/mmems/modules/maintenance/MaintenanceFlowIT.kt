@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList", "NestedBlockDepth")

package rw.rutongo.mmems.modules.maintenance

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
import rw.rutongo.mmems.shared.api.ServiceIntervalCreateDto
import rw.rutongo.mmems.shared.api.ServiceIntervalUpdateDto
import rw.rutongo.mmems.shared.api.ServiceTypeCreateDto
import rw.rutongo.mmems.shared.api.ServiceTypeUpdateDto
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.ServiceStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenanceFlowIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: MaintenanceService
    private lateinit var equipmentTypeId: UUID
    private val fixedNow = Instant.parse("2026-11-08T10:00:00Z")

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
        service = MaintenanceService(dataSource) { fixedNow }
        equipmentTypeId = createEquipmentType()
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `records maintenance idempotently and only a supervisor can verify it`() {
        val site = createSite("MAINT")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val manager = createActor(Role.MANAGER, listOf(site))
        val equipment = createEquipment(site, 1920.0)
        val technician = createTechnician(site)
        val serviceType = createServiceType("SERVICE-250", "250-hour service")
        createInterval(serviceType, equipment)
        val input = maintenanceInput(equipment, serviceType, technician, supervisor, "maintenance-verify")

        val created = service.create(mechanic, input)
        val replayed = service.create(mechanic, input)

        assertEquals(created.id, replayed.id)
        assertEquals(MaintenanceVerificationStatus.PENDING_VERIFICATION, created.verificationStatus)
        assertNull(created.totalCost)
        assertEquals(5000, service.list(manager, site, equipment).single().totalCost)
        assertEquals(listOf("Hydraulic filter"), created.replacedComponents)
        assertEquals(1, count("select count(*) from maintenance_record where id=?", UUID.fromString(created.id)))

        val forbidden = assertFailsWith<AuthFailure> { service.verify(mechanic, UUID.fromString(created.id), created.version, MaintenanceActionInput("Checked")) }
        assertEquals("FORBIDDEN", forbidden.code)

        val verified = service.verify(supervisor, UUID.fromString(created.id), created.version, MaintenanceActionInput("Checked against the service sheet"))
        assertEquals(MaintenanceVerificationStatus.VERIFIED, verified.verificationStatus)
        assertEquals(1, verified.version)
        assertNotNull(verified.verifiedAt)
        assertEquals(1, service.list(mechanic, site, equipment, MaintenanceVerificationStatus.VERIFIED).size)

        val stale = assertFailsWith<AuthFailure> { service.verify(supervisor, UUID.fromString(created.id), created.version, MaintenanceActionInput("Again")) }
        assertEquals("MAINTENANCE_VERSION_CONFLICT", stale.code)

        val due = service.serviceSchedule(supervisor, equipmentId = equipment).single()
        assertEquals(1920.0, due.current)
        assertEquals(1750.0, due.lastVerified)
        assertEquals(2000.0, due.nextDue)
        assertEquals(80.0, due.remaining)
        assertEquals(ServiceStatus.OK, due.status)
        assertEquals(UUID.fromString(created.id), equipmentLastMaintenance(equipment))

        assertEquals(2, count("select count(*) from audit_event where entity_id=?", created.id))
        assertEquals(2, count("select count(*) from outbox_message where payload->>'maintenanceId'=?", created.id))
    }

    @Test
    fun `service catalog configuration is admin controlled and versioned`() {
        val site = createSite("CONFIG")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val admin = createActor(Role.ADMINISTRATOR, listOf(site))
        val code = "CONFIG-${UUID.randomUUID().toString().take(8)}"

        val forbidden = assertFailsWith<AuthFailure> { service.createServiceType(mechanic, ServiceTypeCreateDto(code, "Config service")) }
        assertEquals("FORBIDDEN", forbidden.code)

        val created = service.createServiceType(admin, ServiceTypeCreateDto(code, "Config service", hoursBased = true))
        assertEquals(0, created.version)
        assertTrue(service.serviceTypes(mechanic).any { it.id == created.id })

        val stale =
            assertFailsWith<AuthFailure> {
                service.updateServiceType(admin, UUID.fromString(created.id), created.version + 1, ServiceTypeUpdateDto("Updated", hoursBased = false))
            }
        assertEquals("SERVICE_TYPE_VERSION_CONFLICT", stale.code)
        val updated = service.updateServiceType(admin, UUID.fromString(created.id), created.version, ServiceTypeUpdateDto("Updated", hoursBased = false))
        assertEquals(1, updated.version)
        assertEquals("Updated", updated.name)
        assertEquals(false, updated.hoursBased)

        val equipment = createEquipment(site, 100.0)
        val interval =
            service.createServiceInterval(
                admin,
                ServiceIntervalCreateDto(created.id, equipmentId = equipment.toString(), intervalHours = 300, dueSoonThresholdHours = 40),
            )
        assertEquals(0, interval.version)

        val updatedInterval =
            service.updateServiceInterval(
                admin,
                UUID.fromString(interval.id),
                interval.version,
                ServiceIntervalUpdateDto(intervalHours = 350, dueSoonThresholdHours = 60),
            )
        assertEquals(1, updatedInterval.version)
        assertEquals(350, updatedInterval.intervalHours)
        assertEquals(1, service.serviceIntervals(admin).count { it.id == interval.id })

        val forbiddenIntervals = assertFailsWith<AuthFailure> { service.serviceIntervals(mechanic) }
        assertEquals("FORBIDDEN", forbiddenIntervals.code)
    }

    @Test
    fun `a verified higher service resets lower intervals through satisfies type ids`() {
        val site = createSite("SATISFIES")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val equipment = createEquipment(site, 1920.0)
        val technician = createTechnician(site)
        val service250 = createServiceType("SERVICE-250", "250-hour service")
        val service500 = createServiceType("SERVICE-500", "500-hour service")
        val service1000 = createServiceType("SERVICE-1000", "1000-hour service", listOf(service250, service500))
        createInterval(service250, equipment, 250)
        createInterval(service500, equipment, 500)

        val first = service.create(mechanic, maintenanceInput(equipment, service250, technician, supervisor, "satisfies-250", 1700.0, Instant.parse("2026-10-01T08:00:00Z")))
        service.verify(supervisor, UUID.fromString(first.id), first.version, MaintenanceActionInput("Verified"))

        val major = service.create(mechanic, maintenanceInput(equipment, service1000, technician, supervisor, "satisfies-1000", 1980.0, Instant.parse("2026-11-01T08:00:00Z")))
        service.verify(supervisor, UUID.fromString(major.id), major.version, MaintenanceActionInput("Major service verified"))

        val schedule = service.serviceSchedule(supervisor, equipmentId = equipment)
        val interval250 = schedule.single { it.serviceTypeId == service250.toString() }
        val interval500 = schedule.single { it.serviceTypeId == service500.toString() }

        assertEquals(1980.0, interval250.current)
        assertEquals(1980.0, interval250.lastVerified)
        assertEquals(2230.0, interval250.nextDue)
        assertEquals(1980.0, interval500.lastVerified)
        assertEquals(2480.0, interval500.nextDue)
        assertEquals(ServiceStatus.OK, interval250.status)
    }

    @Test
    fun `reject and correction decisions require comments and are terminal where appropriate`() {
        val site = createSite("DECISION")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val outsider = createActor(Role.SUPERVISOR, listOf(createSite("OTHER")))
        val equipment = createEquipment(site, 500.0)
        val technician = createTechnician(site)
        val serviceType = createServiceType("SERVICE-DECISION", "Decision service")
        createInterval(serviceType, equipment)
        val rejectedInput = maintenanceInput(equipment, serviceType, technician, supervisor, "decision-reject", 500.0)
        val rejected = service.create(mechanic, rejectedInput)

        assertFailsWith<IllegalArgumentException> {
            service.reject(supervisor, UUID.fromString(rejected.id), rejected.version, MaintenanceActionInput("  "))
        }
        val rejectedView = service.reject(supervisor, UUID.fromString(rejected.id), rejected.version, MaintenanceActionInput("Missing evidence"))
        assertEquals(MaintenanceVerificationStatus.REJECTED, rejectedView.verificationStatus)
        val invalid =
            assertFailsWith<AuthFailure> {
                service.verify(supervisor, UUID.fromString(rejected.id), rejectedView.version, MaintenanceActionInput("Late verification"))
            }
        assertEquals("INVALID_MAINTENANCE_TRANSITION", invalid.code)
        val scope = assertFailsWith<AuthFailure> { service.list(outsider, site) }
        assertEquals("SITE_NOT_AUTHORIZED", scope.code)

        val correctionInput = maintenanceInput(equipment, serviceType, technician, supervisor, "decision-correction", 510.0)
        val correction = service.create(mechanic, correctionInput)
        assertFailsWith<IllegalArgumentException> {
            service.requestCorrection(supervisor, UUID.fromString(correction.id), correction.version, MaintenanceActionInput(null))
        }
        val correctionView = service.requestCorrection(supervisor, UUID.fromString(correction.id), correction.version, MaintenanceActionInput("Please attach the part invoice"))
        assertEquals(MaintenanceVerificationStatus.CORRECTION_REQUESTED, correctionView.verificationStatus)
    }

    private fun maintenanceInput(
        equipment: UUID,
        serviceType: UUID,
        technician: UUID,
        supervisor: Principal,
        key: String,
        engineHours: Double = 1750.0,
        performedAt: Instant = fixedNow,
    ) = MaintenanceCreate(
        idempotencyKey = "$key-${UUID.randomUUID()}",
        equipmentId = equipment,
        serviceTypeId = serviceType,
        tunnelId = null,
        performedAt = performedAt,
        engineHours = engineHours,
        workPerformed = "Replaced filters and inspected the hydraulic system",
        cause = "Scheduled service",
        technicianId = technician,
        supervisorId = supervisor.id,
        breakdownId = null,
        replacedComponents = listOf("Hydraulic filter"),
        downtimeMinutes = 90,
        labourCost = 3000,
        partsCost = 2000,
        currency = "RWF",
        notes = "No leaks found",
    )

    private fun createEquipmentType(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "Maintenance type ${UUID.randomUUID()}")
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
                ps.setString(2, "maintenance-user-$id")
                ps.setString(3, "Maintenance User")
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
        return Principal(id, "maintenance-user-$id", role, false)
    }

    private fun createEquipment(
        site: UUID,
        engineHours: Double,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id,engine_hours_current) values (?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Maintenance Equipment")
                ps.setObject(5, equipmentTypeId)
                ps.setObject(6, site)
                ps.setDouble(7, engineHours)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createTechnician(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into technician(id,eid,name,site_id) values (?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "TECH-$id")
                ps.setString(3, "Maintenance Technician")
                ps.setObject(4, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createServiceType(
        code: String,
        name: String,
        satisfies: List<UUID> = emptyList(),
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into service_type(id,code,name,hours_based,satisfies_type_ids) values (?,?,?,true,?::uuid[])").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "$code-$id")
                ps.setString(3, "$name $id")
                ps.setString(4, satisfies.joinToString(prefix = "{", postfix = "}"))
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createInterval(
        serviceType: UUID,
        equipment: UUID,
        intervalHours: Int = 250,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement("insert into service_interval(id,service_type_id,equipment_id,interval_hours,due_soon_threshold_hours) values (?,?,?,?,?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, serviceType)
                ps.setObject(3, equipment)
                ps.setInt(4, intervalHours)
                ps.setInt(5, 50)
                ps.executeUpdate()
            }
        }
    }

    private fun equipmentLastMaintenance(equipment: UUID): UUID? =
        dataSource.connection.use { c ->
            c.prepareStatement("select last_maintenance_id from equipment where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            }
        }

    private fun count(
        sql: String,
        id: UUID,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun count(
        sql: String,
        value: String,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
