package rw.rutongo.mmems.shared.enums

enum class Role { MECHANIC, SUPERVISOR, MANAGER, GENERAL_MANAGER, ADMINISTRATOR }
enum class OperationalStatus { OPERATIONAL, UNDER_REPAIR, DOWN }
enum class EquipmentCondition { DOWN, UNDER_REPAIR, OPERATIONAL_WITH_DEFECTS, OPERATIONAL, NOT_RECORDED }
enum class InspectionState { NOT_STARTED, IN_PROGRESS, COMPLETED, COMPLETED_WITH_DEFECTS, CRITICAL_FAILURE, MISSING }
enum class InspectionResult { COMPLETED, COMPLETED_WITH_DEFECTS, CRITICAL_FAILURE }
enum class Answer { PASS, FAIL, NA }
enum class Severity { MINOR, MAJOR, CRITICAL }
enum class ReportPeriod { DAY, RED, NIGHT, FULL_DAY }
enum class ReportState { DRAFT, SUBMITTED, RETURNED_FOR_CORRECTION, RESUBMITTED, APPROVED, SUPERSEDED }
enum class ServiceStatus { OK, DUE_SOON, OVERDUE, NO_VERIFIED_SERVICE, NOT_RECORDED }

data class EquipmentId(val value: String)
data class SiteId(val value: String)
