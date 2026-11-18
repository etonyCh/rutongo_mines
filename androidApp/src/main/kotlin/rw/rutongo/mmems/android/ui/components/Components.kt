package rw.rutongo.mmems.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography

fun statusColor(status: String): Color =
    when (status.trim().uppercase()) {
        "OPERATIONAL", "COMPLETED", "CLEAN" -> SpecColors.Operational
        "UNDER_REPAIR", "REPORTED", "DIAGNOSING", "AWAITING_PARTS", "DRAFT", "IN_PROGRESS", "COMPLETED_WITH_DEFECTS" -> SpecColors.UnderRepair
        "DOWN", "CRITICAL_FAILURE", "MISSING", "RETURNED", "REJECTED", "ERROR" -> SpecColors.Down
        else -> SpecColors.DisabledText
    }

fun statusLabel(status: String): String =
    status
        .trim()
        .uppercase()
        .replace('_', ' ')

@Composable
fun StatusPill(
    status: String,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(status)
    Row(
        modifier = modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(SpecSpacing.StatusDot).clip(RoundedCornerShape(50)).background(color))
        Text(statusLabel(status), style = SpecTypography.Status, color = color)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .border(SpecSpacing.DividerHeight, SpecColors.Divider)
            .background(SpecColors.Background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = SpecTypography.SectionTitle)
        subtitle?.let { Text(it, style = SpecTypography.BodySmall) }
        content()
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    tint: Color = SpecColors.Operational,
    modifier: Modifier = Modifier,
) {
    BigCounter(label = label, value = value, color = tint, modifier = modifier)
}

@Composable
fun BigCounter(
    label: String,
    value: String,
    color: Color = SpecColors.Text,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = SpecTypography.BigNumberPhone, color = color)
        Text(label.uppercase(), style = SpecTypography.Label)
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        eyebrow?.let { Text(it.uppercase(), style = SpecTypography.Eyebrow) }
        Text(title, style = SpecTypography.ScreenTitle)
        subtitle?.let { Text(it, style = SpecTypography.BodySmall) }
    }
}

@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(SpecSpacing.DividerHeight).background(SpecColors.Divider))
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SpecSpacing.PrimaryButton),
        shape = RoundedCornerShape(12.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = SpecColors.Action,
                contentColor = Color.White,
                disabledContainerColor = SpecColors.ProgressTrack,
                disabledContentColor = SpecColors.DisabledText,
            ),
    ) {
        Text(text, style = SpecTypography.Button)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SpecSpacing.MinimumTouch),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, SpecColors.Action),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = SpecColors.Action,
                disabledContentColor = SpecColors.DisabledText,
            ),
    ) {
        Text(text, style = SpecTypography.Body, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(SpecSpacing.MinimumTouch),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, SpecColors.Down),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = SpecColors.Down,
                disabledContentColor = SpecColors.DisabledText,
            ),
    ) {
        Text(text, style = SpecTypography.Body, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FormLabel(
    text: String,
    required: Boolean = false,
) {
    Text(
        if (required) "$text · REQUIRED" else text.uppercase(),
        style = SpecTypography.Label,
    )
}

@Composable
fun SpecTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FormLabel(label, required)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun FilterPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (selected) SpecColors.Action else SpecColors.Background,
                contentColor = if (selected) Color.White else SpecColors.SecondaryText,
            ),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, SpecColors.Divider),
    ) {
        Text(text, style = SpecTypography.BodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    missing: Boolean = false,
) {
    val bounded = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(if (missing) SpecColors.MissingTrack else SpecColors.ProgressTrack),
    ) {
        if (bounded > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(bounded)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SpecColors.Operational),
            )
        }
    }
}

@Composable
fun EquipmentRow(
    code: String,
    name: String,
    secondaryLine: String,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(SpecSpacing.EquipmentRow)
            .clickable(onClick = onClick)
            .border(SpecSpacing.DividerHeight, SpecColors.Divider)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(SpecSpacing.StatusDot).clip(RoundedCornerShape(50)).background(statusColor(status)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$code · $name", style = SpecTypography.Body, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(secondaryLine, style = SpecTypography.BodySmall, maxLines = 1)
        }
        Text(statusLabel(status), style = SpecTypography.Status, color = statusColor(status))
    }
}

@Composable
fun TimelineItem(
    title: String,
    detail: String,
    status: String,
    modifier: Modifier = Modifier,
    isLast: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(statusColor(status)))
            if (!isLast) {
                Box(Modifier.width(1.dp).height(36.dp).background(SpecColors.Divider))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = SpecTypography.Body, fontWeight = FontWeight.Bold)
            Text(detail, style = SpecTypography.BodySmall)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = SpecTypography.SectionTitle)
        Text(detail, style = SpecTypography.BodySmall)
    }
}

@Composable
fun FieldLabel(text: String) = FormLabel(text)

@Composable
fun VerticalGap(height: Int = 12) {
    Spacer(Modifier.height(height.dp))
}

@Composable
fun FixedActionBar(
    onBack: () -> Unit,
    nextLabel: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SpecColors.Background)
            .border(SpecSpacing.DividerHeight, SpecColors.Divider)
            .padding(horizontal = SpecSpacing.PhoneMargin, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SecondaryButton("Back", onBack, modifier = Modifier.weight(1f))
        PrimaryButton(nextLabel, onNext, modifier = Modifier.weight(2f))
    }
}
