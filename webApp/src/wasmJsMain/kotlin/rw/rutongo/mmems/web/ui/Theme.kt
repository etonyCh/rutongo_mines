package rw.rutongo.mmems.web.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Forest = Color(0xFF1F5A43)
private val ForestDark = Color(0xFF154030)
private val Clay = Color(0xFFA9542C)
private val Sand = Color(0xFFF4EFE7)
private val Ink = Color(0xFF1B2521)
private val Muted = Color(0xFF5E6B64)

val SuccessGreen = Color(0xFF2E7D4F)
val WarningAmber = Color(0xFFB06D12)
val DangerRed = Color(0xFFB23A36)
val InfoBlue = Color(0xFF2F5F8F)

private val colors =
    lightColorScheme(
        primary = Forest,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7EADF),
        onPrimaryContainer = ForestDark,
        secondary = Clay,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF6DFD2),
        onSecondaryContainer = Color(0xFF54230B),
        background = Sand,
        onBackground = Ink,
        surface = Color.White,
        onSurface = Ink,
        surfaceVariant = Color(0xFFEAE4DA),
        onSurfaceVariant = Muted,
        outline = Color(0xFFB7B0A4),
        error = DangerRed,
        onError = Color.White,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RutongoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 56.dp) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
