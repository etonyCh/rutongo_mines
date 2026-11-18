package rw.rutongo.mmems.web.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object WebSpecColors {
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

object WebSpecTypography {
    private val Font = FontFamily.SansSerif

    private fun style(
        size: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        color: Color = WebSpecColors.Text,
        spacing: TextUnit = 0.em,
    ) = TextStyle(fontFamily = Font, fontSize = size, fontWeight = weight, color = color, letterSpacing = spacing)

    val ScreenTitle = style(32.sp, FontWeight.ExtraBold, spacing = (-0.02).em)
    val SectionTitle = style(20.sp, FontWeight.Bold)
    val Eyebrow = style(13.sp, FontWeight.SemiBold, WebSpecColors.SecondaryText, 0.1.em)
    val Body = style(16.sp)
    val BodySmall = style(14.sp, color = WebSpecColors.SecondaryText)
    val Button = style(17.sp, FontWeight.ExtraBold, Color.White)
    val Label = style(11.sp, FontWeight.Bold, WebSpecColors.SecondaryText, 0.06.em)
    val Status = style(12.sp, FontWeight.Bold, spacing = 0.08.em)
    val BigNumber = style(72.sp, FontWeight.ExtraBold)
}

val SuccessGreen = WebSpecColors.Operational
val WarningAmber = WebSpecColors.UnderRepair
val DangerRed = WebSpecColors.Down
val InfoBlue = WebSpecColors.Action

private val colors =
    lightColorScheme(
        primary = WebSpecColors.Action,
        onPrimary = Color.White,
        primaryContainer = WebSpecColors.SelectedSurface,
        onPrimaryContainer = WebSpecColors.Action,
        secondary = WebSpecColors.Text,
        onSecondary = Color.White,
        secondaryContainer = WebSpecColors.Surface,
        onSecondaryContainer = WebSpecColors.Text,
        background = WebSpecColors.Background,
        onBackground = WebSpecColors.Text,
        surface = WebSpecColors.Background,
        onSurface = WebSpecColors.Text,
        surfaceVariant = WebSpecColors.Surface,
        onSurfaceVariant = WebSpecColors.SecondaryText,
        outline = WebSpecColors.Divider,
        error = WebSpecColors.Down,
        onError = Color.White,
        errorContainer = WebSpecColors.ErrorBackground,
        onErrorContainer = WebSpecColors.ErrorText,
    )

private val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

private val typography =
    Typography(
        displayLarge = WebSpecTypography.ScreenTitle,
        displayMedium = WebSpecTypography.ScreenTitle,
        displaySmall = WebSpecTypography.ScreenTitle,
        headlineLarge = WebSpecTypography.ScreenTitle,
        headlineMedium = WebSpecTypography.SectionTitle,
        headlineSmall = WebSpecTypography.SectionTitle,
        titleLarge = WebSpecTypography.SectionTitle,
        titleMedium = WebSpecTypography.Body,
        titleSmall = WebSpecTypography.Body,
        bodyLarge = WebSpecTypography.Body,
        bodyMedium = WebSpecTypography.Body,
        bodySmall = WebSpecTypography.BodySmall,
        labelLarge = WebSpecTypography.Status,
        labelMedium = WebSpecTypography.Label,
        labelSmall = WebSpecTypography.Label,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RutongoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 56.dp) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
