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
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.components.StatusPill

@Composable
fun EquipmentScreen(
    container: AppContainer,
    onOpen: (String) -> Unit,
) {
    val all by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    val equipment = all.filter { query.isBlank() || it.name.contains(query, true) || it.equipment_code.contains(query, true) || it.site_name.contains(query, true) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Equipment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${all.size} records cached for offline work", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search equipment or site") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(equipment, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(item.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Engineering, contentDescription = null)
                            Column {
                                Text(item.name, fontWeight = FontWeight.Bold)
                                Text("${item.equipment_code} · ${item.type}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("v${item.version}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(item.site_name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusPill(item.operational_status)
                    Text(
                        "Engine hours: ${item.engine_hours?.let { "%.1f".format(it) } ?: "not recorded"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
