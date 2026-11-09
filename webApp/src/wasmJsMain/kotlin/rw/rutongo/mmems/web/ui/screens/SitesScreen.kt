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
import rw.rutongo.mmems.shared.web.comparisonRows
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.InfoBlue
import rw.rutongo.mmems.web.ui.SuccessGreen
import rw.rutongo.mmems.web.ui.WarningAmber
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.MetricCard
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SiteFilter
import rw.rutongo.mmems.web.ui.components.StatusBadge

@Composable
fun SitesScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "Sites",
            subtitle = "Authorised operating sites",
            busy = state.sitesBusy || state.dashboardBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            trailing = { SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) } },
        )
        val comparison = state.dashboard?.comparisonRows().orEmpty()
        val selected = state.selectedSiteId
        val sites = state.sites.filter { selected == null || it.id == selected }
        when {
            sites.isEmpty() && state.sitesBusy -> LoadingBlock("Loading sites…")
            sites.isEmpty() -> EmptyState("No sites are assigned to your account.")
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(sites, key = { it.id }) { site ->
                        val metrics = comparison.firstOrNull { it.siteId == site.id }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("${site.code} · ${site.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Timezone ${site.timezone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    StatusBadge(site.state)
                                }
                                if (metrics != null) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        MetricCard("Equipment", metrics.totalEquipment.toString(), "${metrics.operational} operational", SuccessGreen, Modifier.weight(1f))
                                        MetricCard("Availability", "${metrics.availabilityPercent.toInt()}%", "Current fleet", InfoBlue, Modifier.weight(1f))
                                        MetricCard("Open breakdowns", metrics.openBreakdowns.toString(), "${metrics.down} down", WarningAmber, Modifier.weight(1f))
                                        MetricCard("Missing inspections", metrics.missingInspections.toString(), "${metrics.approvedReports} reports approved", WarningAmber, Modifier.weight(1f))
                                    }
                                } else {
                                    Spacer(Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
