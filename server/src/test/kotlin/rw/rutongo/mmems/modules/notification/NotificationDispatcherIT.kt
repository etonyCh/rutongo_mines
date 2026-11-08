@file:Suppress("LongMethod")

package rw.rutongo.mmems.modules.notification

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
import rw.rutongo.mmems.shared.api.PushDeviceRegistrationDto
import rw.rutongo.mmems.shared.enums.PushPlatform
import rw.rutongo.mmems.shared.enums.Role
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationDispatcherIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private var sent = 0

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
    fun `dispatches outbox notifications once and pushes registered devices`() {
        val site = createSite()
        val supervisor = createActor(Role.SUPERVISOR, site)
        val service = NotificationService(dataSource) { now }
        val device =
            service.registerDevice(
                supervisor,
                PushDeviceRegistrationDto("test-device-token-12345", PushPlatform.ANDROID),
            )
        assertNotNull(device.id)
        val dispatcher = NotificationDispatcher(dataSource, RecordingNotifier { sent++ }) { now }
        val expectation = UUID.randomUUID()
        outbox("INSPECTION_MISSING", """{"expectationId":"$expectation","siteId":"$site","dedupeKey":"missing:$expectation"}""")

        val first = dispatcher.dispatchOnce()
        assertEquals(1, first.processed)
        assertEquals(1, first.notifications)
        assertEquals(1, first.pushed)
        assertEquals(1, sent)
        val listed = service.list(supervisor)
        assertEquals(1, listed.items.size)
        assertEquals(1, listed.unreadCount)
        assertEquals(
            "INSPECTION_MISSING",
            listed.items
                .single()
                .type.name,
        )

        val read = service.markRead(supervisor, UUID.fromString(listed.items.single().id))
        assertNotNull(read.readAt)
        assertEquals(0, service.list(supervisor).unreadCount)

        outbox("INSPECTION_MISSING", """{"expectationId":"$expectation","siteId":"$site","dedupeKey":"missing:$expectation"}""")
        val duplicate = dispatcher.dispatchOnce()
        assertEquals(1, duplicate.processed)
        assertEquals(0, duplicate.notifications)
        assertEquals(1, service.list(supervisor).items.size)
    }

    @Test
    fun `service due scan feeds the outbox once per equipment service status`() {
        val site = createSite()
        val supervisor = createActor(Role.SUPERVISOR, site)
        seedServiceDue(site, supervisor)
        val scan = ServiceDueScan(dataSource) { now }
        assertEquals(1, scan.run())
        assertEquals(0, scan.run())
        val dispatcher = NotificationDispatcher(dataSource, RecordingNotifier { }) { now }
        val result = dispatcher.dispatchOnce()
        assertEquals(1, result.notifications)
        val listed = NotificationService(dataSource) { now }.list(supervisor)
        assertEquals(
            "SERVICE_DUE",
            listed.items
                .single()
                .type.name,
        )
    }

    @Test
    fun `events after cursor returns only new notifications`() {
        val site = createSite()
        val supervisor = createActor(Role.SUPERVISOR, site)
        val service = NotificationService(dataSource) { now }
        val dispatcher = NotificationDispatcher(dataSource, RecordingNotifier { }) { now }
        val expectation = UUID.randomUUID()
        outbox("INSPECTION_MISSING", """{"expectationId":"$expectation","siteId":"$site","dedupeKey":"missing:$expectation"}""")
        dispatcher.dispatchOnce()
        val cursor = now.minusSeconds(1)
        val events = service.eventsAfter(supervisor, cursor, UUID(0, 0))
        assertTrue(events.size == 1)
        assertEquals(expectation.toString(), events.single().payload["expectationId"])
    }

    private fun createSite(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state) values (?,?,?,'ACTIVE')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "NOTIF-${id.toString().take(8)}")
                ps.setString(3, "Notification Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(
        role: Role,
        site: UUID,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "notif-$id")
                ps.setString(3, "Notification Supervisor")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, site)
                ps.executeUpdate()
            }
        }
        return Principal(id, "notif-$id", role, false)
    }

    private fun seedServiceDue(
        site: UUID,
        actor: Principal,
    ) {
        dataSource.connection.use { c ->
            val typeId = UUID.randomUUID()
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, typeId)
                ps.setString(2, "Notif type ${typeId.toString().take(8)}")
                ps.executeUpdate()
            }
            val equipmentId = UUID.randomUUID()
            c
                .prepareStatement(
                    "insert into equipment(id,asset_id,equipment_code,name,type_id,site_id,engine_hours_current) values (?,?,?,?,?,?,?)",
                ).use { ps ->
                    ps.setObject(1, equipmentId)
                    ps.setString(2, "ASSET-$equipmentId")
                    ps.setString(3, "EQ-$equipmentId")
                    ps.setString(4, "Service due equipment")
                    ps.setObject(5, typeId)
                    ps.setObject(6, site)
                    ps.setBigDecimal(7, java.math.BigDecimal("980.0"))
                    ps.executeUpdate()
                }
            val serviceTypeId = UUID.randomUUID()
            c.prepareStatement("insert into service_type(id,code,name,hours_based) values (?,?,?,true)").use { ps ->
                ps.setObject(1, serviceTypeId)
                ps.setString(2, "SERVICE-$serviceTypeId")
                ps.setString(3, "Service due type")
                ps.executeUpdate()
            }
            c
                .prepareStatement(
                    "insert into service_interval(service_type_id,equipment_type_id,interval_hours,due_soon_threshold_hours) values (?,?,?,?)",
                ).use { ps ->
                    ps.setObject(1, serviceTypeId)
                    ps.setObject(2, typeId)
                    ps.setInt(3, 100)
                    ps.setInt(4, 50)
                    ps.executeUpdate()
                }
            val technicianId = UUID.randomUUID()
            c.prepareStatement("insert into technician(id,eid,name) values (?,?,?)").use { ps ->
                ps.setObject(1, technicianId)
                ps.setString(2, "TECH-$technicianId")
                ps.setString(3, "Service technician")
                ps.executeUpdate()
            }
            c
                .prepareStatement(
                    """
                    insert into maintenance_record(id,equipment_id,service_type_id,site_id,performed_at,engine_hours,work_performed,
                      recorded_by,technician_id,currency,verification_status,idempotency_key)
                    values (?,?,?,?,?,?,?,?,?,'RWF','VERIFIED',?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, equipmentId)
                    ps.setObject(3, serviceTypeId)
                    ps.setObject(4, site)
                    ps.setTimestamp(5, java.sql.Timestamp.from(now.minusSeconds(86_400)))
                    ps.setBigDecimal(6, java.math.BigDecimal("900.0"))
                    ps.setString(7, "Previous service")
                    ps.setObject(8, actor.id)
                    ps.setObject(9, technicianId)
                    ps.setString(10, "service-due-$equipmentId")
                    ps.executeUpdate()
                }
        }
    }

    private fun outbox(
        topic: String,
        payload: String,
    ) {
        dataSource.connection.use { c ->
            c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,?,?::jsonb,?)").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setString(2, topic)
                ps.setString(3, payload)
                ps.setTimestamp(4, java.sql.Timestamp.from(now))
                ps.executeUpdate()
            }
        }
    }

    private class RecordingNotifier(
        private val onSend: () -> Unit,
    ) : PushNotifier {
        override fun send(
            tokens: Collection<String>,
            title: String,
            body: String,
            data: Map<String, String>,
        ) {
            onSend()
        }
    }
}
