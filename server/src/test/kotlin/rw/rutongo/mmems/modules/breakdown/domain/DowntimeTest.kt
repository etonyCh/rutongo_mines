package rw.rutongo.mmems.modules.breakdown.domain

import org.junit.jupiter.api.Test
import rw.rutongo.mmems.shared.enums.BreakdownState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DowntimeTest {
    private val period = Interval(at("2026-11-05T08:00:00Z"), at("2026-11-05T12:00:00Z"))
    private val cutoff = at("2026-11-05T12:00:00Z")

    @Test
    fun `overlapping incidents are counted once`() {
        val spans =
            listOf(
                BreakdownSpan(at("2026-11-05T08:00:00Z"), at("2026-11-05T10:00:00Z")),
                BreakdownSpan(at("2026-11-05T09:00:00Z"), at("2026-11-05T11:00:00Z")),
            )

        assertEquals(180, downtimeMinutes(spans, period, cutoff))
    }

    @Test
    fun `touching or disjoint incidents are unioned correctly`() {
        val spans =
            listOf(
                BreakdownSpan(at("2026-11-05T08:00:00Z"), at("2026-11-05T09:00:00Z")),
                BreakdownSpan(at("2026-11-05T09:00:00Z"), at("2026-11-05T09:30:00Z")),
                BreakdownSpan(at("2026-11-05T11:00:00Z"), at("2026-11-05T11:15:00Z")),
            )

        assertEquals(105, downtimeMinutes(spans, period, cutoff))
    }

    @Test
    fun `downtime is clipped to the period and the cutoff`() {
        val spans =
            listOf(
                BreakdownSpan(at("2026-11-05T06:00:00Z"), at("2026-11-05T09:00:00Z")),
                BreakdownSpan(at("2026-11-05T11:00:00Z"), at("2026-11-05T14:00:00Z")),
                BreakdownSpan(at("2026-11-05T11:30:00Z"), null),
            )

        assertEquals(120, downtimeMinutes(spans, period, cutoff))
        assertEquals(60, downtimeMinutes(spans, period, at("2026-11-05T11:00:00Z")))
    }

    @Test
    fun `an open breakdown uses the cutoff`() {
        val spans = listOf(BreakdownSpan(at("2026-11-05T08:00:00Z"), null))

        assertEquals(150, downtimeMinutes(spans, period, at("2026-11-05T10:30:00Z")))
        assertEquals(0, downtimeMinutes(spans, period, at("2026-11-05T07:00:00Z")))
    }

    @Test
    fun `fleet downtime sums already deduplicated equipment downtime`() {
        val byEquipment =
            mapOf(
                "A" to
                    listOf(
                        BreakdownSpan(at("2026-11-05T08:00:00Z"), at("2026-11-05T10:00:00Z")),
                        BreakdownSpan(at("2026-11-05T09:00:00Z"), at("2026-11-05T11:00:00Z")),
                    ),
                "B" to listOf(BreakdownSpan(at("2026-11-05T10:00:00Z"), at("2026-11-05T10:30:00Z"))),
            )

        assertEquals(210, fleetDowntimeMinutes(byEquipment, period, cutoff))
    }

    @Test
    fun `a span cannot end before it starts`() {
        assertFailsWith<IllegalArgumentException> {
            BreakdownSpan(at("2026-11-05T10:00:00Z"), at("2026-11-05T09:00:00Z"))
        }
    }

    @Test
    fun `breakdown transitions move forward only`() {
        BreakdownPolicy.requireTransition(BreakdownState.REPORTED, BreakdownState.UNDER_REPAIR)
        BreakdownPolicy.requireTransition(BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED)
        BreakdownPolicy.requireTransition(BreakdownState.RESOLVED, BreakdownState.CLOSED)

        assertEquals(true, BreakdownPolicy.canTransition(BreakdownState.REPORTED, BreakdownState.DIAGNOSING))
        assertEquals(false, BreakdownPolicy.canTransition(BreakdownState.CLOSED, BreakdownState.REPORTED))
        assertEquals(false, BreakdownPolicy.canTransition(BreakdownState.REPORTED, BreakdownState.CLOSED))

        assertFailsWith<IllegalArgumentException> {
            BreakdownPolicy.requireTransition(BreakdownState.CLOSED, BreakdownState.REPORTED)
        }
        assertFailsWith<IllegalArgumentException> {
            BreakdownPolicy.requireTransition(BreakdownState.REPORTED, BreakdownState.CLOSED)
        }
    }

    @Test
    fun `repair timestamps cannot precede the breakdown`() {
        val start = at("2026-11-05T08:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            BreakdownPolicy.validateTimeline(start, at("2026-11-05T07:59:59Z"), null)
        }
        assertFailsWith<IllegalArgumentException> {
            BreakdownPolicy.validateTimeline(start, at("2026-11-05T09:00:00Z"), at("2026-11-05T08:59:59Z"))
        }
    }

    private fun at(value: String): Instant = Instant.parse(value)
}
