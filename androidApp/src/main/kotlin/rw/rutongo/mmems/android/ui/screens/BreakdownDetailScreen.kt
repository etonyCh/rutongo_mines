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
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.FieldLabel
import rw.rutongo.mmems.android.ui.components.SectionCard
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.shared.api.BreakdownTransitionDto
import rw.rutongo.mmems.shared.enums.BreakdownState

private fun nextBreakdownStates(state: String): List<BreakdownState> =
    when (state) {
        BreakdownState.REPORTED.name ->
            listOf(BreakdownState.DIAGNOSING, BreakdownState.AWAITING_PARTS, BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED)
        BreakdownState.DIAGNOSING.name -> listOf(BreakdownState.AWAITING_PARTS, BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED)
        BreakdownState.AWAITING_PARTS.name -> listOf(BreakdownState.UNDER_REPAIR, BreakdownState.RESOLVED)
        BreakdownState.UNDER_REPAIR.name -> listOf(BreakdownState.RESOLVED)
        BreakdownState.RESOLVED.name -> listOf(BreakdownState.CLOSED)
        else -> emptyList()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownDetailScreen(
    container: AppContainer,
    breakdownId: String,
    onBack: () -> Unit,
) {
    val breakdown by container.store.breakdownFlow(breakdownId).collectAsStateWithLifecycle(null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Breakdown repair") },
                navigationIcon = { OutlinedButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val current = breakdown
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val allowed = nextBreakdownStates(current.state)
        var target by remember(breakdownId) { mutableStateOf<BreakdownState?>(null) }
        var actionTaken by remember(breakdownId) { mutableStateOf("") }
        var partsRequired by remember(breakdownId) { mutableStateOf("") }
        var remarks by remember(breakdownId) { mutableStateOf("") }
        var queued by remember { mutableStateOf(false) }
        LaunchedEffect(current.state) {
            if (target !in allowed) target = allowed.firstOrNull()
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(current.equipment_code ?: current.equipment_id, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${current.category} · started ${current.breakdown_start.take(16).replace('T', ' ')}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusPill(current.state)
            SectionCard("Reported problem") {
                Text(current.problem)
                current.action_taken?.let { Text("Action: $it", style = MaterialTheme.typography.bodySmall) }
                current.parts_required?.let { Text("Parts: $it", style = MaterialTheme.typography.bodySmall) }
                current.remarks?.let { Text("Remarks: $it", style = MaterialTheme.typography.bodySmall) }
            }
            SectionCard("Repair timeline", subtitle = "Stored as immutable server events with optimistic version checks.") {
                Text("Breakdown start: ${current.breakdown_start.take(16).replace('T', ' ')}")
                Text("Repair start: ${current.repair_start?.take(16)?.replace('T', ' ') ?: "not started"}")
                Text("Repair end: ${current.repair_end?.take(16)?.replace('T', ' ') ?: "open"}")
                Text("${current.defect_count} linked defect(s) · ${current.photo_count} photo(s)", style = MaterialTheme.typography.bodySmall)
            }
            current.pending_state?.let { pending ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (pending == "CREATE") "Waiting to send this report" else "Repair update waiting to sync",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "The device keeps the full operation and retries it with WorkManager. Do not sign out until it reaches the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (current.pending_state == "CREATE") return@Column
            if (allowed.isEmpty()) {
                Text("This breakdown is closed. Its history remains available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            FieldLabel("Next repair state")
            allowed.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { state ->
                        FilterChip(
                            selected = target == state,
                            onClick = { target = state },
                            label = { Text(state.name.lowercase().replace('_', ' ')) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = actionTaken,
                onValueChange = { actionTaken = it },
                label = { Text("Action taken") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = partsRequired,
                onValueChange = { partsRequired = it },
                label = { Text("Parts required") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = remarks,
                onValueChange = { remarks = it },
                label = { Text("Remarks") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (queued) {
                Text(
                    "Repair update queued. It will use If-Match version ${current.version} when it syncs.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = {
                    val state = target ?: return@Button
                    container.store.enqueueBreakdownTransition(
                        breakdown = current,
                        transition =
                            BreakdownTransitionDto(
                                state = state,
                                actionTaken = actionTaken,
                                partsRequired = partsRequired,
                                remarks = remarks,
                            ),
                    )
                    SyncWorker.enqueueNow(container.appContext)
                    queued = true
                    actionTaken = ""
                    partsRequired = ""
                    remarks = ""
                },
                enabled = target != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("QUEUE REPAIR UPDATE") }
        }
    }
}
