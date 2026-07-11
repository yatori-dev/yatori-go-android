package dev.yatori.mobile.app.ui.courses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.EmptyState
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.TaskItem
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Course list for one account. Shows cached courses; sync action refetches via getCourses. */
@Composable
fun CourseListScreen(container: AppContainer, nav: Navigator, platform: String, account: String) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf(repo.loadCachedCourses(platform, account)) }
    var syncing by remember { mutableStateOf(false) }

    val session = remember { repo.getSession(platform, account) }

    SecondaryScaffold(
        title = "课程列表",
        nav = nav,
        actions = {
            TopBarAction(Icons.Rounded.Sync, "同步", onClick = {
                val s = session ?: return@TopBarAction
                syncing = true
                scope.launch {
                    runCatching { repo.getCourses(s) }.onSuccess { courses = it }
                    syncing = false
                }
            })
        },
    ) { innerPadding ->
        if (courses.isEmpty()) {
            EmptyState(
                title = if (syncing) "同步中" else "暂无课程",
                subtitle = "点击右上角同步按钮获取课程",
                modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(courses, key = { it.id }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                        onClick = { nav.push(Route.CourseDetail(platform, account, c.id)) },
                    ) {
                        Text(c.name.ifBlank { c.id }, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                        Text("进度 ${c.progress.toInt()}%", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (platform == "qingshuxuetang") {
                            qingshuxuetangScoreSummary(c.raw)?.let { summary ->
                                Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Task list for one course. */
@Composable
fun CourseDetailScreen(container: AppContainer, nav: Navigator, platform: String, account: String, courseId: String) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var runningTaskId by remember { mutableStateOf<String?>(null) }
    var challengeTaskId by remember { mutableStateOf<String?>(null) }
    var challengePendingId by remember { mutableStateOf<String?>(null) }
    val taskMessages = remember { mutableStateMapOf<String, String>() }

    val session = remember { repo.getSession(platform, account) }
    val course = remember { repo.loadCachedCourses(platform, account).find { it.id == courseId } ?: CourseItem(courseId, platform = platform) }

    fun runBbsWebPrepare(task: TaskItem) {
        val s = session ?: return
        if (runningTaskId != null) return
        val options = mapOf<String, Any>("action" to "bbsWebPrepare")
        runningTaskId = task.id
        taskMessages[task.id] = "BBS Web 准备中"
        scope.launch {
            val first = runCatching { repo.runTask(s, task, options) }
                .getOrElse { e ->
                    taskMessages[task.id] = e.message ?: "BBS Web 准备失败"
                    runningTaskId = null
                    return@launch
                }
            if (first.status == "captcha") {
                val pending = container.taskChallengeController.capture(s, task, options, first)
                if (pending == null) {
                    taskMessages[task.id] = "验证码图片缺失"
                    runningTaskId = null
                    return@launch
                }
                container.pendingTaskChallenge = pending
                challengeTaskId = task.id
                challengePendingId = pending.id
                when (val outcome = container.taskChallengeController.solveAutomatically(pending) { attempt ->
                    taskMessages[task.id] = "OCR 识别第 $attempt 次"
                }) {
                    is TaskChallengeOutcome.Success -> {
                        taskMessages[task.id] = outcome.result.message.ifBlank { outcome.result.status }
                        container.completePendingTaskChallenge(pending.id, outcome.result)
                    }
                    is TaskChallengeOutcome.Manual -> {
                        if (outcome.pending.id != pending.id) {
                            container.cancelPendingTaskChallenge(pending.id)
                        }
                        container.pendingTaskChallenge = outcome.pending
                        challengePendingId = outcome.pending.id
                        taskMessages[task.id] = "需要手动输入验证码"
                        nav.push(Route.ChallengeManual(outcome.pending.id))
                    }
                    is TaskChallengeOutcome.Error -> {
                        taskMessages[task.id] = outcome.message
                        container.failPendingTaskChallenge(pending.id, outcome.message)
                    }
                    is TaskChallengeOutcome.Cancelled -> {
                        taskMessages[task.id] = "已取消"
                        container.cancelPendingTaskChallenge(pending.id)
                    }
                }
                challengeTaskId = null
                challengePendingId = null
                runningTaskId = null
                return@launch
            }
            taskMessages[task.id] = first.message.ifBlank { first.status }
            runningTaskId = null
        }
    }

    SecondaryScaffold(
        title = course.name.ifBlank { "课程详情" },
        nav = nav,
        actions = {
            TopBarAction(Icons.Rounded.Sync, "刷新任务", onClick = {
                val s = session ?: return@TopBarAction
                loading = true
                scope.launch {
                    runCatching { repo.getTasks(s, course) }.onSuccess { tasks = it }
                    loading = false
                }
            })
        },
    ) { innerPadding ->
        if (tasks.isEmpty()) {
            EmptyState(
                title = if (loading) "加载中" else "暂无任务",
                subtitle = "点击右上角刷新获取任务列表",
                modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { t ->
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text(t.name.ifBlank { t.id }, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                        Text("${t.type} / ${t.status} / ${t.progress.toInt()}%", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        taskMessages[t.id]?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(msg, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (platform == "xuexitong" && t.type.equals("bbs", ignoreCase = true)) {
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(
                                    text = if (runningTaskId == t.id) "准备中" else "BBS Web",
                                    onClick = { runBbsWebPrepare(t) },
                                    modifier = Modifier.weight(1f),
                                    enabled = runningTaskId == null,
                                )
                                if (challengeTaskId == t.id) {
                                    TextButton(
                                        text = "手动输入",
                                        onClick = { challengePendingId?.let { pendingId ->
                                            container.taskChallengeController.requestManual(pendingId)
                                        } },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun qingshuxuetangScoreSummary(raw: com.google.gson.JsonObject): String? {
    val keys = listOf(
        "coursewareLearnGainScore",
        "coursewareLearnTotalScore",
        "courseWorkGainScore",
        "courseWorkTotalScore",
        "courseMaterialsLearnGainScore",
        "courseMaterialsLearnTotalScore",
    )
    if (keys.none(raw::has)) return null

    fun score(key: String): String {
        val value = runCatching { raw.get(key)?.asDouble ?: 0.0 }.getOrDefault(0.0)
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    return "课件 ${score(keys[0])}/${score(keys[1])} · " +
        "作业 ${score(keys[2])}/${score(keys[3])} · " +
        "资料 ${score(keys[4])}/${score(keys[5])}"
}
