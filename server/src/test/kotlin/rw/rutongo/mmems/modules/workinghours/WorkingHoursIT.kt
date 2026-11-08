@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList")

package rw.rutongo.mmems.modules.workinghours

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
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.WorkingHoursGroupBy
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkingHoursIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: WorkingHoursService
    private lateinit var equipmentTypeId: UUID
    private lateinit var shiftId: UUID
    private val fixedNow = Instant.parse("2026-11-08T10:00:00Z")
    private val reportDate = LocalDate.parse("2026-11-08")

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
        service = WorkingHoursService(dataSource)
        equipmentTypeId = createEquipmentType()
        shiftId =
            dataSource.connection.use { c ->
                c.prepareStatement("select id from shift where code='DAY'").use { ps ->
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `working hours are unioned on the meter axis instead of summing raw readings`() {
        val site = createSite("HOURS")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val outsider = createActor(Role.SUPERVISOR, listOf(createSite("OUTSIDE")))
        val firstEquipment = createEquipment(site)
        val secondEquipment = createEquipment(site)

        createEntry(firstEquipment, site, actor, null, null, null, "MISSING_READING")
        createEntry(firstEquipment, site, actor, 100.0, 110.0, 10.0, "VALID")
        createEntry(firstEquipment, site, actor, 105.0, 112.0, 7.0, "OVERLAP")
        createEntry(secondEquipment, site, actor, 200.0, 205.0, 5.0, "VALID")

        val response = service.list(actor, site, groupBy = WorkingHoursGroupBy.EQUIPMENT)
        assertEquals(4, response.entries.size)
        assertEquals(17.0, response.totalRecordedHours)
        val first = response.groups.single { it.equipmentId == firstEquipment.toString() }
        assertEquals(12.0, first.recordedHours)
        assertEquals(3, first.entryCount)
        assertEquals(1, first.overlapEntries)
        assertEquals(1, first.missingReadings)
        assertEquals(5.0, response.groups.single { it.equipmentId == secondEquipment.toString() }.recordedHours)

        val daily = service.list(actor, site, groupBy = WorkingHoursGroupBy.DAY)
        assertEquals(listOf(reportDate.toString()), daily.groups.map { it.key })
        assertEquals(17.0, daily.groups.single().recordedHours)

        assertEquals(0, service.list(actor, site, from = reportDate.plusDays(1), groupBy = WorkingHoursGroupBy.DAY).entries.size)
        val forbidden = assertFailsWith<AuthFailure> { service.list(outsider, site) }
        assertEquals("SITE_NOT_AUTHORIZED", forbidden.code)
    }

    private fun createEntry(
        equipment: UUID,
        site: UUID,
        actor: Principal,
        start: Double?,
        end: Double?,
        recorded: Double?,
        status: String,
    ) {
        val inspectionId = createInspection(equipment, site, actor)
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "insert into working_hours_entry(inspection_id,equipment_id,site_id,shift_id,report_date,hours_start,hours_end,recorded_hours,status) values (?,?,?,?,?,?,?,?,?)",
                ).use { ps ->
                    ps.setObject(1, inspectionId)
                    ps.setObject(2, equipment)
                    ps.setObject(3, site)
                    ps.setObject(4, shiftId)
                    ps.setObject(5, reportDate)
                    ps.setObject(6, start)
                    ps.setObject(7, end)
                    ps.setObject(8, recorded)
                    ps.setString(9, status)
                    ps.executeUpdate()
                }
        }
    }

    private fun createInspection(
        equipment: UUID,
        site: UUID,
        actor: Principal,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    insert into inspection(id,client_uuid,equipment_id,site_id,shift_id,report_date,inspector_id,inspector_eid,
                      result,captured_at,started_at,submitted_at,attestation)
                    values (?,?,?,?,?,?,?,?,'COMPLETED',?,?,?, '{}'::jsonb)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, id)
                    ps.setObject(2, UUID.randomUUID())
                    ps.setObject(3, equipment)
                    ps.setObject(4, site)
                    ps.setObject(5, shiftId)
                    ps.setObject(6, reportDate)
                    ps.setObject(7, actor.id)
                    ps.setString(8, "EID-${actor.id}")
                    ps.setTimestamp(9, Timestamp.from(fixedNow))
                    ps.setTimestamp(10, Timestamp.from(fixedNow))
                    ps.setTimestamp(11, Timestamp.from(fixedNow))
                    ps.executeUpdate()
                }
        }
        return id
    }

    private fun createEquipmentType(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "Hours type ${UUID.randomUUID()}")
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
                ps.setString(2, "hours-user-$id")
                ps.setString(3, "Hours User")
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
        return Principal(id, "hours-user-$id", role, false)
    }

    private fun createEquipment(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Hours Equipment")
                ps.setObject(5, equipmentTypeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }
}
