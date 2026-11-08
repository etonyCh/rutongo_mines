@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "MagicNumber",
    "TooGenericExceptionCaught",
    "ThrowsCount",
)

package rw.rutongo.mmems.modules.report

import kotlinx.serialization.json.Json
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.platform.BlobStore
import rw.rutongo.mmems.shared.api.DailyReportSnapshotDto
import rw.rutongo.mmems.shared.api.ReportCreateDto
import rw.rutongo.mmems.shared.api.ReportNarrativeDto
import rw.rutongo.mmems.shared.api.ReportNarrativeUpdateDto
import rw.rutongo.mmems.shared.api.ReportViewDto
import rw.rutongo.mmems.shared.enums.ReportPeriod
import rw.rutongo.mmems.shared.enums.ReportState
import rw.rutongo.mmems.shared.enums.Role
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

data class ReportPdf(
    val fileName: String,
    val bytes: ByteArray,
)

class ReportService(
    private val dataSource: DataSource,
    private val blobStore: BlobStore,
    private val snapshotBuilder: ReportSnapshotBuilder,
    private val renderer: DailyReportPdfRenderer,
    private val now: () -> Instant = Instant::now,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun preview(
        actor: Principal,
        siteId: UUID,
        reportDate: LocalDate,
        period: ReportPeriod,
    ): DailyReportSnapshotDto {
        requirePermission(actor, "REPORT_PREPARE")
        requireSiteAccess(actor, siteId)
        return snapshotBuilder.build(actor, siteId, reportDate, period)
    }

    fun create(
        actor: Principal,
        input: ReportCreateDto,
    ): ReportViewDto {
        requirePermission(actor, "REPORT_PREPARE")
        val siteId = uuid(input.siteId, "Site")
        val reportDate = date(input.reportDate)
        requireSiteAccess(actor, siteId)
        val snapshot = snapshotBuilder.build(actor, siteId, reportDate, input.period)
        val id = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        val reportRef = reportRef(siteId, reportDate, input.period)
        val narrative = json.encodeToString(ReportNarrativeDto.serializer(), input.narrative)
        val snapshotJson = json.encodeToString(DailyReportSnapshotDto.serializer(), snapshot)
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("insert into daily_report(id,report_ref,site_id,report_date,period,prepared_by,created_at) values (?,?,?,?,?,?,now())").use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, reportRef)
                    ps.setObject(3, siteId)
                    ps.setObject(4, reportDate)
                    ps.setString(5, input.period.name)
                    ps.setObject(6, actor.id)
                    ps.executeUpdate()
                }
                c
                    .prepareStatement(
                        "insert into report_version(id,report_id,version_no,state,narrative,snapshot,data_cutoff_at,created_by,created_at,updated_at) values (?,?,1,'DRAFT',?::jsonb,?::jsonb,?,?,now(),now())",
                    ).use { ps ->
                        ps.setObject(1, versionId)
                        ps.setObject(2, id)
                        ps.setString(3, narrative)
                        ps.setString(4, snapshotJson)
                        ps.setTimestamp(5, Timestamp.from(snapshot.dataCutoffAt.let(Instant::parse)))
                        ps.setObject(6, actor.id)
                        ps.executeUpdate()
                    }
                reportEvent(c, actor, id, versionId, "REPORT_CREATED", mapOf("versionNo" to "1"))
                audit(c, actor, id, "REPORT_CREATED", mapOf("versionNo" to "1", "siteId" to siteId.toString()))
                val view = loadVersion(c, id, 1, false)?.toView() ?: throw AuthFailure("REPORT_NOT_FOUND")
                c.commit()
                return view
            } catch (e: SQLException) {
                c.rollback()
                if (e.sqlState == "23505") throw AuthFailure("REPORT_ALREADY_EXISTS")
                throw e
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun list(
        actor: Principal,
        siteId: UUID? = null,
        reportDate: LocalDate? = null,
        state: ReportState? = null,
        limit: Int = 100,
    ): List<ReportViewDto> {
        if (!canViewReports(actor)) throw AuthFailure("FORBIDDEN")
        val approvedOnly = isApprovedOnly(actor)
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c
                .prepareStatement(
                    """
                    $VERSION_SELECT
                    join (select report_id,max(version_no) max_version from report_version group by report_id) latest
                      on latest.report_id=rv.report_id and latest.max_version=rv.version_no
                    where (?::uuid is null or dr.site_id=?)
                      and (?::date is null or dr.report_date=?)
                      and (?::text is null or rv.state=?)
                      and (? = false or rv.state='APPROVED')
                      and ${siteAccessSql("dr.site_id")}
                    order by dr.report_date desc,dr.created_at desc limit ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setObject(2, siteId)
                    ps.setObject(3, reportDate)
                    ps.setObject(4, reportDate)
                    ps.setString(5, state?.name)
                    ps.setString(6, state?.name)
                    ps.setBoolean(7, approvedOnly)
                    bindSiteAccess(ps, actor, 8)
                    ps.setInt(11, limit.coerceIn(1, 200))
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRow().toView()) } }
                }
        }
    }

    fun get(
        actor: Principal,
        reportId: UUID,
        versionNo: Int? = null,
    ): ReportViewDto =
        dataSource.connection.use { c ->
            val row = loadVersion(c, reportId, versionNo, false) ?: throw AuthFailure("REPORT_NOT_FOUND")
            authorizeView(actor, row)
            row.toView()
        }

    fun updateNarrative(
        actor: Principal,
        reportId: UUID,
        expectedVersionNo: Int,
        input: ReportNarrativeUpdateDto,
    ): ReportViewDto =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val row = currentForUpdate(c, reportId, expectedVersionNo)
                authorizePrepare(actor, row)
                if (row.preparedBy != actor.id) throw AuthFailure("FORBIDDEN")
                if (row.state !in setOf(ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION)) throw AuthFailure("INVALID_REPORT_STATE")
                c.prepareStatement("update report_version set narrative=?::jsonb,updated_at=now() where id=?").use { ps ->
                    ps.setString(1, json.encodeToString(ReportNarrativeDto.serializer(), input.narrative))
                    ps.setObject(2, row.versionId)
                    ps.executeUpdate()
                }
                reportEvent(c, actor, reportId, row.versionId, "REPORT_NARRATIVE_UPDATED", mapOf("versionNo" to row.versionNo.toString()))
                audit(c, actor, reportId, "REPORT_NARRATIVE_UPDATED", mapOf("versionNo" to row.versionNo.toString()))
                val view = loadVersion(c, reportId, row.versionNo, false)?.toView() ?: throw AuthFailure("REPORT_NOT_FOUND")
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    fun submit(
        actor: Principal,
        reportId: UUID,
        expectedVersionNo: Int,
    ): ReportViewDto {
        requirePermission(actor, "REPORT_PREPARE")
        var storedKey: String? = null
        var previousKey: String? = null
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val row = currentForUpdate(c, reportId, expectedVersionNo)
                authorizePrepare(actor, row)
                if (row.preparedBy != actor.id) throw AuthFailure("FORBIDDEN")
                if (row.state !in setOf(ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION)) throw AuthFailure("INVALID_REPORT_STATE")
                val targetState = if (row.state == ReportState.RETURNED_FOR_CORRECTION) ReportState.RESUBMITTED else ReportState.SUBMITTED
                val snapshot = snapshotBuilder.build(actor, row.siteId, row.reportDate, row.period)
                val narrative = json.decodeFromString(ReportNarrativeDto.serializer(), row.narrativeJson)
                val pdf = renderer.render(snapshot, narrative, targetState, row.versionNo)
                previousKey = row.pdfStorageKey
                storedKey = blobStore.put(ByteArrayInputStream(pdf), ".pdf")
                val sha = sha256(pdf)
                val submittedAt = now()
                c
                    .prepareStatement(
                        "update report_version set state=?,narrative=?::jsonb,snapshot=?::jsonb,data_cutoff_at=?,pdf_storage_key=?,pdf_sha256=?,submitted_at=?,updated_at=now() where id=?",
                    ).use { ps ->
                        ps.setString(1, targetState.name)
                        ps.setString(2, json.encodeToString(ReportNarrativeDto.serializer(), narrative))
                        ps.setString(3, json.encodeToString(DailyReportSnapshotDto.serializer(), snapshot))
                        ps.setTimestamp(4, Timestamp.from(Instant.parse(snapshot.dataCutoffAt)))
                        ps.setString(5, storedKey)
                        ps.setString(6, sha)
                        ps.setTimestamp(7, Timestamp.from(submittedAt))
                        ps.setObject(8, row.versionId)
                        ps.executeUpdate()
                    }
                reportEvent(c, actor, reportId, row.versionId, "REPORT_SUBMITTED", mapOf("versionNo" to row.versionNo.toString(), "pdfSha256" to sha))
                audit(c, actor, reportId, "REPORT_SUBMITTED", mapOf("versionNo" to row.versionNo.toString(), "pdfSha256" to sha))
                outbox(c, "REPORT_SUBMITTED", mapOf("reportId" to reportId.toString(), "siteId" to row.siteId.toString(), "versionNo" to row.versionNo.toString()))
                val view = loadVersion(c, reportId, row.versionNo, false)?.toView() ?: throw AuthFailure("REPORT_NOT_FOUND")
                c.commit()
                previousKey?.takeIf { it != storedKey }?.let { runCatching { blobStore.delete(it) } }
                return view
            } catch (e: Exception) {
                c.rollback()
                storedKey?.let { runCatching { blobStore.delete(it) } }
                throw e
            }
        }
    }

    fun approve(
        actor: Principal,
        reportId: UUID,
        expectedVersionNo: Int,
        comment: String?,
    ): ReportViewDto {
        requirePermission(actor, "REPORT_APPROVE")
        var storedKey: String? = null
        var previousKey: String? = null
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val row = currentForUpdate(c, reportId, expectedVersionNo)
                authorizeApprove(actor, row)
                if (row.preparedBy == actor.id) throw AuthFailure("APPROVER_CANNOT_BE_AUTHOR")
                if (row.state !in setOf(ReportState.SUBMITTED, ReportState.RESUBMITTED)) throw AuthFailure("INVALID_REPORT_STATE")
                val snapshot = json.decodeFromString(DailyReportSnapshotDto.serializer(), row.snapshotJson)
                val narrative = json.decodeFromString(ReportNarrativeDto.serializer(), row.narrativeJson)
                val approvedAt = now()
                val approvedByName = loadUserName(c, actor.id) ?: actor.username
                val pdf = renderer.render(snapshot, narrative, ReportState.APPROVED, row.versionNo, approvedByName, approvedAt.toString())
                storedKey = blobStore.put(ByteArrayInputStream(pdf), ".pdf")
                val sha = sha256(pdf)
                previousKey = row.pdfStorageKey
                c
                    .prepareStatement(
                        "update report_version set state='APPROVED',pdf_storage_key=?,pdf_sha256=?,approved_by=?,approved_at=?,approval_comment=?,updated_at=now() where id=?",
                    ).use { ps ->
                        ps.setString(1, storedKey)
                        ps.setString(2, sha)
                        ps.setObject(3, actor.id)
                        ps.setTimestamp(4, Timestamp.from(approvedAt))
                        ps.setString(5, comment?.trim()?.ifBlank { null })
                        ps.setObject(6, row.versionId)
                        ps.executeUpdate()
                    }
                reportEvent(c, actor, reportId, row.versionId, "REPORT_APPROVED", mapOf("versionNo" to row.versionNo.toString(), "pdfSha256" to sha, "previousPdfSha256" to (row.pdfSha256 ?: "")))
                audit(c, actor, reportId, "REPORT_APPROVED", mapOf("versionNo" to row.versionNo.toString(), "pdfSha256" to sha))
                outbox(c, "REPORT_APPROVED", mapOf("reportId" to reportId.toString(), "siteId" to row.siteId.toString(), "versionNo" to row.versionNo.toString()))
                val view = loadVersion(c, reportId, row.versionNo, false)?.toView() ?: throw AuthFailure("REPORT_NOT_FOUND")
                c.commit()
                if (previousKey != null && previousKey != storedKey) runCatching { blobStore.delete(previousKey!!) }
                return view
            } catch (e: Exception) {
                c.rollback()
                storedKey?.let { runCatching { blobStore.delete(it) } }
                throw e
            }
        }
    }

    fun returnForCorrection(
        actor: Principal,
        reportId: UUID,
        expectedVersionNo: Int,
        reason: String?,
    ): ReportViewDto =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val row = currentForUpdate(c, reportId, expectedVersionNo)
                authorizeApprove(actor, row)
                if (row.preparedBy == actor.id) throw AuthFailure("APPROVER_CANNOT_BE_AUTHOR")
                if (row.state !in setOf(ReportState.SUBMITTED, ReportState.RESUBMITTED)) throw AuthFailure("INVALID_REPORT_STATE")
                val correctionReason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("A correction reason is required.")
                c.prepareStatement("update report_version set state='RETURNED_FOR_CORRECTION',returned_by=?,returned_at=now(),correction_reason=?,updated_at=now() where id=?").use { ps ->
                    ps.setObject(1, actor.id)
                    ps.setString(2, correctionReason)
                    ps.setObject(3, row.versionId)
                    ps.executeUpdate()
                }
                reportEvent(c, actor, reportId, row.versionId, "REPORT_RETURNED", mapOf("versionNo" to row.versionNo.toString(), "reason" to correctionReason))
                audit(c, actor, reportId, "REPORT_RETURNED", mapOf("versionNo" to row.versionNo.toString(), "reason" to correctionReason))
                outbox(c, "REPORT_RETURNED", mapOf("reportId" to reportId.toString(), "siteId" to row.siteId.toString(), "versionNo" to row.versionNo.toString()))
                val view = loadVersion(c, reportId, row.versionNo, false)?.toView() ?: throw AuthFailure("REPORT_NOT_FOUND")
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    fun newVersion(
        actor: Principal,
        reportId: UUID,
        expectedVersionNo: Int,
        reason: String?,
    ): ReportViewDto =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val row = currentForUpdate(c, reportId, expectedVersionNo)
                authorizePrepare(actor, row)
                if (row.preparedBy != actor.id) throw AuthFailure("FORBIDDEN")
                if (row.state != ReportState.APPROVED) throw AuthFailure("INVALID_REPORT_STATE")
                val correctionReason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("A correction reason is required.")
                val snapshot = snapshotBuilder.build(actor, row.siteId, row.reportDate, row.period)
                val newId = UUID.randomUUID()
                val newVersionNo = row.versionNo + 1
                c.prepareStatement("update report_version set state='SUPERSEDED',updated_at=now() where id=?").use { ps ->
                    ps.setObject(1, row.versionId)
                    ps.executeUpdate()
                }
                c
                    .prepareStatement(
                        "insert into report_version(id,report_id,version_no,state,narrative,snapshot,data_cutoff_at,correction_reason,created_by,created_at,updated_at) values (?,?,?,'DRAFT',?::jsonb,?::jsonb,?,?,?,now(),now())",
                    ).use { ps ->
                        ps.setObject(1, newId)
                        ps.setObject(2, reportId)
                        ps.setInt(3, newVersionNo)
                        ps.setString(4, row.narrativeJson)
                        ps.setString(5, json.encodeToString(DailyReportSnapshotDto.serializer(), snapshot))
                        ps.setTimestamp(6, Timestamp.from(Instant.parse(snapshot.dataCutoffAt)))
                        ps.setString(7, correctionReason)
                        ps.setObject(8, actor.id)
                        ps.executeUpdate()
                    }
                reportEvent(c, actor, reportId, row.versionId, "REPORT_SUPERSEDED", mapOf("versionNo" to row.versionNo.toString(), "newVersionNo" to newVersionNo.toString()))
                reportEvent(c, actor, reportId, newId, "REPORT_VERSION_CREATED", mapOf("versionNo" to newVersionNo.toString(), "reason" to correctionReason))
                audit(c, actor, reportId, "REPORT_VERSION_CREATED", mapOf("versionNo" to newVersionNo.toString(), "reason" to correctionReason))
                val view = loadVersion(c, reportId, newVersionNo, false)?.toView() ?: throw AuthFailure("REPORT_NOT_FOUND")
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    fun pdf(
        actor: Principal,
        reportId: UUID,
        versionNo: Int? = null,
    ): ReportPdf {
        val row =
            dataSource.connection.use { c ->
                val row = loadVersion(c, reportId, versionNo, false) ?: throw AuthFailure("REPORT_NOT_FOUND")
                authorizePdf(actor, row)
                row
            }
        val fileName = DailyReportPdfRenderer.fileName(row.siteName, row.reportDate, row.period)
        val storedKey = row.pdfStorageKey
        val bytes =
            if (row.state !in setOf(ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION) && !storedKey.isNullOrBlank()) {
                val stored = blobStore.open(storedKey).use { it.readBytes() }
                if (!sha256(stored).equals(row.pdfSha256, ignoreCase = true)) throw AuthFailure("REPORT_PDF_INTEGRITY_FAILED")
                stored
            } else {
                val snapshot = snapshotBuilder.build(actor, row.siteId, row.reportDate, row.period)
                val narrative = json.decodeFromString(ReportNarrativeDto.serializer(), row.narrativeJson)
                renderer.render(snapshot, narrative, row.state, row.versionNo, row.approvedByName, row.approvedAt?.toString())
            }
        dataSource.connection.use { c ->
            audit(
                c,
                actor,
                reportId,
                "REPORT_PDF_DOWNLOADED",
                mapOf(
                    "versionNo" to row.versionNo.toString(),
                    "state" to row.state.name,
                    "pdfSha256" to sha256(bytes),
                    "source" to if (storedKey.isNullOrBlank() || row.state in setOf(ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION)) "REGENERATED" else "STORED",
                ),
            )
        }
        return ReportPdf(fileName, bytes)
    }

    private fun authorizeView(
        actor: Principal,
        row: ReportRow,
    ) {
        if (!canViewReports(actor)) throw AuthFailure("FORBIDDEN")
        if (!hasSiteAccess(actor, row.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
        if (isApprovedOnly(actor) && row.state !in setOf(ReportState.APPROVED, ReportState.SUPERSEDED)) throw AuthFailure("FORBIDDEN")
    }

    private fun authorizePdf(
        actor: Principal,
        row: ReportRow,
    ) {
        if (!hasSiteAccess(actor, row.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
        when (row.state) {
            ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION ->
                if (row.preparedBy != actor.id) throw AuthFailure("FORBIDDEN")
            ReportState.SUBMITTED, ReportState.RESUBMITTED ->
                if (row.preparedBy != actor.id && !RolePermissions.allows(actor.role, "REPORT_APPROVE")) throw AuthFailure("FORBIDDEN")
            else -> authorizeView(actor, row)
        }
    }

    private fun authorizePrepare(
        actor: Principal,
        row: ReportRow,
    ) {
        requirePermission(actor, "REPORT_PREPARE")
        if (!hasSiteAccess(actor, row.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
    }

    private fun authorizeApprove(
        actor: Principal,
        row: ReportRow,
    ) {
        requirePermission(actor, "REPORT_APPROVE")
        if (!hasSiteAccess(actor, row.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
    }

    private fun canViewReports(actor: Principal): Boolean =
        listOf("REPORT_PREPARE", "REPORT_APPROVE", "REPORT_VIEW_APPROVED", "REPORT_VIEW_OWN_SITE").any {
            RolePermissions.allows(actor.role, it)
        }

    private fun isApprovedOnly(actor: Principal): Boolean =
        RolePermissions.allows(actor.role, "REPORT_VIEW_APPROVED") &&
            !RolePermissions.allows(actor.role, "REPORT_VIEW_OWN_SITE") &&
            !RolePermissions.allows(actor.role, "REPORT_PREPARE") &&
            !RolePermissions.allows(actor.role, "REPORT_APPROVE")

    private fun requirePermission(
        actor: Principal,
        permission: String,
    ) {
        if (!RolePermissions.allows(actor.role, permission)) throw AuthFailure("FORBIDDEN")
    }

    private fun requireSiteAccess(
        actor: Principal,
        siteId: UUID,
    ) {
        dataSource.connection.use { c -> if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED") }
    }

    private fun hasSiteAccess(
        actor: Principal,
        siteId: UUID,
    ): Boolean = dataSource.connection.use { c -> hasSiteAccess(c, actor, siteId) }

    private fun currentForUpdate(
        c: Connection,
        reportId: UUID,
        expectedVersionNo: Int,
    ): ReportRow {
        val row = loadVersion(c, reportId, null, true) ?: throw AuthFailure("REPORT_NOT_FOUND")
        if (row.versionNo != expectedVersionNo) throw AuthFailure("REPORT_VERSION_CONFLICT")
        return row
    }

    private fun loadVersion(
        c: Connection,
        reportId: UUID,
        versionNo: Int?,
        forUpdate: Boolean,
    ): ReportRow? {
        val versionClause = if (versionNo == null) "order by rv.version_no desc limit 1" else "and rv.version_no=?"
        val lock = if (forUpdate) " for update of rv" else ""
        return c.prepareStatement("$VERSION_SELECT where dr.id=? $versionClause$lock").use { ps ->
            ps.setObject(1, reportId)
            if (versionNo != null) ps.setInt(2, versionNo)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRow() else null }
        }
    }

    private fun ResultSet.toRow(): ReportRow =
        ReportRow(
            reportId = getObject("report_id", UUID::class.java),
            reportRef = getString("report_ref"),
            siteId = getObject("site_id", UUID::class.java),
            siteName = getString("site_name"),
            reportDate = getObject("report_date", LocalDate::class.java),
            period = ReportPeriod.valueOf(getString("period")),
            preparedBy = getObject("prepared_by", UUID::class.java),
            preparedByName = getString("prepared_by_name"),
            versionId = getObject("version_id", UUID::class.java),
            versionNo = getInt("version_no"),
            state = ReportState.valueOf(getString("state")),
            narrativeJson = getString("narrative_json"),
            snapshotJson = getString("snapshot_json"),
            dataCutoffAt = getTimestamp("data_cutoff_at").toInstant(),
            pdfStorageKey = getString("pdf_storage_key"),
            pdfSha256 = getString("pdf_sha256"),
            submittedAt = getTimestamp("submitted_at")?.toInstant(),
            approvedBy = getObject("approved_by", UUID::class.java),
            approvedByName = getString("approved_by_name"),
            approvedAt = getTimestamp("approved_at")?.toInstant(),
            approvalComment = getString("approval_comment"),
            returnedBy = getObject("returned_by", UUID::class.java),
            returnedByName = getString("returned_by_name"),
            returnedAt = getTimestamp("returned_at")?.toInstant(),
            correctionReason = getString("correction_reason"),
            createdAt = getTimestamp("version_created_at").toInstant(),
            updatedAt = getTimestamp("version_updated_at").toInstant(),
        )

    private fun ReportRow.toView(): ReportViewDto =
        ReportViewDto(
            id = reportId.toString(),
            reportRef = reportRef,
            siteId = siteId.toString(),
            siteName = siteName,
            reportDate = reportDate.toString(),
            period = period,
            versionId = versionId.toString(),
            versionNo = versionNo,
            state = state,
            preparedBy = preparedBy.toString(),
            preparedByName = preparedByName,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            dataCutoffAt = dataCutoffAt.toString(),
            submittedAt = submittedAt?.toString(),
            approvedBy = approvedBy?.toString(),
            approvedByName = approvedByName,
            approvedAt = approvedAt?.toString(),
            approvalComment = approvalComment,
            returnedBy = returnedBy?.toString(),
            returnedByName = returnedByName,
            returnedAt = returnedAt?.toString(),
            correctionReason = correctionReason,
            pdfSha256 = pdfSha256,
            narrative = json.decodeFromString(ReportNarrativeDto.serializer(), narrativeJson),
            snapshot = json.decodeFromString(DailyReportSnapshotDto.serializer(), snapshotJson),
        )

    private fun reportEvent(
        c: Connection,
        actor: Principal,
        reportId: UUID,
        versionId: UUID,
        action: String,
        metadata: Map<String, String>,
    ) {
        c.prepareStatement("insert into report_event(id,report_id,version_id,action,actor_id,occurred_at,metadata) values (?,?,?,?,?,now(),?::jsonb)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, reportId)
            ps.setObject(3, versionId)
            ps.setString(4, action)
            ps.setObject(5, actor.id)
            ps.setString(6, Json.encodeToString(metadata))
            ps.executeUpdate()
        }
    }

    private fun audit(
        c: Connection,
        actor: Principal,
        entityId: UUID,
        action: String,
        metadata: Map<String, String>,
    ) {
        val payload = Json.encodeToString(metadata)
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,'daily_report',?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor.id)
            ps.setString(2, action)
            ps.setString(3, entityId.toString())
            ps.setString(4, payload)
            ps.setBytes(5, MessageDigest.getInstance("SHA-256").digest("$entityId:$action:$payload".toByteArray()))
            ps.executeUpdate()
        }
    }

    private fun outbox(
        c: Connection,
        topic: String,
        payload: Map<String, String>,
    ) {
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,?,?::jsonb,now())").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, topic)
            ps.setString(3, Json.encodeToString(payload))
            ps.executeUpdate()
        }
    }

    private fun loadUserName(
        c: Connection,
        userId: UUID,
    ): String? =
        c.prepareStatement("select full_name from app_user where id=?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun hasSiteAccess(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ): Boolean =
        c.prepareStatement("select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))").use { ps ->
            ps.setObject(1, actor.id)
            ps.setObject(2, siteId)
            ps.setBoolean(3, actor.role in broadRoles)
            ps.setObject(4, actor.id)
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun siteAccessSql(column: String) = "(exists(select 1 from user_site_access a where a.user_id=? and a.site_id=$column) or (? and not exists(select 1 from user_site_access a where a.user_id=?)))"

    private fun bindSiteAccess(
        ps: java.sql.PreparedStatement,
        actor: Principal,
        first: Int,
    ) {
        ps.setObject(first, actor.id)
        ps.setBoolean(first + 1, actor.role in broadRoles)
        ps.setObject(first + 2, actor.id)
    }

    private fun reportRef(
        siteId: UUID,
        reportDate: LocalDate,
        period: ReportPeriod,
    ): String {
        val siteCode =
            dataSource.connection.use { c ->
                c.prepareStatement("select code from site where id=?").use { ps ->
                    ps.setObject(1, siteId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "SITE" }
                }
            }
        return "RPT-$siteCode-${reportDate.toString().replace("-", "")}-${period.name}-${UUID.randomUUID().toString().take(6).uppercase()}"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun uuid(
        value: String,
        label: String,
    ): UUID = runCatching { UUID.fromString(value) }.getOrElse { throw IllegalArgumentException("$label ID is invalid.") }

    private fun date(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("Report date is invalid.") }

    private data class ReportRow(
        val reportId: UUID,
        val reportRef: String,
        val siteId: UUID,
        val siteName: String,
        val reportDate: LocalDate,
        val period: ReportPeriod,
        val preparedBy: UUID,
        val preparedByName: String,
        val versionId: UUID,
        val versionNo: Int,
        val state: ReportState,
        val narrativeJson: String,
        val snapshotJson: String,
        val dataCutoffAt: Instant,
        val pdfStorageKey: String?,
        val pdfSha256: String?,
        val submittedAt: Instant?,
        val approvedBy: UUID?,
        val approvedByName: String?,
        val approvedAt: Instant?,
        val approvalComment: String?,
        val returnedBy: UUID?,
        val returnedByName: String?,
        val returnedAt: Instant?,
        val correctionReason: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    companion object {
        private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)

        private val VERSION_SELECT =
            """
            select dr.id report_id,dr.report_ref,dr.site_id,s.name site_name,dr.report_date,dr.period,
              dr.prepared_by,pu.full_name prepared_by_name,
              rv.id version_id,rv.version_no,rv.state,rv.narrative::text narrative_json,rv.snapshot::text snapshot_json,
              rv.data_cutoff_at,rv.pdf_storage_key,rv.pdf_sha256,rv.submitted_at,
              rv.approved_by,au.full_name approved_by_name,rv.approved_at,rv.approval_comment,
              rv.returned_by,ru.full_name returned_by_name,rv.returned_at,rv.correction_reason,
              rv.created_at version_created_at,rv.updated_at version_updated_at
            from report_version rv
            join daily_report dr on dr.id=rv.report_id
            join site s on s.id=dr.site_id
            join app_user pu on pu.id=dr.prepared_by
            left join app_user au on au.id=rv.approved_by
            left join app_user ru on ru.id=rv.returned_by
            """.trimIndent()
    }
}
