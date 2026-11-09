@file:Suppress("MagicNumber")

package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.api.DashboardDto
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.web.WebTab
import rw.rutongo.mmems.shared.web.comparisonRows
import rw.rutongo.mmems.shared.web.isVisibleFor
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.DangerRed
import rw.rutongo.mmems.web.ui.InfoBlue
import rw.rutongo.mmems.web.ui.SuccessGreen
import rw.rutongo.mmems.web.ui.WarningAmber
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.MetricCard
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SectionTitle
import rw.rutongo.mmems.web.ui.components.SiteFilter
import rw.rutongo.mmems.web.ui.components.StatusBadge

private data class MetricSpec(
    val label: String,
    val value: String,
    val detail: String?,
    val accent: Color,
)

@Composable
fun DashboardScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    val role = state.me?.role
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageHeader(
            title =
                when (role) {
                    Role.GENERAL_MANAGER -> "General management dashboard"
                    Role.MANAGER -> "Management dashboard"
                    else -> "Operations dashboard"
                },
            subtitle = state.dashboard?.let { "${it.from.take(10)} to ${it.to.take(10)}" } ?: "Live operational indicators",
            busy = state.dashboardBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = {
                if (WebTab.SITES.isVisibleFor(role ?: Role.MECHANIC) || role == Role.SUPERVISOR) {
                    SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) }
                }
            },
        )
        val dashboard = state.dashboard
        when {
            dashboard == null && state.dashboardBusy -> LoadingBlock("Loading dashboard…")
            dashboard == null -> EmptyState("Dashboard data is not available.")
            else -> DashboardContent(dashboard)
        }
    }
}

@Composable
private fun DashboardContent(dashboard: DashboardDto) {
    val equipment = dashboard.equipment
    val inspections = dashboard.inspections
    val breakdowns = dashboard.breakdowns
    val maintenance = dashboard.maintenance
    val parts = dashboard.parts
    val reports = dashboard.reports
    val metrics =
        listOf(
            MetricSpec("Equipment", equipment.total.toString(), "${equipment.operational} operational", SuccessGreen),
            MetricSpec("Down", equipment.down.toString(), "${equipment.underRepair} under repair", DangerRed),
            MetricSpec("Availability", "${equipment.operational * 100 / equipment.total.coerceAtLeast(1)}%", "Current fleet", InfoBlue),
            MetricSpec("Inspections", "${inspections.completed}/${inspections.expected}", "${inspections.missing} missing", WarningAmber),
            MetricSpec("Open breakdowns", breakdowns.open.toString(), "${breakdowns.downtimeMinutes} downtime min", DangerRed),
            MetricSpec("Maintenance", maintenance.recorded.toString(), "${maintenance.overdue} overdue · ${maintenance.dueSoon} due soon", WarningAmber),
            MetricSpec("Parts", parts.openRequests.toString(), "${parts.lowStockParts} low stock", InfoBlue),
            MetricSpec("Reports", reports.awaitingApproval.toString(), "${reports.approved} approved", SuccessGreen),
            MetricSpec("Inspection rate", "${inspections.completionPercent.toInt()}%", "${inspections.criticalFailure} critical failures", SuccessGreen),
        )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { metric ->
                    MetricCard(
                        label = metric.label,
                        value = metric.value,
                        detail = metric.detail,
                        accent = metric.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        dashboard.costs?.let { costs ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Labour cost", "${costs.currency} ${costs.labourCost}", "Period total", WarningAmber, Modifier.weight(1f))
                MetricCard("Parts cost", "${costs.currency} ${costs.partsCost}", "Period total", InfoBlue, Modifier.weight(1f))
                MetricCard("Total cost", "${costs.currency} ${costs.totalCost}", "Period total", SuccessGreen, Modifier.weight(1f))
            }
        }
    }
    val rows = dashboard.comparisonRows()
    if (rows.isNotEmpty() && dashboard.kind != rw.rutongo.mmems.shared.enums.DashboardKind.MECHANIC) {
        Spacer(Modifier.height(6.dp))
        SectionTitle("Site comparison", "Equipment, inspections, breakdowns and approved reports by site")
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                ComparisonHeader()
                rows.forEach { row ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    ComparisonRow(
                        site = "${row.siteCode} · ${row.siteName}",
                        equipment = "${row.operational}/${row.totalEquipment}",
                        availability = "${row.availabilityPercent.toInt()}%",
                        down = row.down.toString(),
                        breakdowns = row.openBreakdowns.toString(),
                        missing = row.missingInspections.toString(),
                        reports = row.approvedReports.toString(),
                        availabilityColor =
                            if (row.availabilityPercent >= 80) {
                                SuccessGreen
                            } else if (row.availabilityPercent >= 50) {
                                WarningAmber
                            } else {
                                DangerRed
                            },
                    )
                }
            }
        }
    }
}

private val siteWidth = 240.dp
private val metricWidth = 112.dp

@Composable
private fun ComparisonHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Site", siteWidth)
        HeaderCell("Operational", metricWidth)
        HeaderCell("Availability", metricWidth)
        HeaderCell("Down", metricWidth)
        HeaderCell("Breakdowns", metricWidth)
        HeaderCell("Missing insp.", metricWidth)
        HeaderCell("Reports approved", metricWidth)
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
) {
    Text(
        text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ComparisonRow(
    site: String,
    equipment: String,
    availability: String,
    down: String,
    breakdowns: String,
    missing: String,
    reports: String,
    availabilityColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(site, modifier = Modifier.width(siteWidth), fontWeight = FontWeight.Medium)
        Text(equipment, modifier = Modifier.width(metricWidth))
        Box(modifier = Modifier.width(metricWidth)) { StatusBadge(availability, Modifier.background(availabilityColor.copy(alpha = 0.12f))) }
        Text(down, modifier = Modifier.width(metricWidth))
        Text(breakdowns, modifier = Modifier.width(metricWidth))
        Text(missing, modifier = Modifier.width(metricWidth))
        Text(reports, modifier = Modifier.width(metricWidth))
    }
}
