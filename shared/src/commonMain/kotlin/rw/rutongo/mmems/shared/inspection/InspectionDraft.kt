package rw.rutongo.mmems.shared.inspection

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.shared.api.AnswerDto
import rw.rutongo.mmems.shared.api.AttestationDto
import rw.rutongo.mmems.shared.api.DefectDto
import rw.rutongo.mmems.shared.api.SubmitInspectionDto
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.Severity

@Serializable enum class WizardStep {
    CONTEXT,
    START_HOURS,
    SAFETY,
    MECHANICAL,
    DEFECTS,
    PHOTOS,
    END_HOURS,
    REVIEW,
    SIGN,
}

@Serializable data class DraftAnswer(
    val itemId: String,
    val code: String,
    val label: String,
    val category: String,
    val mandatory: Boolean,
    val safetyCritical: Boolean,
    val photoRequiredOnFail: Boolean,
    val naReasonRequired: Boolean,
    val answer: Answer? = null,
    val naReason: String? = null,
    val defectDescription: String? = null,
    val severity: Severity? = null,
    val immediateAction: String? = null,
    val photoIds: List<String> = emptyList(),
)

@Serializable data class InspectionDraft(
    val clientUuid: String,
    val expectationId: String? = null,
    val equipmentId: String,
    val equipmentName: String,
    val equipmentCode: String,
    val siteId: String,
    val siteName: String,
    val shiftId: String,
    val shiftCode: String,
    val reportDate: String,
    val operatorName: String = "",
    val operatorEid: String = "",
    val supervisorId: String? = null,
    val engineHoursStart: Double? = null,
    val engineHoursEnd: Double? = null,
    val fuelStartPercent: Double? = null,
    val fuelAddedLitres: Double? = null,
    val capturedAt: String,
    val startedAt: String,
    val step: WizardStep = WizardStep.CONTEXT,
    val answers: List<DraftAnswer> = emptyList(),
    val attestation: AttestationDto? = null,
    val lastEngineHours: Double? = null,
)

data class DraftValidation(
    val errors: Map<String, String>,
    val warnings: List<String>,
    val hoursStatus: WorkingHoursStatus,
) {
    val valid: Boolean get() = errors.isEmpty()
}

object DraftValidator {
    fun validate(draft: InspectionDraft): DraftValidation {
        val checklist =
            draft.answers.map {
                InspectionChecklistRule(
                    itemId = it.itemId,
                    code = it.code,
                    label = it.label,
                    mandatory = it.mandatory,
                    safetyCritical = it.safetyCritical,
                    photoRequiredOnFail = it.photoRequiredOnFail,
                    naReasonRequired = it.naReasonRequired,
                )
            }
        val answers =
            draft.answers.mapNotNull { item ->
                item.answer?.let {
                    InspectionAnswerRule(
                        itemId = item.itemId,
                        answer = it,
                        naReason = item.naReason,
                        defectDescription = item.defectDescription,
                        photoIds = item.photoIds,
                    )
                }
            }
        val outcome =
            InspectionValidator.validate(
                InspectionValidationInput(
                    answers = answers,
                    checklist = checklist,
                    engineHoursStart = draft.engineHoursStart,
                    engineHoursEnd = draft.engineHoursEnd,
                    lastReading = draft.lastEngineHours,
                    attestationPresent = draft.attestation != null,
                ),
            )
        val hours = EngineHours.evaluate(draft.engineHoursStart, draft.engineHoursEnd, draft.lastEngineHours)
        return DraftValidation(outcome.errors.associate { it.field to it.message }, outcome.warnings, hours.status)
    }

    fun toSubmission(draft: InspectionDraft): SubmitInspectionDto =
        SubmitInspectionDto(
            clientUuid = draft.clientUuid,
            expectationId = draft.expectationId,
            equipmentId = draft.equipmentId,
            shiftId = draft.shiftId,
            reportDate = draft.reportDate,
            operatorEid = draft.operatorEid.ifBlank { null },
            supervisorId = draft.supervisorId,
            engineHoursStart = draft.engineHoursStart,
            engineHoursEnd = draft.engineHoursEnd,
            fuelStartPercent = draft.fuelStartPercent,
            fuelAddedLitres = draft.fuelAddedLitres,
            capturedAt = draft.capturedAt,
            startedAt = draft.startedAt,
            attestation = draft.attestation,
            answers =
                draft.answers.mapNotNull { item ->
                    item.answer?.let { answer ->
                        AnswerDto(
                            itemId = item.itemId,
                            answer = answer,
                            naReason = item.naReason,
                            defect =
                                if (answer == Answer.FAIL) {
                                    DefectDto(
                                        description = item.defectDescription,
                                        severity = item.severity ?: Severity.MINOR,
                                        immediateAction = item.immediateAction,
                                    )
                                } else {
                                    null
                                },
                            photoIds = item.photoIds,
                        )
                    }
                },
        )
}
