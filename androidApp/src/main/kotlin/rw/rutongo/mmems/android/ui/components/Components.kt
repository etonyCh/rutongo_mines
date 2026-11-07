package rw.rutongo.mmems.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.android.ui.theme.Amber
import rw.rutongo.mmems.android.ui.theme.Green
import rw.rutongo.mmems.android.ui.theme.Red

@Composable
fun StatusPill(
    status: String,
    modifier: Modifier = Modifier,
) {
    val (label, icon, color) =
        when (status.uppercase()) {
            "OPERATIONAL" -> Triple("Operational", Icons.Default.CheckCircle, Green)
            "UNDER_REPAIR" -> Triple("Under repair", Icons.Default.Construction, Amber)
            "DOWN" -> Triple("Down", Icons.Default.Error, Red)
            "OPERATIONAL_WITH_DEFECTS" -> Triple("Operational with defects", Icons.Default.Error, Amber)
            "NOT_RECORDED" -> Triple("Not recorded", Icons.Default.HelpOutline, MaterialTheme.colorScheme.outline)
            else -> Triple(status.lowercase().replace('_', ' '), Icons.Default.HelpOutline, MaterialTheme.colorScheme.outline)
        }
    Row(
        modifier =
            modifier
                .background(color.copy(alpha = 0.13f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color = Green,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
fun VerticalGap(height: Int = 12) {
    Spacer(Modifier.height(height.dp))
}
