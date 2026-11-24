package com.rutger.soundboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Palette taken from the launcher icon: indigo keypad, one lit orange key.
private val Ink = Color(0xFF1C1A2B)
private val KeyDark = Color(0xFF4A4766)
private val Lit = Color(0xFFFF7A3D)

private val DarkColors = darkColorScheme(
    primary = Lit,
    onPrimary = Ink,
    background = Color(0xFF13121D),
    onBackground = Color(0xFFEDECF5),
    surface = Ink,
    onSurface = Color(0xFFEDECF5),
    surfaceVariant = Color(0xFF292739),
    onSurfaceVariant = Color(0xFFA8A5C0),
    surfaceContainerHigh = Color(0xFF242233),
    surfaceContainerLow = Color(0xFF1A1927),
    outline = KeyDark,
    outlineVariant = Color(0xFF34324A),
    error = Color(0xFFFF7070),
    onError = Ink,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE8622A),
    onPrimary = Color.White,
    background = Color(0xFFF1F0F7),
    onBackground = Ink,
    surface = Color(0xFFFAFAFD),
    onSurface = Ink,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF5B5776),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F5FA),
    outline = Color(0xFFC9C6DC),
    outlineVariant = Color(0xFFDDDBEA),
    error = Color(0xFFC62828),
    onError = Color.White,
)

@Immutable
data class StatusColors(val ok: Color, val searching: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color.Unspecified, Color.Unspecified) }

/** Keycap legends and measured values: digits line up. */
val DataFont = FontFamily.Monospace

@Composable
fun SoundboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    val status = if (dark) StatusColors(ok = Color(0xFF5DD39E), searching = Color(0xFFFFC857))
    else StatusColors(ok = Color(0xFF1E8E5A), searching = Color(0xFFB7791F))
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
