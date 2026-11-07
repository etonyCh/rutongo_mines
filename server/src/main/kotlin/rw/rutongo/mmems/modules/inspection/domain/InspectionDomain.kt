@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package rw.rutongo.mmems.modules.inspection.domain

import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.InspectionResult
import rw.rutongo.mmems.shared.enums.Severity
import rw.rutongo.mmems.shared.inspection.InspectionAnswerRule
import rw.rutongo.mmems.shared.inspection.InspectionChecklistRule
import rw.rutongo.mmems.shared.inspection.InspectionValidationInput
import java.time.Instant
import java.time.LocalDate
import rw.rutongo.mmems.shared.inspection.EngineHours as SharedEngineHours
import rw.rutongo.mmems.shared.inspection.InspectionValidator as SharedInspectionValidator

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
        val evaluation = SharedEngineHours.evaluate(start, end, lastReading)
        return EngineHoursEvaluation(
            evaluation.recordedHours,
            WorkingHoursStatus.valueOf(evaluation.status.name),
            evaluation.errors.map { InspectionFieldError(it.field, it.message) },
            evaluation.warnings,
        )
    }
}

object InspectionValidator {
    fun validate(
        submission: InspectionSubmission,
        items: List<ChecklistItem>,
        lastReading: Double?,
    ): ValidationOutcome {
        val outcome =
            SharedInspectionValidator.validate(
                InspectionValidationInput(
                    answers =
                        submission.answers.map {
                            InspectionAnswerRule(
                                itemId = it.itemId,
                                answer = it.answer,
                                naReason = it.naReason,
                                defectDescription = it.defect?.description,
                                photoIds = it.photoIds,
                            )
                        },
                    checklist =
                        items.map {
                            InspectionChecklistRule(
                                itemId = it.id,
                                code = it.code,
                                label = it.label,
                                mandatory = it.mandatory,
                                safetyCritical = it.safetyCritical,
                                photoRequiredOnFail = it.photoRequiredOnFail,
                                naReasonRequired = it.naReasonRequired,
                                active = it.active,
                            )
                        },
                    engineHoursStart = submission.engineHoursStart,
                    engineHoursEnd = submission.engineHoursEnd,
                    lastReading = lastReading,
                    attestationPresent = submission.attestation != null,
                ),
            )
        return ValidationOutcome(
            outcome.errors.map { InspectionFieldError(it.field, it.message) },
            outcome.warnings,
        )
    }

    fun result(answers: List<AnswerWithSeverity>): InspectionResult =
        when {
            answers.any { it.answer == Answer.FAIL && it.severity == Severity.CRITICAL } -> InspectionResult.CRITICAL_FAILURE
            answers.any { it.answer == Answer.FAIL } -> InspectionResult.COMPLETED_WITH_DEFECTS
            else -> InspectionResult.COMPLETED
        }
}
