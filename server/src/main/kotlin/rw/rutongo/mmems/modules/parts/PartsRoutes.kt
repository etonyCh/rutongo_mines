@file:Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.parts

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.shared.api.PartCreateDto
import rw.rutongo.mmems.shared.api.PartRequestCreateDto
import rw.rutongo.mmems.shared.api.PartRequestDecisionDto
import rw.rutongo.mmems.shared.api.PartRequestQuantityActionDto
import rw.rutongo.mmems.shared.api.PartRequestViewDto
import rw.rutongo.mmems.shared.api.PartUpdateDto
import rw.rutongo.mmems.shared.api.StockMovementCreateDto
import rw.rutongo.mmems.shared.enums.PartRequestState
import java.util.UUID

private const val DEFAULT_PARTS_LIMIT = 100
private const val PRECONDITION_REQUIRED = 428

fun Route.partsRoutes(
    service: PartsService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        get("/parts") {
            val actor = call.authenticated(identity, signer) ?: return@get
            respondFailure(call) {
                call.respond(
                    service.listParts(
                        actor,
                        call.request.queryParameters["q"],
                        call.request.queryParameters["lowStock"]?.toBooleanStrictOrNull() ?: false,
                        call.request.queryParameters["includeInactive"]?.toBooleanStrictOrNull() ?: false,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PARTS_LIMIT,
                    ),
                )
            }
        }
        get("/parts/low-stock") {
            val actor = call.authenticated(identity, signer) ?: return@get
            respondFailure(call) {
                call.respond(
                    service.listParts(
                        actor,
                        call.request.queryParameters["q"],
                        lowStock = true,
                        includeInactive = false,
                        limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PARTS_LIMIT,
                    ),
                )
            }
        }
        get("/parts/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val id = parseUuid(call.parameters["id"])
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_ID", "The part ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.getPart(actor, id)) }
        }
        post("/parts") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<PartCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART", "The part is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createPart(actor, request)) }
        }
        put("/parts/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val id = parseUuid(call.parameters["id"])
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_ID", "The part ID is invalid."))
                return@put
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match part version is required."))
                return@put
            }
            val request =
                runCatching { call.receive<PartUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART", "The part is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updatePart(actor, id, version, request)) }
        }
        get("/stock-movements") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val partId = call.request.queryParameters["partId"]?.let(::parseUuid)
            val requestId = call.request.queryParameters["requestId"]?.let(::parseUuid)
            if (call.request.queryParameters["partId"] != null && partId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_ID", "The part ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["requestId"] != null && requestId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST_ID", "The part request ID is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.listStockMovements(
                        actor,
                        partId,
                        requestId,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PARTS_LIMIT,
                    ),
                )
            }
        }
        post("/stock-movements") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<StockMovementCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_STOCK_MOVEMENT", "The stock movement is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.recordStockMovement(actor, request)) }
        }
        get("/part-requests") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let(::parseUuid)
            val state = call.request.queryParameters["state"]?.let { runCatching { PartRequestState.valueOf(it.uppercase()) }.getOrNull() }
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["state"] != null && state == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST_STATE", "The part request state is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.listRequests(
                        actor,
                        siteId,
                        state,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PARTS_LIMIT,
                    ),
                )
            }
        }
        post("/part-requests") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val key = call.request.headers["Idempotency-Key"]
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required."))
                return@post
            }
            val request =
                runCatching { call.receive<PartRequestCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST", "The part request is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createRequest(actor, key, request)) }
        }
        post("/part-requests/{id}/approve") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST_ID", "The part request ID is invalid."))
                return@post
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match request version is required."))
                return@post
            }
            val request = call.receiveNullable<PartRequestDecisionDto>() ?: PartRequestDecisionDto()
            respondFailure(call) { call.respond(service.approve(actor, id, version, request)) }
        }
        post("/part-requests/{id}/reject") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST_ID", "The part request ID is invalid."))
                return@post
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match request version is required."))
                return@post
            }
            val request = call.receiveNullable<PartRequestDecisionDto>() ?: PartRequestDecisionDto()
            respondFailure(call) { call.respond(service.reject(actor, id, version, request)) }
        }
        post("/part-requests/{id}/issue") {
            val actor = call.authenticated(identity, signer) ?: return@post
            respondQuantityAction(call) { id, version, request ->
                service.issue(actor, id, version, request)
            }
        }
        post("/part-requests/{id}/use") {
            val actor = call.authenticated(identity, signer) ?: return@post
            respondQuantityAction(call) { id, version, request ->
                service.use(actor, id, version, request)
            }
        }
        post("/part-requests/{id}/return") {
            val actor = call.authenticated(identity, signer) ?: return@post
            respondQuantityAction(call) { id, version, request ->
                service.returnParts(actor, id, version, request)
            }
        }
    }
}

private suspend fun respondQuantityAction(
    call: ApplicationCall,
    action: (UUID, Long, PartRequestQuantityActionDto) -> PartRequestViewDto,
) {
    val id = call.parameters["id"]?.let(::parseUuid)
    if (id == null) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST_ID", "The part request ID is invalid."))
        return
    }
    val version = headerVersion(call)
    if (version == null) {
        call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match request version is required."))
        return
    }
    val request = call.receiveNullable<PartRequestQuantityActionDto>() ?: PartRequestQuantityActionDto()
    respondFailure(call) { call.respond(action(id, version, request)) }
}

private fun parseUuid(value: String?): UUID? = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun headerVersion(call: ApplicationCall): Long? =
    call.request.headers["If-Match"]
        ?.trim('"')
        ?.toLongOrNull()

private suspend fun respondFailure(
    call: ApplicationCall,
    action: suspend () -> Unit,
) {
    try {
        action()
    } catch (e: AuthFailure) {
        call.respond(statusFor(e.code), ApiError(e.code, "The part request failed."))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PART_REQUEST", e.message ?: "The part request is invalid."))
    }
}

private fun statusFor(code: String): HttpStatusCode =
    when (code) {
        "FORBIDDEN", "SITE_NOT_AUTHORIZED", "IDEMPOTENCY_KEY_REPLAY_FORBIDDEN" -> HttpStatusCode.Forbidden
        "PART_NOT_FOUND", "PART_REQUEST_NOT_FOUND", "STOCK_MOVEMENT_NOT_FOUND", "EQUIPMENT_NOT_FOUND", "BREAKDOWN_NOT_FOUND", "MAINTENANCE_NOT_FOUND" -> HttpStatusCode.NotFound
        "PART_NUMBER_EXISTS", "PART_VERSION_CONFLICT", "PART_REQUEST_VERSION_CONFLICT", "INVALID_PART_REQUEST_TRANSITION", "INSUFFICIENT_STOCK" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }
