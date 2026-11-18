package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.BigCounter
import rw.rutongo.mmems.android.ui.components.EmptyState
import rw.rutongo.mmems.android.ui.components.EquipmentRow
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.ProgressBar
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SecondaryButton
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun HomeScreen(
    container: AppContainer,
    me: MeView,
    onOpenInspections: () -> Unit,
    onOpenEquipment: () -> Unit,
    onOpenBreakdowns: () -> Unit,
) {
    val expected by container.store.expected().collectAsStateWithLifecycle(emptyList())
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    val outbox by container.store.allOutbox().collectAsStateWithLifecycle(emptyList())
    val pending = outbox.count { it.state in setOf("PENDING", "RETRY") }
    val completedStates = setOf("COMPLETED", "COMPLETED_WITH_DEFECTS", "CRITICAL_FAILURE")
    val completed = expected.count { it.state.uppercase() in completedStates }
    val remaining = expected.size - completed
    val site = expected.firstOrNull()?.site_name ?: equipment.firstOrNull()?.site_name ?: "Not recorded"
    val shift = expected.firstOrNull()?.shift_code ?: "Not recorded"
    val down = equipment.count { it.operational_status == "DOWN" }
    val repair = equipment.count { it.operational_status == "UNDER_REPAIR" }
    val operational = equipment.count { it.operational_status == "OPERATIONAL" }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "$site · $shift",
                title = "Good morning, ${me.fullName.substringBefore(' ')}",
                subtitle = "Site-scoped work, available without a connection.",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Start Daily Inspection", onOpenInspections)
                Text("$remaining of ${expected.size} units still to inspect", style = SpecTypography.BodySmall)
                ProgressBar(if (expected.isEmpty()) 0f else completed.toFloat() / expected.size)
                Text("Today's inspections $completed / ${expected.size}", style = SpecTypography.Label)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigCounter("Operational", operational.toString(), SpecColors.Operational, Modifier.weight(1f))
                BigCounter("Under repair", repair.toString(), SpecColors.UnderRepair, Modifier.weight(1f))
                BigCounter("Down", down.toString(), SpecColors.Down, Modifier.weight(1f))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Waiting to sync: $pending", style = SpecTypography.BodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("OPEN EQUIPMENT", onOpenEquipment, Modifier.weight(1f))
                    SecondaryButton("OPEN BREAKDOWNS", onOpenBreakdowns, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("MY INSPECTIONS", onOpenInspections, Modifier.weight(1f))
                    SecondaryButton("SYNC NOW", { SyncWorker.enqueueNow(container.appContext) }, Modifier.weight(1f))
                }
            }
        }
        item { SectionDivider() }
        item { Text("EQUIPMENT", style = SpecTypography.Eyebrow) }
        if (expected.isEmpty()) {
            item { EmptyState("Missing", "No inspection expectations are cached. Sync references from More when connected.") }
        }
        items(expected.take(8), key = { it.id }) { item ->
            val asset = equipment.firstOrNull { it.id == item.equipment_id }
            val inspectionState = item.state.uppercase().replace('_', ' ')
            EquipmentRow(
                code = item.equipment_code,
                name = item.equipment_name,
                secondaryLine = "${asset?.tunnel_name ?: "Not recorded"} · Inspection $inspectionState · due ${item.window_end.take(16).replace('T', ' ')}",
                status = asset?.operational_status ?: "NOT_RECORDED",
                onClick = onOpenInspections,
            )
        }
    }
}
