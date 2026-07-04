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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformActionSchedulerTest {
    private lateinit var tmp: File
    private lateinit var store: MobilecoreStore
    private lateinit var gateway: FakeGateway
    private lateinit var repo: YatoriCoreRepository
    private lateinit var scheduler: PlatformActionScheduler

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("platform-action-scheduler-test").toFile()
        store = MobilecoreStore(tmp)
        gateway = FakeGateway()
        repo = YatoriCoreRepository(gateway, store)
        scheduler = PlatformActionScheduler(repo, now = { 1000L })
    }

    @After
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun `prepareTtcdwProgress persists raw action state`() = runTest {
        gateway.runTaskResult = RunTaskResult(
            "ttcdw",
            "v1",
            "prepared",
            raw = JsonObject().apply {
                addProperty("intervalSeconds", 30)
                addProperty("tickerUrl", "https://ttcdw/progress")
                addProperty("playProgress", 30)
            },
        )
        val session = SessionData("ttcdw", "stu")
        val task = TaskItem("v1", type = "video", platform = "ttcdw", raw = JsonObject().apply {
            addProperty("videoId", "v1")
        })

        val state = scheduler.prepareTtcdwProgress(session, task)

        assertEquals("ttcdw-progress", state.scope)
        assertEquals(30, state.intervalSeconds)
        assertEquals("https://ttcdw/progress", state.raw.get("tickerUrl").asString)
        assertNotNull(store.loadActionState("ttcdw", "stu", "v1", "ttcdw-progress"))
        assertEquals("prepare", gateway.lastOptions["action"])
    }

    @Test
    fun `tickTtcdwProgress replays saved raw and progress without forcing real submit`() = runTest {
        val session = SessionData("ttcdw", "stu")
        val task = TaskItem("v1", type = "video", platform = "ttcdw")
        gateway.runTaskResult = RunTaskResult("ttcdw", "v1", "prepared", raw = JsonObject().apply {
            addProperty("intervalSeconds", 30)
            addProperty("tickerUrl", "https://ttcdw/progress")
        })
        scheduler.prepareTtcdwProgress(session, task)

        gateway.runTaskResult = RunTaskResult("ttcdw", "v1", "dry_run", raw = JsonObject().apply {
            addProperty("intervalSeconds", 30)
            addProperty("tickerUrl", "https://ttcdw/progress")
            addProperty("playProgress", 60)
        })
        val result = scheduler.tickTtcdwProgress(session, "v1", TtcdwTickInput(progress = 60.0))

        assertEquals("dry_run", result.status)
        assertEquals("tick", gateway.lastOptions["action"])
        assertEquals("https://ttcdw/progress", gateway.lastOptions["tickerUrl"])
        assertEquals(60.0, gateway.lastOptions["progress"])
        assertEquals(false, gateway.lastOptions["realSubmit"])
        assertEquals(60.0, store.loadActionState("ttcdw", "stu", "v1", "ttcdw-progress")!!.progress)
    }

    @Test
    fun `ttcdw DES ticker uses des actions and replays ticker data`() = runTest {
        val session = SessionData("ttcdw", "stu")
        val task = TaskItem("v1", type = "video", platform = "ttcdw", raw = JsonObject().apply {
            addProperty("companyCode", "co")
            addProperty("userId", "u1")
            addProperty("tickerCourseId", "c1")
        })
        gateway.runTaskResult = RunTaskResult("ttcdw", "v1", "prepared", raw = JsonObject().apply {
            addProperty("intervalSeconds", 30)
            addProperty("tickerUrl", "https://ttcdw/ticker")
            addProperty("serverDataName", "tickerData")
            addProperty("tickerData", "encrypted")
            addProperty("playedRanges", "0-30")
        })

        scheduler.prepareTtcdwTicker(session, task, TtcdwTickerInput(playedEnd = 30.0, tickerTime = 10L))
        assertEquals("desPrepare", gateway.lastOptions["action"])
        assertEquals(30.0, gateway.lastOptions["playedEnd"])

        gateway.runTaskResult = RunTaskResult("ttcdw", "v1", "submitted", raw = JsonObject().apply {
            addProperty("tickerUrl", "https://ttcdw/ticker")
            addProperty("serverDataName", "tickerData")
            addProperty("tickerData", "encrypted2")
            addProperty("playedRanges", "30-60")
            addProperty("realSubmit", true)
        })
        scheduler.tickTtcdwTicker(session, "v1", TtcdwTickerInput(playedStart = 30.0, playedEnd = 60.0, realSubmit = true))

        assertEquals("desTick", gateway.lastOptions["action"])
        assertEquals("https://ttcdw/ticker", gateway.lastOptions["tickerUrl"])
        assertEquals("encrypted", gateway.lastOptions["tickerData"])
        assertEquals(true, gateway.lastOptions["realSubmit"])
        assertEquals(60.0, store.loadActionState("ttcdw", "stu", "v1", "ttcdw-ticker")!!.progress)
    }

    @Test
    fun `haiqikeji start and tick reuse session id`() = runTest {
        val session = SessionData("haiqikeji", "stu")
        val task = TaskItem("node1", type = "video", platform = "haiqikeji", raw = JsonObject().apply {
            addProperty("courseId", "course1")
        })
        gateway.runTaskResult = RunTaskResult("haiqikeji", "node1", "started", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
        })

        val state = scheduler.startHaiqikejiProgress(session, task)

        assertEquals("start", gateway.lastOptions["action"])
        assertEquals(30, state.intervalSeconds)
        assertEquals("sid-1", state.task.raw.get("sessionId").asString)

        gateway.runTaskResult = RunTaskResult("haiqikeji", "node1", "submitted", raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
            addProperty("progress", 50)
        })
        scheduler.tickHaiqikejiProgress(session, "node1", PercentTickInput(progress = 50))

        assertEquals("submit", gateway.lastOptions["action"])
        assertEquals(50, gateway.lastOptions["progress"])
        assertEquals("sid-1", gateway.lastTask.raw.get("sessionId").asString)
    }

    @Test
    fun `welearn start keep and finalize use expected actions`() = runTest {
        val session = SessionData("welearn", "stu")
        val task = TaskItem("sco1", type = "video", platform = "welearn", raw = JsonObject().apply {
            addProperty("cid", "course1")
            addProperty("uid", "u1")
            addProperty("classId", "class1")
            addProperty("crate", "100")
        })
        gateway.runTaskResult = RunTaskResult("welearn", "sco1", "started")

        scheduler.startWelearnProgress(session, task)
        assertEquals("start", gateway.lastOptions["action"])

        gateway.runTaskResult = RunTaskResult("welearn", "sco1", "submitted", raw = JsonObject().apply {
            addProperty("sessionTime", 60)
            addProperty("totalTime", 120)
        })
        scheduler.tickWelearnProgress(session, "sco1", WelearnTickInput(sessionTime = 60, totalTime = 120))
        assertEquals("keep", gateway.lastOptions["action"])
        assertEquals(60, gateway.lastOptions["sessionTime"])
        assertEquals(50.0, store.loadActionState("welearn", "stu", "sco1", "welearn-progress")!!.progress)

        scheduler.tickWelearnProgress(session, "sco1", WelearnTickInput(sessionTime = 120, totalTime = 120, finish = true))
        assertEquals("finalize", gateway.lastOptions["action"])
        assertEquals("completed", gateway.lastOptions["cstatus"])
    }

    @Test
    fun `enaea tick stores progress and interval`() = runTest {
        val session = SessionData("enaea", "stu")
        val task = TaskItem("video1", type = "video", platform = "enaea", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("circleId", "circle1")
        })
        gateway.runTaskResult = RunTaskResult("enaea", "video1", "submitted", raw = JsonObject().apply {
            addProperty("progress", 70)
        })

        scheduler.tickEnaeaProgress(session, task, EnaeaTickInput(studyTime = 12345L, fast = true))

        assertEquals(true, gateway.lastOptions["fast"])
        assertEquals(12345L, gateway.lastOptions["studyTime"])
        val state = store.loadActionState("enaea", "stu", "video1", "enaea-progress")!!
        assertEquals(25, state.intervalSeconds)
        assertEquals(70.0, state.progress)
    }

    @Test
    fun `cqie start and tick reuse study id`() = runTest {
        val session = SessionData("cqie", "stu")
        val task = TaskItem("video1", type = "video", platform = "cqie", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("studentCourseId", "studentCourse1")
            addProperty("unitId", "unit1")
            addProperty("timeLength", 90)
        })
        gateway.runTaskResult = RunTaskResult("cqie", "video1", "started", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("maxCurrentPos", 0)
        })

        scheduler.startCqieProgress(session, task)
        assertEquals("start", gateway.lastOptions["action"])

        gateway.runTaskResult = RunTaskResult("cqie", "video1", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("maxPos", 6)
        })
        scheduler.tickCqieProgress(session, "video1", CqieTickInput(startPos = 3, stopPos = 6))

        assertEquals("submit", gateway.lastOptions["action"])
        assertEquals(3, gateway.lastOptions["startPos"])
        assertEquals("study-1", gateway.lastTask.raw.get("studyId").asString)
    }

    @Test
    fun `qingshuxuetang start and tick reuse server record id`() = runTest {
        val session = SessionData("qingshuxuetang", "stu")
        val task = TaskItem("content1", type = "video", platform = "qingshuxuetang", raw = JsonObject().apply {
            addProperty("classId", "class1")
            addProperty("courseId", "course1")
            addProperty("periodId", "period1")
            addProperty("schoolId", "school1")
        })
        gateway.runTaskResult = RunTaskResult("qingshuxuetang", "content1", "started", raw = JsonObject().apply {
            addProperty("serverRecordId", "record-1")
        })

        scheduler.startQingshuxuetangProgress(session, task)
        assertEquals("start", gateway.lastOptions["action"])

        gateway.runTaskResult = RunTaskResult("qingshuxuetang", "content1", "submitted", raw = JsonObject().apply {
            addProperty("serverRecordId", "record-1")
            addProperty("position", 60)
        })
        scheduler.tickQingshuxuetangProgress(session, "content1", QingshuxuetangTickInput(position = 60))

        assertEquals("continue", gateway.lastOptions["action"])
        assertEquals(60, gateway.lastOptions["position"])
        assertEquals("record-1", gateway.lastTask.raw.get("serverRecordId").asString)
    }

    @Test
    fun `tickYinghuaKeepAlive triggers relogin on expired when requested`() = runTest {
        store.saveCredential("yinghua", AccountInput("stu", "secret", "https://school"))
        gateway.runTaskResult = RunTaskResult("yinghua", "keepAlive", "done", raw = JsonObject().apply {
            addProperty("alive", false)
            addProperty("expired", true)
        })
        gateway.loginResult = LoginResult.Done(SessionData("yinghua", "stu"))

        val result = scheduler.tickYinghuaKeepAlive(SessionData("yinghua", "stu"), reloginOnExpired = true)

        assertTrue(result.relogin)
        assertIs<LoginResult.Done>(result.loginResult)
        assertEquals("keepAlive", gateway.lastOptions["action"])
        assertEquals(Platform.YINGHUA, gateway.lastLoginPlatform)
        assertEquals("secret", gateway.lastLoginAccount?.password)
    }

    @Test
    fun `yinghua start and tick reuse study id`() = runTest {
        val session = SessionData("yinghua", "stu")
        val task = TaskItem("video1", type = "video", platform = "yinghua", raw = JsonObject().apply {
            addProperty("courseId", "course1")
            addProperty("viewedDuration", 5)
        })
        gateway.runTaskResult = RunTaskResult("yinghua", "video1", "started", raw = JsonObject().apply {
            addProperty("studyId", "study-1")
            addProperty("videoDuration", 15)
        })

        val state = scheduler.startYinghuaProgress(session, task)

        assertEquals("state", gateway.lastOptions["action"])
        assertEquals(5, state.intervalSeconds)
        assertEquals("study-1", state.task.raw.get("studyId").asString)

        gateway.runTaskResult = RunTaskResult("yinghua", "video1", "submitted", raw = JsonObject().apply {
            addProperty("studyId", "study-2")
            addProperty("studyTime", 10)
        })
        scheduler.tickYinghuaProgress(session, "video1", YinghuaTickInput(studyTime = 10))

        assertEquals(10, gateway.lastOptions["studyTime"])
        assertEquals("study-1", gateway.lastTask.raw.get("studyId").asString)
        assertEquals("study-2", store.loadActionState("yinghua", "stu", "video1", "yinghua-progress")!!.task.raw.get("studyId").asString)
    }

    private class FakeGateway : CoreGateway {
        var runTaskResult: RunTaskResult = RunTaskResult("p", "t", "done")
        var loginResult: LoginResult = LoginResult.Done(SessionData("p", "a"))
        var lastOptions: Map<String, Any> = emptyMap()
        var lastTask: TaskItem = TaskItem("")
        var lastLoginPlatform: Platform? = null
        var lastLoginAccount: AccountInput? = null

        override suspend fun init(baseDir: String) = InitResult(baseDir, "fake")
        override suspend fun healthCheck() = HealthInfo("fake", "go0", true, true)
        override suspend fun getConfigSchema(): List<ConfigField> = emptyList()
        override suspend fun setConfig(config: MobileConfig) {}
        override suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) {}
        override suspend fun getConfig() = MobileConfig()

        override suspend fun startLogin(platform: Platform, account: AccountInput): LoginResult {
            lastLoginPlatform = platform
            lastLoginAccount = account
            return loginResult
        }
        override suspend fun continueLogin(taskId: String, result: OcrResult) = loginResult
        override suspend fun cancelLogin(taskId: String) {}

        override suspend fun getCourses(session: SessionData): List<CourseItem> = emptyList()
        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = emptyList()
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            lastOptions = options
            lastTask = task
            return runTaskResult
        }

        override suspend fun getLogs(cursor: String) = LogResult("", "", false, emptyList())
        override suspend fun clearLogs() {}
        override suspend fun setLogLevel(level: String) {}
    }
}
