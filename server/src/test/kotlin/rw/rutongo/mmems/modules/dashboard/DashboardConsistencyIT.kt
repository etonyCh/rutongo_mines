@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList", "NestedBlockDepth")

package rw.rutongo.mmems.modules.dashboard

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
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.shared.enums.DashboardKind
import rw.rutongo.mmems.shared.enums.Role
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardConsistencyIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: DashboardService
    private val fixedNow = Instant.parse("2026-11-08T10:00:00Z")
    private val from = Instant.parse("2026-11-07T10:00:00Z")

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
        service = DashboardService(dataSource) { fixedNow }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `manager dashboard matches direct sql aggregates`() {
        val site = createSite("DASH")
        val manager = createActor(Role.MANAGER, site)
        val type = createEquipmentType()
        val equipmentA = createEquipment(site, type, "OPERATIONAL", fixedNow.minusSeconds(3600))
        createEquipment(site, type, "DOWN", fixedNow.minusSeconds(3600))
        val dayShift = UUID.fromString(scalarString("select id from shift where code='DAY'"))
        val redShift = UUID.fromString(scalarString("select id from shift where code='RED'"))
        val nightShift = UUID.fromString(scalarString("select id from shift where code='NIGHT'"))
        createExpectation(equipmentA, site, dayShift, "COMPLETED", fixedNow.minusSeconds(3600))
        createExpectation(equipmentA, site, redShift, "COMPLETED_WITH_DEFECTS", fixedNow.minusSeconds(3600))
        createExpectation(equipmentA, site, nightShift, "MISSING", fixedNow.minusSeconds(3600))
        val resolved = createEquipment(site, type, "OPERATIONAL", fixedNow.minusSeconds(3600))
        val open = createEquipment(site, type, "UNDER_REPAIR", fixedNow.minusSeconds(3600))
        createBreakdown(resolved, site, manager, "RESOLVED", fixedNow.minusSeconds(3 * 3600), fixedNow.minusSeconds(2 * 3600))
        createBreakdown(open, site, manager, "UNDER_REPAIR", fixedNow.minusSeconds(2 * 3600), null)
        createMaintenance(site, type, manager, fixedNow.minusSeconds(3600))
        createReport(site, manager, fixedNow.minusSeconds(3600))

        val dashboard = service.get(manager, DashboardKind.MANAGER, site, from, fixedNow)

        assertEquals(scalarInt("select count(*) from equipment where site_id=? and lifecycle_state='ACTIVE'", site), dashboard.equipment.total)
        assertEquals(scalarInt("select count(*) from equipment where site_id=? and current_status='DOWN' and lifecycle_state='ACTIVE'", site), dashboard.equipment.down)
        assertEquals(scalarInt("select count(*) from inspection_expectation where site_id=?", site), dashboard.inspections.expected)
        assertEquals(scalarInt("select count(*) from inspection_expectation where site_id=? and state in ('COMPLETED','COMPLETED_WITH_DEFECTS','CRITICAL_FAILURE')", site), dashboard.inspections.completed)
        assertEquals(scalarInt("select count(*) from inspection_expectation where site_id=? and state='MISSING'", site), dashboard.inspections.missing)
        assertEquals(scalarInt("select count(*) from breakdown where site_id=? and reported_at between ? and ?", site, Timestamp.from(from), Timestamp.from(fixedNow)), dashboard.breakdowns.reported)
        assertEquals(scalarInt("select count(*) from breakdown where site_id=? and repair_end is not null", site), dashboard.breakdowns.completed)
        assertEquals(scalarInt("select count(*) from breakdown where site_id=? and state not in ('RESOLVED','CLOSED')", site), dashboard.breakdowns.open)
        assertEquals(180, dashboard.breakdowns.downtimeMinutes)
        assertEquals(scalarInt("select count(*) from maintenance_record where site_id=?", site), dashboard.maintenance.recorded)
        assertEquals(scalarLong("select coalesce(sum(total_cost),0) from maintenance_record where site_id=?", site), dashboard.costs?.totalCost)
        assertEquals(scalarInt("select count(*) from report_version rv join daily_report dr on dr.id=rv.report_id where dr.site_id=? and rv.state='SUBMITTED'", site), dashboard.reports.submitted)
        assertEquals(scalarInt("select count(*) from report_version rv join daily_report dr on dr.id=rv.report_id where dr.site_id=? and rv.state='APPROVED'", site), dashboard.reports.approved)
        assertEquals(1, dashboard.sites.size)
        assertNotNull(dashboard.costs)
    }

    private fun createSite(code: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state) values (?,?,?,'ACTIVE')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "$code-${id.toString().take(8)}")
                ps.setString(3, "Dashboard Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(
        role: Role,
        site: UUID,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "dashboard-$id")
                ps.setString(3, "Dashboard Manager")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, site)
                ps.executeUpdate()
            }
        }
        return Principal(id, "dashboard-$id", role, false)
    }

    private fun createEquipmentType(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "Dashboard Type $id")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createEquipment(
        site: UUID,
        type: UUID,
        status: String,
        createdAt: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id,current_status,created_at) values (?,?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Dashboard Equipment")
                ps.setObject(5, type)
                ps.setObject(6, site)
                ps.setString(7, status)
                ps.setTimestamp(8, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createExpectation(
        equipment: UUID,
        site: UUID,
        shift: UUID,
        state: String,
        at: Instant,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement("insert into inspection_expectation(id,equipment_id,site_id,shift_id,report_date,window_start,window_end,state) values (?,?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, equipment)
                ps.setObject(3, site)
                ps.setObject(4, shift)
                ps.setObject(5, java.time.LocalDate.of(2026, 11, 8))
                ps.setTimestamp(6, Timestamp.from(at))
                ps.setTimestamp(7, Timestamp.from(at.plusSeconds(3600)))
                ps.setString(8, state)
                ps.executeUpdate()
            }
        }
    }

    private fun createBreakdown(
        equipment: UUID,
        site: UUID,
        actor: Principal,
        state: String,
        start: Instant,
        end: Instant?,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement("insert into breakdown(id,equipment_id,category,problem,site_id,reported_by,reported_at,breakdown_start,repair_start,repair_end,state,idempotency_key) values (?,?,?,?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, equipment)
                ps.setString(3, "MECHANICAL")
                ps.setString(4, "Dashboard test breakdown")
                ps.setObject(5, site)
                ps.setObject(6, actor.id)
                ps.setTimestamp(7, Timestamp.from(start))
                ps.setTimestamp(8, Timestamp.from(start))
                ps.setTimestamp(9, Timestamp.from(start.plusSeconds(3600)))
                ps.setTimestamp(10, end?.let(Timestamp::from))
                ps.setString(11, state)
                ps.setString(12, "dashboard-${UUID.randomUUID()}")
                ps.executeUpdate()
            }
        }
    }

    private fun createMaintenance(
        site: UUID,
        type: UUID,
        actor: Principal,
        at: Instant,
    ) {
        val serviceType = UUID.randomUUID()
        val technician = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into service_type(id,code,name,hours_based) values (?,?,?,false) on conflict do nothing").use { ps ->
                ps.setObject(1, serviceType)
                ps.setString(2, "DASH-$serviceType")
                ps.setString(3, "Dashboard Service")
                ps.executeUpdate()
            }
            c.prepareStatement("insert into technician(id,eid,name) values (?,?,?)").use { ps ->
                ps.setObject(1, technician)
                ps.setString(2, "TECH-$technician")
                ps.setString(3, "Dashboard Technician")
                ps.executeUpdate()
            }
            c.prepareStatement("insert into maintenance_record(id,equipment_id,service_type_id,site_id,performed_at,work_performed,recorded_by,technician_id,labour_cost,parts_cost,currency,verification_status,idempotency_key) values (?,?,?,?,?,?,?,?,?,?,?,'VERIFIED',?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, scalarObject("select id from equipment where site_id=? and type_id=? limit 1", site, type))
                ps.setObject(3, serviceType)
                ps.setObject(4, site)
                ps.setTimestamp(5, Timestamp.from(at))
                ps.setString(6, "Dashboard maintenance")
                ps.setObject(7, actor.id)
                ps.setObject(8, technician)
                ps.setLong(9, 100)
                ps.setLong(10, 50)
                ps.setString(11, "USD")
                ps.setString(12, "maintenance-$serviceType")
                ps.executeUpdate()
            }
        }
    }

    private fun createReport(
        site: UUID,
        actor: Principal,
        at: Instant,
    ) {
        val report = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into daily_report(id,report_ref,site_id,report_date,period,prepared_by) values (?,?,?,?,'DAY',?)").use { ps ->
                ps.setObject(1, report)
                ps.setString(2, "DASH-${report.toString().take(8)}")
                ps.setObject(3, site)
                ps.setObject(4, java.time.LocalDate.of(2026, 11, 8))
                ps.setObject(5, actor.id)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into report_version(id,report_id,version_no,state,snapshot,data_cutoff_at,submitted_at,created_by) values (?,?,1,'SUBMITTED','{}'::jsonb,?,?,?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, report)
                ps.setTimestamp(3, Timestamp.from(at))
                ps.setTimestamp(4, Timestamp.from(at))
                ps.setObject(5, actor.id)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into report_version(id,report_id,version_no,state,snapshot,data_cutoff_at,submitted_at,approved_at,created_by) values (?,?,2,'APPROVED','{}'::jsonb,?,?,?,?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, report)
                ps.setTimestamp(3, Timestamp.from(at))
                ps.setTimestamp(4, Timestamp.from(at))
                ps.setTimestamp(5, Timestamp.from(at))
                ps.setObject(6, actor.id)
                ps.executeUpdate()
            }
        }
    }

    private fun scalarInt(
        sql: String,
        vararg params: Any,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun scalarLong(
        sql: String,
        vararg params: Any,
    ): Long =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun scalarString(
        sql: String,
        vararg params: Any,
    ): String =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun scalarObject(
        sql: String,
        vararg params: Any,
    ): Any? =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                params.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
                ps.executeQuery().use {
                    if (it.next()) it.getObject(1) else null
                }
            }
        }
}
