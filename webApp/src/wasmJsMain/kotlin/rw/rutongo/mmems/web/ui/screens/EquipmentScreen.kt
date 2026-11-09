package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SiteFilter
import rw.rutongo.mmems.web.ui.components.StatusBadge
import rw.rutongo.mmems.web.ui.components.statusColor

@Composable
fun EquipmentScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val items =
        state.equipment.orEmpty().filter {
            search.isBlank() ||
                it.equipmentCode.contains(search, ignoreCase = true) ||
                it.assetId.contains(search, ignoreCase = true) ||
                it.name.contains(search, ignoreCase = true) ||
                it.type.contains(search, ignoreCase = true)
        }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "Equipment",
            subtitle = "Fleet register and current operational status",
            busy = state.equipmentBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = { SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) } },
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search asset, code, name or type") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.equipment == null && state.equipmentBusy -> LoadingBlock("Loading equipment…")
            items.isEmpty() -> EmptyState("No equipment matches the current filter.")
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.id }) { equipment ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(equipment.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${equipment.equipmentCode} · ${equipment.assetId} · ${equipment.type}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "${equipment.siteName}${equipment.tunnelName?.let { " · $it" } ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        StatusBadge(equipment.lifecycleState)
                                        StatusBadge(equipment.operationalStatus)
                                    }
                                    Text(
                                        equipment.engineHours?.let { "Engine ${it.toInt()} h" } ?: "Engine hours not recorded",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor(equipment.operationalStatus),
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}
