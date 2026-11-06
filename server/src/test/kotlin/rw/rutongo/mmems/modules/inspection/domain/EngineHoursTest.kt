package rw.rutongo.mmems.modules.inspection.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineHoursTest {
    @Test
    fun `ending hours below the starting reading are never valid`() {
        val starts = listOf(0.0, 0.1, 12.3, 99.9, 1000.0, 9999.9)
        val decreases = listOf(0.1, 1.0, 24.0, 100.0)
        for (start in starts) {
            for (decrease in decreases) {
                val evaluation = EngineHours.evaluate(start, start - decrease, null)
                assertTrue(evaluation.errors.any { it.field == "engineHoursEnd" }, "start=$start decrease=$decrease")
            }
        }
    }

    @Test
    fun `recorded hours equal the end minus start for valid readings`() {
        val cases = listOf(0.0 to 8.0, 100.0 to 100.5, 1999.5 to 2008.0, 5000.0 to 5024.0)
        for ((start, end) in cases) {
            val evaluation = EngineHours.evaluate(start, end, null)
            assertEquals(end - start, evaluation.recordedHours)
            assertEquals(WorkingHoursStatus.VALID, evaluation.status)
        }
    }

    @Test
    fun `readings below the previous meter or above twenty four hours are suspicious`() {
        val belowPrevious = EngineHours.evaluate(99.0, 107.0, 100.0)
        val tooLong = EngineHours.evaluate(100.0, 124.1, 100.0)
        val missingEnd = EngineHours.evaluate(100.0, null, 90.0)

        assertTrue("START_BELOW_LAST_READING" in belowPrevious.warnings)
        assertTrue("WORKING_HOURS_ABOVE_24" in tooLong.warnings)
        assertEquals(WorkingHoursStatus.SUSPICIOUS, tooLong.status)
        assertEquals(WorkingHoursStatus.MISSING_READING, missingEnd.status)
        assertEquals(null, missingEnd.recordedHours)
    }
}
