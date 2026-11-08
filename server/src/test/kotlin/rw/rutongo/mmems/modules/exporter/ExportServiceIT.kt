@file:Suppress("LongMethod")

package rw.rutongo.mmems.modules.exporter

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
import rw.rutongo.mmems.shared.enums.ExportFormat
import rw.rutongo.mmems.shared.enums.Role
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportServiceIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: ExportService
    private val now = Instant.parse("2026-11-08T10:00:00Z")

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
        service = ExportService(dataSource) { now }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `all declared datasets render as csv and xlsx and produce an audit event`() {
        val manager = createActor(Role.MANAGER)
        val datasets = service.datasets(manager)
        assertEquals(10, datasets.size)
        datasets.forEach { dataset ->
            val csv = service.export(manager, dataset.dataset, ExportFormat.CSV, null, null, null)
            assertTrue(csv.bytes.isNotEmpty(), "CSV for ${dataset.dataset} should not be empty")
            assertTrue(csv.fileName.startsWith("rutongo-${dataset.dataset}-"))
        }
        val xlsx = service.export(manager, "equipment", ExportFormat.XLSX, null, null, null)
        assertTrue(xlsx.bytes.size > 100)
        assertEquals(11, scalarInt("select count(*) from audit_event where action='DATA_EXPORTED'"))
    }

    @Test
    fun `roles without export permission are rejected`() {
        val mechanic = createActor(Role.MECHANIC)
        assertFailsWith<AuthFailure> { service.datasets(mechanic) }
    }

    private fun createActor(role: Role): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "export-$id")
                ps.setString(3, "Export User")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
        }
        return Principal(id, "export-$id", role, false)
    }

    private fun scalarInt(sql: String): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
