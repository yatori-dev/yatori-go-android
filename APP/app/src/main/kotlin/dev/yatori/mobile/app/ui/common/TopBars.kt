package dev.yatori.mobile.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.theme.LocalThemeState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Captures a content backdrop for top-bar blur when the theme blur switch is ON (and the device
 * supports RenderEffect, API 33+). Returns null otherwise so callers fall back to a solid bar.
 * Mirrors KernelSU's BlurredBar: the bar samples this layer and frosts the content behind it.
 */
@Composable
fun rememberBarBackdrop(): LayerBackdrop? {
    val blurOn = LocalThemeState.current.blur
    val surface = MiuixTheme.colorScheme.surface
    return if (blurOn && isRenderEffectSupported()) {
        rememberLayerBackdrop { drawRect(surface); drawContent() }
    } else {
        null
    }
}

/** Frosted-blur background for a top bar that samples [backdrop]; no-op when [backdrop] is null. */
fun Modifier.topBarBlur(backdrop: LayerBackdrop?, tint: Color): Modifier =
    if (backdrop == null) {
        this
    } else {
        this.textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = 24f,
            colors = BlurColors(listOf(BlendColorEntry(color = tint))),
        )
    }

/** Wraps screen content so a [backdrop] (when non-null) captures it for the bar to blur. */
fun Modifier.barBackdropLayer(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else this.layerBackdrop(backdrop)

/** Large-title top bar for tab roots (Home, Logs). Actions are icon-only per the blueprint. */
@Composable
fun YatoriLargeTopBar(
    title: String,
    scrollBehavior: ScrollBehavior? = null,
    color: Color = Color.Transparent,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        color = color,
        scrollBehavior = scrollBehavior,
        actions = actions,
    )
}

/** Small top bar with a back arrow for secondary screens. */
@Composable
fun SecondaryTopBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: ScrollBehavior? = null,
    color: Color = MiuixTheme.colorScheme.surface,
    actions: @Composable RowScope.() -> Unit = {},
) {
    SmallTopAppBar(
        title = title,
        color = color,
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(MiuixIcons.Back, contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
        actions = actions,
    )
}

/** Large collapsing top bar with a back arrow for secondary screens (KernelSU theme-screen style). */
@Composable
fun SecondaryLargeTopBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: ScrollBehavior? = null,
    color: Color = MiuixTheme.colorScheme.surface,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        color = color,
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(MiuixIcons.Back, contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground)
            }
        },
        actions = actions,
    )
}

/**
 * Standard wrapper for a secondary (pushed) screen: a large collapsing back-arrow top bar plus a
 * Miuix [Scaffold] so dialogs/popups (renderInRootScaffold) work. The big title collapses into the
 * bar as the content scrolls (same behaviour as the tab roots) — the nested-scroll connection is
 * registered on the content wrapper, so any scrollable child (LazyColumn / verticalScroll) drives
 * the collapse without each screen having to wire it up. Content receives the inner padding.
 *
 * When the theme blur switch is on, the top bar frosts the content scrolling beneath it
 * (KernelSU-style) instead of using a flat opaque surface.
 */
@Composable
fun SecondaryScaffold(
    title: String,
    nav: Navigator,
    actions: @Composable RowScope.() -> Unit = {},
    onBack: () -> Unit = { nav.pop() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberBarBackdrop()
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.7f)
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            Box(Modifier.topBarBlur(backdrop, barTint)) {
                SecondaryLargeTopBar(
                    title = title,
                    onBack = onBack,
                    scrollBehavior = scrollBehavior,
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    actions = actions,
                )
            }
        },
        content = { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .barBackdropLayer(backdrop),
            ) {
                content(innerPadding)
            }
        },
    )
}

/** Convenience: an icon-only top-bar action button with required contentDescription. */
@Composable
fun TopBarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = contentDescription, tint = MiuixTheme.colorScheme.onBackground)
    }
}
