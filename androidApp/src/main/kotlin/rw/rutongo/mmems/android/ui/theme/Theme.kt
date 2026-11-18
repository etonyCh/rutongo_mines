package rw.rutongo.mmems.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object SpecColors {
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFF7F9FA)
    val Text = Color(0xFF12171C)
    val SecondaryText = Color(0xFF5B6670)
    val DisabledText = Color(0xFF8A949C)
    val Divider = Color(0xFFDDE2E6)
    val ProgressTrack = Color(0xFFE8ECEF)
    val Action = Color(0xFF1D4E7A)
    val OnAction = Color(0xFFCFE0EE)
    val SelectedSurface = Color(0xFFE9F0F6)
    val Operational = Color(0xFF1B8A4B)
    val UnderRepair = Color(0xFFE07B00)
    val Down = Color(0xFFD32F2F)
    val ErrorBackground = Color(0xFFFDECEC)
    val ErrorText = Color(0xFFA52222)
    val WarningBackground = Color(0xFFFFF3E3)
    val WarningText = Color(0xFF8A4B00)
    val MissingTrack = Color(0xFFF6D5D5)
}

object SpecSpacing {
    val PhoneMargin = 24.dp
    val TabletMargin = 48.dp
    val DesktopMargin = 64.dp
    val SectionGap = 24.dp
    val RowGap = 12.dp
    val DividerHeight = 1.dp
    val MinimumTouch = 48.dp
    val PrimaryButton = 56.dp
    val StatusDot = 10.dp
    val EquipmentRow = 72.dp
}

object SpecTypography {
    private val Font = FontFamily.SansSerif

    private fun style(
        size: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        color: Color = SpecColors.Text,
        spacing: TextUnit = 0.em,
    ) = TextStyle(fontFamily = Font, fontSize = size, fontWeight = weight, color = color, letterSpacing = spacing)

    val ScreenTitle = style(32.sp, FontWeight.ExtraBold, spacing = (-0.02).em)
    val SectionTitle = style(20.sp, FontWeight.Bold)
    val Eyebrow = style(13.sp, FontWeight.SemiBold, SpecColors.SecondaryText, 0.1.em)
    val Body = style(16.sp)
    val BodySmall = style(14.sp, color = SpecColors.SecondaryText)
    val Label = style(11.sp, FontWeight.Bold, SpecColors.SecondaryText, 0.06.em)
    val Status = style(12.sp, FontWeight.Bold, spacing = 0.08.em)
    val BigNumberPhone = style(56.sp, FontWeight.ExtraBold)
    val BigNumberWide = style(72.sp, FontWeight.ExtraBold)
    val Button = style(18.sp, FontWeight.ExtraBold)
}

val Green = SpecColors.Operational
val Amber = SpecColors.UnderRepair
val Red = SpecColors.Down
val Sand = SpecColors.Background
val Ink = SpecColors.Text
val GreenDark = SpecColors.Action

private val light =
    lightColorScheme(
        primary = SpecColors.Action,
        onPrimary = Color.White,
        primaryContainer = SpecColors.SelectedSurface,
        onPrimaryContainer = SpecColors.Action,
        secondary = SpecColors.Text,
        onSecondary = Color.White,
        error = SpecColors.Down,
        onError = Color.White,
        background = SpecColors.Background,
        surface = SpecColors.Background,
        onSurface = SpecColors.Text,
        onSurfaceVariant = SpecColors.SecondaryText,
        outline = SpecColors.Divider,
        outlineVariant = SpecColors.Divider,
        surfaceVariant = SpecColors.Surface,
    )

private val dark =
    darkColorScheme(
        primary = SpecColors.Action,
        onPrimary = Color.White,
        primaryContainer = SpecColors.SelectedSurface,
        onPrimaryContainer = SpecColors.Action,
        secondary = SpecColors.Text,
        onSecondary = Color.White,
        error = SpecColors.Down,
        onError = Color.White,
        background = SpecColors.Background,
        surface = SpecColors.Background,
        onSurface = SpecColors.Text,
        onSurfaceVariant = SpecColors.SecondaryText,
        outline = SpecColors.Divider,
        outlineVariant = SpecColors.Divider,
        surfaceVariant = SpecColors.Surface,
    )

private val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

@Composable
fun MmemsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) dark else light,
        typography =
            Typography(
                displayLarge = SpecTypography.ScreenTitle,
                displayMedium = SpecTypography.ScreenTitle,
                displaySmall = SpecTypography.ScreenTitle,
                headlineLarge = SpecTypography.ScreenTitle,
                headlineMedium = SpecTypography.SectionTitle,
                headlineSmall = SpecTypography.SectionTitle,
                titleLarge = SpecTypography.SectionTitle,
                titleMedium = SpecTypography.Body,
                titleSmall = SpecTypography.Body,
                bodyLarge = SpecTypography.Body,
                bodyMedium = SpecTypography.Body,
                bodySmall = SpecTypography.BodySmall,
                labelLarge = SpecTypography.Button,
                labelMedium = SpecTypography.Label,
                labelSmall = SpecTypography.Label,
            ),
        shapes = shapes,
        content = content,
    )
}
