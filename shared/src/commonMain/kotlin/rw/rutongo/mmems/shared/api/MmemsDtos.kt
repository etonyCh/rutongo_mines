package rw.rutongo.mmems.shared.api

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.BreakdownState
import rw.rutongo.mmems.shared.enums.EquipmentCondition
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.PartRequestState
import rw.rutongo.mmems.shared.enums.PersonnelType
import rw.rutongo.mmems.shared.enums.ReportPeriod
import rw.rutongo.mmems.shared.enums.ReportState
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.ServiceStatus
import rw.rutongo.mmems.shared.enums.Severity
import rw.rutongo.mmems.shared.enums.StockMovementType
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
    val labourCost: Long? = null,
    val partsCost: Long? = null,
    val totalCost: Long? = null,
    val currency: String,
    val verificationStatus: MaintenanceVerificationStatus,
    val verifiedBy: String? = null,
    val verifiedAt: String? = null,
    val verificationComment: String? = null,
    val notes: String? = null,
    val version: Long,
)

@Serializable data class ServiceTypeViewDto(
    val id: String,
    val code: String,
    val name: String,
    val hoursBased: Boolean,
    val satisfiesTypeIds: List<String> = emptyList(),
    val version: Long,
)

@Serializable data class ServiceTypeCreateDto(
    val code: String,
    val name: String,
    val hoursBased: Boolean = true,
    val satisfiesTypeIds: List<String> = emptyList(),
)

@Serializable data class ServiceTypeUpdateDto(
    val name: String,
    val hoursBased: Boolean = true,
    val satisfiesTypeIds: List<String> = emptyList(),
)

@Serializable data class ServiceIntervalViewDto(
    val id: String,
    val serviceTypeId: String,
    val serviceTypeCode: String,
    val serviceTypeName: String,
    val equipmentTypeId: String? = null,
    val equipmentTypeName: String? = null,
    val equipmentId: String? = null,
    val equipmentCode: String? = null,
    val equipmentName: String? = null,
    val intervalHours: Int,
    val dueSoonThresholdHours: Int,
    val version: Long,
)

@Serializable data class ServiceIntervalCreateDto(
    val serviceTypeId: String,
    val equipmentTypeId: String? = null,
    val equipmentId: String? = null,
    val intervalHours: Int,
    val dueSoonThresholdHours: Int = 50,
)

@Serializable data class ServiceIntervalUpdateDto(
    val intervalHours: Int,
    val dueSoonThresholdHours: Int = 50,
)

@Serializable data class MaintenanceTechnicianDto(
    val id: String,
    val employeeId: String,
    val name: String,
    val siteId: String? = null,
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

@Serializable data class PartStockViewDto(
    val location: String,
    val onHand: Int,
)

@Serializable data class PartViewDto(
    val id: String,
    val partNumber: String,
    val description: String,
    val category: String? = null,
    val unit: String,
    val minStock: Int,
    val unitCost: Long? = null,
    val active: Boolean,
    val onHand: Int,
    val lowStock: Boolean,
    val locations: List<PartStockViewDto> = emptyList(),
    val version: Long,
)

@Serializable data class PartCreateDto(
    val partNumber: String,
    val description: String,
    val category: String? = null,
    val unit: String = "UNIT",
    val minStock: Int = 0,
    val unitCost: Long? = null,
)

@Serializable data class PartUpdateDto(
    val description: String,
    val category: String? = null,
    val unit: String = "UNIT",
    val minStock: Int = 0,
    val unitCost: Long? = null,
    val active: Boolean = true,
)

@Serializable data class StockMovementCreateDto(
    val partId: String,
    val location: String = "MAIN",
    val quantity: Int,
    val type: StockMovementType,
    val reason: String? = null,
)

@Serializable data class StockMovementViewDto(
    val id: String,
    val partId: String,
    val partNumber: String,
    val location: String,
    val quantity: Int,
    val type: StockMovementType,
    val requestId: String? = null,
    val userId: String,
    val occurredAt: String,
    val reason: String? = null,
)

@Serializable data class PartRequestLineCreateDto(
    val partId: String,
    val quantity: Int,
    val location: String = "MAIN",
    val notes: String? = null,
)

@Serializable data class PartRequestCreateDto(
    val siteId: String,
    val equipmentId: String? = null,
    val breakdownId: String? = null,
    val maintenanceId: String? = null,
    val notes: String? = null,
    val lines: List<PartRequestLineCreateDto>,
)

@Serializable data class PartRequestLineQuantityDto(
    val lineId: String,
    val quantity: Int,
)

@Serializable data class PartRequestDecisionDto(
    val comment: String? = null,
)

@Serializable data class PartRequestQuantityActionDto(
    val lines: List<PartRequestLineQuantityDto> = emptyList(),
)

@Serializable data class PartRequestLineViewDto(
    val id: String,
    val partId: String,
    val partNumber: String,
    val description: String,
    val unit: String,
    val location: String,
    val requestedQty: Int,
    val issuedQty: Int,
    val usedQty: Int,
    val returnedQty: Int,
    val unitCost: Long? = null,
    val lineCost: Long? = null,
    val notes: String? = null,
)

@Serializable data class PartRequestViewDto(
    val id: String,
    val state: PartRequestState,
    val siteId: String,
    val siteName: String,
    val equipmentId: String? = null,
    val equipmentCode: String? = null,
    val breakdownId: String? = null,
    val maintenanceId: String? = null,
    val requestedBy: String,
    val requestedAt: String,
    val approvedBy: String? = null,
    val approvedAt: String? = null,
    val decisionComment: String? = null,
    val issuedBy: String? = null,
    val issuedAt: String? = null,
    val usedBy: String? = null,
    val usedAt: String? = null,
    val returnedBy: String? = null,
    val returnedAt: String? = null,
    val notes: String? = null,
    val totalCost: Long? = null,
    val version: Long,
    val lines: List<PartRequestLineViewDto> = emptyList(),
)

@Serializable data class TechnicianViewDto(
    val id: String,
    val eid: String,
    val name: String,
    val function: String? = null,
    val phone: String? = null,
    val siteId: String? = null,
    val siteName: String? = null,
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val shiftId: String? = null,
    val shiftCode: String? = null,
    val active: Boolean,
    val version: Long,
)

@Serializable data class TechnicianCreateDto(
    val eid: String,
    val name: String,
    val function: String? = null,
    val phone: String? = null,
    val siteId: String? = null,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val active: Boolean = true,
)

@Serializable data class TechnicianUpdateDto(
    val name: String,
    val function: String? = null,
    val phone: String? = null,
    val siteId: String? = null,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val active: Boolean = true,
)

@Serializable data class OperatorViewDto(
    val id: String,
    val employeeId: String,
    val name: String,
    val siteId: String? = null,
    val siteName: String? = null,
    val tunnelId: String? = null,
    val tunnelName: String? = null,
    val shiftId: String? = null,
    val shiftCode: String? = null,
    val active: Boolean,
    val version: Long,
)

@Serializable data class OperatorCreateDto(
    val employeeId: String,
    val name: String,
    val siteId: String? = null,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val active: Boolean = true,
)

@Serializable data class OperatorUpdateDto(
    val name: String,
    val siteId: String? = null,
    val tunnelId: String? = null,
    val shiftId: String? = null,
    val active: Boolean = true,
)

@Serializable data class AssignmentCreateDto(
    val personType: PersonnelType,
    val personId: String,
    val equipmentId: String,
    val assignedFrom: String? = null,
    val reason: String? = null,
)

@Serializable data class AssignmentViewDto(
    val id: String,
    val personType: PersonnelType,
    val personId: String,
    val equipmentId: String,
    val assignedFrom: String,
    val assignedTo: String? = null,
    val assignedBy: String,
    val reason: String? = null,
)

@Serializable data class PersonnelTransferCreateDto(
    val personType: PersonnelType,
    val personId: String,
    val toSiteId: String,
    val toTunnelId: String? = null,
    val toShiftId: String? = null,
    val effectiveAt: String? = null,
    val reason: String,
)

@Serializable data class PersonnelTransferViewDto(
    val id: String,
    val personType: PersonnelType,
    val personId: String,
    val fromSiteId: String? = null,
    val fromTunnelId: String? = null,
    val fromShiftId: String? = null,
    val toSiteId: String,
    val toTunnelId: String? = null,
    val toShiftId: String? = null,
    val transferredAt: String,
    val transferredBy: String,
    val reason: String,
)

@Serializable data class ReportNarrativeDto(
    val executiveSummary: String? = null,
    val safetyObservations: String? = null,
    val maintenanceSummary: String? = null,
    val partsSummary: String? = null,
    val recommendations: String? = null,
    val remarks: String? = null,
)

@Serializable data class ReportCreateDto(
    val siteId: String,
    val reportDate: String,
    val period: ReportPeriod,
    val narrative: ReportNarrativeDto = ReportNarrativeDto(),
)

@Serializable data class ReportNarrativeUpdateDto(
    val narrative: ReportNarrativeDto = ReportNarrativeDto(),
)

@Serializable data class ReportActionDto(
    val comment: String? = null,
    val reason: String? = null,
)

@Serializable data class ReportSummaryDto(
    val operational: Int = 0,
    val underRepair: Int = 0,
    val down: Int = 0,
    val operationalWithDefects: Int = 0,
    val notRecorded: Int = 0,
    val totalEquipment: Int = 0,
    val inspectionsCompleted: Int = 0,
    val inspectionsMissing: Int = 0,
    val breakdownsReported: Int = 0,
    val breakdownsOpen: Int = 0,
    val repairsCompleted: Int = 0,
    val repairsInProgress: Int = 0,
    val downtimeMinutes: Int = 0,
    val maintenanceRecorded: Int = 0,
    val maintenanceDue: Int = 0,
    val maintenanceOverdue: Int = 0,
    val pendingPartLines: Int = 0,
)

@Serializable data class ReportDefectDto(
    val id: String,
    val description: String,
    val severity: String,
    val state: String,
    val reportedAt: String,
)

@Serializable data class ReportMissingInspectionDto(
    val equipmentId: String,
    val equipmentCode: String,
    val shiftCode: String,
    val windowStart: String,
    val windowEnd: String,
)

@Serializable data class ReportBreakdownDto(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String,
    val category: String,
    val problem: String,
    val state: String,
    val reportedAt: String,
    val breakdownStart: String,
    val repairStart: String? = null,
    val repairEnd: String? = null,
    val actionTaken: String? = null,
    val partsRequired: String? = null,
    val assignedTechnicianName: String? = null,
    val downtimeMinutes: Int? = null,
)

@Serializable data class ReportInspectionDto(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String,
    val shiftCode: String,
    val result: String,
    val inspectorName: String? = null,
    val inspectorEid: String,
    val submittedAt: String,
    val engineHoursStart: Double? = null,
    val engineHoursEnd: Double? = null,
)

@Serializable data class ReportMaintenanceDto(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String,
    val serviceTypeName: String,
    val workPerformed: String,
    val performedAt: String,
    val technicianName: String? = null,
    val verificationStatus: String,
    val engineHours: Double? = null,
)

@Serializable data class ReportPartLineDto(
    val requestId: String,
    val partNumber: String,
    val description: String,
    val unit: String,
    val equipmentCode: String? = null,
    val state: String,
    val requestedQty: Int,
    val issuedQty: Int,
    val usedQty: Int,
    val returnedQty: Int,
    val requestedAt: String,
)

@Serializable data class ReportEquipmentDto(
    val id: String,
    val assetId: String,
    val equipmentCode: String,
    val name: String,
    val typeName: String,
    val tunnelName: String? = null,
    val shiftCode: String? = null,
    val operationalStatus: String? = null,
    val condition: EquipmentCondition,
    val defects: List<ReportDefectDto> = emptyList(),
    val missingInspections: List<ReportMissingInspectionDto> = emptyList(),
    val openBreakdown: ReportBreakdownDto? = null,
    val lastInspectionAt: String? = null,
    val lastInspectionResult: String? = null,
    val lastMaintenanceAt: String? = null,
)

@Serializable data class DailyReportSnapshotDto(
    val siteId: String,
    val siteName: String,
    val reportDate: String,
    val period: ReportPeriod,
    val dataCutoffAt: String,
    val supervisorName: String? = null,
    val supervisorEid: String? = null,
    val technicians: List<String> = emptyList(),
    val summary: ReportSummaryDto = ReportSummaryDto(),
    val equipment: List<ReportEquipmentDto> = emptyList(),
    val inspections: List<ReportInspectionDto> = emptyList(),
    val breakdowns: List<ReportBreakdownDto> = emptyList(),
    val maintenance: List<ReportMaintenanceDto> = emptyList(),
    val partsRequired: List<ReportPartLineDto> = emptyList(),
    val outstanding: List<String> = emptyList(),
)

@Serializable data class ReportViewDto(
    val id: String,
    val reportRef: String,
    val siteId: String,
    val siteName: String,
    val reportDate: String,
    val period: ReportPeriod,
    val versionId: String,
    val versionNo: Int,
    val state: ReportState,
    val preparedBy: String,
    val preparedByName: String,
    val createdAt: String,
    val updatedAt: String,
    val dataCutoffAt: String,
    val submittedAt: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: String? = null,
    val approvalComment: String? = null,
    val returnedBy: String? = null,
    val returnedByName: String? = null,
    val returnedAt: String? = null,
    val correctionReason: String? = null,
    val pdfSha256: String? = null,
    val narrative: ReportNarrativeDto = ReportNarrativeDto(),
    val snapshot: DailyReportSnapshotDto,
)
