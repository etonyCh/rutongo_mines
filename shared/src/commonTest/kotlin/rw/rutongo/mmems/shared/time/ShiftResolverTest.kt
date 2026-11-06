package rw.rutongo.mmems.shared.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import rw.rutongo.mmems.shared.enums.ReportPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShiftResolverTest {
    private val tz = TimeZone.of("Africa/Kigali")
    private val resolver =
        ShiftResolver(
            listOf(
                ShiftWindow("d", "DAY", LocalTime(6, 0), LocalTime(14, 0)),
                ShiftWindow("r", "RED", LocalTime(14, 0), LocalTime(22, 0)),
                ShiftWindow("n", "NIGHT", LocalTime(22, 0), LocalTime(6, 0)),
            ),
        )

    @Test
    fun `02 00 belongs to the night shift that started the previous day`() {
        val at = LocalDateTime(2026, 11, 5, 2, 0).toInstant(tz)

        val resolved = resolver.resolve(at, tz)

        assertEquals("NIGHT", resolved.code)
        assertEquals(LocalDate(2026, 11, 4), resolved.reportDate)
        assertEquals(LocalDateTime(2026, 11, 5, 6, 0).toInstant(tz), resolved.endsAt)
    }

    @Test
    fun `shift boundaries are half-open so 22 00 starts the next night shift`() {
        val resolved = resolver.resolve(LocalDateTime(2026, 11, 5, 22, 0).toInstant(tz), tz)

        assertEquals("NIGHT", resolved.code)
        assertEquals(LocalDate(2026, 11, 5), resolved.reportDate)
    }

    @Test
    fun `full day unions every shift beginning on the report date`() {
        val (start, end) = resolver.periodInterval(LocalDate(2026, 11, 5), ReportPeriod.FULL_DAY, tz)

        assertEquals(LocalDateTime(2026, 11, 5, 6, 0).toInstant(tz), start)
        assertEquals(LocalDateTime(2026, 11, 6, 6, 0).toInstant(tz), end)
    }

    @Test
    fun `unconfigured shift is rejected instead of guessed`() {
        val empty = ShiftResolver(listOf(ShiftWindow("d", "DAY", LocalTime(6, 0), LocalTime(14, 0))))

        assertFailsWith<ShiftConfigurationException> {
            empty.periodInterval(LocalDate(2026, 11, 5), ReportPeriod.NIGHT, tz)
        }
    }
}
