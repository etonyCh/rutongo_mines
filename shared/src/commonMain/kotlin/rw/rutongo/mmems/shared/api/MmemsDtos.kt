package rw.rutongo.mmems.shared.api

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.Severity

@Serializable data class LoginRequest(
    val username: String,
    val password: String,
    val clientType: String = "ANDROID",
    val deviceLabel: String? = null,
)

@Serializable data class SessionResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val mustChangePassword: Boolean = false,
)

@Serializable data class RefreshRequest(
    val refreshToken: String? = null,
    val clientType: String = "ANDROID",
    val deviceLabel: String? = null,
)

@Serializable data class ApiErrorView(
    val code: String,
    val message: String,
)

@Serializable data class MeView(
    val id: String,
    val username: String,
    val fullName: String,
    val employeeId: String,
    val role: Role,
    val siteIds: List<String> = emptyList(),
    val mustChangePassword: Boolean = false,
    val active: Boolean = true,
    val version: Long = 0,
)

@Serializable data class SiteDto(
    val id: String,
    val code: String,
    val name: String,
    val state: String,
    val timezone: String,
    val version: Long,
)

@Serializable data class EquipmentDto(
    val id: String,
    val assetId: String,
    val equipmentCode: String,
    val name: String,
    val type: String,
    val siteId: String,
    val siteName: String,
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val lifecycleState: String,
    val operationalStatus: String,
    val engineHours: Double? = null,
    val version: Long,
)

@Serializable data class ShiftDto(
    val id: String,
    val code: String,
    val name: String,
    val version: Long,
)

@Serializable data class ChecklistItemDto(
    val id: String,
    val category: String,
    val code: String,
    val label: String,
    val sortOrder: Int,
    val mandatory: Boolean,
    val safetyCritical: Boolean,
    val photoRequiredOnFail: Boolean,
    val naReasonRequired: Boolean,
    val active: Boolean,
)

@Serializable data class ExpectedInspectionDto(
    val id: String,
    val equipmentId: String,
    val equipmentName: String,
    val equipmentCode: String,
    val siteId: String,
    val siteName: String,
    val shiftId: String,
    val shiftCode: String,
    val reportDate: String,
    val windowStart: String,
    val windowEnd: String,
    val state: String,
    val missingMarkedAt: String? = null,
)

@Serializable data class DefectDto(
    val description: String? = null,
    val severity: Severity,
    val immediateAction: String? = null,
)

@Serializable data class AnswerDto(
    val itemId: String,
    val answer: Answer,
    val naReason: String? = null,
    val defect: DefectDto? = null,
    val photoIds: List<String> = emptyList(),
)

@Serializable data class AttestationDto(
    val name: String,
    val employeeId: String,
    val method: String,
    val fingerprint: String? = null,
    val signature: String? = null,
)

@Serializable data class SubmitInspectionDto(
    val clientUuid: String,
    val expectationId: String? = null,
    val equipmentId: String,
    val shiftId: String,
    val reportDate: String,
    val operatorId: String? = null,
    val operatorEid: String? = null,
    val supervisorId: String? = null,
    val engineHoursStart: Double? = null,
    val engineHoursEnd: Double? = null,
    val fuelStartPercent: Double? = null,
    val fuelAddedLitres: Double? = null,
    val capturedAt: String,
    val startedAt: String,
    val attestation: AttestationDto? = null,
    val answers: List<AnswerDto> = emptyList(),
)

@Serializable data class StatusUpdateDto(
    val status: String,
    val problem: String? = null,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val remarks: String? = null,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val occurredAt: String,
    val overrideReason: String? = null,
)

@Serializable data class PhotoDto(
    val id: String,
    val mime: String,
    val sizeBytes: Int,
    val sha256: String,
    val siteId: String,
    val targetType: String,
    val targetId: String,
    val kind: String,
    val uploadedAt: String,
)

@Serializable data class InspectionDetailDto(
    val id: String,
    val clientUuid: String,
    val state: String,
    val result: String,
    val submittedLate: Boolean,
    val submittedAt: String,
)

@Serializable data class StatusEventDto(
    val id: String,
    val equipmentId: String,
    val status: String,
    val occurredAt: String,
    val recordedAt: String,
    val version: Long,
)
