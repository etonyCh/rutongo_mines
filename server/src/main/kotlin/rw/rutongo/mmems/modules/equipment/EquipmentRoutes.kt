package rw.rutongo.mmems.modules.equipment

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import rw.rutongo.mmems.ApiError
import rw.rutongo.mmems.identity.IdentityService
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.identity.TokenSigner
import rw.rutongo.mmems.identity.authenticated
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Serializable private data class MovementRequest(
    val destinationSiteId: String,
    val destinationTunnelId: String? = null,
    val reason: String,
)

@Serializable private data class CreateSiteRequest(
    val code: String,
    val name: String,
    val timezone: String = "Africa/Kigali",
)

@Serializable private data class UpdateSiteRequest(
    val name: String,
    val timezone: String,
    val state: String,
)

@Serializable private data class CreateTunnelRequest(
    val siteId: String,
    val code: String,
    val name: String,
)

@Serializable private data class UpdateTunnelRequest(
    val code: String,
    val name: String,
    val state: String,
)

@Serializable private data class UpdateShiftRequest(
    val name: String,
)

@Serializable private data class CreateTypeRequest(
    val name: String,
)

@Serializable private data class CreateScheduleRequest(
    val shiftCode: String,
    val siteId: String? = null,
    val startLocal: String,
    val endLocal: String,
    val inspectionWindowMinutes: Int,
    val hardBackstopHours: Int = 24,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
)

@Serializable private data class CreateEquipmentRequest(
    val assetId: String,
    val equipmentCode: String,
    val name: String,
    val typeId: String,
    val siteId: String,
    val tunnelId: String? = null,
    val description: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val manufactureYear: Int? = null,
    val engineHoursCurrent: String? = null,
    val requiresDailyInspection: Boolean = true,
)

@Serializable private data class UpdateEquipmentRequest(
    val equipmentCode: String,
    val name: String,
    val typeId: String,
    val description: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val manufactureYear: Int? = null,
    val engineHoursCurrent: String? = null,
    val requiresDailyInspection: Boolean = true,
    val lifecycleState: String = "ACTIVE",
)

fun Route.equipmentRoutes(
    service: EquipmentService,
    identity: IdentityService,
    signer: TokenSigner,
) {
    route("/api/v1") {
        get("/sites") {
            val user = call.authenticated(identity, signer) ?: return@get
            call.respond(service.sites(user))
        }
        get("/tunnels") {
            val user = call.authenticated(identity, signer) ?: return@get
            val siteId =
                call.request.queryParameters["siteId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                        return@get
                    }
                }
            call.respond(service.tunnels(user, siteId))
        }
        get("/shifts") {
            call.authenticated(identity, signer) ?: return@get
            call.respond(service.shifts())
        }
        get("/shifts/schedules") {
            val user = call.authenticated(identity, signer) ?: return@get
            call.respond(service.schedules(user))
        }
        post("/shifts/schedules") {
            val user = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can configure shifts."))
                return@post
            }
            try {
                val body = call.receive<CreateScheduleRequest>()
                call.respond(HttpStatusCode.Created, service.createSchedule(user, body.shiftCode, body.siteId?.let(UUID::fromString), LocalTime.parse(body.startLocal), LocalTime.parse(body.endLocal), body.inspectionWindowMinutes, body.hardBackstopHours, LocalDate.parse(body.effectiveFrom), body.effectiveTo?.let(LocalDate::parse)))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SHIFT_SCHEDULE", e.message ?: "The shift schedule is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status = if (e.code == "SITE_NOT_AUTHORIZED") HttpStatusCode.Forbidden else HttpStatusCode.NotFound
                call.respond(status, ApiError(e.code, "The shift schedule could not be created."))
            }
        }
        put("/shifts/{code}") {
            val user = call.authenticated(identity, signer) ?: return@put
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can update shifts."))
                return@put
            }
            val expected =
                call.request.headers[HttpHeaders.IfMatch]
                    ?.trim('"')
                    ?.toLongOrNull()
            if (expected == null) {
                call.respond(HttpStatusCode.fromValue(428), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@put
            }
            try {
                call.respond(service.updateShift(user.id, call.parameters["code"] ?: "", expected, call.receive<UpdateShiftRequest>().name))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SHIFT", e.message ?: "The shift is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                call.respond(HttpStatusCode.Conflict, ApiError(e.code, "The shift could not be updated."))
            }
        }
        post("/sites") {
            val user = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can create sites."))
                return@post
            }
            try {
                val body = call.receive<CreateSiteRequest>()
                call.respond(HttpStatusCode.Created, service.createSite(user.id, body.code, body.name, body.timezone))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE", e.message ?: "The site is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                call.respond(HttpStatusCode.Conflict, ApiError(e.code, "The site could not be created."))
            }
        }
        put("/sites/{id}") {
            val user = call.authenticated(identity, signer) ?: return@put
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can update sites."))
                return@put
            }
            val expected =
                call.request.headers[HttpHeaders.IfMatch]
                    ?.trim('"')
                    ?.toLongOrNull()
            if (expected == null) {
                call.respond(HttpStatusCode.fromValue(428), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@put
            }
            try {
                val body = call.receive<UpdateSiteRequest>()
                call.respond(service.updateSite(user, UUID.fromString(call.parameters["id"]), expected, body.name, body.timezone, body.state))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE", e.message ?: "The site is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status =
                    if (e.code == "SITE_NOT_AUTHORIZED") {
                        HttpStatusCode.Forbidden
                    } else if (e.code == "SITE_VERSION_CONFLICT") {
                        HttpStatusCode.Conflict
                    } else {
                        HttpStatusCode.NotFound
                    }
                ; call.respond(status, ApiError(e.code, "The site could not be updated."))
            }
        }
        post("/tunnels") {
            val user = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can create tunnels."))
                return@post
            }
            try {
                val body = call.receive<CreateTunnelRequest>()
                call.respond(HttpStatusCode.Created, service.createTunnel(user, UUID.fromString(body.siteId), body.code, body.name))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TUNNEL", e.message ?: "The tunnel is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status =
                    when (e.code) {
                        "SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
                        "SITE_NOT_FOUND" -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.Conflict
                    }
                call.respond(status, ApiError(e.code, "The tunnel could not be created."))
            }
        }
        put("/tunnels/{id}") {
            val user = call.authenticated(identity, signer) ?: return@put
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can update tunnels."))
                return@put
            }
            val expected =
                call.request.headers[HttpHeaders.IfMatch]
                    ?.trim('"')
                    ?.toLongOrNull()
            if (expected == null) {
                call.respond(HttpStatusCode.fromValue(428), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@put
            }
            try {
                val body = call.receive<UpdateTunnelRequest>()
                call.respond(service.updateTunnel(user, UUID.fromString(call.parameters["id"]), expected, body.code, body.name, body.state))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_TUNNEL", e.message ?: "The tunnel is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status =
                    when (e.code) {
                        "SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
                        "TUNNEL_VERSION_CONFLICT", "TUNNEL_ALREADY_EXISTS" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.NotFound
                    }
                call.respond(status, ApiError(e.code, "The tunnel could not be updated."))
            }
        }
        get("/equipment-types") {
            call.authenticated(identity, signer) ?: return@get
            call.respond(service.equipmentTypes())
        }
        post("/equipment-types") {
            val user = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can manage equipment types."))
                return@post
            }
            try {
                call.respond(HttpStatusCode.Created, service.createEquipmentType(user, call.receive<CreateTypeRequest>().name))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_TYPE", e.message ?: "The equipment type is invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                call.respond(HttpStatusCode.Conflict, ApiError(e.code, "The equipment type already exists."))
            }
        }
        post("/equipment") {
            val user = call.authenticated(identity, signer) ?: return@post
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can create equipment."))
                return@post
            }
            try {
                val body = call.receive<CreateEquipmentRequest>()
                val created = service.createEquipment(user, body.assetId, body.equipmentCode, body.name, UUID.fromString(body.typeId), UUID.fromString(body.siteId), body.tunnelId?.let(UUID::fromString), body.description, body.model, body.serialNumber, body.manufactureYear, body.engineHoursCurrent?.let(::BigDecimal), body.requiresDailyInspection)
                call.respond(HttpStatusCode.Created, created)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT", e.message ?: "The equipment details are invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status =
                    when (e.code) {
                        "SITE_NOT_AUTHORIZED", "FORBIDDEN" -> HttpStatusCode.Forbidden
                        "SITE_NOT_FOUND", "TUNNEL_NOT_IN_SITE", "EQUIPMENT_TYPE_NOT_FOUND" -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.Conflict
                    }
                call.respond(status, ApiError(e.code, "The equipment could not be created."))
            }
        }
        put("/equipment/{id}") {
            val user = call.authenticated(identity, signer) ?: return@put
            if (!RolePermissions.allows(user.role, "CONFIG_MANAGE")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Only an Administrator can update equipment."))
                return@put
            }
            val expected =
                call.request.headers[HttpHeaders.IfMatch]
                    ?.trim('"')
                    ?.toLongOrNull()
            if (expected == null) {
                call.respond(HttpStatusCode.fromValue(428), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@put
            }
            try {
                val id = UUID.fromString(call.parameters["id"])
                val body = call.receive<UpdateEquipmentRequest>()
                call.respond(service.updateEquipment(user, id, expected, body.equipmentCode, body.name, UUID.fromString(body.typeId), body.description, body.model, body.serialNumber, body.manufactureYear, body.engineHoursCurrent?.let(::BigDecimal), body.requiresDailyInspection, body.lifecycleState))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT", e.message ?: "The equipment details are invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status =
                    when (e.code) {
                        "SITE_NOT_AUTHORIZED", "FORBIDDEN" -> HttpStatusCode.Forbidden
                        "EQUIPMENT_NOT_FOUND", "EQUIPMENT_TYPE_NOT_FOUND" -> HttpStatusCode.NotFound
                        "EQUIPMENT_VERSION_CONFLICT" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.BadRequest
                    }
                call.respond(status, ApiError(e.code, "The equipment could not be updated."))
            }
        }
        get("/equipment") {
            val user = call.authenticated(identity, signer) ?: return@get
            if (!RolePermissions.allows(user.role, "EQUIPMENT_VIEW")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to view equipment."))
                return@get
            }
            val siteId =
                call.request.queryParameters["siteId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_SITE_ID", "The site ID is invalid."))
                        return@get
                    }
                }
            call.respond(service.equipment(user, siteId, call.request.queryParameters["q"], call.request.queryParameters["limit"]?.toIntOrNull() ?: 100))
        }
        get("/equipment/{id}") {
            val user = call.authenticated(identity, signer) ?: return@get
            if (!RolePermissions.allows(user.role, "EQUIPMENT_VIEW")) {
                call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "You are not allowed to view equipment."))
                return@get
            }
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_EQUIPMENT_ID", "The equipment ID is invalid."))
                return@get
            }
            val equipment = service.equipment(user, id)
            if (equipment == null) call.respond(HttpStatusCode.NotFound, ApiError("EQUIPMENT_NOT_FOUND", "The equipment was not found.")) else call.respond(equipment)
        }
        post("/equipment/{id}/move") {
            val user = call.authenticated(identity, signer) ?: return@post
            val expected =
                call.request.headers[HttpHeaders.IfMatch]
                    ?.trim('"')
                    ?.toLongOrNull()
            if (expected == null) {
                call.respond(HttpStatusCode.fromValue(428), ApiError("IF_MATCH_REQUIRED", "A valid If-Match version is required."))
                return@post
            }
            try {
                val id = UUID.fromString(call.parameters["id"])
                val body = call.receive<MovementRequest>()
                val moved = service.move(user, id, UUID.fromString(body.destinationSiteId), body.destinationTunnelId?.let(UUID::fromString), body.reason, expected)
                call.respond(moved)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_MOVEMENT", e.message ?: "The movement details are invalid."))
            } catch (e: rw.rutongo.mmems.identity.AuthFailure) {
                val status =
                    when (e.code) {
                        "FORBIDDEN", "SOURCE_SITE_NOT_AUTHORIZED", "DESTINATION_SITE_NOT_AUTHORIZED" -> HttpStatusCode.Forbidden
                        "EQUIPMENT_NOT_FOUND", "SITE_NOT_FOUND", "TUNNEL_NOT_IN_SITE" -> HttpStatusCode.NotFound
                        "EQUIPMENT_VERSION_CONFLICT" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.BadRequest
                    }
                call.respond(status, ApiError(e.code, "The equipment could not be moved."))
            }
        }
    }
}
