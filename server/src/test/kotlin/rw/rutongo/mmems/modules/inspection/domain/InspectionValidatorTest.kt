package rw.rutongo.mmems.modules.inspection.domain

import org.junit.jupiter.api.Test
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.InspectionResult
import rw.rutongo.mmems.shared.enums.Severity
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectionValidatorTest {
    private val safety =
        ChecklistItem(
            id = "safety",
            code = "SAFETY_SEAT_BELT",
            label = "Seat Belt",
            category = ChecklistCategory.SAFETY,
            mandatory = true,
            safetyCritical = true,
            photoRequiredOnFail = true,
            naReasonRequired = true,
            active = true,
        )
    private val mechanical =
        ChecklistItem(
            id = "mechanical",
            code = "MECHANICAL_ENGINE_OIL",
            label = "Engine Oil",
            category = ChecklistCategory.MECHANICAL,
            mandatory = true,
            safetyCritical = false,
            photoRequiredOnFail = false,
            naReasonRequired = true,
            active = true,
        )

    @Test
    fun `mandatory answers defects and photos are enforced`() {
        val outcome =
            InspectionValidator.validate(
                submission(
                    answers =
                        listOf(
                            InspectionAnswerInput(safety.id, Answer.FAIL, defect = DefectInput(" ", Severity.CRITICAL)),
                            InspectionAnswerInput(mechanical.id, Answer.NA, naReason = " "),
                        ),
                ),
                items = listOf(safety, mechanical),
                lastReading = 100.0,
            )

        assertEquals(setOf("answers.SAFETY_SEAT_BELT.defect", "answers.SAFETY_SEAT_BELT.photo", "answers.MECHANICAL_ENGINE_OIL.naReason"), outcome.errors.map { it.field }.toSet())
    }

    @Test
    fun `missing mandatory item is rejected`() {
        val outcome =
            InspectionValidator.validate(
                submission(answers = listOf(InspectionAnswerInput(safety.id, Answer.PASS))),
                items = listOf(safety, mechanical),
                lastReading = null,
            )

        assertTrue(outcome.errors.any { it.field == "answers.MECHANICAL_ENGINE_OIL" })
    }

    @Test
    fun `engine hours require a start and cannot decrease`() {
        val outcome =
            InspectionValidator.validate(
                submission(
                    answers = listOf(InspectionAnswerInput(safety.id, Answer.PASS), InspectionAnswerInput(mechanical.id, Answer.PASS)),
                    start = null,
                    end = null,
                ),
                items = listOf(safety, mechanical),
                lastReading = null,
            )
        val decrease =
            InspectionValidator.validate(
                submission(
                    answers = listOf(InspectionAnswerInput(safety.id, Answer.PASS), InspectionAnswerInput(mechanical.id, Answer.PASS)),
                    start = 200.0,
                    end = 199.9,
                ),
                items = listOf(safety, mechanical),
                lastReading = null,
            )

        assertTrue(outcome.errors.any { it.field == "engineHoursStart" })
        assertTrue(decrease.errors.any { it.field == "engineHoursEnd" })
    }

    @Test
    fun `attestation is mandatory before submission`() {
        val outcome =
            InspectionValidator.validate(
                submission(
                    answers = listOf(InspectionAnswerInput(safety.id, Answer.PASS), InspectionAnswerInput(mechanical.id, Answer.PASS)),
                    attestation = null,
                ),
                items = listOf(safety, mechanical),
                lastReading = null,
            )

        assertTrue(outcome.errors.any { it.field == "attestation" })
    }

    @Test
    fun `critical failure dominates the inspection result`() {
        assertEquals(
            InspectionResult.CRITICAL_FAILURE,
            InspectionValidator.result(listOf(AnswerWithSeverity(Answer.FAIL, Severity.MINOR), AnswerWithSeverity(Answer.FAIL, Severity.CRITICAL))),
        )
        assertEquals(InspectionResult.COMPLETED_WITH_DEFECTS, InspectionValidator.result(listOf(AnswerWithSeverity(Answer.FAIL, Severity.MAJOR))))
        assertEquals(InspectionResult.COMPLETED, InspectionValidator.result(listOf(AnswerWithSeverity(Answer.PASS, null), AnswerWithSeverity(Answer.NA, null))))
    }

    private fun submission(
        answers: List<InspectionAnswerInput>,
        start: Double? = 100.0,
        end: Double? = 108.0,
        attestation: AttestationInput? = AttestationInput("Inspector", "EID-1", "PIN"),
    ) = InspectionSubmission(
        clientUuid = "00000000-0000-0000-0000-000000000001",
        expectationId = null,
        equipmentId = "equipment",
        shiftId = "shift",
        reportDate = LocalDate.parse("2026-11-05"),
        engineHoursStart = start,
        engineHoursEnd = end,
        capturedAt = Instant.parse("2026-11-05T08:00:00Z"),
        startedAt = Instant.parse("2026-11-05T07:55:00Z"),
        attestation = attestation,
        answers = answers,
    )
}
