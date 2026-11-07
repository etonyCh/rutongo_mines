package rw.rutongo.mmems.modules.photo

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import java.io.ByteArrayOutputStream
import java.util.UUID

fun Route.photoRoutes(
    service: PhotoService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        post("/photos") {
            val actor = call.authenticated(identity, signer) ?: return@post
            var siteId: UUID? = null
            var targetType: String? = null
            var targetId: UUID? = null
            var kind = "EVIDENCE"
            var photoId: UUID? = null
            var fileName: String? = null
            var contentType: String? = null
            var fileBytes: ByteArray? = null
            var tooLarge = false
            if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > PhotoService.MAX_BYTES + 1024L * 1024L) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("PHOTO_TOO_LARGE", "Photo exceeds 10 MiB."))
                return@post
            }
            call.receiveMultipart().forEachPart { part ->
                try {
                    when (part) {
                        is PartData.FormItem ->
                            when (part.name) {
                                "siteId" -> siteId = runCatching { UUID.fromString(part.value) }.getOrNull()
                                "targetType" -> targetType = part.value.uppercase()
                                "targetId" -> targetId = runCatching { UUID.fromString(part.value) }.getOrNull()
                                "kind" -> kind = part.value.uppercase()
                                "photoId" -> photoId = runCatching { UUID.fromString(part.value) }.getOrNull()
                            }
                        is PartData.FileItem ->
                            if (part.name == "file") {
                                fileName = part.originalFileName
                                contentType = part.contentType?.toString()
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                part.streamProvider().use { input ->
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > PhotoService.MAX_BYTES) {
                                            tooLarge = true
                                            break
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                                fileBytes = output.toByteArray()
                            }
                        else -> Unit
                    }
                } finally {
                    part.dispose()
                }
            }
            if (tooLarge) {
                call.respond(HttpStatusCode.PayloadTooLarge, ApiError("PHOTO_TOO_LARGE", "Photo exceeds 10 MiB."))
                return@post
            }
            if (siteId == null || targetType == null || targetId == null || fileName.isNullOrBlank() || fileBytes == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("PHOTO_FILE_REQUIRED", "Provide siteId, targetType, targetId and a JPEG or PNG file."))
                return@post
            }
            val uploadSiteId = siteId
            val uploadTargetId = targetId
            val uploadTargetType = targetType
            try {
                call.respond(HttpStatusCode.Created, service.upload(actor, uploadSiteId!!, uploadTargetType!!, uploadTargetId!!, kind, contentType, fileBytes!!, photoId))
            } catch (e: IllegalArgumentException) {
                val code = if (e.message == "SITE_NOT_AUTHORIZED") "SITE_NOT_AUTHORIZED" else "INVALID_PHOTO"
                val status = if (code == "SITE_NOT_AUTHORIZED") HttpStatusCode.Forbidden else HttpStatusCode.BadRequest
                call.respond(status, ApiError(code, e.message ?: "The photo could not be uploaded."))
            }
        }
        get("/photos/{id}") {
            val actor = call.authenticated(identity, signer) ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_PHOTO_ID", "The photo ID is invalid."))
                return@get
            }
            try {
                val photo = service.open(actor, id)
                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$id.${if (photo.mime == "image/png") "png" else "jpg"}\"")
                call.respondBytes(photo.bytes, ContentType.parse(photo.mime), HttpStatusCode.OK)
            } catch (e: AuthFailure) {
                val status = if (e.code == "SITE_NOT_AUTHORIZED") HttpStatusCode.Forbidden else HttpStatusCode.NotFound
                call.respond(status, ApiError(e.code, "The photo was not found."))
            }
        }
    }
}
