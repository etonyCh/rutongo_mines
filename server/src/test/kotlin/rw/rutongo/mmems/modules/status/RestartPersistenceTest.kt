package rw.rutongo.mmems.modules.status

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.shared.enums.OperationalStatus
import rw.rutongo.mmems.shared.enums.Role
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class RestartPersistenceTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `status history and projection survive an application restart`() {
        val first = connection()
        Flyway
            .configure()
            .dataSource(first)
            .load()
            .migrate()
        val site = createSite(first, "RESTART")
        val actor = createActor(first, Role.MECHANIC, listOf(site))
        val type = createType(first)
        val equipment = createEquipment(first, type, site)
        val service = StatusService(first)
        val key = "restart-${UUID.randomUUID()}"

        val recorded = service.record(actor, equipment, 0, key, OperationalStatus.DOWN, "Hydraulic pressure loss", null, null, null, null, null, Instant.parse("2026-11-05T08:00:00Z"))
        first.close()

        val second = connection()
        try {
            Flyway
                .configure()
                .dataSource(second)
                .load()
                .migrate()
            val restarted = StatusService(second)
            val history = restarted.history(actor, equipment)
            assertEquals(listOf(recorded.event.id), history.map { it.id })
            assertEquals("DOWN", history.single().newStatus)
            assertEquals("DOWN", currentStatus(second, equipment))
            assertTrue(restarted.activeProblems(actor).any { it.equipmentId == equipment.toString() })

            val replayed = restarted.record(actor, equipment, 999, key, OperationalStatus.DOWN, "Hydraulic pressure loss", null, null, null, null, null, Instant.parse("2026-11-05T08:00:00Z"))
            assertEquals(recorded.event.id, replayed.event.id)
            assertEquals(1, eventCount(second, equipment))
        } finally {
            second.close()
        }
    }

    private fun connection() =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
            },
        )

    private fun createSite(
        dataSource: HikariDataSource,
        prefix: String,
    ): UUID {
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
        dataSource: HikariDataSource,
        role: Role,
        siteIds: List<UUID>,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "restart-user-$id")
                ps.setString(3, "Restart User")
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
        return Principal(id, "restart-user-$id", role, false)
    }

    private fun createType(dataSource: HikariDataSource): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "Restart type $id")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createEquipment(
        dataSource: HikariDataSource,
        typeId: UUID,
        siteId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Restart Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, siteId)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun currentStatus(
        dataSource: HikariDataSource,
        equipmentId: UUID,
    ): String? =
        dataSource.connection.use { c ->
            c.prepareStatement("select current_status from equipment where id=?").use { ps ->
                ps.setObject(1, equipmentId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    private fun eventCount(
        dataSource: HikariDataSource,
        equipmentId: UUID,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("select count(*) from equipment_status_event where equipment_id=?").use { ps ->
                ps.setObject(1, equipmentId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
}
