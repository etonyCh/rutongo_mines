package rw.rutongo.mmems.modules.status

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import rw.rutongo.mmems.shared.enums.OperationalStatus
import java.time.Instant
import java.util.UUID

@Serializable private data class RecordStatusRequest(val status:OperationalStatus,val problem:String?=null,val actionTaken:String?=null,val partsRequired:String?=null,val remarks:String?=null,val tunnelId:String?=null,val shiftId:String?=null,val occurredAt:String)

fun Route.statusRoutes(service:StatusService,identity:IdentityService,signer:TokenSigner) {
    route("/api/v1") {
        post("/equipment/{id}/status") {
            val actor=call.authenticated(identity,signer) ?: return@post
            if(!RolePermissions.allows(actor.role,"STATUS_UPDATE")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to update equipment status.")); return@post }
            val id=call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(id==null) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_EQUIPMENT_ID","The equipment ID is invalid.")); return@post }
            val version=call.request.headers[HttpHeaders.IfMatch]?.trim('"')?.toLongOrNull()
            if(version==null) { call.respond(HttpStatusCode.fromValue(428),ApiError("IF_MATCH_REQUIRED","A valid If-Match equipment version is required.")); return@post }
            val key=call.request.headers["Idempotency-Key"]
            if(key.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest,ApiError("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key is required.")); return@post }
            try {
                val body=call.receive<RecordStatusRequest>()
                val occurredAt=runCatching { Instant.parse(body.occurredAt) }.getOrElse { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_OCCURRED_AT","occurredAt must be an ISO-8601 instant.")); return@post }
                call.respond(HttpStatusCode.Created,service.record(actor,id,version,key,body.status,body.problem,body.actionTaken,body.partsRequired,body.remarks,body.tunnelId?.let(UUID::fromString),body.shiftId?.let(UUID::fromString),occurredAt))
            } catch(e:IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_STATUS_CHANGE",e.message ?: "The status change is invalid.")) }
            catch(e:rw.rutongo.mmems.identity.AuthFailure) { val status=when(e.code) { "FORBIDDEN","SITE_NOT_AUTHORIZED","IDEMPOTENCY_KEY_REPLAY_FORBIDDEN"->HttpStatusCode.Forbidden; "EQUIPMENT_NOT_FOUND","SHIFT_NOT_FOUND","TUNNEL_NOT_IN_SITE"->HttpStatusCode.NotFound; "EQUIPMENT_VERSION_CONFLICT"->HttpStatusCode.Conflict; else->HttpStatusCode.BadRequest }; call.respond(status,ApiError(e.code,"The equipment status could not be updated.")) }
        }
        get("/equipment/{id}/status-events") {
            val actor=call.authenticated(identity,signer) ?: return@get
            val id=call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(id==null) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_EQUIPMENT_ID","The equipment ID is invalid.")); return@get }
            try { call.respond(service.history(actor,id,call.request.queryParameters["limit"]?.toIntOrNull() ?: 100)) }
            catch(e:rw.rutongo.mmems.identity.AuthFailure) { call.respond(if(e.code=="FORBIDDEN"||e.code=="SITE_NOT_AUTHORIZED") HttpStatusCode.Forbidden else HttpStatusCode.NotFound,ApiError(e.code,"The status history is unavailable.")) }
        }
        get("/active-problems") {
            val actor=call.authenticated(identity,signer) ?: return@get
            val site=call.request.queryParameters["siteId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(call.request.queryParameters["siteId"]!=null && site==null) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_SITE_ID","The site ID is invalid.")); return@get }
            try { call.respond(service.activeProblems(actor,site,call.request.queryParameters["limit"]?.toIntOrNull() ?: 100)) }
            catch(e:rw.rutongo.mmems.identity.AuthFailure) { call.respond(HttpStatusCode.Forbidden,ApiError(e.code,"Active problems are unavailable.")) }
        }
        get("/events") {
            val actor=call.authenticated(identity,signer) ?: return@get
            val lastEventId=call.request.headers["Last-Event-ID"]
            val parts=lastEventId?.split('|')
            val since=parts?.getOrNull(0)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val sinceId=parts?.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(lastEventId!=null && (since==null || sinceId==null)) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_LAST_EVENT_ID","Last-Event-ID is invalid.")); return@get }
            call.response.header(HttpHeaders.CacheControl,"no-cache")
            call.response.header("X-Accel-Buffering","no")
            call.respondTextWriter(contentType=ContentType.Text.EventStream) {
                var cursorAt=since
                var cursorId=sinceId
                while(true) {
                    val events=service.eventsAfter(actor,cursorAt,cursorId)
                    if(events.isEmpty()) { write(": keep-alive\n\n"); flush(); delay(20_000) }
                    else events.forEach { event ->
                        write("id: ${event.recordedAt}|${event.id}\nevent: equipment-status\ndata: ${kotlinx.serialization.json.Json.encodeToString(StatusEventView.serializer(),event)}\n\n")
                        flush(); cursorAt=Instant.parse(event.recordedAt); cursorId=UUID.fromString(event.id)
                    }
                }
            }
        }
    }
}
