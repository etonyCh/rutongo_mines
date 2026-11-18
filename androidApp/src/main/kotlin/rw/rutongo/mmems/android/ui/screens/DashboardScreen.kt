package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.components.BigCounter
import rw.rutongo.mmems.android.ui.components.EmptyState
import rw.rutongo.mmems.android.ui.components.EquipmentRow
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SectionDivider
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun DashboardScreen(
    container: AppContainer,
    me: MeView,
    onOpenReport: () -> Unit,
) {
    val expected by container.store.expected().collectAsStateWithLifecycle(emptyList())
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    val breakdowns by container.store.breakdowns().collectAsStateWithLifecycle(emptyList())
    val open = expected.count { it.state.uppercase() !in setOf("COMPLETED", "COMPLETED_WITH_DEFECTS", "CRITICAL_FAILURE") }
    val missing = expected.count { it.state.uppercase() == "MISSING" }
    val down = equipment.count { it.operational_status == "DOWN" }
    val repair = equipment.count { it.operational_status == "UNDER_REPAIR" }
    val operational = equipment.count { it.operational_status == "OPERATIONAL" }
    val withDefects = equipment.count { it.operational_status.uppercase() == "OPERATIONAL_WITH_DEFECTS" }
    val attention = equipment.filter { it.operational_status in setOf("DOWN", "UNDER_REPAIR") }
    val site = expected.firstOrNull()?.site_name ?: equipment.firstOrNull()?.site_name ?: "Not recorded"
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        item {
            ScreenHeader(
                eyebrow = "$site · ${me.role.name.replace('_', ' ')}",
                title = "Site dashboard",
                subtitle = "${me.fullName} · ${equipment.size} active units · $withDefects operational with defects",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigCounter("Operational", operational.toString(), SpecColors.Operational, Modifier.weight(1f))
                BigCounter("Under repair", repair.toString(), SpecColors.UnderRepair, Modifier.weight(1f))
                BigCounter("Down", down.toString(), SpecColors.Down, Modifier.weight(1f))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open inspections $open · Total equipment ${equipment.size} · Missing $missing", style = SpecTypography.BodySmall)
                PrimaryButton("Daily Site Report", onOpenReport)
                if (missing > 0) {
                    Text("$missing inspections missing", style = SpecTypography.Status, color = SpecColors.ErrorText)
                }
            }
        }
        item { SectionDivider() }
        item { Text("NEEDS ATTENTION", style = SpecTypography.Eyebrow) }
        if (attention.isEmpty() && breakdowns.isEmpty()) {
            item { EmptyState("Nothing needs attention", "No down or under-repair equipment is cached for this site.") }
        }
        items(attention.take(6), key = { it.id }) { item ->
            EquipmentRow(
                code = item.asset_id,
                name = item.name,
                secondaryLine = "${item.tunnel_name ?: "Not recorded"} · ${item.site_name}",
                status = item.operational_status,
                onClick = {},
            )
        }
    }
}
