package rw.rutongo.mmems.modules.photo

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.shared.enums.Role
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoAccessIT {
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
    fun `photo bytes are only returned to users with access to the photo site`() {
        val site = context.site("ACCESS")
        val other = context.site("OTHER")
        val mechanic = context.actor(Role.MECHANIC, listOf(site))
        val target = UUID.randomUUID()
        val photo = context.service.upload(mechanic, site, "INSPECTION_ANSWER", target, "EVIDENCE", "image/jpeg", PhotoTestContext.jpeg)

        val content = context.service.open(mechanic, UUID.fromString(photo.id))
        assertEquals("image/jpeg", content.mime)
        assertTrue(PhotoTestContext.jpeg.contentEquals(content.bytes))

        val outsider = context.actor(Role.MECHANIC, listOf(other))
        val denied = assertFailsWith<AuthFailure> { context.service.open(outsider, UUID.fromString(photo.id)) }
        assertEquals("SITE_NOT_AUTHORIZED", denied.code)
    }

    @Test
    fun `unknown photo ids are not disclosed`() {
        val site = context.site("UNKNOWN")
        val actor = context.actor(Role.MECHANIC, listOf(site))
        val error = assertFailsWith<AuthFailure> { context.service.open(actor, UUID.randomUUID()) }
        assertEquals("PHOTO_NOT_FOUND", error.code)
    }
}
