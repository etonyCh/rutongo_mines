package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography

@Composable
fun TasksScreen(container: AppContainer) {
    val drafts by container.store.openDraftsFlow().collectAsStateWithLifecycle(emptyList())
    val outbox by container.store.allOutbox().collectAsStateWithLifecycle(emptyList())
    val pending = outbox.filter { it.state in setOf("PENDING", "RETRY") }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "MY TASKS",
                title = "Waiting to sync",
                subtitle = "${drafts.size} draft(s) · ${pending.size} operation(s) waiting",
            )
        }
        items(drafts, key = { "draft-${it.clientUuid}" }) { draft ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("Continue ${draft.equipmentName}", style = SpecTypography.Body)
                Text("${draft.equipmentCode} · saved at ${draft.step.name.lowercase().replace('_', ' ')}", style = SpecTypography.BodySmall)
                HorizontalDivider(color = SpecColors.Divider)
            }
        }
        items(pending, key = { "outbox-${it.client_uuid}" }) { op ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                val label =
                    when (op.kind) {
                        "STATUS" -> "Status update"
                        "BREAKDOWN_CREATE" -> "Breakdown report"
                        "BREAKDOWN_TRANSITION" -> "Repair update"
                        else -> "Inspection submission"
                    }
                Text(label, style = SpecTypography.Body)
                Text("${op.client_uuid} · ${op.state.lowercase()}", style = SpecTypography.BodySmall)
                HorizontalDivider(color = SpecColors.Divider)
            }
        }
        if (drafts.isEmpty() && pending.isEmpty()) {
            item { Text("Nothing is waiting.", style = SpecTypography.BodySmall) }
        }
    }
}
