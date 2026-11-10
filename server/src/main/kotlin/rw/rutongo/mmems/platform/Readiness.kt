package rw.rutongo.mmems.platform

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

data class ReadinessReport(
    val ready: Boolean,
    val checks: Map<String, String>,
)

@Serializable
data class ReadinessResponse(
    val status: String,
    val checks: Map<String, String>,
)

class Readiness(
    private val dataSource: DataSource,
    private val storageDirectory: Path,
) {
    private val started = AtomicBoolean(false)

    fun markStarted() = started.set(true)

    fun markStopped() = started.set(false)

    fun check(): ReadinessReport {
        val checks =
            linkedMapOf(
                "database" to database(),
                "storage" to storage(),
                "scheduler" to if (started.get()) "ok" else "starting",
            )
        return ReadinessReport(checks.values.all { it == "ok" }, checks)
    }

    private fun database(): String =
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select 1").use { result -> if (result.next()) "ok" else "unavailable" }
                }
            }
        }.getOrElse { "unavailable" }

    private fun storage(): String =
        runCatching {
            if (Files.isDirectory(storageDirectory) && Files.isReadable(storageDirectory) && Files.isWritable(storageDirectory)) "ok" else "unavailable"
        }.getOrElse { "unavailable" }
}
