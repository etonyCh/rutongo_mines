package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer

@Composable
fun TasksScreen(container: AppContainer) {
    val drafts by container.store.openDraftsFlow().collectAsStateWithLifecycle(emptyList())
    val outbox by container.store.allOutbox().collectAsStateWithLifecycle(emptyList())
    val pending = outbox.filter { it.state in setOf("PENDING", "RETRY") }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("My tasks", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${drafts.size} draft(s) · ${pending.size} operation(s) waiting", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(drafts, key = { it.clientUuid }) { draft ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Continue ${draft.equipmentName}", fontWeight = FontWeight.Bold)
                    Text("${draft.equipmentCode} · saved at ${draft.step.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(pending, key = { it.client_uuid }) { op ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (op.kind == "STATUS") "Status update" else "Inspection submission", fontWeight = FontWeight.Bold)
                    Text("${op.client_uuid} · ${op.state.lowercase()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (drafts.isEmpty() && pending.isEmpty()) {
            item { Text("Nothing is waiting.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
