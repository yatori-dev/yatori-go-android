package com.yatori.android.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── 常量 ──────────────────────────────────────────────────────────────
private const val OWNER = "yatori-dev"
private const val REPO  = "yatori-go-android"
private val MIRRORS = listOf("https://ghproxy.net/", "https://gh-proxy.com/")
private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

// ── 数据类 ────────────────────────────────────────────────────────────
data class ReleaseInfo(
    val tagName: String,       // e.g. "v1.2.0"
    val name: String,          // release title
    val body: String,          // changelog (may be long)
    val downloadUrl: String,   // best asset URL (mirror-proxied)
    val fallbackUrl: String    // original github.com URL
)

sealed class UpdateResult {
    data class NewVersion(val release: ReleaseInfo, val current: String) : UpdateResult()
    object UpToDate : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

// ── 公共入口 ──────────────────────────────────────────────────────────
suspend fun checkForUpdate(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
    val json = fetchLatestRelease() ?: return@withContext UpdateResult.Error("检查更新失败，请稍后重试")
    try {
        val tag   = json.getString("tag_name")
        val title = json.optString("name", tag)
        val body  = json.optString("body", "")
        if (!isNewerVersion(tag, currentVersion)) return@withContext UpdateResult.UpToDate
        val (downloadUrl, fallbackUrl) = pickApkAsset(json, tag)
        UpdateResult.NewVersion(
            ReleaseInfo(tag, title, body.take(400).trimEnd(), downloadUrl, fallbackUrl),
            currentVersion
        )
    } catch (e: Exception) {
        UpdateResult.Error("解析版本信息失败：${e.message}")
    }
}

// ── 内部：拉取 latest release（官方 → 镜像兜底）─────────────────────
private fun fetchLatestRelease(): JSONObject? {
    val officialUrl = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    fetchJson(officialUrl)?.let { return it }
    for (mirror in MIRRORS) {
        fetchJson("${mirror}${officialUrl}")?.let { return it }
    }
    return null
}

private fun fetchJson(url: String): JSONObject? = try {
    val body = client.newCall(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").build()).execute().use { it.body?.string() }
    if (body != null) JSONObject(body) else null
} catch (_: Exception) { null }

// ── 内部：选最合适的 APK asset ────────────────────────────────────────
/** 返回 Pair(镜像URL, 原始URL) */
internal fun pickApkAsset(json: JSONObject, tag: String): Pair<String, String> {
    val assets = json.optJSONArray("assets")
    var bestUrl: String? = null
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name  = asset.optString("name", "").lowercase()
            if (name.endsWith(".apk") && (name.contains("release") || name.contains(tag.trimStart('v', 'V').lowercase()))) {
                bestUrl = asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                break
            }
        }
        if (bestUrl == null) for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name  = asset.optString("name", "").lowercase()
            if (name.endsWith(".apk")) { bestUrl = asset.optString("browser_download_url"); break }
        }
    }
    val fallback = bestUrl ?: "https://github.com/$OWNER/$REPO/releases/latest"
    val proxied  = "${MIRRORS[0]}$fallback"
    return proxied to fallback
}

// ── 语义化版本比较 ────────────────────────────────────────────────────
/** remote > local → true */
internal fun isNewerVersion(remote: String, local: String): Boolean {
    val r = parseVersion(remote)
    val l = parseVersion(local)
    if (r == null || l == null) return false
    return r.zip(l).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

/** "v1.2.3" / "V1.2.3" / "1.2.3" → [1, 2, 3] */
internal fun parseVersion(v: String): List<Int>? = try {
    v.trimStart('v', 'V').split(".").map { it.toInt() }
} catch (_: Exception) { null }
