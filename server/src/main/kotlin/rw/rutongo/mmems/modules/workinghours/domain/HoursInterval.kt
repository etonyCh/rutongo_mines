package rw.rutongo.mmems.modules.workinghours.domain

data class HoursInterval(
    val start: Double,
    val end: Double,
) {
    init {
        require(end >= start) { "A working-hours interval cannot end before it starts." }
    }
}

fun List<HoursInterval>.unionHours(): List<HoursInterval> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.start }
    val result = mutableListOf<HoursInterval>()
    var accumulated = sorted.first()
    for (interval in sorted.drop(1)) {
        accumulated =
            if (interval.start <= accumulated.end) {
                HoursInterval(accumulated.start, maxOf(accumulated.end, interval.end))
            } else {
                result += accumulated
                interval
            }
    }
    result += accumulated
    return result
}

fun List<HoursInterval>.recordedHours(): Double = unionHours().sumOf { it.end - it.start }
