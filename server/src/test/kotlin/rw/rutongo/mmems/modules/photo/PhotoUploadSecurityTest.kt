package rw.rutongo.mmems.modules.photo

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.shared.enums.Role
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoUploadSecurityTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var context: PhotoTestContext

    @BeforeAll
    fun setUp() {
        context = PhotoTestContext(postgres)
    }

    @AfterAll
    fun tearDown() {
        context.close()
    }

    @Test
    fun `rejects files whose content is not a jpeg or png`() {
        val site = context.site("MAGIC")
        val actor = context.actor(Role.MECHANIC, listOf(site))
        assertFailsWith<IllegalArgumentException> {
            context.service.upload(actor, site, "INSPECTION_ANSWER", UUID.randomUUID(), "EVIDENCE", "image/png", "not an image".toByteArray())
        }
    }

    @Test
    fun `rejects a declared type that does not match the bytes`() {
        val site = context.site("MISMATCH")
        val actor = context.actor(Role.MECHANIC, listOf(site))
        assertFailsWith<IllegalArgumentException> {
            context.service.upload(actor, site, "INSPECTION_ANSWER", UUID.randomUUID(), "EVIDENCE", "image/png", PhotoTestContext.jpeg)
        }
    }

    @Test
    fun `rejects an upload for a site the user cannot access`() {
        val allowed = context.site("ALLOWED")
        val denied = context.site("DENIED")
        val actor = context.actor(Role.MECHANIC, listOf(allowed))
        val error =
            assertFailsWith<IllegalArgumentException> {
                context.service.upload(actor, denied, "INSPECTION_ANSWER", UUID.randomUUID(), "EVIDENCE", "image/jpeg", PhotoTestContext.jpeg)
            }
        assertEquals("SITE_NOT_AUTHORIZED", error.message)
    }

    @Test
    fun `rejects oversized files before storing them`() {
        val site = context.site("LARGE")
        val actor = context.actor(Role.MECHANIC, listOf(site))
        val bytes = ByteArray(PhotoService.MAX_BYTES + 1)
        PhotoTestContext.jpeg.copyInto(bytes)
        assertFailsWith<IllegalArgumentException> {
            context.service.upload(actor, site, "INSPECTION_ANSWER", UUID.randomUUID(), "EVIDENCE", "image/jpeg", bytes)
        }
    }

    @Test
    fun `accepts a valid jpeg and stores a digest and opaque key`() {
        val site = context.site("VALID")
        val actor = context.actor(Role.MECHANIC, listOf(site))
        val target = UUID.randomUUID()
        val view = context.service.upload(actor, site, "INSPECTION_ANSWER", target, "EVIDENCE", "image/jpeg", PhotoTestContext.jpeg)
        assertEquals("image/jpeg", view.mime)
        assertEquals(PhotoTestContext.jpeg.size, view.sizeBytes)
        assertEquals(target.toString(), view.targetId)
        assertTrue(view.sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
