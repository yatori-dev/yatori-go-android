package dev.yatori.mobile.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.yatori.mobile.api.dto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobilecoreApiInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val core by lazy { YatoriMobileCore() }

    @Test fun testInit() = runBlocking {
        val r = core.init(ctx.filesDir.absolutePath)
        assertNotNull(r.version)
        assertTrue("baseDir must not be empty", r.baseDir.isNotEmpty())
    }

    @Test fun testHealthCheck() = runBlocking {
        core.init(ctx.filesDir.absolutePath)
        val h = core.healthCheck()
        assertTrue(h.initialized)
        assertNotNull(h.version)
    }

    @Test fun testSetGetConfig() = runBlocking {
        core.init(ctx.filesDir.absolutePath)
        val cfg = MobileConfig(MobileConfigSetting(BasicSetting(logLevel = "debug", logModel = 1)))
        core.setConfig(cfg)
        val got = core.getConfig()
        assertEquals("debug", got.setting.basicSetting.logLevel)
    }

    @Test fun testGetLogs() = runBlocking {
        core.init(ctx.filesDir.absolutePath)
        val logs = core.getLogs("")
        assertNotNull(logs.nextCursor)
    }

    @Test fun testRunTaskDryRun() = runBlocking {
        core.init(ctx.filesDir.absolutePath)
        val sess = SessionData("yinghua", "demo", "tok", extra = com.google.gson.JsonObject().apply {
            addProperty("preUrl", "https://example.com")
        })
        val raw = com.google.gson.JsonObject().apply { addProperty("courseId", "course-1") }
        val task = TaskItem("node-1", raw = raw, platform = "yinghua")
        val r = core.runTask(sess, task, mapOf("dryRun" to true))
        assertEquals("dry_run", r.status)
        assertEquals("yinghua", r.platform)
    }
}
