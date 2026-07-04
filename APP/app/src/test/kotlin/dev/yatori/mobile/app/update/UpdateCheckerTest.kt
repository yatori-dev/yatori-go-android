package dev.yatori.mobile.app.update

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun `version parser accepts v prefix`() {
        assertEquals(listOf(1, 2, 3), parseVersion("v1.2.3"))
        assertEquals(listOf(2, 0), parseVersion("V2.0"))
    }

    @Test
    fun `semantic version comparison detects newer versions`() {
        assertTrue(isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("0.9.9", "1.0.0"))
    }

    @Test
    fun `arm64 device prefers arm64 apk`() {
        val picked = pickRecommendedApk(
            assets = listOf(
                ReleaseAsset("YatoriGoAndroid-v1.1.0-release-all.apk", "all"),
                ReleaseAsset("YatoriGoAndroid-v1.1.0-release-arm64.apk", "arm64"),
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("arm64", picked?.browserDownloadUrl)
    }

    @Test
    fun `non arm64 device prefers all apk`() {
        val picked = pickRecommendedApk(
            assets = listOf(
                ReleaseAsset("YatoriGoAndroid-v1.1.0-release-arm64.apk", "arm64"),
                ReleaseAsset("YatoriGoAndroid-v1.1.0-release-all.apk", "all"),
            ),
            supportedAbis = listOf("x86_64", "x86"),
        )

        assertEquals("all", picked?.browserDownloadUrl)
    }

    @Test
    fun `checker uses fastest reachable endpoint first`() = runTest {
        val direct = GitHubEndpoint("direct", "https://api.example/latest") { it }
        val proxy = GitHubEndpoint("proxy", "https://proxy.example/https://api.example/latest") { "https://proxy.example/$it" }
        val calls = mutableListOf<String>()
        val checker = AppUpdateChecker(
            endpoints = listOf(direct, proxy),
            latencyProbe = LatencyProbe { url, _ ->
                when (url) {
                    direct.latestReleaseApiUrl -> 200
                    proxy.latestReleaseApiUrl -> 10
                    else -> null
                }
            },
            fetcher = HttpTextFetcher { url, _ ->
                calls.add(url)
                releaseJson()
            },
        )

        val result = checker.checkForUpdate("1.0.0", supportedAbis = listOf("arm64-v8a"))

        assertTrue(result is UpdateResult.NewVersion)
        assertEquals(listOf(proxy.latestReleaseApiUrl), calls)
        assertEquals("proxy", result.release.channelName)
        assertEquals("https://proxy.example/https://github.com/yatori-dev/yatori-go-android/releases/download/v1.1.0/YatoriGoAndroid-v1.1.0-release-arm64.apk", result.release.recommendedDownloadUrl)
    }

    private fun releaseJson(): String = """
        {
          "tag_name": "v1.1.0",
          "name": "v1.1.0",
          "body": "更新说明",
          "html_url": "https://github.com/yatori-dev/yatori-go-android/releases/tag/v1.1.0",
          "assets": [
            {
              "name": "YatoriGoAndroid-v1.1.0-release-arm64.apk",
              "browser_download_url": "https://github.com/yatori-dev/yatori-go-android/releases/download/v1.1.0/YatoriGoAndroid-v1.1.0-release-arm64.apk"
            },
            {
              "name": "YatoriGoAndroid-v1.1.0-release-all.apk",
              "browser_download_url": "https://github.com/yatori-dev/yatori-go-android/releases/download/v1.1.0/YatoriGoAndroid-v1.1.0-release-all.apk"
            }
          ]
        }
    """.trimIndent()
}
