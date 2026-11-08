@file:Suppress("CyclomaticComplexMethod")

package rw.rutongo.mmems.modules.workinghours

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
import rw.rutongo.mmems.shared.enums.WorkingHoursGroupBy
import java.time.LocalDate
import java.util.UUID

fun Route.workingHoursRoutes(
    service: WorkingHoursService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        get("/working-hours") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val siteParam = call.request.queryParameters["siteId"] ?: call.request.queryParameters["site"]
            val equipmentParam = call.request.queryParameters["equipmentId"] ?: call.request.queryParameters["equipment"]
            val shiftParam = call.request.queryParameters["shiftId"] ?: call.request.queryParameters["shift"]
            val siteId = siteParam?.let { parseUuid(it) }
            val equipmentId = equipmentParam?.let { parseUuid(it) }
            val shiftId = shiftParam?.let { parseUuid(it) }
            val from = call.request.queryParameters["from"]?.let { parseDate(it) }
            val to = call.request.queryParameters["to"]?.let { parseDate(it) }
            val groupByParam = call.request.queryParameters["groupBy"]
            val requestedGroupBy = groupByParam?.let { runCatching { WorkingHoursGroupBy.valueOf(it.uppercase()) }.getOrNull() }
            val groupBy = requestedGroupBy ?: WorkingHoursGroupBy.EQUIPMENT
            if (siteParam != null && siteId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                return@get
            }
            if (equipmentParam != null && equipmentId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            if (shiftParam != null && shiftId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SHIFT_ID", "The shift ID is invalid."))
                return@get
            }
            if (call.request.queryParameters["from"] != null && from == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_FROM", "The from date is invalid."))
                return@get
            }
            if (call.request.queryParameters["to"] != null && to == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TO", "The to date is invalid."))
                return@get
            }
            if (groupByParam != null && requestedGroupBy == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_GROUP_BY", "The groupBy value is invalid."))
                return@get
            }
            try {
                call.respond(service.list(actor, siteId, equipmentId, from, to, shiftId, groupBy))
            } catch (e: AuthFailure) {
                val status = if (e.code == "SITE_NOT_AUTHORIZED") HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                call.respond(status, ApiError(e.code, "The working-hours request failed."))
            }
        }
    }
}

private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
