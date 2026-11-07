package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.components.MetricTile
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun DashboardScreen(
    container: AppContainer,
    me: MeView,
) {
    val expected by container.store.expected().collectAsStateWithLifecycle(emptyList())
    val equipment by container.store.equipment().collectAsStateWithLifecycle(emptyList())
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Site dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${me.fullName} · ${me.role.name.replace('_', ' ')}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Open inspections", expected.size.toString(), Icons.Default.Assignment, modifier = Modifier.weight(1f))
            MetricTile("Down equipment", equipment.count { it.operational_status == "DOWN" }.toString(), Icons.Default.Warning, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Under repair", equipment.count { it.operational_status == "UNDER_REPAIR" }.toString(), Icons.Default.Build, modifier = Modifier.weight(1f))
            MetricTile("Total equipment", equipment.size.toString(), Icons.Default.Build, modifier = Modifier.weight(1f))
        }
        Text("The full management dashboard is delivered in Sprint 10. This view uses the same site-scoped offline cache as the inspection assistant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
