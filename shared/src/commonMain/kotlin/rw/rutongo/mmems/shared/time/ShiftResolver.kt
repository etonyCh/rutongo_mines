package rw.rutongo.mmems.shared.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import rw.rutongo.mmems.shared.enums.ReportPeriod

data class ShiftWindow(
    val shiftId: String,
    val code: String,
    val start: LocalTime,
    val end: LocalTime,
) {
    val crossesMidnight: Boolean get() = end <= start
}

data class ResolvedShift(
    val shiftId: String,
    val code: String,
    val reportDate: LocalDate,
    val startsAt: Instant,
    val endsAt: Instant,
)

class ShiftConfigurationException(
    message: String,
) : IllegalArgumentException(message)

class ShiftResolver(
    private val windows: List<ShiftWindow>,
) {
    init {
        require(windows.isNotEmpty()) { "At least one shift window is required" }
    }

    fun resolve(
        at: Instant,
        tz: TimeZone,
    ): ResolvedShift {
        val today = at.toLocalDateTime(tz).date
        for (day in listOf(today, today.minus(1, DateTimeUnit.DAY))) {
            for (window in windows) {
                val start = LocalDateTime(day, window.start).toInstant(tz)
                val endDay = if (window.crossesMidnight) day.plus(1, DateTimeUnit.DAY) else day
                val end = LocalDateTime(endDay, window.end).toInstant(tz)
                if (at >= start && at < end) return ResolvedShift(window.shiftId, window.code, day, start, end)
            }
        }
        throw ShiftConfigurationException("No shift covers $at in $tz")
    }

    fun periodInterval(
        date: LocalDate,
        period: ReportPeriod,
        tz: TimeZone,
    ): Pair<Instant, Instant> {
        if (period == ReportPeriod.FULL_DAY) {
            val intervals = windows.map { it.interval(date, tz) }
            return intervals.minOf { it.first } to intervals.maxOf { it.second }
        }
        val window =
            windows.firstOrNull { it.code == period.name }
                ?: throw ShiftConfigurationException("No ${period.name} shift is configured")
        return window.interval(date, tz)
    }

    private fun ShiftWindow.interval(
        date: LocalDate,
        tz: TimeZone,
    ): Pair<Instant, Instant> {
        val start = LocalDateTime(date, start).toInstant(tz)
        val endDay = if (crossesMidnight) date.plus(1, DateTimeUnit.DAY) else date
        return start to LocalDateTime(endDay, end).toInstant(tz)
    }
}
