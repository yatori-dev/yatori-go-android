package dev.yatori.mobile.app.update

import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.system.measureTimeMillis

private const val OWNER = "yatori-dev"
private const val REPO = "yatori-go-android"
private const val SPEED_TIMEOUT_MS = 1200
private const val FETCH_TIMEOUT_MS = 10000

data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val recommendedAssetName: String,
    val recommendedDownloadUrl: String,
    val directDownloadUrl: String,
    val releasePageUrl: String,
    val channelName: String,
)

sealed interface UpdateResult {
    data class NewVersion(val release: ReleaseInfo, val current: String) : UpdateResult
    data object UpToDate : UpdateResult
    data class Error(val message: String) : UpdateResult
}

fun interface HttpTextFetcher {
    fun fetch(url: String, timeoutMillis: Int): String?
}

fun interface LatencyProbe {
    fun measure(url: String, timeoutMillis: Int): Long?
}

data class GitHubEndpoint(
    val name: String,
    val latestReleaseApiUrl: String,
    val urlForBrowser: (String) -> String,
)

class AppUpdateChecker(
    private val fetcher: HttpTextFetcher = UrlConnectionTextFetcher(),
    private val latencyProbe: LatencyProbe = UrlConnectionLatencyProbe(),
    private val endpoints: List<GitHubEndpoint> = defaultGitHubEndpoints(),
) {
    suspend fun checkForUpdate(
        currentVersion: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val (json, endpoint) = fetchLatestRelease()
                ?: return@withContext UpdateResult.Error("检查更新失败")
            parseRelease(json, endpoint, supportedAbis, currentVersion)
        }.getOrElse { UpdateResult.Error("检查更新失败") }
    }

    private fun fetchLatestRelease(): Pair<JsonObject, GitHubEndpoint>? {
        for (endpoint in rankedEndpoints()) {
            val body = fetcher.fetch(endpoint.latestReleaseApiUrl, FETCH_TIMEOUT_MS) ?: continue
            val parsed = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            if (parsed != null) return parsed to endpoint
        }
        return null
    }

    private fun rankedEndpoints(): List<GitHubEndpoint> {
        val measured = endpoints.map { endpoint ->
            endpoint to latencyProbe.measure(endpoint.latestReleaseApiUrl, SPEED_TIMEOUT_MS)
        }
        val reachable = measured.filter { it.second != null }.sortedBy { it.second }
        val unreachable = measured.filter { it.second == null }
        return (reachable + unreachable).map { it.first }
    }
}

private fun parseRelease(
    json: JsonObject,
    endpoint: GitHubEndpoint,
    supportedAbis: List<String>,
    currentVersion: String,
): UpdateResult {
    val tag = json.string("tag_name") ?: return UpdateResult.Error("解析版本信息失败")
    if (!isNewerVersion(tag, currentVersion)) return UpdateResult.UpToDate

    val title = json.string("name").takeUnless { it.isNullOrBlank() } ?: tag
    val body = json.string("body").orEmpty().take(800).trimEnd()
    val releasePage = json.string("html_url") ?: "https://github.com/$OWNER/$REPO/releases/latest"
    val assets = json.arrayObjects("assets").mapNotNull { asset ->
        val name = asset.string("name").orEmpty()
        val url = asset.string("browser_download_url").orEmpty()
        if (name.isBlank() || url.isBlank()) null else ReleaseAsset(name, url)
    }
    val preferred = pickRecommendedApk(assets, supportedAbis)
    val directUrl = preferred?.browserDownloadUrl ?: releasePage
    return UpdateResult.NewVersion(
        release = ReleaseInfo(
            tagName = tag,
            name = title,
            body = body,
            recommendedAssetName = preferred?.name ?: "发布页",
            recommendedDownloadUrl = endpoint.urlForBrowser(directUrl),
            directDownloadUrl = directUrl,
            releasePageUrl = releasePage,
            channelName = endpoint.name,
        ),
        current = currentVersion,
    )
}

internal fun pickRecommendedApk(
    assets: List<ReleaseAsset>,
    supportedAbis: List<String>,
): ReleaseAsset? {
    val apks = assets.filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
    if (apks.isEmpty()) return null
    val nonDebug = apks.filterNot { it.name.lowercase(Locale.ROOT).contains("debug") }.ifEmpty { apks }
    val releaseFirst = nonDebug.sortedByDescending { it.name.lowercase(Locale.ROOT).contains("release") }
    val isArm64Device = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun firstWithAny(markers: List<String>): ReleaseAsset? =
        releaseFirst.firstOrNull { asset ->
            val name = asset.name.lowercase(Locale.ROOT)
            markers.any { marker -> name.contains(marker) }
        }

    return if (isArm64Device) {
        firstWithAny(listOf("arm64-v8a", "arm64")) ?:
            firstWithAny(listOf("universal", "all-abi", "all_arch", "all")) ?:
            releaseFirst.firstOrNull()
    } else {
        firstWithAny(listOf("universal", "all-abi", "all_arch", "all")) ?:
            firstWithAny(listOf("x86_64", "x64", "x86")) ?:
            releaseFirst.firstOrNull()
    }
}

internal fun isNewerVersion(remote: String, local: String): Boolean {
    val r = parseVersion(remote) ?: return false
    val l = parseVersion(local) ?: return false
    val max = maxOf(r.size, l.size)
    for (i in 0 until max) {
        val rv = r.getOrElse(i) { 0 }
        val lv = l.getOrElse(i) { 0 }
        if (rv != lv) return rv > lv
    }
    return false
}

internal fun parseVersion(value: String): List<Int>? {
    val match = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(value.trim()) ?: return null
    return match.groupValues.drop(1).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }
        .takeIf { it.isNotEmpty() }
}

private fun defaultGitHubEndpoints(): List<GitHubEndpoint> {
    val api = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    val proxies = listOf("https://ghproxy.net/", "https://gh-proxy.com/")
    return listOf(
        GitHubEndpoint("GitHub 直连", api) { it },
    ) + proxies.map { proxy ->
        GitHubEndpoint(proxy.removeSuffix("/"), "$proxy$api") { url -> "$proxy$url" }
    }
}

private class UrlConnectionTextFetcher : HttpTextFetcher {
    override fun fetch(url: String, timeoutMillis: Int): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "YatoriGoAndroid")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

private class UrlConnectionLatencyProbe : LatencyProbe {
    override fun measure(url: String, timeoutMillis: Int): Long? {
        return measureWithMethod(url, timeoutMillis, "HEAD")
            ?: measureWithMethod(url, timeoutMillis, "GET")
    }

    private fun measureWithMethod(url: String, timeoutMillis: Int, method: String): Long? {
        var ok = false
        val elapsed = runCatching {
            measureTimeMillis {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    requestMethod = method
                    connectTimeout = timeoutMillis
                    readTimeout = timeoutMillis
                    setRequestProperty("User-Agent", "YatoriGoAndroid")
                }
                try {
                    ok = conn.responseCode in 200..399
                } finally {
                    conn.disconnect()
                }
            }
        }.getOrNull()
        return elapsed?.takeIf { ok }
    }
}

private fun JsonObject.string(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.arrayObjects(name: String): List<JsonObject> {
    val array = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return array.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
}
