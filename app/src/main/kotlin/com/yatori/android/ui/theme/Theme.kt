package com.yatori.android.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF4F6EF7)
private val Secondary = Color(0xFF7C4DFF)
private val Tertiary = Color(0xFF00BCD4)
private val Error = Color(0xFFE53935)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    secondary = Secondary,
    onSecondary = Color.White,
    tertiary = Tertiary,
    error = Error,
    background = Color(0xFFF8F9FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F3FF),
    outline = Color(0xFFBEC6D8)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA8FF),
    onPrimary = Color(0xFF0A1980),
    primaryContainer = Color(0xFF1A2660),
    secondary = Color(0xFFBEA8FF),
    tertiary = Color(0xFF63E9FF),
    error = Color(0xFFFF6B6B),
    background = Color(0xFF0F1117),
    surface = Color(0xFF1A1D27),
    surfaceVariant = Color(0xFF242736)
)

@Composable
fun YatoriTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
