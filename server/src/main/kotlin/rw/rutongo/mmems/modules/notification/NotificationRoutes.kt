@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package rw.rutongo.mmems.modules.notification

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
import rw.rutongo.mmems.shared.api.NotificationRuleUpdateDto
import rw.rutongo.mmems.shared.api.PushDeviceRegistrationDto
import rw.rutongo.mmems.shared.enums.NotificationType
import java.time.Instant
import java.util.UUID

fun Route.notificationRoutes(
    service: NotificationService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        get("/notifications") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val since = call.request.queryParameters["since"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (call.request.queryParameters["since"] != null && since == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SINCE", "The since timestamp is invalid."))
                return@get
            }
            respondNotificationFailure(call) {
                call.respond(
                    service.list(
                        actor,
                        since,
                        call.request.queryParameters["unreadOnly"]?.toBooleanStrictOrNull() ?: false,
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                    ),
                )
            }
        }
        post("/notifications/read-all") {
            val actor = call.authenticated(identity, signer) ?: return@post
            respondNotificationFailure(call) {
                call.respond(mapOf("updated" to service.markAllRead(actor)))
            }
        }
        post("/notifications/devices") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val input =
                runCatching { call.receive<PushDeviceRegistrationDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PUSH_DEVICE", "The push device is invalid."))
                    return@post
                }
            try {
                call.respond(HttpStatusCode.Created, service.registerDevice(actor, input))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PUSH_DEVICE", e.message ?: "The push device is invalid."))
            }
        }
        post("/notifications/{id}/read") {
            val actor = call.authenticated(identity, signer) ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_NOTIFICATION_ID", "The notification ID is invalid."))
                return@post
            }
            respondNotificationFailure(call) { call.respond(service.markRead(actor, id)) }
        }
        get("/notification-rules") {
            val actor = call.authenticated(identity, signer) ?: return@get
            respondNotificationFailure(call) { call.respond(service.rules(actor)) }
        }
        put("/notification-rules/{type}") {
            val actor = call.authenticated(identity, signer) ?: return@put
            val type = call.parameters["type"]?.let { runCatching { NotificationType.valueOf(it.uppercase()) }.getOrNull() }
            if (type == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_NOTIFICATION_TYPE", "The notification type is invalid."))
                return@put
            }
            val input =
                runCatching { call.receive<NotificationRuleUpdateDto>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_NOTIFICATION_RULE", "The notification rule is invalid."))
                    return@put
                }
            try {
                call.respond(service.updateRule(actor, type, input))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_NOTIFICATION_RULE", e.message ?: "The notification rule is invalid."))
            } catch (e: AuthFailure) {
                call.respond(HttpStatusCode.Forbidden, ApiError(e.code, "The notification rule cannot be changed."))
            }
        }
    }
}

private suspend fun respondNotificationFailure(
    call: ApplicationCall,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: AuthFailure) {
        val status =
            when (e.code) {
                "FORBIDDEN" -> HttpStatusCode.Forbidden
                "NOTIFICATION_NOT_FOUND" -> HttpStatusCode.NotFound
                else -> HttpStatusCode.BadRequest
            }
        call.respond(status, ApiError(e.code, "The notification request failed."))
    }
}
