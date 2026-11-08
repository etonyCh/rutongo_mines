package rw.rutongo.mmems.shared.api

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.BreakdownState
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.ServiceStatus
import rw.rutongo.mmems.shared.enums.Severity
import rw.rutongo.mmems.shared.enums.WorkingHoursGroupBy

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

@Serializable data class BreakdownCreateDto(
    val equipmentId: String,
    val category: String,
    val problem: String,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val reportedAt: String? = null,
    val breakdownStart: String? = null,
    val assignedTechnicianId: String? = null,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val remarks: String? = null,
    val defectIds: List<String> = emptyList(),
)

@Serializable data class BreakdownTransitionDto(
    val state: BreakdownState,
    val repairStart: String? = null,
    val repairEnd: String? = null,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val assignedTechnicianId: String? = null,
    val remarks: String? = null,
)

@Serializable data class BreakdownViewDto(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String? = null,
    val category: String,
    val problem: String,
    val siteId: String,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val reportedBy: String,
    val reportedAt: String,
    val breakdownStart: String,
    val repairStart: String? = null,
    val repairEnd: String? = null,
    val state: String,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val assignedTechnicianId: String? = null,
    val remarks: String? = null,
    val defectIds: List<String> = emptyList(),
    val photoIds: List<String> = emptyList(),
    val version: Long,
)

@Serializable data class MaintenanceCreateDto(
    val equipmentId: String,
    val serviceTypeId: String,
    val tunnelId: String? = null,
    val performedAt: String? = null,
    val engineHours: Double? = null,
    val workPerformed: String,
    val cause: String? = null,
    val technicianId: String,
    val supervisorId: String? = null,
    val breakdownId: String? = null,
    val replacedComponents: List<String> = emptyList(),
    val downtimeMinutes: Int? = null,
    val labourCost: Long = 0,
    val partsCost: Long = 0,
    val currency: String = "RWF",
    val notes: String? = null,
)

@Serializable data class MaintenanceActionDto(
    val comment: String? = null,
)

@Serializable data class MaintenanceViewDto(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String,
    val equipmentName: String,
    val serviceTypeId: String,
    val serviceTypeCode: String,
    val serviceTypeName: String,
    val siteId: String,
    val siteName: String,
    val tunnelId: String? = null,
    val performedAt: String,
    val engineHours: Double? = null,
    val workPerformed: String,
    val cause: String? = null,
    val technicianId: String,
    val technicianName: String,
    val supervisorId: String? = null,
    val breakdownId: String? = null,
    val replacedComponents: List<String> = emptyList(),
    val downtimeMinutes: Int? = null,
    val labourCost: Long = 0,
    val partsCost: Long = 0,
    val totalCost: Long = 0,
    val currency: String,
    val verificationStatus: MaintenanceVerificationStatus,
    val verifiedBy: String? = null,
    val verifiedAt: String? = null,
    val verificationComment: String? = null,
    val notes: String? = null,
    val version: Long,
)

@Serializable data class ServiceScheduleViewDto(
    val intervalId: String,
    val equipmentId: String,
    val equipmentCode: String,
    val equipmentName: String,
    val siteId: String,
    val serviceTypeId: String,
    val serviceTypeCode: String,
    val serviceTypeName: String,
    val intervalHours: Int,
    val dueSoonThresholdHours: Int,
    val current: Double? = null,
    val lastVerified: Double? = null,
    val lastVerifiedAt: String? = null,
    val nextDue: Double? = null,
    val remaining: Double? = null,
    val status: ServiceStatus,
)

@Serializable data class WorkingHoursEntryDto(
    val inspectionId: String,
    val equipmentId: String,
    val equipmentCode: String,
    val equipmentName: String,
    val siteId: String,
    val siteName: String,
    val shiftId: String,
    val shiftCode: String,
    val reportDate: String,
    val hoursStart: Double? = null,
    val hoursEnd: Double? = null,
    val recordedHours: Double? = null,
    val status: String,
)

@Serializable data class WorkingHoursGroupDto(
    val key: String,
    val siteId: String? = null,
    val siteName: String? = null,
    val equipmentId: String? = null,
    val equipmentCode: String? = null,
    val equipmentName: String? = null,
    val shiftId: String? = null,
    val shiftCode: String? = null,
    val reportDate: String? = null,
    val recordedHours: Double,
    val entryCount: Int,
    val overlapEntries: Int = 0,
    val missingReadings: Int = 0,
)

@Serializable data class WorkingHoursResponseDto(
    val groupBy: WorkingHoursGroupBy,
    val entries: List<WorkingHoursEntryDto>,
    val groups: List<WorkingHoursGroupDto>,
    val totalRecordedHours: Double,
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
