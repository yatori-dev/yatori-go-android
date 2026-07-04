package dev.yatori.mobile.app.ui.logs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.ConfirmDialog
import dev.yatori.mobile.app.ui.common.EmptyState
import dev.yatori.mobile.app.ui.common.LogListItem
import dev.yatori.mobile.app.ui.common.MenuEntry
import dev.yatori.mobile.app.ui.common.NestedOverflowMenu
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.common.barBackdropLayer
import dev.yatori.mobile.app.ui.common.rememberBarBackdrop
import dev.yatori.mobile.app.ui.common.topBarBlur
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.localFullTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Logs tab (HyperCeiler interaction): large title + export/share-zip/more actions; an always-on
 * search field; level-badge cards; tap → detail bottom sheet. The level filter here is a UI-only
 * view filter over already-collected entries — it does NOT change MobileConfig.logLevel.
 */
@Composable
fun LogsScreen(container: AppContainer, nav: Navigator, bottomPadding: Dp, isActive: Boolean = false) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var all by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<String?>(null) } // null = all
    var detail by remember { mutableStateOf<LogEntry?>(null) }
    var showClear by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<java.io.File?>(null) }
    val sessions = remember(all) { repo.listSessions() }
    val savedConfig = remember(all) { repo.loadSavedConfig() }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(LOG_EXPORT_MIME_TYPE),
    ) { uri ->
        val zip = pendingExport
        pendingExport = null
        when {
            uri == null -> Toast.makeText(context, "已取消导出", Toast.LENGTH_SHORT).show()
            zip == null -> Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
            else -> {
                scope.launch {
                    saveLogExport(context, zip, uri)
                        .onSuccess { Toast.makeText(context, "日志已保存", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    val listState = rememberLazyListState()
    // True when the user is at (or very near) the top — used to decide auto-scroll on refresh.
    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }

    // While the log tab is active: refresh every 3 s.
    // • On first activation (returning from anywhere) → always scroll to top.
    // • On subsequent refreshes → only scroll if user was already at the top.
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        // Wait for tab-switch animation to complete before first refresh to avoid jank.
        delay(300)
        var firstRun = true
        while (true) {
            val shouldScroll = firstRun || isAtTop
            val processed = withContext(Dispatchers.IO) {
                runCatching { repo.pollLogs() }
                val raw = repo.currentSessionLogs()
                (if (raw.size > 2000) raw.takeLast(2000) else raw)
                    .reversed()
                    .distinctBy { it.id }
            }
            all = processed
            if (shouldScroll && processed.isNotEmpty()) {
                listState.scrollToItem(0)
            }
            firstRun = false
            delay(3_000)
        }
    }

    // Recompute only when the underlying data or filter criteria change.
    val filtered = remember(all, query, levelFilter) {
        all.filter { e ->
            (levelFilter == null || e.level.equals(levelFilter, true)) &&
                (query.isBlank() || e.message.contains(query, true) || e.source.contains(query, true))
        }
    }

    val barBackdrop = rememberBarBackdrop()
    val barTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.7f)

    TopBarOverlayHost(
        topBar = {
            androidx.compose.foundation.layout.Box(Modifier.topBarBlur(barBackdrop, barTint)) {
                TopAppBar(
                    title = "日志",
                    color = if (barBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TopBarAction(Icons.Rounded.Download, "导出 ZIP", onClick = {
                            scope.launch {
                                prepareLogExport(container, context)
                                    .onSuccess { zip ->
                                        pendingExport = zip
                                        saveLogLauncher.launch(zip.name)
                                    }
                                    .onFailure { e -> Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show() }
                            }
                        })
                        TopBarAction(Icons.Rounded.IosShare, "分享 ZIP", onClick = {
                            scope.launch {
                                prepareLogExport(container, context)
                                    .onSuccess { zip ->
                                        shareLogExport(context, zip)
                                            .onFailure { e -> Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show() }
                                    }
                                    .onFailure { e -> Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show() }
                            }
                        })
                        NestedOverflowMenu(
                            icon = Icons.Rounded.MoreVert,
                            entries = listOf(
                                MenuEntry.Action("清空日志") { showClear = true },
                                MenuEntry.Action("历史日志") { nav.push(Route.LogHistory) },
                                MenuEntry.Submenu(
                                    "日志筛选",
                                    listOf(
                                        "全部级别" to { levelFilter = null },
                                        "仅 INFO" to { levelFilter = "info" },
                                        "仅 WARN" to { levelFilter = "warn" },
                                        "仅 ERROR" to { levelFilter = "error" },
                                    ),
                                ),
                            ),
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().barBackdropLayer(barBackdrop).padding(top = innerPadding.calculateTopPadding())) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索日志",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (filtered.isEmpty()) {
                EmptyState(
                    title = if (all.isEmpty()) "当前会话无日志" else "无匹配日志",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { e ->
                        LogListItem(
                            e,
                            title = logDisplayName(e, sessions, savedConfig),
                            onClick = { detail = e },
                        )
                    }
                }
            }
        }
    }

    ConfirmDialog(
        show = showClear,
        title = "清空日志",
        message = "确定清空当前会话日志并重置游标？",
        confirmLabel = "清空",
        onConfirm = {
            scope.launch {
                runCatching { repo.clearLogs() }
                container.logStore.clearCurrentSession()
                all = emptyList()
            }
            showClear = false
        },
        onDismiss = { showClear = false },
    )

    LogDetailSheet(
        entry = detail,
        title = detail?.let { logDisplayName(it, sessions, savedConfig) }.orEmpty(),
        onDismiss = { detail = null },
    )
}

@Composable
private fun LogDetailSheet(entry: LogEntry?, title: String, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    OverlayDialog(
        show = entry != null,
        title = "日志详情",
        onDismissRequest = onDismiss,
    ) {
        if (entry != null) {
            Column(Modifier.fillMaxWidth()) {
                Text("时间：${entry.localFullTime()}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                Text("级别：${entry.level.orEmpty()}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                Text("来源：${entry.source.orEmpty()}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                Text("对象：$title", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Card(insideMargin = PaddingValues(12.dp)) {
                    Text(entry.message, fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceContainer)
                }
                Spacer(Modifier.height(12.dp))
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "复制整条日志",
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString("${entry.localFullTime()} [${entry.level.orEmpty()}] $title: ${entry.message.orEmpty()}"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A Miuix Scaffold host so OverlayDialog/WindowListPopup (renderInRootScaffold) work here. */
@Composable
private fun TopBarOverlayHost(
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    top.yukonga.miuix.kmp.basic.Scaffold(topBar = topBar, content = content)
}
