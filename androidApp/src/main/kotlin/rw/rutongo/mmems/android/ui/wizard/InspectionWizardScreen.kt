package rw.rutongo.mmems.android.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.camera.CameraCaptureScreen
import rw.rutongo.mmems.android.ui.components.FieldLabel
import rw.rutongo.mmems.android.ui.components.SectionCard
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.android.ui.theme.Amber
import rw.rutongo.mmems.android.ui.theme.Green
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.Severity
import rw.rutongo.mmems.shared.inspection.DraftAnswer
import rw.rutongo.mmems.shared.inspection.InspectionDraft
import rw.rutongo.mmems.shared.inspection.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionWizardScreen(
    viewModel: WizardViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val loadError = state.loadError
    if (loadError != null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(loadError, color = MaterialTheme.colorScheme.error)
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }
    if (draft == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("Preparing the offline inspection…")
        }
        return
    }
    if (state.capture != null) {
        CameraCaptureScreen(
            documentName = draft.equipmentCode,
            onClose = viewModel::cancelPhoto,
            onCaptured = viewModel::completePhoto,
        )
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(draft.equipmentName, fontWeight = FontWeight.Bold)
                        Text(stepTitle(state.step), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { OutlinedButton(onClick = { if (state.step == WizardStep.CONTEXT) onBack() else viewModel.previous() }) { Text("Back") } },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (state.queued) {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("BACK TO MY INSPECTIONS") }
                } else if (state.step == WizardStep.SIGN) {
                    val onlySignatureMissing =
                        state.validation
                            ?.errors
                            ?.keys
                            ?.all { it == "attestation" } == true
                    Button(
                        onClick = {
                            viewModel.sign(draft.operatorName, draft.operatorEid)
                            viewModel.submit()
                        },
                        enabled = !state.submitting && onlySignatureMissing && draft.operatorName.isNotBlank() && draft.operatorEid.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (state.submitting) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("SIGN AND SUBMIT")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { if (state.step == WizardStep.CONTEXT) onBack() else viewModel.previous() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("BACK") }
                        Button(onClick = viewModel::next, modifier = Modifier.weight(1f).height(56.dp)) { Text("NEXT") }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepProgress(state.step)
            state.message?.let {
                Text(it, color = if (state.queued) Green else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            when (state.step) {
                WizardStep.CONTEXT -> ContextStep(draft)
                WizardStep.START_HOURS -> StartHoursStep(draft, viewModel)
                WizardStep.SAFETY -> ChecklistStep(draft, "SAFETY", state, viewModel)
                WizardStep.MECHANICAL -> ChecklistStep(draft, "MECHANICAL", state, viewModel)
                WizardStep.DEFECTS -> DefectsStep(draft, viewModel)
                WizardStep.PHOTOS -> PhotosStep(draft, state, viewModel)
                WizardStep.END_HOURS -> EndHoursStep(draft, state, viewModel)
                WizardStep.REVIEW -> ReviewStep(draft, state)
                WizardStep.SIGN -> SignStep(draft, state, viewModel)
            }
        }
    }
    if (state.queued) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Inspection queued") },
            text = { Text("The inspection and its photos are stored encrypted on this device. WorkManager will retry until the server confirms receipt.") },
            confirmButton = { TextButton(onClick = onBack) { Text("CONTINUE") } },
        )
    }
}

private fun stepTitle(step: WizardStep) =
    when (step) {
        WizardStep.CONTEXT -> "Context"
        WizardStep.START_HOURS -> "Start hours"
        WizardStep.SAFETY -> "Safety checklist"
        WizardStep.MECHANICAL -> "Mechanical checklist"
        WizardStep.DEFECTS -> "Defects"
        WizardStep.PHOTOS -> "Photo evidence"
        WizardStep.END_HOURS -> "End hours"
        WizardStep.REVIEW -> "Review"
        WizardStep.SIGN -> "Sign and submit"
    }

@Composable
private fun StepProgress(step: WizardStep) {
    val index = WizardStep.entries.indexOf(step)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("STEP ${index + 1} OF ${WizardStep.entries.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            WizardStep.entries.forEach {
                Column(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(if (WizardStep.entries.indexOf(it) <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                ) {}
            }
        }
    }
}

@Composable
private fun ContextStep(draft: InspectionDraft) {
    SectionCard("Inspection context", subtitle = "Pre-filled from the queued expectation.") {
        FieldLabel("Equipment")
        Text("${draft.equipmentName} · ${draft.equipmentCode}")
        FieldLabel("Site")
        Text(draft.siteName)
        FieldLabel("Shift and report date")
        Text("${draft.shiftCode} · ${draft.reportDate}")
        FieldLabel("Operator")
        Text("${draft.operatorName} · ${draft.operatorEid}")
        if (draft.expectationId == null) {
            Text("This draft is not linked to an open expectation.", color = Amber)
        }
        Text("Client reference ${draft.clientUuid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StartHoursStep(
    draft: InspectionDraft,
    viewModel: WizardViewModel,
) {
    SectionCard("Starting condition", subtitle = "Enter the meter and fuel at the start of the inspection.") {
        OutlinedTextField(
            value = draft.engineHoursStart?.toString().orEmpty(),
            onValueChange = viewModel::startHours,
            label = { Text("Engine hours at start") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Last recorded reading: ${draft.lastEngineHours?.let { "%.1f".format(it) } ?: "not available"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.fuelStartPercent?.toString().orEmpty(),
            onValueChange = viewModel::fuelStart,
            label = { Text("Fuel at start (%)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChecklistStep(
    draft: InspectionDraft,
    category: String,
    state: WizardUiState,
    viewModel: WizardViewModel,
) {
    val items = draft.answers.filter { it.category == category }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (category == "SAFETY") "Safety-critical checks" else "Mechanical checks",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        items.forEach { item ->
            AnswerCard(item, state, viewModel)
        }
    }
}

@Composable
private fun AnswerCard(
    item: DraftAnswer,
    state: WizardUiState,
    viewModel: WizardViewModel,
) {
    val errors =
        state.validation
            ?.errors
            .orEmpty()
            .filterKeys { it.startsWith("answers.${item.code}") }
    SectionCard(item.label, subtitle = if (item.safetyCritical) "SAFETY CRITICAL" else null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Answer.entries.forEach { answer ->
                FilterChip(
                    selected = item.answer == answer,
                    onClick = { viewModel.answer(item.itemId, answer) },
                    label = { Text(answer.name) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (item.answer == Answer.NA && item.naReasonRequired) {
            OutlinedTextField(
                value = item.naReason.orEmpty(),
                onValueChange = { viewModel.naReason(item.itemId, it) },
                label = { Text("Reason for N/A") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (item.answer == Answer.FAIL) {
            FieldLabel("Severity")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Severity.entries.forEach { severity ->
                    FilterChip(
                        selected = item.severity == severity,
                        onClick = { viewModel.failure(item.itemId, severity = severity) },
                        label = { Text(severity.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OutlinedTextField(
                value = item.defectDescription.orEmpty(),
                onValueChange = { viewModel.failure(item.itemId, description = it) },
                label = { Text("Defect description (required)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = item.immediateAction.orEmpty(),
                onValueChange = { viewModel.failure(item.itemId, immediateAction = it) },
                label = { Text("Immediate action taken") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            if (item.safetyCritical || item.photoRequiredOnFail) {
                Text(
                    if (item.photoIds.isEmpty()) "A photo is required for this failure." else "${item.photoIds.size} photo(s) attached",
                    color = if (item.photoIds.isEmpty()) MaterialTheme.colorScheme.error else Green,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(onClick = { viewModel.requestPhoto(item.itemId) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Text("  TAKE PHOTO")
            }
        }
        errors.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DefectsStep(
    draft: InspectionDraft,
    viewModel: WizardViewModel,
) {
    val failed = draft.answers.filter { it.answer == Answer.FAIL }
    if (failed.isEmpty()) {
        SectionCard("No defects recorded", subtitle = "Every answered item passed or was marked N/A.") {
            Icon(Icons.Default.Check, contentDescription = null, tint = Green)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${failed.size} defect(s) recorded", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        failed.forEach { item ->
            SectionCard(item.label, subtitle = item.category) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Severity.entries.forEach { severity ->
                        FilterChip(
                            selected = item.severity == severity,
                            onClick = { viewModel.failure(item.itemId, severity = severity) },
                            label = { Text(severity.name) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = item.defectDescription.orEmpty(),
                    onValueChange = { viewModel.failure(item.itemId, description = it) },
                    label = { Text("Description") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = item.immediateAction.orEmpty(),
                    onValueChange = { viewModel.failure(item.itemId, immediateAction = it) },
                    label = { Text("Immediate action") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Photos: ${item.photoIds.size}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PhotosStep(
    draft: InspectionDraft,
    state: WizardUiState,
    viewModel: WizardViewModel,
) {
    val failures = draft.answers.filter { it.answer == Answer.FAIL }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Photo evidence", subtitle = "Camera images are compressed to about 1600 px, stored encrypted and uploaded separately.") {
            Text("${draft.answers.sumOf { it.photoIds.size }} photo reference(s) attached")
            Text("${state.photoCount} photo(s) waiting in the upload queue", color = if (state.photoCount > 0) Amber else Green)
        }
        failures.forEach { item ->
            SectionCard(item.label, subtitle = if (item.safetyCritical || item.photoRequiredOnFail) "PHOTO REQUIRED" else "OPTIONAL PHOTO") {
                Text("${item.photoIds.size} attached")
                OutlinedButton(onClick = { viewModel.requestPhoto(item.itemId) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Text("  TAKE PHOTO")
                }
            }
        }
        if (failures.isEmpty()) {
            Text("No failed item needs a photo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EndHoursStep(
    draft: InspectionDraft,
    state: WizardUiState,
    viewModel: WizardViewModel,
) {
    val start = draft.engineHoursStart
    val end = draft.engineHoursEnd
    val worked = if (start != null && end != null && end >= start) end - start else null
    SectionCard("Ending condition", subtitle = "Close the meter reading before review.") {
        OutlinedTextField(
            value = draft.engineHoursEnd?.toString().orEmpty(),
            onValueChange = viewModel::endHours,
            label = { Text("Engine hours at end") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.fuelAddedLitres?.toString().orEmpty(),
            onValueChange = viewModel::fuelAdded,
            label = { Text("Fuel added (litres)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Working hours: ${worked?.let { "%.1f".format(it) } ?: "not yet valid"}", fontWeight = FontWeight.Bold)
        Text("Hours status: ${state.validation?.hoursStatus?.name ?: "NOT EVALUATED"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.validation?.warnings?.forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Amber)
                Text(it, color = Amber)
            }
        }
    }
}

@Composable
private fun ReviewStep(
    draft: InspectionDraft,
    state: WizardUiState,
) {
    val passed = draft.answers.count { it.answer == Answer.PASS }
    val failed = draft.answers.count { it.answer == Answer.FAIL }
    val na = draft.answers.count { it.answer == Answer.NA }
    val critical = draft.answers.any { it.answer == Answer.FAIL && it.severity == Severity.CRITICAL }
    SectionCard("Review", subtitle = "The shared validator applies exactly the same rules as the server.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text("$passed pass", color = Green, fontWeight = FontWeight.Bold)
            Text("$failed fail", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Text("$na N/A", color = Amber, fontWeight = FontWeight.Bold)
        }
        Text(
            if (critical) {
                "Critical failure — the server may set the equipment DOWN."
            } else if (failed > 0) {
                "Result: completed with defects."
            } else {
                "Result: completed."
            },
            fontWeight = FontWeight.Bold,
        )
        StatusPill(
            if (critical) {
                "DOWN"
            } else if (failed > 0) {
                "OPERATIONAL_WITH_DEFECTS"
            } else {
                "OPERATIONAL"
            },
        )
        Text("Engine hours: ${draft.engineHoursStart?.toString() ?: "—"} → ${draft.engineHoursEnd?.toString() ?: "—"}")
        Text("Warnings: ${state.validation?.warnings?.joinToString().orEmpty().ifBlank { "none" }}")
        val errors = state.validation?.errors.orEmpty()
        if (errors.isNotEmpty()) {
            Text("${errors.size} requirement(s) still missing:", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            errors.values.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SignStep(
    draft: InspectionDraft,
    state: WizardUiState,
    viewModel: WizardViewModel,
) {
    SectionCard("Sign the inspection", subtitle = "The signature confirms the checklist and readings recorded above.") {
        OutlinedTextField(
            value = draft.operatorName,
            onValueChange = { viewModel.operator(it, draft.operatorEid) },
            label = { Text("Signer name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.operatorEid,
            onValueChange = { viewModel.operator(draft.operatorName, it) },
            label = { Text("Employee ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.attestation != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Green)
                Text("Attestation captured", color = Green, fontWeight = FontWeight.Bold)
            }
        }
        val remaining = state.validation?.errors.orEmpty()
        if (remaining.isNotEmpty()) {
            Text("Complete ${remaining.size} outstanding requirement(s) before signing.", color = MaterialTheme.colorScheme.error)
            remaining.values.take(5).forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
