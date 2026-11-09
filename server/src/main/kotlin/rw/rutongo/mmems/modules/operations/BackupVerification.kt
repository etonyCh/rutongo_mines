@file:Suppress("MagicNumber")

package rw.rutongo.mmems.modules.operations

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.io.path.isRegularFile

sealed interface BackupVerificationResult {
    data class Verified(
        val completedAt: Instant,
    ) : BackupVerificationResult

    data object NotConfigured : BackupVerificationResult

    data class Invalid(
        val reason: String,
    ) : BackupVerificationResult
}

class BackupVerification(
    private val markerFile: Path? = environmentMarker(),
    private val maxAge: Duration = environmentMaxAge(),
    private val now: () -> Instant = Instant::now,
) {
    fun run(): BackupVerificationResult {
        val marker = markerFile
        return when {
            marker == null -> BackupVerificationResult.NotConfigured
            !marker.isRegularFile() -> BackupVerificationResult.Invalid("marker file not found: $marker")
            else ->
                runCatching { verify(marker) }
                    .getOrElse { BackupVerificationResult.Invalid("cannot verify backup marker: ${it.message}") }
        }
    }

    private fun verify(marker: Path): BackupVerificationResult {
        val input = json.decodeFromString<BackupMarker>(Files.readString(marker))
        val completedAt = Instant.parse(input.completedAt)
        return when {
            completedAt.isAfter(now().plus(CLOCK_SKEW)) -> BackupVerificationResult.Invalid("backup marker is in the future")
            completedAt.plus(maxAge).isBefore(now()) -> BackupVerificationResult.Invalid("backup marker is older than $maxAge")
            else -> verifyArtifact(marker, input) ?: BackupVerificationResult.Verified(completedAt)
        }
    }

    private fun verifyArtifact(
        marker: Path,
        input: BackupMarker,
    ): BackupVerificationResult? {
        val raw = input.path ?: return null
        val artifact = (marker.parent ?: Path.of(".")).resolve(raw).normalize()
        return when {
            !artifact.isRegularFile() -> BackupVerificationResult.Invalid("backup artifact not found: $artifact")
            input.sha256 == null -> null
            sha256(artifact).equals(input.sha256, ignoreCase = true) -> null
            else -> BackupVerificationResult.Invalid("backup checksum mismatch for $artifact")
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class BackupMarker(
        val completedAt: String,
        val path: String? = null,
        val sha256: String? = null,
    )

    private companion object {
        const val MARKER_ENV = "BACKUP_VERIFICATION_MARKER"
        const val MAX_AGE_ENV = "BACKUP_VERIFICATION_MAX_AGE_HOURS"
        const val DEFAULT_MAX_AGE_HOURS = 48L
        const val BUFFER_SIZE = 8192
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        val json = Json { ignoreUnknownKeys = true }

        fun environmentMarker(): Path? =
            System
                .getenv(MARKER_ENV)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(Path::of)

        fun environmentMaxAge(): Duration = Duration.ofHours(System.getenv(MAX_AGE_ENV)?.toLongOrNull() ?: DEFAULT_MAX_AGE_HOURS)
    }
}
