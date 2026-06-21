package com.yatori.android.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yatori.android.data.local.entity.LogEntity
import com.yatori.android.ui.viewmodel.LogViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogsScreen(vm: LogViewModel = hiltViewModel()) {
    val logs by vm.logs.collectAsState()
    val keyword by vm.keyword.collectAsState()
    val accountFilter by vm.accountFilter.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val ctx = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("确定清空所有日志记录？") },
            confirmButton = { TextButton(onClick = { vm.clear(); showClearDialog = false }) { Text("清空", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // 工具栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                keyword, { vm.setKeyword(it) },
                Modifier.weight(1f).height(52.dp),
                placeholder = { Text("搜索日志…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = { if (keyword.isNotEmpty()) IconButton(onClick = { vm.setKeyword("") }) { Icon(Icons.Default.Clear, null, Modifier.size(16.dp)) } },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { showClearDialog = true }) { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) }
            IconButton(onClick = {
                val text = logs.joinToString("\n") { "[${formatTime(it.timestamp)}][${it.level}][${it.account}] ${it.message}" }
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("logs", text))
            }) { Icon(Icons.Default.ContentCopy, null) }
        }

        // 级别筛选已迁移至「设置」页（日志显示级别）

        // 账号筛选
        if (accounts.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(selected = accountFilter == null, onClick = { vm.setAccount(null) },
                        label = { Text("全部账号", fontSize = 12.sp) }, modifier = Modifier.height(32.dp))
                }
                items(accounts) { acc ->
                    FilterChip(selected = accountFilter == acc, onClick = { vm.setAccount(if (accountFilter == acc) null else acc) },
                        label = { Text(acc, fontSize = 12.sp) }, modifier = Modifier.height(32.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.EventNote, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val listState = rememberLazyListState()
            // 新 log 插入 index 0 时原 index 0 会被推到 index 1，故用 <= 1 判断是否在顶部
            val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 100 } }
            LaunchedEffect(logs.size) {
                if (isAtTop) listState.animateScrollToItem(0)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs, key = { it.id }, contentType = { it.level }) { LogItem(it) }
            }
        }
    }
}

@Composable
private fun LogItem(log: LogEntity) {
    val (bg, fg) = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "WARN"  -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        "DEBUG" -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        else    -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Text(formatTime(log.timestamp), fontSize = 10.sp, color = fg.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace, modifier = Modifier.width(54.dp), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(if (log.level == "ERROR") "ERR" else log.level, fontSize = 10.sp, color = fg, fontFamily = FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.width(52.dp), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                if (log.account.isNotEmpty())
                    Text("[${log.platform}][${log.account}]", fontSize = 10.sp, color = fg.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(log.message, fontSize = 12.sp, color = fg, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun formatTime(ts: Long) = fmt.format(Date(ts))
