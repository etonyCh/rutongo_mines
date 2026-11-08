@file:Suppress("CyclomaticComplexMethod", "ReturnCount", "MagicNumber")

package rw.rutongo.mmems

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.modules.notification.NotificationService
import rw.rutongo.mmems.modules.status.StatusEventView
import rw.rutongo.mmems.modules.status.StatusService
import rw.rutongo.mmems.shared.api.NotificationDto
import java.time.Instant
import java.util.UUID

fun Route.eventRoutes(
    statuses: StatusService,
    notifications: NotificationService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    get("/api/v1/events") {
        val actor = call.authenticated(identity, signer) ?: return@get
        val cursor = EventCursor.parse(call.request.headers["Last-Event-ID"])
        if (cursor == null && call.request.headers["Last-Event-ID"] != null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_LAST_EVENT_ID", "Last-Event-ID is invalid."))
            return@get
        }
        val initialNotificationCursor = cursor?.notificationAt ?: Instant.now()
        val initialNotificationId = cursor?.notificationId ?: UUID(0, 0)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            var statusAt = cursor?.statusAt
            var statusId = cursor?.statusId
            var notificationAt = initialNotificationCursor
            var notificationId = initialNotificationId
            while (true) {
                val statusEvents = statuses.eventsAfter(actor, statusAt, statusId)
                val notificationEvents = notifications.eventsAfter(actor, notificationAt, notificationId)
                if (statusEvents.isEmpty() && notificationEvents.isEmpty()) {
                    write(": keep-alive\n\n")
                    flush()
                    delay(20_000)
                } else {
                    statusEvents.forEach { event ->
                        statusAt = Instant.parse(event.recordedAt)
                        statusId = UUID.fromString(event.id)
                        write("id: ${cursorId(statusAt, statusId, notificationAt, notificationId)}\nevent: equipment-status\ndata: ${Json.encodeToString(StatusEventView.serializer(), event)}\n\n")
                        flush()
                    }
                    notificationEvents.forEach { event ->
                        notificationAt = Instant.parse(event.createdAt)
                        notificationId = UUID.fromString(event.id)
                        write("id: ${cursorId(statusAt, statusId, notificationAt, notificationId)}\nevent: notification\ndata: ${Json.encodeToString(NotificationDto.serializer(), event)}\n\n")
                        flush()
                    }
                }
            }
        }
    }
}

private fun cursorId(
    statusAt: Instant?,
    statusId: UUID?,
    notificationAt: Instant?,
    notificationId: UUID?,
): String = "s|${statusAt ?: ""}|${statusId ?: ""}|n|${notificationAt ?: ""}|${notificationId ?: ""}"

private data class EventCursor(
    val statusAt: Instant?,
    val statusId: UUID?,
    val notificationAt: Instant?,
    val notificationId: UUID?,
) {
    companion object {
        fun parse(raw: String?): EventCursor? {
            if (raw == null) return EventCursor(null, null, null, null)
            val parts = raw.split('|')
            if (parts.size == 2) {
                val at = runCatching { Instant.parse(parts[0]) }.getOrNull() ?: return null
                val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
                return EventCursor(at, id, null, null)
            }
            if (parts.size != 6 || parts[0] != "s" || parts[3] != "n") return null
            val statusAt = parts[1].takeIf(String::isNotEmpty)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val statusId = parts[2].takeIf(String::isNotEmpty)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val notificationAt = parts[4].takeIf(String::isNotEmpty)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val notificationId = parts[5].takeIf(String::isNotEmpty)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (parts[1].isNotEmpty() && statusAt == null) return null
            if (parts[2].isNotEmpty() && statusId == null) return null
            if (parts[4].isNotEmpty() && notificationAt == null) return null
            if (parts[5].isNotEmpty() && notificationId == null) return null
            return EventCursor(statusAt, statusId, notificationAt, notificationId)
        }
    }
}
