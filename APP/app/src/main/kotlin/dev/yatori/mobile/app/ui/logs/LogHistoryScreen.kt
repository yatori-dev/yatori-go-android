package dev.yatori.mobile.app.ui.logs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.api.dto.localFullTime
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.EmptyState
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.runtime.StoredSession
import dev.yatori.mobile.runtime.log.LogFileMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * History log file list. Selecting a file pushes [Route.LogHistoryDetail] as a real secondary
 * screen — so the drill-down obeys the theme page-transition setting, paints its own opaque
 * background (no see-through overlap), and supports system/predictive back like every other
 * detail screen.
 */
@Composable
fun LogHistoryScreen(container: AppContainer, nav: Navigator) {
    val store = container.logStore
    val history = remember { runCatching { store.listHistory() }.getOrDefault(emptyList()) }

    SecondaryScaffold(title = "历史日志", nav = nav) { innerPadding ->
        HistoryList(history, innerPadding, onOpen = { nav.push(Route.LogHistoryDetail(it.name)) })
    }
}

@Composable
private fun HistoryList(
    history: List<LogFileMeta>,
    innerPadding: PaddingValues,
    onOpen: (LogFileMeta) -> Unit,
) {
    if (history.isEmpty()) {
        EmptyState(title = "暂无历史日志", modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(history, key = { it.name }) { f ->
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp), onClick = { onOpen(f) }) {
                Text(f.name, color = MiuixTheme.colorScheme.onSurface)
                Text("${f.sizeBytes} 字节", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/**
 * One history log file, decrypted. Export/share act on THIS file (scoped by [fileName]),
 * mirroring the 日志 tab's top-bar actions (which act on the current session).
 */
@Composable
fun LogHistoryDetailScreen(container: AppContainer, nav: Navigator, fileName: String) {
    val store = container.logStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Decrypt is a whole-file, synchronous op whose cost grows with log length, so run it OFF the
    // main thread: enter instantly with a spinner, then swap in the list. Keeping it in the first
    // composition would block the main thread and eat the route's enter-transition frames.
    // null = still loading.
    var entries by remember(fileName) { mutableStateOf<List<LogEntry>?>(null) }
    var sessions by remember(fileName) { mutableStateOf<List<StoredSession>>(emptyList()) }
    var savedConfig by remember(fileName) { mutableStateOf<MobileConfig?>(null) }
    LaunchedEffect(fileName) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                runCatching { store.readHistory(fileName) }.getOrDefault(emptyList()),
                container.repository.listSessions(),
                container.repository.loadSavedConfig(),
            )
        }
        entries = loaded.first
        sessions = loaded.second
        savedConfig = loaded.third
    }
    var pendingExport by remember { mutableStateOf<java.io.File?>(null) }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(LOG_EXPORT_MIME_TYPE),
    ) { uri ->
        val zip = pendingExport
        pendingExport = null
        when {
            uri == null -> Toast.makeText(context, "已取消导出", Toast.LENGTH_SHORT).show()
            zip == null -> Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
            else -> scope.launch {
                saveLogExport(context, zip, uri)
                    .onSuccess { Toast.makeText(context, "日志已保存", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    SecondaryScaffold(
        title = "日志详情",
        nav = nav,
        actions = {
            TopBarAction(Icons.Rounded.Download, "导出 ZIP", onClick = {
                scope.launch {
                    prepareLogExport(container, context, fileName)
                        .onSuccess { zip ->
                            pendingExport = zip
                            saveLogLauncher.launch(zip.name)
                        }
                        .onFailure { e -> Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show() }
                }
            })
            TopBarAction(Icons.Rounded.IosShare, "分享 ZIP", onClick = {
                scope.launch {
                    prepareLogExport(container, context, fileName)
                        .onSuccess { zip ->
                            shareLogExport(context, zip)
                                .onFailure { e -> Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show() }
                        }
                        .onFailure { e -> Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_LONG).show() }
                }
            })
        },
    ) { innerPadding ->
        val loaded = entries
        when {
            loaded == null -> Box(
                Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                contentAlignment = Alignment.Center,
            ) { InfiniteProgressIndicator() }
            loaded.isEmpty() -> EmptyState(
                title = "该历史文件为空",
                actionLabel = "返回列表",
                onAction = { nav.pop() },
                modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(loaded, key = { it.id }) { e ->
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                        Text("[${e.level.orEmpty()}] ${logDisplayName(e, sessions, savedConfig)} · ${e.localFullTime()}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                        Text(e.message.orEmpty(), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}
