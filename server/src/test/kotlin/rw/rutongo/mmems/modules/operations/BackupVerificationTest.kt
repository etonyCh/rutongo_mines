package rw.rutongo.mmems.modules.operations

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackupVerificationTest {
    @TempDir
    lateinit var temp: Path

    private val now = Instant.parse("2026-11-09T10:00:00Z")

    @Test
    fun `verifies fresh marker and checksum`() {
        val backup = temp.resolve("backup.dump")
        Files.writeString(backup, "backup")
        val marker = temp.resolve("backup.json")
        Files.writeString(
            marker,
            """{"completedAt":"2026-11-09T09:00:00Z","path":"backup.dump","sha256":"${sha256(backup)}"}""",
        )

        val result = BackupVerification(marker, Duration.ofHours(24)) { now }.run()

        assertIs<BackupVerificationResult.Verified>(result)
        assertEquals(Instant.parse("2026-11-09T09:00:00Z"), result.completedAt)
    }

    @Test
    fun `rejects stale and checksum-invalid backups`() {
        val backup = temp.resolve("backup.dump")
        Files.writeString(backup, "backup")
        val marker = temp.resolve("backup.json")

        Files.writeString(marker, """{"completedAt":"2026-11-07T09:00:00Z","path":"backup.dump"}""")
        val stale = BackupVerification(marker, Duration.ofHours(24)) { now }.run()
        assertIs<BackupVerificationResult.Invalid>(stale)

        Files.writeString(marker, """{"completedAt":"2026-11-09T09:00:00Z","path":"backup.dump","sha256":"bad"}""")
        val mismatch = BackupVerification(marker, Duration.ofHours(24)) { now }.run()
        assertIs<BackupVerificationResult.Invalid>(mismatch)
    }

    @Test
    fun `reports missing marker configuration and missing artifact`() {
        assertIs<BackupVerificationResult.NotConfigured>(BackupVerification(null) { now }.run())

        val marker = temp.resolve("backup.json")
        Files.writeString(marker, """{"completedAt":"2026-11-09T09:00:00Z","path":"missing.dump"}""")
        val missing = BackupVerification(marker) { now }.run()
        assertIs<BackupVerificationResult.Invalid>(missing)
    }

    private fun sha256(file: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
}
