package dev.yatori.mobile.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yatori.mobile.app.BuildConfig
import dev.yatori.mobile.app.R
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.SectionLabel
import dev.yatori.mobile.app.ui.common.effect.BgEffectBackground
import dev.yatori.mobile.app.ui.common.effect.ColorBlendToken
import dev.yatori.mobile.app.ui.nav.DocKind
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.app.ui.theme.isInDarkTheme
import dev.yatori.mobile.app.update.ReleaseInfo
import dev.yatori.mobile.app.update.UpdateResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

/**
 * About page: a HyperOS / KernelSU-style scene — the project logo + wordmark + version floating over
 * a runtime-shader flowing-light (流光律动) background. The logo, wordmark and the link cards all use
 * [textureBlur] color-blending so they pick up (映射) the flowing-light colors behind them. Scrolling
 * parallax-fades the logo block while the top-bar title cross-fades in. Carries over the developer /
 * upstream / repo links plus the terms / privacy / notice cards migrated from the old 存储与安全 page.
 */
@Composable
fun AboutScreen(container: AppContainer, nav: Navigator) {
    val context = LocalContext.current
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val updateState by container.updateController.state.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var coreVersion by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        runCatching { repo.healthCheck() }.onSuccess { coreVersion = it.version.removeSuffix("-mobile") }
    }
    val openUrl: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    val checkUpdate: () -> Unit = checkUpdate@{
        val knownRelease = updateState.latestRelease
        if (knownRelease != null) {
            Toast.makeText(context, "检测到新版本 ${knownRelease.tagName}", Toast.LENGTH_SHORT).show()
            return@checkUpdate
        }
        if (!updateState.checking) {
            scope.launch {
                when (val result = container.updateController.checkManually()) {
                    is UpdateResult.NewVersion ->
                        Toast.makeText(context, "检测到新版本 ${result.release.tagName}", Toast.LENGTH_SHORT).show()
                    is UpdateResult.UpToDate ->
                        Toast.makeText(context, "已经是最新版", Toast.LENGTH_SHORT).show()
                    is UpdateResult.Error ->
                        Toast.makeText(context, "检测更新失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                if (spacer != null && spacer.size > 0) {
                    (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }
    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "关于",
                scrollBehavior = scrollBehavior,
                color = if (collapsed) MiuixTheme.colorScheme.surface else Color.Transparent,
                titleColor = MiuixTheme.colorScheme.onSurface.copy(
                    alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                ),
                defaultWindowInsetsPadding = false,
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground)
                    }
                },
            )
        },
    ) { innerPadding ->
        AboutContent(
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            lazyListState = lazyListState,
            scrollProgressProvider = { scrollProgress },
            appVersion = BuildConfig.VERSION_NAME,
            coreVersion = coreVersion,
            latestRelease = updateState.latestRelease,
            checkingUpdate = updateState.checking,
            onCheckUpdate = checkUpdate,
            onShowUpdate = { showUpdateDialog = true },
            onOpenUrl = openUrl,
            onTerms = { nav.push(Route.StaticDoc(DocKind.TERMS)) },
            onPrivacy = { nav.push(Route.StaticDoc(DocKind.PRIVACY)) },
            onLicense = { nav.push(Route.StaticDoc(DocKind.LICENSE)) },
        )
    }

    UpdateDialog(
        show = showUpdateDialog && updateState.latestRelease != null,
        release = updateState.latestRelease,
        currentVersion = BuildConfig.VERSION_NAME,
        onOpenUrl = openUrl,
        onDismiss = { showUpdateDialog = false },
    )
}

@Composable
private fun AboutContent(
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
    appVersion: String,
    coreVersion: String,
    latestRelease: ReleaseInfo?,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onShowUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onLicense: () -> Unit,
) {
    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    val isDark = isInDarkTheme()
    val effectOn = remember { isRuntimeShaderSupported() }
    val topPad = innerPadding.calculateTopPadding()

    val backdrop = rememberLayerBackdrop()

    // Blend stacks: logo/wordmark glow (映射 the flowing light through their silhouette via DstIn);
    // cards pick up a softer tint of the same background.
    val logoBlend = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xE6A1A1A1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xFF1AF500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xCC4A4A4A), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xFF4F4F4F), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xFF1AF200), BlurBlendMode.Lab),
            )
        }
    }
    val cardBlend = remember(isDark) {
        if (isDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    }
    val logoBlockAlpha = (1f - scrollProgressProvider()).coerceIn(0f, 1f)
    val heroProgress = scrollProgressProvider()
    val logoBlockClickable = heroProgress < 0.22f && !checkingUpdate && latestRelease == null
    val updatePillAlpha = (1f - (heroProgress / 0.32f)).coerceIn(0f, 1f)
    val updatePillClickable = updatePillAlpha > 0.98f

    BgEffectBackground(
        dynamicBackground = effectOn,
        isFullSize = true,
        effectBackground = effectOn,
        modifier = Modifier.fillMaxSize(),
        bgModifier = Modifier.layerBackdrop(backdrop),
        alpha = { 1f - scrollProgressProvider() },
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = topPad),
        ) {
            // Transparent spacer the height of the logo block, so it shows through underneath.
            item(key = "logoSpacer") {
                Box(Modifier.fillMaxWidth().height(logoHeightDp + 218.dp))
            }

            item(key = "about") {
                Column(
                    modifier = Modifier
                        .padding(bottom = innerPadding.calculateBottomPadding() + 12.dp),
                ) {
                    BlendCard(backdrop, cardBlend, effectOn) {
                        ArrowPreference(
                            title = "开发 | Rentz",
                            summary = "@Rentz412",
                            onClick = { onOpenUrl("https://github.com/Rentz412") },
                        )
                        ArrowPreference(
                            title = "基于 Yatori-Go-Core 项目",
                            summary = "@yatori-dev",
                            onClick = { onOpenUrl("https://github.com/yatori-dev/yatori-go-core") },
                        )
                        ArrowPreference(
                            title = "项目仓库",
                            summary = "如果该项目对你有帮助，请点一个免费的 Star ⭐",
                            onClick = { onOpenUrl("https://github.com/yatori-dev/yatori-go-android") },
                        )
                    }

                    SectionLabel("开源许可")
                    BlendCard(backdrop, cardBlend, effectOn) {
                        ArrowPreference(
                            title = "Miuix",
                            summary = "Apache-2.0 · compose-miuix-ui",
                            onClick = { onOpenUrl("https://github.com/miuix-kotlin-multiplatform/miuix") },
                        )
                        ArrowPreference(
                            title = "KernelSU",
                            summary = "GPL-3.0 · UI 参考",
                            onClick = { onOpenUrl("https://github.com/tiann/KernelSU") },
                        )
                        ArrowPreference(
                            title = "ONNX Runtime",
                            summary = "MIT · 验证码 OCR",
                            onClick = { onOpenUrl("https://github.com/microsoft/onnxruntime") },
                        )
                        ArrowPreference(
                            title = "完整开源许可",
                            onClick = onLicense,
                        )
                    }

                    SectionLabel("协议")
                    BlendCard(backdrop, cardBlend, effectOn) {
                        ArrowPreference(title = "用户协议", onClick = onTerms)
                        ArrowPreference(title = "隐私协议", onClick = onPrivacy)
                    }

                    SectionLabel("说明")
                    BlendCard(backdrop, cardBlend, effectOn) {
                        Text(
                            "Yatori 是非盈利学习辅助工具，请遵守所在平台的使用规范，合规使用。",
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // Guard: when fully scrolled (collapsed), remove from composition entirely so it
        // neither intercepts touches nor blocks the LazyColumn from scrolling in that region.
        val collapsed = scrollProgressProvider() >= 1f
        if (!collapsed) Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPad + 92.dp)
                .graphicsLayer {
                    alpha = logoBlockAlpha
                }
                .onSizeChanged { size -> with(density) { logoHeightDp = size.height.toDp() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.then(
                    if (logoBlockClickable) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCheckUpdate,
                        )
                    } else {
                        Modifier
                    },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Centered hero logo — a full-color PNG shown as-is (no tint/blend; the SVG silhouette
                // approach looked wrong here). Still parallax-fades with scroll like the rest of the block.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(112.dp)
                        .graphicsLayer {
                            val p = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                            alpha = 1 - p
                            scaleX = 1 - (p * 0.05f)
                            scaleY = 1 - (p * 0.05f)
                        },
                ) {
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        painter = painterResource(R.drawable.yatori_logo2),
                        contentDescription = null,
                    )
                }
                Image(
                    modifier = Modifier
                        .padding(top = 14.dp, bottom = 6.dp)
                        .height(30.dp)
                        .graphicsLayer {
                            val p = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                            alpha = 1 - p
                            scaleX = 1 - (p * 0.05f)
                            scaleY = 1 - (p * 0.05f)
                        }
                        .then(
                            if (effectOn) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(0.dp),
                                    blurRadius = 150f,
                                    colors = BlurColors(blendColors = logoBlend),
                                    contentBlendMode = ComposeBlendMode.DstIn,
                                    enabled = true,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    painter = painterResource(R.drawable.yatori_title),
                    contentDescription = "YatoriGoAndroid",
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                )
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                            alpha = 1 - p
                            scaleX = 1 - (p * 0.05f)
                            scaleY = 1 - (p * 0.05f)
                        },
                    text = "v$appVersion APP · v$coreVersion Core",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedVisibility(
                visible = latestRelease != null,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                UpdatePill(
                    modifier = Modifier
                        .padding(top = 56.dp)
                        .graphicsLayer { alpha = updatePillAlpha },
                    backdrop = backdrop,
                    blend = cardBlend,
                    effectOn = effectOn,
                    enabled = updatePillClickable,
                    onClick = onShowUpdate,
                )
            }
        }
    }
}

/** A Miuix [Card] that, when [effectOn], color-blends the flowing-light backdrop through itself. */
@Composable
private fun BlendCard(
    backdrop: LayerBackdrop,
    blend: List<BlendColorEntry>,
    effectOn: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .then(
                if (effectOn) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = 60f,
                        colors = BlurColors(blendColors = blend),
                        enabled = true,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            color = if (effectOn) Color.Transparent else MiuixTheme.colorScheme.surface,
        ),
        content = { content() },
    )
}

@Composable
private fun UpdatePill(
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop,
    blend: List<BlendColorEntry>,
    effectOn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth(0.72f)
            .widthIn(min = 188.dp, max = 300.dp)
            .then(
                if (effectOn) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = 60f,
                        colors = BlurColors(blendColors = blend),
                        enabled = true,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            color = if (effectOn) Color.Transparent else MiuixTheme.colorScheme.surface,
        ),
        insideMargin = PaddingValues(0.dp),
        showIndication = enabled,
        pressFeedbackType = PressFeedbackType.None,
        onClick = if (enabled) onClick else null,
        onLongPress = if (enabled) ({}) else null,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "有新版本",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UpdateDialog(
    show: Boolean,
    release: ReleaseInfo?,
    currentVersion: String,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val rel = release ?: return
    OverlayDialog(
        show = show,
        title = "发现新版本 ${rel.tagName}",
        summary = "当前版本 v$currentVersion",
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Card(insideMargin = PaddingValues(12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "最新版本：${rel.tagName}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "推荐安装包：${rel.recommendedAssetName}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "连接通道：${rel.channelName}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (rel.body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Card(insideMargin = PaddingValues(12.dp)) {
                    Text(
                        text = rel.body,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "打开发布页",
                    onClick = {
                        onDismiss()
                        onOpenUrl(rel.releasePageUrl)
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onDismiss()
                        onOpenUrl(rel.recommendedDownloadUrl)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("下载推荐版本", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
