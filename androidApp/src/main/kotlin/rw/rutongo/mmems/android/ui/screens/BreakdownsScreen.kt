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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
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
import rw.rutongo.mmems.android.ui.components.MetricTile
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.shared.api.MeView

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Breakdowns", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${me.fullName} · reports and repairs work without a connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Active", active.size.toString(), Icons.Default.Build, modifier = Modifier.weight(1f))
                MetricTile("Awaiting parts", awaitingParts.toString(), Icons.Default.Build, modifier = Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("REPORT BREAKDOWN")
            }
        }
        if (breakdowns.isEmpty()) {
            item {
                Text(
                    "No breakdowns are cached. Sync from More when connected, or report a breakdown now and let the queue send it later.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(breakdowns, key = { it.id }) { breakdown ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(breakdown.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(breakdown.equipment_code ?: breakdown.equipment_id, fontWeight = FontWeight.Bold)
                            Text(breakdown.category, style = MaterialTheme.typography.bodySmall)
                        }
                        StatusPill(breakdown.state)
                    }
                    Text(breakdown.problem, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "Started ${breakdown.breakdown_start.take(16).replace('T', ' ')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        breakdown.pending_state?.let { pending ->
                            Text(
                                if (pending == "CREATE") "Waiting to sync" else "Repair update waiting",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
