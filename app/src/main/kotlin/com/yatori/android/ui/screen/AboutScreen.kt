package com.yatori.android.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Download
import com.yatori.android.update.ReleaseInfo
import com.yatori.android.update.UpdateResult
import com.yatori.android.update.checkForUpdate
import kotlinx.coroutines.launch
import com.yatori.android.R

import com.yatori.android.BuildConfig

private val VERSION get() = BuildConfig.VERSION_NAME

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }
    var pendingRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }

    // ── 新版本确认弹窗 ─────────────────────────────────────────────
    if (showDialog) {
        val rel = pendingRelease!!
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("发现新版本 ${rel.tagName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前版本：$VERSION")
                    Text("最新版本：${rel.tagName}")
                    if (rel.name.isNotEmpty()) Text("更新标题：${rel.name}", fontWeight = FontWeight.Medium)
                    if (rel.body.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(rel.body + if (rel.body.length >= 400) "…" else "", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.downloadUrl)))
                }) { Icon(Icons.Default.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("立即下载") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } }
        )
    }

    // ── 错误弹窗 ───────────────────────────────────────────────────
    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("检查更新失败") },
            text = { Text(errorMsg) },
            confirmButton = { TextButton(onClick = { showErrorDialog = false }) { Text("确定") } }
        )
    }

    fun openUrl(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── 顶部大卡片 ────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openUrl("https://github.com/yatori-dev/yatori-go-android") },
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.logo2),
                        contentDescription = null,
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Yatori-Go-Android",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "@yatori-dev",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "如果该项目对你有帮助，\n请为我点一个免费的 Star ⭐~",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        // ── 开发者卡片 ────────────────────────────────────────
        LinkCard(
            onClick = { openUrl("https://github.com/Rentz412") }
        ) {
            Image(
                painter = painterResource(R.drawable.pfp),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("开发|Rentz", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("@Rentz412", fontSize = 12.sp, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── 上游项目卡片 ──────────────────────────────────────
        LinkCard(
            onClick = { openUrl("https://github.com/yatori-dev/yatori-go-core") }
        ) {
            Image(
                painter = painterResource(R.drawable.logo2),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Yatori-Go-Android 基于 Yatori-go-core 项目",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)
                Text("@yatori-dev", fontSize = 12.sp, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── 版本/更新卡片 ─────────────────────────────────────
        LinkCard(
            onClick = {
                if (checking) return@LinkCard
                scope.launch {
                    checking = true
                    updateStatus = "检查中..."
                    when (val result = checkForUpdate(VERSION)) {
                        is UpdateResult.NewVersion -> {
                            pendingRelease = result.release
                            showDialog = true
                            updateStatus = "发现新版本 ${result.release.tagName}"
                        }
                        is UpdateResult.UpToDate -> updateStatus = "当前已是最新版本"
                        is UpdateResult.Error -> {
                            errorMsg = result.message
                            showErrorDialog = true
                            updateStatus = "检查失败"
                        }
                    }
                    checking = false
                }
            }
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(32.dp).padding(4.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(30.dp))
            } else {
                Icon(Icons.Outlined.Info, null,
                    modifier = Modifier.size(48.dp).padding(6.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("当前版本：$VERSION", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    updateStatus.ifEmpty { "点击检测更新" },
                    fontSize = 12.sp, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LinkCard(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
