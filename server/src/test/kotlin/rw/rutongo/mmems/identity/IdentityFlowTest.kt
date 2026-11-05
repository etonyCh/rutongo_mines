package rw.rutongo.mmems.identity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.shared.enums.Role
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class IdentityFlowTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `bootstrap login password change site scope and refresh replay protection`() {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 3
        })
        try {
            Flyway.configure().dataSource(dataSource).load().migrate()
            val identity = IdentityService(dataSource, TokenSigner.createForTests())
            val initialPassword = "CorrectHorseBattery1".toCharArray()
            identity.bootstrapAdmin("admin", "Rutongo Admin", "EID-ADMIN", initialPassword)
            assertFailsWithCode("ADMIN_ALREADY_EXISTS") {
                identity.bootstrapAdmin("admin-2", "Second Admin", "EID-ADMIN-2", "AnotherCorrectPassword1".toCharArray())
            }

            val adminId = dataSource.connection.use { c ->
                c.prepareStatement("select id from app_user where username='admin'").use { ps ->
                    ps.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) }
                }
            }
            val firstSite = dataSource.connection.use { c ->
                c.prepareStatement("select id from site order by code limit 1").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getObject(1, UUID::class.java) } }
            }
            val secondSite = dataSource.connection.use { c ->
                c.prepareStatement("select id from site where id<>? order by code limit 1").use { ps -> ps.setObject(1,firstSite); ps.executeQuery().use { rs -> rs.next(); rs.getObject(1,UUID::class.java) } }
            }
            val mechanic = identity.createUser(adminId,"mechanic","Rutongo Mechanic","EID-MECH",Role.MECHANIC,"MechanicPassword1".toCharArray(),listOf(firstSite))
            assertTrue(identity.canAccess(UUID.fromString(mechanic.id),Role.MECHANIC,firstSite))
            assertFalse(identity.canAccess(UUID.fromString(mechanic.id),Role.MECHANIC,secondSite))
            assertTrue(identity.canAccess(adminId,Role.ADMINISTRATOR,secondSite))

            val firstSession = identity.login("admin",initialPassword,"WEB",null)
            assertNotNull(firstSession)
            assertTrue(firstSession.mustChangePassword)
            val rotated = identity.refresh(firstSession.refreshToken,"WEB",null)
            assertNotNull(rotated)
            assertNull(identity.refresh(firstSession.refreshToken,"WEB",null))
            assertNull(identity.refresh(rotated.refreshToken,"WEB",null))

            val principal = identity.currentPrincipal(adminId)
            assertNotNull(principal)
            identity.changePassword(principal,initialPassword,"NewAdminPassword2".toCharArray())
            assertNull(identity.login("admin","CorrectHorseBattery1".toCharArray(),"WEB",null))
            val signedIn = identity.login("admin","NewAdminPassword2".toCharArray(),"WEB",null)
            assertNotNull(signedIn)
            assertFalse(signedIn.mustChangePassword)
        } finally {
            dataSource.close()
        }
    }

    private fun assertFailsWithCode(expected: String, action: () -> Unit) {
        val error = try { action(); error("Expected AuthFailure($expected)") } catch (e: AuthFailure) { e }
        assertEquals(expected,error.code)
    }
}
