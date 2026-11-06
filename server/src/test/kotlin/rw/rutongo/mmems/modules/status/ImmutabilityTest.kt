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
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.shared.enums.OperationalStatus
import rw.rutongo.mmems.shared.enums.Role
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImmutabilityTest {
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
                    ps.setString(1, "Immutable type ${UUID.randomUUID()}")
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
    fun `equipment status events cannot be updated or deleted`() {
        val site = createSite()
        val actor = createActor(site)
        val equipment = createEquipment(site)
        val event = service.record(actor, equipment, 0, "immutable-${UUID.randomUUID()}", OperationalStatus.DOWN, "Hydraulic leak", null, null, null, null, null, Instant.parse("2026-11-05T08:00:00Z")).event

        val updateError =
            assertFailsWith<SQLException> {
                dataSource.connection.use { c ->
                    c.prepareStatement("update equipment_status_event set new_status='OPERATIONAL' where id=?").use { ps ->
                        ps.setObject(1, UUID.fromString(event.id))
                        ps.executeUpdate()
                    }
                }
            }
        assertEquals("55000", updateError.sqlState)

        val deleteError =
            assertFailsWith<SQLException> {
                dataSource.connection.use { c ->
                    c.prepareStatement("delete from equipment_status_event where id=?").use { ps ->
                        ps.setObject(1, UUID.fromString(event.id))
                        ps.executeUpdate()
                    }
                }
            }
        assertEquals("55000", deleteError.sqlState)
    }

    private fun createSite(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state) values (?,?,?,'ACTIVE')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "IMMUTABLE-${id.toString().take(8)}".uppercase())
                ps.setString(3, "Immutable Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(siteId: UUID): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "immutable-user-$id")
                ps.setString(3, "Immutable User")
                ps.setString(4, "EID-$id")
                ps.setString(5, Role.MECHANIC.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, siteId)
                ps.executeUpdate()
            }
        }
        return Principal(id, "immutable-user-$id", Role.MECHANIC, false)
    }

    private fun createEquipment(siteId: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Immutable Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, siteId)
                ps.executeUpdate()
            }
        }
        return id
    }
}
