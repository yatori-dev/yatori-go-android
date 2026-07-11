package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseSyncManagerTest {

    private val session = SessionData("yinghua", "stu")

    private fun course(id: String, name: String) = CourseItem(id, name = name, platform = "yinghua")
    private fun task(id: String, status: String = "not_started", progress: Double = 0.0) =
        TaskItem(id, name = "task-$id", status = status, progress = progress, platform = "yinghua")

    private class FakeRunner(
        val courses: List<CourseItem>,
        val tasksByCourse: Map<String, List<TaskItem>>,
    ) : CourseTaskRunner {
        val submitted = mutableListOf<String>()
        override suspend fun getCourses(session: SessionData) = courses
        override suspend fun getTasks(session: SessionData, course: CourseItem) =
            tasksByCourse[course.id] ?: emptyList()
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            submitted.add(task.id)
            return RunTaskResult("yinghua", task.id, if (options["dryRun"] == true) "dry_run" else "submitted")
        }
    }

    private class FakePlatformRunner : PlatformTaskRunner {
        val submitted = mutableListOf<String>()
        override fun supports(session: SessionData, task: TaskItem): Boolean = session.platform == "haiqikeji"
        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            submitted.add(task.id)
            onEvent(SyncEvent("info", "platform-runner ${task.id}", session.platform))
            return RunTaskResult(session.platform, task.id, if (options.dryRun) "dry_run" else "submitted")
        }
    }

    private class ConcurrentHaiqikejiPlatformRunner(
        private val failTaskId: String? = null,
    ) : PlatformTaskRunner {
        val videoModels = mutableListOf<Int>()
        var active = 0
        var maxActive = 0

        override fun supports(session: SessionData, task: TaskItem): Boolean = session.platform == "haiqikeji"

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            videoModels.add(options.videoModel)
            active += 1
            maxActive = maxOf(maxActive, active)
            return try {
                delay(1)
                if (task.id == failTaskId) error("failed ${task.id}")
                RunTaskResult(session.platform, task.id, "submitted")
            } finally {
                active -= 1
            }
        }
    }

    private class ConcurrentYinghuaPlatformRunner : PlatformTaskRunner {
        val videoModels = mutableListOf<Int>()
        var active = 0
        var maxActive = 0

        override fun supports(session: SessionData, task: TaskItem): Boolean = true

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            videoModels.add(options.videoModel)
            active += 1
            maxActive = maxOf(maxActive, active)
            delay(1)
            active -= 1
            return RunTaskResult(session.platform, task.id, "submitted")
        }
    }
    private class YinghuaRedRunner(private val redTask: TaskItem) : CourseTaskRunner {
        private val redCourse = CourseItem("red-course", name = "red course", platform = "yinghua")
        var getTasksCalls = 0

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(redCourse)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
            getTasksCalls += 1
            return if (getTasksCalls == 1) listOf(redTask) else emptyList()
        }

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: Map<String, Any>,
        ): RunTaskResult = error("generic runner must not handle Yinghua red mode")
    }

    private class YinghuaPersistentRedRunner(private val redTask: TaskItem) : CourseTaskRunner {
        private val course = CourseItem("persistent-red-course", name = "persistent red course", platform = "yinghua")
        var getTasksCalls = 0

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
            getTasksCalls += 1
            return listOf(redTask)
        }

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: Map<String, Any>,
        ): RunTaskResult = error("generic runner must not handle persistent Yinghua red mode")
    }

    private class YinghuaMode2AutoRedRunner : CourseTaskRunner {
        private val course = CourseItem("auto-red-course", name = "auto red course", platform = "yinghua")
        private val video = TaskItem("auto-red-video", name = "auto red video", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("tabVideo", true)
            addProperty("viewedDuration", 5)
        })
        private val redVideo = video.copy(status = "completed", progress = 100.0, raw = video.raw.deepCopy().apply {
            addProperty("errorMessage", "检测到可能使用并行播放刷课")
        })
        var getTasksCalls = 0

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
            getTasksCalls += 1
            return when (getTasksCalls) {
                1 -> listOf(video)
                2 -> listOf(redVideo)
                else -> emptyList()
            }
        }

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: Map<String, Any>,
        ): RunTaskResult = error("generic runner must not handle Yinghua mode 2 / red mode")
    }

    private class SlowYinghuaRedPlatformRunner(private val expireKeepAlive: Boolean = false) : PlatformTaskRunner {
        var keepAliveCalls = 0

        override fun supports(session: SessionData, task: TaskItem): Boolean = true

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            if (task.id == "keepAlive") {
                keepAliveCalls += 1
                return RunTaskResult(session.platform, task.id, "done", raw = JsonObject().apply {
                    addProperty("expired", expireKeepAlive)
                })
            }
            delay(4 * 60_000L + 1L)
            return RunTaskResult(session.platform, task.id, "submitted")
        }
    }

    private class YinghuaRedPlatformRunner : PlatformTaskRunner {
        val executed = mutableListOf<String>()
        val videoModels = mutableListOf<Int>()

        override fun supports(session: SessionData, task: TaskItem): Boolean = true

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            executed.add(task.id)
            videoModels.add(options.videoModel)
            return RunTaskResult(session.platform, task.id, "submitted")
        }
    }

    private class ResultStatusRunner(
        private val platform: String,
        statuses: List<String>,
    ) : CourseTaskRunner {
        val tasks = statuses.mapIndexed { index, status ->
            TaskItem("result-$index", name = status, type = "video", platform = platform)
        }
        val executed = mutableListOf<String>()
        private val course = CourseItem("result-course", name = "Result Course", platform = platform)

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = tasks
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            executed.add(task.id)
            return RunTaskResult(platform, task.id, task.name)
        }
    }

    private class ResultStatusPlatformRunner : PlatformTaskRunner {
        val executed = mutableListOf<String>()
        override fun supports(session: SessionData, task: TaskItem): Boolean = true
        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            executed.add(task.id)
            return RunTaskResult(session.platform, task.id, task.name)
        }
    }

    @Test
    fun cqieTaskFailureFailsTheBatchInsteadOfMarkingDone() = runTest {
        val cqieSession = SessionData("cqie", "stu")
        val cqieCourse = CourseItem("c1", name = "CQIE课程", platform = "cqie")
        val cqieTask = TaskItem("v1", name = "视频", type = "video", platform = "cqie")
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) = listOf(cqieCourse)
            override suspend fun getTasks(session: SessionData, course: CourseItem) = listOf(cqieTask)
            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>) =
                error("generic runner must not handle CQIE")
        }
        val failingPlatform = object : PlatformTaskRunner {
            override fun supports(session: SessionData, task: TaskItem) = true
            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: PlatformTaskRunOptions,
                shouldCancel: () -> Boolean,
                onEvent: (SyncEvent) -> Unit,
            ): RunTaskResult = error("cqie verification failed")
        }
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), failingPlatform)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(cqieSession, RunPlan("cqie-failure", "cqie", "stu"))
        }

        assertTrue(error.message.orEmpty().contains("CQIE", ignoreCase = true))
    }

    @Test
    fun yinghuaSkipsCoursesThatHaveNotStarted() = runTest {
        var getTasksCalls = 0
        val futureCourse = CourseItem(
            "future",
            name = "未来课程",
            platform = "yinghua",
            raw = JsonObject().apply { addProperty("startDate", "2999-01-01") },
        )
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) = listOf(futureCourse)
            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
                getTasksCalls += 1
                return listOf(task("must-not-run"))
            }
            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>) =
                error("future course task must not run")
        }
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), FakePlatformRunner())

        val submitted = mgr.run(session, RunPlan("future-course", "yinghua", "stu"), events::add)

        assertTrue(submitted.isEmpty())
        assertEquals(0, getTasksCalls)
        assertTrue(events.any { it.message.contains("未来课程 尚未开课，已跳过") })
    }

    @Test
    fun yinghuaNodeFetchExpiryPropagatesToHostRelogin() = runTest {
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) = listOf(course("c1", "课程一"))
            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
                throw IllegalStateException("outer", IllegalStateException("账号登录超时，请重新登录"))
            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>) =
                error("must not run")
        }
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), FakePlatformRunner())

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(session, RunPlan("fetch-expired", "yinghua", "stu"))
        }

        assertEquals("outer", error.message)
    }

    @Test
    fun yinghuaVideoSubmitExpiryPropagatesToHostRelogin() = runTest {
        val video = TaskItem(
            "video-expired",
            name = "视频",
            type = "video",
            platform = "yinghua",
            raw = JsonObject().apply { addProperty("tabVideo", true) },
        )
        val runner = FakeRunner(listOf(course("c1", "课程一")), mapOf("c1" to listOf(video)))
        val expiringPlatform = object : PlatformTaskRunner {
            override fun supports(session: SessionData, task: TaskItem) = true
            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: PlatformTaskRunOptions,
                shouldCancel: () -> Boolean,
                onEvent: (SyncEvent) -> Unit,
            ): RunTaskResult = throw IllegalStateException("yinghua: study submit failed: 账号登录超时，请重新登录")
        }
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), expiringPlatform)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(session, RunPlan("submit-expired", "yinghua", "stu"))
        }

        assertTrue(error.message.orEmpty().contains("账号登录超时"))
    }

    @Test
    fun `submits all unfinished tasks across courses`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "math"), course("c2", "english")),
            tasksByCourse = mapOf(
                "c1" to listOf(task("t1"), task("t2", status = "completed")),
                "c2" to listOf(task("t3"), task("t4", progress = 100.0)),
            ),
        )
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(session, RunPlan("plan1", "yinghua", "stu"), onEvent = { events.add(it) })

        assertEquals(listOf("t1", "t3"), submitted)
        assertEquals(OperationStatus.DONE, controller.operations.value.single().status)
        assertTrue(events.any { it.message.contains("t1") && it.message.contains("submitted") })
    }

    @Test
    fun `manual contains rule filters courses`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "advanced math"), course("c2", "college english")),
            tasksByCourse = mapOf("c1" to listOf(task("t1")), "c2" to listOf(task("t2"))),
        )
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            "plan2", "yinghua", "stu",
            rule = CourseSelectionRule(CourseSelectionRule.Mode.MANUAL_CONTAINS, includeKeywords = listOf("math")),
        )
        val submitted = mgr.run(session, plan)
        assertEquals(listOf("t1"), submitted)
    }

    @Test
    fun `enaea project category rule filters using raw hierarchy`() = runTest {
        fun enaeaCourse(id: String, project: String, category: String) = CourseItem(
            id = id,
            name = "相同课程标题",
            platform = "enaea",
            raw = JsonObject().apply {
                addProperty("projectName", project)
                addProperty("titleTag", category)
            },
        )
        val runner = FakeRunner(
            courses = listOf(
                enaeaCourse("c1", "项目A", "必修"),
                enaeaCourse("c2", "项目B", "必修"),
                enaeaCourse("c3", "项目A", "选修"),
            ),
            tasksByCourse = mapOf(
                "c1" to listOf(task("t1")),
                "c2" to listOf(task("t2")),
                "c3" to listOf(task("t3")),
            ),
        )
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val rule = CourseSelectionRule(
            CourseSelectionRule.Mode.ENAEA_PROJECT_CATEGORY,
            includeKeywords = listOf("项目A-->必修"),
        )

        val submitted = mgr.run(
            SessionData("enaea", "stu"),
            RunPlan("enaea-filter", "enaea", "stu", rule = rule),
        )

        assertEquals(listOf("t1"), submitted)
    }

    @Test
    fun `enaea task failure fails the batch instead of reporting completion`() = runTest {
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) =
                listOf(CourseItem("c1", name = "课程", platform = "enaea"))

            override suspend fun getTasks(session: SessionData, course: CourseItem) =
                listOf(TaskItem("t1", name = "视频", type = "video", platform = "enaea"))

            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: Map<String, Any>,
            ): RunTaskResult = throw IllegalStateException("network down")
        }
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(SessionData("enaea", "stu"), RunPlan("enaea-error", "enaea", "stu"))
        }

        assertTrue(error.message.orEmpty().contains("network down"))
        assertEquals(OperationStatus.FAILED, controller.operations.value.single().status)
    }

    @Test
    fun `ketangx task failure fails the batch instead of reporting completion`() = runTest {
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) =
                listOf(CourseItem("c1", name = "课程", platform = "ketangx"))

            override suspend fun getTasks(session: SessionData, course: CourseItem) =
                listOf(TaskItem("t1", name = "视频", type = "video", platform = "ketangx"))

            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: Map<String, Any>,
            ): RunTaskResult = throw IllegalStateException("network down")
        }
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(SessionData("ketangx", "stu"), RunPlan("ketangx-error", "ketangx", "stu"))
        }

        assertTrue(error.message.orEmpty().contains("operations failed"))
        assertEquals(OperationStatus.FAILED, controller.operations.value.single().status)
    }

    @Test
    fun `ketangx session expiry reaches the app relogin boundary`() = runTest {
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) =
                listOf(CourseItem("c1", name = "课程", platform = "ketangx"))

            override suspend fun getTasks(session: SessionData, course: CourseItem) =
                listOf(TaskItem("t1", name = "视频", type = "video", platform = "ketangx"))

            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: Map<String, Any>,
            ): RunTaskResult = throw IllegalStateException("ketangx: 账号登录超时，请重新登录")
        }
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(SessionData("ketangx", "stu"), RunPlan("ketangx-expired", "ketangx", "stu"))
        }

        assertTrue(error.message.orEmpty().contains("账号登录超时，请重新登录"))
        assertTrue(error.isSessionExpiredError())
        assertEquals(OperationStatus.FAILED, controller.operations.value.single().status)
    }

    @Test
    fun `completedTaskIds are not resubmitted on resume`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "math")),
            tasksByCourse = mapOf("c1" to listOf(task("t1"), task("t2"))),
        )
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan("plan3", "yinghua", "stu", completedTaskIds = listOf("t1"))
        val submitted = mgr.run(session, plan)
        assertEquals(listOf("t2"), submitted)
    }

    @Test
    fun `dryRun passes the option through`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "math")),
            tasksByCourse = mapOf("c1" to listOf(task("t1"))),
        )
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val submitted = mgr.run(session, RunPlan("plan4", "yinghua", "stu", dryRun = true), onEvent = { events.add(it) })
        assertTrue(events.any { it.message.contains("dry_run") })
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun genericHostOnlyCountsSubmittedAndDoneResults() = runTest {
        val statuses = listOf("submitted", "done", "dry_run", "prepared", "rejected", "progress", "incomplete", "skipped")
        val runner = ResultStatusRunner("generic", statuses)
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        val submitted = mgr.run(SessionData("generic", "stu"), RunPlan("result-generic", "generic", "stu"))

        assertEquals(listOf("result-0", "result-1"), submitted)
        assertEquals(runner.tasks.map { it.id }, runner.executed)
    }

    @Test
    fun xuexitongHostOnlyCountsSubmittedAndDoneResults() = runTest {
        val statuses = listOf("submitted", "done", "dry_run", "prepared", "rejected", "progress", "incomplete", "skipped")
        val runner = ResultStatusRunner("xuexitong", statuses)
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        val submitted = mgr.run(SessionData("xuexitong", "stu"), RunPlan("result-xxt", "xuexitong", "stu"))

        assertEquals(listOf("result-0", "result-1"), submitted)
        assertEquals(runner.tasks.map { it.id }, runner.executed)
    }

    @Test
    fun yinghuaViolenceModeOnlyCountsSubmittedAndDoneResults() = runTest {
        val statuses = listOf("submitted", "done", "dry_run", "prepared", "rejected", "progress", "incomplete", "skipped")
        val runner = ResultStatusRunner("yinghua", statuses)
        val platformRunner = ResultStatusPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("result-yinghua-mode2", "yinghua", "stu", yinghuaVideoModel = 2),
        )

        assertEquals(setOf("result-0", "result-1"), submitted.toSet())
        assertEquals(runner.tasks.map { it.id }.toSet(), platformRunner.executed.toSet())
    }

    @Test
    fun yinghuaRedModeKeepsSessionAliveDuringLongRun() = runTest {
        val redTask = TaskItem("slow-red", name = "slow red", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("tabVideo", true)
            addProperty("errorMessage", "检测到可能使用并行播放刷课")
            addProperty("viewedDuration", 60)
        })
        val runner = YinghuaRedRunner(redTask)
        val platformRunner = SlowYinghuaRedPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("yinghua-red-keepalive", "yinghua", "stu", yinghuaVideoModel = 3),
        )

        assertEquals(listOf("slow-red"), submitted)
        assertEquals(1, platformRunner.keepAliveCalls)
    }

    @Test
    fun yinghuaKeepAliveExpiryAbortsRunSoHostCanRelogin() = runTest {
        val redTask = TaskItem("expired-red", name = "expired red", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("tabVideo", true)
            addProperty("errorMessage", "检测到可能使用并行播放刷课")
            addProperty("viewedDuration", 60)
        })
        val runner = YinghuaRedRunner(redTask)
        val platformRunner = SlowYinghuaRedPlatformRunner(expireKeepAlive = true)
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(
                SessionData("yinghua", "stu"),
                RunPlan("yinghua-expired-keepalive", "yinghua", "stu", yinghuaVideoModel = 3),
            )
        }

        assertTrue(error.message.orEmpty().contains("session expired"))
        assertEquals(1, platformRunner.keepAliveCalls)
    }

    @Test
    fun yinghuaRedModeStopsAfterBoundedPassesWhenServerFlagNeverClears() = runTest {
        val redTask = TaskItem("stale-red", name = "stale red", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("tabVideo", true)
            addProperty("errorMessage", "检测到可能使用并行播放刷课")
            addProperty("viewedDuration", 30)
        })
        val runner = YinghuaPersistentRedRunner(redTask)
        val platformRunner = YinghuaRedPlatformRunner()
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("yinghua-stale-red", "yinghua", "stu", yinghuaVideoModel = 3),
            onEvent = events::add,
        )

        assertEquals(listOf("stale-red"), submitted)
        assertEquals(10, platformRunner.executed.size)
        assertEquals(11, runner.getTasksCalls)
        assertTrue(events.any { it.level == "warn" && it.message.contains("达到 10 轮") })
    }

    @Test
    fun yinghuaViolenceModeAutoRedRefetchesUntilWarningClears() = runTest {
        val runner = YinghuaMode2AutoRedRunner()
        val platformRunner = YinghuaRedPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("yinghua-auto-red", "yinghua", "stu", yinghuaVideoModel = 2),
        )

        assertEquals(listOf("auto-red-video"), submitted)
        assertEquals(listOf("auto-red-video", "auto-red-video"), platformRunner.executed)
        assertEquals(listOf(2, 3), platformRunner.videoModels)
        assertEquals(3, runner.getTasksCalls)
    }

    @Test
    fun yinghuaRedModeProcessesCompletedRedNodeAndRefetchesUntilClear() = runTest {
        val redTask = TaskItem(
            id = "red-video",
            name = "red video",
            type = "video",
            status = "completed",
            progress = 100.0,
            platform = "yinghua",
            raw = JsonObject().apply {
                addProperty("tabVideo", true)
                addProperty("errorMessage", "检测到可能使用并行播放刷课")
                addProperty("viewedDuration", 0)
            },
        )
        val runner = YinghuaRedRunner(redTask)
        val platformRunner = YinghuaRedPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("yinghua-red", "yinghua", "stu", yinghuaVideoModel = 3),
        )

        assertEquals(listOf("red-video"), submitted)
        assertEquals(listOf("red-video"), platformRunner.executed)
        assertEquals(listOf(3), platformRunner.videoModels)
        assertEquals(2, runner.getTasksCalls)
    }

    @Test
    fun yinghuaViolenceModeRunsVideoNodesConcurrently() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("violence-course", "violence course")),
            tasksByCourse = mapOf("violence-course" to listOf(
                TaskItem("video-1", name = "video 1", type = "video", platform = "yinghua"),
                TaskItem("video-2", name = "video 2", type = "video", platform = "yinghua"),
            )),
        )
        val platformRunner = ConcurrentYinghuaPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("yinghua-mode2-concurrent", "yinghua", "stu", yinghuaVideoModel = 2),
        )

        assertEquals(setOf("video-1", "video-2"), submitted.toSet())
        assertTrue(platformRunner.maxActive >= 2)
        assertEquals(listOf(2, 2), platformRunner.videoModels)
    }

    @Test
    fun yinghuaViolenceModeIncludesVideoCapabilityOnMixedNode() = runTest {
        val mixed = TaskItem("mixed-node", name = "submitted", type = "exam", platform = "yinghua", raw = JsonObject().apply {
            addProperty("tabVideo", true)
            addProperty("tabExam", true)
        })
        val runner = FakeRunner(
            courses = listOf(course("mixed-course", "mixed course")),
            tasksByCourse = mapOf("mixed-course" to listOf(mixed)),
        )
        val platformRunner = ResultStatusPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            SessionData("yinghua", "stu"),
            RunPlan("mixed-yinghua-mode2", "yinghua", "stu", yinghuaVideoModel = 2),
        )

        assertEquals(listOf("mixed-node"), submitted)
        assertEquals(listOf("mixed-node"), platformRunner.executed)
    }

    @Test
    fun `uses platform runner for supported host-driven tasks`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val hqTask = TaskItem("node1", name = "video", type = "video", platform = "haiqikeji")
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "haiqikeji")),
            tasksByCourse = mapOf("c1" to listOf(hqTask)),
        )
        val platformRunner = FakePlatformRunner()
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(hqSession, RunPlan("plan5", "haiqikeji", "stu"), onEvent = { events.add(it) })

        assertEquals(listOf("node1"), submitted)
        assertEquals(listOf("node1"), platformRunner.submitted)
        assertTrue(runner.submitted.isEmpty(), "generic runTask should not be used for supported platform tasks")
        assertTrue(events.any { it.message.contains("platform-runner") })
    }

    @Test
    fun `haiqikeji normal plan runs only video tasks when answering is disabled`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val tasks = listOf(
            TaskItem("video1", name = "video", type = "video", platform = "haiqikeji"),
            TaskItem("doc1", name = "document", type = "document", platform = "haiqikeji"),
            TaskItem("work1", name = "work", type = "work", platform = "haiqikeji"),
            TaskItem("exam1", name = "exam", type = "exam", platform = "haiqikeji"),
        )
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "haiqikeji")),
            tasksByCourse = mapOf("c1" to tasks),
        )
        val platformRunner = FakePlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(hqSession, RunPlan("hq-video-only", "haiqikeji", "stu"))

        assertEquals(listOf("video1"), submitted)
        assertEquals(listOf("video1"), platformRunner.submitted)
        assertTrue(runner.submitted.isEmpty())
    }

    @Test
    fun `haiqikeji violence mode runs video tasks concurrently`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val runner = FakeRunner(
            courses = listOf(
                CourseItem("c1", name = "course 1", platform = "haiqikeji"),
                CourseItem("c2", name = "course 2", platform = "haiqikeji"),
            ),
            tasksByCourse = mapOf(
                "c1" to listOf(
                    TaskItem("v1", type = "video", platform = "haiqikeji"),
                    TaskItem("v2", type = "video", platform = "haiqikeji"),
                ),
                "c2" to listOf(TaskItem("v3", type = "video", platform = "haiqikeji")),
            ),
        )
        val platformRunner = ConcurrentHaiqikejiPlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            hqSession,
            RunPlan("hq-violence", "haiqikeji", "stu", haiqikejiVideoModel = 2),
        )

        assertEquals(setOf("v1", "v2", "v3"), submitted.toSet())
        assertTrue(platformRunner.maxActive > 1)
        assertEquals(listOf(2, 2, 2), platformRunner.videoModels.sorted())
    }

    @Test
    fun `haiqikeji violence mode fails the batch when a video fails`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "haiqikeji")),
            tasksByCourse = mapOf(
                "c1" to listOf(
                    TaskItem("ok", type = "video", platform = "haiqikeji"),
                    TaskItem("broken", type = "video", platform = "haiqikeji"),
                ),
            ),
        )
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(
            runner,
            controller,
            ConcurrentHaiqikejiPlatformRunner(failTaskId = "broken"),
        )

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(
                hqSession,
                RunPlan("hq-violence-failure", "haiqikeji", "stu", haiqikejiVideoModel = 2),
            )
        }

        assertTrue(error.message.orEmpty().contains("任务失败"))
        assertEquals(OperationStatus.FAILED, controller.operations.value.single().status)
    }

    @Test
    fun `haiqikeji skips courses outside their active date range`() = runTest {
        fun datedCourse(id: String, startDate: String, endDate: String) =
            CourseItem(id, name = id, platform = "haiqikeji", raw = JsonObject().apply {
                addProperty("startDate", startDate)
                addProperty("endDate", endDate)
            })
        val courses = listOf(
            datedCourse("future", "2999-01-01", "2999-12-31"),
            datedCourse("expired", "2000-01-01", "2000-12-31"),
            datedCourse("active", "2000-01-01", "2999-12-31"),
        )
        val runner = FakeRunner(
            courses = courses,
            tasksByCourse = courses.associate { course ->
                course.id to listOf(TaskItem("${course.id}-video", type = "video", platform = "haiqikeji"))
            },
        )
        val platformRunner = FakePlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(SessionData("haiqikeji", "stu"), RunPlan("hq-dates", "haiqikeji", "stu"))

        assertEquals(listOf("active-video"), submitted)
        assertEquals(listOf("active-video"), platformRunner.submitted)
    }

    @Test
    fun `haiqikeji task expiry propagates to host relogin`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "haiqikeji")),
            tasksByCourse = mapOf("c1" to listOf(TaskItem("video1", type = "video", platform = "haiqikeji"))),
        )
        val expiringPlatform = object : PlatformTaskRunner {
            override fun supports(session: SessionData, task: TaskItem) = true
            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: PlatformTaskRunOptions,
                shouldCancel: () -> Boolean,
                onEvent: (SyncEvent) -> Unit,
            ): RunTaskResult = throw IllegalStateException("haiqikeji: session expired, please re-login")
        }
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), expiringPlatform)

        val error = assertFailsWith<IllegalStateException> {
            mgr.run(hqSession, RunPlan("hq-expired", "haiqikeji", "stu"))
        }

        assertTrue(error.message.orEmpty().contains("session expired"))
    }

    @Test
    fun `haiqikeji video mode zero does not schedule video tasks`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "haiqikeji")),
            tasksByCourse = mapOf(
                "c1" to listOf(TaskItem("video1", name = "video", type = "video", platform = "haiqikeji")),
            ),
        )
        val platformRunner = FakePlatformRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(
            hqSession,
            RunPlan("hq-disabled", "haiqikeji", "stu", haiqikejiVideoModel = 0),
        )

        assertTrue(submitted.isEmpty())
        assertTrue(platformRunner.submitted.isEmpty())
    }

    @Test
    fun `ketangx video mode zero does not schedule learning tasks`() = runTest {
        val ketangxSession = SessionData("ketangx", "stu")
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "ketangx")),
            tasksByCourse = mapOf(
                "c1" to listOf(TaskItem("video1", name = "video", type = "video", platform = "ketangx")),
            ),
        )
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        val submitted = mgr.run(
            ketangxSession,
            RunPlan("ketangx-disabled", "ketangx", "stu", ketangxVideoModel = 0),
        )

        assertTrue(submitted.isEmpty())
        assertTrue(runner.submitted.isEmpty())
    }

    @Test
    fun `ketangx runs courses concurrently but keeps nodes sequential within each course`() = runTest {
        val courses = listOf(
            CourseItem("c1", name = "course-1", platform = "ketangx"),
            CourseItem("c2", name = "course-2", platform = "ketangx"),
        )
        val runner = object : CourseTaskRunner {
            var active = 0
            var maxActive = 0
            val activeByCourse = mutableMapOf<String, Int>()
            var maxPerCourse = 0

            override suspend fun getCourses(session: SessionData) = courses

            override suspend fun getTasks(session: SessionData, course: CourseItem) = listOf(
                TaskItem("${course.id}-1", name = "first", type = "video", platform = "ketangx"),
                TaskItem("${course.id}-2", name = "second", type = "document", platform = "ketangx"),
            )

            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: Map<String, Any>,
            ): RunTaskResult {
                val courseId = task.id.substringBeforeLast("-")
                active += 1
                maxActive = maxOf(maxActive, active)
                val courseActive = activeByCourse.getOrDefault(courseId, 0) + 1
                activeByCourse[courseId] = courseActive
                maxPerCourse = maxOf(maxPerCourse, courseActive)
                return try {
                    delay(10)
                    RunTaskResult("ketangx", task.id, "submitted")
                } finally {
                    active -= 1
                    activeByCourse[courseId] = activeByCourse.getValue(courseId) - 1
                }
            }
        }
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        val submitted = mgr.run(SessionData("ketangx", "stu"), RunPlan("ketangx-parallel", "ketangx", "stu"))

        assertEquals(setOf("c1-1", "c1-2", "c2-1", "c2-2"), submitted.toSet())
        assertTrue(runner.maxActive > 1)
        assertEquals(1, runner.maxPerCourse)
    }

    @Test
    fun `ketangx ready course starts before another course finishes loading nodes`() = runTest {
        val releaseSlowCourse = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData) = listOf(
                CourseItem("slow", name = "slow", platform = "ketangx"),
                CourseItem("ready", name = "ready", platform = "ketangx"),
            )

            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
                if (course.id == "slow") releaseSlowCourse.await()
                return listOf(TaskItem("${course.id}-task", name = course.id, type = "video", platform = "ketangx"))
            }

            override suspend fun runTask(
                session: SessionData,
                task: TaskItem,
                options: Map<String, Any>,
            ): RunTaskResult {
                executed.add(task.id)
                if (task.id == "ready-task") releaseSlowCourse.complete(Unit)
                return RunTaskResult("ketangx", task.id, "submitted")
            }
        }
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        withTimeout(1_000) {
            mgr.run(SessionData("ketangx", "stu"), RunPlan("ketangx-no-fetch-barrier", "ketangx", "stu"))
        }

        assertEquals(listOf("ready-task", "slow-task"), executed)
    }

    @Test
    fun `generic task captcha is handed to resolver and original task is counted`() = runTest {
        val challengeRaw = JsonObject().apply { addProperty("captchaImage", "base64") }
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData): List<CourseItem> =
                listOf(course("c1", "math"))

            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
                listOf(task("t1"))

            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult =
                RunTaskResult("yinghua", task.id, "captcha", raw = challengeRaw)
        }
        var request: TaskChallengeRequest? = null
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            taskChallengeResolver = TaskChallengeResolver {
                request = it
                RunTaskResult(it.session.platform, it.task.id, "submitted")
            },
        )
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(session, RunPlan("plan-captcha", "yinghua", "stu"), onEvent = { events.add(it) })

        assertEquals(listOf("t1"), submitted)
        assertEquals("t1", request?.task?.id)
        assertEquals("captcha", request?.result?.status)
        assertTrue(events.any { it.message.contains("需要验证码") })
    }

    @Test
    fun `cancelling during captcha resolver marks operation cancelled instead of failed`() = runTest {
        val challengeRaw = JsonObject().apply { addProperty("captchaImage", "base64") }
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData): List<CourseItem> =
                listOf(course("c1", "math"))

            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
                listOf(task("t1"))

            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult =
                RunTaskResult("yinghua", task.id, "captcha", raw = challengeRaw)
        }
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(
            runner,
            controller,
            taskChallengeResolver = TaskChallengeResolver {
                controller.cancel(controller.operations.value.single().id)
                throw CancellationException("manual captcha cancelled")
            },
        )

        val submitted = mgr.run(session, RunPlan("plan-captcha-cancel", "yinghua", "stu"))

        assertEquals(emptyList(), submitted)
        val op = controller.operations.value.single()
        assertEquals(OperationStatus.DONE, op.status)
        assertTrue(op.detail.contains("cancelled"))
    }

    @Test
    fun `exact course name rule matches console include and exclude semantics`() {
        val includeRule = CourseSelectionRule(
            mode = CourseSelectionRule.Mode.EXACT_NAME,
            includeKeywords = listOf("数学"),
        )
        val excludeRule = CourseSelectionRule(
            mode = CourseSelectionRule.Mode.EXACT_NAME,
            excludeKeywords = listOf("数学"),
        )

        assertTrue(includeRule.matches("math-id", "数学"))
        assertFalse(includeRule.matches("数学", "高等数学"))
        assertFalse(includeRule.matches("math-id", "数学基础"))
        assertFalse(excludeRule.matches("math-id", "数学"))
        assertTrue(excludeRule.matches("math-id", "高等数学"))
    }

    @Test
    fun `course selection excludes configured keywords before include matching`() {
        val rule = CourseSelectionRule(
            mode = CourseSelectionRule.Mode.MANUAL_CONTAINS,
            includeKeywords = listOf("数学"),
            excludeKeywords = listOf("复习"),
        )

        assertTrue(rule.matches("c1", "高等数学"))
        assertFalse(rule.matches("c2", "高等数学复习课"))
        assertFalse(rule.matches("c3", "大学英语"))
    }

    @Test
    fun `xuexitong course run keeps chapter order then runs course work and exam`() = runTest {
        val runner = XuexitongFlowRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            planId = "xxt-plan",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = true,
                runChapterTest = true,
                submitWorkFinal = true,
                submitExamFinal = true,
                submitChapterTestFinal = true,
                realSubmitExam = false,
                realSubmitChapterTest = false,
            ),
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu"), plan)

        assertEquals(listOf("video-1", "quiz-1", "doc-1", "bbs-1"), submitted)
        assertEquals(
            listOf(
                "detail:course-1",
                "tasks:node-1",
                "tasks:node-2",
                "run:video-1:none",
                "run:quiz-1:pullChapterTest",
                "run:course-1:xxtAI",
                "run:quiz-1:chapterTest:false",
                "run:doc-1:none",
                "run:bbs-1:bbsPrepare",
                "run:course-1:xxtAI",
                "run:bbs-1:bbs:true",
                "run:xxt-work-list:pullWorkList",
                "run:work-1:enterWork",
                "run:work-answer:workQuestion",
                "run:course-1:xxtAI",
                "run:work-answer:work:true",
                "run:xxt-exam-list:pullExamList",
                "run:exam-1:enterExam",
                "run:exam-answer:examQuestion",
                "run:course-1:xxtAI",
                "run:exam-answer:exam:false",
            ),
            runner.calls,
        )
    }

    @Test
    fun `xuexitong exam waits for minimum submit window then retries final submit`() = runTest {
        val runner = XuexitongFlowRunner(examSubmitWaitMinutes = 5)
        val controller = OperationController(now = { 0L })
        val sleeps = mutableListOf<Long>()
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(
            runner,
            controller,
            sleepMillis = { sleeps.add(it) },
        )
        val plan = RunPlan(
            planId = "xxt-exam-submit-wait",
            platform = "xuexitong",
            account = "stu",
            xuexitong = XuexitongRunOptions(videoModel = 0),
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = false,
                runExam = true,
                runChapterTest = false,
                submitExamFinal = true,
                realSubmitExam = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan, onEvent = { events.add(it) })

        assertEquals(listOf(5 * 60_000L), sleeps)
        assertEquals(2, runner.examSubmitAttempts)
        assertEquals(2, runner.calls.count { it == "run:exam-answer:exam:true" })
        assertTrue(events.any { it.message.contains("等待后将自动重试") })
        val history = controller.questionHistory.value.single { it.scope == "exam" }
        assertEquals(QuestionHistoryStatus.SUBMITTED, history.status)
        assertTrue(history.finalSubmit)
        assertEquals("submitted", history.message)
    }

    @Test
    fun `xuexitong work uses fallback answer when built in ai is empty`() = runTest {
        val runner = XuexitongFallbackRunner()
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)
        val events = mutableListOf<SyncEvent>()
        val plan = RunPlan(
            planId = "xxt-fallback",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
                submitFinalWhenComplete = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan, onEvent = { events.add(it) })

        assertEquals(listOf("Option A"), runner.submittedAnswers)
        assertEquals(listOf(true), runner.submittedFinals)
        assertTrue(events.any { it.message.contains("使用了备用答案") })
        val history = controller.questionHistory.value.single()
        assertEquals(QuestionHistoryStatus.SUBMITTED, history.status)
        assertEquals("fallback", history.answerSource)
        assertEquals("Option A", history.answerPreview)
        assertTrue(history.finalSubmit)
    }

    @Test
    fun `xuexitong complete-only final submit saves when fallback answer is blank`() = runTest {
        val runner = XuexitongFallbackRunner(fillBlank = true)
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            planId = "xxt-fallback-blank",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
                submitFinalWhenComplete = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan)

        assertEquals(listOf(""), runner.submittedAnswers)
        assertEquals(listOf(false), runner.submittedFinals)
    }

    @Test
    fun `blank fallback answer can be edited by host before submit`() = runTest {
        val runner = XuexitongFallbackRunner(fillBlank = true)
        var request: AnswerEditRequest? = null
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(
            runner,
            controller,
            answerEditResolver = AnswerEditResolver {
                request = it
                listOf("Edited Answer")
            },
        )
        val events = mutableListOf<SyncEvent>()
        val plan = RunPlan(
            planId = "xxt-answer-edit",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
                submitFinalWhenComplete = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan, onEvent = { events.add(it) })

        assertEquals("work", request?.scope)
        assertEquals(listOf(""), request?.suggestedAnswers)
        assertEquals(listOf("Edited Answer"), runner.submittedAnswers)
        assertEquals(listOf(true), runner.submittedFinals)
        assertTrue(events.any { it.message.contains("等待手动填写答案") })
        assertTrue(controller.questionHistory.value.any { it.status == QuestionHistoryStatus.WAITING_EDIT })
        val history = controller.questionHistory.value.single { it.status == QuestionHistoryStatus.SUBMITTED }
        assertEquals(QuestionHistoryStatus.SUBMITTED, history.status)
        assertEquals("manual", history.answerSource)
        assertEquals("Edited Answer", history.answerPreview)
    }

    @Test
    fun `provider answer does not open manual answer edit`() = runTest {
        val runner = XuexitongFallbackRunner(fillBlank = true)
        val provider = FakeAnswerProvider(listOf("Provider Answer"))
        var editOpened = false
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
            answerEditResolver = AnswerEditResolver {
                editOpened = true
                listOf("Edited Answer")
            },
        )

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.HOST_AI))

        assertFalse(editOpened)
        assertEquals(listOf("Provider Answer"), runner.submittedAnswers)
    }

    @Test
    fun `blank bbs content can be edited by host before submit`() = runTest {
        val runner = XuexitongBbsRunner()
        val provider = FakeAnswerProvider(emptyList())
        var request: AnswerEditRequest? = null
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
            answerEditResolver = AnswerEditResolver {
                request = it
                listOf("Edited BBS reply")
            },
        )
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(SessionData("xuexitong", "stu"), bbsPlan(), onEvent = { events.add(it) })

        assertEquals(listOf("bbs-1"), submitted)
        assertEquals("bbs", request?.scope)
        assertEquals(listOf(""), request?.suggestedAnswers)
        assertEquals("Edited BBS reply", runner.submittedContent.single())
        assertTrue(runner.realSubmit.single())
        assertTrue(events.any { it.message.contains("等待内容编辑") })
    }

    @Test
    fun `provider bbs content does not open manual edit`() = runTest {
        val runner = XuexitongBbsRunner()
        val provider = FakeAnswerProvider(listOf("Provider BBS reply"))
        var editOpened = false
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
            answerEditResolver = AnswerEditResolver {
                editOpened = true
                listOf("Edited BBS reply")
            },
        )

        mgr.run(SessionData("xuexitong", "stu"), bbsPlan())

        assertFalse(editOpened)
        assertEquals("Provider BBS reply", runner.submittedContent.single())
    }

    @Test
    fun `bbs phone prepare failure falls back to web and submits same full reply`() = runTest {
        val runner = XuexitongBbsFallbackRunner(phonePrepareFails = true)
        val provider = FakeAnswerProvider(listOf("第一段", "第二段"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
        )
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(SessionData("xuexitong", "stu"), bbsPlan(), onEvent = { events.add(it) })

        assertEquals(listOf("bbs-1"), submitted)
        assertEquals(listOf("bbsPrepare", "bbsWebPrepare", "bbsWeb"), runner.actions)
        assertEquals("第一段\n第二段", runner.submittedContent.single())
        assertTrue(events.any { it.message.contains("回退网页端") })
    }

    @Test
    fun `bbs phone rejected submit falls back to web`() = runTest {
        val runner = XuexitongBbsFallbackRunner(phoneSubmitRejected = true)
        val provider = FakeAnswerProvider(listOf("完整回复"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
        )

        mgr.run(SessionData("xuexitong", "stu"), bbsPlan())

        assertEquals(listOf("bbsPrepare", "bbs", "bbsWebPrepare", "bbsWeb"), runner.actions)
        assertEquals(listOf("完整回复", "完整回复"), runner.submittedContent)
        assertEquals(1, provider.answerCalls)
    }

    @Test
    fun `bbs non task point skips ai and submit`() = runTest {
        val runner = XuexitongBbsFallbackRunner(nonTask = true)
        val provider = FakeAnswerProvider(listOf("should not be used"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu"), bbsPlan())

        assertTrue(submitted.isEmpty())
        assertEquals(listOf("bbsPrepare"), runner.actions)
        assertEquals(0, provider.answerCalls)
        assertTrue(runner.submittedContent.isEmpty())
    }

    @Test
    fun `autoExam host ai mode routes to host provider`() = runTest {
        val runner = XuexitongFallbackRunner()
        val host = FakeAnswerProvider(listOf("Host Answer"))
        val external = FakeAnswerProvider(listOf("External Answer"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = CompositeAnswerProviderFactory(
                builtIn = BuiltInXuexitongAnswerProvider(runner),
                hostAi = host,
                external = external,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.HOST_AI))

        assertEquals(listOf("Host Answer"), runner.submittedAnswers)
        assertEquals(1, host.answerCalls)
        assertEquals(0, external.answerCalls)
    }

    @Test
    fun `autoExam external bank mode routes to external provider`() = runTest {
        val runner = XuexitongFallbackRunner()
        val host = FakeAnswerProvider(listOf("Host Answer"))
        val external = FakeAnswerProvider(listOf("External Answer"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = CompositeAnswerProviderFactory(
                builtIn = BuiltInXuexitongAnswerProvider(runner),
                hostAi = host,
                external = external,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.EXTERNAL_QUESTION_BANK))

        assertEquals(listOf("External Answer"), runner.submittedAnswers)
        assertEquals(0, host.answerCalls)
        assertEquals(1, external.answerCalls)
    }

    @Test
    fun `haiqikeji external bank mode answers and submits work`() = runTest {
        val runner = HaiqikejiAnswerRunner("work")
        val external = FakeAnswerProvider(listOf("Option B"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { mode ->
                if (mode == AnswerMode.EXTERNAL_QUESTION_BANK) external else null
            },
        )

        val submitted = mgr.run(
            SessionData("haiqikeji", "stu"),
            haiqikejiAnswerPlan(scope = "work", submitFinal = false),
        )

        assertEquals(listOf("node-work"), submitted)
        assertEquals(listOf("pullWork", "workQuestions", "work"), runner.actions)
        assertEquals(1, external.answerCalls)
        assertEquals("single", external.requests.single().question.get("type").asString)
        assertEquals("single", external.requests.single().question.get("typeCode").asString)
        val payload = runner.submittedAnswers.single()
        assertEquals("topic-1", payload["topicId"])
        assertEquals("record-1", payload["recordId"])
        assertEquals("wr-1", payload["wrId"])
        assertEquals("wa-1", payload["waId"])
        assertEquals(1, payload["type"])
        assertEquals(listOf("Option A", "Option B"), payload["options"])
        assertEquals(listOf("A", "B"), payload["optionIdx"])
        assertEquals(listOf("Option B"), payload["answers"])
    }

    @Test
    fun `haiqikeji exam keeps real submit disabled unless policy allows it`() = runTest {
        val runner = HaiqikejiAnswerRunner("exam")
        val external = FakeAnswerProvider(listOf("Option A"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { mode ->
                if (mode == AnswerMode.EXTERNAL_QUESTION_BANK) external else null
            },
        )

        mgr.run(
            SessionData("haiqikeji", "stu"),
            haiqikejiAnswerPlan(scope = "exam", submitFinal = true, realSubmitExam = false),
        )

        assertEquals(listOf("pullExam", "examQuestions", "exam"), runner.actions)
        assertEquals(listOf(false), runner.realSubmitOptions)
        assertEquals(listOf("Option A"), runner.submittedAnswers.single()["answers"])
    }

    @Test
    fun `haiqikeji fill and short answers stay as text in save mode`() = runTest {
        val cases = listOf(
            Triple(4, "fill", "北京"),
            Triple(5, "short", "因为地球自转"),
        )
        for ((type, typeCode, answer) in cases) {
            val runner = HaiqikejiAnswerRunner(
                scope = "exam",
                questionType = type,
                questionContent = "Text question",
                questionOptions = emptyList(),
                questionOptionIdx = emptyList(),
            )
            val external = FakeAnswerProvider(listOf(answer))
            val mgr = CourseSyncManager(
                runner,
                OperationController(now = { 0L }),
                answerProviderFactory = AnswerProviderFactory { external },
            )

            val submitted = mgr.run(
                SessionData("haiqikeji", "stu"),
                haiqikejiAnswerPlan(scope = "exam", submitFinal = false, realSubmitExam = false),
            )

            assertTrue(submitted.isEmpty())
            assertEquals(typeCode, external.requests.single().question.get("type").asString)
            assertEquals(typeCode, external.requests.single().question.get("typeCode").asString)
            val payload = runner.submittedAnswers.single()
            assertEquals(type, payload["type"])
            assertEquals(emptyList<String>(), payload["options"])
            assertEquals(listOf(answer), payload["answers"])
            assertEquals(listOf(false), runner.realSubmitOptions)
        }
    }

    @Test
    fun `haiqikeji mixed nodes finish all videos before exam answering`() = runTest {
        val runner = HaiqikejiCapabilityRunner(
            listOf(
                TaskItem(
                    "mixed",
                    name = "mixed",
                    type = "video",
                    platform = "haiqikeji",
                    raw = obj("courseId" to "course-1", "tabVideo" to 1, "tabExam" to 1),
                ),
                TaskItem(
                    "video",
                    name = "video",
                    type = "video",
                    platform = "haiqikeji",
                    raw = obj("courseId" to "course-1", "tabVideo" to 1),
                ),
            ),
        )
        val external = FakeAnswerProvider(listOf("Option A"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            platformRunner = runner,
            answerProviderFactory = AnswerProviderFactory { external },
        )

        mgr.run(
            SessionData("haiqikeji", "stu"),
            haiqikejiAnswerPlan(scope = "exam", submitFinal = true, realSubmitExam = true),
        )

        assertEquals(listOf("video:mixed", "video:video", "pullExam", "examQuestions", "exam"), runner.actions)
    }

    @Test
    fun `haiqikeji mode2 mixed nodes answer only after all videos finish`() = runTest {
        val runner = HaiqikejiCapabilityRunner(
            listOf(
                TaskItem(
                    "mixed",
                    type = "video",
                    platform = "haiqikeji",
                    raw = obj("courseId" to "course-1", "tabVideo" to 1, "tabExam" to 1),
                ),
                TaskItem(
                    "video",
                    type = "video",
                    platform = "haiqikeji",
                    raw = obj("courseId" to "course-1", "tabVideo" to 1),
                ),
            ),
        )
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            platformRunner = runner,
            answerProviderFactory = AnswerProviderFactory { FakeAnswerProvider(listOf("Option A")) },
        )
        val plan = haiqikejiAnswerPlan(scope = "exam", submitFinal = true, realSubmitExam = true)
            .copy(haiqikejiVideoModel = 2)

        mgr.run(SessionData("haiqikeji", "stu"), plan)

        val pullExamIndex = runner.actions.indexOf("pullExam")
        assertTrue(pullExamIndex > runner.actions.indexOf("video:mixed"))
        assertTrue(pullExamIndex > runner.actions.indexOf("video:video"))
        assertEquals(1, runner.actions.count { it == "exam" })
    }

    @Test
    fun `haiqikeji answers the same exam only once per course`() = runTest {
        val runner = HaiqikejiCapabilityRunner(
            listOf(
                TaskItem("exam-node-1", type = "exam", platform = "haiqikeji", raw = obj("courseId" to "course-1", "tabExam" to 1)),
                TaskItem("exam-node-2", type = "exam", platform = "haiqikeji", raw = obj("courseId" to "course-1", "tabExam" to 1)),
            ),
        )
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            platformRunner = runner,
            answerProviderFactory = AnswerProviderFactory { FakeAnswerProvider(listOf("Option A")) },
        )

        mgr.run(
            SessionData("haiqikeji", "stu"),
            haiqikejiAnswerPlan(scope = "exam", submitFinal = true, realSubmitExam = true),
        )

        assertEquals(2, runner.actions.count { it == "pullExam" })
        assertEquals(1, runner.actions.count { it == "examQuestions" })
        assertEquals(1, runner.actions.count { it == "exam" })
    }

    @Test
    fun `autoExam xuexitong built in mode still calls xxtAI action`() = runTest {
        val runner = XuexitongFlowRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.XUEXITONG_BUILT_IN))

        assertTrue(runner.calls.any { it == "run:course-1:xxtAI" })
    }

    @Test
    fun `xuexitong video model zero skips media tasks but keeps answer tasks`() = runTest {
        val runner = XuexitongFlowRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            planId = "xxt-video-off",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = false,
                runExam = false,
                runChapterTest = true,
            ),
            xuexitong = XuexitongRunOptions(videoModel = 0),
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu"), plan)

        assertEquals(listOf("quiz-1", "bbs-1"), submitted)
        assertFalse(runner.calls.any { it == "run:video-1:none" })
        assertFalse(runner.calls.any { it == "run:doc-1:none" })
    }

    @Test
    fun `xuexitong mode3 builds bounded relogin pool and runs node tasks`() = runTest {
        val runner = XuexitongFlowRunner()
        val relogins = mutableListOf<Int>()
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            xuexitongSessionProvider = XuexitongSessionProvider { primary, index ->
                relogins.add(index)
                primary.copy(cookies = "pool-$index")
            },
            mode3LoginSpacingMillis = 0,
            sleepMillis = {},
        )
        val plan = RunPlan(
            planId = "xxt-mode3",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(enabled = false),
            xuexitong = XuexitongRunOptions(videoModel = 3, cxNode = 2),
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu", cookies = "primary"), plan)

        assertEquals(listOf(1), relogins)
        assertEquals(setOf("video-1", "doc-1"), submitted.toSet())
        assertTrue(runner.calls.contains("run:video-1:none"))
        assertTrue(runner.calls.contains("run:doc-1:none"))
    }

    private class XuexitongFlowRunner(
        private val examSubmitWaitMinutes: Int = 0,
    ) : CourseTaskRunner {
        val calls = mutableListOf<String>()
        var examSubmitAttempts = 0
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())
        private val node1 = CourseItem("node-1", name = "Node 1", platform = "xuexitong", raw = ctx())
        private val node2 = CourseItem("node-2", name = "Node 2", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> {
            calls.add("detail:${course.id}")
            return listOf(node1, node2)
        }

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
            calls.add("tasks:${course.id}")
            return when (course.id) {
                "node-1" -> listOf(
                    TaskItem("video-1", name = "Video", type = "video", platform = "xuexitong", raw = ctx()),
                    TaskItem("quiz-1", name = "Quiz", type = "quiz", platform = "xuexitong", raw = ctx()),
                )
                "node-2" -> listOf(
                    TaskItem("doc-1", name = "Document", type = "document", platform = "xuexitong", raw = ctx()),
                    TaskItem("bbs-1", name = "BBS", type = "bbs", platform = "xuexitong", raw = ctx()),
                )
                else -> emptyList()
            }
        }

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            val action = options["action"] as? String ?: "none"
            val submitSuffix = when (action) {
                "chapterTest", "exam" -> ":${options["isSubmit"] == true && options["realSubmit"] == true}"
                "work" -> ":${options["isSubmit"] == true}"
                "bbs" -> ":${options["realSubmit"] == true}"
                else -> ""
            }
            calls.add("run:${task.id}:$action$submitSuffix")
            return when (action) {
                "pullChapterTest" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj(
                    "meta" to ctx(),
                    "questions" to arr(question("q-chapter")),
                ))
                "xxtAI" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("answers" to arr("A")))
                "bbsPrepare" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("prompt" to "Discuss"))
                "pullWorkList" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("works" to arr(obj("taskRefId" to "work-1", "name" to "Work"))))
                "enterWork" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("workId" to "work-answer", "questionTotal" to 1))
                "workQuestion" -> RunTaskResult("xuexitong", task.id, "ok", raw = question("q-work"))
                "pullExamList" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("exams" to arr(obj("taskRefId" to "exam-1", "name" to "Exam"))))
                "enterExam" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("examRelationId" to "exam-answer", "questionTotal" to 1))
                "examQuestion" -> RunTaskResult("xuexitong", task.id, "ok", raw = question("q-exam"))
                "exam" -> {
                    examSubmitAttempts++
                    if (examSubmitWaitMinutes > 0 && examSubmitAttempts == 1) {
                        RunTaskResult(
                            "xuexitong",
                            task.id,
                            "submit_wait",
                            raw = obj("retryAfterMinutes" to examSubmitWaitMinutes),
                        )
                    } else {
                        RunTaskResult("xuexitong", task.id, "submitted")
                    }
                }
                else -> RunTaskResult("xuexitong", task.id, "submitted")
            }
        }

        private fun question(qid: String) = obj(
            "qid" to qid,
            "typeCode" to "single",
            "content" to "Question $qid",
            "options" to obj("A" to "A", "B" to "B"),
            "submit" to obj(),
        )

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj(
                "classId" to "class-1",
                "courseId" to "course-1",
                "cpi" to "cpi-1",
                *extra,
        )
    }

    private class XuexitongFallbackRunner(private val fillBlank: Boolean = false) : CourseTaskRunner {
        val submittedAnswers = mutableListOf<String>()
        val submittedFinals = mutableListOf<Boolean>()
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = emptyList()

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            return when (options["action"] as? String ?: "none") {
                "pullWorkList" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("works" to arr(obj("taskRefId" to "work-1", "name" to "Work"))))
                "enterWork" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("workId" to "work-answer", "questionTotal" to 1))
                "workQuestion" -> RunTaskResult("xuexitong", task.id, "ok", raw = if (fillBlank) {
                    obj(
                        "qid" to "q-work",
                        "typeCode" to "fill",
                        "content" to "Question",
                        "submit" to obj(),
                    )
                } else {
                    obj(
                        "qid" to "q-work",
                        "typeCode" to "single",
                        "content" to "Question",
                        "options" to obj("A" to "Option A", "B" to "Option B"),
                        "submit" to obj(),
                    )
                })
                "xxtAI" -> RunTaskResult("xuexitong", task.id, "ok", raw = JsonObject())
                "work" -> {
                    @Suppress("UNCHECKED_CAST")
                    val question = options["question"] as Map<String, Any>
                    @Suppress("UNCHECKED_CAST")
                    submittedAnswers.addAll(question["answers"] as List<String>)
                    submittedFinals.add(options["isSubmit"] == true)
                    RunTaskResult("xuexitong", task.id, "submitted")
                }
                else -> RunTaskResult("xuexitong", task.id, "ok")
            }
        }

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj(
                "classId" to "class-1",
                "courseId" to "course-1",
                "cpi" to "cpi-1",
                *extra,
            )
    }

    private class XuexitongBbsRunner : CourseTaskRunner {
        val submittedContent = mutableListOf<String>()
        val realSubmit = mutableListOf<Boolean>()
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())
        private val node = CourseItem("node-1", name = "Node", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> =
            listOf(node)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
            listOf(TaskItem("bbs-1", name = "BBS", type = "bbs", platform = "xuexitong", raw = ctx()))

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            return when (options["action"] as? String ?: "none") {
                "bbsPrepare" -> RunTaskResult("xuexitong", task.id, "prepared", raw = ctx(
                    "prompt" to "Discussion title\nDiscussion content",
                    "title" to "Discussion title",
                    "detail" to "Discussion content",
                    "jobId" to task.id,
                    "topicUUID" to "topic-1",
                    "topicClassId" to "class-1",
                ))
                "bbs" -> {
                    submittedContent.add(options["content"] as String)
                    realSubmit.add(options["realSubmit"] == true)
                    RunTaskResult("xuexitong", task.id, "submitted")
                }
                else -> RunTaskResult("xuexitong", task.id, "ok")
            }
        }

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj(
                "classId" to "class-1",
                "courseId" to "course-1",
                "cpi" to "cpi-1",
                "knowledgeId" to 111,
                *extra,
            )
    }

    private class XuexitongBbsFallbackRunner(
        private val phonePrepareFails: Boolean = false,
        private val phoneSubmitRejected: Boolean = false,
        private val nonTask: Boolean = false,
    ) : CourseTaskRunner {
        val actions = mutableListOf<String>()
        val submittedContent = mutableListOf<String>()
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())
        private val node = CourseItem("node-1", name = "Node", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)
        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = listOf(node)
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
            listOf(TaskItem("bbs-1", name = "BBS", type = "bbs", platform = "xuexitong", raw = ctx()))

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            val action = options["action"] as? String ?: "none"
            actions.add(action)
            return when (action) {
                "bbsPrepare" -> {
                    if (phonePrepareFails) error("phone unavailable")
                    if (nonTask) RunTaskResult("xuexitong", task.id, "skipped", raw = ctx("isJob" to false, "isJobKnown" to true))
                    else RunTaskResult("xuexitong", task.id, "prepared", raw = preparedRaw("phone"))
                }
                "bbsWebPrepare" -> RunTaskResult("xuexitong", task.id, "prepared", raw = preparedRaw("web"))
                "bbs" -> {
                    submittedContent.add(options["content"] as String)
                    if (phoneSubmitRejected) RunTaskResult("xuexitong", task.id, "rejected", "phone rejected")
                    else RunTaskResult("xuexitong", task.id, "submitted")
                }
                "bbsWeb" -> {
                    submittedContent.add(options["content"] as String)
                    RunTaskResult("xuexitong", task.id, "submitted")
                }
                else -> RunTaskResult("xuexitong", task.id, "ok")
            }
        }

        private fun preparedRaw(platform: String): JsonObject = ctx(
            "prompt" to "Discussion title\nDiscussion content",
            "title" to "Discussion title",
            "detail" to "Discussion content",
            "jobId" to "bbs-1",
            "isJob" to true,
            "isJobKnown" to true,
            "topicUUID" to "topic-1",
            "topicClassId" to "class-1",
            "bbsPlatform" to platform,
            "urlToken" to "token-1",
            "bbsId" to "bbs-1",
            "enc" to "enc-1",
        )

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject = obj(
            "classId" to "class-1",
            "courseId" to "course-1",
            "cpi" to "cpi-1",
            "knowledgeId" to 111,
            *extra,
        )
    }

    private class HaiqikejiAnswerRunner(
        private val scope: String,
        private val questionType: Int = 1,
        private val questionContent: String = "Pick one",
        private val questionOptions: List<String> = listOf("Option A", "Option B"),
        private val questionOptionIdx: List<String> = listOf("A", "B"),
    ) : CourseTaskRunner {
        val actions = mutableListOf<String>()
        val submittedAnswers = mutableListOf<Map<String, Any>>()
        val realSubmitOptions = mutableListOf<Boolean>()
        private val course = CourseItem("course-1", name = "Course", platform = "haiqikeji", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
            listOf(TaskItem("node-$scope", name = "Node", type = scope, platform = "haiqikeji", raw = ctx("nodeId" to "node-$scope")))

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            val action = options["action"] as? String ?: "none"
            actions.add(action)
            return when (action) {
                "pullWork" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "done",
                    raw = obj("works" to arr(quiz("work"))),
                )
                "pullExam" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "done",
                    raw = obj("exams" to arr(quiz("exam"))),
                )
                "workQuestions", "examQuestions" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "questions",
                    raw = obj("questions" to arr(question())),
                )
                "work", "exam" -> {
                    @Suppress("UNCHECKED_CAST")
                    val answers = options["answers"] as List<Map<String, Any>>
                    submittedAnswers.addAll(answers)
                    if (action == "exam") realSubmitOptions.add(options["realSubmit"] == true)
                    RunTaskResult("haiqikeji", task.id, if (action == "exam" && options["realSubmit"] != true) "saved" else "submitted")
                }
                else -> RunTaskResult("haiqikeji", task.id, "ok")
            }
        }

        private fun quiz(scope: String): JsonObject =
            obj(
                "${scope}Id" to "$scope-1",
                "title" to "$scope title",
                "courseId" to "course-1",
                "nodeId" to "node-$scope",
            )

        private fun question(): JsonObject =
            obj(
                "topicId" to "topic-1",
                "recordId" to "record-1",
                "wrId" to "wr-1",
                "waId" to "wa-1",
                "type" to questionType,
                "content" to questionContent,
                "options" to JsonArray().apply { questionOptions.forEach(::add) },
                "optionIdx" to JsonArray().apply { questionOptionIdx.forEach(::add) },
            )

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj("courseId" to "course-1", *extra)
    }

    private class HaiqikejiCapabilityRunner(
        private val tasks: List<TaskItem>,
    ) : CourseTaskRunner, PlatformTaskRunner {
        val actions = mutableListOf<String>()
        private val course = CourseItem("course-1", name = "Course", platform = "haiqikeji", raw = obj("courseId" to "course-1"))

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = tasks

        override fun supports(session: SessionData, task: TaskItem): Boolean =
            task.type.equals("video", true) ||
                runCatching { task.raw.get("tabVideo")?.asInt ?: 0 }.getOrDefault(0) > 0

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            actions.add("video:${task.id}")
            return RunTaskResult("haiqikeji", task.id, "submitted")
        }

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            val action = options["action"] as? String ?: "none"
            actions.add(action)
            return when (action) {
                "pullExam" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "done",
                    raw = obj(
                        "exams" to arr(
                            obj(
                                "examId" to "shared-exam",
                                "title" to "Exam",
                                "courseId" to "course-1",
                                "nodeId" to task.id,
                            ),
                        ),
                    ),
                )
                "examQuestions" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "questions",
                    raw = obj(
                        "questions" to arr(
                            obj(
                                "topicId" to "topic-1",
                                "recordId" to "record-1",
                                "wrId" to "wr-1",
                                "waId" to "wa-1",
                                "type" to 1,
                                "content" to "Pick one",
                                "options" to arr("Option A", "Option B"),
                                "optionIdx" to arr("A", "B"),
                            ),
                        ),
                    ),
                )
                "exam" -> RunTaskResult("haiqikeji", task.id, "submitted")
                else -> RunTaskResult("haiqikeji", task.id, "ok")
            }
        }
    }

    private class FakeAnswerProvider(private val answers: List<String>) : AnswerProvider {
        var answerCalls = 0
        val requests = mutableListOf<AnswerRequest>()
        override suspend fun answers(request: AnswerRequest): List<String> {
            answerCalls++
            requests.add(request)
            return answers
        }
    }

    private companion object {
        fun answerPlan(mode: AnswerMode): RunPlan = RunPlan(
            planId = "answer-plan-$mode",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = mode,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
            ),
        )

        fun haiqikejiAnswerPlan(
            scope: String,
            submitFinal: Boolean,
            realSubmitExam: Boolean = false,
        ): RunPlan = RunPlan(
            planId = "haiqikeji-$scope-answer-plan",
            platform = "haiqikeji",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.EXTERNAL_QUESTION_BANK,
                runWork = scope == "work",
                runExam = scope == "exam",
                runChapterTest = false,
                submitWorkFinal = submitFinal,
                submitExamFinal = submitFinal,
                realSubmitExam = realSubmitExam,
            ),
        )

        fun bbsPlan(): RunPlan = RunPlan(
            planId = "bbs-plan",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.HOST_AI,
                runWork = false,
                runExam = false,
                runChapterTest = false,
            ),
        )

        fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply {
            for ((key, value) in pairs) {
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is JsonObject -> add(key, value)
                    is JsonArray -> add(key, value)
                    else -> addProperty(key, value.toString())
                }
            }
        }

        fun arr(vararg values: JsonObject): JsonArray = JsonArray().apply { values.forEach(::add) }
        fun arr(vararg values: String): JsonArray = JsonArray().apply { values.forEach(::add) }
    }
}
