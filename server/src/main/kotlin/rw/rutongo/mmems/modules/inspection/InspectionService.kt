@file:Suppress("TooManyFunctions", "LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod", "MagicNumber", "TooGenericExceptionCaught", "LongParameterList", "ThrowsCount", "LargeClass")

package rw.rutongo.mmems.modules.inspection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.modules.inspection.domain.AnswerWithSeverity
import rw.rutongo.mmems.modules.inspection.domain.ChecklistCategory
import rw.rutongo.mmems.modules.inspection.domain.ChecklistItem
import rw.rutongo.mmems.modules.inspection.domain.EngineHours
import rw.rutongo.mmems.modules.inspection.domain.InspectionAnswerInput
import rw.rutongo.mmems.modules.inspection.domain.InspectionFieldError
import rw.rutongo.mmems.modules.inspection.domain.InspectionSubmission
import rw.rutongo.mmems.modules.inspection.domain.InspectionValidator
import rw.rutongo.mmems.modules.inspection.domain.ValidationOutcome
import rw.rutongo.mmems.modules.inspection.domain.WorkingHoursStatus
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.InspectionResult
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.Severity
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

@Serializable data class ExpectedInspectionView(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String,
    val equipmentName: String,
    val siteId: String,
    val siteName: String,
    val shiftId: String,
    val shiftCode: String,
    val reportDate: String,
    val windowStart: String,
    val windowEnd: String,
    val state: String,
    val missingMarkedAt: String?,
    val inspectionId: String?,
)

@Serializable data class InspectionSummaryView(
    val id: String,
    val clientUuid: String,
    val expectationId: String?,
    val equipmentId: String,
    val siteId: String,
    val tunnelId: String?,
    val shiftId: String,
    val reportDate: String,
    val operatorId: String?,
    val operatorEid: String?,
    val inspectorId: String,
    val inspectorEid: String,
    val supervisorId: String?,
    val engineHoursStart: Double?,
    val engineHoursEnd: Double?,
    val fuelStartPercent: Double?,
    val fuelAddedLitres: Double?,
    val result: String,
    val submittedLate: Boolean,
    val hoursWarnings: List<String>,
    val capturedAt: String,
    val startedAt: String,
    val submittedAt: String,
)

@Serializable data class AnswerView(
    val itemId: String,
    val category: String,
    val label: String,
    val answer: String,
    val naReason: String?,
    val defectId: String?,
    val photoIds: List<String>,
)

@Serializable data class DefectView(
    val id: String,
    val equipmentId: String,
    val inspectionId: String?,
    val checklistItemId: String?,
    val category: String,
    val description: String,
    val severity: String,
    val immediateAction: String?,
    val state: String,
    val breakdownId: String?,
    val reportedBy: String,
    val reportedAt: String,
    val resolvedAt: String?,
    val resolvedBy: String?,
    val version: Long,
)

@Serializable data class InspectionDetailView(
    val inspection: InspectionSummaryView,
    val answers: List<AnswerView>,
    val defects: List<DefectView>,
)

@Serializable data class DraftView(
    val id: String,
    val expectationId: String?,
    val equipmentId: String,
    val userId: String,
    val payload: String,
    val updatedAt: String,
)

@Serializable data class ChecklistItemView(
    val id: String,
    val category: String,
    val code: String,
    val label: String,
    val sortOrder: Int,
    val mandatory: Boolean,
    val safetyCritical: Boolean,
    val photoRequiredOnFail: Boolean,
    val naReasonRequired: Boolean,
    val active: Boolean,
)

@Serializable data class CorrectionView(
    val id: String,
    val inspectionId: String,
    val changes: String,
    val reason: String,
    val requestedBy: String,
    val requestedAt: String,
    val decision: String?,
    val decidedBy: String?,
    val decidedAt: String?,
    val decisionComment: String?,
)

class InspectionValidationException(
    val errors: List<InspectionFieldError>,
) : IllegalArgumentException(errors.joinToString(" ") { it.message })

class InspectionService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun submit(
        actor: Principal,
        submission: InspectionSubmission,
    ): InspectionDetailView {
        val clientUuid = UUID.fromString(submission.clientUuid)
        require(submission.answers.isNotEmpty()) { "At least one answer is required" }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                existingInspection(c, clientUuid)?.let {
                    authorizeInspection(c, actor, UUID.fromString(it.siteId))
                    c.rollback()
                    return loadDetail(c, actor, UUID.fromString(it.id))
                }
                val equipment = lockEquipment(c, actor, UUID.fromString(submission.equipmentId))
                if (!RolePermissions.allows(actor.role, "INSPECTION_SUBMIT")) throw AuthFailure("FORBIDDEN")
                if (!hasSiteAccess(c, actor, equipment.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                val shiftId = UUID.fromString(submission.shiftId)
                if (!exists(c, "select exists(select 1 from shift where id=?)", shiftId)) throw AuthFailure("SHIFT_NOT_FOUND")
                val expectation = submission.expectationId?.let { lockExpectation(c, UUID.fromString(it)) }
                if (expectation != null) {
                    if (expectation.equipmentId != equipment.id || expectation.shiftId != shiftId || expectation.reportDate != submission.reportDate) {
                        throw AuthFailure("INSPECTION_EXPECTATION_MISMATCH")
                    }
                    if (expectation.inspectionId != null) throw AuthFailure("INSPECTION_EXPECTATION_ALREADY_COMPLETED")
                }
                val items = checklistItems(c, equipment.typeId)
                val lastReading = lastEngineReading(c, equipment.id)
                val outcome = InspectionValidator.validate(submission, items, lastReading)
                if (!outcome.valid) throw InspectionValidationException(outcome.errors)
                val submittedAt = now()
                val submittedLate = expectation != null && (expectation.state == "MISSING" || submittedAt.isAfter(expectation.windowEnd))
                val result = resultFor(submission.answers)
                val inspectionId = UUID.randomUUID()
                insertInspection(c, inspectionId, actor, equipment, shiftId, submission, result, outcome, submittedAt, submittedLate)
                insertAnswersAndDefects(c, inspectionId, actor, equipment, submission.answers, items)
                applyStatusRules(c, actor, equipment, inspectionId, shiftId, submission, items, result, submittedAt)
                val workingStatus = workingHoursStatus(c, equipment.id, submission, outcome)
                insertWorkingHours(c, inspectionId, equipment, shiftId, submission, workingStatus)
                insertEngineReadings(c, inspectionId, equipment.id, submission, submittedAt, workingStatus)
                projectEquipment(c, equipment, inspectionId, submission)
                expectation?.let {
                    c.prepareStatement("update inspection_expectation set state=?,inspection_id=? where id=?").use { ps ->
                        ps.setString(1, result.name)
                        ps.setObject(2, inspectionId)
                        ps.setObject(3, expectation.id)
                        ps.executeUpdate()
                    }
                }
                audit(c, actor, inspectionId, result, submittedLate)
                outbox(c, inspectionId, equipment, result, submittedLate)
                val detail = loadDetail(c, actor, inspectionId)
                c.commit()
                return detail
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun expected(
        actor: Principal,
        siteId: UUID? = null,
    ): List<ExpectedInspectionView> =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
            c
                .prepareStatement(
                    """
                    select x.*,e.equipment_code,e.name equipment_name,e.site_id equipment_site_id,s.name site_name,sh.code shift_code
                    from inspection_expectation x
                    join equipment e on e.id=x.equipment_id
                    join site s on s.id=x.site_id
                    join shift sh on sh.id=x.shift_id
                    where x.state in ('NOT_STARTED','IN_PROGRESS')
                      and (?::uuid is null or x.site_id=?)
                      and ${siteAccessSql()}
                    order by x.window_end,x.equipment_id
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setObject(2, siteId)
                    bindSiteAccess(ps, actor, 3)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toExpectedView()) } }
                }
        }

    fun control(
        actor: Principal,
        reportDate: LocalDate? = null,
        siteId: UUID? = null,
    ): List<ExpectedInspectionView> =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
            c
                .prepareStatement(
                    """
                    select x.*,e.equipment_code,e.name equipment_name,e.site_id equipment_site_id,s.name site_name,sh.code shift_code
                    from inspection_expectation x
                    join equipment e on e.id=x.equipment_id
                    join site s on s.id=x.site_id
                    join shift sh on sh.id=x.shift_id
                    where (?::date is null or x.report_date=?)
                      and (?::uuid is null or x.site_id=?)
                      and ${siteAccessSql()}
                    order by x.report_date desc,x.window_end,x.equipment_id
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, reportDate)
                    ps.setObject(2, reportDate)
                    ps.setObject(3, siteId)
                    ps.setObject(4, siteId)
                    bindSiteAccess(ps, actor, 5)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toExpectedView()) } }
                }
        }

    fun detail(
        actor: Principal,
        inspectionId: UUID,
    ): InspectionDetailView = dataSource.connection.use { c -> loadDetail(c, actor, inspectionId) }

    private fun loadDetail(
        c: Connection,
        actor: Principal,
        inspectionId: UUID,
    ): InspectionDetailView {
        val summary = loadSummary(c, inspectionId)
        authorizeInspection(c, actor, UUID.fromString(summary.siteId))
        val photos =
            c.prepareStatement("select checklist_item_id,photo_id from inspection_answer_photo where inspection_id=?").use { ps ->
                ps.setObject(1, inspectionId)
                ps.executeQuery().use { rs ->
                    buildMap<String, MutableList<String>> {
                        while (rs.next()) {
                            val itemId = rs.getObject(1, UUID::class.java).toString()
                            getOrPut(itemId) { mutableListOf() } += rs.getObject(2, UUID::class.java).toString()
                        }
                    }
                }
            }
        val answers =
            c.prepareStatement("select * from inspection_answer where inspection_id=? order by category,label_snapshot").use { ps ->
                ps.setObject(1, inspectionId)
                ps.executeQuery().use {
                    buildList {
                        while (it.next()) {
                            val itemId = it.getObject("checklist_item_id", UUID::class.java).toString()
                            add(it.toAnswerView(photos[itemId].orEmpty()))
                        }
                    }
                }
            }
        val defects =
            c.prepareStatement("select * from defect where inspection_id=? order by reported_at").use { ps ->
                ps.setObject(1, inspectionId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDefectView()) } }
            }
        return InspectionDetailView(summary, answers, defects)
    }

    fun saveDraft(
        actor: Principal,
        equipmentId: UUID,
        expectationId: UUID?,
        payload: String,
    ): DraftView =
        dataSource.connection.use { c ->
            val siteId = siteForEquipment(c, equipmentId)
            if (!RolePermissions.allows(actor.role, "INSPECTION_SUBMIT")) throw AuthFailure("FORBIDDEN")
            if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            if (expectationId != null && !exists(c, "select exists(select 1 from inspection_expectation where id=? and equipment_id=?)", expectationId, equipmentId)) {
                throw AuthFailure("EXPECTATION_NOT_FOUND")
            }
            val id =
                c
                    .prepareStatement(
                        """
                        insert into inspection_draft(id,expectation_id,equipment_id,user_id,payload,updated_at)
                        values (?,?,?,?,?::jsonb,now())
                        on conflict (equipment_id,user_id,expectation_id) do update set payload=excluded.payload,updated_at=now()
                        returning id
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, UUID.randomUUID())
                        ps.setObject(2, expectationId)
                        ps.setObject(3, equipmentId)
                        ps.setObject(4, actor.id)
                        ps.setString(5, payload)
                        ps.executeQuery().use {
                            it.next()
                            it.getObject(1, UUID::class.java)
                        }
                    }
            expectationId?.let {
                c.prepareStatement("update inspection_expectation set state='IN_PROGRESS' where id=? and state='NOT_STARTED'").use { ps ->
                    ps.setObject(1, it)
                    ps.executeUpdate()
                }
            }
            c.prepareStatement("select * from inspection_draft where id=?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.toDraftView()
                }
            }
        }

    fun checklistItems(
        actor: Principal,
        activeOnly: Boolean = false,
    ): List<ChecklistItemView> =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
            c.prepareStatement("select * from checklist_item where (not ? or active) order by sort_order,code").use { ps ->
                ps.setBoolean(1, activeOnly)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toChecklistItemView()) } }
            }
        }

    fun createChecklistItem(
        actor: Principal,
        item: ChecklistItemView,
    ): ChecklistItemView =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "CHECKLIST_MANAGE")) throw AuthFailure("FORBIDDEN")
            c
                .prepareStatement(
                    """
                    insert into checklist_item(id,category,code,label,sort_order,mandatory,safety_critical,photo_required_on_fail,na_reason_required,active)
                    values (?,?,?,?,?,?,?,?,?,?) returning *
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setString(2, item.category)
                    ps.setString(3, item.code)
                    ps.setString(4, item.label)
                    ps.setInt(5, item.sortOrder)
                    ps.setBoolean(6, item.mandatory)
                    ps.setBoolean(7, item.safetyCritical)
                    ps.setBoolean(8, item.photoRequiredOnFail)
                    ps.setBoolean(9, item.naReasonRequired)
                    ps.setBoolean(10, item.active)
                    ps.executeQuery().use {
                        it.next()
                        it.toChecklistItemView()
                    }
                }
        }

    fun updateChecklistItem(
        actor: Principal,
        id: UUID,
        item: ChecklistItemView,
    ): ChecklistItemView =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "CHECKLIST_MANAGE")) throw AuthFailure("FORBIDDEN")
            c
                .prepareStatement(
                    """
                    update checklist_item set category=?,code=?,label=?,sort_order=?,mandatory=?,safety_critical=?,
                      photo_required_on_fail=?,na_reason_required=?,active=? where id=? returning *
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, item.category)
                    ps.setString(2, item.code)
                    ps.setString(3, item.label)
                    ps.setInt(4, item.sortOrder)
                    ps.setBoolean(5, item.mandatory)
                    ps.setBoolean(6, item.safetyCritical)
                    ps.setBoolean(7, item.photoRequiredOnFail)
                    ps.setBoolean(8, item.naReasonRequired)
                    ps.setBoolean(9, item.active)
                    ps.setObject(10, id)
                    ps.executeQuery().use {
                        if (!it.next()) throw AuthFailure("CHECKLIST_ITEM_NOT_FOUND")
                        it.toChecklistItemView()
                    }
                }
        }

    fun requestCorrection(
        actor: Principal,
        inspectionId: UUID,
        changes: String,
        reason: String,
    ): CorrectionView {
        require(reason.isNotBlank()) { "A correction reason is required" }
        return dataSource.connection.use { c ->
            val summary = loadSummary(c, inspectionId)
            if (!RolePermissions.allows(actor.role, "INSPECTION_SUBMIT")) throw AuthFailure("FORBIDDEN")
            if (!hasSiteAccess(c, actor, UUID.fromString(summary.siteId))) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("insert into inspection_correction(id,original_inspection_id,changes,reason,requested_by,requested_at) values (?,?,?::jsonb,?,?,?) returning *").use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, inspectionId)
                ps.setString(3, changes)
                ps.setString(4, reason)
                ps.setObject(5, actor.id)
                ps.setTimestamp(6, Timestamp.from(now()))
                ps.executeQuery().use {
                    it.next()
                    it.toCorrectionView()
                }
            }
        }
    }

    fun decideCorrection(
        actor: Principal,
        correctionId: UUID,
        decision: String,
        comment: String?,
    ): CorrectionView {
        require(decision == "APPROVED" || decision == "REJECTED") { "Decision must be APPROVED or REJECTED" }
        return dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "INSPECTION_REVIEW")) throw AuthFailure("FORBIDDEN")
            val siteId =
                c.prepareStatement("select i.site_id from inspection_correction c join inspection i on i.id=c.original_inspection_id where c.id=?").use { ps ->
                    ps.setObject(1, correctionId)
                    ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else throw AuthFailure("CORRECTION_NOT_FOUND") }
                }
            if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("update inspection_correction set decision=?,decided_by=?,decided_at=?,decision_comment=? where id=? and decision is null returning *").use { ps ->
                ps.setString(1, decision)
                ps.setObject(2, actor.id)
                ps.setTimestamp(3, Timestamp.from(now()))
                ps.setString(4, comment?.trim())
                ps.setObject(5, correctionId)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("CORRECTION_ALREADY_DECIDED")
                    it.toCorrectionView()
                }
            }
        }
    }

    fun defects(
        actor: Principal,
        equipmentId: UUID? = null,
        state: String? = null,
    ): List<DefectView> =
        dataSource.connection.use { c ->
            if (actor.role !in defectRoles) throw AuthFailure("FORBIDDEN")
            c
                .prepareStatement(
                    """
                    select d.* from defect d join equipment e on e.id=d.equipment_id
                    where (?::uuid is null or d.equipment_id=?) and (?::text is null or d.state=?)
                      and ${siteAccessSql("e.site_id")}
                    order by d.reported_at desc
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, equipmentId)
                    ps.setObject(2, equipmentId)
                    ps.setString(3, state)
                    ps.setString(4, state)
                    bindSiteAccess(ps, actor, 5)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toDefectView()) } }
                }
        }

    fun updateDefect(
        actor: Principal,
        defectId: UUID,
        state: String?,
        severity: String?,
        immediateAction: String?,
        breakdownId: UUID?,
        expectedVersion: Long,
    ): DefectView =
        dataSource.connection.use { c ->
            if (actor.role !in defectRoles) throw AuthFailure("FORBIDDEN")
            val siteId =
                c.prepareStatement("select e.site_id from defect d join equipment e on e.id=d.equipment_id where d.id=?").use { ps ->
                    ps.setObject(1, defectId)
                    ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else throw AuthFailure("DEFECT_NOT_FOUND") }
                }
            if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            if (breakdownId != null) validateDefectBreakdown(c, defectId, breakdownId)
            c
                .prepareStatement(
                    """
                    update defect set state=coalesce(?,state),severity=coalesce(?,severity),
                      immediate_action=coalesce(?,immediate_action),breakdown_id=coalesce(?,breakdown_id),
                      resolved_at=case when ? in ('RESOLVED','CLOSED') then now() else resolved_at end,
                      resolved_by=case when ? in ('RESOLVED','CLOSED') then ? else resolved_by end,version=version+1
                    where id=? and version=? returning *
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, state)
                    ps.setString(2, severity)
                    ps.setString(3, immediateAction)
                    ps.setObject(4, breakdownId)
                    ps.setString(5, state)
                    ps.setString(6, state)
                    ps.setObject(7, actor.id)
                    ps.setObject(8, defectId)
                    ps.setLong(9, expectedVersion)
                    ps.executeQuery().use {
                        if (!it.next()) throw AuthFailure("DEFECT_VERSION_CONFLICT")
                        it.toDefectView()
                    }
                }
        }

    private fun insertInspection(
        c: Connection,
        id: UUID,
        actor: Principal,
        equipment: EquipmentLock,
        shiftId: UUID,
        s: InspectionSubmission,
        result: InspectionResult,
        outcome: ValidationOutcome,
        submittedAt: Instant,
        submittedLate: Boolean,
    ) {
        val attestation =
            s.attestation?.let {
                buildJsonObject {
                    put("name", it.name)
                    put("employeeId", it.employeeId)
                    put("method", it.method)
                    put("fingerprint", it.fingerprint)
                    put("signature", it.signature)
                }.toString()
            } ?: "{}"
        c
            .prepareStatement(
                """
                insert into inspection(id,client_uuid,expectation_id,equipment_id,site_id,tunnel_id,shift_id,report_date,
                  operator_id,operator_eid,inspector_id,inspector_eid,supervisor_id,engine_hours_start,engine_hours_end,
                  fuel_start_percent,fuel_added_litres,result,submitted_late,hours_warnings,captured_at,started_at,submitted_at,attestation)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, UUID.fromString(s.clientUuid))
                ps.setObject(3, s.expectationId?.let(UUID::fromString))
                ps.setObject(4, equipment.id)
                ps.setObject(5, equipment.siteId)
                ps.setObject(6, equipment.tunnelId)
                ps.setObject(7, shiftId)
                ps.setObject(8, s.reportDate)
                ps.setObject(9, s.operatorId?.let(UUID::fromString))
                ps.setString(10, s.operatorEid)
                ps.setObject(11, actor.id)
                ps.setString(12, equipment.employeeId)
                ps.setObject(13, s.supervisorId?.let(UUID::fromString))
                ps.setObject(14, s.engineHoursStart)
                ps.setObject(15, s.engineHoursEnd)
                ps.setObject(16, s.fuelStartPercent)
                ps.setObject(17, s.fuelAddedLitres)
                ps.setString(18, result.name)
                ps.setBoolean(19, submittedLate)
                ps.setArray(20, c.createArrayOf("text", outcome.warnings.toTypedArray()))
                ps.setTimestamp(21, Timestamp.from(s.capturedAt))
                ps.setTimestamp(22, Timestamp.from(s.startedAt))
                ps.setTimestamp(23, Timestamp.from(submittedAt))
                ps.setString(24, attestation)
                ps.executeUpdate()
            }
    }

    private fun insertAnswersAndDefects(
        c: Connection,
        inspectionId: UUID,
        actor: Principal,
        equipment: EquipmentLock,
        answers: List<InspectionAnswerInput>,
        items: List<ChecklistItem>,
    ) {
        val byId = items.associateBy { it.id }
        for (answer in answers.distinctBy { it.itemId }) {
            val item = byId[answer.itemId] ?: continue
            val defectId =
                if (answer.answer == Answer.FAIL) {
                    val defect = answer.defect ?: error("A failing answer requires a defect")
                    insertDefect(c, actor, equipment, inspectionId, item, defect.description ?: "", defect.severity, defect.immediateAction)
                } else {
                    null
                }
            c.prepareStatement("insert into inspection_answer(inspection_id,checklist_item_id,category,label_snapshot,answer,na_reason,defect_id) values (?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, inspectionId)
                ps.setObject(2, UUID.fromString(item.id))
                ps.setString(3, item.category.name)
                ps.setString(4, item.label)
                ps.setString(5, answer.answer.name)
                ps.setString(6, answer.naReason?.trim())
                ps.setObject(7, defectId)
                ps.executeUpdate()
            }
            answer.photoIds.distinct().forEach { photoId ->
                c.prepareStatement("insert into inspection_answer_photo(inspection_id,checklist_item_id,photo_id) values (?,?,?) on conflict do nothing").use { ps ->
                    ps.setObject(1, inspectionId)
                    ps.setObject(2, UUID.fromString(item.id))
                    ps.setObject(3, UUID.fromString(photoId))
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun applyStatusRules(
        c: Connection,
        actor: Principal,
        equipment: EquipmentLock,
        inspectionId: UUID,
        shiftId: UUID,
        submission: InspectionSubmission,
        items: List<ChecklistItem>,
        result: InspectionResult,
        occurredAt: Instant,
    ) {
        val byId = items.associateBy { it.id }
        val rules =
            c.prepareStatement("select code,trigger_result,trigger_severity,safety_critical_only,action from status_rule where active order by code").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StatusRule(
                                    rs.getString("code"),
                                    rs.getString("trigger_result"),
                                    rs.getString("trigger_severity"),
                                    rs.getBoolean("safety_critical_only"),
                                    rs.getString("action"),
                                ),
                            )
                        }
                    }
                }
            }
        for (rule in rules.filter { it.triggerResult == result.name }) {
            val matching =
                submission.answers.filter {
                    val item = byId[it.itemId]
                    it.answer == Answer.FAIL &&
                        it.defect?.severity?.name == rule.triggerSeverity &&
                        (!rule.safetyCriticalOnly || item?.safetyCritical == true)
                }
            if (matching.isEmpty()) continue
            val labels = matching.mapNotNull { byId[it.itemId]?.label }.distinct().sorted()
            if (rule.action == "AUTO_DOWN") {
                applySystemDown(c, actor, equipment, inspectionId, shiftId, rule, labels, occurredAt)
            } else {
                recommendDown(c, equipment, inspectionId, rule, labels, occurredAt)
            }
        }
    }

    private fun applySystemDown(
        c: Connection,
        actor: Principal,
        equipment: EquipmentLock,
        inspectionId: UUID,
        shiftId: UUID,
        rule: StatusRule,
        labels: List<String>,
        occurredAt: Instant,
    ): Boolean {
        if (equipment.currentStatus == "DOWN") return false
        val eventId = UUID.randomUUID()
        val problem = "Critical safety failure: ${labels.joinToString(", ")}"
        val sequence =
            c.prepareStatement("select coalesce(max(seq),0)+1 from equipment_status_event where equipment_id=?").use { ps ->
                ps.setObject(1, equipment.id)
                ps.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        c
            .prepareStatement(
                """
                insert into equipment_status_event(id,equipment_id,seq,previous_status,new_status,reason,problem,site_id,
                  tunnel_id,shift_id,user_id,user_eid,occurred_at,recorded_at,source,idempotency_key)
                values (?,?,?,?,?,'AUTOMATIC_STATUS_RULE',?,?,?,?,?,?,?,clock_timestamp(),'SYSTEM_RULE',?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, eventId)
                ps.setObject(2, equipment.id)
                ps.setLong(3, sequence)
                ps.setString(4, equipment.currentStatus)
                ps.setString(5, "DOWN")
                ps.setString(6, problem)
                ps.setObject(7, equipment.siteId)
                ps.setObject(8, equipment.tunnelId)
                ps.setObject(9, shiftId)
                ps.setObject(10, actor.id)
                ps.setString(11, equipment.employeeId)
                ps.setTimestamp(12, Timestamp.from(occurredAt))
                ps.setString(13, "inspection:$inspectionId:system-rule:${rule.code}")
                ps.executeUpdate()
            }
        val latest =
            c.prepareStatement("select id from equipment_status_event where equipment_id=? order by occurred_at desc,seq desc limit 1").use { ps ->
                ps.setObject(1, equipment.id)
                ps.executeQuery().use {
                    it.next()
                    it.getObject(1, UUID::class.java)
                }
            }
        if (latest == eventId) {
            c
                .prepareStatement(
                    """
                    update equipment set current_status='DOWN',current_status_event_id=?,current_problem=?,current_shift_id=?,
                      last_status_update_at=?,last_status_reported_by=?,version=version+1,updated_at=now() where id=?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, eventId)
                    ps.setString(2, problem)
                    ps.setObject(3, shiftId)
                    ps.setTimestamp(4, Timestamp.from(occurredAt))
                    ps.setObject(5, actor.id)
                    ps.setObject(6, equipment.id)
                    ps.executeUpdate()
                }
        }
        auditStatusRule(c, actor, equipment.id, eventId, inspectionId, rule.code)
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,'EQUIPMENT_STATUS_CHANGED',?::jsonb,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, "{\"eventId\":\"$eventId\",\"equipmentId\":\"${equipment.id}\",\"siteId\":\"${equipment.siteId}\",\"status\":\"DOWN\",\"source\":\"SYSTEM_RULE\",\"inspectionId\":\"$inspectionId\"}")
            ps.setTimestamp(3, Timestamp.from(occurredAt))
            ps.executeUpdate()
        }
        return latest == eventId
    }

    private fun recommendDown(
        c: Connection,
        equipment: EquipmentLock,
        inspectionId: UUID,
        rule: StatusRule,
        labels: List<String>,
        occurredAt: Instant,
    ) {
        val dedupe = "down-recommendation:$inspectionId:${rule.code}"
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,'EQUIPMENT_STATUS_DOWN_RECOMMENDED',?::jsonb,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, "{\"equipmentId\":\"${equipment.id}\",\"siteId\":\"${equipment.siteId}\",\"inspectionId\":\"$inspectionId\",\"rule\":\"${rule.code}\",\"labels\":${labels.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }},\"dedupeKey\":\"$dedupe\"}")
            ps.setTimestamp(3, Timestamp.from(occurredAt))
            ps.executeUpdate()
        }
    }

    private fun auditStatusRule(
        c: Connection,
        actor: Principal,
        equipmentId: UUID,
        eventId: UUID,
        inspectionId: UUID,
        ruleCode: String,
    ) {
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,'EQUIPMENT_STATUS_CHANGED','equipment',?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor.id)
            ps.setString(2, equipmentId.toString())
            ps.setString(3, "{\"status\":\"DOWN\",\"eventId\":\"$eventId\",\"source\":\"SYSTEM_RULE\",\"inspectionId\":\"$inspectionId\",\"rule\":\"$ruleCode\"}")
            ps.setBytes(
                4,
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("$eventId:DOWN:$ruleCode".toByteArray()),
            )
            ps.executeUpdate()
        }
    }

    private fun insertDefect(
        c: Connection,
        actor: Principal,
        equipment: EquipmentLock,
        inspectionId: UUID,
        item: ChecklistItem,
        description: String,
        severity: Severity,
        immediateAction: String?,
    ): UUID {
        val id = UUID.randomUUID()
        c.prepareStatement("insert into defect(id,equipment_id,inspection_id,checklist_item_id,category,description,severity,immediate_action,reported_by,reported_at) values (?,?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, equipment.id)
            ps.setObject(3, inspectionId)
            ps.setObject(4, UUID.fromString(item.id))
            ps.setString(5, item.category.name)
            ps.setString(6, description.trim())
            ps.setString(7, severity.name)
            ps.setString(8, immediateAction?.trim())
            ps.setObject(9, actor.id)
            ps.setTimestamp(10, Timestamp.from(now()))
            ps.executeUpdate()
        }
        return id
    }

    private fun workingHoursStatus(
        c: Connection,
        equipmentId: UUID,
        submission: InspectionSubmission,
        outcome: ValidationOutcome,
    ): WorkingHoursStatus {
        val engine = EngineHours.evaluate(submission.engineHoursStart, submission.engineHoursEnd, null)
        if (engine.status == WorkingHoursStatus.MISSING_READING || engine.status == WorkingHoursStatus.SUSPICIOUS) return engine.status
        val overlap =
            c.prepareStatement("select exists(select 1 from working_hours_entry where equipment_id=? and report_date=? and hours_start is not null and hours_end is not null and ? < hours_end and hours_start < ?)").use { ps ->
                ps.setObject(1, equipmentId)
                ps.setObject(2, submission.reportDate)
                ps.setObject(3, submission.engineHoursStart)
                ps.setObject(4, submission.engineHoursEnd)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        return if (overlap) {
            WorkingHoursStatus.OVERLAP
        } else if (outcome.warnings.isEmpty()) {
            WorkingHoursStatus.VALID
        } else {
            WorkingHoursStatus.SUSPICIOUS
        }
    }

    private fun insertWorkingHours(
        c: Connection,
        inspectionId: UUID,
        equipment: EquipmentLock,
        shiftId: UUID,
        submission: InspectionSubmission,
        status: WorkingHoursStatus,
    ) {
        val engine = EngineHours.evaluate(submission.engineHoursStart, submission.engineHoursEnd, null)
        c.prepareStatement("insert into working_hours_entry(inspection_id,equipment_id,site_id,shift_id,report_date,hours_start,hours_end,recorded_hours,status) values (?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setObject(1, inspectionId)
            ps.setObject(2, equipment.id)
            ps.setObject(3, equipment.siteId)
            ps.setObject(4, shiftId)
            ps.setObject(5, submission.reportDate)
            ps.setObject(6, submission.engineHoursStart)
            ps.setObject(7, submission.engineHoursEnd)
            ps.setObject(8, engine.recordedHours)
            ps.setString(9, status.name)
            ps.executeUpdate()
        }
    }

    private fun insertEngineReadings(
        c: Connection,
        inspectionId: UUID,
        equipmentId: UUID,
        submission: InspectionSubmission,
        submittedAt: Instant,
        status: WorkingHoursStatus,
    ) {
        val quality = if (status == WorkingHoursStatus.SUSPICIOUS) "SUSPICIOUS" else "VALID"
        insertEngineReading(c, equipmentId, submission.engineHoursStart, submission.startedAt, "INSPECTION_START", inspectionId, quality)
        insertEngineReading(c, equipmentId, submission.engineHoursEnd, submittedAt, "INSPECTION_END", inspectionId, quality)
    }

    private fun insertEngineReading(
        c: Connection,
        equipmentId: UUID,
        reading: Double?,
        at: Instant,
        source: String,
        sourceId: UUID,
        quality: String,
    ) {
        if (reading == null) return
        c.prepareStatement("insert into engine_hour_reading(id,equipment_id,reading,read_at,source,source_id,quality) values (?,?,?,?,?,?,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, equipmentId)
            ps.setDouble(3, reading)
            ps.setTimestamp(4, Timestamp.from(at))
            ps.setString(5, source)
            ps.setObject(6, sourceId)
            ps.setString(7, quality)
            ps.executeUpdate()
        }
    }

    private fun projectEquipment(
        c: Connection,
        equipment: EquipmentLock,
        inspectionId: UUID,
        submission: InspectionSubmission,
    ) {
        val current = submission.engineHoursEnd ?: submission.engineHoursStart
        c.prepareStatement("update equipment set engine_hours_current=coalesce(?,engine_hours_current),last_inspection_id=?,updated_at=now(),version=version+1 where id=?").use { ps ->
            ps.setObject(1, current)
            ps.setObject(2, inspectionId)
            ps.setObject(3, equipment.id)
            ps.executeUpdate()
        }
    }

    private fun audit(
        c: Connection,
        actor: Principal,
        inspectionId: UUID,
        result: InspectionResult,
        late: Boolean,
    ) {
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,'INSPECTION_SUBMITTED','inspection',?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor.id)
            ps.setString(2, inspectionId.toString())
            ps.setString(3, "{\"result\":\"${result.name}\",\"submittedLate\":$late}")
            ps.setBytes(
                4,
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("$inspectionId:$result".toByteArray()),
            )
            ps.executeUpdate()
        }
    }

    private fun outbox(
        c: Connection,
        inspectionId: UUID,
        equipment: EquipmentLock,
        result: InspectionResult,
        late: Boolean,
    ) {
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?, 'INSPECTION_SUBMITTED', ?::jsonb, now())").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, "{\"inspectionId\":\"$inspectionId\",\"equipmentId\":\"${equipment.id}\",\"siteId\":\"${equipment.siteId}\",\"result\":\"${result.name}\",\"submittedLate\":$late}")
            ps.executeUpdate()
        }
    }

    private fun lockEquipment(
        c: Connection,
        actor: Principal,
        id: UUID,
    ): EquipmentLock =
        c.prepareStatement("select e.id,e.site_id,e.tunnel_id,e.type_id,e.engine_hours_current,e.current_status,u.employee_id from equipment e join app_user u on u.id=? where e.id=? and e.lifecycle_state='ACTIVE' for update of e").use { ps ->
            ps.setObject(1, actor.id)
            ps.setObject(2, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("EQUIPMENT_NOT_FOUND")
                EquipmentLock(
                    it.getObject(1, UUID::class.java),
                    it.getObject(2, UUID::class.java),
                    it.getObject(3, UUID::class.java),
                    it.getObject(4, UUID::class.java),
                    it.getBigDecimal(5)?.toDouble(),
                    it.getString(6),
                    it.getString(7),
                )
            }
        }

    private fun lockExpectation(
        c: Connection,
        id: UUID,
    ): ExpectationLock =
        c.prepareStatement("select id,equipment_id,site_id,shift_id,report_date,window_end,state,inspection_id from inspection_expectation where id=? for update").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("EXPECTATION_NOT_FOUND")
                ExpectationLock(
                    it.getObject(1, UUID::class.java),
                    it.getObject(2, UUID::class.java),
                    it.getObject(3, UUID::class.java),
                    it.getObject(4, UUID::class.java),
                    it.getObject(5, LocalDate::class.java),
                    it.getTimestamp(6).toInstant(),
                    it.getString(7),
                    it.getObject(8, UUID::class.java),
                )
            }
        }

    private fun checklistItems(
        c: Connection,
        typeId: UUID,
    ): List<ChecklistItem> =
        c.prepareStatement("select * from checklist_item where active and (cardinality(applies_to_type_ids)=0 or ?=any(applies_to_type_ids)) order by sort_order,code").use { ps ->
            ps.setObject(1, typeId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ChecklistItem(
                                id = rs.getObject("id", UUID::class.java).toString(),
                                code = rs.getString("code"),
                                label = rs.getString("label"),
                                category = ChecklistCategory.valueOf(rs.getString("category")),
                                mandatory = rs.getBoolean("mandatory"),
                                safetyCritical = rs.getBoolean("safety_critical"),
                                photoRequiredOnFail = rs.getBoolean("photo_required_on_fail"),
                                naReasonRequired = rs.getBoolean("na_reason_required"),
                                active = rs.getBoolean("active"),
                            ),
                        )
                    }
                }
            }
        }

    private fun lastEngineReading(
        c: Connection,
        equipmentId: UUID,
    ): Double? =
        c.prepareStatement("select max(reading) from engine_hour_reading where equipment_id=?").use { ps ->
            ps.setObject(1, equipmentId)
            ps.executeQuery().use {
                it.next()
                it.getBigDecimal(1)?.toDouble()
            }
        }

    private fun resultFor(answers: List<InspectionAnswerInput>): InspectionResult = InspectionValidator.result(answers.map { AnswerWithSeverity(it.answer, it.defect?.severity) })

    private fun existingInspection(
        c: Connection,
        clientUuid: UUID,
    ): InspectionSummaryView? =
        c.prepareStatement("select * from inspection where client_uuid=?").use { ps ->
            ps.setObject(1, clientUuid)
            ps.executeQuery().use { if (it.next()) it.toSummaryView() else null }
        }

    private fun loadSummary(
        c: Connection,
        id: UUID,
    ): InspectionSummaryView =
        c.prepareStatement("select * from inspection where id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { if (it.next()) it.toSummaryView() else throw AuthFailure("INSPECTION_NOT_FOUND") }
        }

    private fun authorizeInspection(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ) {
        if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
        if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
    }

    private fun siteForEquipment(
        c: Connection,
        id: UUID,
    ): UUID =
        c.prepareStatement("select site_id from equipment where id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else throw AuthFailure("EQUIPMENT_NOT_FOUND") }
        }

    private fun validateDefectBreakdown(
        c: Connection,
        defectId: UUID,
        breakdownId: UUID,
    ) {
        val equipmentId =
            c.prepareStatement("select equipment_id from defect where id=?").use { ps ->
                ps.setObject(1, defectId)
                ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else throw AuthFailure("DEFECT_NOT_FOUND") }
            }
        val breakdown =
            c.prepareStatement("select equipment_id,state from breakdown where id=?").use { ps ->
                ps.setObject(1, breakdownId)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("BREAKDOWN_NOT_FOUND")
                    it.getObject(1, UUID::class.java) to it.getString(2)
                }
            }
        if (breakdown.first != equipmentId) throw AuthFailure("BREAKDOWN_EQUIPMENT_MISMATCH")
        if (breakdown.second == "CLOSED") throw AuthFailure("BREAKDOWN_CLOSED")
    }

    private fun hasSiteAccess(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ) = exists(c, "select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))", actor.id, siteId, actor.role in broadRoles, actor.id)

    private fun exists(
        c: Connection,
        sql: String,
        vararg args: Any?,
    ): Boolean =
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, value -> ps.setObject(i + 1, value) }
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun siteAccessSql(column: String = "x.site_id") = "(exists(select 1 from user_site_access a where a.user_id=? and a.site_id=$column) or (? and not exists(select 1 from user_site_access a where a.user_id=?)))"

    private fun bindSiteAccess(
        ps: java.sql.PreparedStatement,
        actor: Principal,
        first: Int,
    ) {
        ps.setObject(first, actor.id)
        ps.setBoolean(first + 1, actor.role in broadRoles)
        ps.setObject(first + 2, actor.id)
    }

    private fun ResultSet.toExpectedView() =
        ExpectedInspectionView(
            getObject("id", UUID::class.java).toString(),
            getObject("equipment_id", UUID::class.java).toString(),
            getString("equipment_code"),
            getString("equipment_name"),
            getObject("equipment_site_id", UUID::class.java).toString(),
            getString("site_name"),
            getObject("shift_id", UUID::class.java).toString(),
            getString("shift_code"),
            getObject("report_date", LocalDate::class.java).toString(),
            getTimestamp("window_start").toInstant().toString(),
            getTimestamp("window_end").toInstant().toString(),
            getString("state"),
            getTimestamp("missing_marked_at")?.toInstant()?.toString(),
            getObject("inspection_id", UUID::class.java)?.toString(),
        )

    private fun ResultSet.toSummaryView() =
        InspectionSummaryView(
            getObject("id", UUID::class.java).toString(),
            getObject("client_uuid", UUID::class.java).toString(),
            getObject("expectation_id", UUID::class.java)?.toString(),
            getObject("equipment_id", UUID::class.java).toString(),
            getObject("site_id", UUID::class.java).toString(),
            getObject("tunnel_id", UUID::class.java)?.toString(),
            getObject("shift_id", UUID::class.java).toString(),
            getObject("report_date", LocalDate::class.java).toString(),
            getObject("operator_id", UUID::class.java)?.toString(),
            getString("operator_eid"),
            getObject("inspector_id", UUID::class.java).toString(),
            getString("inspector_eid"),
            getObject("supervisor_id", UUID::class.java)?.toString(),
            getBigDecimal("engine_hours_start")?.toDouble(),
            getBigDecimal("engine_hours_end")?.toDouble(),
            getBigDecimal("fuel_start_percent")?.toDouble(),
            getBigDecimal("fuel_added_litres")?.toDouble(),
            getString("result"),
            getBoolean("submitted_late"),
            (getArray("hours_warnings")?.array as? Array<*>)?.map { it.toString() } ?: emptyList(),
            getTimestamp("captured_at").toInstant().toString(),
            getTimestamp("started_at").toInstant().toString(),
            getTimestamp("submitted_at").toInstant().toString(),
        )

    private fun ResultSet.toAnswerView(photoIds: List<String>) =
        AnswerView(
            getObject("checklist_item_id", UUID::class.java).toString(),
            getString("category"),
            getString("label_snapshot"),
            getString("answer"),
            getString("na_reason"),
            getObject("defect_id", UUID::class.java)?.toString(),
            photoIds,
        )

    private fun ResultSet.toDefectView() =
        DefectView(
            getObject("id", UUID::class.java).toString(),
            getObject("equipment_id", UUID::class.java).toString(),
            getObject("inspection_id", UUID::class.java)?.toString(),
            getObject("checklist_item_id", UUID::class.java)?.toString(),
            getString("category"),
            getString("description"),
            getString("severity"),
            getString("immediate_action"),
            getString("state"),
            getObject("breakdown_id", UUID::class.java)?.toString(),
            getObject("reported_by", UUID::class.java).toString(),
            getTimestamp("reported_at").toInstant().toString(),
            getTimestamp("resolved_at")?.toInstant()?.toString(),
            getObject("resolved_by", UUID::class.java)?.toString(),
            getLong("version"),
        )

    private fun ResultSet.toDraftView() =
        DraftView(
            getObject("id", UUID::class.java).toString(),
            getObject("expectation_id", UUID::class.java)?.toString(),
            getObject("equipment_id", UUID::class.java).toString(),
            getObject("user_id", UUID::class.java).toString(),
            getString("payload"),
            getTimestamp("updated_at").toInstant().toString(),
        )

    private fun ResultSet.toChecklistItemView() =
        ChecklistItemView(
            getObject("id", UUID::class.java).toString(),
            getString("category"),
            getString("code"),
            getString("label"),
            getInt("sort_order"),
            getBoolean("mandatory"),
            getBoolean("safety_critical"),
            getBoolean("photo_required_on_fail"),
            getBoolean("na_reason_required"),
            getBoolean("active"),
        )

    private fun ResultSet.toCorrectionView() =
        CorrectionView(
            getObject("id", UUID::class.java).toString(),
            getObject("original_inspection_id", UUID::class.java).toString(),
            getString("changes"),
            getString("reason"),
            getObject("requested_by", UUID::class.java).toString(),
            getTimestamp("requested_at").toInstant().toString(),
            getString("decision"),
            getObject("decided_by", UUID::class.java)?.toString(),
            getTimestamp("decided_at")?.toInstant()?.toString(),
            getString("decision_comment"),
        )

    private data class EquipmentLock(
        val id: UUID,
        val siteId: UUID,
        val tunnelId: UUID?,
        val typeId: UUID,
        val engineHours: Double?,
        val currentStatus: String?,
        val employeeId: String,
    )

    private data class StatusRule(
        val code: String,
        val triggerResult: String,
        val triggerSeverity: String,
        val safetyCriticalOnly: Boolean,
        val action: String,
    )

    private data class ExpectationLock(
        val id: UUID,
        val equipmentId: UUID,
        val siteId: UUID,
        val shiftId: UUID,
        val reportDate: LocalDate,
        val windowEnd: Instant,
        val state: String,
        val inspectionId: UUID?,
    )

    companion object {
        private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
        private val defectRoles = setOf(Role.MECHANIC, Role.SUPERVISOR, Role.MANAGER, Role.ADMINISTRATOR)
    }
}
