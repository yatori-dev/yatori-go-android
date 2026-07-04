package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonObject
import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.*
import dev.yatori.mobile.runtime.CoreGateway
import dev.yatori.mobile.runtime.MobilecoreStore
import dev.yatori.mobile.runtime.YatoriCoreRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class PlatformTaskSchedulerTest {
    private lateinit var tmp: File
    private lateinit var store: MobilecoreStore
    private lateinit var gateway: FakeGateway
    private lateinit var taskScheduler: PlatformTaskScheduler

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("platform-task-scheduler-test").toFile()
        store = MobilecoreStore(tmp)
        gateway = FakeGateway()
        val repo = YatoriCoreRepository(gateway, store)
        val actions = PlatformActionScheduler(repo, now = { 1000L })
        taskScheduler = PlatformTaskScheduler(actions, sleepMillis = { gateway.sleeps.add(it) })
    }

    @After
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun `haiqikeji task scheduler drives start submit end and persists raw`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", name = "video", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 60)
        })
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "started", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "submitted", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
            addProperty("progress", 50)
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "submitted", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
            addProperty("progress", 100)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals("submitted", result.status)
        assertEquals(listOf("start", "submit", "end"), gateway.options.map { it["action"] })
        assertEquals(50, gateway.options[1]["progress"])
        assertEquals(100, gateway.options[2]["progress"])
        assertEquals(listOf(30_000L), gateway.sleeps)
        val state = store.loadActionState("haiqikeji", "stu", "node1", "haiqikeji-progress")!!
        assertEquals("sid-1", state.task.raw.get("sessionId").asString)
        assertEquals(100.0, state.progress)
    }

    @Test
    fun `xuexitong audio scheduler drives prepare and 58s ticks`() = runTest {
        val session = SessionData("xuexitong", "stu")
        val task = TaskItem("audio1", name = "audio", type = "audio", platform = "xuexitong", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("classId", "class1")
            addProperty("cpi", "cpi1")
            addProperty("knowledgeId", "k1")
        })
        gateway.results.add(RunTaskResult("xuexitong", "audio1", "prepared", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("duration", 116)
            addProperty("intervalSeconds", 58)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "audio1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("duration", 116)
            addProperty("playingTime", 58)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "audio1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("duration", 116)
            addProperty("playingTime", 116)
        }))

        taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals(listOf("audioPrepare", "audioTick", "audioTick"), gateway.options.map { it["action"] })
        assertEquals(58, gateway.options[1]["playingTime"])
        assertEquals(116, gateway.options[2]["playingTime"])
        assertEquals(listOf(58_000L), gateway.sleeps)
        assertEquals(116.0, store.loadActionState("xuexitong", "stu", "audio1", "xuexitong-audio")!!.progress)
    }

    @Test
    fun `xuexitong video scheduler drives prepare and 58s ticks`() = runTest {
        val session = SessionData("xuexitong", "stu")
        val task = TaskItem("video1", name = "video", type = "video", platform = "xuexitong", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("classId", "class1")
            addProperty("cpi", "cpi1")
            addProperty("knowledgeId", "k1")
        })
        gateway.results.add(RunTaskResult("xuexitong", "video1", "prepared", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 116)
            addProperty("intervalSeconds", 58)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "video1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 116)
            addProperty("playingTime", 58)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "video1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 116)
            addProperty("playingTime", 116)
        }))

        taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals(listOf("videoPrepare", "videoTick", "videoTick"), gateway.options.map { it["action"] })
        assertEquals(58, gateway.options[1]["playingTime"])
        assertEquals(116, gateway.options[2]["playingTime"])
        assertEquals(listOf(58_000L), gateway.sleeps)
        assertEquals(116.0, store.loadActionState("xuexitong", "stu", "video1", "xuexitong-video")!!.progress)
    }

    @Test
    fun `yinghua video scheduler drives state and 5s ticks from viewed duration`() = runTest {
        val session = SessionData("yinghua", "stu")
        val task = TaskItem("video1", name = "video", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("videoDuration", 15)
            addProperty("viewedDuration", 5)
        })
        gateway.results.add(RunTaskResult("yinghua", "video1", "started", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("videoDuration", 15)
        }))
        gateway.results.add(RunTaskResult("yinghua", "video1", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("studyTime", 10)
            addProperty("videoDuration", 15)
        }))
        gateway.results.add(RunTaskResult("yinghua", "video1", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "study-2")
            addProperty("studyTime", 15)
            addProperty("videoDuration", 15)
        }))

        taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals(listOf("state", null, null), gateway.options.map { it["action"] })
        assertEquals(10, gateway.options[1]["studyTime"])
        assertEquals(15, gateway.options[2]["studyTime"])
        assertEquals(listOf(5_000L), gateway.sleeps)
        val state = store.loadActionState("yinghua", "stu", "video1", "yinghua-progress")!!
        assertEquals(15.0, state.progress)
        assertEquals("study-2", state.task.raw.get("studyId").asString)
    }

    @Test
    fun `xuexitong document scheduler drives prepare and submit`() = runTest {
        val session = SessionData("xuexitong", "stu")
        val task = TaskItem("doc1", name = "doc", type = "document", platform = "xuexitong")
        gateway.results.add(RunTaskResult("xuexitong", "doc1", "prepared", raw = JsonObject().apply {
            addProperty("jobId", "job-doc")
            addProperty("jtoken", "token")
        }))
        gateway.results.add(RunTaskResult("xuexitong", "doc1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-doc")
        }))

        val result = taskScheduler.runTask(session, task)

        assertEquals("submitted", result.status)
        assertEquals(listOf("documentPrepare", "document"), gateway.options.map { it["action"] })
        assertEquals(100.0, store.loadActionState("xuexitong", "stu", "doc1", "xuexitong-document")!!.progress)
    }

    private class FakeGateway : CoreGateway {
        val results = ArrayDeque<RunTaskResult>()
        val options = mutableListOf<Map<String, Any>>()
        val sleeps = mutableListOf<Long>()

        override suspend fun init(baseDir: String) = InitResult(baseDir, "fake")
        override suspend fun healthCheck() = HealthInfo("fake", "go0", true, true)
        override suspend fun getConfigSchema(): List<ConfigField> = emptyList()
        override suspend fun setConfig(config: MobileConfig) {}
        override suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) {}
        override suspend fun getConfig() = MobileConfig()
        override suspend fun startLogin(platform: Platform, account: AccountInput): LoginResult =
            LoginResult.Done(SessionData(platform.id, account.account))
        override suspend fun continueLogin(taskId: String, result: OcrResult): LoginResult =
            LoginResult.Done(SessionData("p", "a"))
        override suspend fun cancelLogin(taskId: String) {}
        override suspend fun getCourses(session: SessionData): List<CourseItem> = emptyList()
        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = emptyList()
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            this.options.add(options)
            return results.removeFirst()
        }
        override suspend fun getLogs(cursor: String) = LogResult("", "", false, emptyList())
        override suspend fun clearLogs() {}
        override suspend fun setLogLevel(level: String) {}
    }
}
