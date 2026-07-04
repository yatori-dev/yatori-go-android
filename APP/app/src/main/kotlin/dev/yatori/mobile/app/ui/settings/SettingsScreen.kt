package dev.yatori.mobile.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.BuildConfig
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.SectionLabel
import dev.yatori.mobile.app.ui.common.barBackdropLayer
import dev.yatori.mobile.app.ui.common.rememberBarBackdrop
import dev.yatori.mobile.app.ui.common.topBarBlur
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Main settings (KernelSU preference style, grouped cards). OCR is intentionally NOT here
 * (it lives under Advanced/Diagnostics) — OCR is a built-in capability, not a user setting.
 */
@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator, bottomPadding: Dp) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var coreVersion by remember { mutableStateOf("…") }

    LaunchedEffect(Unit) {
        runCatching { repo.healthCheck() }.onSuccess { coreVersion = it.version.removeSuffix("-mobile") }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val barBackdrop = rememberBarBackdrop()
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.7f)

    Scaffold(
        topBar = {
            androidx.compose.foundation.layout.Box(Modifier.topBarBlur(barBackdrop, barTint)) {
                TopAppBar(
                    title = "设置",
                    color = if (barBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .barBackdropLayer(barBackdrop)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding + 16.dp),
        ) {
            SectionLabel("主题设置")
            Card { ArrowPreference(title = "主题设置", summary = "外观、深色模式、悬浮底栏、手势、缩放", startAction = { PrefLeadingIcon(Icons.Rounded.Palette) }, onClick = { nav.push(Route.ThemeSettings) }) }

            SectionLabel("通知设置")
            Card { ArrowPreference(title = "邮件通知", summary = "SMTP 完成通知", startAction = { PrefLeadingIcon(Icons.Rounded.Email) }, onClick = { nav.push(Route.EmailNotification) }) }

            SectionLabel("答题设置")
            Card { ArrowPreference(title = "题库/AI配置", summary = "第三方题库配置、AI答题配置", startAction = { PrefLeadingIcon(Icons.Rounded.AutoAwesome) }, onClick = { nav.push(Route.AiSettings) }) }

            SectionLabel("关于")
            Card {
                ArrowPreference(title = "关于 Yatori-Go 项目", summary = "v${BuildConfig.VERSION_NAME} APP / v$coreVersion Core", startAction = { PrefLeadingIcon(Icons.Rounded.Info) }, onClick = { nav.push(Route.About) })
            }

            SectionLabel("高级 / 诊断")
            Card { ArrowPreference(title = "高级 / 诊断", summary = "Core 配置、OCR 诊断、日志管理", startAction = { PrefLeadingIcon(Icons.Rounded.Tune) }, onClick = { nav.push(Route.AdvancedDiagnostics) }) }
        }
    }
}

/** Leading icon for a settings preference row (KernelSU startAction style). */
@Composable
fun PrefLeadingIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}
