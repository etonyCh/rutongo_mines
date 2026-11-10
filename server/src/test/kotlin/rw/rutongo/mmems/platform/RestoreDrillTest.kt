package rw.rutongo.mmems.platform

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class RestoreDrillTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `database dump restores into an empty database`() {
        migrateAndSeed()
        dumpDatabase()
        createRestoreDatabase()
        restoreDump()
        assertRestoredData()
    }

    private fun migrateAndSeed() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("create table restore_probe(value text not null)")
                statement.executeUpdate("insert into restore_probe(value) values ('preserved')")
            }
        }
    }

    private fun dumpDatabase() {
        val dump = tempDir.resolve("mmems.dump")
        val dumpResult =
            postgres.execInContainer(
                "pg_dump",
                "-U",
                postgres.username,
                "-d",
                postgres.databaseName,
                "-Fc",
                "-f",
                "/tmp/mmems.dump",
            )
        assertEquals(0, dumpResult.exitCode, dumpResult.stderr)
        postgres.copyFileFromContainer("/tmp/mmems.dump", dump.toAbsolutePath().toString())
        assertTrue(Files.size(dump) > 0)
    }

    private fun createRestoreDatabase() {
        val create =
            postgres.execInContainer(
                "psql",
                "-U",
                postgres.username,
                "-d",
                "postgres",
                "-c",
                "create database mmems_restore",
            )
        assertEquals(0, create.exitCode, create.stderr)
    }

    private fun restoreDump() {
        val restore =
            postgres.execInContainer(
                "pg_restore",
                "-U",
                postgres.username,
                "-d",
                "mmems_restore",
                "--no-owner",
                "--no-privileges",
                "/tmp/mmems.dump",
            )
        assertEquals(0, restore.exitCode, restore.stderr)
    }

    private fun assertRestoredData() {
        val restoredUrl = postgres.jdbcUrl.replace("/${postgres.databaseName}", "/mmems_restore")
        DriverManager.getConnection(restoredUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select value from restore_probe").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("preserved", rs.getString(1))
                }
                statement.executeQuery("select count(*) from site").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(6, rs.getInt(1))
                }
                statement.executeQuery("select version from flyway_schema_history order by installed_rank desc limit 1").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("014", rs.getString(1))
                }
            }
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }
}
