package rw.rutongo.mmems.modules.status.domain

import rw.rutongo.mmems.shared.enums.OperationalStatus

 data class StatusChangeInput(
    val newStatus: OperationalStatus,
    val problem: String?,
    val actionTaken: String?,
)

data class FieldError(val field: String, val message: String)

enum class EquipmentCondition { DOWN, UNDER_REPAIR, OPERATIONAL_WITH_DEFECTS, OPERATIONAL, NOT_RECORDED }

object StatusPolicy {
    fun validate(change: StatusChangeInput): List<FieldError> = buildList {
        if (change.newStatus == OperationalStatus.DOWN || change.newStatus == OperationalStatus.UNDER_REPAIR) {
            if (change.problem.isNullOrBlank()) add(FieldError("problem", "A problem description is required for this status."))
        }
        if (change.newStatus == OperationalStatus.UNDER_REPAIR && change.actionTaken.isNullOrBlank()) {
            add(FieldError("actionTaken", "Repair information is required while equipment is under repair."))
        }
    }

    fun condition(status: OperationalStatus?, openDefects: Int): EquipmentCondition = when (status) {
        null -> EquipmentCondition.NOT_RECORDED
        OperationalStatus.DOWN -> EquipmentCondition.DOWN
        OperationalStatus.UNDER_REPAIR -> EquipmentCondition.UNDER_REPAIR
        OperationalStatus.OPERATIONAL -> if (openDefects > 0) EquipmentCondition.OPERATIONAL_WITH_DEFECTS else EquipmentCondition.OPERATIONAL
    }
}
