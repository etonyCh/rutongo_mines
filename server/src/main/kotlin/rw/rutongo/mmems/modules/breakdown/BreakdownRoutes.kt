@file:Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.breakdown

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.shared.enums.BreakdownState
import java.time.Instant
import java.util.UUID

private const val DEFAULT_LIMIT = 100
private const val PRECONDITION_REQUIRED = 428

@Serializable private data class CreateBreakdownRequest(
    val equipmentId: String,
    val category: String,
    val problem: String,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val reportedAt: String? = null,
    val breakdownStart: String? = null,
    val assignedTechnicianId: String? = null,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val remarks: String? = null,
    val defectIds: List<String> = emptyList(),
)

@Serializable private data class TransitionBreakdownRequest(
    val state: BreakdownState,
    val repairStart: String? = null,
    val repairEnd: String? = null,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val assignedTechnicianId: String? = null,
    val remarks: String? = null,
)

fun Route.breakdownRoutes(
    service: BreakdownService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        post("/breakdowns") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val key = call.request.headers["Idempotency-Key"]
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required."))
                return@post
            }
            val request =
                runCatching { call.receive<CreateBreakdownRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN", "The breakdown is invalid."))
                    return@post
                }
            val input =
                runCatching {
                    BreakdownCreate(
                        idempotencyKey = key,
                        equipmentId = UUID.fromString(request.equipmentId),
                        category = request.category,
                        problem = request.problem,
                        tunnelId = request.tunnelId?.let(UUID::fromString),
                        shiftId = request.shiftId?.let(UUID::fromString),
                        reportedAt = request.reportedAt?.let(Instant::parse),
                        breakdownStart = request.breakdownStart?.let(Instant::parse),
                        assignedTechnicianId = request.assignedTechnicianId?.let(UUID::fromString),
                        actionTaken = request.actionTaken,
                        partsRequired = request.partsRequired,
                        remarks = request.remarks,
                        defectIds = request.defectIds.map(UUID::fromString),
                    )
                }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN", "The breakdown is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.create(actor, input)) }
        }
        get("/breakdowns") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val equipmentId = call.request.queryParameters["equipmentId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val state = call.request.queryParameters["state"]?.let { runCatching { BreakdownState.valueOf(it.uppercase()) }.getOrNull() }
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["equipmentId"] != null && equipmentId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["state"] != null && state == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN_STATE", "The breakdown state is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(service.list(actor, siteId, equipmentId, state, call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT))
            }
        }
        post("/breakdowns/{id}/transition") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN_ID", "The breakdown ID is invalid."))
                return@post
            }
            val version =
                call.request.headers["If-Match"]
                    ?.trim('"')
                    ?.toLongOrNull()
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match breakdown version is required."))
                return@post
            }
            val request =
                runCatching { call.receive<TransitionBreakdownRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN_TRANSITION", "The breakdown transition is invalid."))
                    return@post
                }
            val input =
                runCatching {
                    BreakdownTransition(
                        state = request.state,
                        repairStart = request.repairStart?.let(Instant::parse),
                        repairEnd = request.repairEnd?.let(Instant::parse),
                        actionTaken = request.actionTaken,
                        partsRequired = request.partsRequired,
                        assignedTechnicianId = request.assignedTechnicianId?.let(UUID::fromString),
                        remarks = request.remarks,
                    )
                }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN_TRANSITION", "The breakdown transition is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(service.transition(actor, id, version, input)) }
        }
    }
}

private suspend fun respondFailure(
    call: ApplicationCall,
    action: suspend () -> Unit,
) {
    try {
        action()
    } catch (e: AuthFailure) {
        call.respond(statusFor(e.code), ApiError(e.code, "The breakdown request failed."))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_BREAKDOWN_REQUEST", e.message ?: "The breakdown request is invalid."))
    }
}

private fun statusFor(code: String): HttpStatusCode =
    when (code) {
        "FORBIDDEN", "SITE_NOT_AUTHORIZED", "IDEMPOTENCY_KEY_REPLAY_FORBIDDEN" -> HttpStatusCode.Forbidden
        "EQUIPMENT_NOT_FOUND", "BREAKDOWN_NOT_FOUND", "SHIFT_NOT_FOUND", "TUNNEL_NOT_IN_SITE", "TECHNICIAN_NOT_FOUND", "DEFECT_NOT_FOUND" -> HttpStatusCode.NotFound
        "BREAKDOWN_ALREADY_ACTIVE", "BREAKDOWN_VERSION_CONFLICT", "INVALID_BREAKDOWN_TRANSITION", "DEFECT_ALREADY_LINKED", "DEFECT_EQUIPMENT_MISMATCH", "DEFECT_CLOSED" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }
