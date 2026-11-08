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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.MaintenanceViewModel
import rw.rutongo.mmems.android.ui.components.FieldLabel
import rw.rutongo.mmems.android.ui.components.MetricTile
import rw.rutongo.mmems.android.ui.components.SectionCard
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.shared.api.MaintenanceCreateDto
import rw.rutongo.mmems.shared.api.MaintenanceViewDto
import rw.rutongo.mmems.shared.enums.Role

private val maintenanceRoles = setOf(Role.MECHANIC, Role.SUPERVISOR)

@Composable
fun MaintenanceScreen(
    container: AppContainer,
    me: rw.rutongo.mmems.shared.api.MeView,
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
    var tab by remember { mutableStateOf("schedule") }
    var showCreate by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Maintenance", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Service intervals, technician records and meter-hour summaries", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile("Schedule", schedule.size.toString(), Icons.Default.Schedule, modifier = Modifier.weight(1f))
                MetricTile("Pending", records.count { it.verificationStatus.name == "PENDING_VERIFICATION" }.toString(), Icons.Default.Build, modifier = Modifier.weight(1f))
                MetricTile("Queued", queued.size.toString(), Icons.Default.Timer, modifier = Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("schedule", "records", "hours").forEach { value ->
                    FilterChip(
                        selected = tab == value,
                        onClick = { tab = value },
                        label = { Text(value.replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        actionState.message?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        actionState.error?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = viewModel::refresh, enabled = !actionState.refreshing, modifier = Modifier.weight(1f)) {
                    Text(if (actionState.refreshing) "Refreshing…" else "Refresh")
                }
                if (me.role in maintenanceRoles) {
                    Button(onClick = { showCreate = !showCreate }, modifier = Modifier.weight(1f)) {
                        Text(if (showCreate) "Close form" else "Record service")
                    }
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
                    item { Text("No service intervals are cached yet. An administrator configures intervals on the server.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(schedule, key = { it.intervalId }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${item.equipmentCode} · ${item.equipmentName}", fontWeight = FontWeight.Bold)
                                    Text("${item.serviceTypeName} every ${item.intervalHours} h", style = MaterialTheme.typography.bodySmall)
                                }
                                StatusPill(item.status.name)
                            }
                            Text(
                                "Last verified ${item.lastVerified?.let { "%.1f".format(it) } ?: "never"} h · next due ${item.nextDue?.let { "%.1f".format(it) } ?: "—"} h",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Remaining ${item.remaining?.let { "%.1f".format(it) } ?: "—"} h · current ${item.current?.let { "%.1f".format(it) } ?: "not recorded"} h",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            "records" -> {
                if (records.isEmpty()) {
                    item { Text("No maintenance records are cached. Records you queue offline appear after the next sync.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(records, key = { it.id }) { record ->
                    MaintenanceRecordCard(record, canDecide = me.role == Role.SUPERVISOR, onDecide = viewModel::decide)
                }
            }
            else -> {
                item {
                    MetricTile("Recorded hours", "%.1f".format(hours?.totalRecordedHours ?: 0.0), Icons.Default.Timer)
                }
                val groups = hours?.groups.orEmpty()
                if (groups.isEmpty()) {
                    item { Text("No working-hour entries are cached yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(groups, key = { it.key }) { group ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(group.equipmentName ?: group.siteName ?: group.shiftCode ?: group.reportDate ?: group.key, fontWeight = FontWeight.Bold)
                            Text("%.1f recorded hours across %d entries".format(group.recordedHours, group.entryCount))
                            if (group.overlapEntries > 0 || group.missingReadings > 0) {
                                Text(
                                    "${group.overlapEntries} overlap(s) · ${group.missingReadings} missing reading(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceCreateCard(
    equipment: List<rw.rutongo.mmems.android.db.Equipment>,
    serviceTypes: List<rw.rutongo.mmems.shared.api.ServiceTypeViewDto>,
    technicians: List<rw.rutongo.mmems.shared.api.MaintenanceTechnicianDto>,
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
        Selector(
            label = "Equipment",
            value = selectedEquipment?.let { "${it.equipment_code} · ${it.name}" },
            options = equipment.map { it.id to "${it.equipment_code} · ${it.name}" },
            onSelect = { equipmentId = it },
        )
        Selector(
            label = "Service type",
            value = serviceTypes.find { it.id == serviceTypeId }?.name,
            options = serviceTypes.map { it.id to "${it.code} · ${it.name}" },
            onSelect = { serviceTypeId = it },
        )
        Selector(
            label = "Technician",
            value = technicians.find { it.id == technicianId }?.name,
            options = technicians.map { it.id to "${it.employeeId} · ${it.name}" },
            onSelect = { technicianId = it },
        )
        OutlinedTextField(
            value = workPerformed,
            onValueChange = { workPerformed = it },
            label = { Text("Work performed (required)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cause,
            onValueChange = { cause = it },
            label = { Text("Cause") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = engineHours,
            onValueChange = { engineHours = it },
            label = { Text("Engine hours") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (serviceTypes.isEmpty() || technicians.isEmpty()) {
            Text(
                "Service types and technicians must be cached before recording work. Refresh when connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val equipmentValue = equipmentId
                val serviceValue = serviceTypeId
                val technicianValue = technicianId
                if (equipmentValue == null || serviceValue == null || technicianValue == null) {
                    error = "Choose the equipment, service type and technician."
                    return@Button
                }
                if (workPerformed.isBlank()) {
                    error = "Describe the work performed."
                    return@Button
                }
                val hours = engineHours.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                if (engineHours.isNotBlank() && hours == null) {
                    error = "Engine hours must be a number."
                    return@Button
                }
                error = null
                onQueue(
                    equipmentValue,
                    MaintenanceCreateDto(
                        equipmentId = equipmentValue,
                        serviceTypeId = serviceValue,
                        engineHours = hours,
                        workPerformed = workPerformed.trim(),
                        cause = cause.trim().ifBlank { null },
                        technicianId = technicianValue,
                        supervisorId = supervisorId,
                        notes = notes.trim().ifBlank { null },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("QUEUE MAINTENANCE") }
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
    FieldLabel(label)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value ?: "Select $label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MaintenanceRecordCard(
    record: MaintenanceViewDto,
    canDecide: Boolean,
    onDecide: (MaintenanceViewDto, String, String?) -> Unit,
) {
    var decision by remember { mutableStateOf<String?>(null) }
    var comment by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${record.equipmentCode} · ${record.serviceTypeName}", fontWeight = FontWeight.Bold)
                    Text(record.performedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(record.verificationStatus.name)
            }
            Text(record.workPerformed)
            Text(
                "Technician ${record.technicianName} · ${record.replacedComponents.size} part(s) · ${record.downtimeMinutes ?: 0} min downtime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val cost = record.totalCost
            Text(
                if (cost == null) "Cost hidden for your role" else "Total cost ${record.currency} $cost",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canDecide && record.verificationStatus.name == "PENDING_VERIFICATION") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        decision = "VERIFY"
                        comment = ""
                    }, modifier = Modifier.weight(1f)) { Text("Verify") }
                    TextButton(onClick = {
                        decision = "REJECT"
                        comment = ""
                    }, modifier = Modifier.weight(1f)) { Text("Reject") }
                    TextButton(onClick = {
                        decision = "REQUEST_CORRECTION"
                        comment = ""
                    }, modifier = Modifier.weight(1f)) { Text("Correction") }
                }
            }
        }
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
