package dev.yatori.mobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * Resolved dark-mode flag for the current theme. Provided in MainActivity from the same
 * `isDark` that drives MiuixTheme, so any composable can branch colors on it without
 * threading a parameter. Mirrors KernelSU's `isInDarkTheme()`.
 */
val LocalIsDark = staticCompositionLocalOf { false }

/**
 * The current theme/UI preference snapshot, provided in MainActivity. Lets any screen read
 * flags like [ThemeState.blur] without threading them through every composable — used by the
 * top-bar blur in secondary scaffolds.
 */
val LocalThemeState = staticCompositionLocalOf { ThemeState() }

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = LocalIsDark.current

/**
 * Yatori theme — wraps MiuixTheme with the correct color scheme.
 * UI scale (LocalDensity override) is applied in YatoriApp, BELOW Navigator creation,
 * so themeState changes never rebuild the Navigator or clear the back stack.
 */
@Composable
fun YatoriTheme(
    state: ThemeState,
    content: @Composable (isDark: Boolean) -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (state.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }
    // KernelSU model: the "启用 Monet 颜色" switch (state.monet) is the master. When on, the palette
    // is dynamic — seeded from a chosen accent (keyColor) if set, otherwise from the wallpaper. When
    // off, the plain default scheme is used and any stored accent is ignored.
    val mode = when {
        state.monet && state.mode == ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
        state.monet && state.mode == ThemeMode.LIGHT  -> ColorSchemeMode.MonetLight
        state.monet && state.mode == ThemeMode.DARK   -> ColorSchemeMode.MonetDark
        state.mode == ThemeMode.SYSTEM -> ColorSchemeMode.System
        state.mode == ThemeMode.DARK   -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.Light
    }
    val keyColor = if (state.monet && state.keyColor != 0) Color(state.keyColor) else null
    val paletteStyle = runCatching { ThemePaletteStyle.valueOf(state.paletteStyle) }
        .getOrDefault(ThemePaletteStyle.TonalSpot)
    val colorSpec = runCatching { ThemeColorSpec.valueOf(state.colorSpec) }
        .getOrDefault(ThemeColorSpec.Spec2021)
    val controller = remember(mode, isDark, state.monet, state.keyColor, paletteStyle, colorSpec) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = keyColor,
            isDark = isDark,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec,
        )
    }
    MiuixTheme(controller = controller) { content(isDark) }
}
