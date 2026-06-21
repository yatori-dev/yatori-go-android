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
import com.yatori.android.data.local.entity.TaskStatus
import com.yatori.android.engine.BrushState
import com.yatori.android.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TasksScreen(vm: HomeViewModel = hiltViewModel()) {
    val activeTasks by vm.activeTasks.collectAsState()

    if (activeTasks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PlayCircleOutline, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("暂无任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("在首页点击「开始全部刷课」", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(activeTasks.values.toList(), key = { it.taskId }) { progress ->
                AccountTaskCard(
                    account = progress.account,
                    progress = progress,
                    onStart = { vm.startOne(progress.account) },
                    onCancel = { vm.cancel(progress.taskId) }
                )
            }
        }
    }
}
