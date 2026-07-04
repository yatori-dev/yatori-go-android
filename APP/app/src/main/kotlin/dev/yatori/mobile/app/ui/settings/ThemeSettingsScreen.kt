package dev.yatori.mobile.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.ui.common.SectionLabel
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.theme.PageAnim
import dev.yatori.mobile.app.ui.theme.ThemeMode
import dev.yatori.mobile.app.ui.theme.ThemeState
import dev.yatori.mobile.app.ui.theme.accentColorNames
import dev.yatori.mobile.app.ui.theme.accentColorOptions
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

private const val SCALE_MIN = 0.80f
private const val SCALE_MAX = 1.20f

@Composable
fun ThemeSettingsScreen(nav: Navigator, state: ThemeState, onChange: (ThemeState) -> Unit) {
    val modes = listOf("跟随系统", "浅色", "深色")
    val modeIndex = when (state.mode) {
        ThemeMode.SYSTEM -> 0; ThemeMode.LIGHT -> 1; ThemeMode.DARK -> 2
    }
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val anims = listOf("滚动", "渐变")
    val pageAnimIndex = if (state.pageAnim == PageAnim.FADE) 1 else 0
    val tabAnimIndex = if (state.tabAnim == PageAnim.FADE) 1 else 0

    // Draft scale — local during drag, committed to ThemePrefs only on onValueChangeFinished /
    // dialog confirm. remember(state.uiScale) resets on external change but NOT during the drag.
    var draftScale by remember(state.uiScale) { mutableFloatStateOf(state.uiScale) }
    var showScaleDialog by remember { mutableStateOf(false) }

    SecondaryScaffold(title = "主题设置", nav = nav) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel("深色模式")
            TabRow(
                tabs = modes,
                selectedTabIndex = modeIndex,
                onTabSelected = { idx ->
                    onChange(state.copy(mode = when (idx) { 0 -> ThemeMode.SYSTEM; 2 -> ThemeMode.DARK; else -> ThemeMode.LIGHT }))
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            SectionLabel("配色")
            Card {
                // KernelSU layout: a single Monet switch. When on, reveal the accent dropdown
                // (默认 = wallpaper); when an accent is chosen, reveal palette-style + color-spec.
                SwitchPreference(
                    title = "启用 Monet 颜色",
                    startAction = { PrefLeadingIcon(Icons.Rounded.Wallpaper) },
                    checked = state.monet,
                    onCheckedChange = { v -> onChange(state.copy(monet = v)) },
                )
                if (state.monet) {
                    val colorValues = remember { listOf(0) + accentColorOptions }
                    val colorNames = remember { listOf("默认") + accentColorNames }
                    OverlayDropdownPreference(
                        title = "强调色",
                        startAction = { PrefLeadingIcon(Icons.Rounded.Colorize) },
                        items = colorNames,
                        selectedIndex = colorValues.indexOf(state.keyColor).coerceAtLeast(0),
                        onSelectedIndexChange = { idx -> onChange(state.copy(keyColor = colorValues[idx])) },
                    )
                    if (state.keyColor != 0) {
                        val styles = remember { ThemePaletteStyle.entries.map { it.name } }
                        val specs = remember { ThemeColorSpec.entries.map { it.name } }
                        OverlayDropdownPreference(
                            title = "色彩风格",
                            startAction = { PrefLeadingIcon(Icons.Rounded.Style) },
                            items = styles,
                            selectedIndex = styles.indexOf(state.paletteStyle).coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> onChange(state.copy(paletteStyle = styles[idx])) },
                        )
                        OverlayDropdownPreference(
                            title = "色彩标准",
                            startAction = { PrefLeadingIcon(Icons.Rounded.DesignServices) },
                            items = specs,
                            selectedIndex = specs.indexOf(state.colorSpec).coerceAtLeast(0),
                            onSelectedIndexChange = { idx -> onChange(state.copy(colorSpec = specs[idx])) },
                        )
                    }
                }
            }

            SectionLabel("页面切换动画")
            TabRow(
                tabs = anims,
                selectedTabIndex = pageAnimIndex,
                onTabSelected = { idx ->
                    onChange(state.copy(pageAnim = if (idx == 1) PageAnim.FADE else PageAnim.SLIDE))
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            SectionLabel("主页切换动画")
            TabRow(
                tabs = anims,
                selectedTabIndex = tabAnimIndex,
                onTabSelected = { idx ->
                    onChange(state.copy(tabAnim = if (idx == 1) PageAnim.FADE else PageAnim.SLIDE))
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            SectionLabel("界面")
            Card {
                // Blur is independent (KernelSU-style): it frosts the top bar (and the floating
                // bottom bar when present) on its own — it does NOT require the floating bar.
                SwitchPreference(
                    title = "模糊",
                    summary = "启用顶栏和底栏的模糊效果",
                    startAction = { PrefLeadingIcon(Icons.Rounded.BlurOn) },
                    checked = state.blur,
                    onCheckedChange = { v ->
                        // Disabling blur clears liquid glass (glass requires blur)
                        onChange(state.copy(blur = v, liquidGlass = if (!v) false else state.liquidGlass))
                    },
                    enabled = blurSupported,
                )
                SwitchPreference(
                    title = "悬浮底栏",
                    summary = "使用 Apple 风格的悬浮底栏",
                    startAction = { PrefLeadingIcon(Icons.Rounded.CallToAction) },
                    checked = state.floatingBar,
                    onCheckedChange = { v ->
                        // Only liquid glass lives on the floating bar; blur stays independent.
                        onChange(state.copy(floatingBar = v, liquidGlass = if (!v) false else state.liquidGlass))
                    },
                )
                SwitchPreference(
                    title = "液态玻璃",
                    summary = "启用悬浮底栏的液态玻璃效果",
                    startAction = { PrefLeadingIcon(Icons.Rounded.WaterDrop) },
                    checked = state.liquidGlass,
                    onCheckedChange = { v ->
                        // liquidGlass sits on the floating bar and needs blur; toggling on auto-enables blur
                        onChange(state.copy(liquidGlass = v, blur = if (v) true else state.blur))
                    },
                    enabled = state.floatingBar,
                )
                SwitchPreference(
                    title = "预测性返回手势",
                    summary = "启用对预测性返回手势的支持",
                    startAction = { PrefLeadingIcon(Icons.AutoMirrored.Rounded.MenuOpen) },
                    checked = state.predictiveBack,
                    onCheckedChange = { onChange(state.copy(predictiveBack = it)) },
                )
            }

            SectionLabel("界面缩放")
            Card {
                // KernelSU-style: ArrowPreference whose end shows the live percentage and whose
                // bottom hosts a snapping slider; tapping the row opens a numeric-input dialog.
                ArrowPreference(
                    title = "界面缩放",
                    summary = "缩放整个界面的布局与文字",
                    startAction = { PrefLeadingIcon(Icons.Rounded.AspectRatio) },
                    endActions = {
                        Text(
                            text = "${(draftScale * 100).toInt()}%",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    onClick = { showScaleDialog = true },
                    holdDownState = showScaleDialog,
                    bottomAction = {
                        Slider(
                            value = draftScale,
                            onValueChange = { draftScale = it },
                            onValueChangeFinished = { onChange(state.copy(uiScale = draftScale)) },
                            valueRange = SCALE_MIN..SCALE_MAX,
                            showKeyPoints = true,
                            keyPoints = listOf(0.80f, 0.90f, 1.00f, 1.10f, 1.20f),
                            magnetThreshold = 0.02f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }
    }

    ScaleDialog(
        show = showScaleDialog,
        current = { state.uiScale },
        onDismiss = { showScaleDialog = false },
        onConfirm = { onChange(state.copy(uiScale = it)) },
    )
}

/** Numeric-entry dialog for the exact UI-scale percentage (80%–120%), KernelSU ScaleDialog port. */
@Composable
private fun ScaleDialog(
    show: Boolean,
    current: () -> Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "界面缩放",
        summary = "${(SCALE_MIN * 100).toInt()}% - ${(SCALE_MAX * 100).toInt()}%",
        onDismissRequest = onDismiss,
    ) {
        var text by remember(show) { mutableStateOf((current() * 100).toInt().toString()) }
        TextField(
            value = text,
            onValueChange = { v -> if (v.isEmpty() || v.all { it.isDigit() }) text = v },
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val min = (SCALE_MIN * 100).toInt()
                    val max = (SCALE_MAX * 100).toInt()
                    val clamped = text.toIntOrNull()?.coerceIn(min, max) ?: (current() * 100).toInt()
                    onConfirm(clamped / 100f)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text("确定", color = MiuixTheme.colorScheme.onPrimary) }
        }
    }
}
