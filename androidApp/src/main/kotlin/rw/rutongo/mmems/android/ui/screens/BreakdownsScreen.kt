package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.components.BigCounter
import rw.rutongo.mmems.android.ui.components.EmptyState
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.components.statusColor
import rw.rutongo.mmems.android.ui.components.statusLabel
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.MeView
import java.time.Duration
import java.time.Instant

@Composable
fun BreakdownsScreen(
    container: AppContainer,
    me: MeView,
    onReport: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val breakdowns by container.store.breakdowns().collectAsStateWithLifecycle(emptyList())
    val active = breakdowns.filter { it.state !in setOf("RESOLVED", "CLOSED") }
    val awaitingParts = active.count { it.state == "AWAITING_PARTS" }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = me.role.name.replace('_', ' '),
                title = "Active problems",
                subtitle = "${me.fullName} · reports and repairs work without a connection",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigCounter("Active", active.size.toString(), SpecColors.Down, Modifier.weight(1f))
                BigCounter("Awaiting parts", awaitingParts.toString(), SpecColors.UnderRepair, Modifier.weight(1f))
            }
        }
        item { PrimaryButton("REPORT BREAKDOWN", onReport) }
        if (breakdowns.isEmpty()) {
            item { EmptyState("No breakdowns cached", "Sync from More when connected, or report a breakdown now and let the queue send it later.") }
        }
        items(breakdowns, key = { it.id }) { breakdown ->
            val color = statusColor(breakdown.state)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(breakdown.id) }
                    .background(SpecColors.Background)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.width(4.dp).height(96.dp).background(color))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${breakdown.equipment_code ?: "Not recorded"} · ${breakdown.category}", style = SpecTypography.Body, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(statusLabel(breakdown.state), style = SpecTypography.Status, color = color)
                    }
                    Text(breakdown.problem, style = SpecTypography.BodySmall)
                    Text("Reported at ${breakdown.reported_at.take(16).replace('T', ' ')} · reporter Not recorded", style = SpecTypography.BodySmall)
                    Text("Technician Not recorded · Downtime ${durationLabel(breakdown.breakdown_start, breakdown.repair_end)}", style = SpecTypography.BodySmall, color = color)
                    Text("Parts: ${breakdown.parts_required ?: "Not recorded"}", style = SpecTypography.BodySmall)
                    breakdown.pending_state?.let {
                        Text(if (it == "CREATE") "Waiting to sync" else "Repair update waiting", style = SpecTypography.Label, color = SpecColors.Action)
                    }
                    SectionDivider()
                }
            }
        }
    }
}

private fun durationLabel(
    start: String,
    end: String? = null,
): String =
    runCatching {
        val finish = end?.let { Instant.parse(it) } ?: Instant.now()
        val minutes = Duration.between(Instant.parse(start), finish).toMinutes().coerceAtLeast(0)
        when {
            minutes >= 1440 -> "${minutes / 1440} d ${(minutes % 1440) / 60} h"
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} m"
            else -> "$minutes m"
        }
    }.getOrDefault("Not recorded")
