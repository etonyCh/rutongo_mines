package rw.rutongo.mmems.shared.inspection

import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.InspectionResult
import rw.rutongo.mmems.shared.enums.Severity

data class InspectionChecklistRule(
    val itemId: String,
    val code: String,
    val label: String,
    val mandatory: Boolean,
    val safetyCritical: Boolean,
    val photoRequiredOnFail: Boolean,
    val naReasonRequired: Boolean,
    val active: Boolean = true,
)

data class InspectionAnswerRule(
    val itemId: String,
    val answer: Answer,
    val naReason: String? = null,
    val defectDescription: String? = null,
    val photoIds: List<String> = emptyList(),
)

data class InspectionValidationInput(
    val answers: List<InspectionAnswerRule>,
    val checklist: List<InspectionChecklistRule>,
    val engineHoursStart: Double?,
    val engineHoursEnd: Double?,
    val lastReading: Double?,
    val attestationPresent: Boolean,
)

data class InspectionValidationError(
    val field: String,
    val message: String,
)

data class InspectionValidationOutcome(
    val errors: List<InspectionValidationError>,
    val warnings: List<String>,
) {
    val valid: Boolean get() = errors.isEmpty()
}

data class EngineHoursEvaluation(
    val recordedHours: Double?,
    val status: WorkingHoursStatus,
    val errors: List<InspectionValidationError>,
    val warnings: List<String>,
)

enum class WorkingHoursStatus { VALID, MISSING_READING, SUSPICIOUS, OVERLAP, CORRECTED }

data class AnswerWithSeverity(
    val answer: Answer,
    val severity: Severity?,
)

object EngineHours {
    fun evaluate(
        start: Double?,
        end: Double?,
        lastReading: Double?,
    ): EngineHoursEvaluation {
        val errors = mutableListOf<InspectionValidationError>()
        val warnings = mutableListOf<String>()
        if (start == null) errors += InspectionValidationError("engineHoursStart", "Starting engine hours are required.")
        if (start != null && start < 0.0) errors += InspectionValidationError("engineHoursStart", "Starting engine hours cannot be negative.")
        if (end != null && end < 0.0) errors += InspectionValidationError("engineHoursEnd", "Ending engine hours cannot be negative.")
        if (start != null && end != null && end < start) errors += InspectionValidationError("engineHoursEnd", "Ending hours cannot be lower than starting hours.")
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
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun validate(input: InspectionValidationInput): InspectionValidationOutcome {
        val errors = mutableListOf<InspectionValidationError>()
        val warnings = mutableListOf<String>()
        val answers = input.answers.associateBy { it.itemId }
        for (item in input.checklist.filter { it.mandatory && it.active }) {
            val answer = answers[item.itemId]
            if (answer == null) {
                errors += InspectionValidationError("answers.${item.code}", "${item.label} must be answered.")
                continue
            }
            when (answer.answer) {
                Answer.FAIL -> {
                    if (answer.defectDescription.isNullOrBlank()) {
                        errors += InspectionValidationError("answers.${item.code}.defect", "Describe the defect.")
                    }
                    if ((item.safetyCritical || item.photoRequiredOnFail) && answer.photoIds.isEmpty()) {
                        errors += InspectionValidationError("answers.${item.code}.photo", "A photo is required for this failure.")
                    }
                    if (answer.photoIds.any { !uuidPattern.matches(it) }) {
                        errors += InspectionValidationError("answers.${item.code}.photo", "Photo references must be valid identifiers.")
                    }
                }
                Answer.NA ->
                    if (item.naReasonRequired && answer.naReason.isNullOrBlank()) {
                        errors += InspectionValidationError("answers.${item.code}.naReason", "Give a reason for N/A.")
                    }
                Answer.PASS -> Unit
            }
        }
        val engineHours = EngineHours.evaluate(input.engineHoursStart, input.engineHoursEnd, input.lastReading)
        errors += engineHours.errors
        warnings += engineHours.warnings
        if (!input.attestationPresent) errors += InspectionValidationError("attestation", "Signature is required before submission.")
        return InspectionValidationOutcome(errors, warnings)
    }

    fun result(answers: List<AnswerWithSeverity>): InspectionResult =
        when {
            answers.any { it.answer == Answer.FAIL && it.severity == Severity.CRITICAL } -> InspectionResult.CRITICAL_FAILURE
            answers.any { it.answer == Answer.FAIL } -> InspectionResult.COMPLETED_WITH_DEFECTS
            else -> InspectionResult.COMPLETED
        }
}
