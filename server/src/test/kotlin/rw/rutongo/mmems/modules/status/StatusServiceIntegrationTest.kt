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
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusServiceIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource:HikariDataSource
    private lateinit var service:StatusService
    private lateinit var typeId:UUID

    @BeforeAll
    fun setUp() {
        dataSource=HikariDataSource(HikariConfig().apply {
            jdbcUrl=postgres.jdbcUrl; username=postgres.username; password=postgres.password; maximumPoolSize=4
        })
        Flyway.configure().dataSource(dataSource).load().migrate()
        service=StatusService(dataSource)
        typeId=dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(name) values (?) returning id").use { ps ->
                ps.setString(1,"Status test type ${UUID.randomUUID()}"); ps.executeQuery().use { rs -> rs.next(); rs.getObject(1,UUID::class.java) }
            }
        }
    }

    @AfterAll
    fun tearDown() { dataSource.close() }

    @Test
    fun `status update projects the current state and the migrated event table is immutable`() {
        val site=createSite("IMMUTABLE")
        val actor=createActor(Role.MECHANIC,listOf(site))
        val equipment=createEquipment(site)

        val result=service.record(actor,equipment,0,key(),OperationalStatus.DOWN,"Hydraulic leak",null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))

        assertTrue(result.projected)
        assertEquals(1,result.equipmentVersion)
        assertEquals(1,result.event.sequence)
        assertNull(result.event.previousStatus)
        assertEquals("DOWN",result.event.newStatus)

        dataSource.connection.use { c ->
            c.prepareStatement("select current_status,current_status_event_id,current_problem,version from equipment where id=?").use { ps ->
                ps.setObject(1,equipment); ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("DOWN",rs.getString(1))
                    assertEquals(result.event.id,rs.getObject(2,UUID::class.java).toString())
                    assertEquals("Hydraulic leak",rs.getString(3))
                    assertEquals(1L,rs.getLong(4))
                }
            }
        }

        val updateError=assertFailsWith<SQLException> {
            dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate("update equipment_status_event set new_status='OPERATIONAL' where id='${result.event.id}'") } }
        }
        assertEquals("55000",updateError.sqlState)
        val deleteError=assertFailsWith<SQLException> {
            dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate("delete from equipment_status_event where id='${result.event.id}'") } }
        }
        assertEquals("55000",deleteError.sqlState)
    }

    @Test
    fun `idempotent replay returns the original event without appending or projecting again`() {
        val site=createSite("REPLAY")
        val actor=createActor(Role.SUPERVISOR,listOf(site))
        val equipment=createEquipment(site)
        val idempotencyKey=key()

        val first=service.record(actor,equipment,0,idempotencyKey,OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))
        val replayed=service.record(actor,equipment,999,idempotencyKey,OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))

        assertEquals(first.event.id,replayed.event.id)
        assertEquals(1,replayed.equipmentVersion)
        assertEquals(1,eventCount(equipment))
        assertEquals(1L,equipmentVersion(equipment))
    }

    @Test
    fun `stale If-Match conflicts without writing an event`() {
        val site=createSite("CONFLICT")
        val actor=createActor(Role.MECHANIC,listOf(site))
        val equipment=createEquipment(site)
        service.record(actor,equipment,0,key(),OperationalStatus.DOWN,"Bearing failure",null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))

        val failure=assertFailsWith<AuthFailure> {
            service.record(actor,equipment,0,key(),OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T09:00:00Z"))
        }

        assertEquals("EQUIPMENT_VERSION_CONFLICT",failure.code)
        assertEquals(1,eventCount(equipment))
        assertEquals(1L,equipmentVersion(equipment))
    }

    @Test
    fun `late event is appended and ordered by occurred_at without regressing the projection`() {
        val site=createSite("LATE")
        val actor=createActor(Role.MECHANIC,listOf(site))
        val equipment=createEquipment(site)
        service.record(actor,equipment,0,key(),OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T10:00:00Z"))

        val late=service.record(actor,equipment,1,key(),OperationalStatus.DOWN,"Offline report",null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))

        assertFalse(late.projected)
        assertEquals(1,late.equipmentVersion)
        assertEquals(2,late.event.sequence)
        assertEquals("OPERATIONAL",currentStatus(equipment))
        assertEquals(listOf("OPERATIONAL","DOWN"),service.history(actor,equipment).map { it.newStatus })

        val newer=service.record(actor,equipment,1,key(),OperationalStatus.UNDER_REPAIR,"Pump failure","Replaced seal",null,null,null,null,Instant.parse("2026-11-05T12:00:00Z"))

        assertTrue(newer.projected)
        assertEquals(2,newer.equipmentVersion)
        assertEquals("UNDER_REPAIR",currentStatus(equipment))
        assertEquals(listOf("UNDER_REPAIR","OPERATIONAL","DOWN"),service.history(actor,equipment).map { it.newStatus })
    }

    @Test
    fun `history enforces site scope for current and historical event sites`() {
        val originalSite=createSite("HISTORY-A")
        val destinationSite=createSite("HISTORY-B")
        val author=createActor(Role.MECHANIC,listOf(originalSite))
        val equipment=createEquipment(originalSite)
        service.record(author,equipment,0,key(),OperationalStatus.DOWN,"Cable damage",null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))
        dataSource.connection.use { c -> c.prepareStatement("update equipment set site_id=? where id=?").use { ps -> ps.setObject(1,destinationSite); ps.setObject(2,equipment); ps.executeUpdate() } }

        val destinationReader=createActor(Role.MECHANIC,listOf(destinationSite))
        assertTrue(service.history(destinationReader,equipment).isEmpty())
        val originalReader=createActor(Role.MECHANIC,listOf(originalSite))
        val failure=assertFailsWith<AuthFailure> { service.history(originalReader,equipment) }
        assertEquals("SITE_NOT_AUTHORIZED",failure.code)
        val bothSites=createActor(Role.MECHANIC,listOf(originalSite,destinationSite))
        assertEquals(1,service.history(bothSites,equipment).size)
    }

    @Test
    fun `active problems include only down or under repair equipment in authorized sites`() {
        val siteA=createSite("PROBLEMS-A")
        val siteB=createSite("PROBLEMS-B")
        val writer=createActor(Role.SUPERVISOR,listOf(siteA,siteB))
        val down=createEquipment(siteA)
        val underRepair=createEquipment(siteB)
        val operational=createEquipment(siteA)
        service.record(writer,down,0,key(),OperationalStatus.DOWN,"Engine failure",null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))
        service.record(writer,underRepair,0,key(),OperationalStatus.UNDER_REPAIR,"Gearbox failure","Rebuilding gearbox",null,null,null,null,Instant.parse("2026-11-05T09:00:00Z"))
        service.record(writer,operational,0,key(),OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T10:00:00Z"))

        val readerA=createActor(Role.MECHANIC,listOf(siteA))
        assertEquals(setOf(down.toString()),service.activeProblems(readerA).map { it.equipmentId }.toSet())
        assertTrue(service.activeProblems(readerA,siteB).isEmpty())
        val readerB=createActor(Role.MECHANIC,listOf(siteB))
        assertEquals(setOf(underRepair.toString()),service.activeProblems(readerB).map { it.equipmentId }.toSet())
        val manager=createActor(Role.MANAGER,emptyList())
        val managerProblems=service.activeProblems(manager)
        assertTrue(managerProblems.any { it.equipmentId==down.toString() })
        assertTrue(managerProblems.any { it.equipmentId==underRepair.toString() })
        assertFalse(managerProblems.any { it.equipmentId==operational.toString() })
        assertTrue(managerProblems.all { it.newStatus in setOf("DOWN","UNDER_REPAIR") })
    }

    @Test
    fun `eventsAfter resumes from Last-Event-ID cursor and filters authorized sites`() {
        val siteA=createSite("EVENTS-A")
        val siteB=createSite("EVENTS-B")
        val writer=createActor(Role.SUPERVISOR,listOf(siteA,siteB))
        val readerA=createActor(Role.MECHANIC,listOf(siteA))
        val equipmentA=createEquipment(siteA)
        val equipmentB=createEquipment(siteB)
        val firstA=service.record(writer,equipmentA,0,key(),OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))
        val eventB=service.record(writer,equipmentB,0,key(),OperationalStatus.DOWN,"Site B failure",null,null,null,null,null,Instant.parse("2026-11-05T09:00:00Z"))

        val initial=service.eventsAfter(readerA,null)
        assertTrue(initial.any { it.id==firstA.event.id })
        assertFalse(initial.any { it.id==eventB.event.id })
        assertTrue(initial.all { it.siteId==siteA.toString() })

        val cursorAt=Instant.parse(firstA.event.recordedAt)
        val cursorId=UUID.fromString(firstA.event.id)
        assertFalse(service.eventsAfter(readerA,cursorAt,cursorId).any { it.id==firstA.event.id })
        val secondA=service.record(writer,equipmentA,1,key(),OperationalStatus.UNDER_REPAIR,"Hydraulic failure","Hose replaced",null,null,null,null,Instant.parse("2026-11-05T10:00:00Z"))
        val resumed=service.eventsAfter(readerA,cursorAt,cursorId)
        assertTrue(resumed.any { it.id==secondA.event.id })
        assertFalse(resumed.any { it.siteId==siteB.toString() })
    }

    @Test
    fun `status writes require STATUS_UPDATE and site access`() {
        val siteA=createSite("AUTH-A")
        val siteB=createSite("AUTH-B")
        val equipment=createEquipment(siteA)
        val otherSite=createActor(Role.MECHANIC,listOf(siteB))
        val siteFailure=assertFailsWith<AuthFailure> {
            service.record(otherSite,equipment,0,key(),OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))
        }
        assertEquals("SITE_NOT_AUTHORIZED",siteFailure.code)

        val manager=createActor(Role.MANAGER,listOf(siteA))
        val roleFailure=assertFailsWith<AuthFailure> {
            service.record(manager,equipment,0,key(),OperationalStatus.OPERATIONAL,null,null,null,null,null,null,Instant.parse("2026-11-05T08:00:00Z"))
        }
        assertEquals("FORBIDDEN",roleFailure.code)
        assertEquals(0,eventCount(equipment))
    }

    private fun createSite(prefix:String):UUID {
        val id=UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state) values (?,?,?,'ACTIVE')").use { ps ->
                ps.setObject(1,id); ps.setString(2,"$prefix-${id.toString().take(8)}".uppercase()); ps.setString(3,"$prefix Site"); ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(role:Role,siteIds:List<UUID>):Principal {
        val id=UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1,id); ps.setString(2,"status-user-$id"); ps.setString(3,"Status User"); ps.setString(4,"EID-$id"); ps.setString(5,role.name); ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                siteIds.distinct().forEach { site ->
                    ps.setObject(1,id); ps.setObject(2,site); ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return Principal(id,"status-user-$id",role,false)
    }

    private fun createEquipment(siteId:UUID):UUID {
        val id=UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1,id); ps.setString(2,"ASSET-$id"); ps.setString(3,"EQ-${id.toString().take(8)}"); ps.setString(4,"Status Test Equipment"); ps.setObject(5,typeId); ps.setObject(6,siteId); ps.executeUpdate()
            }
        }
        return id
    }

    private fun key()="status-${UUID.randomUUID()}"

    private fun eventCount(equipmentId:UUID):Int = dataSource.connection.use { c ->
        c.prepareStatement("select count(*) from equipment_status_event where equipment_id=?").use { ps ->
            ps.setObject(1,equipmentId); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun equipmentVersion(equipmentId:UUID):Long = dataSource.connection.use { c ->
        c.prepareStatement("select version from equipment where id=?").use { ps ->
            ps.setObject(1,equipmentId); ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun currentStatus(equipmentId:UUID):String? = dataSource.connection.use { c ->
        c.prepareStatement("select current_status from equipment where id=?").use { ps ->
            ps.setObject(1,equipmentId); ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }
}
