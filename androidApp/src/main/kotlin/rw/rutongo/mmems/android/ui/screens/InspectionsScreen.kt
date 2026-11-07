package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.theme.Amber
import rw.rutongo.mmems.android.ui.theme.Green
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
    val visibleQueue = queued.filter { it.state in setOf("PENDING", "RETRY", "CONFLICT", "REJECTED") }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("My inspections", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${me.fullName} · work continues without a connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (drafts.isNotEmpty()) {
            item { Text("DRAFTS", style = MaterialTheme.typography.labelLarge, color = Amber, fontWeight = FontWeight.Bold) }
            items(drafts, key = { "draft-${it.clientUuid}" }) { draft ->
                Card(Modifier.fillMaxWidth().clickable { onResume(draft.clientUuid) }) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Amber)
                        Column(Modifier.weight(1f)) {
                            Text(draft.equipmentName, fontWeight = FontWeight.Bold)
                            Text("${draft.equipmentCode} · ${draft.shiftCode} · step ${draft.step.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Resume", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (visibleQueue.isNotEmpty()) {
            item { Text("WAITING TO SYNC", style = MaterialTheme.typography.labelLarge, color = Amber, fontWeight = FontWeight.Bold) }
            items(visibleQueue, key = { "outbox-${it.client_uuid}" }) { operation ->
                val equipment = container.store.equipment(operation.equipment_id)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = if (operation.state == "REJECTED") MaterialTheme.colorScheme.error else Amber)
                        Column(Modifier.weight(1f)) {
                            Text(equipment?.name ?: "Inspection", fontWeight = FontWeight.Bold)
                            val detail =
                                when (operation.state) {
                                    "CONFLICT" -> "Conflict — open More and sync after reviewing."
                                    "REJECTED" -> operation.last_error ?: "Rejected by the server."
                                    else -> "Queued locally · ${operation.attempts} attempt(s)"
                                }
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (operation.state == "REJECTED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Text("OPEN EXPECTATIONS", style = MaterialTheme.typography.labelLarge, color = Green, fontWeight = FontWeight.Bold) }
        if (expected.isEmpty() && drafts.isEmpty()) {
            item { Text("No inspections are open. Sync references from More when connected.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(expected, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(item.id) }) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Assignment, contentDescription = null, tint = Green)
                    Column(Modifier.weight(1f)) {
                        Text(item.equipment_name, fontWeight = FontWeight.Bold)
                        Text("${item.equipment_code} · ${item.site_name}", style = MaterialTheme.typography.bodySmall)
                        Text("${item.shift_code} · ${item.report_date} · due ${item.window_end.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("START", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
