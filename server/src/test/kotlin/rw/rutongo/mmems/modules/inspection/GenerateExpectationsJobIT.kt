package rw.rutongo.mmems.modules.inspection

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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenerateExpectationsJobIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var job: GenerateExpectationsJob
    private lateinit var typeId: UUID
    private lateinit var dayShiftId: UUID

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 3
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        job = GenerateExpectationsJob(dataSource)
        typeId =
            dataSource.connection.use { c ->
                c.prepareStatement("insert into equipment_type(name) values (?) returning id").use { ps ->
                    ps.setString(1, "Generation type ${UUID.randomUUID()}")
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
        dayShiftId =
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
    fun `opening shift generates one expectation without selecting equipment twice`() {
        val site = createSite("GENERATE")
        val equipment = createEquipment(site)
        createSchedule(site)
        val now = Instant.parse("2026-11-05T05:00:00Z")

        val first = job.run(now)
        val second = job.run(now)

        assertTrue(first.inserted >= 1)
        assertEquals(0, second.inserted)
        assertEquals(1, expectationCount(equipment))
        assertEquals(LocalDate.parse("2026-11-05"), reportDate(equipment))
        assertEquals("NOT_STARTED", state(equipment))
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

    private fun createEquipment(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id,lifecycle_state,requires_daily_inspection) values (?,?,?,?,?,?,'ACTIVE',true)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Generated Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createSchedule(site: UUID) {
        dataSource.connection.use { c ->
            c.prepareStatement("insert into shift_schedule(shift_id,site_id,start_local,end_local,inspection_window_minutes,effective_from) values (?,?,'06:00','18:00',120,'2026-01-01')").use { ps ->
                ps.setObject(1, dayShiftId)
                ps.setObject(2, site)
                ps.executeUpdate()
            }
        }
    }

    private fun expectationCount(equipment: UUID): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("select count(*) from inspection_expectation where equipment_id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun reportDate(equipment: UUID): LocalDate =
        dataSource.connection.use { c ->
            c.prepareStatement("select report_date from inspection_expectation where equipment_id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getObject(1, LocalDate::class.java)
                }
            }
        }

    private fun state(equipment: UUID): String =
        dataSource.connection.use { c ->
            c.prepareStatement("select state from inspection_expectation where equipment_id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }
}
