package dev.yatori.mobile.app.platform

import dev.yatori.mobile.api.Platform

data class PlatformMeta(
    val platform: Platform,
    val displayName: String,
    val requiresUrl: Boolean,
    val urlLabel: String = "",
    /** Label shown on the password field (override for platforms that use cookie-based login). */
    val passwordLabel: String = "密码",
    /** Whether to mask the password field input. Set false for cookie strings. */
    val maskPassword: Boolean = true,
)

/** Source of truth for AddAccount field visibility (see Appendix A of UI blueprint). */
val allPlatformMeta: List<PlatformMeta> = listOf(
    PlatformMeta(Platform.YINGHUA,         "英华学堂",   requiresUrl = true,  urlLabel = "学校平台地址"),
    PlatformMeta(Platform.HAIQIKEJI,       "海旗科技",   requiresUrl = true,  urlLabel = "服务器地址"),
    PlatformMeta(Platform.XUEXITONG,       "学习通",     requiresUrl = false),
    PlatformMeta(Platform.CQIE,            "重庆工程学院", requiresUrl = false),
    PlatformMeta(Platform.QINGSHUXUETANG,  "青书学堂",   requiresUrl = false),
    PlatformMeta(Platform.KETANGX,         "码上研训",   requiresUrl = false),
    PlatformMeta(Platform.TTCDW,           "学习公社（TTCDW）", requiresUrl = false),
    PlatformMeta(Platform.ENAEA,           "学习公社 / 网络党校（ENAEA）", requiresUrl = false),
    PlatformMeta(Platform.WELEARN,         "随行课堂",   requiresUrl = false),
    PlatformMeta(
        platform      = Platform.ICVE,
        displayName   = "智慧职教",
        requiresUrl   = false,
        passwordLabel = "Cookie（将浏览器登录后的Cookie粘贴至此）",
        maskPassword  = false,
    ),
)

private val displayNameById: Map<String, String> =
    allPlatformMeta.associate { it.platform.id to it.displayName }

/**
 * Maps a raw platform code (e.g. "haiqikeji") from a [dev.yatori.mobile.api.dto.LogEntry] to its
 * Chinese display name (海旗科技). Unknown / blank codes fall back to [fallback]. Null-safe because
 * Gson can inject null into the non-null [String] field.
 */
fun platformDisplayName(code: String?, fallback: String = "Yatori-go-mobile-core"): String {
    val key = code.orEmpty().trim()
    if (key.isEmpty()) return fallback
    return displayNameById[key] ?: key
}
