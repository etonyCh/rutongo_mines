@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package rw.rutongo.mmems.modules.inspection.domain

import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.InspectionResult
import rw.rutongo.mmems.shared.enums.Severity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ChecklistCategory { SAFETY, MECHANICAL }

data class ChecklistItem(
    val id: String,
    val code: String,
    val label: String,
    val category: ChecklistCategory,
    val mandatory: Boolean,
    val safetyCritical: Boolean,
    val photoRequiredOnFail: Boolean,
    val naReasonRequired: Boolean,
    val active: Boolean,
)

data class DefectInput(
    val description: String?,
    val severity: Severity,
    val immediateAction: String? = null,
)

data class InspectionAnswerInput(
    val itemId: String,
    val answer: Answer,
    val naReason: String? = null,
    val defect: DefectInput? = null,
    val photoIds: List<String> = emptyList(),
)

data class AttestationInput(
    val name: String,
    val employeeId: String,
    val method: String,
    val fingerprint: String? = null,
    val signature: String? = null,
)

data class InspectionSubmission(
    val clientUuid: String,
    val expectationId: String?,
    val equipmentId: String,
    val shiftId: String,
    val reportDate: LocalDate,
    val operatorId: String? = null,
    val operatorEid: String? = null,
    val supervisorId: String? = null,
    val engineHoursStart: Double? = null,
    val engineHoursEnd: Double? = null,
    val fuelStartPercent: Double? = null,
    val fuelAddedLitres: Double? = null,
    val capturedAt: Instant,
    val startedAt: Instant,
    val attestation: AttestationInput? = null,
    val answers: List<InspectionAnswerInput> = emptyList(),
)

data class InspectionFieldError(
    val field: String,
    val message: String,
)

data class ValidationOutcome(
    val errors: List<InspectionFieldError>,
    val warnings: List<String>,
) {
    val valid: Boolean get() = errors.isEmpty()
}

data class AnswerWithSeverity(
    val answer: Answer,
    val severity: Severity?,
)

enum class WorkingHoursStatus { VALID, MISSING_READING, SUSPICIOUS, OVERLAP, CORRECTED }

data class EngineHoursEvaluation(
    val recordedHours: Double?,
    val status: WorkingHoursStatus,
    val errors: List<InspectionFieldError>,
    val warnings: List<String>,
)

object EngineHours {
    fun evaluate(
        start: Double?,
        end: Double?,
        lastReading: Double?,
    ): EngineHoursEvaluation {
        val errors = mutableListOf<InspectionFieldError>()
        val warnings = mutableListOf<String>()
        if (start == null) errors += InspectionFieldError("engineHoursStart", "Starting engine hours are required.")
        if (start != null && start < 0.0) errors += InspectionFieldError("engineHoursStart", "Starting engine hours cannot be negative.")
        if (end != null && end < 0.0) errors += InspectionFieldError("engineHoursEnd", "Ending engine hours cannot be negative.")
        if (start != null && end != null && end < start) errors += InspectionFieldError("engineHoursEnd", "Ending hours cannot be lower than starting hours.")
        if (start != null && lastReading != null && start < lastReading) warnings += "START_BELOW_LAST_READING"
        if (start != null && end != null && end - start > 24.0) warnings += "WORKING_HOURS_ABOVE_24"
        val recorded = if (start != null && end != null && end >= start) end - start else null
        val status =
            when {
                start == null || end == null -> WorkingHoursStatus.MISSING_READING
                errors.isNotEmpty() -> WorkingHoursStatus.SUSPICIOUS
                "WORKING_HOURS_ABOVE_24" in warnings -> WorkingHoursStatus.SUSPICIOUS
                else -> WorkingHoursStatus.VALID
            }
        return EngineHoursEvaluation(recorded, status, errors, warnings)
    }
}

object InspectionValidator {
    fun validate(
        submission: InspectionSubmission,
        items: List<ChecklistItem>,
        lastReading: Double?,
    ): ValidationOutcome {
        val errors = mutableListOf<InspectionFieldError>()
        val warnings = mutableListOf<String>()
        val answers = submission.answers.associateBy { it.itemId }
        for (item in items.filter { it.mandatory && it.active }) {
            val answer = answers[item.id]
            if (answer == null) {
                errors += InspectionFieldError("answers.${item.code}", "${item.label} must be answered.")
                continue
            }
            when (answer.answer) {
                Answer.FAIL -> {
                    if (answer.defect?.description.isNullOrBlank()) errors += InspectionFieldError("answers.${item.code}.defect", "Describe the defect.")
                    if ((item.safetyCritical || item.photoRequiredOnFail) && answer.photoIds.isEmpty()) {
                        errors += InspectionFieldError("answers.${item.code}.photo", "A photo is required for this failure.")
                    }
                    if (answer.photoIds.any { runCatching { UUID.fromString(it) }.isFailure }) {
                        errors += InspectionFieldError("answers.${item.code}.photo", "Photo references must be valid identifiers.")
                    }
                }
                Answer.NA ->
                    if (item.naReasonRequired && answer.naReason.isNullOrBlank()) {
                        errors += InspectionFieldError("answers.${item.code}.naReason", "Give a reason for N/A.")
                    }
                Answer.PASS -> Unit
            }
        }
        val engineHours = EngineHours.evaluate(submission.engineHoursStart, submission.engineHoursEnd, lastReading)
        errors += engineHours.errors
        warnings += engineHours.warnings
        if (submission.attestation == null) errors += InspectionFieldError("attestation", "Signature is required before submission.")
        return ValidationOutcome(errors, warnings)
    }

    fun result(answers: List<AnswerWithSeverity>): InspectionResult =
        when {
            answers.any { it.answer == Answer.FAIL && it.severity == Severity.CRITICAL } -> InspectionResult.CRITICAL_FAILURE
            answers.any { it.answer == Answer.FAIL } -> InspectionResult.COMPLETED_WITH_DEFECTS
            else -> InspectionResult.COMPLETED
        }
}
