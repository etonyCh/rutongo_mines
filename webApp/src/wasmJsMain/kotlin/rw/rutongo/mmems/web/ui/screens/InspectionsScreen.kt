@file:Suppress("MagicNumber")

package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SiteFilter
import rw.rutongo.mmems.web.ui.components.StatusBadge

@Composable
fun InspectionsScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "Inspection control",
            subtitle = "Expected, missing and completed inspections for the selected site",
            busy = state.inspectionsBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = { SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) } },
        )
        when {
            state.inspections == null && state.inspectionsBusy -> LoadingBlock("Loading inspections…")
            state.inspections.isNullOrEmpty() -> EmptyState("No inspections recorded for the current filter.")
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.inspections.orEmpty(), key = { it.id }) { inspection ->
                        InspectionCard(inspection)
                    }
                }
        }
    }
}

@Composable
private fun InspectionCard(inspection: ExpectedInspectionDto) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${inspection.equipmentCode} · ${inspection.equipmentName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusBadge(inspection.state)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${inspection.siteName} · ${inspection.shiftCode} · ${inspection.reportDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Window ${inspection.windowStart.take(16).replace('T', ' ')} – ${inspection.windowEnd.take(16).replace('T', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            inspection.missingMarkedAt?.let {
                Text("Missing marked ${it.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
