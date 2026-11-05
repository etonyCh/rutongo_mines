package rw.rutongo.mmems.identity

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.shared.enums.Role
import java.util.UUID

@Serializable private data class LoginRequest(val username: String, val password: String, val clientType: String = "WEB", val deviceLabel: String? = null)
@Serializable private data class RefreshRequest(val refreshToken: String? = null, val clientType: String = "WEB", val deviceLabel: String? = null)
@Serializable private data class LogoutRequest(val refreshToken: String? = null)
@Serializable private data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable private data class CreateUserRequest(val username: String, val fullName: String, val employeeId: String, val role: Role, val initialPassword: String, val siteIds: List<String>)
@Serializable private data class SetUserActiveRequest(val active: Boolean)
@Serializable private data class UpdateUserRequest(val fullName: String, val employeeId: String, val role: Role, val siteIds: List<String>)
@Serializable private data class SessionResponse(val accessToken: String, val refreshToken: String? = null, val tokenType: String = "Bearer", val expiresIn: Long, val mustChangePassword: Boolean)

fun Route.identityRoutes(identity: IdentityService, signer: TokenSigner) {
    route("/api/v1") {
        route("/auth") {
            post("/login") {
                val request = call.receive<LoginRequest>()
                val ip = call.request.local.remoteHost
                if (!identity.allowLoginAttempt("ip:$ip") || !identity.allowLoginAttempt("username:${request.username.trim().lowercase()}")) {
                    call.respond(HttpStatusCode.TooManyRequests, ApiError("RATE_LIMITED", "Please wait before trying again.")); return@post
                }
                val session = identity.login(request.username, request.password.toCharArray(), request.clientType.uppercase(), request.deviceLabel)
                if (session == null) { call.respond(HttpStatusCode.Unauthorized, ApiError("INVALID_CREDENTIALS", "Username or password is incorrect.")); return@post }
                val web = request.clientType.equals("WEB", ignoreCase = true)
                if (web) setRefreshCookie(call, session.refreshToken, 8 * 60 * 60)
                call.respond(SessionResponse(session.accessToken, if (web) null else session.refreshToken, expiresIn = session.expiresIn, mustChangePassword = session.mustChangePassword))
            }
            post("/refresh") {
                val request = call.receive<RefreshRequest>()
                val web = request.clientType.equals("WEB", ignoreCase = true)
                if (web && (call.request.headers["X-CSRF-Token"].isNullOrBlank() || call.request.headers["X-Requested-With"] != "rutongo-mmems")) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("CSRF_REJECTED", "The request could not be verified.")); return@post
                }
                val raw = if (web) call.request.cookies["refresh_token"] else request.refreshToken
                if (raw.isNullOrBlank()) { call.respond(HttpStatusCode.Unauthorized, ApiError("INVALID_REFRESH_TOKEN", "The session is invalid or expired.")); return@post }
                val session = identity.refresh(raw, request.clientType.uppercase(), request.deviceLabel)
                if (session == null) { clearRefreshCookie(call); call.respond(HttpStatusCode.Unauthorized, ApiError("INVALID_REFRESH_TOKEN", "The session is invalid or expired.")); return@post }
                if (web) setRefreshCookie(call, session.refreshToken, 8 * 60 * 60)
                call.respond(SessionResponse(session.accessToken, if (web) null else session.refreshToken, expiresIn = session.expiresIn, mustChangePassword = session.mustChangePassword))
            }
            post("/logout") {
                val request = call.receiveNullable<LogoutRequest>()
                val raw = request?.refreshToken ?: call.request.cookies["refresh_token"]
                if (!raw.isNullOrBlank()) identity.logout(raw)
                clearRefreshCookie(call)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/change-password") {
                val principal = call.authenticated(identity, signer) ?: return@post
                val request = call.receive<ChangePasswordRequest>()
                try {
                    identity.changePassword(principal, request.currentPassword.toCharArray(), request.newPassword.toCharArray())
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, ApiError("PASSWORD_POLICY", e.message ?: "The password does not meet policy.")) }
            }
        }
        get("/me") {
            val principal = call.authenticated(identity, signer) ?: return@get
            val me = identity.me(principal.id)
            if (me == null) call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHENTICATED", "Authentication is required.")) else call.respond(me)
        }
        route("/users") {
            get {
                val principal = call.authenticated(identity, signer) ?: return@get
                if (!RolePermissions.allows(principal.role, "USER_MANAGE")) { call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to manage users.")); return@get }
                call.respond(identity.listUsers())
            }
            post {
                val principal = call.authenticated(identity, signer) ?: return@post
                if (!RolePermissions.allows(principal.role, "USER_MANAGE")) { call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to manage users.")); return@post }
                try {
                    val request = call.receive<CreateUserRequest>()
                    val created = identity.createUser(principal.id, request.username, request.fullName, request.employeeId, request.role, request.initialPassword.toCharArray(), request.siteIds.map(UUID::fromString))
                    call.respond(HttpStatusCode.Created, created)
                } catch (e: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_USER", e.message ?: "The user details are invalid.")) }
                catch (e: AuthFailure) { call.respond(HttpStatusCode.Conflict, ApiError(e.code, "The user could not be created.")) }
            }
            put("/{id}") {
                val principal = call.authenticated(identity, signer) ?: return@put
                if (!RolePermissions.allows(principal.role, "USER_MANAGE")) { call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to manage users.")); return@put }
                val expectedVersion = call.request.headers[HttpHeaders.IfMatch]?.trim('"')?.toLongOrNull()
                if (expectedVersion == null) { call.respond(HttpStatusCode.PreconditionRequired, ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required.")); return@put }
                try {
                    val id=UUID.fromString(call.parameters["id"])
                    val request=call.receive<UpdateUserRequest>()
                    call.respond(identity.updateUser(principal.id,id,expectedVersion,request.fullName,request.employeeId,request.role,request.siteIds.map(UUID::fromString)))
                } catch(e: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest,ApiError("INVALID_USER",e.message ?: "The user details are invalid.")) }
                catch(e: AuthFailure) { call.respond(HttpStatusCode.Conflict,ApiError(e.code,"The user could not be updated.")) }
            }
            patch("/{id}/active") {
                val principal = call.authenticated(identity, signer) ?: return@patch
                if (!RolePermissions.allows(principal.role, "USER_MANAGE")) { call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to manage users.")); return@patch }
                try {
                    val id = UUID.fromString(call.parameters["id"])
                    identity.setUserActive(principal.id, id, call.receive<SetUserActiveRequest>().active)
                    call.respond(HttpStatusCode.NoContent)
                } catch (_: IllegalArgumentException) { call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_USER_ID", "The user ID is invalid.")) }
                catch (e: AuthFailure) { call.respond(HttpStatusCode.NotFound, ApiError(e.code, "The user was not found.")) }
            }
        }
        get("/audit-events") {
            val principal = call.authenticated(identity, signer) ?: return@get
            if (!RolePermissions.allows(principal.role, "AUDIT_VIEW")) { call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to view audit events.")); return@get }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            call.respond(identity.auditEvents(limit))
        }
    }
}

private suspend fun ApplicationCall.authenticated(identity: IdentityService, signer: TokenSigner): Principal? {
    val bearer = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')
    val tokenPrincipal = bearer?.let(signer::verify)
    val principal = tokenPrincipal?.let { identity.currentPrincipal(it.id) }
    if (principal == null) respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHENTICATED", "Authentication is required."))
    else if (principal.mustChangePassword && !request.uri.endsWith("/change-password")) respond(HttpStatusCode.Forbidden, ApiError("PASSWORD_CHANGE_REQUIRED", "Change your password before continuing."))
    return principal
}

private fun setRefreshCookie(call: ApplicationCall, token: String, seconds: Int) {
    call.response.cookies.append("refresh_token", token, maxAge = seconds, httpOnly = true, secure = true, path = "/api/v1/auth", extensions = mapOf("SameSite" to "Strict"))
}

private fun clearRefreshCookie(call: ApplicationCall) {
    call.response.cookies.append("refresh_token", "", maxAge = 0, httpOnly = true, secure = true, path = "/api/v1/auth", extensions = mapOf("SameSite" to "Strict"))
}
