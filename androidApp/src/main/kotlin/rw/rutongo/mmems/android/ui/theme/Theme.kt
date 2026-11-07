package rw.rutongo.mmems.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF0B5D3B)
val GreenDark = Color(0xFF073D27)
val Amber = Color(0xFF9A5B00)
val Red = Color(0xFFB3261E)
val Sand = Color(0xFFF6F1E7)
val Ink = Color(0xFF1A1C19)

private val light =
    lightColorScheme(
        primary = Green,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB8EBCF),
        onPrimaryContainer = GreenDark,
        secondary = Amber,
        onSecondary = Color.White,
        error = Red,
        onError = Color.White,
        background = Sand,
        surface = Color.White,
        onSurface = Ink,
    )

private val dark =
    darkColorScheme(
        primary = Color(0xFF8FD5B3),
        onPrimary = GreenDark,
        secondary = Color(0xFFFFB95C),
        error = Color(0xFFFFB4AB),
        background = Color(0xFF101410),
        surface = Color(0xFF191D19),
    )

@Composable
fun MmemsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}
