@file:Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.personnel

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
import rw.rutongo.mmems.shared.api.AssignmentCreateDto
import rw.rutongo.mmems.shared.api.OperatorCreateDto
import rw.rutongo.mmems.shared.api.OperatorUpdateDto
import rw.rutongo.mmems.shared.api.PersonnelTransferCreateDto
import rw.rutongo.mmems.shared.api.TechnicianCreateDto
import rw.rutongo.mmems.shared.api.TechnicianUpdateDto
import rw.rutongo.mmems.shared.enums.PersonnelType
import java.util.UUID

private const val DEFAULT_PERSONNEL_LIMIT = 100
private const val PRECONDITION_REQUIRED = 428

fun Route.personnelRoutes(
    service: PersonnelService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        get("/technicians") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let(::parseUuid)
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.listTechnicians(
                        actor,
                        siteId,
                        call.request.queryParameters["activeOnly"]?.toBooleanStrictOrNull() ?: false,
                        call.request.queryParameters["q"],
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PERSONNEL_LIMIT,
                    ),
                )
            }
        }
        post("/technicians") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<TechnicianCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TECHNICIAN", "The technician is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createTechnician(actor, request)) }
        }
        put("/technicians/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val id = parseUuid(call.parameters["id"])
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TECHNICIAN_ID", "The technician ID is invalid."))
                return@put
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match technician version is required."))
                return@put
            }
            val request =
                runCatching { call.receive<TechnicianUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TECHNICIAN", "The technician is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updateTechnician(actor, id, version, request)) }
        }
        get("/operators") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let(::parseUuid)
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.listOperators(
                        actor,
                        siteId,
                        call.request.queryParameters["activeOnly"]?.toBooleanStrictOrNull() ?: false,
                        call.request.queryParameters["q"],
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PERSONNEL_LIMIT,
                    ),
                )
            }
        }
        post("/operators") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<OperatorCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_OPERATOR", "The operator is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createOperator(actor, request)) }
        }
        put("/operators/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val id = parseUuid(call.parameters["id"])
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_OPERATOR_ID", "The operator ID is invalid."))
                return@put
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match operator version is required."))
                return@put
            }
            val request =
                runCatching { call.receive<OperatorUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_OPERATOR", "The operator is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updateOperator(actor, id, version, request)) }
        }
        get("/assignments") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val personType = call.request.queryParameters["personType"]?.let(::parsePersonnelType)
            val personId = call.request.queryParameters["personId"]?.let(::parseUuid)
            val equipmentId = call.request.queryParameters["equipmentId"]?.let(::parseUuid)
            if (call.request.queryParameters["personType"] != null && personType == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PERSONNEL_TYPE", "The personnel type is invalid."))
                return@get
            }
            if (call.request.queryParameters["personId"] != null && personId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PERSON_ID", "The personnel ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["equipmentId"] != null && equipmentId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.listAssignments(
                        actor,
                        personType,
                        personId,
                        equipmentId,
                        call.request.queryParameters["openOnly"]?.toBooleanStrictOrNull() ?: false,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PERSONNEL_LIMIT,
                    ),
                )
            }
        }
        post("/assignments") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<AssignmentCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_ASSIGNMENT", "The assignment is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createAssignment(actor, request)) }
        }
        get("/transfers") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val personType = call.request.queryParameters["personType"]?.let(::parsePersonnelType)
            val personId = call.request.queryParameters["personId"]?.let(::parseUuid)
            if (call.request.queryParameters["personType"] != null && personType == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PERSONNEL_TYPE", "The personnel type is invalid."))
                return@get
            }
            if (call.request.queryParameters["personId"] != null && personId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PERSON_ID", "The personnel ID is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.listTransfers(
                        actor,
                        personType,
                        personId,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PERSONNEL_LIMIT,
                    ),
                )
            }
        }
        post("/transfers") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<PersonnelTransferCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TRANSFER", "The transfer is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.createTransfer(actor, request)) }
        }
    }
}

private fun parseUuid(value: String?): UUID? = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun parsePersonnelType(value: String): PersonnelType? = runCatching { PersonnelType.valueOf(value.uppercase()) }.getOrNull()

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
        call.respond(statusFor(e.code), ApiError(e.code, "The personnel request failed."))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PERSONNEL_REQUEST", e.message ?: "The personnel request is invalid."))
    }
}

private fun statusFor(code: String): HttpStatusCode =
    when (code) {
        "FORBIDDEN", "SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
        "TECHNICIAN_NOT_FOUND", "OPERATOR_NOT_FOUND", "PERSON_NOT_FOUND", "EQUIPMENT_NOT_FOUND", "SITE_NOT_FOUND", "TUNNEL_NOT_FOUND", "SHIFT_NOT_FOUND", "ASSIGNMENT_NOT_FOUND", "TRANSFER_NOT_FOUND" -> HttpStatusCode.NotFound
        "TECHNICIAN_EXISTS", "OPERATOR_EXISTS", "TECHNICIAN_VERSION_CONFLICT", "OPERATOR_VERSION_CONFLICT" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }
