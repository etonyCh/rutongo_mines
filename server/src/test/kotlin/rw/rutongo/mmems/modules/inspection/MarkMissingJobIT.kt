@file:Suppress("LongParameterList")

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
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkMissingJobIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var job: MarkMissingJob
    private lateinit var typeId: UUID
    private lateinit var shiftId: UUID
    private val now = Instant.parse("2026-11-05T10:00:00Z")

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
        job = MarkMissingJob(dataSource)
        typeId =
            dataSource.connection.use { c ->
                c.prepareStatement("insert into equipment_type(name) values (?) returning id").use { ps ->
                    ps.setString(1, "Missing type ${UUID.randomUUID()}")
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
        shiftId =
            dataSource.connection.use { c ->
                c.prepareStatement("select id from shift where code='NIGHT'").use { ps ->
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
    fun `open expectations past their window are marked missing once and emit one outbox event`() {
        val site = createSite("MISSING")
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), now.minusSeconds(7200), now.minusSeconds(3600), "NOT_STARTED")

        val first = job.run(now)
        val second = job.run(now)

        assertTrue(expectation in first.expectationIds)
        assertTrue(second.expectationIds.isEmpty())
        assertEquals("MISSING", state(expectation))
        assertNotNull(missingAt(expectation))
        assertEquals(1, outboxCount(expectation))
    }

    @Test
    fun `hard backstop closes an expectation left open beyond twenty four hours`() {
        val site = createSite("BACKSTOP")
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-04"), now.minusSeconds(90_000), now.plusSeconds(3600), "IN_PROGRESS")

        job.run(now)

        assertEquals("MISSING", state(expectation))
    }

    @Test
    fun `completed expectations are never changed by the missing job`() {
        val site = createSite("COMPLETED")
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), now.minusSeconds(7200), now.minusSeconds(3600), "COMPLETED")

        job.run(now)

        assertEquals("COMPLETED", state(expectation))
        assertEquals(0, outboxCount(expectation))
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
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Missing Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createExpectation(
        site: UUID,
        equipment: UUID,
        reportDate: LocalDate,
        start: Instant,
        end: Instant,
        state: String,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into inspection_expectation(id,equipment_id,site_id,shift_id,report_date,window_start,window_end,state) values (?,?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, equipment)
                ps.setObject(3, site)
                ps.setObject(4, shiftId)
                ps.setObject(5, reportDate)
                ps.setTimestamp(6, Timestamp.from(start))
                ps.setTimestamp(7, Timestamp.from(end))
                ps.setString(8, state)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun state(id: UUID): String =
        dataSource.connection.use { c ->
            c.prepareStatement("select state from inspection_expectation where id=?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun missingAt(id: UUID): Instant? =
        dataSource.connection.use { c ->
            c.prepareStatement("select missing_marked_at from inspection_expectation where id=?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getTimestamp(1)?.toInstant()
                }
            }
        }

    private fun outboxCount(id: UUID): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("select count(*) from outbox_message where topic='INSPECTION_MISSING' and payload->>'expectationId'=?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
