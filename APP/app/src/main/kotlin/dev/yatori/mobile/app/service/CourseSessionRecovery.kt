package dev.yatori.mobile.app.service

import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.SessionData
import kotlinx.coroutines.CancellationException

private val sessionExpiredMarkers = listOf(
    "账号登录超时",
    "登录已超时",
    "请重新登录",
    "会话已过期",
    "会话过期",
    "登录状态已失效",
    "登录状态可能已失效",
    "登录失效",
    "未登录",
    "session expired",
    "login expired",
    "login timeout",
    "login required",
    "not logged in",
    "unauthorized",
    "invalid token",
    "token expired",
    "token无效",
)

/** Matches explicit authentication-expiry signals without treating every login-related error as expiry. */
internal fun isSessionExpiredError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .mapNotNull { it.message?.lowercase() }
        .any { message -> sessionExpiredMarkers.any(message::contains) }

/** Fetches courses and retries once with a freshly logged-in session on confirmed expiry. */
internal suspend fun loadCoursesWithSessionRecovery(
    initialSession: SessionData,
    fetchCourses: suspend (SessionData) -> List<CourseItem>,
    relogin: suspend () -> SessionData?,
): List<CourseItem> = try {
    fetchCourses(initialSession)
} catch (firstError: Throwable) {
    if (firstError is CancellationException) throw firstError
    if (!isSessionExpiredError(firstError)) throw firstError
    val refreshed = try {
        relogin()
    } catch (reloginError: Throwable) {
        if (reloginError is CancellationException) throw reloginError
        throw IllegalStateException("会话已过期，自动重新登录失败：${reloginError.message}", reloginError)
    } ?: throw IllegalStateException("会话已过期，自动重新登录失败", firstError)
    fetchCourses(refreshed)
}
