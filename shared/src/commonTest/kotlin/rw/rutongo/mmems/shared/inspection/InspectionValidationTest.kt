package rw.rutongo.mmems.shared.inspection

import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectionValidationTest {
    private val safety =
        InspectionChecklistRule(
            itemId = "safety",
            code = "SAFETY_BRAKE",
            label = "Brake test",
            mandatory = true,
            safetyCritical = true,
            photoRequiredOnFail = true,
            naReasonRequired = true,
        )

    @Test
    fun `failures require a description and a photo`() {
        val outcome =
            InspectionValidator.validate(
                InspectionValidationInput(
                    answers = listOf(InspectionAnswerRule("safety", Answer.FAIL)),
                    checklist = listOf(safety),
                    engineHoursStart = 10.0,
                    engineHoursEnd = 12.0,
                    lastReading = 9.0,
                    attestationPresent = true,
                ),
            )
        assertEquals(setOf("answers.SAFETY_BRAKE.defect", "answers.SAFETY_BRAKE.photo"), outcome.errors.map { it.field }.toSet())
    }

    @Test
    fun `critical failures produce a critical result`() {
        val result = InspectionValidator.result(listOf(AnswerWithSeverity(Answer.FAIL, Severity.CRITICAL)))
        assertEquals(rw.rutongo.mmems.shared.enums.InspectionResult.CRITICAL_FAILURE, result)
    }

    @Test
    fun `negative engine hours are rejected`() {
        val outcome = EngineHours.evaluate(-1.0, 2.0, null)
        assertTrue(outcome.errors.any { it.field == "engineHoursStart" })
    }
}
