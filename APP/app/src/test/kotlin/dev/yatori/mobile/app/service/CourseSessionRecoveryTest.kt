package dev.yatori.mobile.app.service

import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.SessionData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseSessionRecoveryTest {
    @Test
    fun `recognizes explicit Yinghua expiry through wrapped causes`() {
        val error = IllegalStateException(
            "course sync failed",
            IllegalArgumentException("yinghua: course list failed: 账号登录超时，请重新登录"),
        )

        assertTrue(isSessionExpiredError(error))
        assertFalse(isSessionExpiredError(IllegalStateException("登录验证码识别失败")))
        assertFalse(isSessionExpiredError(IllegalStateException("course list parse error")))
    }

    @Test
    fun `course refresh relogs in and retries once after expiry`() = runTest {
        val oldSession = SessionData("yinghua", "stu", token = "old")
        val newSession = SessionData("yinghua", "stu", token = "new")
        val seenTokens = mutableListOf<String>()
        var reloginCalls = 0

        val courses = loadCoursesWithSessionRecovery(
            initialSession = oldSession,
            fetchCourses = { session ->
                seenTokens.add(session.token)
                if (session.token == "old") error("账号登录超时，请重新登录")
                listOf(CourseItem("course-1", name = "课程一", platform = "yinghua"))
            },
            relogin = {
                reloginCalls += 1
                newSession
            },
        )

        assertEquals(listOf("old", "new"), seenTokens)
        assertEquals(1, reloginCalls)
        assertEquals(listOf("course-1"), courses.map { it.id })
    }

    @Test
    fun `course refresh does not relogin for unrelated failures`() = runTest {
        var reloginCalls = 0

        val error = assertFailsWith<IllegalStateException> {
            loadCoursesWithSessionRecovery(
                initialSession = SessionData("yinghua", "stu"),
                fetchCourses = { error("network unavailable") },
                relogin = {
                    reloginCalls += 1
                    SessionData("yinghua", "stu", token = "new")
                },
            )
        }

        assertEquals("network unavailable", error.message)
        assertEquals(0, reloginCalls)
    }

    @Test
    fun `course refresh reports relogin failure instead of retrying old session`() = runTest {
        var fetchCalls = 0

        val error = assertFailsWith<IllegalStateException> {
            loadCoursesWithSessionRecovery(
                initialSession = SessionData("yinghua", "stu"),
                fetchCourses = {
                    fetchCalls += 1
                    error("账号登录超时，请重新登录")
                },
                relogin = { null },
            )
        }

        assertTrue(error.message.orEmpty().contains("自动重新登录失败"))
        assertEquals(1, fetchCalls)
    }
    @Test
    fun `course refresh preserves coroutine cancellation`() = runTest {
        var reloginCalls = 0

        assertFailsWith<CancellationException> {
            loadCoursesWithSessionRecovery(
                initialSession = SessionData("yinghua", "stu"),
                fetchCourses = { throw CancellationException("cancelled") },
                relogin = {
                    reloginCalls += 1
                    SessionData("yinghua", "stu", token = "new")
                },
            )
        }

        assertEquals(0, reloginCalls)
    }

}
