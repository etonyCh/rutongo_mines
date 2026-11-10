package rw.rutongo.mmems.platform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "fatJar.path", matches = ".+")
class FatJarStartupTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fat jar starts and runs all Flyway migrations`() {
        val jar = Path.of(System.getProperty("fatJar.path")).toAbsolutePath()
        assertTrue(Files.isRegularFile(jar), "Expected fat jar at $jar")

        val port = ServerSocket(0).use { it.localPort }
        val log = tempDir.resolve("server.log")
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                jar.toString(),
            ).apply {
                redirectErrorStream(true)
                redirectOutput(log.toFile())
                environment()["APP_ENV"] = "test"
                environment()["DATABASE_URL"] = postgres.jdbcUrl
                environment()["DATABASE_USER"] = postgres.username
                environment()["DATABASE_PASSWORD"] = postgres.password
                environment()["PORT"] = port.toString()
                environment()["BLOB_STORAGE_DIR"] = tempDir.resolve("blobs").toString()
                environment()["HSTS_ENABLED"] = "true"
            }.start()

        try {
            val ready = awaitReady(process, port, log)
            assertTrue(ready.body().contains("\"status\":\"ready\""), "Unexpected readiness response: ${ready.body()}")
            assertSecurityHeaders(ready)
            assertMigrated()
        } finally {
            stop(process)
        }
    }

    private fun assertSecurityHeaders(response: HttpResponse<String>) {
        val headers = response.headers()
        assertEquals("nosniff", headers.firstValue("X-Content-Type-Options").orElse(null))
        assertEquals("DENY", headers.firstValue("X-Frame-Options").orElse(null))
        val hsts = headers.firstValue("Strict-Transport-Security").orElse("")
        assertTrue(hsts.startsWith("max-age=31536000"))
    }

    private fun awaitReady(
        process: Process,
        port: Int,
        log: Path,
    ): HttpResponse<String> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/readyz"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
        val deadline = Instant.now().plusSeconds(90)
        var lastFailure: Exception? = null
        while (Instant.now().isBefore(deadline)) {
            check(process.isAlive) { "Fat jar exited before becoming ready:\n${readLog(log)}" }
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) return response
                lastFailure = IllegalStateException("Readiness returned ${response.statusCode()}: ${response.body()}")
            } catch (e: Exception) {
                lastFailure = e
            }
            Thread.sleep(250)
        }
        fail("Fat jar did not become ready. Last failure: ${lastFailure?.message}\n${readLog(log)}")
    }

    private fun assertMigrated() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select version, success from flyway_schema_history order by installed_rank desc limit 1").use { rs ->
                    assertTrue(rs.next(), "Flyway history is empty")
                    assertEquals("014", rs.getString("version"))
                    assertTrue(rs.getBoolean("success"))
                }
                statement.executeQuery("select count(*) from scheduled_tasks").use { rs ->
                    assertTrue(rs.next())
                    assertTrue(rs.getInt(1) > 0, "Schedulers did not register tasks")
                }
            }
        }
    }

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private fun readLog(log: Path): String = if (Files.exists(log)) Files.readString(log) else "(no server output)"

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }
}
