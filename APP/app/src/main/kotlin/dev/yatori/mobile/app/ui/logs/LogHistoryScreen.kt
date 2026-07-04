package dev.yatori.mobile.app.ui.logs

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.localFullTime
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.EmptyState
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.runtime.log.LogFileMeta
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** History log files; selecting one shows its decrypted contents on the same screen. */
@Composable
fun LogHistoryScreen(container: AppContainer, nav: Navigator) {
    val store = container.logStore
    val history = remember { runCatching { store.listHistory() }.getOrDefault(emptyList()) }
    var selected by remember { mutableStateOf<LogFileMeta?>(null) }

    // Viewing a single file is an in-screen drill-down: back returns to the file list first,
    // and only pops the whole screen once we're back at the overview.
    BackHandler(enabled = selected != null) { selected = null }

    SecondaryScaffold(
        title = "历史日志",
        nav = nav,
        onBack = { if (selected != null) selected = null else nav.pop() },
    ) { innerPadding ->
        // Slide list ↔ detail like a push/pop: open a file → detail slides in from the right;
        // back → detail slides out to the right and the list reappears beneath it.
        AnimatedContent(
            targetState = selected,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (targetState != null) {
                    ContentTransform(
                        targetContentEnter = slideInHorizontally(tween(300)) { it },
                        initialContentExit = slideOutHorizontally(tween(300)) { -it / 4 },
                        targetContentZIndex = 1f,
                        sizeTransform = null,
                    )
                } else {
                    ContentTransform(
                        targetContentEnter = slideInHorizontally(tween(300)) { -it / 4 } + fadeIn(tween(150)),
                        initialContentExit = slideOutHorizontally(tween(300)) { it },
                        targetContentZIndex = -1f,
                        sizeTransform = null,
                    )
                }
            },
            contentAlignment = Alignment.TopStart,
            label = "history_detail",
        ) { sel ->
            if (sel == null) {
                HistoryList(history, innerPadding, onOpen = { selected = it })
            } else {
                // Decrypt-and-parse can fail on a corrupt file; never let it crash the screen.
                val entries = remember(sel.name) {
                    runCatching { store.readHistory(sel.name) }.getOrDefault(emptyList())
                }
                HistoryDetail(container, entries, innerPadding, onBack = { selected = null })
            }
        }
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

@Composable
private fun HistoryDetail(
    container: AppContainer,
    entries: List<LogEntry>,
    innerPadding: PaddingValues,
    onBack: () -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState(title = "该历史文件为空", actionLabel = "返回列表", onAction = onBack, modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()))
        return
    }
    val sessions = remember(entries) { container.repository.listSessions() }
    val savedConfig = remember(entries) { container.repository.loadSavedConfig() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.id }) { e ->
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) {
                Text("[${e.level.orEmpty()}] ${logDisplayName(e, sessions, savedConfig)} · ${e.localFullTime()}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                Text(e.message.orEmpty(), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
