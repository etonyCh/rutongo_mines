package rw.rutongo.mmems.shared.enums

enum class Role { MECHANIC, SUPERVISOR, MANAGER, GENERAL_MANAGER, ADMINISTRATOR }

enum class OperationalStatus { OPERATIONAL, UNDER_REPAIR, DOWN }

enum class EquipmentCondition { DOWN, UNDER_REPAIR, OPERATIONAL_WITH_DEFECTS, OPERATIONAL, NOT_RECORDED }

enum class InspectionState { NOT_STARTED, IN_PROGRESS, COMPLETED, COMPLETED_WITH_DEFECTS, CRITICAL_FAILURE, MISSING }

enum class InspectionResult { COMPLETED, COMPLETED_WITH_DEFECTS, CRITICAL_FAILURE }

enum class Answer { PASS, FAIL, NA }

enum class Severity { MINOR, MAJOR, CRITICAL }

enum class BreakdownState { REPORTED, DIAGNOSING, AWAITING_PARTS, UNDER_REPAIR, RESOLVED, CLOSED }

enum class ReportPeriod { DAY, RED, NIGHT, FULL_DAY }

enum class ReportState { DRAFT, SUBMITTED, RETURNED_FOR_CORRECTION, RESUBMITTED, APPROVED, SUPERSEDED }

enum class ServiceStatus { OK, DUE_SOON, OVERDUE, NO_VERIFIED_SERVICE, NOT_RECORDED }

enum class MaintenanceVerificationStatus { DRAFT, PENDING_VERIFICATION, VERIFIED, REJECTED, CORRECTION_REQUESTED }

enum class MaintenanceAction { VERIFY, REJECT, REQUEST_CORRECTION }

enum class WorkingHoursGroupBy { SITE, EQUIPMENT, SHIFT, DAY }

enum class PartRequestState { REQUESTED, APPROVED, REJECTED, ISSUED, USED, RETURNED }

enum class StockMovementType { RECEIPT, ISSUE, RETURN, ADJUSTMENT }

enum class PersonnelType { TECHNICIAN, OPERATOR }

data class EquipmentId(
    val value: String,
)

data class SiteId(
    val value: String,
)
