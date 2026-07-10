package dev.yatori.mobile.runtime.operation

import dev.yatori.mobile.api.dto.CourseItem
import java.time.LocalDate

private val yinghuaSessionExpiredMarkers = listOf(
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

/** Explicit auth-expiry errors must reach the App worker so its captcha-aware re-login can run. */
internal fun Throwable.isYinghuaSessionExpiredError(): Boolean =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.lowercase() }
        .any { message -> yinghuaSessionExpiredMarkers.any(message::contains) }

/** Mirrors the console's startDate guard; malformed/missing dates remain runnable. */
internal fun CourseItem.isYinghuaCourseNotStarted(today: LocalDate = LocalDate.now()): Boolean {
    val value = runCatching { raw.get("startDate")?.asString.orEmpty() }.getOrDefault("").trim()
    if (value.length < 10) return false
    val startDate = runCatching { LocalDate.parse(value.substring(0, 10)) }.getOrNull() ?: return false
    return today.isBefore(startDate)
}
