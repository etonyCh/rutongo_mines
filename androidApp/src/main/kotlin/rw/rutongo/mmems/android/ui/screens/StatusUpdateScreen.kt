package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.data.LocalStore
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.FormLabel
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.components.SpecTextField
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.android.ui.components.statusColor
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
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
    val scope = rememberCoroutineScope()
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
                title = { Text("Update equipment status", style = SpecTypography.SectionTitle) },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = SpecColors.Background,
                        titleContentColor = SpecColors.Text,
                        navigationIconContentColor = SpecColors.Action,
                    ),
            )
        },
    ) { padding ->
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                CircularProgressIndicator(color = SpecColors.Action)
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpecSpacing.PhoneMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${current.equipment_code} · ${current.name}", style = SpecTypography.ScreenTitle)
                Text("${current.site_name} · ${current.tunnel_name ?: "Not recorded"} · version ${current.version}", style = SpecTypography.BodySmall)
                StatusPill(current.operational_status)
            }
            SectionDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormLabel("New status")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("OPERATIONAL", "UNDER_REPAIR", "DOWN").forEach { option ->
                        val selected = status == option
                        val color = statusColor(option)
                        Button(
                            onClick = { status = option },
                            modifier = Modifier.height(SpecSpacing.MinimumTouch).weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = if (selected) color else SpecColors.Background,
                                    contentColor = if (selected) Color.White else color,
                                ),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, color),
                        ) {
                            Text(option.replace('_', ' '), style = SpecTypography.Label)
                        }
                    }
                }
            }
            TextButton(onClick = onReportBreakdown, modifier = Modifier.fillMaxWidth()) { Text("REPORT BREAKDOWN") }
            if (status != "OPERATIONAL") {
                SpecTextField(problem, { problem = it }, "Problem", required = true, minLines = 2)
                SpecTextField(action, { action = it }, "Action taken", minLines = 2)
                SpecTextField(parts, { parts = it }, "Parts required", singleLine = true)
            }
            SpecTextField(remarks, { remarks = it }, "Remarks", minLines = 2)
            SpecTextField(overrideReason, { overrideReason = it }, "Override reason", minLines = 2)
            error?.let { Text(it, color = SpecColors.ErrorText, style = SpecTypography.BodySmall) }
            if (queued) {
                Text("Queued locally. It will be sent by the sync worker.", color = SpecColors.Action, style = SpecTypography.BodySmall)
            }
            PrimaryButton(
                text = "QUEUE STATUS UPDATE",
                onClick = {
                    if (status != "OPERATIONAL" && problem.isBlank()) {
                        error = "Describe the problem before recording ${status.replace('_', ' ')}."
                        return@PrimaryButton
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
                        delay(700)
                        onDone()
                    }
                },
            )
            Text(
                "Date, time and user name are recorded automatically. Status events are append-only; a late offline event does not replace a newer server status.",
                style = SpecTypography.BodySmall,
            )
        }
    }
}
