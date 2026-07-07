package dev.yatori.mobile.app.ui.courses

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.platform.allPlatformMeta
import dev.yatori.mobile.app.service.CourseSyncWorker
import dev.yatori.mobile.app.service.OperationForegroundService
import dev.yatori.mobile.app.ui.common.ConfirmDialog
import dev.yatori.mobile.app.ui.common.EmptyState
import dev.yatori.mobile.app.ui.common.OverflowMenu
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.common.barBackdropLayer
import dev.yatori.mobile.app.ui.common.operationSummaryText
import dev.yatori.mobile.app.ui.common.rememberBarBackdrop
import dev.yatori.mobile.app.ui.common.topBarBlur
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.runtime.StoredSession
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.runtime.operation.Operation
import dev.yatori.mobile.runtime.operation.OperationController
import dev.yatori.mobile.runtime.operation.OperationStatus
import dev.yatori.mobile.runtime.operation.OperationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Courses tab: account cards, course sync, rule editing, and batch-run entry. */
@Composable
fun CoursesScreen(container: AppContainer, nav: Navigator, bottomPadding: Dp, isActive: Boolean = false) {
    val repo = container.repository
    val scrollBehavior = MiuixScrollBehavior()
    var sessions by remember { mutableStateOf<List<StoredSession>>(emptyList()) }
    var showRunAllConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val operations by container.operationController.operations.collectAsState()
    val questionHistory by container.operationController.questionHistory.collectAsState()
    val pendingTaskChallenges by container.pendingTaskChallengesFlow.collectAsState()
    val pendingAnswerEdits by container.pendingAnswerEditsFlow.collectAsState()
    // reloadTick forces savedConfig to reload even when sessions content didn't change (e.g. remark update).
    var reloadTick by remember { mutableStateOf(0) }
    val savedConfig = remember(sessions, reloadTick) { repo.loadSavedConfig() }
    val allAccountsRunning = sessions.isNotEmpty() && sessions.all { session -> operations.accountRunOp(session, sessions)?.isActiveRun() == true }

    fun reload() {
        reloadTick++
        sessions = repo.listSessions()
    }

    // Initial load on first composition.
    LaunchedEffect(Unit) {
        reload()
    }
    // Edit then refresh.
    val currentRoute = nav.current
    val prevRoute = remember { mutableStateOf(currentRoute) }
    LaunchedEffect(currentRoute) {
        val returnedToRoot = prevRoute.value != null && currentRoute == null
        prevRoute.value = currentRoute
        if (returnedToRoot && isActive) reload()
    }
    // Periodic safety refresh while the tab is active.
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            delay(60_000L)
            reload()
        }
    }

    val barBackdrop = rememberBarBackdrop()
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.7f)

    Scaffold(
        topBar = {
            Box(Modifier.topBarBlur(barBackdrop, barTint)) {
                TopAppBar(
                    title = "课程",
                    color = if (barBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TopBarAction(
                            if (allAccountsRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (allAccountsRunning) "全部暂停" else "运行全部账号",
                            onClick = {
                                if (allAccountsRunning) {
                                    operations
                                        .filter { op -> op.type == OperationType.BATCH_LEARN && op.isActiveRun() && sessions.any { it.matchesOperation(op, sessions) } }
                                        .forEach { op -> container.operationController.cancel(op.id) }
                                    container.cancelPendingTaskChallenge()
                                    container.cancelPendingAnswerEdit()
                                } else {
                                    showRunAllConfirm = true
                                }
                            },
                        )
                        TopBarAction(Icons.Rounded.Add, "添加账号", onClick = { nav.push(Route.AddAccount) })
                        OverflowMenu(
                            icon = Icons.Rounded.MoreVert,
                            items = listOf("刷新账号状态" to { reload() }),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().barBackdropLayer(barBackdrop)) {
            if (sessions.isEmpty()) {
                EmptyState(
                    title = "尚未添加账号",
                    subtitle = "添加一个学习平台账号开始使用",
                    actionLabel = "添加账号",
                    onAction = { nav.push(Route.AddAccount) },
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = bottomPadding + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sessions, key = { it.platform + it.account + it.session.extra["preUrl"]?.asString.orEmpty() }) { s ->
                        AccountCard(
                            container = container,
                            nav = nav,
                            s = s,
                            allSessions = sessions,
                            operations = operations,
                            questionHistory = questionHistory,
                            pendingTaskChallenges = pendingTaskChallenges,
                            pendingAnswerEdits = pendingAnswerEdits,
                            savedConfig = savedConfig,
                            onChanged = { reload() },
                        )
                    }
                }
            }
        }
    }
    ConfirmDialog(
        show = showRunAllConfirm,
        title = "运行全部账号",
        message = "会按每个账号的课程配置开始运行。学习通会按配置同时跑多个任务点；章测/考试按各账号配置处理。",
        confirmLabel = "开始",
        onConfirm = {
            showRunAllConfirm = false
            OperationForegroundService.start(context)
            sessions.forEach { s ->
                if (operations.accountRunOp(s, sessions)?.isActiveRun() != true) {
                    CourseSyncWorker.enqueue(
                        context = context,
                        platform = s.platform,
                        account = s.account,
                        url = s.urlOf(),
                        dryRun = false,
                    )
                }
            }
        },
        onDismiss = { showRunAllConfirm = false },
    )
}

@Composable
private fun AccountCard(
    container: AppContainer,
    nav: Navigator,
    s: StoredSession,
    allSessions: List<StoredSession>,
    operations: List<Operation>,
    questionHistory: List<dev.yatori.mobile.runtime.operation.QuestionHistoryEntry>,
    pendingTaskChallenges: List<PendingTaskChallenge>,
    pendingAnswerEdits: List<dev.yatori.mobile.runtime.operation.AnswerEditRequest>,
    savedConfig: dev.yatori.mobile.api.dto.MobileConfig?,
    onChanged: () -> Unit,
) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showRunConfirm by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var cachedCount by remember { mutableStateOf(repo.loadCachedCourses(s.platform, s.account).size) }
    var hasCourseCache by remember { mutableStateOf(repo.hasCourseCache(s.platform, s.account)) }

    val displayName = allPlatformMeta.find { it.platform.id == s.platform }?.displayName ?: s.platform
    val remarkName = savedConfig
        ?.users
        ?.firstOrNull { it.matchesAccount(s.platform, s.account) }
        ?.str("remarkName")
        .orEmpty()
    val title = accountCardTitle(s.account, displayName, remarkName)
    val op = operations.accountRunOp(s, allSessions)
    val activeOp = op?.takeIf { it.isActiveRun() }
    val hasQuestionHistory = op?.let { current -> questionHistory.any { it.operationId == current.id } } == true
    val accountPendingChallenge = pendingTaskChallenges.firstOrNull {
        it.session.platform == s.platform && it.session.account == s.account
    }
    val accountPendingAnswer = pendingAnswerEdits.firstOrNull {
        it.session.platform == s.platform && it.session.account == s.account
    }

    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(title.subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            IconButton(
                onClick = {
                    if (activeOp != null) {
                        container.operationController.cancel(activeOp.id)
                        container.cancelPendingTaskChallenge()
                        container.cancelPendingAnswerEdit()
                    } else {
                        showRunConfirm = true
                    }
                },
            ) {
                Icon(
                    if (activeOp != null) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (activeOp != null) "暂停运行" else "开始运行",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            OverflowMenu(
                icon = Icons.Rounded.MoreVert,
                items = listOf(
                    "编辑课程规则" to { nav.push(Route.CourseRuleEditor(s.platform, s.account)) },
                    "清除课程缓存" to {
                        repo.deleteCourseCache(s.platform, s.account)
                        cachedCount = 0
                        hasCourseCache = false
                    },
                    "删除账号" to { showDelete = true },
                ),
            )
        }

        Text(
            text = courseCacheSummaryText(hasCourseCache, cachedCount),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (op != null) {
            Text(
                text = operationSummaryText(op.status, op.completed, op.total, op.detail),
                style = MiuixTheme.textStyles.body2,
                color = if (op.status == OperationStatus.FAILED) Color(0xFFE53935) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (hasQuestionHistory) {
                TextButton(
                    text = "题目历史",
                    onClick = { nav.push(Route.QuestionHistory(op.id)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        if (accountPendingChallenge != null) {
            TextButton(
                text = "手动输入验证码",
                onClick = { nav.push(Route.ChallengeManual(accountPendingChallenge.id)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (accountPendingAnswer != null) {
            TextButton(
                text = if (accountPendingAnswer.scope == "bbs") "编辑内容" else "编辑答案",
                onClick = { nav.push(Route.AnswerEdit(accountPendingAnswer.id)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "查看课程",
                        onClick = { nav.push(Route.CourseList(s.platform, s.account)) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = if (syncing) "同步中" else "同步课程",
                        onClick = {
                            syncing = true
                            scope.launch {
                                runCatching { repo.getCourses(s.session) }.onSuccess {
                                    cachedCount = it.size
                                    hasCourseCache = true
                                }
                                syncing = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !syncing,
                    )
                }
            }
        }
    }

    val runConfirmation = buildCourseRunConfirmation(
        platform = s.platform,
        account = s.account,
        config = savedConfig,
        dryRun = false,
    )
    ConfirmDialog(
        show = showRunConfirm,
        title = runConfirmation.title,
        message = runConfirmation.message,
        confirmLabel = runConfirmation.confirmLabel,
        onConfirm = {
            showRunConfirm = false
            OperationForegroundService.start(context)
            CourseSyncWorker.enqueue(
                context = context,
                platform = s.platform,
                account = s.account,
                dryRun = false,
            )
        },
        onDismiss = { showRunConfirm = false },
    )

    ConfirmDialog(
        show = showDelete,
        title = "删除账号",
        message = "确定删除 $displayName · ${title.title} 的账号、会话、凭据、课程缓存和任务状态？此操作不可撤销。",
        confirmLabel = "删除",
        onConfirm = {
            repo.deleteSession(s.platform, s.account, s.urlOf())
            repo.deleteCredential(s.platform, s.account)
            repo.deleteCourseCache(s.platform, s.account)
            repo.deleteActionStates(s.platform, s.account)
            showDelete = false
            onChanged()
        },
        onDismiss = { showDelete = false },
    )
}

internal fun List<Operation>.accountRunOp(session: StoredSession, allSessions: List<StoredSession>): Operation? =
    lastOrNull { it.type == OperationType.BATCH_LEARN && it.matchesOperation(session, allSessions) }

internal fun Operation.matchesOperation(session: StoredSession, allSessions: List<StoredSession>): Boolean {
    if (!platform.equals(session.platform, ignoreCase = true)) return false
    if (account.isNotBlank()) return account == session.account
    val masked = OperationController.maskAccount(session.account)
    val sameMaskCount = allSessions.count {
        it.platform.equals(session.platform, ignoreCase = true) &&
            OperationController.maskAccount(it.account) == masked
    }
    return sameMaskCount == 1 && accountMasked == masked
}

private fun StoredSession.matchesOperation(op: Operation, allSessions: List<StoredSession>): Boolean =
    op.matchesOperation(this, allSessions)

private fun Operation.isActiveRun(): Boolean =
    status == OperationStatus.RUNNING || status == OperationStatus.CANCELLING

internal fun courseCacheSummaryText(hasCourseCache: Boolean, cachedCount: Int): String =
    when {
        cachedCount > 0 -> "已同步 $cachedCount 门课程"
        hasCourseCache -> "没有进行中的课程"
        else -> "未同步课程"
    }

internal data class AccountCardTitle(val title: String, val subtitle: String)

internal fun accountCardTitle(account: String, platformName: String, remarkName: String): AccountCardTitle {
    val remark = remarkName.trim()
    return if (remark.isNotBlank()) {
        AccountCardTitle("$remark(${accountSnippetForRemark(account)})", platformName)
    } else {
        AccountCardTitle(account, platformName)
    }
}

internal fun accountSnippetForRemark(account: String): String {
    val trimmed = account.trim()
    if (trimmed.length <= 7) return trimmed
    val at = trimmed.indexOf('@')
    if (at > 0) {
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at)
        return if (local.length <= 7) trimmed else local.take(3) + "...." + local.takeLast(2) + domain
    }
    return trimmed.take(3) + "...." + trimmed.takeLast(2)
}

private fun JsonObject.matchesAccount(platform: String, account: String): Boolean =
    str("account").equals(account, ignoreCase = false) &&
        str("accountType").equals(platform, ignoreCase = true)

private fun SessionData.urlOf(): String =
    extra["preUrl"]?.asString?.takeIf { it.isNotBlank() } ?: ""

private fun StoredSession.urlOf(): String = session.urlOf()

private fun JsonObject.str(key: String): String =
    runCatching { get(key)?.asString.orEmpty() }.getOrDefault("")

