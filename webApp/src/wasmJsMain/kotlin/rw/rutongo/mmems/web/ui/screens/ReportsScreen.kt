@file:Suppress("MagicNumber", "LongMethod", "TooManyFunctions")

package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.enums.ReportPeriod
import rw.rutongo.mmems.shared.enums.ReportState
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.data.todayDate
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
    var showCreate by remember { mutableStateOf(false) }
    val role = state.me?.role
    val allReports = state.reports.orEmpty()
    val reports = allReports.filter { filter == "All" || it.state.name == filter }
    val selected = allReports.firstOrNull { it.id == selectedId }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = if (role == Role.SUPERVISOR) "Daily site report" else "Daily reports",
            subtitle = if (role == Role.SUPERVISOR) "Prepare, submit and track the site report" else "Site reports, approvals and PDF versions",
            busy = state.reportsBusy || state.reportActionBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (role == Role.SUPERVISOR) {
                        Button(onClick = { showCreate = true }) { Text("Prepare report") }
                    }
                    SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) }
                }
            },
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
                        item { ReportDetail(report, state, onIntent) }
                    }
                }
        }
    }
    if (showCreate) {
        CreateReportDialog(
            sites = state.sites,
            defaultSiteId = state.selectedSiteId,
            busy = state.reportActionBusy,
            onDismiss = { showCreate = false },
            onCreate = {
                showCreate = false
                onIntent(it)
            },
        )
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
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    var showApprove by remember(report.id, report.versionNo) { mutableStateOf(false) }
    var showReturn by remember(report.id, report.versionNo) { mutableStateOf(false) }
    val me = state.me
    val canApprove = me?.role == Role.MANAGER && report.state in setOf(ReportState.SUBMITTED, ReportState.RESUBMITTED)
    val canSubmit = me?.role == Role.SUPERVISOR && report.preparedBy == me.id && report.state in setOf(ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION)
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onIntent(WebIntent.DownloadReportPdf(report.id, report.versionNo)) }) {
                    Text("Download PDF v${report.versionNo}")
                }
                if (canSubmit) {
                    Button(enabled = !state.reportActionBusy, onClick = { onIntent(WebIntent.SubmitReport(report.id, report.versionNo)) }) {
                        Text("Submit for approval")
                    }
                }
                if (canApprove) {
                    Button(enabled = !state.reportActionBusy, onClick = { showApprove = true }) { Text("Approve") }
                    OutlinedButton(enabled = !state.reportActionBusy, onClick = { showReturn = true }) { Text("Return") }
                }
            }
        }
    }
    if (showApprove) {
        ApproveDialog(
            report = report,
            busy = state.reportActionBusy,
            onDismiss = { showApprove = false },
            onApprove = { comment ->
                showApprove = false
                onIntent(WebIntent.ApproveReport(report.id, report.versionNo, comment))
            },
        )
    }
    if (showReturn) {
        ReturnDialog(
            report = report,
            busy = state.reportActionBusy,
            onDismiss = { showReturn = false },
            onReturn = { reason ->
                showReturn = false
                onIntent(WebIntent.ReturnReport(report.id, report.versionNo, reason))
            },
        )
    }
}

@Composable
private fun ApproveDialog(
    report: ReportViewDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApprove: (String?) -> Unit,
) {
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Approve ${report.reportRef}") },
        text = {
            Column(modifier = Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Approval confirms the report totals and narrative for version ${report.versionNo}.")
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Approval comment (optional)") },
                    minLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = { onApprove(comment) }) { Text("Approve") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReturnDialog(
    report: ReportViewDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReturn: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Return ${report.reportRef}") },
        text = {
            Column(modifier = Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The supervisor must receive a clear correction reason.")
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Correction reason") },
                    minLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = !busy && reason.isNotBlank(), onClick = { onReturn(reason) }) { Text("Return") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateReportDialog(
    sites: List<SiteDto>,
    defaultSiteId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (WebIntent.CreateReport) -> Unit,
) {
    var siteId by remember(sites, defaultSiteId) { mutableStateOf(defaultSiteId ?: sites.firstOrNull()?.id.orEmpty()) }
    var reportDate by remember { mutableStateOf(todayDate()) }
    var period by remember { mutableStateOf(ReportPeriod.FULL_DAY) }
    var siteMenu by remember { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    var executiveSummary by remember { mutableStateOf("") }
    var safetyObservations by remember { mutableStateOf("") }
    var maintenanceSummary by remember { mutableStateOf("") }
    var recommendations by remember { mutableStateOf("") }
    val siteName = sites.firstOrNull { it.id == siteId }?.let { "${it.code} · ${it.name}" } ?: "Select a site"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prepare daily site report") },
        text = {
            Column(modifier = Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Site", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { siteMenu = true }, enabled = !busy) { Text(siteName) }
                        DropdownMenu(expanded = siteMenu, onDismissRequest = { siteMenu = false }) {
                            sites.forEach { site ->
                                DropdownMenuItem(
                                    text = { Text("${site.code} · ${site.name}") },
                                    onClick = {
                                        siteId = site.id
                                        siteMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Period", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { periodMenu = true }, enabled = !busy) { Text(period.name.replace('_', ' ')) }
                        DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                            ReportPeriod.entries.forEach { value ->
                                DropdownMenuItem(
                                    text = { Text(value.name.replace('_', ' ')) },
                                    onClick = {
                                        period = value
                                        periodMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = reportDate,
                    onValueChange = { reportDate = it },
                    label = { Text("Report date (YYYY-MM-DD)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = executiveSummary,
                    onValueChange = { executiveSummary = it },
                    label = { Text("Executive summary") },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = safetyObservations,
                    onValueChange = { safetyObservations = it },
                    label = { Text("Safety observations") },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maintenanceSummary,
                    onValueChange = { maintenanceSummary = it },
                    label = { Text("Maintenance summary") },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = recommendations,
                    onValueChange = { recommendations = it },
                    label = { Text("Recommendations") },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && siteId.isNotBlank() && reportDate.isNotBlank(),
                onClick = {
                    onCreate(
                        WebIntent.CreateReport(
                            siteId = siteId,
                            reportDate = reportDate,
                            period = period,
                            executiveSummary = executiveSummary,
                            safetyObservations = safetyObservations,
                            maintenanceSummary = maintenanceSummary,
                            recommendations = recommendations,
                            remarks = null,
                        ),
                    )
                },
            ) { Text("Create draft") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
