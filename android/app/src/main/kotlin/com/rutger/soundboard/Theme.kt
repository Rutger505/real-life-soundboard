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

private val Ink = Color(0xFF1A1C24)
private val Night = Color(0xFF0F1220)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6E8BFF),
    onPrimary = Night,
    background = Night,
    onBackground = Color(0xFFE9ECF7),
    surface = Night,
    onSurface = Color(0xFFE9ECF7),
    surfaceVariant = Color(0xFF1B2033),
    onSurfaceVariant = Color(0xFF9CA4C2),
    surfaceContainerHigh = Color(0xFF1B2033),
    surfaceContainerLow = Color(0xFF151A2A),
    outline = Color(0xFF3A4366),
    outlineVariant = Color(0xFF2A3150),
    error = Color(0xFFFF7070),
    onError = Night,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color.White,
    background = Color(0xFFF6F4EF),
    onBackground = Ink,
    surface = Color(0xFFF6F4EF),
    onSurface = Ink,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF5C5F6B),
    surfaceContainerHigh = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF7),
    outline = Color(0xFFCFCABD),
    outlineVariant = Color(0xFFE2DED4),
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
