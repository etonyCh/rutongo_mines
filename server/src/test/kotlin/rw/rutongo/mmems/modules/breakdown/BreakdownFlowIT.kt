@file:Suppress("LongMethod")

package rw.rutongo.mmems.modules.breakdown

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
import rw.rutongo.mmems.modules.breakdown.domain.BreakdownSpan
import rw.rutongo.mmems.modules.breakdown.domain.Interval
import rw.rutongo.mmems.modules.breakdown.domain.downtimeMinutes
import rw.rutongo.mmems.modules.inspection.InspectionService
import rw.rutongo.mmems.modules.photo.PhotoService
import rw.rutongo.mmems.platform.PrivateFileBlobStore
import rw.rutongo.mmems.shared.enums.BreakdownState
import rw.rutongo.mmems.shared.enums.Role
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BreakdownFlowIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: BreakdownService
    private lateinit var inspections: InspectionService
    private lateinit var photos: PhotoService
    private lateinit var blobDirectory: Path
    private lateinit var typeId: UUID
    private lateinit var shiftId: UUID
    private val fixedNow = Instant.parse("2026-11-05T10:00:00Z")

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
        service = BreakdownService(dataSource) { fixedNow }
        inspections = InspectionService(dataSource) { fixedNow }
        blobDirectory = Files.createTempDirectory("rutongo-breakdown-photo")
        photos = PhotoService(dataSource, PrivateFileBlobStore(blobDirectory))
        typeId =
            dataSource.connection.use { c ->
                c.prepareStatement("insert into equipment_type(name) values (?) returning id").use { ps ->
                    ps.setString(1, "Breakdown type ${UUID.randomUUID()}")
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
        shiftId =
            dataSource.connection.use { c ->
                c.prepareStatement("select id from shift where code='DAY'").use { ps ->
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        blobDirectory.toFile().deleteRecursively()
    }

    @Test
    fun `reporting a breakdown is idempotent links defects and rejects a second active incident`() {
        val site = createSite("REPORT")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val defect = createDefect(equipment, mechanic, "Hydraulic hose leak")
        val input =
            createInput(
                equipment = equipment,
                key = "breakdown-report-${UUID.randomUUID()}",
                start = Instant.parse("2026-11-05T08:30:00Z"),
                defectIds = listOf(defect),
            )

        val created = service.create(mechanic, input)
        val replayed = service.create(mechanic, input)

        assertEquals(created.id, replayed.id)
        assertEquals("REPORTED", created.state)
        assertEquals(created.defectIds, listOf(defect.toString()))
        assertEquals(created.id, defectBreakdown(defect)?.toString())
        assertEquals(90, createdDowntime(created))
        assertEquals(1, count("select count(*) from breakdown where equipment_id=?", equipment))
        assertEquals(1, count("select count(*) from audit_event where entity_id=?", created.id))
        assertEquals(1, count("select count(*) from outbox_message where topic='BREAKDOWN_REPORTED' and payload->>'breakdownId'=?", created.id))

        val duplicate =
            assertFailsWith<AuthFailure> {
                service.create(mechanic, input.copy(idempotencyKey = "another-${UUID.randomUUID()}"))
            }
        assertEquals("BREAKDOWN_ALREADY_ACTIVE", duplicate.code)
        assertEquals(created.id, service.list(mechanic, site, equipment).single().id)
    }

    @Test
    fun `repair transitions track timestamps and enforce optimistic concurrency`() {
        val site = createSite("REPAIR")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val created =
            service.create(
                mechanic,
                createInput(
                    equipment = equipment,
                    key = "breakdown-repair-${UUID.randomUUID()}",
                    start = Instant.parse("2026-11-05T08:00:00Z"),
                ),
            )

        val underRepair =
            service.transition(
                mechanic,
                UUID.fromString(created.id),
                created.version,
                BreakdownTransition(
                    state = BreakdownState.UNDER_REPAIR,
                    repairStart = Instant.parse("2026-11-05T09:00:00Z"),
                    repairEnd = null,
                    actionTaken = "Replaced the hydraulic hose",
                    partsRequired = "Hydraulic hose",
                    assignedTechnicianId = null,
                    remarks = null,
                ),
            )

        assertEquals("UNDER_REPAIR", underRepair.state)
        assertEquals("2026-11-05T09:00:00Z", underRepair.repairStart)
        assertEquals(1, underRepair.version)
        assertEquals(120, createdDowntime(underRepair))

        val resolved =
            service.transition(
                mechanic,
                UUID.fromString(created.id),
                underRepair.version,
                BreakdownTransition(
                    state = BreakdownState.RESOLVED,
                    repairStart = null,
                    repairEnd = Instant.parse("2026-11-05T09:45:00Z"),
                    actionTaken = "Pressure tested and returned to service",
                    partsRequired = null,
                    assignedTechnicianId = null,
                    remarks = "No leaks",
                ),
            )

        assertEquals("RESOLVED", resolved.state)
        assertEquals("2026-11-05T09:45:00Z", resolved.repairEnd)
        assertEquals(105, createdDowntime(resolved))

        val stale =
            assertFailsWith<AuthFailure> {
                service.transition(
                    mechanic,
                    UUID.fromString(created.id),
                    underRepair.version,
                    BreakdownTransition(BreakdownState.CLOSED, null, null, null, null, null, null),
                )
            }
        assertEquals("BREAKDOWN_VERSION_CONFLICT", stale.code)

        val closed =
            service.transition(
                mechanic,
                UUID.fromString(created.id),
                resolved.version,
                BreakdownTransition(BreakdownState.CLOSED, null, null, null, null, null, null),
            )
        assertEquals("CLOSED", closed.state)

        val illegal =
            assertFailsWith<AuthFailure> {
                service.transition(
                    mechanic,
                    UUID.fromString(created.id),
                    closed.version,
                    BreakdownTransition(BreakdownState.REPORTED, null, null, null, null, null, null),
                )
            }
        assertEquals("INVALID_BREAKDOWN_TRANSITION", illegal.code)

        val replacement =
            service.create(
                mechanic,
                createInput(
                    equipment = equipment,
                    key = "breakdown-replacement-${UUID.randomUUID()}",
                    start = Instant.parse("2026-11-05T10:00:00Z"),
                ),
            )
        assertEquals("REPORTED", replacement.state)
    }

    @Test
    fun `overlapping breakdowns are deduplicated in equipment downtime`() {
        val site = createSite("OVERLAP")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val first =
            service.create(
                mechanic,
                createInput(equipment, "breakdown-overlap-a-${UUID.randomUUID()}", Instant.parse("2026-11-05T08:00:00Z")),
            )
        val firstResolved =
            service.transition(
                mechanic,
                UUID.fromString(first.id),
                first.version,
                BreakdownTransition(
                    BreakdownState.RESOLVED,
                    Instant.parse("2026-11-05T08:00:00Z"),
                    Instant.parse("2026-11-05T10:00:00Z"),
                    "Repaired",
                    null,
                    null,
                    null,
                ),
            )
        val second =
            service.create(
                mechanic,
                createInput(equipment, "breakdown-overlap-b-${UUID.randomUUID()}", Instant.parse("2026-11-05T09:00:00Z")),
            )
        val secondResolved =
            service.transition(
                mechanic,
                UUID.fromString(second.id),
                second.version,
                BreakdownTransition(
                    BreakdownState.RESOLVED,
                    Instant.parse("2026-11-05T09:00:00Z"),
                    Instant.parse("2026-11-05T11:00:00Z"),
                    "Repaired again",
                    null,
                    null,
                    null,
                ),
            )

        val spans =
            listOf(firstResolved, secondResolved).map {
                BreakdownSpan(
                    Instant.parse(it.breakdownStart),
                    it.repairEnd?.let(Instant::parse),
                )
            }

        assertEquals(180, downtimeMinutes(spans, Interval(Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T12:00:00Z")), Instant.parse("2026-11-05T12:00:00Z")))
    }

    @Test
    fun `defects can be linked to a breakdown only on the same equipment`() {
        val site = createSite("LINK")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val otherEquipment = createEquipment(site)
        val defect = createDefect(equipment, mechanic, "Loose guard")
        val breakdown = service.create(mechanic, createInput(equipment, "breakdown-link-a-${UUID.randomUUID()}", fixedNow))
        val otherBreakdown = service.create(mechanic, createInput(otherEquipment, "breakdown-link-b-${UUID.randomUUID()}", fixedNow))

        val linked = inspections.updateDefect(mechanic, defect, null, null, null, UUID.fromString(breakdown.id), 0)

        assertEquals(breakdown.id, linked.breakdownId)
        assertEquals(1, linked.version)

        val mismatch =
            assertFailsWith<AuthFailure> {
                inspections.updateDefect(mechanic, defect, null, null, null, UUID.fromString(otherBreakdown.id), linked.version)
            }
        assertEquals("BREAKDOWN_EQUIPMENT_MISMATCH", mismatch.code)
    }

    @Test
    fun `breakdown photos are site scoped and limited to breakdown managers`() {
        val site = createSite("PHOTO")
        val otherSite = createSite("OTHER")
        val mechanic = createActor(Role.MECHANIC, listOf(site, otherSite))
        val manager = createActor(Role.MANAGER, listOf(site))
        val equipment = createEquipment(site)
        val breakdown = service.create(mechanic, createInput(equipment, "breakdown-photo-${UUID.randomUUID()}", fixedNow))

        val photo = photos.upload(mechanic, site, "BREAKDOWN", UUID.fromString(breakdown.id), "BEFORE", "image/jpeg", jpeg)

        assertTrue(
            service
                .list(mechanic, site, equipment)
                .single()
                .photoIds
                .contains(photo.id),
        )
        assertEquals("SITE_NOT_AUTHORIZED", assertFailsWith<IllegalArgumentException> { photos.upload(mechanic, otherSite, "BREAKDOWN", UUID.fromString(breakdown.id), "AFTER", "image/jpeg", jpeg) }.message)
        assertEquals("FORBIDDEN", assertFailsWith<IllegalArgumentException> { photos.upload(manager, site, "BREAKDOWN", UUID.fromString(breakdown.id), "EVIDENCE", "image/jpeg", jpeg) }.message)
        assertEquals("BREAKDOWN_NOT_FOUND", assertFailsWith<IllegalArgumentException> { photos.upload(mechanic, site, "BREAKDOWN", UUID.randomUUID(), "EVIDENCE", "image/jpeg", jpeg) }.message)
        assertNotNull(photos.open(mechanic, UUID.fromString(photo.id)))
    }

    private fun createInput(
        equipment: UUID,
        key: String,
        start: Instant,
        defectIds: List<UUID> = emptyList(),
    ) = BreakdownCreate(
        idempotencyKey = key,
        equipmentId = equipment,
        category = "HYDRAULIC",
        problem = "Hydraulic pressure loss",
        tunnelId = null,
        shiftId = shiftId,
        reportedAt = start,
        breakdownStart = start,
        assignedTechnicianId = null,
        actionTaken = null,
        partsRequired = null,
        remarks = null,
        defectIds = defectIds,
    )

    private fun createdDowntime(breakdown: BreakdownView): Long {
        val spans = listOf(BreakdownSpan(Instant.parse(breakdown.breakdownStart), breakdown.repairEnd?.let(Instant::parse)))
        val period = Interval(Instant.parse(breakdown.breakdownStart), fixedNow)
        return downtimeMinutes(spans, period, fixedNow)
    }

    private fun createSite(prefix: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state,timezone) values (?,?,?,'ACTIVE','Africa/Kigali')").use { ps ->
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
        sites: List<UUID>,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "breakdown-user-$id")
                ps.setString(3, "Breakdown User")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                sites.forEach {
                    ps.setObject(1, id)
                    ps.setObject(2, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return Principal(id, "breakdown-user-$id", role, false)
    }

    private fun createEquipment(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Breakdown Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createDefect(
        equipment: UUID,
        actor: Principal,
        description: String,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into defect(id,equipment_id,category,description,severity,reported_by,reported_at) values (?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, equipment)
                ps.setString(3, "HYDRAULIC")
                ps.setString(4, description)
                ps.setString(5, "MAJOR")
                ps.setObject(6, actor.id)
                ps.setTimestamp(7, java.sql.Timestamp.from(fixedNow))
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun defectBreakdown(defect: UUID): UUID? =
        dataSource.connection.use { c ->
            c.prepareStatement("select breakdown_id from defect where id=?").use { ps ->
                ps.setObject(1, defect)
                ps.executeQuery().use {
                    it.next()
                    it.getObject(1, UUID::class.java)
                }
            }
        }

    private fun count(
        sql: String,
        id: UUID,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun count(
        sql: String,
        value: String,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00)
}
