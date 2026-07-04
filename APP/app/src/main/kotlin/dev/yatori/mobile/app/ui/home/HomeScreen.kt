package dev.yatori.mobile.app.ui.home

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.platform.platformDisplayName
import dev.yatori.mobile.app.ui.common.LogListItem
import dev.yatori.mobile.app.ui.common.SectionLabel
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.common.WarningCard
import dev.yatori.mobile.app.ui.common.barBackdropLayer
import dev.yatori.mobile.app.ui.common.operationSummaryText
import dev.yatori.mobile.app.ui.common.rememberBarBackdrop
import dev.yatori.mobile.app.ui.common.topBarBlur
import dev.yatori.mobile.app.ui.logs.logDisplayName
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.app.ui.nav.Tab
import dev.yatori.mobile.app.ui.theme.isInDarkTheme
import dev.yatori.mobile.app.ui.theme.LocalThemeState
import dev.yatori.mobile.api.dto.HealthInfo
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.runtime.operation.OperationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private sealed interface CoreUiState {
    data object Loading : CoreUiState
    data class Ready(val health: HealthInfo) : CoreUiState
    data class Error(val message: String) : CoreUiState
}

/**
 * Home dashboard (KernelSU normal-dashboard style): large title + refresh action; a status
 * grid (large Core status card + two metric cards); a running-task section; and a recent-events
 * section. Reads only through the repository / operation controller / log store.
 */
@Composable
fun HomeScreen(container: AppContainer, nav: Navigator, bottomPadding: androidx.compose.ui.unit.Dp) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var core by remember { mutableStateOf<CoreUiState>(CoreUiState.Loading) }
    var accountCount by remember { mutableStateOf(0) }
    var courseCount by remember { mutableStateOf(0) }
    var recent by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    val operations by container.operationController.operations.collectAsState()
    val questionHistory by container.operationController.questionHistory.collectAsState()
    val pendingTaskChallenges by container.pendingTaskChallengesFlow.collectAsState()
    val pendingAnswerEdits by container.pendingAnswerEditsFlow.collectAsState()
    val updateState by container.updateController.state.collectAsState()
    val pendingTaskChallenge = pendingTaskChallenges.firstOrNull()
    val pendingAnswerEdit = pendingAnswerEdits.firstOrNull()
    // Pre-compute the set once per questionHistory change instead of scanning inside the render loop.
    val questionHistoryOpIds = remember(questionHistory) { questionHistory.map { it.operationId }.toHashSet() }
    val hasFinishedOp = remember(operations) { operations.any { it.status == OperationStatus.DONE || it.status == OperationStatus.FAILED } }

    // showToast=true → manual refresh via the top-bar button (gives a "刷新成功" toast).
    // showToast=false → silent auto-refresh fired when the user returns to the Home tab
    // (Home leaves composition on tab switch, so LaunchedEffect(Unit) re-runs on return).
    // While the user stays on Home nothing polls, so there is no background churn.
    fun refresh(showToast: Boolean) {
        scope.launch {
            core = CoreUiState.Loading
            runCatching {
                container.ensureCoreReady()
                repo.healthCheck()
            }.onSuccess { health ->
                core = CoreUiState.Ready(health)
                runCatching { repo.pollLogs() }
                recent = repo.currentSessionLogs().takeLast(5).reversed()
                if (showToast) Toast.makeText(context, "刷新成功", Toast.LENGTH_SHORT).show()
            }.onFailure {
                core = CoreUiState.Error(it.message ?: "Core 初始化失败")
                if (showToast) Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show()
            }
            accountCount = repo.listSessions().size
            courseCount = container.cachedCourseCount()
        }
    }

    LaunchedEffect(Unit) { refresh(showToast = false) }

    // Refresh recent events every 10 s while home tab is in composition.
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            runCatching { repo.pollLogs() }
            recent = repo.currentSessionLogs().takeLast(5).reversed()
        }
    }

    val barBackdrop = rememberBarBackdrop()
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.7f)
    // Cache disk reads; recompute only when accountCount changes (i.e. after refresh).
    val logSessions = remember(accountCount) { repo.listSessions() }
    val logConfig = remember(accountCount) { repo.loadSavedConfig() }
    Scaffold(
        topBar = {
            androidx.compose.foundation.layout.Box(Modifier.topBarBlur(barBackdrop, barTint)) {
                TopAppBar(
                    title = "YatoriGoAndroid",
                    color = if (barBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TopBarAction(Icons.Rounded.Refresh, "刷新", onClick = { refresh(showToast = true) })
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().barBackdropLayer(barBackdrop).nestedScroll(scrollBehavior.nestedScrollConnection).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding + 16.dp,
            ),
        ) {
            item {
                AnimatedVisibility(
                    visible = updateState.latestRelease != null,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    WarningCard(
                        message = "新版本 ${updateState.latestRelease?.tagName.orEmpty()} 可用，请去“关于”页查看更新。",
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        onClick = { nav.push(Route.About) },
                    )
                }
            }

            item { StatusGrid(core, accountCount, courseCount, onRetry = { refresh(showToast = true) }) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 18.dp, bottom = 6.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "当前运行",
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                    if (hasFinishedOp) {
                        Text(
                            "清理记录",
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { container.operationController.clearFinished() },
                            ),
                            color = MiuixTheme.colorScheme.primary,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    if (pendingTaskChallenge != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = if (operations.isEmpty()) 0.dp else 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                text = "手动输入验证码",
                                onClick = { nav.push(Route.ChallengeManual(pendingTaskChallenge.id)) },
                            )
                        }
                    }
                    if (pendingAnswerEdit != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = if (operations.isEmpty()) 0.dp else 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                text = if (pendingAnswerEdit.scope == "bbs") "编辑内容" else "编辑答案",
                                onClick = { nav.push(Route.AnswerEdit(pendingAnswerEdit.id)) },
                            )
                        }
                    }
                    if (operations.isEmpty()) {
                        Text("暂无任务运行", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
                    } else {
                        operations.forEachIndexed { index, op ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(0.5.dp)
                                        .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f))
                                )
                            }
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(operationDisplayTitle(op, logConfig), fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                                val hasQuestionHistory = questionHistoryOpIds.contains(op.id)
                                val showActions = op.status == OperationStatus.RUNNING || hasQuestionHistory
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        operationSummaryText(op.status, op.completed, op.total, op.detail),
                                        modifier = Modifier.weight(1f),
                                        style = MiuixTheme.textStyles.body2,
                                        color = when (op.status) {
                                            OperationStatus.RUNNING -> MiuixTheme.colorScheme.primary
                                            OperationStatus.FAILED  -> MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                            else                    -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                    )
                                    if (showActions) {
                                        if (hasQuestionHistory) {
                                            Text(
                                                "题目历史",
                                                modifier = Modifier.padding(start = 12.dp).clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = { nav.push(Route.QuestionHistory(op.id)) },
                                                ),
                                                color = MiuixTheme.colorScheme.primary,
                                                style = MiuixTheme.textStyles.body2,
                                            )
                                        }
                                        if (op.status == OperationStatus.RUNNING) {
                                            Text(
                                                "取消",
                                                modifier = Modifier.padding(start = 12.dp).clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = {
                                                        container.operationController.cancel(op.id)
                                                        container.cancelPendingTaskChallenge()
                                                        container.cancelPendingAnswerEdit()
                                                    },
                                                ),
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                style = MiuixTheme.textStyles.body2,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { SectionLabel("最近事件") }
            if (recent.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text("暂无事件", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
                    }
                }
            } else {
                items(recent) { entry ->
                    LogListItem(
                        entry,
                        title = logDisplayName(entry, logSessions, logConfig),
                        onClick = { nav.selectTab(Tab.LOGS) },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusGrid(core: CoreUiState, accountCount: Int, courseCount: Int, onRetry: () -> Unit) {
    val dark = isInDarkTheme()
    // "启用 Monet 颜色" on (wallpaper or accent seed) → the status card + glyph follow the themed
    // scheme (KernelSU HomeMiuix pattern: secondaryContainer card + primary glyph); off → the
    // per-state semantic tints.
    val dynamic = LocalThemeState.current.monet
    val cardColor = if (dynamic) MiuixTheme.colorScheme.secondaryContainer else statusCardColor(core, dark)
    val glyphTint = if (dynamic) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f) else statusIconTint(core)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: Core status card (1:1 with the metric column — KernelSU dashboard ratio).
        // Clickable with Tilt press-feedback + long-press (no navigation target yet — interaction only).
        Card(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = cardColor),
            pressFeedbackType = PressFeedbackType.Tilt,
            showIndication = true,
            onClick = {},
            onLongPress = {},
        ) {
            Box(Modifier.fillMaxSize()) {
                // Oversized status glyph anchored to the bottom-end corner as a soft accent.
                Box(Modifier.fillMaxSize().offset(x = 38.dp, y = 45.dp), contentAlignment = Alignment.BottomEnd) {
                    Icon(statusIcon(core), contentDescription = null, tint = glyphTint, modifier = Modifier.size(170.dp))
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(statusTitle(core), fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(statusSubtitle(core), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (core is CoreUiState.Error) {
                        Spacer(Modifier.height(10.dp))
                        top.yukonga.miuix.kmp.basic.TextButton(text = "重试初始化", onClick = onRetry)
                    }
                }
            }
        }
        // Right: two stacked metric cards (matching weight → balanced 1:1 grid).
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("账号", accountCount, Modifier.weight(1f))
            MetricCard("课程", courseCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Int, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = {},
        onLongPress = {},
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("$value", fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}

private fun statusTitle(core: CoreUiState) = when (core) {
    is CoreUiState.Loading -> "Core 初始化中"
    is CoreUiState.Ready -> if (core.health.configured) "Core 就绪" else "Core 已初始化"
    is CoreUiState.Error -> "Core 异常"
}

private fun statusSubtitle(core: CoreUiState) = when (core) {
    is CoreUiState.Loading -> "正在连接 Go 核心…"
    is CoreUiState.Ready -> "版本：v${core.health.version.removeSuffix("-mobile")}"
    is CoreUiState.Error -> core.message
}

private fun statusIcon(core: CoreUiState) = when (core) {
    is CoreUiState.Ready -> Icons.Rounded.CheckCircleOutline
    else -> Icons.Rounded.ErrorOutline
}

// Vivid corner glyph in BOTH light and dark — mirrors KernelSU's full-opacity check (0xFF36D167).
private fun statusIconTint(core: CoreUiState): Color = when (core) {
    is CoreUiState.Ready -> Color(0xFF36D167)
    is CoreUiState.Error -> Color(0xFFE53935)
    else -> Color(0xFFFB8C00)
}

// Card background follows the theme — light tints in light mode, deep desaturated tints in dark.
private fun statusCardColor(core: CoreUiState, dark: Boolean): Color = when (core) {
    is CoreUiState.Ready -> if (dark) Color(0xFF1A3825) else Color(0xFFDFF3E4)
    is CoreUiState.Error -> if (dark) Color(0xFF3A1E1E) else Color(0xFFFBE3E3)
    else -> if (dark) Color(0xFF3A2E1A) else Color(0xFFFFF1DD)
}

private fun accountSemiHidden(account: String): String {
    val at = account.indexOf('@')
    val local = if (at > 0) account.substring(0, at) else account
    val domain = if (at > 0) account.substring(at) else ""
    return if (local.length <= 6) local + domain
    else local.take(4) + "..." + local.takeLast(2) + domain
}

private fun operationDisplayTitle(
    op: dev.yatori.mobile.runtime.operation.Operation,
    config: dev.yatori.mobile.api.dto.MobileConfig?,
): String {
    val platformName = platformDisplayName(op.platform, fallback = op.platform)
    val remark = config?.users
        ?.firstOrNull { u ->
            u.asJsonObject?.get("account")?.asString == op.account &&
                u.asJsonObject?.get("accountType")?.asString.orEmpty().equals(op.platform, ignoreCase = true)
        }
        ?.asJsonObject?.get("remarkName")?.asString.orEmpty()
    val accountPart = if (remark.isNotBlank()) {
        "$remark（${accountSemiHidden(op.account)}）"
    } else {
        op.account
    }
    return "$platformName · $accountPart"
}
