package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.FieldLabel
import rw.rutongo.mmems.android.ui.components.StatusPill
import java.time.Instant

private val breakdownCategories = listOf("MECHANICAL", "ELECTRICAL", "HYDRAULIC", "SAFETY", "OTHER")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakdownReportScreen(
    container: AppContainer,
    initialEquipmentId: String?,
    onDone: () -> Unit,
    onOpenBreakdown: (String) -> Unit,
) {
    var selectedEquipmentId by remember(initialEquipmentId) { mutableStateOf(initialEquipmentId?.takeIf { it.isNotBlank() }) }
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    val selected = selectedEquipmentId?.let { container.store.equipment(it) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report breakdown") },
                navigationIcon = { OutlinedButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        if (selected == null) {
            EquipmentPicker(
                modifier = Modifier.fillMaxSize().padding(padding),
                equipment = equipment,
                onSelect = { selectedEquipmentId = it },
            )
            return@Scaffold
        }
        BreakdownReportForm(
            container = container,
            equipmentId = selected.id,
            modifier = Modifier.fillMaxSize().padding(padding),
            onDone = onDone,
            onOpenBreakdown = onOpenBreakdown,
        )
    }
}

@Composable
private fun EquipmentPicker(
    modifier: Modifier,
    equipment: List<rw.rutongo.mmems.android.db.Equipment>,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible =
        equipment.filter {
            query.isBlank() ||
                it.name.contains(query, true) ||
                it.equipment_code.contains(query, true) ||
                it.site_name.contains(query, true)
        }
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Choose equipment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Only cached equipment is available offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search equipment or site") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(visible, key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(item.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, fontWeight = FontWeight.Bold)
                    Text("${item.equipment_code} · ${item.site_name}", style = MaterialTheme.typography.bodySmall)
                    StatusPill(item.operational_status)
                }
            }
        }
        if (visible.isEmpty()) {
            item { Text("No cached equipment matches this search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BreakdownReportForm(
    container: AppContainer,
    equipmentId: String,
    modifier: Modifier,
    onDone: () -> Unit,
    onOpenBreakdown: (String) -> Unit,
) {
    val equipment = container.store.equipment(equipmentId)
    val breakdowns by container.store.breakdowns().collectAsStateWithLifecycle(emptyList())
    val active = breakdowns.firstOrNull { it.equipment_id == equipmentId && it.state !in setOf("RESOLVED", "CLOSED") }
    val scope = rememberCoroutineScope()
    var category by remember(equipmentId) { mutableStateOf("MECHANICAL") }
    var problem by remember(equipmentId) { mutableStateOf("") }
    var actionTaken by remember(equipmentId) { mutableStateOf("") }
    var partsRequired by remember(equipmentId) { mutableStateOf("") }
    var remarks by remember(equipmentId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf(false) }
    if (equipment == null) return
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(equipment.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${equipment.equipment_code} · ${equipment.site_name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusPill(equipment.operational_status)
        if (active != null && !queued) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("An active breakdown already exists.", fontWeight = FontWeight.Bold)
                    Text(active.problem, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onOpenBreakdown(active.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("OPEN ACTIVE BREAKDOWN")
                    }
                }
            }
            return@Column
        }
        FieldLabel("Category")
        breakdownCategories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach {
                    FilterChip(
                        selected = category == it,
                        onClick = { category = it },
                        label = { Text(it.lowercase().replaceFirstChar(Char::uppercase)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        OutlinedTextField(
            value = problem,
            onValueChange = { problem = it },
            label = { Text("Problem (required)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = actionTaken,
            onValueChange = { actionTaken = it },
            label = { Text("Immediate action taken") },
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
        Text(
            "The breakdown starts at the current device time. Record the equipment status separately if it also changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (queued) {
            Text(
                "Breakdown queued locally. It will be sent with an idempotency key when a connection is available.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Button(
            onClick = {
                if (problem.isBlank()) {
                    error = "Describe the problem before reporting the breakdown."
                    return@Button
                }
                container.store.enqueueBreakdownReport(
                    equipment = equipment,
                    category = category,
                    problem = problem,
                    actionTaken = actionTaken,
                    partsRequired = partsRequired,
                    remarks = remarks,
                    breakdownStart = Instant.now().toString(),
                )
                SyncWorker.enqueueNow(container.appContext)
                queued = true
                error = null
                scope.launch {
                    delay(700)
                    onDone()
                }
            },
            enabled = !queued,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("QUEUE BREAKDOWN REPORT") }
    }
}
