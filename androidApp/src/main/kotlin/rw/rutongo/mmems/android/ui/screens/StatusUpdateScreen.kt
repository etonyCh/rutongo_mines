package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.data.LocalStore
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.FieldLabel
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.shared.api.StatusUpdateDto
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusUpdateScreen(
    container: AppContainer,
    equipmentId: String,
    onDone: () -> Unit,
    onReportBreakdown: () -> Unit,
) {
    val equipment by container.store.equipmentFlow(equipmentId).collectAsStateWithLifecycle(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = equipment
    var status by remember(current?.id) { mutableStateOf(current?.operational_status?.takeIf { it in setOf("OPERATIONAL", "UNDER_REPAIR", "DOWN") } ?: "OPERATIONAL") }
    var problem by remember(current?.id) { mutableStateOf("") }
    var action by remember(current?.id) { mutableStateOf("") }
    var parts by remember(current?.id) { mutableStateOf("") }
    var remarks by remember(current?.id) { mutableStateOf("") }
    var overrideReason by remember(current?.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record status") },
                navigationIcon = { OutlinedButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(current.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${current.equipment_code} · ${current.site_name} · version ${current.version}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusPill(current.operational_status)
            OutlinedButton(onClick = onReportBreakdown, modifier = Modifier.fillMaxWidth()) { Text("REPORT BREAKDOWN") }
            FieldLabel("New status")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("OPERATIONAL", "UNDER_REPAIR", "DOWN").forEach {
                    FilterChip(
                        selected = status == it,
                        onClick = { status = it },
                        label = { Text(it.replace('_', ' ')) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (status != "OPERATIONAL") {
                OutlinedTextField(
                    value = problem,
                    onValueChange = { problem = it },
                    label = { Text("Problem (required)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    label = { Text("Action taken") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = parts,
                    onValueChange = { parts = it },
                    label = { Text("Parts required") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = remarks,
                onValueChange = { remarks = it },
                label = { Text("Remarks") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = overrideReason,
                onValueChange = { overrideReason = it },
                label = { Text("Override reason (only if contradicting an automatic safety rule)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (queued) {
                Text("Queued locally. It will be sent by the sync worker.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = {
                    if (status != "OPERATIONAL" && problem.isBlank()) {
                        error = "Describe the problem before recording $status."
                        return@Button
                    }
                    val clientUuid = UUID.randomUUID().toString()
                    val payload =
                        StatusUpdateDto(
                            status = status,
                            problem = problem.ifBlank { null },
                            actionTaken = action.ifBlank { null },
                            partsRequired = parts.ifBlank { null },
                            remarks = remarks.ifBlank { null },
                            occurredAt = Instant.now().toString(),
                            overrideReason = overrideReason.ifBlank { null },
                        )
                    container.store.enqueueOutbox(
                        clientUuid = clientUuid,
                        kind = "STATUS",
                        equipmentId = current.id,
                        payload = LocalStore.json.encodeToString(StatusUpdateDto.serializer(), payload),
                        ifMatch = current.version,
                    )
                    SyncWorker.enqueueNow(container.appContext)
                    queued = true
                    error = null
                    scope.launch {
                        kotlinx.coroutines.delay(700)
                        onDone()
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
            ) { Text("QUEUE STATUS UPDATE") }
            Text(
                "Status events are append-only. A late offline event does not replace a newer status on the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
