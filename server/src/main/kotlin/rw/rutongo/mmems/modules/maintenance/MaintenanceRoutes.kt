@file:Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.maintenance

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
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
import rw.rutongo.mmems.shared.api.MaintenanceActionDto
import rw.rutongo.mmems.shared.api.MaintenanceCreateDto
import rw.rutongo.mmems.shared.api.ServiceIntervalCreateDto
import rw.rutongo.mmems.shared.api.ServiceIntervalUpdateDto
import rw.rutongo.mmems.shared.api.ServiceTypeCreateDto
import rw.rutongo.mmems.shared.api.ServiceTypeUpdateDto
import rw.rutongo.mmems.shared.enums.MaintenanceAction
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import java.time.Instant
import java.util.UUID

private const val DEFAULT_MAINTENANCE_LIMIT = 100
private const val PRECONDITION_REQUIRED = 428

fun Route.maintenanceRoutes(
    service: MaintenanceService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        post("/maintenance") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val key = call.request.headers["Idempotency-Key"]
            if (key.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required."))
                return@post
            }
            val request =
                runCatching { call.receive<MaintenanceCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MAINTENANCE", "The maintenance record is invalid."))
                    return@post
                }
            val input =
                runCatching {
                    MaintenanceCreate(
                        idempotencyKey = key,
                        equipmentId = UUID.fromString(request.equipmentId),
                        serviceTypeId = UUID.fromString(request.serviceTypeId),
                        tunnelId = request.tunnelId?.let(UUID::fromString),
                        performedAt = request.performedAt?.let(Instant::parse),
                        engineHours = request.engineHours,
                        workPerformed = request.workPerformed,
                        cause = request.cause,
                        technicianId = UUID.fromString(request.technicianId),
                        supervisorId = request.supervisorId?.let(UUID::fromString),
                        breakdownId = request.breakdownId?.let(UUID::fromString),
                        replacedComponents = request.replacedComponents,
                        downtimeMinutes = request.downtimeMinutes,
                        labourCost = request.labourCost,
                        partsCost = request.partsCost,
                        currency = request.currency,
                        notes = request.notes,
                    )
                }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MAINTENANCE", "The maintenance record is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.create(actor, input)) }
        }
        get("/maintenance") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let { parseUuid(it) }
            val equipmentId = call.request.queryParameters["equipmentId"]?.let { parseUuid(it) }
            val status =
                call.request.queryParameters["status"]?.let {
                    runCatching { MaintenanceVerificationStatus.valueOf(it.uppercase()) }.getOrNull()
                }
            val from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val to = call.request.queryParameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["equipmentId"] != null && equipmentId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["status"] != null && status == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MAINTENANCE_STATUS", "The maintenance status is invalid."))
                return@get
            }
            if (call.request.queryParameters["from"] != null && from == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_FROM", "The from timestamp is invalid."))
                return@get
            }
            if (call.request.queryParameters["to"] != null && to == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TO", "The to timestamp is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.list(
                        actor,
                        siteId,
                        equipmentId,
                        status,
                        from,
                        to,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_MAINTENANCE_LIMIT,
                    ),
                )
            }
        }
        maintenanceActionRoute("maintenance/{id}/verify", service, identity, signer, MaintenanceAction.VERIFY)
        maintenanceActionRoute("maintenance/{id}/reject", service, identity, signer, MaintenanceAction.REJECT)
        maintenanceActionRoute("maintenance/{id}/request-correction", service, identity, signer, MaintenanceAction.REQUEST_CORRECTION)
        get("/service-schedule") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let { parseUuid(it) }
            val equipmentId = call.request.queryParameters["equipmentId"]?.let { parseUuid(it) }
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["equipmentId"] != null && equipmentId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.serviceSchedule(actor, siteId, equipmentId)) }
        }
        get("/equipment/{id}/service-status") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val id = call.parameters["id"]?.let { parseUuid(it) }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.serviceStatus(actor, id)) }
        }
        get("/service-types") {
            val actor = call.authenticated(identity, signer) ?: return@get
            respondFailure(call) { call.respond(service.serviceTypes(actor)) }
        }
        post("/service-types") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<ServiceTypeCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SERVICE_TYPE", "The service type is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createServiceType(actor, request)) }
        }
        put("/service-types/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val id = call.parameters["id"]?.let { parseUuid(it) }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SERVICE_TYPE_ID", "The service type ID is invalid."))
                return@put
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@put
            }
            val request =
                runCatching { call.receive<ServiceTypeUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SERVICE_TYPE", "The service type is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updateServiceType(actor, id, version, request)) }
        }
        get("/service-intervals") {
            val actor = call.authenticated(identity, signer) ?: return@get
            respondFailure(call) { call.respond(service.serviceIntervals(actor)) }
        }
        post("/service-intervals") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<ServiceIntervalCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SERVICE_INTERVAL", "The service interval is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createServiceInterval(actor, request)) }
        }
        put("/service-intervals/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val id = call.parameters["id"]?.let { parseUuid(it) }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SERVICE_INTERVAL_ID", "The service interval ID is invalid."))
                return@put
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@put
            }
            val request =
                runCatching { call.receive<ServiceIntervalUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SERVICE_INTERVAL", "The service interval is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updateServiceInterval(actor, id, version, request)) }
        }
        get("/maintenance-technicians") {
            val actor = call.authenticated(identity, signer) ?: return@get
            respondFailure(call) { call.respond(service.maintenanceTechnicians(actor)) }
        }
    }
}

private fun Route.maintenanceActionRoute(
    path: String,
    service: MaintenanceService,
    identity: IdentityService,
    signer: TokenSigner,
    action: MaintenanceAction,
) {
    post(path) {
        val actor = call.authenticated(identity, signer) ?: return@post
        val id = call.parameters["id"]?.let { parseUuid(it) }
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MAINTENANCE_ID", "The maintenance ID is invalid."))
            return@post
        }
        val version =
            call.request.headers["If-Match"]
                ?.trim('"')
                ?.toLongOrNull()
        if (version == null) {
            call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match maintenance version is required."))
            return@post
        }
        val request =
            runCatching { call.receive<MaintenanceActionDto>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MAINTENANCE_ACTION", "The maintenance action is invalid."))
                return@post
            }
        respondFailure(call) {
            val view =
                when (action) {
                    MaintenanceAction.VERIFY -> service.verify(actor, id, version, MaintenanceActionInput(request.comment))
                    MaintenanceAction.REJECT -> service.reject(actor, id, version, MaintenanceActionInput(request.comment))
                    MaintenanceAction.REQUEST_CORRECTION -> service.requestCorrection(actor, id, version, MaintenanceActionInput(request.comment))
                }
            call.respond(view)
        }
    }
}

private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

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
        call.respond(statusFor(e.code), ApiError(e.code, "The maintenance request failed."))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MAINTENANCE_REQUEST", e.message ?: "The maintenance request is invalid."))
    }
}

private fun statusFor(code: String): HttpStatusCode =
    when (code) {
        "FORBIDDEN", "SITE_NOT_AUTHORIZED", "IDEMPOTENCY_KEY_REPLAY_FORBIDDEN" -> HttpStatusCode.Forbidden
        "EQUIPMENT_NOT_FOUND", "MAINTENANCE_NOT_FOUND", "SERVICE_TYPE_NOT_FOUND", "SERVICE_INTERVAL_NOT_FOUND", "EQUIPMENT_TYPE_NOT_FOUND", "TUNNEL_NOT_IN_SITE", "TECHNICIAN_NOT_FOUND", "SUPERVISOR_NOT_FOUND", "BREAKDOWN_NOT_FOUND" -> HttpStatusCode.NotFound
        "MAINTENANCE_VERSION_CONFLICT", "SERVICE_TYPE_VERSION_CONFLICT", "SERVICE_INTERVAL_VERSION_CONFLICT", "SERVICE_TYPE_ALREADY_EXISTS", "SERVICE_INTERVAL_ALREADY_EXISTS", "INVALID_MAINTENANCE_TRANSITION", "BREAKDOWN_EQUIPMENT_MISMATCH" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }
