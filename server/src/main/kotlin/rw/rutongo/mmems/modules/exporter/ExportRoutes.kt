@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.exporter

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.shared.enums.ExportFormat
import java.time.Instant
import java.util.UUID

fun Route.exportRoutes(
    service: ExportService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1/exports") {
        get {
            val actor = call.authenticated(identity, signer) ?: return@get
            try {
                call.respond(service.datasets(actor))
            } catch (e: AuthFailure) {
                call.respond(HttpStatusCode.Forbidden, ApiError(e.code, "Exports are unavailable."))
            }
        }
        get("/{file}") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val file =
                call.parameters["file"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EXPORT", "The export file is invalid."))
                    return@get
                }
            val separator = file.lastIndexOf('.')
            if (separator <= 0 || separator == file.lastIndex) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EXPORT", "The export file is invalid."))
                return@get
            }
            val dataset = file.substring(0, separator)
            val format = runCatching { ExportFormat.valueOf(file.substring(separator + 1).uppercase()) }.getOrNull()
            if (format == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EXPORT_FORMAT", "The export format is invalid."))
                return@get
            }
            val rawSite = call.request.queryParameters["site"] ?: call.request.queryParameters["siteId"]
            val siteId = rawSite?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (rawSite != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            val from = parseInstant(call.request.queryParameters["from"])
            if (call.request.queryParameters["from"] != null && from == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_FROM", "The export start is invalid."))
                return@get
            }
            val to = parseInstant(call.request.queryParameters["to"])
            if (call.request.queryParameters["to"] != null && to == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TO", "The export end is invalid."))
                return@get
            }
            try {
                val exported = service.export(actor, dataset, format, siteId, from, to)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${exported.fileName}\"")
                call.respondBytes(exported.bytes, ContentType.parse(exported.contentType))
            } catch (e: AuthFailure) {
                val status =
                    when (e.code) {
                        "FORBIDDEN", "SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
                        "EXPORT_DATASET_NOT_FOUND" -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.BadRequest
                    }
                call.respond(status, ApiError(e.code, "The export could not be generated."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EXPORT_RANGE", e.message ?: "The export range is invalid."))
            }
        }
    }
}

private fun parseInstant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
