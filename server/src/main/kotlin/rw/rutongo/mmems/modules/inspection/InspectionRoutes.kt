@file:Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "ComplexCondition")

package rw.rutongo.mmems.modules.inspection

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.modules.inspection.domain.AttestationInput
import rw.rutongo.mmems.modules.inspection.domain.DefectInput
import rw.rutongo.mmems.modules.inspection.domain.InspectionAnswerInput
import rw.rutongo.mmems.modules.inspection.domain.InspectionSubmission
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.Severity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Serializable private data class DefectRequest(
    val description: String? = null,
    val severity: Severity,
    val immediateAction: String? = null,
)

@Serializable private data class AnswerRequest(
    val itemId: String,
    val answer: Answer,
    val naReason: String? = null,
    val defect: DefectRequest? = null,
    val photoIds: List<String> = emptyList(),
)

@Serializable private data class AttestationRequest(
    val name: String,
    val employeeId: String,
    val method: String,
    val fingerprint: String? = null,
    val signature: String? = null,
)

@Serializable private data class SubmitInspectionRequest(
    val clientUuid: String,
    val expectationId: String? = null,
    val equipmentId: String,
    val shiftId: String,
    val reportDate: String,
    val operatorId: String? = null,
    val operatorEid: String? = null,
    val supervisorId: String? = null,
    val engineHoursStart: Double? = null,
    val engineHoursEnd: Double? = null,
    val fuelStartPercent: Double? = null,
    val fuelAddedLitres: Double? = null,
    val capturedAt: String,
    val startedAt: String,
    val attestation: AttestationRequest? = null,
    val answers: List<AnswerRequest> = emptyList(),
)

@Serializable private data class DraftRequest(
    val expectationId: String? = null,
    val payload: JsonElement,
)

@Serializable private data class ChecklistItemRequest(
    val category: String,
    val code: String,
    val label: String,
    val sortOrder: Int,
    val mandatory: Boolean = true,
    val safetyCritical: Boolean = false,
    val photoRequiredOnFail: Boolean = false,
    val naReasonRequired: Boolean = true,
    val active: Boolean = true,
)

@Serializable private data class CorrectionRequest(
    val changes: JsonElement,
    val reason: String,
)

@Serializable private data class CorrectionDecisionRequest(
    val decision: String,
    val comment: String? = null,
)

@Serializable private data class DefectPatchRequest(
    val state: String? = null,
    val severity: String? = null,
    val immediateAction: String? = null,
    val breakdownId: String? = null,
    val version: Long,
)

fun Route.inspectionRoutes(
    service: InspectionService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        get("/inspections/expected") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val site = call.request.queryParameters["siteId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (call.request.queryParameters["siteId"] != null && site == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.expected(actor, site)) }
        }
        get("/inspections/control") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val date = call.request.queryParameters["reportDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val site = call.request.queryParameters["siteId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if ((call.request.queryParameters["reportDate"] != null && date == null) || (call.request.queryParameters["siteId"] != null && site == null)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_FILTER", "The report date or site ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.control(actor, date, site)) }
        }
        get("/inspections/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_INSPECTION_ID", "The inspection ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.detail(actor, id)) }
        }
        post("/inspections") {
            val actor = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(actor.role, "INSPECTION_SUBMIT")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to submit inspections."))
                return@post
            }
            val body =
                runCatching { call.receive<SubmitInspectionRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_INSPECTION", "The inspection payload is invalid."))
                    return@post
                }
            val submission =
                runCatching { body.toDomain() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_INSPECTION", it.message ?: "The inspection payload is invalid."))
                    return@post
                }
            try {
                call.respond(HttpStatusCode.Created, service.submit(actor, submission))
            } catch (e: InspectionValidationException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INSPECTION_INVALID", e.message ?: "The inspection is invalid."))
            } catch (e: AuthFailure) {
                call.respond(statusFor(e.code), ApiError(e.code, "The inspection could not be submitted."))
            }
        }
        put("/inspection-drafts/{equipmentId}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val equipmentId = call.parameters["equipmentId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (equipmentId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@put
            }
            val body =
                runCatching { call.receive<DraftRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_DRAFT", "The draft payload is invalid."))
                    return@put
                }
            val expectationId = body.expectationId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (body.expectationId != null && expectationId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EXPECTATION_ID", "The expectation ID is invalid."))
                return@put
            }
            respondFailure(call) { call.respond(service.saveDraft(actor, equipmentId, expectationId, body.payload.toString())) }
        }
        get("/checklist-items") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val activeOnly = call.request.queryParameters["active"]?.toBooleanStrictOrNull() ?: false
            respondFailure(call) { call.respond(service.checklistItems(actor, activeOnly)) }
        }
        post("/checklist-items") {
            val actor = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(actor.role, "CHECKLIST_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to manage checklist items."))
                return@post
            }
            val request =
                runCatching { call.receive<ChecklistItemRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_CHECKLIST_ITEM", "The checklist item is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createChecklistItem(actor, request.toView(""))) }
        }
        put("/checklist-items/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            if (!RolePermissions.allows(actor.role, "CHECKLIST_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to manage checklist items."))
                return@put
            }
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_CHECKLIST_ITEM_ID", "The checklist item ID is invalid."))
                return@put
            }
            val request =
                runCatching { call.receive<ChecklistItemRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_CHECKLIST_ITEM", "The checklist item is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updateChecklistItem(actor, id, request.toView(id.toString()))) }
        }
        post("/inspections/{id}/corrections") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_INSPECTION_ID", "The inspection ID is invalid."))
                return@post
            }
            val request =
                runCatching { call.receive<CorrectionRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_CORRECTION", "The correction request is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.requestCorrection(actor, id, request.changes.toString(), request.reason)) }
        }
        post("/corrections/{id}/decision") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_CORRECTION_ID", "The correction ID is invalid."))
                return@post
            }
            val request =
                runCatching { call.receive<CorrectionDecisionRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_DECISION", "The correction decision is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(service.decideCorrection(actor, id, request.decision, request.comment)) }
        }
        get("/defects") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val equipment = call.request.queryParameters["equipmentId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (call.request.queryParameters["equipmentId"] != null && equipment == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.defects(actor, equipment, call.request.queryParameters["state"])) }
        }
        patch("/defects/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@patch
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_DEFECT_ID", "The defect ID is invalid."))
                return@patch
            }
            val request =
                runCatching { call.receive<DefectPatchRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_DEFECT", "The defect patch is invalid."))
                    return@patch
                }
            val breakdownId = request.breakdownId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (request.breakdownId != null && breakdownId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN_ID", "The breakdown ID is invalid."))
                return@patch
            }
            respondFailure(call) { call.respond(service.updateDefect(actor, id, request.state, request.severity, request.immediateAction, breakdownId, request.version)) }
        }
    }
}

private suspend fun respondFailure(
    call: io.ktor.server.application.ApplicationCall,
    action: suspend () -> Unit,
) {
    try {
        action()
    } catch (e: AuthFailure) {
        call.respond(statusFor(e.code), ApiError(e.code, "The inspection request failed."))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_INSPECTION_REQUEST", e.message ?: "The inspection request is invalid."))
    }
}

private fun statusFor(code: String): HttpStatusCode =
    when (code) {
        "FORBIDDEN", "SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
        "EQUIPMENT_NOT_FOUND", "EXPECTATION_NOT_FOUND", "INSPECTION_NOT_FOUND", "CHECKLIST_ITEM_NOT_FOUND", "CORRECTION_NOT_FOUND", "DEFECT_NOT_FOUND", "BREAKDOWN_NOT_FOUND", "SHIFT_NOT_FOUND" -> HttpStatusCode.NotFound
        "INSPECTION_EXPECTATION_MISMATCH", "INSPECTION_EXPECTATION_ALREADY_COMPLETED", "DEFECT_VERSION_CONFLICT", "CORRECTION_ALREADY_DECIDED", "BREAKDOWN_EQUIPMENT_MISMATCH", "BREAKDOWN_CLOSED" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }

private fun SubmitInspectionRequest.toDomain(): InspectionSubmission {
    val reportDate = LocalDate.parse(reportDate)
    val captured = Instant.parse(capturedAt)
    val started = Instant.parse(startedAt)
    val parsedClient = UUID.fromString(clientUuid)
    UUID.fromString(equipmentId)
    UUID.fromString(shiftId)
    expectationId?.let(UUID::fromString)
    return InspectionSubmission(
        clientUuid = parsedClient.toString(),
        expectationId = expectationId,
        equipmentId = equipmentId,
        shiftId = shiftId,
        reportDate = reportDate,
        operatorId = operatorId,
        operatorEid = operatorEid,
        supervisorId = supervisorId,
        engineHoursStart = engineHoursStart,
        engineHoursEnd = engineHoursEnd,
        fuelStartPercent = fuelStartPercent,
        fuelAddedLitres = fuelAddedLitres,
        capturedAt = captured,
        startedAt = started,
        attestation = attestation?.let { AttestationInput(it.name, it.employeeId, it.method, it.fingerprint, it.signature) },
        answers =
            answers.map {
                InspectionAnswerInput(
                    itemId = it.itemId,
                    answer = it.answer,
                    naReason = it.naReason,
                    defect = it.defect?.let { d -> DefectInput(d.description, d.severity, d.immediateAction) },
                    photoIds = it.photoIds,
                )
            },
    )
}

private fun ChecklistItemRequest.toView(id: String) =
    ChecklistItemView(
        id = id,
        category = category,
        code = code,
        label = label,
        sortOrder = sortOrder,
        mandatory = mandatory,
        safetyCritical = safetyCritical,
        photoRequiredOnFail = photoRequiredOnFail,
        naReasonRequired = naReasonRequired,
        active = active,
    )
