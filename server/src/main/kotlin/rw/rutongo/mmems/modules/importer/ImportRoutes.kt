package rw.rutongo.mmems.modules.importer

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import java.io.ByteArrayOutputStream
import java.util.UUID

@Serializable private data class CreateImportProfileRequest(val name: String,val kind: ImportKind,val sheetName: String?=null,val columnMapping: Map<String,String>)
@Serializable private data class CommitImportRequest(val applyUpdates: Boolean=false)

fun Route.importRoutes(service: ImportService,identity: IdentityService,signer: TokenSigner) {
    route("/api/v1") {
        route("/import-profiles") {
            get {
                val user=call.authenticated(identity,signer) ?: return@get
                if(!RolePermissions.allows(user.role,"IMPORT_RUN")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to manage import profiles.")); return@get }
                call.respond(service.profiles())
            }
            post {
                val user=call.authenticated(identity,signer) ?: return@post
                if(!RolePermissions.allows(user.role,"IMPORT_RUN")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to manage import profiles.")); return@post }
                try {
                    val request=call.receive<CreateImportProfileRequest>()
                    call.respond(HttpStatusCode.Created,service.createProfile(user.id,request.name,request.kind,request.sheetName,request.columnMapping))
                } catch(e: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_IMPORT_PROFILE",e.message ?: "The import profile is invalid.")) }
                catch(e: AuthFailure) { call.respond(HttpStatusCode.Conflict,ApiError(e.code,"The import profile could not be created.")) }
            }
        }
        post("/imports") {
            val user=call.authenticated(identity,signer) ?: return@post
            if(!RolePermissions.allows(user.role,"IMPORT_RUN")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to run imports.")); return@post }
            var profileId: UUID?=null
            var fileName: String?=null
            var fileBytes: ByteArray?=null
            var tooLarge=false
            if((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L)>ImportService.MAX_FILE_SIZE+1024L*1024L) {
                call.respond(HttpStatusCode.PayloadTooLarge,ApiError("FILE_TOO_LARGE","Workbook exceeds 20 MiB.")); return@post
            }
            call.receiveMultipart().forEachPart { part ->
                try {
                    when(part) {
                        is PartData.FormItem -> if(part.name=="profileId") profileId=runCatching { UUID.fromString(part.value) }.getOrNull()
                        is PartData.FileItem -> if(part.name=="file") {
                            fileName=part.originalFileName
                            val output=ByteArrayOutputStream()
                            val buffer=ByteArray(8192)
                            part.streamProvider().use { input ->
                                while(true) {
                                    val count=input.read(buffer)
                                    if(count<0) break
                                    if(output.size()+count>ImportService.MAX_FILE_SIZE) { tooLarge=true; break }
                                    output.write(buffer,0,count)
                                }
                            }
                            fileBytes=output.toByteArray()
                        }
                        else -> Unit
                    }
                } finally { part.dispose() }
            }
            if(tooLarge) { call.respond(HttpStatusCode.PayloadTooLarge,ApiError("FILE_TOO_LARGE","Workbook exceeds 20 MiB.")); return@post }
            if(profileId==null || fileName.isNullOrBlank() || fileBytes==null) { call.respond(HttpStatusCode.BadRequest,ApiError("IMPORT_FILE_REQUIRED","Provide profileId and an .xlsx file.")); return@post }
            try {
                val result=service.preview(user,profileId!!,fileName!!,fileBytes!!)
                call.respond(HttpStatusCode.Created,result)
            } catch(e: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_WORKBOOK",e.message ?: "The workbook could not be read.")) }
            catch(e: AuthFailure) { call.respond(HttpStatusCode.NotFound,ApiError(e.code,"The import profile was not found.")) }
        }
        get("/imports/{id}") {
            val user=call.authenticated(identity,signer) ?: return@get
            if(!RolePermissions.allows(user.role,"IMPORT_RUN")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to view imports.")); return@get }
            val id=call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(id==null) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_IMPORT_ID","The import ID is invalid.")); return@get }
            try { call.respond(service.rows(id,call.request.queryParameters["from"]?.toIntOrNull() ?: 1,call.request.queryParameters["limit"]?.toIntOrNull() ?: 100)) }
            catch(e: AuthFailure) { call.respond(HttpStatusCode.NotFound,ApiError(e.code,"The import was not found.")) }
        }
        post("/imports/{id}/commit") {
            val user=call.authenticated(identity,signer) ?: return@post
            if(!RolePermissions.allows(user.role,"IMPORT_RUN")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to commit imports.")); return@post }
            val id=call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(id==null) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_IMPORT_ID","The import ID is invalid.")); return@post }
            try {
                val request=call.receive<CommitImportRequest>()
                call.respond(service.commit(user,id,request.applyUpdates))
            } catch(e: AuthFailure) { call.respond(HttpStatusCode.Conflict,ApiError(e.code,"The import could not be committed.")) }
        }
        get("/imports/{id}/rejections.csv") {
            val user=call.authenticated(identity,signer) ?: return@get
            if(!RolePermissions.allows(user.role,"IMPORT_RUN")) { call.respond(HttpStatusCode.Forbidden,ApiError("FORBIDDEN","You are not allowed to export rejected import rows.")); return@get }
            val id=call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if(id==null) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_IMPORT_ID","The import ID is invalid.")); return@get }
            try {
                call.response.header(HttpHeaders.ContentDisposition,"attachment; filename=\"import-rejections.csv\"")
                call.respondText(service.rejectionCsv(id),ContentType.Text.CSV)
            } catch(e: AuthFailure) { call.respond(HttpStatusCode.NotFound,ApiError(e.code,"The import was not found.")) }
        }
    }
}
