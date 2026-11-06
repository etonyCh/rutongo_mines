package rw.rutongo.mmems.modules.status

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
import rw.rutongo.mmems.shared.enums.OperationalStatus
import rw.rutongo.mmems.shared.enums.Role
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusHistoryTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: StatusService
    private lateinit var typeId: UUID

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
        service = StatusService(dataSource)
        typeId =
            dataSource.connection.use { c ->
                c.prepareStatement("insert into equipment_type(name) values (?) returning id").use { ps ->
                    ps.setString(1, "History type ${UUID.randomUUID()}")
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }
            }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `history follows occurred_at order and keeps late events without regressing projection`() {
        val site = createSite("HISTORY")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        service.record(actor, equipment, 0, key(), OperationalStatus.OPERATIONAL, null, null, null, null, null, null, Instant.parse("2026-11-05T10:00:00Z"))
        val late = service.record(actor, equipment, 1, key(), OperationalStatus.DOWN, "Late breakdown", null, null, null, null, null, Instant.parse("2026-11-05T08:00:00Z"))

        assertFalse(late.projected)
        assertEquals(listOf("OPERATIONAL", "DOWN"), service.history(actor, equipment).map { it.newStatus })
        assertEquals("OPERATIONAL", currentStatus(equipment))
    }

    @Test
    fun `history requires current site access and filters events from unauthorized historical sites`() {
        val original = createSite("HISTORY-ORIGINAL")
        val destination = createSite("HISTORY-DESTINATION")
        val author = createActor(Role.MECHANIC, listOf(original))
        val equipment = createEquipment(original)
        service.record(author, equipment, 0, key(), OperationalStatus.DOWN, "Cable damage", null, null, null, null, null, Instant.parse("2026-11-05T08:00:00Z"))
        dataSource.connection.use { c ->
            c.prepareStatement("update equipment set site_id=? where id=?").use { ps ->
                ps.setObject(1, destination)
                ps.setObject(2, equipment)
                ps.executeUpdate()
            }
        }

        val destinationReader = createActor(Role.MECHANIC, listOf(destination))
        assertTrue(service.history(destinationReader, equipment).isEmpty())
        val originalReader = createActor(Role.MECHANIC, listOf(original))
        val failure = assertFailsWith<AuthFailure> { service.history(originalReader, equipment) }
        assertEquals("SITE_NOT_AUTHORIZED", failure.code)
        val bothSites = createActor(Role.MECHANIC, listOf(original, destination))
        assertEquals(1, service.history(bothSites, equipment).size)
    }

    private fun createSite(prefix: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state) values (?,?,?,'ACTIVE')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "$prefix-${id.toString().take(8)}".uppercase())
                ps.setString(3, "$prefix Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(
        role: Role,
        siteIds: List<UUID>,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "history-user-$id")
                ps.setString(3, "History User")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                siteIds.distinct().forEach { site ->
                    ps.setObject(1, id)
                    ps.setObject(2, site)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return Principal(id, "history-user-$id", role, false)
    }

    private fun createEquipment(siteId: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "History Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, siteId)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun key() = "history-${UUID.randomUUID()}"

    private fun currentStatus(equipmentId: UUID): String? =
        dataSource.connection.use { c ->
            c.prepareStatement("select current_status from equipment where id=?").use { ps ->
                ps.setObject(1, equipmentId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }
}
