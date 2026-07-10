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
import kotlin.test.assertFailsWith

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
    fun `haiqikeji task scheduler drives start submit end verify and persists raw`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", name = "video", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 60)
        })
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "done", raw = JsonObject().apply {
            addProperty("progress", 0)
        }))
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
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "ended", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "done", raw = JsonObject().apply {
            addProperty("progress", 100)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals("done", result.status)
        assertEquals(listOf("getProgress", "start", "submit", "submit", "end", "getProgress"), gateway.options.map { it["action"] })
        assertEquals(50, gateway.options[2]["progress"])
        assertEquals(100, gateway.options[3]["progress"])
        assertEquals(listOf(30_000L, 30_000L), gateway.sleeps)
        val state = store.loadActionState("haiqikeji", "stu", "node1", "haiqikeji-progress")!!
        assertEquals("sid-1", state.task.raw.get("sessionId").asString)
        assertEquals(100.0, state.progress)
    }

    @Test
    fun `haiqikeji normal mode waits before first progress submission`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 30)
        })
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "done", raw = JsonObject().apply {
            addProperty("progress", 0)
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "started", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "submitted", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
            addProperty("progress", 100)
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "ended", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
        }))
        gateway.results.add(RunTaskResult("haiqikeji", "node1", "done", raw = JsonObject().apply {
            addProperty("progress", 100)
        }))

        taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals(listOf(30_000L), gateway.sleeps)
    }

    @Test
    fun `haiqikeji retries a full study cycle when verified progress is below 100`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 30)
        })
        fun result(status: String, progress: Int? = null, sessionId: String? = null) =
            RunTaskResult("haiqikeji", "node1", status, raw = JsonObject().apply {
                progress?.let { addProperty("progress", it) }
                sessionId?.let { addProperty("sessionId", it) }
            })
        listOf(
            result("done", progress = 0),
            result("started", sessionId = "sid-1"),
            result("submitted", progress = 100, sessionId = "sid-1"),
            result("ended", sessionId = "sid-1"),
            result("done", progress = 80),
            result("started", sessionId = "sid-2"),
            result("submitted", progress = 100, sessionId = "sid-2"),
            result("ended", sessionId = "sid-2"),
            result("done", progress = 100),
        ).forEach(gateway.results::add)

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals("done", result.status)
        assertEquals(100.0, result.raw.get("progress").asDouble)
        assertEquals(
            listOf("getProgress", "start", "submit", "end", "getProgress", "start", "submit", "end", "getProgress"),
            gateway.options.map { it["action"] },
        )
        assertEquals(listOf(30_000L, 30_000L), gateway.sleeps)
    }

    @Test
    fun `haiqikeji cancellation during wait ends session without submitting another tick`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 60)
        })
        fun result(status: String, progress: Int? = null, sessionId: String? = null) =
            RunTaskResult("haiqikeji", "node1", status, raw = JsonObject().apply {
                progress?.let { addProperty("progress", it) }
                sessionId?.let { addProperty("sessionId", it) }
            })
        listOf(
            result("done", progress = 0),
            result("started", sessionId = "sid-1"),
            result("ended", sessionId = "sid-1"),
        ).forEach(gateway.results::add)
        var cancelled = false
        val repo = YatoriCoreRepository(gateway, store)
        val scheduler = PlatformTaskScheduler(
            PlatformActionScheduler(repo, now = { 1000L }),
            sleepMillis = {
                gateway.sleeps.add(it)
                cancelled = true
            },
        )

        val result = scheduler.runTask(
            session,
            task,
            PlatformTaskRunOptions(maxTicksPerTask = 10),
            shouldCancel = { cancelled },
        )

        assertEquals("ended", result.status)
        assertEquals(listOf("getProgress", "start", "end"), gateway.options.map { it["action"] })
        assertEquals(listOf(30_000L), gateway.sleeps)
    }

    @Test
    fun `haiqikeji normal video is not truncated by generic max tick limit`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 60)
        })
        fun result(status: String, progress: Int? = null, sessionId: String? = null) =
            RunTaskResult("haiqikeji", "node1", status, raw = JsonObject().apply {
                progress?.let { addProperty("progress", it) }
                sessionId?.let { addProperty("sessionId", it) }
            })
        listOf(
            result("done", progress = 0),
            result("started", sessionId = "sid-1"),
            result("submitted", progress = 50, sessionId = "sid-1"),
            result("submitted", progress = 100, sessionId = "sid-1"),
            result("ended", sessionId = "sid-1"),
            result("done", progress = 100),
        ).forEach(gateway.results::add)

        val result = taskScheduler.runTask(
            session,
            task,
            PlatformTaskRunOptions(maxTicksPerTask = 1),
        )

        assertEquals("done", result.status)
        assertEquals(
            listOf("getProgress", "start", "submit", "submit", "end", "getProgress"),
            gateway.options.map { it["action"] },
        )
        assertEquals(listOf(30_000L, 30_000L), gateway.sleeps)
    }

    @Test
    fun `haiqikeji progress ticks do not consume verification cycle budget`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 60)
        })
        fun result(status: String, progress: Int? = null, sessionId: String? = null) =
            RunTaskResult("haiqikeji", "node1", status, raw = JsonObject().apply {
                progress?.let { addProperty("progress", it) }
                sessionId?.let { addProperty("sessionId", it) }
            })
        listOf(
            result("done", progress = 0),
            result("started", sessionId = "sid-1"),
            result("submitted", progress = 50, sessionId = "sid-1"),
            result("submitted", progress = 100, sessionId = "sid-1"),
            result("ended", sessionId = "sid-1"),
            result("done", progress = 80),
            result("started", sessionId = "sid-2"),
            result("submitted", progress = 100, sessionId = "sid-2"),
            result("ended", sessionId = "sid-2"),
            result("done", progress = 100),
        ).forEach(gateway.results::add)

        val result = taskScheduler.runTask(
            session,
            task,
            PlatformTaskRunOptions(maxTicksPerTask = 2),
        )

        assertEquals("done", result.status)
        assertEquals(
            listOf(
                "getProgress", "start", "submit", "submit", "end", "getProgress",
                "start", "submit", "end", "getProgress",
            ),
            gateway.options.map { it["action"] },
        )
        assertEquals(listOf(30_000L, 30_000L, 30_000L), gateway.sleeps)
    }

    @Test
    fun `haiqikeji fast mode submits 100 before ending the session`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 30)
        })
        fun result(status: String, progress: Int? = null, sessionId: String? = null) =
            RunTaskResult("haiqikeji", "node1", status, raw = JsonObject().apply {
                progress?.let { addProperty("progress", it) }
                sessionId?.let { addProperty("sessionId", it) }
            })
        listOf(
            result("done", progress = 0),
            result("started", sessionId = "sid-1"),
            result("submitted", progress = 100, sessionId = "sid-1"),
            result("ended", sessionId = "sid-1"),
            result("done", progress = 100),
        ).forEach(gateway.results::add)

        val result = taskScheduler.runTask(
            session,
            task,
            PlatformTaskRunOptions(maxTicksPerTask = 10, videoModel = 2),
        )

        assertEquals("done", result.status)
        assertEquals(listOf("getProgress", "start", "submit", "end", "getProgress"), gateway.options.map { it["action"] })
        assertEquals(100, gateway.options[2]["progress"])
    }

    @Test
    fun `haiqikeji fast mode ends the session when submit fails`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("duration", 30)
        })
        fun result(status: String, progress: Int? = null, sessionId: String? = null) =
            RunTaskResult("haiqikeji", "node1", status, raw = JsonObject().apply {
                progress?.let { addProperty("progress", it) }
                sessionId?.let { addProperty("sessionId", it) }
            })
        listOf(
            result("done", progress = 0),
            result("started", sessionId = "sid-1"),
            result("ended", sessionId = "sid-1"),
        ).forEach(gateway.results::add)
        gateway.failAction = "submit"

        assertFailsWith<IllegalStateException> {
            taskScheduler.runTask(session, task, PlatformTaskRunOptions(videoModel = 2))
        }

        assertEquals(listOf("getProgress", "start", "submit", "end"), gateway.options.map { it["action"] })
    }

    @Test
    fun `xuexitong audio scheduler sends start heartbeat then timed 58s ticks`() = runTest {
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
        gateway.results.add(RunTaskResult("xuexitong", "audio1", "progress", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("duration", 116)
            addProperty("playingTime", 0)
            addProperty("isPassed", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "audio1", "progress", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("duration", 116)
            addProperty("playingTime", 58)
            addProperty("isPassed", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "audio1", "done", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("duration", 116)
            addProperty("playingTime", 116)
            addProperty("isPassed", true)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals("done", result.status)
        assertEquals(listOf("audioPrepare", "audioTick", "audioTick", "audioTick"), gateway.options.map { it["action"] })
        assertEquals(listOf(0, 58, 116), gateway.options.drop(1).map { it["playingTime"] })
        assertEquals(listOf(3, 0, 0), gateway.options.drop(1).map { it["isdrag"] })
        assertEquals(listOf(58_000L, 58_000L), gateway.sleeps.take(2))
        assertEquals(116.0, store.loadActionState("xuexitong", "stu", "audio1", "xuexitong-audio")!!.progress)
    }

    @Test
    fun `xuexitong video scheduler sends start heartbeat then timed 58s ticks`() = runTest {
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
        gateway.results.add(RunTaskResult("xuexitong", "video1", "progress", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 116)
            addProperty("playingTime", 0)
            addProperty("isPassed", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "video1", "progress", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 116)
            addProperty("playingTime", 58)
            addProperty("isPassed", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "video1", "done", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 116)
            addProperty("playingTime", 116)
            addProperty("isPassed", true)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals("done", result.status)
        assertEquals(listOf("videoPrepare", "videoTick", "videoTick", "videoTick"), gateway.options.map { it["action"] })
        assertEquals(listOf(0, 58, 116), gateway.options.drop(1).map { it["playingTime"] })
        assertEquals(listOf(3, 0, 0), gateway.options.drop(1).map { it["isdrag"] })
        assertEquals(listOf(58_000L, 58_000L), gateway.sleeps.take(2))
        assertEquals(116.0, store.loadActionState("xuexitong", "stu", "video1", "xuexitong-video")!!.progress)
    }

    @Test
    fun `xuexitong short video waits real duration before end heartbeat`() = runTest {
        val session = SessionData("xuexitong", "stu")
        val task = TaskItem("short-video", name = "short", type = "video", platform = "xuexitong", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("classId", "class1")
            addProperty("cpi", "cpi1")
            addProperty("knowledgeId", "k1")
        })
        gateway.results.add(RunTaskResult("xuexitong", "short-video", "prepared", raw = JsonObject().apply {
            addProperty("jobId", "job-1")
            addProperty("dtoken", "token-1")
            addProperty("duration", 1)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "short-video", "progress", raw = JsonObject().apply {
            addProperty("duration", 1)
            addProperty("playingTime", 0)
            addProperty("isPassed", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "short-video", "done", raw = JsonObject().apply {
            addProperty("duration", 1)
            addProperty("playingTime", 1)
            addProperty("isPassed", true)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 10))

        assertEquals("done", result.status)
        assertEquals(listOf(0, 1), gateway.options.drop(1).map { it["playingTime"] })
        assertEquals(listOf(3, 0), gateway.options.drop(1).map { it["isdrag"] })
        assertEquals(1_000L, gateway.sleeps.first())
    }

    @Test
    fun `yinghua keepalive surfaces expiry without starting captcha login`() = runTest {
        store.saveCredential("yinghua", AccountInput("stu", "secret", "https://school"))
        gateway.results.add(RunTaskResult("yinghua", "keepAlive", "done", raw = JsonObject().apply {
            addProperty("alive", false)
            addProperty("expired", true)
        }))
        val task = TaskItem("keepAlive", name = "keepAlive", type = "keepAlive", platform = "yinghua")

        val result = taskScheduler.runTask(SessionData("yinghua", "stu"), task)

        assertEquals(true, result.raw.get("expired").asBoolean)
        assertEquals(0, gateway.loginCalls)
        assertEquals("keepAlive", gateway.options.single()["action"])
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
    fun `yinghua red mode submits original viewed duration with study id zero before delay`() = runTest {
        val session = SessionData("yinghua", "stu")
        val task = TaskItem("red-video", name = "red video", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("videoDuration", 120)
            addProperty("viewedDuration", 0)
            addProperty("errorMessage", "检测到可能使用并行播放刷课")
        })
        gateway.results.add(RunTaskResult("yinghua", "red-video", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "red-study")
            addProperty("studyTime", 0)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(videoModel = 3))

        assertEquals("submitted", result.status)
        assertEquals(1, gateway.options.size)
        assertEquals(null, gateway.options.single()["action"])
        assertEquals(0, gateway.options.single()["studyTime"])
        assertEquals("0", gateway.tasks.single().raw.get("studyId").asString)
        assertEquals(listOf(0), gateway.sleepCountsAtCalls)
        assertEquals(listOf(8_000L), gateway.sleeps)
    }

    @Test
    fun `yinghua video is not truncated by generic max tick limit`() = runTest {
        val session = SessionData("yinghua", "stu")
        val task = TaskItem("long-video", name = "long video", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("videoDuration", 1_210)
            addProperty("viewedDuration", 1_200)
        })
        gateway.results.add(RunTaskResult("yinghua", "long-video", "started", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("videoDuration", 1_210)
        }))
        gateway.results.add(RunTaskResult("yinghua", "long-video", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("studyTime", 1_205)
            addProperty("videoDuration", 1_210)
        }))
        gateway.results.add(RunTaskResult("yinghua", "long-video", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "study-2")
            addProperty("studyTime", 1_210)
            addProperty("videoDuration", 1_210)
        }))

        val result = taskScheduler.runTask(session, task, PlatformTaskRunOptions(maxTicksPerTask = 1, videoModel = 2))

        assertEquals("submitted", result.status)
        assertEquals(listOf(1_205, 1_210), gateway.options.drop(1).map { it["studyTime"] })
        assertEquals(1_210.0, store.loadActionState("yinghua", "stu", "long-video", "yinghua-progress")!!.progress)
    }

    @Test
    fun `xuexitong document scheduler drives prepare and submit`() = runTest {
        val session = SessionData("xuexitong", "stu")
        val task = TaskItem("doc1", name = "doc", type = "document", platform = "xuexitong")
        gateway.results.add(RunTaskResult("xuexitong", "doc1", "prepared", raw = JsonObject().apply {
            addProperty("jobId", "job-doc")
            addProperty("jtoken", "token")
            addProperty("realSubmit", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "doc1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-doc")
        }))

        val result = taskScheduler.runTask(session, task)

        assertEquals("submitted", result.status)
        assertEquals(listOf("documentPrepare", "document"), gateway.options.map { it["action"] })
        assertEquals(true, gateway.options[1]["realSubmit"])
        assertEquals(100.0, store.loadActionState("xuexitong", "stu", "doc1", "xuexitong-document")!!.progress)
    }

    @Test
    fun `xuexitong hyperlink scheduler overrides prepare dry-run flag`() = runTest {
        val session = SessionData("xuexitong", "stu")
        val task = TaskItem("link1", name = "link", type = "hyperlink", platform = "xuexitong")
        gateway.results.add(RunTaskResult("xuexitong", "link1", "prepared", raw = JsonObject().apply {
            addProperty("jobId", "job-link")
            addProperty("jtoken", "token")
            addProperty("realSubmit", false)
        }))
        gateway.results.add(RunTaskResult("xuexitong", "link1", "submitted", raw = JsonObject().apply {
            addProperty("jobId", "job-link")
        }))

        val result = taskScheduler.runTask(session, task)

        assertEquals("submitted", result.status)
        assertEquals(listOf("hyperlinkPrepare", "hyperlink"), gateway.options.map { it["action"] })
        assertEquals(true, gateway.options[1]["realSubmit"])
        assertEquals(100.0, store.loadActionState("xuexitong", "stu", "link1", "xuexitong-hyperlink")!!.progress)
    }

    private class FakeGateway : CoreGateway {
        val results = ArrayDeque<RunTaskResult>()
        val options = mutableListOf<Map<String, Any>>()
        val tasks = mutableListOf<TaskItem>()
        val sleeps = mutableListOf<Long>()
        val sleepCountsAtCalls = mutableListOf<Int>()
        var loginCalls = 0
        var failAction: String? = null

        override suspend fun init(baseDir: String) = InitResult(baseDir, "fake")
        override suspend fun healthCheck() = HealthInfo("fake", "go0", true, true)
        override suspend fun getConfigSchema(): List<ConfigField> = emptyList()
        override suspend fun setConfig(config: MobileConfig) {}
        override suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) {}
        override suspend fun getConfig() = MobileConfig()
        override suspend fun startLogin(platform: Platform, account: AccountInput): LoginResult {
            loginCalls += 1
            return LoginResult.Done(SessionData(platform.id, account.account))
        }
        override suspend fun continueLogin(taskId: String, result: OcrResult): LoginResult =
            LoginResult.Done(SessionData("p", "a"))
        override suspend fun cancelLogin(taskId: String) {}
        override suspend fun getCourses(session: SessionData): List<CourseItem> = emptyList()
        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = emptyList()
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            this.options.add(options)
            tasks.add(task)
            sleepCountsAtCalls.add(sleeps.size)
            if (failAction != null && options["action"] == failAction) error("failed ${options["action"]}")
            return results.removeFirst()
        }
        override suspend fun getLogs(cursor: String) = LogResult("", "", false, emptyList())
        override suspend fun clearLogs() {}
        override suspend fun setLogLevel(level: String) {}
    }
}
