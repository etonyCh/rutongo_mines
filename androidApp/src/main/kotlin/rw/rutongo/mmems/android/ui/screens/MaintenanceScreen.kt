package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.MaintenanceViewModel
import rw.rutongo.mmems.android.ui.components.BigCounter
import rw.rutongo.mmems.android.ui.components.DangerButton
import rw.rutongo.mmems.android.ui.components.FilterPill
import rw.rutongo.mmems.android.ui.components.FormLabel
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SecondaryButton
import rw.rutongo.mmems.android.ui.components.SectionCard
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.components.SpecTextField
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.MaintenanceCreateDto
import rw.rutongo.mmems.shared.api.MaintenanceTechnicianDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.ServiceTypeViewDto
import rw.rutongo.mmems.shared.enums.Role

private val maintenanceRoles = setOf(Role.MECHANIC, Role.SUPERVISOR)

@Composable
fun MaintenanceScreen(
    container: AppContainer,
    me: MeView,
) {
    val viewModel = remember(container) { MaintenanceViewModel(container) }
    val actionState by viewModel.state.collectAsStateWithLifecycle()
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    val serviceTypes by container.store.serviceTypes().collectAsStateWithLifecycle(emptyList())
    val technicians by container.store.technicians().collectAsStateWithLifecycle(emptyList())
    val records by container.store.maintenance().collectAsStateWithLifecycle(emptyList())
    val schedule by container.store.serviceSchedule().collectAsStateWithLifecycle(emptyList())
    val hours by container.store.workingHours().collectAsStateWithLifecycle(null)
    val queued by container.store.outboxForKind("MAINTENANCE_CREATE").collectAsStateWithLifecycle(emptyList())
    val queuedCount = queued.count { it.state == "PENDING" || it.state == "RETRY" }
    var tab by remember { mutableStateOf("schedule") }
    var showCreate by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Service and verification",
                title = "Maintenance",
                subtitle = "Service intervals, technician records and meter-hour summaries",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigCounter("Schedule", schedule.size.toString(), SpecColors.Action, Modifier.weight(1f))
                BigCounter("Pending", records.count { it.verificationStatus.name == "PENDING_VERIFICATION" }.toString(), SpecColors.UnderRepair, Modifier.weight(1f))
                BigCounter("Queued", queuedCount.toString(), SpecColors.Text, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilterPill("Schedule", tab == "schedule", { tab = "schedule" }, Modifier.weight(1f))
                FilterPill("Records", tab == "records", { tab = "records" }, Modifier.weight(1f))
                FilterPill("Hours", tab == "hours", { tab = "hours" }, Modifier.weight(1f))
            }
        }
        actionState.message?.let { item { Text(it, style = SpecTypography.BodySmall, color = SpecColors.Action) } }
        actionState.error?.let { item { Text(it, style = SpecTypography.BodySmall, color = SpecColors.ErrorText) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(if (actionState.refreshing) "Refreshing…" else "Refresh", viewModel::refresh, Modifier.weight(1f))
                if (me.role in maintenanceRoles) {
                    PrimaryButton(if (showCreate) "Close form" else "Record service", { showCreate = !showCreate }, Modifier.weight(1f))
                }
            }
        }
        if (showCreate && me.role in maintenanceRoles) {
            item {
                MaintenanceCreateCard(
                    equipment = equipment,
                    serviceTypes = serviceTypes,
                    technicians = technicians,
                    supervisorId = me.id.takeIf { me.role == Role.SUPERVISOR },
                    onQueue = { equipmentId, input ->
                        viewModel.create(equipmentId, input)
                        showCreate = false
                    },
                )
            }
        }
        when (tab) {
            "schedule" -> {
                if (schedule.isEmpty()) {
                    item { Text("No service intervals are cached yet. An administrator configures intervals on the server.", style = SpecTypography.BodySmall) }
                }
                items(schedule, key = { it.intervalId }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${item.equipmentCode} · ${item.equipmentName}", style = SpecTypography.Body, color = SpecColors.Text)
                                Text("${item.serviceTypeName} every ${item.intervalHours} h", style = SpecTypography.BodySmall)
                            }
                            StatusPill(item.status.name)
                        }
                        Text("Last verified ${item.lastVerified?.let { "%.1f".format(it) } ?: "Not recorded"} h · next due ${item.nextDue?.let { "%.1f".format(it) } ?: "Not recorded"} h", style = SpecTypography.BodySmall)
                        Text("Remaining ${item.remaining?.let { "%.1f".format(it) } ?: "Not recorded"} h · current ${item.current?.let { "%.1f".format(it) } ?: "Not recorded"} h", style = SpecTypography.BodySmall)
                        SectionDivider()
                    }
                }
            }
            "records" -> {
                if (records.isEmpty()) {
                    item { Text("No maintenance records are cached. Records you queue offline appear after the next sync.", style = SpecTypography.BodySmall) }
                }
                items(records, key = { it.id }) { record ->
                    MaintenanceRecordCard(record, canDecide = me.role == Role.SUPERVISOR, onDecide = viewModel::decide)
                }
            }
            else -> {
                item { BigCounter("Recorded hours", "%.1f".format(hours?.totalRecordedHours ?: 0.0), SpecColors.Action) }
                val groups = hours?.groups.orEmpty()
                if (groups.isEmpty()) {
                    item { Text("No working-hour entries are cached yet.", style = SpecTypography.BodySmall) }
                }
                items(groups, key = { it.key }) { group ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(group.equipmentName ?: group.siteName ?: group.shiftCode ?: group.reportDate ?: group.key, style = SpecTypography.Body)
                        Text("%.1f recorded hours across %d entries".format(group.recordedHours, group.entryCount), style = SpecTypography.BodySmall)
                        if (group.overlapEntries > 0 || group.missingReadings > 0) {
                            Text("${group.overlapEntries} overlap(s) · ${group.missingReadings} missing reading(s)", style = SpecTypography.BodySmall, color = SpecColors.ErrorText)
                        }
                        SectionDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCreateCard(
    equipment: List<rw.rutongo.mmems.android.db.Equipment>,
    serviceTypes: List<ServiceTypeViewDto>,
    technicians: List<MaintenanceTechnicianDto>,
    supervisorId: String?,
    onQueue: (String, MaintenanceCreateDto) -> Unit,
) {
    var equipmentId by remember { mutableStateOf<String?>(null) }
    var serviceTypeId by remember { mutableStateOf<String?>(null) }
    var technicianId by remember { mutableStateOf<String?>(null) }
    var workPerformed by remember { mutableStateOf("") }
    var cause by remember { mutableStateOf("") }
    var engineHours by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedEquipment = equipment.find { it.id == equipmentId }
    SectionCard("Record maintenance", subtitle = "Queues offline and sends with an idempotency key.") {
        Selector("Equipment", selectedEquipment?.let { "${it.equipment_code} · ${it.name}" }, equipment.map { it.id to "${it.equipment_code} · ${it.name}" }) { equipmentId = it }
        Selector("Service type", serviceTypes.find { it.id == serviceTypeId }?.name, serviceTypes.map { it.id to "${it.code} · ${it.name}" }) { serviceTypeId = it }
        Selector("Technician", technicians.find { it.id == technicianId }?.name, technicians.map { it.id to "${it.employeeId} · ${it.name}" }) { technicianId = it }
        SpecTextField(workPerformed, { workPerformed = it }, "Work performed", required = true, minLines = 2)
        SpecTextField(cause, { cause = it }, "Cause", singleLine = true)
        SpecTextField(engineHours, { engineHours = it }, "Engine hours", singleLine = true)
        SpecTextField(notes, { notes = it }, "Notes", minLines = 2)
        if (serviceTypes.isEmpty() || technicians.isEmpty()) {
            Text("Service types and technicians must be cached before recording work. Refresh when connected.", style = SpecTypography.BodySmall, color = SpecColors.ErrorText)
        }
        error?.let { Text(it, style = SpecTypography.BodySmall, color = SpecColors.ErrorText) }
        PrimaryButton(
            text = "QUEUE MAINTENANCE",
            onClick = {
                val equipmentValue = equipmentId
                val serviceValue = serviceTypeId
                val technicianValue = technicianId
                if (equipmentValue == null || serviceValue == null || technicianValue == null) {
                    error = "Choose the equipment, service type and technician."
                    return@PrimaryButton
                }
                if (workPerformed.isBlank()) {
                    error = "Describe the work performed."
                    return@PrimaryButton
                }
                val parsedHours = engineHours.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                if (engineHours.isNotBlank() && parsedHours == null) {
                    error = "Engine hours must be a number."
                    return@PrimaryButton
                }
                error = null
                onQueue(
                    equipmentValue,
                    MaintenanceCreateDto(
                        equipmentId = equipmentValue,
                        serviceTypeId = serviceValue,
                        engineHours = parsedHours,
                        workPerformed = workPerformed.trim(),
                        cause = cause.trim().ifBlank { null },
                        technicianId = technicianValue,
                        supervisorId = supervisorId,
                        notes = notes.trim().ifBlank { null },
                    ),
                )
            },
        )
    }
}

@Composable
private fun Selector(
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FormLabel(label)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value ?: "Select $label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(id)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun MaintenanceRecordCard(
    record: rw.rutongo.mmems.shared.api.MaintenanceViewDto,
    canDecide: Boolean,
    onDecide: (rw.rutongo.mmems.shared.api.MaintenanceViewDto, String, String?) -> Unit,
) {
    var decision by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${record.equipmentCode} · ${record.serviceTypeName}", style = SpecTypography.Body, color = SpecColors.Text)
                Text(record.performedAt.take(16).replace('T', ' '), style = SpecTypography.BodySmall)
            }
            StatusPill(record.verificationStatus.name)
        }
        Text(record.workPerformed, style = SpecTypography.BodySmall)
        Text("Technician ${record.technicianName} · ${record.replacedComponents.size} part(s) · ${record.downtimeMinutes ?: 0} min downtime", style = SpecTypography.BodySmall)
        val cost = record.totalCost
        Text(if (cost == null) "Cost hidden for your role" else "Total cost ${record.currency} $cost", style = SpecTypography.BodySmall)
        if (record.verificationStatus.name == "PENDING_VERIFICATION") {
            Text("pending verification", style = SpecTypography.Status, color = SpecColors.UnderRepair)
        }
        if (canDecide && record.verificationStatus.name == "PENDING_VERIFICATION") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton("Verify", {
                    decision = "VERIFY"
                    comment = ""
                }, Modifier.weight(1f))
                SecondaryButton("Correction", {
                    decision = "REQUEST_CORRECTION"
                    comment = ""
                }, Modifier.weight(1f))
                DangerButton("Reject", {
                    decision = "REJECT"
                    comment = ""
                }, Modifier.weight(1f))
            }
        }
        SectionDivider()
    }
    val activeDecision = decision
    if (activeDecision != null) {
        val commentRequired = activeDecision != "VERIFY"
        AlertDialog(
            onDismissRequest = { decision = null },
            title = { Text(activeDecision.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
            text = {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(if (commentRequired) "Comment (required)" else "Comment") },
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (commentRequired && comment.isBlank()) return@TextButton
                        onDecide(record, activeDecision, comment.ifBlank { null })
                        decision = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { decision = null }) { Text("Cancel") } },
        )
    }
}
