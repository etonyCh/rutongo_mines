package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.FilterPill
import rw.rutongo.mmems.android.ui.components.FormLabel
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SecondaryButton
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.components.SpecTextField
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import java.time.Instant

private val breakdownCategories = listOf("MECHANICAL", "ELECTRICAL", "HYDRAULIC", "SAFETY", "OTHER")

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
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        SecondaryButton("Back", onDone)
        if (selected == null) {
            EquipmentPicker(
                modifier = Modifier.weight(1f),
                equipment = equipment,
                onSelect = { selectedEquipmentId = it },
            )
        } else {
            BreakdownReportForm(
                container = container,
                equipmentId = selected.id,
                modifier = Modifier.weight(1f),
                onDone = onDone,
                onOpenBreakdown = onOpenBreakdown,
            )
        }
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap)) {
        ScreenHeader("Report breakdown", eyebrow = "EQUIPMENT", subtitle = "Only cached equipment is available offline.")
        SpecTextField(query, { query = it }, "Search equipment or site", singleLine = true)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(visible, key = { it.id }) { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item.id) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(item.name, style = SpecTypography.Body)
                    Text("${item.equipment_code} · ${item.site_name}", style = SpecTypography.BodySmall)
                    StatusPill(item.operational_status)
                    SectionDivider()
                }
            }
            if (visible.isEmpty()) {
                item { Text("No cached equipment matches this search.", style = SpecTypography.BodySmall) }
            }
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
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        ScreenHeader("Report breakdown", eyebrow = equipment.equipment_code, subtitle = "${equipment.name} · ${equipment.site_name}")
        StatusPill(equipment.operational_status)
        if (active != null && !queued) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("An active breakdown already exists.", style = SpecTypography.SectionTitle)
                Text(active.problem, style = SpecTypography.BodySmall)
                SecondaryButton("OPEN ACTIVE BREAKDOWN", { onOpenBreakdown(active.id) })
            }
            return@Column
        }
        FormLabel("Category")
        breakdownCategories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach {
                    FilterPill(
                        it.lowercase().replaceFirstChar(Char::uppercase),
                        category == it,
                        { category = it },
                        Modifier.weight(1f),
                    )
                }
            }
        }
        SpecTextField(problem, { problem = it }, "Problem", required = true, minLines = 3)
        SpecTextField(actionTaken, { actionTaken = it }, "Immediate action taken", minLines = 2)
        SpecTextField(partsRequired, { partsRequired = it }, "Parts required", singleLine = true)
        SpecTextField(remarks, { remarks = it }, "Remarks", minLines = 2)
        Text(
            "The breakdown starts at the current device time. Record the equipment status separately if it also changed.",
            style = SpecTypography.BodySmall,
        )
        error?.let { Text(it, color = SpecColors.ErrorText, style = SpecTypography.BodySmall) }
        if (queued) {
            Text(
                "Breakdown queued locally. It will be sent with an idempotency key when a connection is available.",
                color = SpecColors.Action,
                style = SpecTypography.Body,
            )
        }
        PrimaryButton(
            "QUEUE BREAKDOWN REPORT",
            {
                if (problem.isBlank()) {
                    error = "Describe the problem before reporting the breakdown."
                    return@PrimaryButton
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
        )
    }
}
