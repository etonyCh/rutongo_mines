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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import rw.rutongo.mmems.shared.api.MaintenanceViewDto
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SiteFilter
import rw.rutongo.mmems.web.ui.components.StatusBadge

@Composable
fun MaintenanceScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            title = "Maintenance",
            subtitle = "Recorded maintenance and supervisor verification",
            busy = state.maintenanceBusy || state.maintenanceActionBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            site = state.siteName,
            trailing = { SiteFilter(state.sites, state.selectedSiteId) { onIntent(WebIntent.SelectSite(it)) } },
        )
        when {
            state.maintenance == null && state.maintenanceBusy -> LoadingBlock("Loading maintenance…")
            state.maintenance.isNullOrEmpty() -> EmptyState("No maintenance records for the current filter.")
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.maintenance.orEmpty(), key = { it.id }) { record ->
                        MaintenanceCard(record, state, onIntent)
                    }
                }
        }
    }
}

@Composable
private fun MaintenanceCard(
    record: MaintenanceViewDto,
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    val pending = record.verificationStatus == MaintenanceVerificationStatus.PENDING_VERIFICATION
    var action by remember(record.id) { mutableStateOf<MaintenanceAction?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${record.equipmentCode} · ${record.equipmentName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusBadge(record.verificationStatus.name)
            }
            Text(
                "${record.serviceTypeName} · ${record.performedAt.take(16).replace('T', ' ')} · ${record.technicianName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(record.workPerformed, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                record.cause?.let { Text("Cause: $it", style = MaterialTheme.typography.bodySmall) }
                record.downtimeMinutes?.let { Text("Downtime: $it min", style = MaterialTheme.typography.bodySmall) }
                record.notes?.let { Text("Notes: $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (pending && state.me?.role == Role.SUPERVISOR) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !state.maintenanceActionBusy,
                        onClick = { action = MaintenanceAction.VERIFY },
                    ) { Text("Verify") }
                    OutlinedButton(
                        enabled = !state.maintenanceActionBusy,
                        onClick = { action = MaintenanceAction.REJECT },
                    ) { Text("Reject") }
                    OutlinedButton(
                        enabled = !state.maintenanceActionBusy,
                        onClick = { action = MaintenanceAction.REQUEST_CORRECTION },
                    ) { Text("Request correction") }
                }
            }
        }
    }
    action?.let { selected ->
        MaintenanceActionDialog(
            action = selected,
            record = record,
            busy = state.maintenanceActionBusy,
            onDismiss = { action = null },
            onConfirm = { comment ->
                action = null
                onIntent(
                    when (selected) {
                        MaintenanceAction.VERIFY -> WebIntent.VerifyMaintenance(record.id, record.version, comment)
                        MaintenanceAction.REJECT -> WebIntent.RejectMaintenance(record.id, record.version, comment)
                        MaintenanceAction.REQUEST_CORRECTION -> WebIntent.RequestMaintenanceCorrection(record.id, record.version, comment)
                    },
                )
            },
        )
    }
}

@Composable
private fun MaintenanceActionDialog(
    action: MaintenanceAction,
    record: MaintenanceViewDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${action.title} ${record.equipmentCode}") },
        text = {
            Column(modifier = Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (action.commentRequired) {
                        "A reason is required so the technician knows what to correct."
                    } else {
                        "Add an optional comment for the verification record."
                    },
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(if (action.commentRequired) "Reason" else "Comment (optional)") },
                    minLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (!action.commentRequired || comment.isNotBlank()),
                onClick = { onConfirm(comment.trim().takeIf { it.isNotEmpty() }) },
            ) { Text(action.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class MaintenanceAction(
    val title: String,
    val confirmLabel: String,
    val commentRequired: Boolean,
) {
    VERIFY("Verify maintenance for", "Verify", false),
    REJECT("Reject maintenance for", "Reject", true),
    REQUEST_CORRECTION("Request correction for", "Request correction", true),
}
