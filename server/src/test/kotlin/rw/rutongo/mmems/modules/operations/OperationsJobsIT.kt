@file:Suppress("LongParameterList")

package rw.rutongo.mmems.modules.operations

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
import rw.rutongo.mmems.modules.notification.LowStockScan
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsJobsIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        private val now = Instant.parse("2026-11-09T10:00:00Z")
        private val old = now.minus(Duration.ofDays(40))
    }

    private lateinit var dataSource: HikariDataSource

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
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `low stock scan emits one daily notification per low part`() {
        val low = createPart("LOW-1", minStock = 10, onHand = 3)
        createPart("OK-1", minStock = 2, onHand = 5)
        val scan = LowStockScan(dataSource) { now }

        assertEquals(1, scan.run())
        assertEquals(0, scan.run())

        dataSource.connection.use { c ->
            c.prepareStatement("select topic,payload,occurred_at from outbox_message where topic='LOW_STOCK'").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val payload = rs.getString("payload")
                    assertTrue(payload.contains(low.toString()))
                    assertTrue(payload.contains("lowstock:$low:2026-11-09"))
                    assertFalse(rs.next())
                }
            }
        }
    }

    @Test
    fun `token cleanup removes expired revoked used and old rate buckets only`() {
        val user = createUser()
        val active = UUID.randomUUID()
        dataSource.connection.use { c ->
            insertToken(c, user, active, issued = now.minus(Duration.ofMinutes(5)), expires = now.plus(Duration.ofDays(1)))
            insertToken(c, user, UUID.randomUUID(), issued = old, expires = old)
            insertToken(c, user, UUID.randomUUID(), issued = old, expires = now.plus(Duration.ofDays(1)), revoked = old)
            insertToken(c, user, UUID.randomUUID(), issued = old, expires = now.plus(Duration.ofDays(1)), used = old)
            c.prepareStatement("insert into login_rate_bucket(bucket_key,window_started_at,attempts) values ('old',?,1),('new',?,1)").use { ps ->
                ps.setTimestamp(1, Timestamp.from(old))
                ps.setTimestamp(2, Timestamp.from(now))
                ps.executeUpdate()
            }
        }

        assertEquals(4, TokenCleanup(dataSource, now = { now }).run())

        dataSource.connection.use { c ->
            c.prepareStatement("select id from refresh_token").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(active, rs.getObject("id", UUID::class.java))
                    assertFalse(rs.next())
                }
            }
            c.prepareStatement("select bucket_key from login_rate_bucket").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("new", rs.getString("bucket_key"))
                    assertFalse(rs.next())
                }
            }
        }
    }

    @Test
    fun `audit chain verification detects a broken hash`() {
        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("truncate audit_event") }
            insertAudit(c, "CREATE", "part", "one")
            insertAudit(c, "UPDATE", "part", "one")
        }
        val verifier = AuditChainVerify(dataSource)
        assertTrue(verifier.run().valid)
        assertEquals(2, verifier.run().checked)

        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("alter table audit_event disable trigger audit_event_hash_chain") }
            c.prepareStatement("insert into audit_event(action,entity_type,entity_id,occurred_at,event_hash) values ('DELETE','part','one',?,decode('00','hex'))").use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.executeUpdate()
            }
            c.createStatement().use { it.execute("alter table audit_event enable trigger audit_event_hash_chain") }
        }
        val broken = verifier.run()
        assertFalse(broken.valid)
        assertTrue(broken.firstBrokenSequence != null)
    }

    private fun createPart(
        number: String,
        minStock: Int,
        onHand: Int,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into part(id,part_number,description,min_stock) values (?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, number)
                ps.setString(3, "Part $number")
                ps.setInt(4, minStock)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into part_stock(part_id,location,on_hand) values (?, 'MAIN', ?)").use { ps ->
                ps.setObject(1, id)
                ps.setInt(2, onHand)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createUser(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,'ADMINISTRATOR','not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ops-$id")
                ps.setString(3, "Operations User")
                ps.setString(4, "EID-$id")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun insertToken(
        c: java.sql.Connection,
        user: UUID,
        id: UUID,
        issued: Instant,
        expires: Instant,
        revoked: Instant? = null,
        used: Instant? = null,
    ) {
        c
            .prepareStatement(
                "insert into refresh_token(id,user_id,token_hash,family_id,issued_at,expires_at,revoked_at,used_at,client_type) values (?,?,?,?,?,?,?,?,'WEB')",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, user)
                ps.setBytes(3, id.toString().toByteArray())
                ps.setObject(4, UUID.randomUUID())
                ps.setTimestamp(5, Timestamp.from(issued))
                ps.setTimestamp(6, Timestamp.from(expires))
                ps.setTimestamp(7, revoked?.let { Timestamp.from(it) })
                ps.setTimestamp(8, used?.let { Timestamp.from(it) })
                ps.executeUpdate()
            }
    }

    private fun insertAudit(
        c: java.sql.Connection,
        action: String,
        entityType: String,
        entityId: String,
    ) {
        c.prepareStatement("insert into audit_event(action,entity_type,entity_id,occurred_at) values (?,?,?,?)").use { ps ->
            ps.setString(1, action)
            ps.setString(2, entityType)
            ps.setString(3, entityId)
            ps.setTimestamp(4, Timestamp.from(now))
            ps.executeUpdate()
        }
    }
}
