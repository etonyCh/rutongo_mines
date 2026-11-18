package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.components.BigCounter
import rw.rutongo.mmems.android.ui.components.EmptyState
import rw.rutongo.mmems.android.ui.components.FilterPill
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.components.statusColor
import rw.rutongo.mmems.android.ui.components.statusLabel
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun InspectionsScreen(
    container: AppContainer,
    me: MeView,
    onOpen: (String) -> Unit,
    onResume: (String) -> Unit,
) {
    val expected by container.store.expected().collectAsStateWithLifecycle(emptyList())
    val drafts by container.store.openDraftsFlow().collectAsStateWithLifecycle(emptyList())
    val queued by container.store.outboxForKind("INSPECTION").collectAsStateWithLifecycle(emptyList())
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    val visibleQueue = queued.filter { it.state in setOf("PENDING", "RETRY", "CONFLICT", "REJECTED") }
    var filter by remember { mutableStateOf("ALL") }
    val done = expected.count { it.state.uppercase() == "COMPLETED" }
    val clean = done
    val defects = expected.count { it.state.uppercase() in setOf("COMPLETED_WITH_DEFECTS", "CRITICAL_FAILURE") }
    val missing = expected.count { it.state.uppercase() == "MISSING" }
    val visible =
        expected.filter { item ->
            when (filter) {
                "MISSING" -> item.state.uppercase() == "MISSING"
                "DEFECTS" -> item.state.uppercase() in setOf("COMPLETED_WITH_DEFECTS", "CRITICAL_FAILURE")
                "TUNNEL" -> equipment.firstOrNull { it.id == item.equipment_id }?.tunnel_name != null
                else -> true
            }
        }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = me.role.name.replace('_', ' '),
                title = "Daily inspection control",
                subtitle = "${me.fullName} · work continues without a connection",
            )
        }
        item {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigCounter("DONE", done.toString(), SpecColors.Operational, Modifier.weight(1f))
                BigCounter("CLEAN", clean.toString(), SpecColors.Operational, Modifier.weight(1f))
                BigCounter("DEFECTS", defects.toString(), SpecColors.UnderRepair, Modifier.weight(1f))
                BigCounter("MISSING", missing.toString(), SpecColors.Down, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All", filter == "ALL", { filter = "ALL" }, Modifier.width(80.dp))
                FilterPill("Missing $missing", filter == "MISSING", { filter = "MISSING" })
                FilterPill("Defects", filter == "DEFECTS", { filter = "DEFECTS" })
                FilterPill("Tunnel", filter == "TUNNEL", { filter = "TUNNEL" })
            }
        }
        if (drafts.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DRAFTS", style = SpecTypography.Eyebrow)
                    drafts.forEach { draft ->
                        Row(
                            Modifier
                                .fillMaxSize()
                                .clickable { onResume(draft.clientUuid) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(draft.equipmentName, style = SpecTypography.Body)
                                Text("${draft.equipmentCode} · step ${draft.step.name.lowercase().replace('_', ' ')}", style = SpecTypography.BodySmall)
                            }
                            Text("Resume", style = SpecTypography.Status, color = SpecColors.Action)
                        }
                        SectionDivider()
                    }
                }
            }
        }
        if (visibleQueue.isNotEmpty()) {
            item { Text("WAITING TO SYNC", style = SpecTypography.Eyebrow) }
            items(visibleQueue, key = { "outbox-${it.client_uuid}" }) { operation ->
                val asset = equipment.firstOrNull { it.id == operation.equipment_id }
                Row(Modifier.fillMaxSize().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(asset?.name ?: "Inspection", style = SpecTypography.Body)
                        Text(
                            when (operation.state) {
                                "CONFLICT" -> "Conflict — open More and sync after reviewing."
                                "REJECTED" -> operation.last_error ?: "Rejected by the server."
                                else -> "Queued locally · ${operation.attempts} attempt(s)"
                            },
                            style = SpecTypography.BodySmall,
                            color = if (operation.state == "REJECTED") SpecColors.Down else SpecColors.SecondaryText,
                        )
                    }
                }
                SectionDivider()
            }
        }
        item { Text("OPEN EXPECTATIONS", style = SpecTypography.Eyebrow) }
        if (visible.isEmpty() && drafts.isEmpty()) {
            item { EmptyState("Nothing to inspect", "Change the filter or sync references from More when connected.") }
        }
        items(visible, key = { it.id }) { item ->
            val asset = equipment.firstOrNull { it.id == item.equipment_id }
            val state = item.state.uppercase()
            Row(
                Modifier
                    .fillMaxSize()
                    .clickable { onOpen(item.id) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.equipment_code} · ${item.equipment_name}", style = SpecTypography.Body, color = SpecColors.Text)
                    Text(
                        "${asset?.tunnel_name ?: "Not recorded"} · ${item.site_name} · ${item.shift_code} · due ${item.window_end.take(16).replace('T', ' ')}",
                        style = SpecTypography.BodySmall,
                    )
                    Text(statusLabel(state), style = SpecTypography.Status, color = statusColor(state))
                }
                Text("START", style = SpecTypography.Status, color = SpecColors.Action)
            }
            SectionDivider()
        }
    }
}
