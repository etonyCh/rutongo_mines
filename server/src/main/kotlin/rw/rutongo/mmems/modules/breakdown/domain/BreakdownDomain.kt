package rw.rutongo.mmems.modules.breakdown.domain

import rw.rutongo.mmems.shared.enums.BreakdownState
import java.time.Duration
import java.time.Instant

data class Interval(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(!end.isBefore(start)) { "An interval cannot end before it starts." }
    }
}

data class BreakdownSpan(
    val start: Instant,
    val end: Instant?,
) {
    init {
        require(end == null || !end.isBefore(start)) { "A breakdown cannot end before it starts." }
    }
}

fun List<Interval>.union(): List<Interval> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.start }
    val result = mutableListOf<Interval>()
    var accumulated = sorted.first()
    for (interval in sorted.drop(1)) {
        accumulated =
            if (interval.start <= accumulated.end) {
                Interval(accumulated.start, maxOf(accumulated.end, interval.end))
            } else {
                result += accumulated
                interval
            }
    }
    result += accumulated
    return result
}

fun List<Interval>.clip(to: Interval): List<Interval> =
    mapNotNull { interval ->
        val start = maxOf(interval.start, to.start)
        val end = minOf(interval.end, to.end)
        if (end > start) Interval(start, end) else null
    }

fun downtimeMinutes(
    breakdowns: List<BreakdownSpan>,
    period: Interval,
    cutoff: Instant,
): Long {
    val boundedEnd = minOf(period.end, cutoff)
    if (boundedEnd <= period.start) return 0
    val boundedPeriod = Interval(period.start, boundedEnd)
    return breakdowns
        .mapNotNull { breakdown ->
            val end = breakdown.end ?: cutoff
            if (end > breakdown.start) Interval(breakdown.start, end) else null
        }.clip(boundedPeriod)
        .union()
        .sumOf { Duration.between(it.start, it.end).toMinutes() }
}

fun fleetDowntimeMinutes(
    breakdownsByEquipment: Map<String, List<BreakdownSpan>>,
    period: Interval,
    cutoff: Instant,
): Long = breakdownsByEquipment.values.sumOf { downtimeMinutes(it, period, cutoff) }

object BreakdownPolicy {
    private val transitions =
        mapOf(
            BreakdownState.REPORTED to setOf(BreakdownState.DIAGNOSING, BreakdownState.AWAITING_PARTS, BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED),
            BreakdownState.DIAGNOSING to setOf(BreakdownState.AWAITING_PARTS, BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED),
            BreakdownState.AWAITING_PARTS to setOf(BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED),
            BreakdownState.UNDER_REPAIR to setOf(BreakdownState.RESOLVED),
            BreakdownState.RESOLVED to setOf(BreakdownState.CLOSED),
            BreakdownState.CLOSED to emptySet(),
        )

    fun canTransition(
        from: BreakdownState,
        to: BreakdownState,
    ): Boolean = to in transitions.getValue(from)

    fun requireTransition(
        from: BreakdownState,
        to: BreakdownState,
    ) {
        require(canTransition(from, to)) { "A breakdown cannot move from $from to $to." }
    }

    fun isActive(state: BreakdownState): Boolean = state !in setOf(BreakdownState.RESOLVED, BreakdownState.CLOSED)

    fun validateTimeline(
        breakdownStart: Instant,
        repairStart: Instant?,
        repairEnd: Instant?,
    ) {
        require(repairStart == null || !repairStart.isBefore(breakdownStart)) { "The repair cannot start before the breakdown." }
        val effectiveStart = repairStart ?: breakdownStart
        require(repairEnd == null || !repairEnd.isBefore(effectiveStart)) { "The repair cannot end before it starts." }
    }
}
