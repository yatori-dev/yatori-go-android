package dev.yatori.mobile.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.ConfirmDialog
import dev.yatori.mobile.app.ui.common.SectionLabel
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.DocKind
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.api.dto.MobileConfig
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LOG_LEVELS = listOf("debug", "info", "warn", "error")
private val LOG_MODELS = listOf("以视频为基准", "以课程为基准")

/** Advanced / diagnostics: Core config (logLevel/logModel), OCR diagnostics, log management. */
@Composable
fun AdvancedDiagnosticsScreen(container: AppContainer, nav: Navigator) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(repo.loadSavedConfig() ?: MobileConfig()) }
    var logLevelIndex by remember { mutableStateOf(LOG_LEVELS.indexOf(cfg.setting.basicSetting.logLevel).coerceAtLeast(1)) }
    var logModelIndex by remember { mutableStateOf(cfg.setting.basicSetting.logModel.coerceIn(0, 1)) }

    LaunchedEffect(Unit) {
        runCatching { repo.fetchConfig() }.onSuccess {
            cfg = it
            logLevelIndex = LOG_LEVELS.indexOf(it.setting.basicSetting.logLevel).coerceAtLeast(1)
            logModelIndex = it.setting.basicSetting.logModel.coerceIn(0, 1)
        }
    }

    fun saveBasic(level: String = LOG_LEVELS[logLevelIndex], model: Int = logModelIndex) {
        val newCfg = cfg.copy(setting = cfg.setting.copy(basicSetting = cfg.setting.basicSetting.copy(logLevel = level, logModel = model)))
        scope.launch { runCatching { repo.applyConfig(newCfg) }.onSuccess { cfg = newCfg } }
    }

    SecondaryScaffold(title = "高级 / 诊断", nav = nav) { innerPadding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
        ) {
            SectionLabel("Core 配置")
            Card {
                OverlayDropdownPreference(
                    title = "Core 日志等级",
                    summary = "Go 核心产生日志的等级（≠ 日志页查看过滤器）",
                    items = LOG_LEVELS,
                    selectedIndex = logLevelIndex,
                    onSelectedIndexChange = { logLevelIndex = it; saveBasic(level = LOG_LEVELS[it]) },
                )
                OverlayDropdownPreference(
                    title = "任务模式",
                    items = LOG_MODELS,
                    selectedIndex = logModelIndex,
                    onSelectedIndexChange = { logModelIndex = it; saveBasic(model = it) },
                )
            }

            SectionLabel("OCR")
            Card { ArrowPreference(title = "关于 OCR", summary = "OCR", onClick = { nav.push(Route.OcrDiagnostics) }) }

            SectionLabel("日志管理")
            Card { ArrowPreference(title = "历史日志", summary = "查看 / 清理历史日志文件", onClick = { nav.push(Route.LogHistory) }) }
        }
    }
}

/** About OCR: model info and platform support. Does not initialize either OCR model. */
@Composable
fun OcrDiagnosticsScreen(container: AppContainer, nav: Navigator) {
    SecondaryScaffold(title = "关于 OCR", nav = nav) { innerPadding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("引擎状态")
            Card(insideMargin = PaddingValues(16.dp)) {
                BasicComponent(title = "常规验证码", summary = "common_old.onnx")
                BasicComponent(title = "QSXT算术验证码", summary = "calc_det.onnx")
            }

            SectionLabel("平台支持")
            Card(insideMargin = PaddingValues(16.dp)) {
                BasicComponent(title = "英华学堂", summary = "常规验证码识别")
                BasicComponent(title = "重庆工程学院", summary = "常规验证码识别")
                BasicComponent(title = "学习通", summary = "常规验证码识别")
                BasicComponent(title = "青书学堂", summary = "算术验证码自动计算")
            }
        }
    }
}

/** Static markdown documents (terms / privacy / license) loaded from assets. */
@Composable
fun StaticDocScreen(nav: Navigator, which: DocKind) {
    val context = LocalContext.current
    val title = when (which) { DocKind.TERMS -> "用户协议"; DocKind.PRIVACY -> "隐私协议"; DocKind.LICENSE -> "开源许可" }
    val assetName = when (which) { DocKind.TERMS -> "terms.md"; DocKind.PRIVACY -> "privacy.md"; DocKind.LICENSE -> "license.md" }
    val text = remember(assetName) {
        runCatching { context.assets.open(assetName).bufferedReader().use { it.readText() } }.getOrDefault("（文档缺失）")
    }
    SecondaryScaffold(title = title, nav = nav) { innerPadding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
        ) {
            Text(text, fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackground)
        }
    }
}
