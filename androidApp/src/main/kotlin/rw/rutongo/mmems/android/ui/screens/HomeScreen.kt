package rw.rutongo.mmems.android.ui.screens

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
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Button
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
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.MetricTile
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun HomeScreen(
    container: AppContainer,
    me: MeView,
    onOpenInspections: () -> Unit,
    onOpenEquipment: () -> Unit,
) {
    val expected by container.store.expected().collectAsStateWithLifecycle(emptyList())
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    val outbox by container.store.allOutbox().collectAsStateWithLifecycle(emptyList())
    val pending = outbox.count { it.state in setOf("PENDING", "RETRY") }
    val down = equipment.count { it.operational_status == "DOWN" }
    val repair = equipment.count { it.operational_status == "UNDER_REPAIR" }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Good shift, ${me.fullName.substringBefore(' ')}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Site-scoped work, available without a connection.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Inspections due", expected.size.toString(), Icons.Default.Assignment, modifier = Modifier.weight(1f))
                MetricTile("Waiting to sync", pending.toString(), Icons.Default.ReportProblem, modifier = Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Equipment condition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$down down", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Text("$repair under repair", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("${equipment.size} visible", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onOpenEquipment, modifier = Modifier.fillMaxWidth()) { Text("OPEN EQUIPMENT") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Today's inspections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (expected.isEmpty()) {
                        Text("No open inspection expectations. Pull the reference sync from More after connecting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = onOpenInspections, modifier = Modifier.fillMaxWidth()) { Text("MY INSPECTIONS") }
                    Button(onClick = { SyncWorker.enqueueNow(container.appContext) }, modifier = Modifier.fillMaxWidth()) { Text("SYNC NOW") }
                }
            }
        }
        items(expected.take(5), key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(item.equipment_name, fontWeight = FontWeight.Bold)
                            Text("${item.equipment_code} · ${item.shift_code} · ${item.report_date}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Engineering, contentDescription = null)
                    }
                    StatusPill("NOT_RECORDED")
                }
            }
        }
    }
}
