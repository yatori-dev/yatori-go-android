package dev.yatori.mobile.app.ui.logs

import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.runtime.StoredSession
import org.junit.Test
import kotlin.test.assertEquals

class LogDisplayNameTest {
    @Test
    fun `system log uses system go core label`() {
        val entry = log(platform = "")

        assertEquals(SYSTEM_GO_CORE_LABEL, logDisplayName(entry, emptyList(), null))
    }

    @Test
    fun `single account platform log shows platform and account`() {
        val sessions = listOf(session("xuexitong", "student2026@example.com"))
        val entry = log(platform = "xuexitong")

        assertEquals("学习通（student2026@example.com）", logDisplayName(entry, sessions, MobileConfig()))
    }

    @Test
    fun `remarked account log shows remark and masked account`() {
        val account = "student2026@example.com"
        val sessions = listOf(session("xuexitong", account))
        val cfg = MobileConfig(
            users = listOf(
                JsonObject().apply {
                    addProperty("accountType", "xuexitong")
                    addProperty("account", account)
                    addProperty("remarkName", "高数号")
                },
            ),
        )
        val entry = log(platform = "xuexitong")

        assertEquals("高数号（stu....26@example.com）", logDisplayName(entry, sessions, cfg))
    }

    @Test
    fun `ambiguous multi-account platform log falls back to platform name`() {
        val sessions = listOf(
            session("xuexitong", "a@example.com"),
            session("xuexitong", "b@example.com"),
        )
        val entry = log(platform = "xuexitong")

        assertEquals("学习通", logDisplayName(entry, sessions, MobileConfig()))
    }

    @Test
    fun `multi-account log can match account from message`() {
        val sessions = listOf(
            session("xuexitong", "a@example.com"),
            session("xuexitong", "b@example.com"),
        )
        val entry = log(platform = "xuexitong", message = "StartLogin succeeded for b@example.com")

        assertEquals("学习通（b@example.com）", logDisplayName(entry, sessions, MobileConfig()))
    }

    @Test
    fun `multi-account log prefers explicit account field`() {
        val sessions = listOf(
            session("xuexitong", "a@example.com"),
            session("xuexitong", "b@example.com"),
        )
        val entry = log(platform = "xuexitong", message = "course sync failed", account = "b@example.com")

        assertEquals("学习通（b@example.com）", logDisplayName(entry, sessions, MobileConfig()))
    }

    private fun log(platform: String, message: String = "GetCourses succeeded", account: String = "") =
        LogEntry(id = 1, time = "2026-06-27T00:00:00Z", level = "info", source = "mobilecore", platform = platform, message = message, account = account)

    private fun session(platform: String, account: String) =
        StoredSession(platform, account, SessionData(platform = platform, account = account), updatedAt = 0L)
}
