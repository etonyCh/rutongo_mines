@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package rw.rutongo.mmems.modules.dashboard

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.shared.enums.DashboardKind
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

fun Route.dashboardRoutes(
    service: DashboardService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1/dashboard") {
        get("/{kind}") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val kind = call.parameters["kind"]?.let { runCatching { DashboardKind.valueOf(it.uppercase()) }.getOrNull() }
            if (kind == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_DASHBOARD", "The dashboard kind is invalid."))
                return@get
            }
            val rawSite = call.request.queryParameters["site"] ?: call.request.queryParameters["siteId"]
            val siteId = rawSite?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (rawSite != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            val to =
                call.request.queryParameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: Instant.now()
            if (call.request.queryParameters["to"] != null && runCatching { Instant.parse(call.request.queryParameters["to"]!!) }.isFailure) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TO", "The dashboard end is invalid."))
                return@get
            }
            val from =
                call.request.queryParameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: to.minus(7, ChronoUnit.DAYS)
            if (call.request.queryParameters["from"] != null && runCatching { Instant.parse(call.request.queryParameters["from"]!!) }.isFailure) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_FROM", "The dashboard start is invalid."))
                return@get
            }
            try {
                call.respond(service.get(actor, kind, siteId, from, to))
            } catch (e: AuthFailure) {
                val status = if (e.code == "FORBIDDEN" || e.code == "SITE_NOT_AUTHORIZED") HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                call.respond(status, ApiError(e.code, "The dashboard is unavailable."))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_DASHBOARD_RANGE", e.message ?: "The dashboard range is invalid."))
            }
        }
    }
}
