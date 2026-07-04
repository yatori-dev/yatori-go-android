package dev.yatori.mobile.runtime

import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobilecoreStoreTest {

    private lateinit var tmp: File
    private lateinit var store: MobilecoreStore

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("mobilecore-store-test").toFile()
        store = MobilecoreStore(tmp)
    }

    @After
    fun tearDown() { tmp.deleteRecursively() }

    @Test
    fun `save and load config`() {
        val config = MobileConfig()
        store.saveConfig(config)
        assertNotNull(store.loadConfig())
    }

    @Test
    fun `clear config`() {
        store.saveConfig(MobileConfig())
        store.clearConfig()
        assertNull(store.loadConfig())
    }

    @Test
    fun `save and load session preserves extra`() {
        val extra = JsonObject().apply { addProperty("key", "val") }
        val session = SessionData("yinghua", "user1", token = "tok", extra = extra)
        store.saveSession(session)
        val loaded = store.loadSession("yinghua", "user1")
        assertNotNull(loaded)
        assertEquals("val", loaded.extra.get("key").asString)
    }

    @Test
    fun `listSessions returns all saved sessions`() {
        store.saveSession(SessionData("p1", "a1"))
        store.saveSession(SessionData("p2", "a2"))
        assertEquals(2, store.listSessions().size)
    }

    @Test
    fun `deleteSession removes the session`() {
        store.saveSession(SessionData("p1", "a1"))
        assertTrue(store.deleteSession("p1", "a1"))
        assertNull(store.loadSession("p1", "a1"))
    }

    @Test
    fun `save and load log cursor`() {
        store.saveLogCursor("cursor-42")
        assertEquals("cursor-42", store.loadLogCursor())
    }

    @Test
    fun `clearAll removes config, sessions, and cursor`() {
        store.saveConfig(MobileConfig())
        store.saveSession(SessionData("p", "a"))
        store.saveCredential("p", AccountInput("a", "secret"))
        store.saveLogCursor("c")
        store.clearAll()
        assertNull(store.loadConfig())
        assertEquals(0, store.listSessions().size)
        assertEquals(0, store.listCredentials().size)
        assertEquals("", store.loadLogCursor())
    }

    @Test
    fun `save and load credential preserves url and extra`() {
        val extra = JsonObject().apply { addProperty("ua", "pc") }
        store.saveCredential("xuexitong", AccountInput("stu", "secret", "https://i.chaoxing.com", extra))
        val loaded = store.loadCredential("xuexitong", "stu")
        assertNotNull(loaded)
        assertEquals("secret", loaded.password)
        assertEquals("https://i.chaoxing.com", loaded.url)
        assertEquals("pc", loaded.extra.get("ua").asString)
    }

    @Test
    fun `list and delete credentials`() {
        store.saveCredential("p1", AccountInput("a1", "s1"))
        store.saveCredential("p2", AccountInput("a2", "s2"))
        assertEquals(2, store.listCredentials().size)
        assertTrue(store.deleteCredential("p1", "a1"))
        assertNull(store.loadCredential("p1", "a1"))
        assertEquals(1, store.listCredentials().size)
    }

    @Test
    fun `credential file name does not expose raw account`() {
        store.saveCredential("p", AccountInput("user@example.com", "secret"))
        val credentialsDir = File(tmp, "mobilecore-state/credentials")
        val name = credentialsDir.listFiles()!!.single().name
        assertFalse(name.contains("user"), "filename must not contain 'user'")
        assertFalse(name.contains("example"), "filename must not contain 'example'")
        assertFalse(name.contains("@"), "filename must not contain '@'")
    }

    @Test
    fun `save and load course cache preserves raw`() {
        val raw = JsonObject().apply { addProperty("courseId", "c1") }
        val courses = listOf(CourseItem("c1", name = "课程", raw = raw, platform = "yinghua"))
        store.saveCourseCache("yinghua", "stu", courses)
        val loaded = store.loadCourseCache("yinghua", "stu")
        assertEquals(1, loaded.size)
        assertEquals("课程", loaded[0].name)
        assertEquals("c1", loaded[0].raw.get("courseId").asString)
    }

    @Test
    fun `loadCourseCache empty when none saved`() {
        assertTrue(store.loadCourseCache("yinghua", "none").isEmpty())
        assertFalse(store.hasCourseCache("yinghua", "none"))
    }

    @Test
    fun `empty saved course cache still counts as synced`() {
        store.saveCourseCache("yinghua", "stu", emptyList())

        assertTrue(store.loadCourseCache("yinghua", "stu").isEmpty())
        assertTrue(store.hasCourseCache("yinghua", "stu"))
    }

    @Test
    fun `cachedCourseCount sums across accounts`() {
        store.saveCourseCache("yinghua", "a", listOf(CourseItem("1"), CourseItem("2")))
        store.saveCourseCache("cqie", "b", listOf(CourseItem("3")))
        assertEquals(3, store.cachedCourseCount())
    }

    @Test
    fun `clearAll also removes course cache`() {
        store.saveCourseCache("p", "a", listOf(CourseItem("1")))
        store.clearAll()
        assertEquals(0, store.cachedCourseCount())
    }

    @Test
    fun `save and load action state preserves task and raw`() {
        val raw = JsonObject().apply {
            addProperty("sessionId", "sid-1")
            addProperty("progress", 30)
        }
        val taskRaw = JsonObject().apply { addProperty("courseId", "c1") }
        val state = StoredActionState(
            platform = "haiqikeji",
            account = "stu",
            taskId = "node-1",
            scope = "haiqikeji-progress",
            task = TaskItem("node-1", name = "video", raw = taskRaw),
            status = "started",
            raw = raw,
            intervalSeconds = 30,
            progress = 30.0,
            createdAt = 1L,
            updatedAt = 2L,
        )

        store.saveActionState(state)
        val loaded = store.loadActionState("haiqikeji", "stu", "node-1", "haiqikeji-progress")

        assertNotNull(loaded)
        assertEquals("sid-1", loaded.raw.get("sessionId").asString)
        assertEquals("c1", loaded.task.raw.get("courseId").asString)
        assertEquals(30, loaded.intervalSeconds)
    }

    @Test
    fun `list and delete action states by account`() {
        fun state(platform: String, account: String, taskId: String) = StoredActionState(
            platform = platform,
            account = account,
            taskId = taskId,
            scope = "scope",
            task = TaskItem(taskId),
            status = "started",
            createdAt = 1L,
            updatedAt = 1L,
        )
        store.saveActionState(state("p", "a1", "t1"))
        store.saveActionState(state("p", "a1", "t2"))
        store.saveActionState(state("p", "a2", "t3"))

        assertEquals(3, store.listActionStates().size)
        assertEquals(2, store.deleteActionStates("p", "a1"))

        assertNull(store.loadActionState("p", "a1", "t1", "scope"))
        assertEquals(1, store.listActionStates().size)
        assertNotNull(store.loadActionState("p", "a2", "t3", "scope"))
    }

    @Test
    fun `action state file name does not expose raw account or task id`() {
        store.saveActionState(
            StoredActionState(
                platform = "ttcdw",
                account = "user@example.com",
                taskId = "video/secret-42",
                scope = "ttcdw-progress",
                task = TaskItem("video/secret-42"),
                status = "prepared",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        val actionDir = File(tmp, "mobilecore-state/action-states")
        val name = actionDir.listFiles()!!.single().name
        assertFalse(name.contains("user"), "filename must not contain 'user'")
        assertFalse(name.contains("example"), "filename must not contain 'example'")
        assertFalse(name.contains("secret"), "filename must not contain task id")
        assertFalse(name.contains("/"), "filename must not contain path separators")
    }

    @Test
    fun `clearAll also removes action states`() {
        store.saveActionState(
            StoredActionState(
                platform = "p",
                account = "a",
                taskId = "t",
                scope = "scope",
                task = TaskItem("t"),
                status = "started",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        store.clearAll()
        assertEquals(0, store.listActionStates().size)
    }

    @Test
    fun `session file names do not collide after sanitization`() {
        // "a:b" and "a?b" both sanitise to "a_b" under the old strategy;
        // with SHA-256 on the raw account they must produce distinct files.
        store.saveSession(SessionData("p", "a:b"))
        store.saveSession(SessionData("p", "a?b"))
        assertEquals(2, store.listSessions().size)
        assertNotNull(store.loadSession("p", "a:b"))
        assertNotNull(store.loadSession("p", "a?b"))
    }

    @Test
    fun `session file name does not expose raw account`() {
        store.saveSession(SessionData("p", "user@example.com"))
        val sessionsDir = File(tmp, "mobilecore-state/sessions")
        val name = sessionsDir.listFiles()!!.single().name
        assertFalse(name.contains("user"), "filename must not contain 'user'")
        assertFalse(name.contains("example"), "filename must not contain 'example'")
        assertFalse(name.contains("@"), "filename must not contain '@'")
        assertFalse(name.contains(".com"), "filename must not contain '.com'")
    }

    @Test
    fun `same account same platform overwrites same file`() {
        store.saveSession(SessionData("p", "user", token = "tok1"))
        store.saveSession(SessionData("p", "user", token = "tok2"))
        val sessionsDir = File(tmp, "mobilecore-state/sessions")
        assertEquals(1, sessionsDir.listFiles()!!.size)
        assertEquals("tok2", store.loadSession("p", "user")!!.token)
    }

    @Test
    fun `session file with path traversal chars stays inside sessions dir`() {
        val session = SessionData("../../evil", "../../../etc/passwd")
        store.saveSession(session)
        val sessionsDir = File(tmp, "mobilecore-state/sessions")
        val files = sessionsDir.listFiles() ?: emptyArray()
        assertEquals(1, files.size)
        files.forEach { f ->
            assertTrue(
                f.canonicalPath.startsWith(sessionsDir.canonicalPath),
                "file escaped sessions dir: ${f.canonicalPath}"
            )
        }
        // round-trip works too
        assertNotNull(store.loadSession("../../evil", "../../../etc/passwd"))
    }
}
