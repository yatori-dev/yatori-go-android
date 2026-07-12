package dev.yatori.mobile.runtime

import com.google.gson.JsonObject
import dev.yatori.mobile.api.CoreException
import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YatoriCoreRepositoryTest {

    private lateinit var tmp: File
    private lateinit var store: MobilecoreStore
    private lateinit var gateway: FakeGateway
    private lateinit var repo: YatoriCoreRepository

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("repo-test").toFile()
        store = MobilecoreStore(tmp)
        gateway = FakeGateway()
        repo = YatoriCoreRepository(gateway, store)
    }

    @After
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun `applyConfig saves config after gateway succeeds`() = runTest {
        val config = MobileConfig()
        repo.applyConfig(config)
        assertNotNull(store.loadConfig())
    }

    @Test
    fun `applyConfig does not save when gateway throws`() = runTest {
        gateway.throwOnSetConfig = true
        assertFailsWith<CoreException> { repo.applyConfig(MobileConfig()) }
        assertNull(store.loadConfig())
    }

    @Test
    fun `setXuexitongFontTables passes through to gateway`() = runTest {
        repo.setXuexitongFontTables("""{"hash":"uni4E00"}""", """{"uni4E00":19968}""")
        assertEquals("""{"hash":"uni4E00"}""", gateway.lastGlyfJson)
        assertEquals("""{"uni4E00":19968}""", gateway.lastCmapJson)
    }

    @Test
    fun `startLoginAndPersist saves session on Done`() = runTest {
        gateway.loginResult = LoginResult.Done(SessionData("p", "user"))
        val result = repo.startLoginAndPersist(Platform.YINGHUA, AccountInput("user", "pass"))
        assertIs<LoginResult.Done>(result)
        assertNotNull(store.loadSession("p", "user"))
        assertEquals("pass", store.loadCredential("p", "user")?.password)
    }

    @Test
    fun `credential key follows saved session account`() = runTest {
        gateway.loginResult = LoginResult.Done(SessionData("xuexitong", "normalized"))
        repo.startLoginAndPersist(Platform.XUEXITONG, AccountInput("typed", "secret"))

        assertNull(store.loadCredential("xuexitong", "typed"))
        assertEquals("secret", store.loadCredential("xuexitong", "normalized")?.password)
    }

    @Test
    fun `startLoginAndPersist does not save session or credential on Challenge`() = runTest {
        gateway.loginResult = LoginResult.Challenge("tid", OcrChallenge("tid", "p", "image_ocr", "base64"))
        repo.startLoginAndPersist(Platform.YINGHUA, AccountInput("user", "pass"))
        assertEquals(0, store.listSessions().size)
        assertEquals(0, store.listCredentials().size)
    }

    @Test
    fun `continueLoginAndPersist saves session on Done`() = runTest {
        gateway.loginResult = LoginResult.Done(SessionData("p", "user2"))
        val result = repo.continueLoginAndPersist("tid", OcrResult("tid", text = "1234"))
        assertIs<LoginResult.Done>(result)
        assertNotNull(store.loadSession("p", "user2"))
    }

    @Test
    fun `continueLoginAndPersist saves pending credential after Challenge then Done`() = runTest {
        gateway.loginResult = LoginResult.Challenge("tid", OcrChallenge("tid", "p", "image_ocr", "base64"))
        repo.startLoginAndPersist(Platform.XUEXITONG, AccountInput("stu", "secret", extra = JsonObject().apply {
            addProperty("ua", "pc")
        }))

        gateway.loginResult = LoginResult.Done(SessionData("xuexitong", "stu"))
        val result = repo.continueLoginAndPersist("tid", OcrResult("tid", text = "1234"))

        assertIs<LoginResult.Done>(result)
        val credential = store.loadCredential("xuexitong", "stu")
        assertNotNull(credential)
        assertEquals("secret", credential.password)
        assertEquals("pc", credential.extra.get("ua").asString)
    }

    @Test
    fun `cancelLogin drops pending credential`() = runTest {
        gateway.loginResult = LoginResult.Challenge("tid", OcrChallenge("tid", "p", "image_ocr", "base64"))
        repo.startLoginAndPersist(Platform.YINGHUA, AccountInput("user", "pass"))
        repo.cancelLogin("tid")

        gateway.loginResult = LoginResult.Done(SessionData("yinghua", "user"))
        repo.continueLoginAndPersist("tid", OcrResult("tid", text = "1234"))

        assertNotNull(store.loadSession("yinghua", "user"))
        assertNull(store.loadCredential("yinghua", "user"))
    }

    @Test
    fun `drainLogs uses stored cursor and saves nextCursor`() = runTest {
        store.saveLogCursor("old-cursor")
        gateway.logsResult = LogResult("new-cursor", "old-cursor", false, emptyList())
        repo.drainLogs()
        assertEquals("new-cursor", store.loadLogCursor())
        assertEquals("old-cursor", gateway.lastCursorUsed)
    }

    @Test
    fun `gateway CoreException propagates to caller`() = runTest {
        gateway.throwOnLogin = true
        assertFailsWith<CoreException> {
            repo.startLoginAndPersist(Platform.YINGHUA, AccountInput("u", "p"))
        }
    }

    @Test
    fun `getCourses refreshes local cache and loadCachedCourses returns it`() = runTest {
        val raw = JsonObject().apply { addProperty("courseId", "c1") }
        gateway.coursesResult = listOf(CourseItem("c1", name = "课程一", raw = raw, platform = "yinghua"))
        val session = SessionData("yinghua", "stu")

        val fetched = repo.getCourses(session)
        assertEquals(1, fetched.size)

        val cached = repo.loadCachedCourses("yinghua", "stu")
        assertEquals(1, cached.size)
        assertEquals("课程一", cached[0].name)
        // raw opaque field is preserved through the cache round-trip
        assertEquals("c1", cached[0].raw.get("courseId").asString)
    }

    @Test
    fun `loadCachedCourses empty before any sync`() {
        assertTrue(repo.loadCachedCourses("yinghua", "never").isEmpty())
    }

    @Test
    fun `setLogLevel passes through to gateway`() = runTest {
        repo.setLogLevel("debug")
        assertEquals("debug", gateway.lastLogLevel)
    }

    @Test
    fun `clearLogs clears gateway buffer and local cursor`() = runTest {
        store.saveLogCursor("c")
        repo.clearLogs()
        assertTrue(gateway.clearLogsCalled)
        assertEquals("", store.loadLogCursor())
    }

    // ── fake ─────────────────────────────────────────────────────────────────

    private class FakeGateway : CoreGateway {
        var throwOnSetConfig = false
        var throwOnLogin = false
        var loginResult: LoginResult = LoginResult.Done(SessionData("p", "user"))
        var logsResult: LogResult = LogResult("c2", "c0", false, emptyList())
        var coursesResult: List<CourseItem> = emptyList()
        var lastCursorUsed: String = ""
        var lastLogLevel: String = ""
        var lastGlyfJson: String = ""
        var lastCmapJson: String = ""
        var clearLogsCalled = false

        override suspend fun init(baseDir: String) = InitResult(baseDir, "fake")
        override suspend fun healthCheck() = HealthInfo("fake", "go0", true, true)

        override suspend fun getConfigSchema(): List<ConfigField> = emptyList()
        override suspend fun setConfig(config: MobileConfig) {
            if (throwOnSetConfig) throw CoreException("setConfig failed")
        }
        override suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) {
            lastGlyfJson = glyfJson
            lastCmapJson = cmapJson
        }
        override suspend fun getConfig() = MobileConfig()

        override suspend fun startLogin(platform: Platform, account: AccountInput): LoginResult {
            if (throwOnLogin) throw CoreException("login failed")
            return loginResult
        }
        override suspend fun continueLogin(taskId: String, result: OcrResult) = loginResult
        override suspend fun cancelLogin(taskId: String) {}

        override suspend fun getCourses(session: SessionData): List<CourseItem> = coursesResult
        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = emptyList()
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult =
            RunTaskResult(session.platform, task.id, "dry_run")

        override suspend fun getLogs(cursor: String): LogResult {
            lastCursorUsed = cursor
            return logsResult
        }
        override suspend fun clearLogs() { clearLogsCalled = true }
        override suspend fun setLogLevel(level: String) { lastLogLevel = level }
    }
}
