@file:Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.report

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
import rw.rutongo.mmems.shared.api.ReportActionDto
import rw.rutongo.mmems.shared.api.ReportCreateDto
import rw.rutongo.mmems.shared.api.ReportNarrativeUpdateDto
import rw.rutongo.mmems.shared.enums.ReportPeriod
import rw.rutongo.mmems.shared.enums.ReportState
import java.time.LocalDate
import java.util.UUID

private const val DEFAULT_REPORT_LIMIT = 100
private const val PRECONDITION_REQUIRED = 428

fun Route.reportRoutes(
    service: ReportService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1/reports") {
        get("/preview") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let(::parseUuid)
            val date = call.request.queryParameters["reportDate"]?.let(::parseDate)
            val period = call.request.queryParameters["period"]?.let(::parsePeriod)
            if (siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (date == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_DATE", "The report date is invalid."))
                return@get
            }
            if (period == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_PERIOD", "The report period is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.preview(actor, siteId, date, period)) }
        }
        get {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteId = call.request.queryParameters["siteId"]?.let(::parseUuid)
            val date = call.request.queryParameters["reportDate"]?.let(::parseDate)
            val state = call.request.queryParameters["state"]?.let(::parseState)
            if (call.request.queryParameters["siteId"] != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["reportDate"] != null && date == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_DATE", "The report date is invalid."))
                return@get
            }
            if (call.request.queryParameters["state"] != null && state == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_STATE", "The report state is invalid."))
                return@get
            }
            respondFailure(call) {
                call.respond(
                    service.list(
                        actor,
                        siteId,
                        date,
                        state,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_REPORT_LIMIT,
                    ),
                )
            }
        }
        post {
            val actor = call.authenticated(identity, signer) ?: return@post
            val request =
                runCatching { call.receive<ReportCreateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT", "The report is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(HttpStatusCode.Created, service.create(actor, request)) }
        }
        get("/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@get
            }
            val version = queryVersion(call)
            if (call.request.queryParameters["version"] != null && version == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_VERSION", "The report version is invalid."))
                return@get
            }
            respondFailure(call) { call.respond(service.get(actor, id, version)) }
        }
        put("/{id}/narrative") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@put
            }
            val version = headerVersion(call)
            if (version == null) {
                call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match report version is required."))
                return@put
            }
            val request =
                runCatching { call.receive<ReportNarrativeUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_NARRATIVE", "The report narrative is invalid."))
                    return@put
                }
            respondFailure(call) { call.respond(service.updateNarrative(actor, id, version, request)) }
        }
        get("/{id}/pdf") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@get
            }
            val version = queryVersion(call)
            if (call.request.queryParameters["version"] != null && version == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_VERSION", "The report version is invalid."))
                return@get
            }
            respondFailure(call) {
                val pdf = service.pdf(actor, id, version)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${pdf.fileName}\"")
                call.respondBytes(pdf.bytes, ContentType.Application.Pdf)
            }
        }
        post("/{id}/submit") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@post
            }
            val version = requiredVersion(call) ?: return@post
            respondFailure(call) { call.respond(service.submit(actor, id, version)) }
        }
        post("/{id}/approve") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@post
            }
            val version = requiredVersion(call) ?: return@post
            val request =
                runCatching { call.receive<ReportActionDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ACTION", "The report action is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(service.approve(actor, id, version, request.comment)) }
        }
        post("/{id}/return") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@post
            }
            val version = requiredVersion(call) ?: return@post
            val request =
                runCatching { call.receive<ReportActionDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ACTION", "The report action is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(service.returnForCorrection(actor, id, version, request.reason ?: request.comment)) }
        }
        post("/{id}/new-version") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let(::parseUuid)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ID", "The report ID is invalid."))
                return@post
            }
            val version = requiredVersion(call) ?: return@post
            val request =
                runCatching { call.receive<ReportActionDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_ACTION", "The report action is invalid."))
                    return@post
                }
            respondFailure(call) { call.respond(service.newVersion(actor, id, version, request.reason ?: request.comment)) }
        }
    }
}

private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private fun parsePeriod(value: String): ReportPeriod? = runCatching { ReportPeriod.valueOf(value.uppercase()) }.getOrNull()

private fun parseState(value: String): ReportState? = runCatching { ReportState.valueOf(value.uppercase()) }.getOrNull()

private fun queryVersion(call: ApplicationCall): Int? = call.request.queryParameters["version"]?.toIntOrNull()

private fun headerVersion(call: ApplicationCall): Int? =
    call.request.headers["If-Match"]
        ?.trim('"')
        ?.toIntOrNull()

private suspend fun requiredVersion(call: ApplicationCall): Int? {
    val version = headerVersion(call)
    if (version == null) {
        call.respond(HttpStatusCode.fromValue(PRECONDITION_REQUIRED), ApiError("IF_MATCH_REQUIRED", "A valid If-Match report version is required."))
    }
    return version
}

private suspend fun respondFailure(
    call: ApplicationCall,
    action: suspend () -> Unit,
) {
    try {
        action()
    } catch (e: AuthFailure) {
        call.respond(statusFor(e.code), ApiError(e.code, "The report request failed."))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REPORT_REQUEST", e.message ?: "The report request is invalid."))
    }
}

private fun statusFor(code: String): HttpStatusCode =
    when (code) {
        "FORBIDDEN", "SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
        "REPORT_NOT_FOUND", "SITE_NOT_FOUND" -> HttpStatusCode.NotFound
        "REPORT_VERSION_CONFLICT", "REPORT_ALREADY_EXISTS", "INVALID_REPORT_STATE", "APPROVER_CANNOT_BE_AUTHOR" -> HttpStatusCode.Conflict
        "REPORT_PDF_INTEGRITY_FAILED" -> HttpStatusCode.InternalServerError
        else -> HttpStatusCode.BadRequest
    }
