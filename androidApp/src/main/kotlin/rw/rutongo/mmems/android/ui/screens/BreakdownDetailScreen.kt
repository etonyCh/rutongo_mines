package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.FilterPill
import rw.rutongo.mmems.android.ui.components.FormLabel
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.SecondaryButton
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.components.SpecTextField
import rw.rutongo.mmems.android.ui.components.StatusPill
import rw.rutongo.mmems.android.ui.components.TimelineItem
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.BreakdownTransitionDto
import rw.rutongo.mmems.shared.enums.BreakdownState
import java.time.Instant

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
                title = { Text("Breakdown record", style = SpecTypography.SectionTitle) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = SpecColors.Background,
                        titleContentColor = SpecColors.Text,
                        navigationIconContentColor = SpecColors.Action,
                    ),
            )
        },
    ) { padding ->
        val current = breakdown
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                CircularProgressIndicator(color = SpecColors.Action)
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
                .padding(horizontal = SpecSpacing.PhoneMargin, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
        ) {
            Text(current.equipment_code ?: current.equipment_id, style = SpecTypography.ScreenTitle)
            Text("${current.category} · started ${current.breakdown_start.take(16).replace('T', ' ')}", style = SpecTypography.BodySmall)
            StatusPill(current.state)
            Text("Downtime ${downtime(current.breakdown_start, current.repair_end)}", style = SpecTypography.SectionTitle, color = SpecColors.Down)
            SectionDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reported problem", style = SpecTypography.Eyebrow)
                Text(current.problem, style = SpecTypography.Body)
                current.action_taken?.let { Text("Action: $it", style = SpecTypography.BodySmall) }
                current.parts_required?.let { Text("Parts: $it", style = SpecTypography.BodySmall) }
                current.remarks?.let { Text("Remarks: $it", style = SpecTypography.BodySmall) }
            }
            SectionDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Repair timeline", style = SpecTypography.Eyebrow)
                Text("Stored as immutable server events with optimistic version checks.", style = SpecTypography.BodySmall)
                TimelineItem(
                    title = "Breakdown reported",
                    detail = "Breakdown start: ${current.breakdown_start.take(16).replace('T', ' ')}",
                    status = "REPORTED",
                )
                TimelineItem(
                    title = "Repair started",
                    detail = "Repair start: ${current.repair_start?.take(16)?.replace('T', ' ') ?: "Not recorded"}",
                    status = current.repair_start?.let { "UNDER_REPAIR" } ?: "NOT_RECORDED",
                )
                TimelineItem(
                    title = "Repair completed",
                    detail = "Repair end: ${current.repair_end?.take(16)?.replace('T', ' ') ?: "Not recorded"}",
                    status = current.repair_end?.let { "RESOLVED" } ?: "NOT_RECORDED",
                    isLast = true,
                )
                Text("${current.defect_count} linked defect(s) · ${current.photo_count} photo(s)", style = SpecTypography.BodySmall)
            }
            current.pending_state?.let { pending ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (pending == "CREATE") "Waiting to send this report" else "Repair update waiting to sync",
                        style = SpecTypography.Body,
                        color = SpecColors.Action,
                    )
                    Text(
                        "The device keeps the full operation and retries it with WorkManager. Do not sign out until it reaches the server.",
                        style = SpecTypography.BodySmall,
                    )
                }
            }
            if (current.pending_state == "CREATE") return@Column
            if (allowed.isEmpty()) {
                Text("This breakdown is closed. Its history remains available.", style = SpecTypography.BodySmall)
                return@Column
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormLabel("Next repair state")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    allowed.forEach { state ->
                        FilterPill(
                            text = state.name.lowercase().replace('_', ' '),
                            selected = target == state,
                            onClick = { target = state },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (BreakdownState.UNDER_REPAIR in allowed) {
                    SecondaryButton("Start repair", { target = BreakdownState.UNDER_REPAIR })
                }
            }
            SpecTextField(actionTaken, { actionTaken = it }, "Action taken", minLines = 2)
            SpecTextField(partsRequired, { partsRequired = it }, "Parts required", singleLine = true)
            SpecTextField(remarks, { remarks = it }, "Remarks", minLines = 2)
            if (queued) {
                Text(
                    "Repair update queued. It will use If-Match version ${current.version} when it syncs.",
                    style = SpecTypography.BodySmall,
                    color = SpecColors.Action,
                )
            }
            PrimaryButton(
                text = "QUEUE REPAIR UPDATE",
                enabled = target != null,
                onClick = {
                    val state = target ?: return@PrimaryButton
                    container.store.enqueueBreakdownTransition(
                        breakdown = current,
                        transition =
                            BreakdownTransitionDto(
                                state = state,
                                actionTaken = actionTaken.ifBlank { null },
                                partsRequired = partsRequired.ifBlank { null },
                                remarks = remarks.ifBlank { null },
                            ),
                    )
                    SyncWorker.enqueueNow(container.appContext)
                    queued = true
                    actionTaken = ""
                    partsRequired = ""
                    remarks = ""
                },
            )
        }
    }
}

private fun downtime(
    start: String,
    end: String? = null,
): String =
    runCatching {
        val finish = end?.let { Instant.parse(it) } ?: Instant.now()
        val minutes =
            java.time.Duration
                .between(Instant.parse(start), finish)
                .toMinutes()
                .coerceAtLeast(0)
        when {
            minutes >= 1440 -> "${minutes / 1440} d ${(minutes % 1440) / 60} h"
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} m"
            else -> "$minutes m"
        }
    }.getOrDefault("Not recorded")
