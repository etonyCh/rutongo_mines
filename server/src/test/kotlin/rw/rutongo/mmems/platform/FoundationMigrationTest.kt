package rw.rutongo.mmems.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class FoundationMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `migration seeds sites and audit rows are immutable and hash chained`() {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        })
        try {
            Flyway.configure().dataSource(dataSource).load().migrate()
            dataSource.connection.use { c ->
                c.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from site").use { rs -> rs.next(); assertEquals(6,rs.getInt(1)) }
                    statement.executeUpdate("insert into audit_event(action,entity_type,entity_id,occurred_at,metadata,event_hash) values ('TEST_ONE','test','1',now(),'{}',decode('00','hex'))")
                    statement.executeUpdate("insert into audit_event(action,entity_type,entity_id,occurred_at,metadata,event_hash) values ('TEST_TWO','test','2',now(),'{}',decode('00','hex'))")
                    statement.executeQuery("select previous_hash,event_hash from audit_event where action in ('TEST_ONE','TEST_TWO') order by audit_sequence").use { rs ->
                        assertTrue(rs.next()); assertNull(rs.getBytes("previous_hash")); val firstHash=rs.getBytes("event_hash"); assertNotNull(firstHash)
                        assertTrue(rs.next()); assertEquals(firstHash.toList(),rs.getBytes("previous_hash").toList()); assertTrue(rs.getBytes("event_hash").toList()!=firstHash.toList())
                    }
                }
                val immutableFailure=try {
                    c.createStatement().use { it.executeUpdate("update audit_event set action='TAMPERED' where action='TEST_ONE'") }
                    null
                } catch(e: SQLException) { e }
                assertNotNull(immutableFailure)
                assertEquals("55000",immutableFailure.sqlState)
            }
        } finally { dataSource.close() }
    }
}
