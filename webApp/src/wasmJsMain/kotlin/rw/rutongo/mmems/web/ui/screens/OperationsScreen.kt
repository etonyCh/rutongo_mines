@file:Suppress("MagicNumber")

package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.data.ImportProfile
import rw.rutongo.mmems.web.data.ImportRow
import rw.rutongo.mmems.web.data.pickXlsxFile
import rw.rutongo.mmems.web.ui.SuccessGreen
import rw.rutongo.mmems.web.ui.WarningAmber
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SectionTitle
import rw.rutongo.mmems.web.ui.components.StatusBadge

@Composable
fun OperationsScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PageHeader(
            title = "Operations",
            subtitle = "Controlled workbook imports and dataset exports",
            busy = state.operationsBusy || state.importBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
        )
        ImportSection(state, onIntent)
        HorizontalDivider()
        ExportSection(state, onIntent)
    }
}

@Composable
private fun ImportSection(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    val profiles = state.profiles.orEmpty()
    val profile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    SectionTitle("Workbook import", "Preview an .xlsx workbook, then commit the validated rows")
    Spacer(Modifier.height(10.dp))
    if (profiles.isEmpty() && state.operationsBusy) {
        LoadingBlock("Loading import profiles…")
        return
    }
    if (profiles.isEmpty()) {
        EmptyState("No import profile is configured.")
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfilePicker(profiles, profile, state.operationsBusy) { selectedProfileId = it }
        OutlinedButton(
            onClick = {
                if (profile != null) {
                    pickXlsxFile { fileName, base64 -> onIntent(WebIntent.UploadImport(profile.id, fileName, base64)) }
                }
            },
            enabled = profile != null && !state.importBusy,
        ) { Text("Choose workbook") }
        state.importFileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    state.importPreview?.let { preview ->
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Batch ${preview.batch.id.take(8)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        StatusBadge(preview.batch.state)
                    }
                    Text("SHA-256 ${preview.batch.fileSha256.take(12)}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("New ${preview.batch.newCount}", color = SuccessGreen)
                    Text("Updated ${preview.batch.updatedCount}")
                    Text("Unchanged ${preview.batch.unchangedCount}")
                    Text("Rejected ${preview.batch.rejectedCount}", color = WarningAmber)
                }
                if (preview.batch.newEquipmentTypes.isNotEmpty()) {
                    Text("New equipment types: ${preview.batch.newEquipmentTypes.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onIntent(WebIntent.CommitImport) },
                        enabled = preview.batch.state != "COMMITTED" && !state.importBusy,
                    ) { Text(if (preview.batch.state == "COMMITTED") "Committed" else "Commit import") }
                    TextButton(onClick = { onIntent(WebIntent.ClearImport) }, enabled = !state.importBusy) { Text("Clear preview") }
                }
                if (preview.rows.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Preview rows", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    preview.rows.take(100).forEach { row -> ImportRowLine(row) }
                    if (preview.nextRowNumber != null) {
                        Text("More rows are available from the API import view.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<ImportProfile>,
    selected: ImportProfile?,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
            Text(selected?.let { "${it.name} (${it.kind})" } ?: "Select profile", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text("${profile.name} · ${profile.kind}") },
                    onClick = {
                        expanded = false
                        onSelect(profile.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportRowLine(row: ImportRow) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#${row.rowNumber}", modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
        Text(row.businessKey ?: "—", modifier = Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        StatusBadge(row.state)
        Text(
            row.rejectionReason ?: row.warnings.joinToString("; ").ifBlank { "—" },
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.rejectionReason != null) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportSection(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    SectionTitle("Dataset exports", "Exports are generated from the server and downloaded directly")
    Spacer(Modifier.height(10.dp))
    val exports = state.exports.orEmpty()
    when {
        exports.isEmpty() && state.operationsBusy -> LoadingBlock("Loading export datasets…")
        exports.isEmpty() -> EmptyState("No export datasets are available for your role.")
        else ->
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(exports, key = { it.dataset }) { dataset ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dataset.dataset.replace('-', ' ').replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                Text("Permission ${dataset.permission}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                dataset.formats.forEach { format ->
                                    OutlinedButton(onClick = { onIntent(WebIntent.DownloadExport(dataset.dataset, format.lowercase())) }) {
                                        Text(format.uppercase())
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
}
