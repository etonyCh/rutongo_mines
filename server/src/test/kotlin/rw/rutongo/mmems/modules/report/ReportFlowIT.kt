@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList", "NestedBlockDepth")

package rw.rutongo.mmems.modules.report

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
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
import rw.rutongo.mmems.platform.PrivateFileBlobStore
import rw.rutongo.mmems.shared.api.ReportCreateDto
import rw.rutongo.mmems.shared.api.ReportNarrativeDto
import rw.rutongo.mmems.shared.api.ReportNarrativeUpdateDto
import rw.rutongo.mmems.shared.enums.ReportPeriod
import rw.rutongo.mmems.shared.enums.ReportState
import rw.rutongo.mmems.shared.enums.Role
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportFlowIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: ReportService
    private lateinit var blobStore: PrivateFileBlobStore
    private lateinit var directory: java.nio.file.Path
    private lateinit var equipmentTypeId: UUID
    private val fixedNow = Instant.parse("2026-11-08T10:00:00Z")
    private val reportDate = LocalDate.parse("2026-11-08")

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 6
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        directory = Files.createTempDirectory("rutongo-report-test")
        blobStore = PrivateFileBlobStore(directory)
        service =
            ReportService(
                dataSource,
                blobStore,
                ReportSnapshotBuilder(dataSource) { fixedNow },
                DailyReportPdfRenderer(),
            ) { fixedNow }
        equipmentTypeId = createEquipmentType()
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `prepares submits and approves a daily report with stable PDFs`() {
        val site = createSite("REPORT")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val manager = createActor(Role.MANAGER, listOf(site))
        val equipment = createEquipment(site)

        val preview = service.preview(supervisor, site, reportDate, ReportPeriod.DAY)
        assertEquals(1, preview.summary.totalEquipment)
        assertEquals(1, preview.summary.notRecorded)
        assertEquals(equipment.toString(), preview.equipment.single().id)

        val created =
            service.create(
                supervisor,
                ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.DAY, ReportNarrativeDto(executiveSummary = "Shift overview")),
            )
        val reportId = UUID.fromString(created.id)
        assertEquals(ReportState.DRAFT, created.state)
        assertEquals(1, created.versionNo)

        val draft = service.pdf(supervisor, reportId)
        assertEquals("RUTONGO_MINES_DAILY_REPORT_REPORT_SITE_2026-11-08_DAY.pdf", draft.fileName)
        val draftText = text(draft.bytes)
        assertTrue(draftText.contains("DRAFT"))
        assertTrue(draftText.contains("Not Recorded"))

        val updated =
            service.updateNarrative(
                supervisor,
                reportId,
                1,
                ReportNarrativeUpdateDto(ReportNarrativeDto(executiveSummary = "Updated overview", safetyObservations = "None")),
            )
        assertEquals("Updated overview", updated.narrative.executiveSummary)

        val submitted = service.submit(supervisor, reportId, 1)
        assertEquals(ReportState.SUBMITTED, submitted.state)
        assertNotNull(submitted.submittedAt)
        assertNotNull(submitted.pdfSha256)
        assertFalse(text(service.pdf(supervisor, reportId).bytes).contains("DRAFT"))

        val approved = service.approve(manager, reportId, 1, "Approved for the general manager")
        assertEquals(ReportState.APPROVED, approved.state)
        assertNotNull(approved.approvedAt)
        assertEquals("Manager User", approved.approvedByName)
        assertTrue(text(service.pdf(manager, reportId).bytes).contains("APPROVED"))
        assertEquals(4, count("select count(*) from report_event where report_id=?", reportId))
    }

    @Test
    fun `returned reports are corrected and resubmitted without changing the version number`() {
        val site = createSite("CORRECT")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val manager = createActor(Role.MANAGER, listOf(site))
        createEquipment(site)
        val created = service.create(supervisor, ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.FULL_DAY))
        val reportId = UUID.fromString(created.id)
        service.submit(supervisor, reportId, 1)
        val firstKey = pdfStorageKey(reportId, 1) ?: error("The submitted PDF was not stored")

        assertFailsWith<IllegalArgumentException> {
            service.returnForCorrection(manager, reportId, 1, "  ")
        }
        val returned = service.returnForCorrection(manager, reportId, 1, "Add the missing inspection context")
        assertEquals(ReportState.RETURNED_FOR_CORRECTION, returned.state)
        assertTrue(text(service.pdf(supervisor, reportId).bytes).contains("DRAFT"))

        service.updateNarrative(
            supervisor,
            reportId,
            1,
            ReportNarrativeUpdateDto(ReportNarrativeDto(remarks = "Context added")),
        )
        val resubmitted = service.submit(supervisor, reportId, 1)
        assertEquals(ReportState.RESUBMITTED, resubmitted.state)
        assertEquals(1, resubmitted.versionNo)
        val secondKey = pdfStorageKey(reportId, 1) ?: error("The resubmitted PDF was not stored")
        assertTrue(firstKey != secondKey)
        assertFalse(Files.exists(directory.resolve(firstKey)))
        assertTrue(Files.exists(directory.resolve(secondKey)))

        val approved = service.approve(manager, reportId, 1, null)
        assertEquals(ReportState.APPROVED, approved.state)
        assertEquals(6, count("select count(*) from report_event where report_id=?", reportId))
    }

    @Test
    fun `approved reports can be superseded by a new version`() {
        val site = createSite("VERSION")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val manager = createActor(Role.MANAGER, listOf(site))
        createEquipment(site)
        val created = service.create(supervisor, ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.NIGHT))
        val reportId = UUID.fromString(created.id)
        service.submit(supervisor, reportId, 1)
        service.approve(manager, reportId, 1, "Initial approval")

        val newVersion = service.newVersion(supervisor, reportId, 1, "Correct the supervisor narrative")
        assertEquals(2, newVersion.versionNo)
        assertEquals(ReportState.DRAFT, newVersion.state)
        assertEquals(ReportState.SUPERSEDED, service.get(supervisor, reportId, 1).state)
        assertEquals(1, service.list(supervisor, site).size)
        assertEquals(2, service.list(supervisor, site).single().versionNo)

        val stale = assertFailsWith<AuthFailure> { service.submit(supervisor, reportId, 1) }
        assertEquals("REPORT_VERSION_CONFLICT", stale.code)
        val invalid = assertFailsWith<AuthFailure> { service.updateNarrative(supervisor, reportId, 1, ReportNarrativeUpdateDto()) }
        assertEquals("REPORT_VERSION_CONFLICT", invalid.code)
    }

    @Test
    fun `site access and report permissions are enforced`() {
        val site = createSite("ACCESS")
        val otherSite = createSite("OTHER")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val outsider = createActor(Role.SUPERVISOR, listOf(otherSite))
        val manager = createActor(Role.MANAGER, listOf(site))
        val generalManager = createActor(Role.GENERAL_MANAGER, emptyList())
        createEquipment(site)

        assertEquals("FORBIDDEN", assertFailsWith<AuthFailure> { service.preview(mechanic, site, reportDate, ReportPeriod.DAY) }.code)
        assertEquals("SITE_NOT_AUTHORIZED", assertFailsWith<AuthFailure> { service.preview(outsider, site, reportDate, ReportPeriod.DAY) }.code)
        assertEquals("FORBIDDEN", assertFailsWith<AuthFailure> { service.create(mechanic, ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.DAY)) }.code)

        val created = service.create(supervisor, ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.DAY))
        val reportId = UUID.fromString(created.id)
        assertEquals("SITE_NOT_AUTHORIZED", assertFailsWith<AuthFailure> { service.get(outsider, reportId) }.code)
        assertTrue(service.list(generalManager, site).isEmpty())
        assertEquals("FORBIDDEN", assertFailsWith<AuthFailure> { service.get(generalManager, reportId) }.code)

        service.submit(supervisor, reportId, 1)
        service.approve(manager, reportId, 1, "Approved")
        assertEquals(1, service.list(generalManager, site).size)
        assertEquals(ReportState.APPROVED, service.get(generalManager, reportId).state)
    }

    @Test
    fun `stored pdfs are integrity checked and downloads are audited`() {
        val site = createSite("INTEGRITY")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        createEquipment(site)
        val created = service.create(supervisor, ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.DAY))
        val reportId = UUID.fromString(created.id)
        service.submit(supervisor, reportId, 1)
        val key = pdfStorageKey(reportId, 1) ?: error("The submitted PDF was not stored")

        val pdf = service.pdf(supervisor, reportId)
        assertTrue(Loader.loadPDF(pdf.bytes).use { it.numberOfPages } >= 1)
        assertEquals(1, countText("select count(*) from audit_event where action='REPORT_PDF_DOWNLOADED' and entity_id=?", reportId.toString()))

        Files.write(directory.resolve(key), "tampered".toByteArray())
        val failure = assertFailsWith<AuthFailure> { service.pdf(supervisor, reportId) }
        assertEquals("REPORT_PDF_INTEGRITY_FAILED", failure.code)
    }

    @Test
    fun `draft pdfs are regenerated from a fresh snapshot`() {
        val site = createSite("FRESH")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val equipment = createEquipment(site)
        val created = service.create(supervisor, ReportCreateDto(site.toString(), reportDate.toString(), ReportPeriod.DAY))
        val reportId = UUID.fromString(created.id)

        dataSource.connection.use { c ->
            c.prepareStatement("update equipment set current_status='OPERATIONAL' where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeUpdate()
            }
        }
        val text = text(service.pdf(supervisor, reportId).bytes)
        assertTrue(text.contains("DRAFT"))
        assertTrue(text.contains("Operational status: OPERATIONAL"))
    }

    @Test
    fun `report metrics bound downtime to the period and distinguish repair states`() {
        val site = createSite("METRICS")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val repairedEquipment = createEquipment(site)
        val openEquipment = createEquipment(site)
        createBreakdown(
            site,
            supervisor,
            repairedEquipment,
            Instant.parse("2026-11-08T07:00:00Z"),
            Instant.parse("2026-11-08T08:00:00Z"),
            "RESOLVED",
        )
        createBreakdown(
            site,
            supervisor,
            openEquipment,
            Instant.parse("2026-11-08T09:30:00Z"),
            null,
            "UNDER_REPAIR",
        )

        val preview = service.preview(supervisor, site, reportDate, ReportPeriod.FULL_DAY)
        assertEquals(2, preview.summary.breakdownsReported)
        assertEquals(1, preview.summary.breakdownsOpen)
        assertEquals(1, preview.summary.repairsCompleted)
        assertEquals(1, preview.summary.repairsInProgress)
        assertEquals(90, preview.summary.downtimeMinutes)
        assertEquals(1, preview.breakdowns.count { it.repairEnd != null })
    }

    @Test
    fun `preview is read only and leaves operational tables untouched`() {
        val site = createSite("READONLY")
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        createEquipment(site)
        val before = tableCounts()
        val preview = service.preview(supervisor, site, reportDate, ReportPeriod.FULL_DAY)
        assertEquals(1, preview.summary.totalEquipment)
        assertEquals(before, tableCounts())
        assertEquals(0, count("select count(*) from daily_report where site_id=?", site))
    }

    private fun text(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    private fun createEquipmentType(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment_type(id,name) values (?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "Report type ${UUID.randomUUID()}")
                ps.executeUpdate()
            }
        }
        return id
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
                ps.setString(2, "report-user-$id")
                ps.setString(3, "${role.name.lowercase().replaceFirstChar { it.uppercase() }} User")
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
        return Principal(id, "report-user-$id", role, false)
    }

    private fun createEquipment(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Report Equipment")
                ps.setObject(5, equipmentTypeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createBreakdown(
        site: UUID,
        actor: Principal,
        equipment: UUID,
        start: Instant,
        end: Instant?,
        state: String,
    ) {
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "insert into breakdown(id,equipment_id,category,problem,site_id,reported_by,reported_at,breakdown_start,repair_start,repair_end,state,idempotency_key) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, equipment)
                    ps.setString(3, "MECHANICAL")
                    ps.setString(4, "Test breakdown")
                    ps.setObject(5, site)
                    ps.setObject(6, actor.id)
                    ps.setTimestamp(7, java.sql.Timestamp.from(start))
                    ps.setTimestamp(8, java.sql.Timestamp.from(start))
                    ps.setTimestamp(9, java.sql.Timestamp.from(start))
                    ps.setTimestamp(10, end?.let { java.sql.Timestamp.from(it) })
                    ps.setString(11, state)
                    ps.setString(12, "breakdown-${UUID.randomUUID()}")
                    ps.executeUpdate()
                }
        }
    }

    private fun pdfStorageKey(
        reportId: UUID,
        versionNo: Int,
    ): String? =
        dataSource.connection.use { c ->
            c.prepareStatement("select pdf_storage_key from report_version where report_id=? and version_no=?").use { ps ->
                ps.setObject(1, reportId)
                ps.setInt(2, versionNo)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun countText(
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

    private fun tableCounts(): List<Int> =
        listOf(
            "equipment",
            "defect",
            "inspection",
            "inspection_expectation",
            "breakdown",
            "maintenance_record",
            "part_request",
            "part_request_line",
            "audit_event",
            "outbox_message",
        ).map { count("select count(*) from $it") }

    private fun count(
        sql: String,
        id: UUID? = null,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                if (id != null) ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
