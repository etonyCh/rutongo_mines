@file:Suppress("MagicNumber", "LongMethod")

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.api.DailyReportSnapshotDto
import rw.rutongo.mmems.shared.api.ReportNarrativeDto
import rw.rutongo.mmems.shared.api.ReportViewDto
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
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

private val filters = listOf("All", "SUBMITTED", "APPROVED", "RETURNED_FOR_CORRECTION", "DRAFT")

@Composable
fun ReportsScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    var filter by remember { mutableStateOf("All") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val allReports = state.reports.orEmpty()
    val reports = allReports.filter { filter == "All" || it.state.name == filter }
    val selected = allReports.firstOrNull { it.id == selectedId }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "Daily reports",
            subtitle = "Supervisor reports, approvals and PDF versions",
            busy = state.reportsBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = { SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) } },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { value ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value.replace('_', ' ')) })
            }
        }
        when {
            state.reports == null && state.reportsBusy -> LoadingBlock("Loading reports…")
            reports.isEmpty() -> EmptyState("No reports match the current filter.")
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(reports, key = { it.id }) { report ->
                        ReportCard(report, onView = { selectedId = report.id }, onPdf = { onIntent(WebIntent.DownloadReportPdf(report.id, report.versionNo)) })
                    }
                    selected?.let { report ->
                        item { ReportDetail(report, onIntent) }
                    }
                }
        }
    }
}

@Composable
private fun ReportCard(
    report: ReportViewDto,
    onView: () -> Unit,
    onPdf: () -> Unit,
) {
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
                    Text(report.reportRef, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusBadge(report.state.name)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${report.siteName} · ${report.reportDate} · ${report.period.name.replace('_', ' ')} · v${report.versionNo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Prepared by ${report.preparedByName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onView) { Text("View") }
            OutlinedButton(onClick = onPdf) { Text("PDF") }
        }
    }
}

@Composable
private fun ReportDetail(
    report: ReportViewDto,
    onIntent: (WebIntent) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("${report.reportRef} · ${report.siteName}", "Version ${report.versionNo} · ${report.state.name.replace('_', ' ')}")
            SnapshotMetrics(report.snapshot)
            HorizontalDivider()
            Narrative(report.narrative)
            if (report.approvedByName != null) {
                HorizontalDivider()
                Text(
                    "Approved by ${report.approvedByName} at ${report.approvedAt?.take(16)?.replace('T', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SuccessGreen,
                )
                report.approvalComment?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (report.returnedByName != null) {
                Text(
                    "Returned by ${report.returnedByName}: ${report.correctionReason ?: "correction requested"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
            }
            OutlinedButton(onClick = { onIntent(WebIntent.DownloadReportPdf(report.id, report.versionNo)) }) {
                Text("Download PDF v${report.versionNo}")
            }
        }
    }
}

@Composable
private fun SnapshotMetrics(snapshot: DailyReportSnapshotDto) {
    val summary = snapshot.summary
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("Equipment", summary.totalEquipment.toString(), "${summary.operational} operational", SuccessGreen, Modifier.weight(1f))
        MetricCard("Inspections", "${summary.inspectionsCompleted}/${summary.inspectionsCompleted + summary.inspectionsMissing}", "${summary.inspectionsMissing} missing", WarningAmber, Modifier.weight(1f))
        MetricCard("Breakdowns", summary.breakdownsReported.toString(), "${summary.breakdownsOpen} open", InfoBlue, Modifier.weight(1f))
        MetricCard("Maintenance", summary.maintenanceRecorded.toString(), "${summary.maintenanceOverdue} overdue", InfoBlue, Modifier.weight(1f))
    }
}

@Composable
private fun Narrative(narrative: ReportNarrativeDto) {
    val entries =
        listOf(
            "Executive summary" to narrative.executiveSummary,
            "Safety observations" to narrative.safetyObservations,
            "Maintenance summary" to narrative.maintenanceSummary,
            "Parts summary" to narrative.partsSummary,
            "Recommendations" to narrative.recommendations,
            "Remarks" to narrative.remarks,
        ).filter { !it.second.isNullOrBlank() }
    if (entries.isEmpty()) {
        Text("No narrative recorded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        entries.forEach { (label, value) ->
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(value.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
