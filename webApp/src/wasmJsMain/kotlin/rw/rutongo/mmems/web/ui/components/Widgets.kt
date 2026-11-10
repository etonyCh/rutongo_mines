@file:Suppress("MagicNumber")

package rw.rutongo.mmems.web.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.web.ui.DangerRed
import rw.rutongo.mmems.web.ui.InfoBlue
import rw.rutongo.mmems.web.ui.SuccessGreen
import rw.rutongo.mmems.web.ui.WarningAmber

@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    site: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (site.isNullOrBlank()) subtitle else "$subtitle · $site",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun SiteFilter(
    sites: List<SiteDto>,
    selectedSiteId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = sites.firstOrNull { it.id == selectedSiteId }?.name ?: "All sites"
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All sites") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            sites.forEach { site ->
                DropdownMenuItem(
                    text = { Text("${site.code} · ${site.name}") },
                    onClick = {
                        expanded = false
                        onSelect(site.id)
                    },
                )
            }
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    detail: String? = null,
    accent: Color = SuccessGreen,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accent),
            )
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            detail?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(text)
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(statusIcon(text), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(text.replace('_', ' '), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun statusIcon(text: String): String =
    when (text.uppercase()) {
        "APPROVED", "OPERATIONAL", "ACTIVE", "COMPLETED", "VERIFIED", "COMMITTED", "OK" -> "✓"
        "DOWN", "REJECTED", "CRITICAL", "OVERDUE", "FAIL" -> "!"
        "UNDER_REPAIR", "SUBMITTED", "PENDING", "DUE_SOON", "RETURNED", "RETURNED_FOR_CORRECTION", "AWAITING_APPROVAL", "PREVIEWED", "PENDING_VERIFICATION" -> "▲"
        "OPEN", "IN_PROGRESS", "RESUBMITTED" -> "●"
        else -> "•"
    }

fun statusColor(text: String): Color =
    when (text.uppercase()) {
        "APPROVED", "OPERATIONAL", "ACTIVE", "COMPLETED", "VERIFIED", "COMMITTED", "OK" -> SuccessGreen
        "DOWN", "REJECTED", "CRITICAL", "OVERDUE", "FAIL" -> DangerRed
        "UNDER_REPAIR", "SUBMITTED", "PENDING", "DUE_SOON", "RETURNED", "RETURNED_FOR_CORRECTION", "AWAITING_APPROVAL", "PREVIEWED", "PENDING_VERIFICATION", "CORRECTION_REQUESTED" -> WarningAmber
        "OPEN", "IN_PROGRESS", "RESUBMITTED" -> InfoBlue
        else -> Color(0xFF5E6B64)
    }

@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LoadingBlock(message: String = "Loading…") {
    Row(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorNote(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
fun PrimaryAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) { Text(text) }
}
