@file:Suppress("MagicNumber")

package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.data.ActiveProblemRow
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SiteFilter
import rw.rutongo.mmems.web.ui.components.StatusBadge

@Composable
fun ActiveProblemsScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "Active problems",
            subtitle = "Equipment currently down or under repair",
            busy = state.activeProblemsBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = { SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) } },
        )
        when {
            state.activeProblems == null && state.activeProblemsBusy -> LoadingBlock("Loading active problems…")
            state.activeProblems.isNullOrEmpty() -> EmptyState("No active problems for the current filter.")
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.activeProblems.orEmpty(), key = { it.id }) { problem ->
                        ActiveProblemCard(problem, state)
                    }
                }
        }
    }
}

@Composable
private fun ActiveProblemCard(
    problem: ActiveProblemRow,
    state: WebState,
) {
    val equipment = state.equipment?.firstOrNull { it.id == problem.equipmentId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    equipment?.let { "${it.equipmentCode} · ${it.name}" } ?: problem.equipmentId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusBadge(problem.newStatus)
            }
            Text(
                "${problem.occurredAt.take(16).replace('T', ' ')} · reported by ${problem.userEid ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(problem.problem ?: "No problem description recorded.", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                problem.actionTaken?.let { Text("Action: $it", style = MaterialTheme.typography.bodySmall) }
                problem.partsRequired?.let { Text("Parts: $it", style = MaterialTheme.typography.bodySmall) }
            }
            problem.remarks?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(2.dp))
        }
    }
}
