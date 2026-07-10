package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections

interface CourseTaskRunner {
    suspend fun getCourses(session: SessionData): List<CourseItem>
    suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = listOf(course)
    suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem>
    suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any> = emptyMap()): RunTaskResult
}

data class SyncEvent(
    val level: String,
    val message: String,
    val platform: String,
    val account: String = "",
)

data class TaskChallengeRequest(
    val session: SessionData,
    val task: TaskItem,
    val retryOptions: Map<String, Any>,
    val result: RunTaskResult,
)

fun interface TaskChallengeResolver {
    suspend fun resolve(request: TaskChallengeRequest): RunTaskResult
}

data class AnswerEditRequest(
    val id: String,
    val operationId: String,
    val session: SessionData,
    val scope: String,
    val label: String,
    val ctx: JsonObject,
    val question: JsonObject,
    val options: List<String>,
    val suggestedAnswers: List<String>,
    val prompt: String,
    val dryRun: Boolean,
)

fun interface AnswerEditResolver {
    suspend fun resolve(request: AnswerEditRequest): List<String>
}

fun interface XuexitongSessionProvider {
    suspend fun relogin(primary: SessionData, index: Int): SessionData
}

private data class AnswerDecision(
    val answers: List<String>,
    val source: String,
)

private data class XuexitongNodeTasks(
    val node: CourseItem,
    val tasks: List<TaskItem>,
)

private data class XuexitongCourseTasks(
    val course: CourseItem,
    val nodes: List<XuexitongNodeTasks>,
)

class CourseSyncManager(
    private val runner: CourseTaskRunner,
    private val controller: OperationController,
    private val platformRunner: PlatformTaskRunner? = null,
    private val taskChallengeResolver: TaskChallengeResolver? = null,
    private val answerProviderFactory: AnswerProviderFactory = AnswerProviderFactory { mode ->
        if (mode == AnswerMode.XUEXITONG_BUILT_IN) BuiltInXuexitongAnswerProvider(runner) else null
    },
    private val answerEditResolver: AnswerEditResolver? = null,
    private val xuexitongSessionProvider: XuexitongSessionProvider = XuexitongSessionProvider { primary, _ -> primary },
    private val mode3LoginSpacingMillis: Long = 1_000L,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private fun TaskItem.isFinished(): Boolean =
        status.equals("completed", true) || status.equals("done", true) ||
            status.equals("finished", true) || progress >= 100.0

    private fun RunTaskResult.isSubmittedOrDone(): Boolean {
        val normalized = status.trim()
        return normalized.equals("submitted", ignoreCase = true) ||
            normalized.equals("done", ignoreCase = true)
    }

    suspend fun run(
        session: SessionData,
        plan: RunPlan,
        onEvent: (SyncEvent) -> Unit = {},
    ): List<String> {
        val emit: (SyncEvent) -> Unit = { event ->
            val withPlatform = if (event.platform.isBlank()) event.copy(platform = plan.platform) else event
            onEvent(if (withPlatform.account.isBlank()) withPlatform.copy(account = plan.account) else withPlatform)
        }
        val opId = controller.start(
            type = OperationType.BATCH_LEARN,
            platform = plan.platform,
            account = plan.account,
            detail = "sync courses",
        )
        val submitted = ArrayList<String>()
        try {
            val courses = runner.getCourses(session).filter { plan.rule.matches(it.id, it.name) }
            emit(SyncEvent("info", "共 ${courses.size} 门课程", plan.platform))

            if (session.platform == "xuexitong") {
                submitted.addAll(runXuexitongCourses(session, plan, opId, courses, emit))
                controller.markDone(
                    opId,
                    detail = if (controller.isCancelling(opId)) "已取消，已提交 ${submitted.size} 个" else "已完成，已提交 ${submitted.size} 个",
                )
                return submitted
            }

            // A Yinghua node can independently contain video, work, and exam tabs. Keep every
            // Yinghua mode on a dedicated path so a node's primary type/video completion cannot
            // hide its other capabilities. This mirrors console nodeListStudy, which invokes
            // videoAction, workAction, and examAction separately for every node.
            if (session.platform == "yinghua" && (platformRunner != null || plan.answerPolicy.enabled)) {
                submitted.addAll(withYinghuaKeepAlive(session, opId) {
                    when (plan.yinghuaVideoModel) {
                        2 -> runYinghuaMode2Courses(session, plan, opId, courses, emit)
                        3 -> runYinghuaRedCourses(session, plan, opId, courses, emit)
                        else -> runYinghuaSequentialCourses(session, plan, opId, courses, emit)
                    }
                })
                controller.markDone(
                    opId,
                    detail = if (controller.isCancelling(opId)) "已取消，已提交 ${submitted.size} 个" else "已完成，已提交 ${submitted.size} 个",
                )
                return submitted
            }

            data class Pending(val course: CourseItem, val task: TaskItem)
            val pending = ArrayList<Pending>()
            for (course in courses) {
                if (controller.isCancelling(opId)) break
                xuexitongOrderedTasks(session, course, plan, opId, emit)
                    .filter { !it.isFinished() && it.id !in plan.completedTaskIds }
                    .forEach { pending.add(Pending(course, it)) }
            }
            controller.updateProgress(opId, completed = 0, total = pending.size, detail = "共 ${pending.size} 个任务")

            withYinghuaKeepAlive(session, opId) {
                for ((index, p) in pending.withIndex()) {
                    if (controller.isCancelling(opId)) {
                        emit(SyncEvent("warn", "已收到取消请求，停止运行", plan.platform))
                        break
                    }
                    controller.updateProgress(opId, completed = index, total = pending.size, detail = p.task.name.ifBlank { p.task.id })
                    runCatching { runOneTask(session, p.task, plan, opId, emit) }
                        .onSuccess { r ->
                            if (r.isSubmittedOrDone()) submitted.add(p.task.id)
                            emit(SyncEvent("info", "${p.course.name}／${p.task.name}：${r.status}", plan.platform))
                        }
                        .onFailure { e ->
                            emit(SyncEvent("error", "${p.course.name}／${p.task.name} 失败：${e.message}", plan.platform))
                        }
                    controller.updateProgress(opId, completed = index + 1, detail = p.task.name.ifBlank { p.task.id })
                }
            }

            if (!controller.isCancelling(opId) && session.platform == "xuexitong" && plan.answerPolicy.enabled) {
                for (course in courses) {
                    if (controller.isCancelling(opId)) break
                    runXuexitongCourseAnswers(session, course, plan, opId, emit)
                }
            }

            controller.markDone(
                opId,
                detail = if (controller.isCancelling(opId)) "cancelled submitted=${submitted.size}" else "done submitted=${submitted.size}",
            )
        } catch (e: CancellationException) {
            if (controller.isCancelling(opId)) {
                emit(SyncEvent("warn", "课程同步已取消：${e.message}", plan.platform))
                controller.markDone(opId, detail = "已取消，已提交 ${submitted.size} 个")
                return submitted
            }
            emit(SyncEvent("error", "课程同步已取消：${e.message}", plan.platform))
            controller.markFailed(opId, detail = e.message ?: "同步失败")
            throw e
        } catch (e: Throwable) {
            emit(SyncEvent("error", "课程同步失败：${e.message}", plan.platform))
            controller.markFailed(opId, detail = e.message ?: "failed")
            throw e
        }
        return submitted
    }

    /** Runs every Yinghua execution mode under the same four-minute keep-alive cadence. */
    private suspend fun <T> withYinghuaKeepAlive(
        session: SessionData,
        opId: String,
        block: suspend () -> T,
    ): T {
        if (session.platform != "yinghua") return block()
        return coroutineScope {
            val keepAliveJob = launch {
                delay(4 * 60_000L)
                while (!controller.isCancelling(opId)) {
                    runCatching {
                        val task = TaskItem(
                            id = "keepAlive",
                            type = "keepAlive",
                            name = "keepAlive",
                            platform = "yinghua",
                            raw = session.extra.deepCopy(),
                        )
                        platformRunner?.runTask(session, task, PlatformTaskRunOptions(), { false }, {})
                    }
                    delay(4 * 60_000L)
                }
            }
            try {
                block()
            } finally {
                keepAliveJob.cancel()
            }
        }
    }

    private suspend fun xuexitongOrderedTasks(
        session: SessionData,
        course: CourseItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): List<TaskItem> {
        if (session.platform != "xuexitong") return runner.getTasks(session, course)
        val nodes = runner.getCourseDetail(session, course)
        val tasks = ArrayList<TaskItem>()
        for (node in nodes) {
            if (controller.isCancelling(opId)) break
            for (task in runner.getTasks(session, node)) {
                if (task.type.equals("quiz", true) && (!plan.answerPolicy.enabled || !plan.answerPolicy.runChapterTest)) continue
                if (task.type.equals("bbs", true) && !plan.answerPolicy.enabled) continue
                tasks.add(task)
            }
        }
        onEvent(SyncEvent("info", "${course.name.ifBlank { course.id }} 共 ${tasks.size} 个任务", session.platform))
        return tasks
    }

    private suspend fun runXuexitongCourses(
        session: SessionData,
        plan: RunPlan,
        opId: String,
        courses: List<CourseItem>,
        onEvent: (SyncEvent) -> Unit,
    ): List<String> {
        val courseTasks = courses.map { course ->
            XuexitongCourseTasks(course, collectXuexitongNodeTasks(session, course, plan, opId, onEvent))
        }
        val total = courseTasks.sumOf { course -> course.nodes.sumOf { it.tasks.count { task -> !task.isFinished() && task.id !in plan.completedTaskIds } } }
        controller.updateProgress(opId, completed = 0, total = total, detail = "tasks=$total")

        val submitted = Collections.synchronizedList(mutableListOf<String>())
        val completed = intArrayOf(0)

        suspend fun markProgress() {
            val done = synchronized(completed) { completed[0] += 1; completed[0] }
            controller.updateProgress(opId, completed = done, detail = "$done/$total")
        }

        suspend fun runCourse(course: XuexitongCourseTasks) {
            if (controller.isCancelling(opId)) return
            if (plan.xuexitong.mode3) {
                runXuexitongMode3Course(session, course, plan, opId, submitted, ::markProgress, onEvent)
            } else {
                runXuexitongSequentialCourse(session, course, plan, opId, submitted, ::markProgress, onEvent)
            }
            if (!controller.isCancelling(opId) && plan.answerPolicy.enabled) {
                runXuexitongCourseAnswers(session, course.course, plan, opId, onEvent)
            }
        }

        if (plan.xuexitong.videoModel == 1) {
            for (course in courseTasks) {
                if (controller.isCancelling(opId)) break
                runCourse(course)
            }
        } else {
            coroutineScope {
                courseTasks.map { course ->
                    async { runCourse(course) }
                }.awaitAll()
            }
        }
        return submitted.toList()
    }

    /** Normal/answer-only Yinghua flow: process every node capability independently. */
    private suspend fun runYinghuaSequentialCourses(
        session: SessionData,
        plan: RunPlan,
        opId: String,
        courses: List<CourseItem>,
        onEvent: (SyncEvent) -> Unit,
    ): List<String> {
        val submitted = ArrayList<String>()
        for (course in courses) {
            if (controller.isCancelling(opId)) break
            val tasks = try {
                runner.getTasks(session, course)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onEvent(SyncEvent("error", "${course.name.ifBlank { course.id }} 拉取节点失败：${e.message}", session.platform))
                continue
            }
            onEvent(SyncEvent("info", "${course.name.ifBlank { course.id }} 共 ${tasks.size} 个节点", session.platform))
            for (task in tasks) {
                if (controller.isCancelling(opId)) break
                val hasVideo = task.hasYinghuaCapability("video")
                if (plan.yinghuaVideoModel != 0 && hasVideo && !task.isFinished() && task.id !in plan.completedTaskIds) {
                    try {
                        val result = platformRunner?.runTask(
                            session,
                            task,
                            PlatformTaskRunOptions(dryRun = plan.dryRun, videoModel = plan.yinghuaVideoModel),
                            { controller.isCancelling(opId) },
                            onEvent,
                        ) ?: runTaskWithChallenge(
                            session,
                            task,
                            if (plan.dryRun) mapOf("dryRun" to true) else emptyMap(),
                            onEvent,
                        )
                        if (result.isSubmittedOrDone()) submitted.add(task.id)
                        onEvent(SyncEvent("info", "${course.name}／${task.name} 视频：${result.status}", session.platform))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        onEvent(SyncEvent("error", "${course.name}／${task.name} 视频失败：${e.message}", session.platform))
                    }
                }
                runYinghuaNodeAnswers(session, task, plan, opId, onEvent)
            }
        }
        return submitted
    }

    /**
     * Yinghua 暴力模式 (videoModel=2): Phase 1 — run video tasks concurrently within each course
     * (mirrors console fastModeAction goroutines). Phase 2 — re-fetch tasks and run a 去红 pass
     * (videoBadRedAction) on nodes flagged with the parallel-play warning.
     */
    private suspend fun runYinghuaMode2Courses(
        session: SessionData,
        plan: RunPlan,
        opId: String,
        courses: List<CourseItem>,
        onEvent: (SyncEvent) -> Unit,
    ): List<String> {
        val submitted = Collections.synchronizedList(mutableListOf<String>())
        val platform = platformRunner ?: return submitted

        // Phase 1: concurrent video tasks per course (暴力模式)
        coroutineScope {
            courses.map { course ->
                async {
                    if (controller.isCancelling(opId)) return@async
                    val tasks = runCatching { runner.getTasks(session, course) }.getOrElse { return@async }
                    val videoTasks = tasks.filter {
                        // A Yinghua chapter node may carry video together with work/exam tabs. The
                        // Go adapter keeps those capabilities in raw; type alone represents only one
                        // of them, so filtering only type=video drops the video part of mixed nodes.
                        val hasVideo = it.type.equals("video", true) ||
                            runCatching { it.raw.get("tabVideo")?.asBoolean == true }.getOrDefault(false)
                        hasVideo && !it.isFinished() && it.id !in plan.completedTaskIds
                    }
                    onEvent(SyncEvent("info", "${course.name.ifBlank { course.id }} 暴力模式 ${videoTasks.size} 个视频任务", session.platform))
                    coroutineScope {
                        val videoJobs = videoTasks.map { task ->
                            async {
                                if (controller.isCancelling(opId)) return@async
                                runCatching {
                                    val r = platform.runTask(
                                        session, task,
                                        PlatformTaskRunOptions(dryRun = plan.dryRun, videoModel = 2),
                                        { controller.isCancelling(opId) },
                                        onEvent,
                                    )
                                    if (r.isSubmittedOrDone()) submitted.add(task.id)
                                    onEvent(SyncEvent("info", "${course.name}／${task.name}：${r.status}", session.platform))
                                }.onFailure { e ->
                                    onEvent(SyncEvent("error", "${course.name}／${task.name} 失败：${e.message}", session.platform))
                                }
                            }
                        }
                        // The console starts each video goroutine and immediately handles that
                        // course's work/exam tabs, rather than waiting for long videos to finish.
                        for (task in tasks) {
                            if (controller.isCancelling(opId)) break
                            runYinghuaNodeAnswers(session, task, plan, opId, onEvent)
                        }
                        videoJobs.awaitAll()
                    }
                }
            }.awaitAll()
        }

        // Phase 2: use the same refetch-until-clear implementation as standalone mode 3.
        if (!controller.isCancelling(opId)) {
            for (taskId in runYinghuaRedCourses(session, plan, opId, courses, onEvent, answerNodes = false)) {
                if (!submitted.contains(taskId)) submitted.add(taskId)
            }
        }

        return submitted.toList()
    }

    /**
     * Yinghua 去红 mirrors console nodeListStudy(videoModel=3): courses may run concurrently, red
     * nodes inside one course are submitted sequentially, and the course task list is fetched again
     * until the parallel-play warning disappears. A bounded pass count prevents a permanently stale
     * server flag from recursing forever; failures are retried on the next pass after 10 seconds.
     */
    private suspend fun runYinghuaRedCourses(
        session: SessionData,
        plan: RunPlan,
        opId: String,
        courses: List<CourseItem>,
        onEvent: (SyncEvent) -> Unit,
        answerNodes: Boolean = true,
    ): List<String> {
        val platform = platformRunner ?: return emptyList()
        val submitted = Collections.synchronizedList(mutableListOf<String>())
        val maxPasses = 10

        coroutineScope {
            courses.map { course ->
                async {
                    var completedPasses = 0
                    var answersHandled = false
                    while (!controller.isCancelling(opId)) {
                        val refreshed = try {
                            runner.getTasks(session, course)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            onEvent(SyncEvent("warn", "${course.name.ifBlank { course.id }} 去红重新拉取失败：${e.message}", session.platform))
                            break
                        }
                        if (answerNodes && !answersHandled) {
                            // 去红 mode still answers work/exam tabs; do it once rather than on
                            // every refetch pass used to verify that the red marker cleared.
                            for (task in refreshed) {
                                if (controller.isCancelling(opId)) break
                                runYinghuaNodeAnswers(session, task, plan, opId, onEvent)
                            }
                            answersHandled = true
                        }
                        val redTasks = refreshed.filter { task ->
                            val hasVideo = task.type.equals("video", true) ||
                                runCatching { task.raw.get("tabVideo")?.asBoolean == true }.getOrDefault(false)
                            val isRed = runCatching {
                                task.raw.get("errorMessage")?.asString == "检测到可能使用并行播放刷课"
                            }.getOrDefault(false)
                            hasVideo && isRed
                        }
                        if (redTasks.isEmpty()) break
                        if (completedPasses >= maxPasses) {
                            onEvent(SyncEvent("warn", "${course.name.ifBlank { course.id }} 去红达到 $maxPasses 轮仍有 ${redTasks.size} 个标红节点，停止重试", session.platform))
                            break
                        }

                        completedPasses += 1
                        onEvent(SyncEvent("info", "${course.name.ifBlank { course.id }} 去红第 $completedPasses 轮，共 ${redTasks.size} 个节点", session.platform))
                        for (task in redTasks) {
                            if (controller.isCancelling(opId)) break
                            try {
                                val result = platform.runTask(
                                    session,
                                    task,
                                    PlatformTaskRunOptions(dryRun = plan.dryRun, videoModel = 3),
                                    { controller.isCancelling(opId) },
                                    onEvent,
                                )
                                if (result.isSubmittedOrDone() && !submitted.contains(task.id)) submitted.add(task.id)
                                onEvent(SyncEvent("info", "${course.name}／${task.name} 去红：${result.status}", session.platform))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                onEvent(SyncEvent("warn", "${course.name}／${task.name} 去红失败：${e.message}", session.platform))
                                sleepMillis(10_000L)
                            }
                        }
                        // Dry-run never clears the server flag, so one observed pass is sufficient.
                        if (plan.dryRun) break
                    }
                }
            }.awaitAll()
        }
        return submitted.toList()
    }

    private suspend fun collectXuexitongNodeTasks(
        session: SessionData,
        course: CourseItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): List<XuexitongNodeTasks> {
        val nodes = runner.getCourseDetail(session, course).let { list ->
            if (plan.xuexitong.shuffle) list.shuffled() else list
        }
        val nodeTasks = ArrayList<XuexitongNodeTasks>()
        for (node in nodes) {
            if (controller.isCancelling(opId)) break
            val tasks = runner.getTasks(session, node).filter { task ->
                when {
                    task.type.equals("quiz", true) -> plan.answerPolicy.enabled && plan.answerPolicy.runChapterTest
                    task.type.equals("bbs", true) -> plan.answerPolicy.enabled
                    task.isXuexitongMediaOrContent() -> plan.xuexitong.mediaEnabled
                    else -> true
                }
            }
            if (tasks.isNotEmpty()) nodeTasks.add(XuexitongNodeTasks(node, tasks))
        }
        onEvent(SyncEvent("info", "${course.name.ifBlank { course.id }} 共 ${nodeTasks.size} 个节点", session.platform))
        return nodeTasks
    }

    private suspend fun runXuexitongSequentialCourse(
        session: SessionData,
        course: XuexitongCourseTasks,
        plan: RunPlan,
        opId: String,
        submitted: MutableList<String>,
        markProgress: suspend () -> Unit,
        onEvent: (SyncEvent) -> Unit,
    ) {
        for (node in course.nodes) {
            if (controller.isCancelling(opId)) break
            runXuexitongNodeTasks(session, course.course, node, plan, opId, submitted, markProgress, onEvent)
        }
    }

    private suspend fun runXuexitongMode3Course(
        primary: SessionData,
        course: XuexitongCourseTasks,
        plan: RunPlan,
        opId: String,
        submitted: MutableList<String>,
        markProgress: suspend () -> Unit,
        onEvent: (SyncEvent) -> Unit,
    ) = coroutineScope {
        if (plan.xuexitong.cxNode == -1) {
            course.nodes.mapIndexed { index, node ->
                async {
                    if (controller.isCancelling(opId)) return@async
                    if (index > 0) sleepMillis(mode3LoginSpacingMillis)
                    val session = xuexitongSessionProvider.relogin(primary, index)
                    runXuexitongNodeTasks(session, course.course, node, plan, opId, submitted, markProgress, onEvent)
                }
            }.awaitAll()
            return@coroutineScope
        }

        val poolSize = plan.xuexitong.boundedNodeConcurrency.coerceAtLeast(1)
        val pool = buildXuexitongSessionPool(primary, poolSize, onEvent)
        val queue = Channel<SessionData>(capacity = pool.size)
        pool.forEach { queue.trySend(it) }
        course.nodes.map { node ->
            async {
                val session = queue.receive()
                try {
                    if (!controller.isCancelling(opId)) {
                        runXuexitongNodeTasks(session, course.course, node, plan, opId, submitted, markProgress, onEvent)
                    }
                } finally {
                    queue.trySend(session)
                }
            }
        }.awaitAll()
    }

    private suspend fun buildXuexitongSessionPool(
        primary: SessionData,
        size: Int,
        onEvent: (SyncEvent) -> Unit,
    ): List<SessionData> {
        val out = ArrayList<SessionData>(size)
        for (i in 0 until size) {
            val session = if (i == 0) primary else xuexitongSessionProvider.relogin(primary, i)
            out.add(session)
            onEvent(SyncEvent("info", "学习通 mode3 登录并发池 ${i + 1}/$size", primary.platform))
            if (i < size - 1) sleepMillis(mode3LoginSpacingMillis)
        }
        return out
    }

    private suspend fun runXuexitongNodeTasks(
        session: SessionData,
        course: CourseItem,
        node: XuexitongNodeTasks,
        plan: RunPlan,
        opId: String,
        submitted: MutableList<String>,
        markProgress: suspend () -> Unit,
        onEvent: (SyncEvent) -> Unit,
    ) {
        for (task in node.tasks) {
            if (controller.isCancelling(opId)) break
            if (task.isFinished() || task.id in plan.completedTaskIds) continue
            runCatching { runOneTask(session, task, plan, opId, onEvent) }
                .onSuccess { r ->
                    if (r.isSubmittedOrDone()) submitted.add(task.id)
                    onEvent(SyncEvent("info", "${course.name}／${node.node.name}／${task.name}：${r.status}", session.platform))
                }
                .onFailure { e ->
                    onEvent(SyncEvent("error", "${course.name}／${node.node.name}／${task.name} 失败：${e.message}", session.platform))
                }
            markProgress()
        }
    }

    private fun TaskItem.isXuexitongMediaOrContent(): Boolean =
        type.equals("video", true) ||
            type.equals("audio", true) ||
            type.equals("live", true) ||
            type.equals("document", true) ||
            type.equals("read", true) ||
            type.equals("hyperlink", true) ||
            type.equals("monitor", true) ||
            type.equals("keepAlive", true)

    private suspend fun runOneTask(
        session: SessionData,
        task: TaskItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        if (session.platform == "haiqikeji" && plan.answerPolicy.enabled) {
            if (task.type.equals("work", true)) return runHaiqikejiWorkOrExam(session, task, "work", plan, opId, onEvent)
            if (task.type.equals("exam", true)) return runHaiqikejiWorkOrExam(session, task, "exam", plan, opId, onEvent)
        }
        if (session.platform == "xuexitong" && plan.answerPolicy.enabled) {
            if (task.type.equals("quiz", true)) return runXuexitongChapterTest(session, task, plan, opId, onEvent)
            if (task.type.equals("bbs", true)) return runXuexitongBbs(session, task, plan, opId, onEvent)
        }
        val platform = platformRunner
        if (platform != null && platform.supports(session, task)) {
            return platform.runTask(
                session = session,
                task = task,
                options = PlatformTaskRunOptions(
                    dryRun = plan.dryRun,
                    videoModel = when (session.platform) {
                        "haiqikeji" -> plan.haiqikejiVideoModel
                        "yinghua"   -> plan.yinghuaVideoModel
                        "welearn"   -> plan.welearnVideoModel
                        "cqie"      -> plan.cqieVideoModel
                        else        -> 1
                    },
                ),
                shouldCancel = { controller.isCancelling(opId) },
                onEvent = onEvent,
            )
        }
        return runTaskWithChallenge(session, task, if (plan.dryRun) mapOf("dryRun" to true) else emptyMap(), onEvent)
    }

    private suspend fun runXuexitongCourseAnswers(
        session: SessionData,
        course: CourseItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ) {
        if (plan.answerPolicy.runWork) runXuexitongWorkOrExam(session, course, "work", plan, opId, onEvent)
        if (plan.answerPolicy.runExam) runXuexitongWorkOrExam(session, course, "exam", plan, opId, onEvent)
    }

    private fun TaskItem.hasYinghuaCapability(capability: String): Boolean {
        if (type.equals(capability, ignoreCase = true)) return true
        val rawKey = when (capability.lowercase()) {
            "video" -> "tabVideo"
            "work" -> "tabWork"
            "exam" -> "tabExam"
            else -> return false
        }
        return runCatching { raw.get(rawKey)?.asBoolean == true }.getOrDefault(false)
    }

    /** Answer every enabled work/exam capability without letting one failed tab hide the other. */
    private suspend fun runYinghuaNodeAnswers(
        session: SessionData,
        nodeTask: TaskItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult? {
        if (!plan.answerPolicy.enabled) return null
        var last: RunTaskResult? = null
        suspend fun runCapability(scope: String) {
            try {
                last = runYinghuaWorkOrExam(session, nodeTask, scope, plan, opId, onEvent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val label = if (scope == "work") "作业" else "考试"
                onEvent(SyncEvent("error", "${nodeTask.name.ifBlank { nodeTask.id }} ${label}答题失败：${e.message}", session.platform))
            }
        }
        if (plan.answerPolicy.runWork && nodeTask.hasYinghuaCapability("work")) runCapability("work")
        if (plan.answerPolicy.runExam && nodeTask.hasYinghuaCapability("exam")) runCapability("exam")
        return last
    }

    /**
     * Mirrors console workAction/examAction: list every paper on the node, start it, pull and
     * answer every question, save each answer, and only send finish=1 when configured.
     */
    private suspend fun runYinghuaWorkOrExam(
        session: SessionData,
        nodeTask: TaskItem,
        scope: String,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val isWork = scope == "work"
        val listAction = if (isWork) "pullWork" else "pullExam"
        val questionAction = if (isWork) "workQuestions" else "examQuestions"
        val listKey = if (isWork) "works" else "exams"
        val idKey = if (isWork) "workId" else "examId"
        val kind = if (isWork) "作业" else "考试"

        val list = runTaskWithChallenge(session, nodeTask, mapOf("action" to listAction), onEvent)
        val items = jsonObjects(list.raw.get(listKey))
        onEvent(SyncEvent("info", "${nodeTask.name.ifBlank { nodeTask.id }} 共 ${items.size} 项${kind}", session.platform))
        var last = list
        for ((itemIndex, item) in items.withIndex()) {
            if (controller.isCancelling(opId)) return last
            val quizId = jsonString(item, idKey).ifBlank { "${scope}-${itemIndex}" }
            val quizRaw = item.deepCopy().apply {
                if (!has("preUrl") && nodeTask.raw.has("preUrl")) add("preUrl", nodeTask.raw.get("preUrl"))
            }
            val quizTask = TaskItem(
                id = quizId,
                name = jsonString(item, "title").ifBlank { "${kind}${itemIndex + 1}" },
                type = scope,
                platform = "yinghua",
                raw = quizRaw,
            )
            val pulled = runTaskWithChallenge(session, quizTask, mapOf("action" to questionAction), onEvent)
            val questions = jsonObjects(pulled.raw.get("questions"))
            if (questions.isEmpty()) {
                onEvent(SyncEvent("warn", "${quizTask.name.ifBlank { quizTask.id }} 未解析到题目，跳过提交", session.platform))
                last = pulled
                continue
            }

            val answerPayloads = ArrayList<Map<String, Any>>()
            val historyQuestions = ArrayList<Triple<Int, JsonObject, AnswerDecision>>()
            var hasBlankAnswer = false
            for ((qIndex, rawQuestion) in questions.withIndex()) {
                if (controller.isCancelling(opId)) return pulled
                val q = normalizeYinghuaQuestion(rawQuestion)
                val label = "yinghua ${scope} ${qIndex + 1}"
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "yinghua-${scope}",
                    taskId = quizTask.id,
                    label = label,
                    q = q,
                    index = qIndex + 1,
                    total = questions.size,
                    status = QuestionHistoryStatus.PENDING,
                )
                val decision = answersWithFallback(session, pulled.raw, q, plan, opId, label, onEvent)
                if (decision.answers.isEmpty()) {
                    val message = "yinghua ${scope} question ${qIndex + 1} missing answer"
                    recordQuestionHistory(
                        opId = opId,
                        session = session,
                        scope = "yinghua-${scope}",
                        taskId = quizTask.id,
                        label = label,
                        q = q,
                        index = qIndex + 1,
                        total = questions.size,
                        answerSource = decision.source,
                        status = QuestionHistoryStatus.MISSING,
                        message = message,
                    )
                    if (plan.answerPolicy.pauseOnMissingAnswer) error(message)
                    onEvent(SyncEvent("warn", message, session.platform))
                    continue
                }
                if (decision.answers.any { it.isBlank() }) hasBlankAnswer = true
                historyQuestions.add(Triple(qIndex, q, decision))
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "yinghua-${scope}",
                    taskId = quizTask.id,
                    label = label,
                    q = q,
                    index = qIndex + 1,
                    total = questions.size,
                    answers = decision.answers,
                    answerSource = decision.source,
                    status = QuestionHistoryStatus.ANSWERED,
                )
                answerPayloads.add(yinghuaAnswerPayload(rawQuestion, decision.answers))
            }
            if (answerPayloads.isEmpty()) continue

            val finalSubmit = if (isWork) {
                plan.answerPolicy.submitWorkFinal || (plan.answerPolicy.submitFinalWhenComplete && !hasBlankAnswer)
            } else {
                plan.answerPolicy.submitExamFinal || (plan.answerPolicy.submitFinalWhenComplete && !hasBlankAnswer)
            }
            val options = mutableMapOf<String, Any>(
                "action" to scope,
                "answers" to answerPayloads,
                "finalize" to finalSubmit,
            )
            // realSubmit guards any exam HTTP submit in Go. Saving answers with finish=0 is still
            // required when auto-final-submit is off, exactly as the original console behaves.
            if (!isWork) options["realSubmit"] = true
            if (plan.dryRun) options["dryRun"] = true
            last = runTaskWithChallenge(session, quizTask, options, onEvent)
            if (finalSubmit && !plan.dryRun) {
                val scoreAction = if (isWork) "workScore" else "examScore"
                try {
                    val scoreResult = runTaskWithChallenge(session, quizTask, mapOf("action" to scoreAction), onEvent)
                    val score = jsonString(scoreResult.raw, "score")
                    if (score.isNotBlank()) {
                        onEvent(SyncEvent("info", "${quizTask.name.ifBlank { quizTask.id }} ${kind}得分：${score}", session.platform))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The paper was already finalized; score retrieval failure must not turn a
                    // successful submission into a retry that could submit it again.
                    onEvent(SyncEvent("warn", "${quizTask.name.ifBlank { quizTask.id }} 已交卷，但获取得分失败：${e.message}", session.platform))
                }
            }
            for ((originalIndex, q, decision) in historyQuestions) {
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "yinghua-${scope}",
                    taskId = quizTask.id,
                    label = "yinghua ${scope} ${originalIndex + 1}",
                    q = q,
                    index = originalIndex + 1,
                    total = questions.size,
                    answers = decision.answers,
                    answerSource = decision.source,
                    status = if (finalSubmit) QuestionHistoryStatus.SUBMITTED else QuestionHistoryStatus.SAVED,
                    finalSubmit = finalSubmit,
                    realSubmit = !plan.dryRun,
                    message = last.status,
                )
            }
        }
        return last
    }

    private fun normalizeYinghuaQuestion(raw: JsonObject): JsonObject =
        raw.deepCopy().apply {
            val type = jsonString(raw, "type")
            if (!has("typeCode")) addProperty("typeCode", type)
            if (!has("qid")) addProperty("qid", jsonString(raw, "answerId").ifBlank { jsonString(raw, "index") })
        }

    private fun yinghuaAnswerPayload(raw: JsonObject, answers: List<String>): Map<String, Any> =
        mapOf(
            "answerId" to jsonString(raw, "answerId"),
            "type" to jsonString(raw, "type"),
            "options" to jsonStrings(raw.get("options")),
            "answers" to answers,
        )

    private suspend fun runHaiqikejiWorkOrExam(
        session: SessionData,
        nodeTask: TaskItem,
        scope: String,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        if (scope == "work" && !plan.answerPolicy.runWork) {
            return RunTaskResult(session.platform, nodeTask.id, "skipped", "作业答题未开启")
        }
        if (scope == "exam" && !plan.answerPolicy.runExam) {
            return RunTaskResult(session.platform, nodeTask.id, "skipped", "考试答题未开启")
        }
        val listAction = if (scope == "work") "pullWork" else "pullExam"
        val questionAction = if (scope == "work") "workQuestions" else "examQuestions"
        val submitAction = scope
        val listKey = if (scope == "work") "works" else "exams"
        val idKey = if (scope == "work") "workId" else "examId"
        val list = runTaskWithChallenge(session, nodeTask, mapOf("action" to listAction), onEvent)
        val items = jsonObjects(list.raw.get(listKey))
        onEvent(SyncEvent("info", "${nodeTask.name.ifBlank { nodeTask.id }} 共 ${items.size} 项${if (scope == "work") "作业" else "考试"}", session.platform))
        var last = list
        for ((itemIndex, item) in items.withIndex()) {
            if (controller.isCancelling(opId)) return last
            val quizId = jsonString(item, idKey).ifBlank { "$scope-$itemIndex" }
            val quizTask = TaskItem(
                id = quizId,
                name = jsonString(item, "title").ifBlank { jsonString(item, "name") },
                type = scope,
                platform = "haiqikeji",
                raw = item,
            )
            val pulled = runTaskWithChallenge(session, quizTask, mapOf("action" to questionAction), onEvent)
            val questions = jsonObjects(pulled.raw.get("questions"))
            val answers = ArrayList<Map<String, Any>>()
            val historyQuestions = ArrayList<Triple<Int, JsonObject, AnswerDecision>>()
            var hasBlankAnswer = false
            for ((qIndex, rawQuestion) in questions.withIndex()) {
                if (controller.isCancelling(opId)) return pulled
                val q = normalizeHaiqikejiQuestion(rawQuestion)
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "haiqikeji-$scope",
                    taskId = quizTask.id,
                    label = "haiqikeji $scope ${qIndex + 1}",
                    q = q,
                    index = qIndex + 1,
                    total = questions.size,
                    status = QuestionHistoryStatus.PENDING,
                )
                val decision = answersWithFallback(session, pulled.raw, q, plan, opId, "haiqikeji $scope ${qIndex + 1}", onEvent)
                if (decision.answers.isEmpty()) {
                    val msg = "haiqikeji $scope question ${qIndex + 1} missing answer"
                    recordQuestionHistory(
                        opId = opId,
                        session = session,
                        scope = "haiqikeji-$scope",
                        taskId = quizTask.id,
                        label = "haiqikeji $scope ${qIndex + 1}",
                        q = q,
                        index = qIndex + 1,
                        total = questions.size,
                        answerSource = decision.source,
                        status = QuestionHistoryStatus.MISSING,
                        message = msg,
                    )
                    if (plan.answerPolicy.pauseOnMissingAnswer) error(msg)
                    onEvent(SyncEvent("warn", msg, session.platform))
                    continue
                }
                if (decision.answers.any { it.isBlank() }) hasBlankAnswer = true
                historyQuestions.add(Triple(qIndex, q, decision))
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "haiqikeji-$scope",
                    taskId = quizTask.id,
                    label = "haiqikeji $scope ${qIndex + 1}",
                    q = q,
                    index = qIndex + 1,
                    total = questions.size,
                    answers = decision.answers,
                    answerSource = decision.source,
                    status = QuestionHistoryStatus.ANSWERED,
                )
                answers.add(haiqikejiAnswerPayload(rawQuestion, decision.answers))
            }
            if (answers.isEmpty()) continue
            val finalSubmit = if (scope == "work") {
                plan.answerPolicy.submitWorkFinal || (plan.answerPolicy.submitFinalWhenComplete && !hasBlankAnswer)
            } else {
                plan.answerPolicy.submitExamFinal || (plan.answerPolicy.submitFinalWhenComplete && !hasBlankAnswer)
            }
            val opts = mutableMapOf<String, Any>(
                "action" to submitAction,
                "answers" to answers,
            )
            if (scope == "exam") opts["realSubmit"] = plan.answerPolicy.realSubmitExam && finalSubmit
            if (plan.dryRun) opts["dryRun"] = true
            last = runTaskWithChallenge(session, quizTask, opts, onEvent)
            for ((originalIndex, q, decision) in historyQuestions) {
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "haiqikeji-$scope",
                    taskId = quizTask.id,
                    label = "haiqikeji $scope ${originalIndex + 1}",
                    q = q,
                    index = originalIndex + 1,
                    total = questions.size,
                    answers = decision.answers,
                    answerSource = decision.source,
                    status = if (finalSubmit) QuestionHistoryStatus.SUBMITTED else QuestionHistoryStatus.SAVED,
                    finalSubmit = finalSubmit,
                    realSubmit = scope != "exam" || (plan.answerPolicy.realSubmitExam && finalSubmit),
                    message = last.status,
                )
            }
        }
        return last
    }

    private fun normalizeHaiqikejiQuestion(raw: JsonObject): JsonObject =
        raw.deepCopy().apply {
            val typeCode = when (jsonInt(raw, "type")) {
                1 -> "single"
                2 -> "multiple"
                3 -> "judge"
                4 -> "fill"
                5 -> "short"
                else -> jsonString(raw, "typeCode").ifBlank { jsonString(raw, "type") }
            }
            addProperty("type", typeCode)
            addProperty("typeCode", typeCode)
            if (!has("qid")) {
                addProperty("qid", jsonString(raw, "topicId").ifBlank { jsonString(raw, "id") })
            }
        }

    private fun haiqikejiAnswerPayload(raw: JsonObject, answers: List<String>): Map<String, Any> =
        mapOf(
            "topicId" to jsonString(raw, "topicId"),
            "recordId" to jsonString(raw, "recordId"),
            "wrId" to jsonString(raw, "wrId"),
            "waId" to jsonString(raw, "waId"),
            "type" to jsonInt(raw, "type").let { if (it == 0) 1 else it },
            "options" to orderedOptionTexts(raw.get("options")),
            "optionIdx" to jsonStrings(raw.get("optionIdx")),
            "answers" to answers,
        )

    private suspend fun runXuexitongWorkOrExam(
        session: SessionData,
        course: CourseItem,
        scope: String,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ) {
        val listAction = if (scope == "work") "pullWorkList" else "pullExamList"
        val enterAction = if (scope == "work") "enterWork" else "enterExam"
        val questionAction = if (scope == "work") "workQuestion" else "examQuestion"
        val submitAction = if (scope == "work") "work" else "exam"
        val listKey = if (scope == "work") "works" else "exams"
        val list = runTaskWithChallenge(session, course.asTask("xxt-$scope-list", scope), mapOf("action" to listAction), onEvent)
        val items = jsonObjects(list.raw.get(listKey))
        onEvent(SyncEvent("info", "${course.name.ifBlank { course.id }} 共 ${items.size} 项${if (scope == "work") "作业" else "考试"}", session.platform))
        for ((itemIndex, item) in items.withIndex()) {
            if (controller.isCancelling(opId)) return
            val taskRefId = jsonString(item, "taskRefId").ifBlank { "$scope-$itemIndex" }
            val itemTask = TaskItem(taskRefId, jsonString(item, "name"), scope, platform = "xuexitong", raw = item)
            val entered = runCatching { runTaskWithChallenge(session, itemTask, mapOf("action" to enterAction), onEvent) }
                .getOrElse { e ->
                    onEvent(SyncEvent("warn", "${if (scope == "work") "作业" else "考试"} 进入失败：${e.message}", session.platform))
                    continue
            }
            val ctx = entered.raw
            val total = jsonInt(ctx, "questionTotal").coerceAtLeast(0)
            var hasBlankAnswer = false
            for (qIndex in 0 until total) {
                if (controller.isCancelling(opId)) return
                var historyId = questionHistoryId(opId, scope, itemTask.id, qIndex, "")
                runCatching {
                    val answerTask = TaskItem(answerCtxId(scope, ctx), itemTask.name, scope, platform = "xuexitong", raw = ctx)
                    val q = runTaskWithChallenge(
                        session,
                        answerTask,
                        mapOf("action" to questionAction, "index" to qIndex),
                        onEvent,
                    ).raw
                    historyId = questionHistoryId(opId, scope, itemTask.id, qIndex, jsonString(q, "qid"))
                    recordQuestionHistory(
                        opId = opId,
                        session = session,
                        scope = scope,
                        taskId = itemTask.id,
                        label = "$scope question ${qIndex + 1}",
                        q = q,
                        index = qIndex + 1,
                        total = total,
                        status = QuestionHistoryStatus.PENDING,
                    )
                    val decision = answersWithFallback(session, ctx, q, plan, opId, "$scope question ${qIndex + 1}", onEvent)
                    val answers = decision.answers
                    if (answers.isEmpty()) {
                        hasBlankAnswer = true
                        val msg = "$scope question ${qIndex + 1} missing answer"
                        recordQuestionHistory(
                            opId = opId,
                            session = session,
                            scope = scope,
                            taskId = itemTask.id,
                            label = "$scope question ${qIndex + 1}",
                            q = q,
                            index = qIndex + 1,
                            total = total,
                            answerSource = decision.source,
                            status = QuestionHistoryStatus.MISSING,
                            message = msg,
                        )
                        if (plan.answerPolicy.pauseOnMissingAnswer) error(msg)
                        onEvent(SyncEvent("warn", msg, session.platform))
                        return@runCatching
                    }
                    if (answers.any { it.isBlank() }) hasBlankAnswer = true
                    val finalQuestion = qIndex == total - 1 &&
                        (
                            if (scope == "work") plan.answerPolicy.submitWorkFinal else plan.answerPolicy.submitExamFinal
                        ) || (
                            qIndex == total - 1 && plan.answerPolicy.submitFinalWhenComplete && !hasBlankAnswer
                        )
                    recordQuestionHistory(
                        opId = opId,
                        session = session,
                        scope = scope,
                        taskId = itemTask.id,
                        label = "$scope question ${qIndex + 1}",
                        q = q,
                        index = qIndex + 1,
                        total = total,
                        answers = answers,
                        answerSource = decision.source,
                        status = QuestionHistoryStatus.ANSWERED,
                        finalSubmit = finalQuestion,
                        realSubmit = scope != "exam" || plan.answerPolicy.realSubmitExam,
                    )
                    val opts = mutableMapOf<String, Any>(
                        "action" to submitAction,
                        "question" to mapOf("submit" to (q.get("submit") ?: JsonObject()), "options" to orderedOptionTexts(q.get("options")), "answers" to answers),
                        "isSubmit" to finalQuestion,
                    )
                    if (scope == "exam") opts["realSubmit"] = plan.answerPolicy.realSubmitExam
                    if (plan.dryRun) opts["dryRun"] = true
                    var result = runTaskWithChallenge(session, answerTask, opts, onEvent)
                    if (scope == "exam" && finalQuestion && result.status == "submit_wait") {
                        val waitMinutes = jsonInt(result.raw, "retryAfterMinutes")
                        require(waitMinutes > 0) { "xuexitong exam submit wait response missing retryAfterMinutes" }
                        onEvent(
                            SyncEvent(
                                "warn",
                                "考试限制开考 $waitMinutes 分钟内交卷，等待后将自动重试",
                                session.platform,
                            ),
                        )
                        sleepMillis(waitMinutes.toLong() * 60_000L)
                        if (controller.isCancelling(opId)) return@runCatching
                        result = runTaskWithChallenge(session, answerTask, opts, onEvent)
                        check(result.status != "submit_wait") { "xuexitong exam is still inside the minimum submit window" }
                    }
                    val submittedFinal = finalQuestion && result.status.equals("submitted", ignoreCase = true)
                    recordQuestionHistory(
                        opId = opId,
                        session = session,
                        scope = scope,
                        taskId = itemTask.id,
                        label = "$scope question ${qIndex + 1}",
                        q = q,
                        index = qIndex + 1,
                        total = total,
                        answers = answers,
                        answerSource = decision.source,
                        status = if (submittedFinal) QuestionHistoryStatus.SUBMITTED else QuestionHistoryStatus.SAVED,
                        finalSubmit = submittedFinal,
                        realSubmit = scope != "exam" || plan.answerPolicy.realSubmitExam,
                        message = result.status,
                    )
                    onEvent(SyncEvent("info", "${if (scope == "work") "作业" else "考试"} 第 ${qIndex + 1}/$total 题：${result.status}", session.platform))
                }.onFailure { e ->
                    controller.upsertQuestionHistory(
                        QuestionHistoryEntry(
                            id = historyId,
                            operationId = opId,
                            platform = session.platform,
                            accountMasked = OperationController.maskAccount(session.account),
                            scope = scope,
                            taskId = itemTask.id,
                            label = "$scope question ${qIndex + 1}",
                            questionIndex = qIndex + 1,
                            questionTotal = total,
                            status = QuestionHistoryStatus.FAILED,
                            message = e.message ?: "failed",
                        ),
                    )
                    throw e
                }
            }
        }
    }

    private suspend fun runXuexitongChapterTest(
        session: SessionData,
        task: TaskItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val pulled = runTaskWithChallenge(session, task, mapOf("action" to "pullChapterTest"), onEvent)
        val meta = jsonObject(pulled.raw.get("meta"))
        val questions = jsonObjects(pulled.raw.get("questions"))
        val answers = ArrayList<Map<String, Any>>()
        val historyQuestions = ArrayList<Pair<JsonObject, AnswerDecision>>()
        var hasBlankAnswer = false
        for ((qIndex, q) in questions.withIndex()) {
            if (controller.isCancelling(opId)) break
            recordQuestionHistory(
                opId = opId,
                session = session,
                scope = "chapterTest",
                taskId = task.id,
                label = "chapterTest ${task.id}/${jsonString(q, "qid")}",
                q = q,
                index = qIndex + 1,
                total = questions.size,
                status = QuestionHistoryStatus.PENDING,
            )
            val decision = answersWithFallback(session, meta, q, plan, opId, "chapterTest ${task.id}/${jsonString(q, "qid")}", onEvent)
            val ans = decision.answers
            if (ans.isEmpty()) {
                val msg = "chapterTest ${task.id}/${jsonString(q, "qid")} missing answer"
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = "chapterTest",
                    taskId = task.id,
                    label = "chapterTest ${task.id}/${jsonString(q, "qid")}",
                    q = q,
                    index = qIndex + 1,
                    total = questions.size,
                    answerSource = decision.source,
                    status = QuestionHistoryStatus.MISSING,
                    message = msg,
                )
                if (plan.answerPolicy.pauseOnMissingAnswer) error(msg)
                onEvent(SyncEvent("warn", msg, session.platform))
                continue
            }
            if (ans.any { it.isBlank() }) hasBlankAnswer = true
            historyQuestions.add(q to decision)
            recordQuestionHistory(
                opId = opId,
                session = session,
                scope = "chapterTest",
                taskId = task.id,
                label = "chapterTest ${task.id}/${jsonString(q, "qid")}",
                q = q,
                index = qIndex + 1,
                total = questions.size,
                answers = ans,
                answerSource = decision.source,
                status = QuestionHistoryStatus.ANSWERED,
            )
            answers.add(
                mapOf(
                    "qid" to jsonString(q, "qid"),
                    "typeCode" to jsonString(q, "typeCode"),
                    "options" to orderedOptionTexts(q.get("options")),
                    "selects" to jsonStrings(q.get("selects")),
                    "answers" to ans,
                ),
            )
        }
        val isSubmit = (
            plan.answerPolicy.submitChapterTestFinal ||
                (plan.answerPolicy.submitFinalWhenComplete && !hasBlankAnswer)
            ) && plan.answerPolicy.realSubmitChapterTest
        val opts = mutableMapOf<String, Any>(
            "action" to "chapterTest",
            "paper" to mapOf("meta" to meta, "answers" to answers),
            "isSubmit" to isSubmit,
        )
        if (plan.dryRun) opts["dryRun"] = true
        val result = runTaskWithChallenge(session, task, opts, onEvent)
        historyQuestions.forEachIndexed { index, (q, decision) ->
            recordQuestionHistory(
                opId = opId,
                session = session,
                scope = "chapterTest",
                taskId = task.id,
                label = "chapterTest ${task.id}/${jsonString(q, "qid")}",
                q = q,
                index = index + 1,
                total = questions.size,
                answers = decision.answers,
                answerSource = decision.source,
                status = if (isSubmit) QuestionHistoryStatus.SUBMITTED else QuestionHistoryStatus.SAVED,
                finalSubmit = isSubmit,
                realSubmit = plan.answerPolicy.realSubmitChapterTest,
                message = result.status,
            )
        }
        return result
    }

    private suspend fun runXuexitongBbs(
        session: SessionData,
        task: TaskItem,
        plan: RunPlan,
        opId: String,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        suspend fun prepare(web: Boolean, sourceTask: TaskItem): RunTaskResult =
            runTaskWithChallenge(
                session,
                sourceTask,
                mapOf("action" to if (web) "bbsWebPrepare" else "bbsPrepare"),
                onEvent,
            )

        var useWeb = false
        var prepared = runCatching { prepare(web = false, sourceTask = task) }
            .getOrElse { error ->
                onEvent(SyncEvent("warn", "讨论手机端读取失败，回退网页端：${error.message}", session.platform))
                useWeb = true
                prepare(web = true, sourceTask = task)
            }
        if (prepared.status == "skipped") return prepared
        if (prepared.status !in setOf("prepared", "ok")) return prepared
        if (controller.isCancelling(opId)) return prepared

        val prompt = JsonObject().apply {
            addProperty("content", jsonString(prepared.raw, "prompt").ifBlank { jsonString(prepared.raw, "title") })
            addProperty("title", jsonString(prepared.raw, "title"))
            addProperty("detail", jsonString(prepared.raw, "detail"))
        }
        var answer = answerProviderFactory.provider(plan.answerPolicy.answerMode)
            ?.bbs(
                AnswerRequest(
                    session = session,
                    ctx = prepared.raw,
                    question = prompt,
                    prompt = bbsPrompt(prompt),
                    dryRun = plan.dryRun,
                    label = "bbs ${task.id}",
                ),
            )
            .orEmpty()
        if (answer.isBlank()) {
            val resolver = answerEditResolver
            if (resolver != null) {
                onEvent(SyncEvent("warn", "讨论区 ${task.id} 等待内容编辑", session.platform))
                answer = resolver.resolve(
                    AnswerEditRequest(
                        id = answerEditId("bbs ${task.id}", prompt),
                        operationId = opId,
                        session = session,
                        scope = "bbs",
                        label = "bbs ${task.id}",
                        ctx = prepared.raw,
                        question = prompt,
                        options = emptyList(),
                        suggestedAnswers = listOf(""),
                        prompt = bbsPrompt(prompt),
                        dryRun = plan.dryRun,
                    ),
                ).firstOrNull().orEmpty()
            }
            if (answer.isBlank()) {
                val msg = "bbs ${task.id} missing answer"
                if (plan.answerPolicy.pauseOnMissingAnswer) error(msg)
                onEvent(SyncEvent("warn", msg, session.platform))
                return prepared
            }
        }

        suspend fun submit(web: Boolean, source: RunTaskResult): RunTaskResult {
            val opts = source.raw.toMutableMap().apply {
                put("action", if (web) "bbsWeb" else "bbs")
                put("content", answer)
                put("realSubmit", !plan.dryRun)
                if (plan.dryRun) put("dryRun", true)
            }
            return runTaskWithChallenge(session, task.copy(raw = source.raw), opts, onEvent)
        }

        val firstSubmit = runCatching { submit(web = useWeb, source = prepared) }
        if (useWeb || plan.dryRun) return firstSubmit.getOrThrow()
        val phoneResult = firstSubmit.getOrNull()
        if (phoneResult != null && phoneResult.status in setOf("submitted", "done")) return phoneResult

        val fallbackReason = firstSubmit.exceptionOrNull()?.message ?: "status=${phoneResult?.status.orEmpty()}"
        onEvent(SyncEvent("warn", "讨论手机端提交失败，回退网页端：$fallbackReason", session.platform))
        val webPrepared = prepare(web = true, sourceTask = task.copy(raw = prepared.raw))
        if (webPrepared.status == "skipped") return webPrepared
        if (webPrepared.status !in setOf("prepared", "ok")) return webPrepared
        return submit(web = true, source = webPrepared)
    }

    private suspend fun answersWithFallback(
        session: SessionData,
        ctx: JsonObject,
        q: JsonObject,
        plan: RunPlan,
        opId: String,
        label: String,
        onEvent: (SyncEvent) -> Unit,
    ): AnswerDecision {
        val answers = answerProviderFactory.provider(plan.answerPolicy.answerMode)
            ?.answers(
                AnswerRequest(
                    session = session,
                    ctx = ctx,
                    question = q,
                    prompt = questionPrompt(q),
                    dryRun = plan.dryRun,
                    label = label,
                ),
            )
            .orEmpty()
        if (answers.isNotEmpty()) return AnswerDecision(answers, "provider:${plan.answerPolicy.answerMode.name.lowercase()}")
        val fallback = fallbackAnswers(q)
        if (fallback.isNotEmpty()) {
            onEvent(SyncEvent("warn", "$label 使用了备用答案", session.platform))
        }
        if (shouldEditAnswer(fallback, q, plan.answerPolicy)) {
            val resolver = answerEditResolver
            if (resolver != null) {
                onEvent(SyncEvent("warn", "$label 等待手动填写答案", session.platform))
                recordQuestionHistory(
                    opId = opId,
                    session = session,
                    scope = answerScope(label),
                    taskId = answerTaskId(label),
                    label = label,
                    q = q,
                    answerSource = if (fallback.isNotEmpty()) "fallback" else "",
                    answers = fallback,
                    status = QuestionHistoryStatus.WAITING_EDIT,
                    message = "awaiting manual edit",
                )
                return AnswerDecision(resolver.resolve(
                    AnswerEditRequest(
                        id = answerEditId(label, q),
                        operationId = opId,
                        session = session,
                        scope = answerScope(label),
                        label = label,
                        ctx = ctx,
                        question = q,
                        options = orderedOptionTexts(q.get("options")),
                        suggestedAnswers = fallback,
                        prompt = questionPrompt(q),
                        dryRun = plan.dryRun,
                    ),
                ), "manual")
            }
        }
        return AnswerDecision(fallback, if (fallback.isNotEmpty()) "fallback" else "")
    }

    private fun shouldEditAnswer(
        fallback: List<String>,
        q: JsonObject,
        policy: AnswerPolicy,
    ): Boolean {
        if (fallback.isEmpty()) return false
        if (policy.pauseOnMissingAnswer) return true
        if (fallback.any { it.isBlank() }) return true
        val options = orderedOptionTexts(q.get("options"))
        val type = (jsonString(q, "type") + " " + jsonString(q, "typeCode")).lowercase()
        return options.isEmpty() && (type.contains("short") || type.contains("essay") || type.contains("简答") || type.contains("论述"))
    }

    private fun fallbackAnswers(q: JsonObject): List<String> {
        val type = (jsonString(q, "type") + " " + jsonString(q, "typeCode")).lowercase()
        val options = orderedOptionTexts(q.get("options"))
        val selects = jsonStrings(q.get("selects"))
        return when {
            type.contains("fill") || type.contains("blank") || type.contains("填空") ->
                listOf("")
            type.contains("judge") || type.contains("判断") ->
                listOf(selects.firstOrNull() ?: options.firstOrNull() ?: "正确")
            options.isNotEmpty() ->
                listOf(options.first())
            selects.isNotEmpty() ->
                listOf(selects.first())
            type.contains("short") || type.contains("essay") || type.contains("简答") || type.contains("论述") ->
                listOf("A")
            else ->
                listOf("A")
        }
    }

    private suspend fun runTaskWithChallenge(
        session: SessionData,
        task: TaskItem,
        options: Map<String, Any>,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val result = runner.runTask(session, task, options)
        if (result.status != "captcha") return result
        val resolver = taskChallengeResolver ?: return result
        onEvent(SyncEvent("warn", "${task.name.ifBlank { task.id }} 需要验证码", session.platform))
        return resolver.resolve(TaskChallengeRequest(session, task, options, result))
    }

    private fun CourseItem.asTask(id: String, type: String): TaskItem =
        TaskItem(id = id, name = name, type = type, platform = platform, raw = raw)

    private fun answerCtxId(scope: String, ctx: JsonObject): String =
        jsonString(ctx, if (scope == "work") "workId" else "examRelationId").ifBlank { "$scope-answer" }

    private fun answerEditId(label: String, q: JsonObject): String {
        val qid = jsonString(q, "qid").ifBlank { jsonString(q, "id") }
        return (label + ":" + qid).filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "answer-edit" }
    }

    private fun answerScope(label: String): String =
        when {
            label.startsWith("chapterTest") -> "chapterTest"
            label.startsWith("work") -> "work"
            label.startsWith("exam") -> "exam"
            else -> "answer"
        }

    private fun answerTaskId(label: String): String {
        if (!label.startsWith("chapterTest")) return answerScope(label)
        val parts = label.split('/', limit = 2).firstOrNull().orEmpty().split(' ')
        return parts.getOrNull(1).orEmpty().ifBlank { "chapterTest" }
    }

    private fun questionHistoryId(
        opId: String,
        scope: String,
        taskId: String,
        index: Int,
        questionId: String,
    ): String =
        "$opId:$scope:$taskId:${questionId.ifBlank { index.toString() }}"

    private fun recordQuestionHistory(
        opId: String,
        session: SessionData,
        scope: String,
        taskId: String,
        label: String,
        q: JsonObject,
        index: Int = 0,
        total: Int = 0,
        answers: List<String> = emptyList(),
        answerSource: String = "",
        status: QuestionHistoryStatus,
        finalSubmit: Boolean = false,
        realSubmit: Boolean = false,
        message: String = "",
    ) {
        val qid = jsonString(q, "qid").ifBlank { jsonString(q, "id") }
        val effectiveIndex = index.takeIf { it > 0 } ?: jsonInt(q, "index")
        controller.upsertQuestionHistory(
            QuestionHistoryEntry(
                id = questionHistoryId(opId, scope, taskId, effectiveIndex, qid),
                operationId = opId,
                platform = session.platform,
                accountMasked = OperationController.maskAccount(session.account),
                scope = scope,
                taskId = taskId,
                label = label,
                questionIndex = effectiveIndex,
                questionTotal = total,
                questionId = qid,
                questionType = jsonString(q, "type").ifBlank { jsonString(q, "typeCode") },
                contentPreview = previewText(jsonString(q, "content").ifBlank { jsonString(q, "prompt") }),
                answerPreview = previewText(answers.joinToString(" | ")),
                answerSource = answerSource,
                finalSubmit = finalSubmit,
                realSubmit = realSubmit,
                status = status,
                message = message,
            ),
        )
    }

    private fun previewText(raw: String, maxLength: Int = 120): String {
        val compact = raw.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxLength) compact else compact.take(maxLength - 1) + "…"
    }

    private fun questionPrompt(q: JsonObject): String = buildString {
        append(jsonString(q, "content").ifBlank { jsonString(q, "prompt") })
        val options = orderedOptionTexts(q.get("options"))
        if (options.isNotEmpty()) append("\nOptions:\n").append(options.joinToString("\n"))
        append("\nReturn only the answer text. Use a JSON array or new lines for multiple answers.")
    }

    private fun bbsPrompt(q: JsonObject): String = buildString {
        append(jsonString(q, "content").ifBlank { jsonString(q, "prompt") })
        append("\nWrite a concise discussion reply. Return only the reply content.")
    }

    private fun aiAnswers(result: RunTaskResult): List<String> {
        val direct = jsonStrings(result.raw.get("answers"))
        if (direct.isNotEmpty()) return direct
        val answer = jsonString(result.raw, "answer")
        if (answer.isBlank()) return emptyList()
        return runCatching {
            val parsed = JsonParser.parseString(answer)
            if (parsed.isJsonArray) jsonStrings(parsed) else splitAnswers(answer)
        }.getOrDefault(splitAnswers(answer))
    }

    private fun splitAnswers(raw: String): List<String> =
        raw.split('\n', ',', '，').map { it.trim() }.filter { it.isNotBlank() }

    private fun JsonObject.toMutableMap(): MutableMap<String, Any> =
        entrySet().associate { it.key to it.value.asKotlinValue() }.toMutableMap()

    private fun JsonElement.asKotlinValue(): Any = when {
        isJsonNull -> ""
        isJsonPrimitive -> {
            val p = asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble
                else -> p.asString
            }
        }
        else -> this
    }

    private fun jsonObjects(element: JsonElement?): List<JsonObject> =
        if (element == null || !element.isJsonArray) emptyList()
        else element.asJsonArray.mapNotNull { runCatching { if (it.isJsonObject) it.asJsonObject else null }.getOrNull() }

    private fun jsonObject(element: JsonElement?): JsonObject =
        runCatching { if (element != null && element.isJsonObject) element.asJsonObject else JsonObject() }.getOrDefault(JsonObject())

    private fun jsonStrings(element: JsonElement?): List<String> = when {
        element == null -> emptyList()
        element.isJsonArray -> element.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
        else -> runCatching { listOf(element.asString) }.getOrDefault(emptyList())
    }

    private fun orderedOptionTexts(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        if (element.isJsonArray) return jsonStrings(element)
        if (!element.isJsonObject) return emptyList()
        val obj = element.asJsonObject
        return optionKeys.mapNotNull { key -> obj.get(key)?.let { runCatching { it.asString }.getOrNull() } }
    }

    private fun jsonString(obj: JsonObject, key: String): String =
        runCatching { obj.get(key)?.asString ?: "" }.getOrDefault("")

    private fun jsonInt(obj: JsonObject, key: String): Int =
        runCatching { obj.get(key)?.asInt ?: 0 }.getOrDefault(0)

    private val optionKeys = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "1", "2", "3", "4", "5", "6")
}
