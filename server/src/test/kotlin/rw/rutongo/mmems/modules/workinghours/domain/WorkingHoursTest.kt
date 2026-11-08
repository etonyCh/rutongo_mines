package rw.rutongo.mmems.modules.workinghours.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkingHoursTest {
    @Test
    fun `overlapping meter intervals are counted once`() {
        val intervals =
            listOf(
                HoursInterval(100.0, 110.0),
                HoursInterval(105.0, 112.0),
                HoursInterval(112.0, 115.0),
            )

        assertEquals(15.0, intervals.recordedHours())
        assertEquals(listOf(HoursInterval(100.0, 115.0)), intervals.unionHours())
    }

    @Test
    fun `disjoint intervals remain separate and sum to their total length`() {
        val intervals = listOf(HoursInterval(100.0, 101.5), HoursInterval(103.0, 105.0))

        assertEquals(2, intervals.unionHours().size)
        assertEquals(3.5, intervals.recordedHours())
    }

    @Test
    fun `empty and zero-length intervals do not invent recorded hours`() {
        assertEquals(0.0, emptyList<HoursInterval>().recordedHours())
        assertEquals(0.0, listOf(HoursInterval(200.0, 200.0)).recordedHours())
    }
}
