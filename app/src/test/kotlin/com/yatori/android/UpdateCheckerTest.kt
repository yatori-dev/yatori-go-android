package com.yatori.android

import com.yatori.android.update.isNewerVersion
import com.yatori.android.update.parseVersion
import com.yatori.android.update.pickApkAsset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    @Test fun parseVersion_standard()    { assertEquals(listOf(1,2,3), parseVersion("1.2.3")) }
    @Test fun parseVersion_v_prefix()   { assertEquals(listOf(1,0,0), parseVersion("v1.0.0")) }
    @Test fun parseVersion_V_prefix()   { assertEquals(listOf(2,3,4), parseVersion("V2.3.4")) }
    @Test fun parseVersion_invalid()    { assertNull(parseVersion("not-a-version")) }

    @Test fun newer_patch()   { assertTrue(isNewerVersion("1.0.1", "1.0.0")) }
    @Test fun newer_minor()   { assertTrue(isNewerVersion("1.1.0", "1.0.9")) }
    @Test fun newer_major()   { assertTrue(isNewerVersion("2.0.0", "1.9.9")) }
    @Test fun same_version()  { assertFalse(isNewerVersion("1.0.0", "1.0.0")) }
    @Test fun older_version() { assertFalse(isNewerVersion("0.9.9", "1.0.0")) }
    @Test fun v_prefix_newer(){ assertTrue(isNewerVersion("v1.2.0", "1.1.9")) }

    @Test fun pickApk_prefers_release_apk() {
        val json = JSONObject("""{"assets":[
            {"name":"app-debug.apk","browser_download_url":"https://github.com/owner/repo/releases/download/v1.0/app-debug.apk"},
            {"name":"app-release.apk","browser_download_url":"https://github.com/owner/repo/releases/download/v1.0/app-release.apk"}
        ]}""")
        val (proxied, fallback) = pickApkAsset(json, "v1.0")
        assertTrue(fallback.contains("release"))
        assertTrue(proxied.startsWith("https://ghproxy.net/"))
    }

    @Test fun pickApk_fallback_to_release_page() {
        val json = JSONObject("""{"assets":[]}""")
        val (_, fallback) = pickApkAsset(json, "v1.0")
        assertTrue(fallback.contains("releases/latest"))
    }
}
