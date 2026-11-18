package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import rw.rutongo.mmems.android.ui.components.EmptyState
import rw.rutongo.mmems.android.ui.components.EquipmentRow
import rw.rutongo.mmems.android.ui.components.FormLabel
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.theme.SpecSpacing

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
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Offline cache",
                title = "Equipment",
                subtitle = "${all.size} records cached for offline work",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel("Search equipment or site")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search equipment or site") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillParentMaxWidth(),
                )
            }
        }
        if (equipment.isEmpty()) {
            item { EmptyState("No matching equipment", "Change the search text or sync references from More when connected.") }
        }
        items(equipment, key = { it.id }) { item ->
            EquipmentRow(
                code = item.equipment_code,
                name = item.name,
                secondaryLine = "${item.site_name} · ${item.tunnel_name ?: "Not recorded"} · ${item.type} · hours ${item.engine_hours?.let { "%.1f".format(it) } ?: "Not recorded"} · v${item.version}",
                status = item.operational_status,
                onClick = { onOpen(item.id) },
            )
        }
    }
}
