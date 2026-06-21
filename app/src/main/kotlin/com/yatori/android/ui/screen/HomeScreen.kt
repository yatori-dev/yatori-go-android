package com.yatori.android.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yatori.android.domain.model.Account
import com.yatori.android.engine.BrushState
import com.yatori.android.engine.TaskProgress
import com.yatori.android.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    vm: HomeViewModel = hiltViewModel(),
    onNavigateAccounts: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val activeTasks by vm.activeTasks.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    var showStopDialog by remember { mutableStateOf(false) }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("停止刷课") },
            text = { Text("确定要停止所有进行中的刷课任务吗？") },
            confirmButton = { TextButton(onClick = { vm.cancelAll(); showStopDialog = false }) { Text("确定停止", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text("取消") } }
        )
    }

    val enabledAccounts = accounts.filter { it.enabled }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            DashboardHeader(
                accountCount = enabledAccounts.size,
                tasks = activeTasks,
                isRunning = isRunning,
                onStart = { if (enabledAccounts.isEmpty()) onNavigateAccounts() else vm.startAll() },
                onStop = { showStopDialog = true }
            )
        }

        if (enabledAccounts.isEmpty()) {
            item { EmptyAccountsHint(onNavigateAccounts) }
        } else {
            item { Text("账号任务", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
            items(enabledAccounts, key = { it.id }) { account ->
                val taskId = account.id.toString()
                val progress = activeTasks[taskId]
                AccountTaskCard(
                    account = account,
                    progress = progress,
                    onStart = { vm.startOne(account) },
                    onCancel = { vm.cancel(taskId) }
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    accountCount: Int, tasks: Map<String, TaskProgress>,
    isRunning: Boolean, onStart: () -> Unit, onStop: () -> Unit
) {
    val running = tasks.values.count { it.state == BrushState.RUNNING }
    val done = tasks.values.count { it.state == BrushState.SUCCESS }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Yatori Android", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("账号数", accountCount.toString())
                StatItem("进行中", running.toString())
                StatItem("已完成", done.toString())
            }
            Spacer(Modifier.height(16.dp))
            if (isRunning) {
                OutlinedButton(onClick = onStop, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止所有任务")
                }
            } else {
                Button(onClick = onStart, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (accountCount == 0) "添加账号开始" else "开始全部刷课")
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AccountTaskCard(
    account: Account,
    progress: TaskProgress?,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val state = progress?.state
    val stateColor = when (state) {
        BrushState.RUNNING   -> MaterialTheme.colorScheme.primary
        BrushState.SUCCESS   -> MaterialTheme.colorScheme.tertiary
        BrushState.FAILED    -> MaterialTheme.colorScheme.error
        BrushState.CANCELLED -> MaterialTheme.colorScheme.outline
        null                 -> MaterialTheme.colorScheme.outline
    }
    val stateLabel = when (state) {
        BrushState.RUNNING   -> "刷课中"
        BrushState.SUCCESS   -> "完成"
        BrushState.FAILED    -> "失败"
        BrushState.CANCELLED -> "已取消"
        null                 -> "未启动"
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(account.displayName, fontWeight = FontWeight.SemiBold)
                    Text(account.platform.displayName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = stateColor.copy(alpha = 0.12f)) {
                    Text(stateLabel, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, color = stateColor, fontWeight = FontWeight.Medium)
                }
            }

            if (progress != null && progress.state == BrushState.RUNNING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    progress.currentPhase.ifEmpty { "刷课中..." },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            } else if (progress != null && progress.state == BrushState.SUCCESS) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { 1f }, Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text("全部完成 ✓", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }

            progress?.errorMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text("错误：$it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(10.dp))
            when (state) {
                BrushState.RUNNING -> OutlinedButton(
                    onClick = onCancel, Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("停止", fontSize = 13.sp) }
                else -> OutlinedButton(
                    onClick = onStart, Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state == BrushState.FAILED) "重试" else "启动", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyAccountsHint(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.PersonAdd, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("还没有添加账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAdd) { Text("去添加账号 →") }
    }
}
