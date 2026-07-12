package dev.yatori.mobile.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.*
import dev.yatori.mobile.api.internal.EnvelopeParser
import org.junit.Test
import java.time.ZoneId
import kotlin.test.*

class EnvelopeParserTest {

    @Test fun `parse success extracts data`() {
        val v = EnvelopeParser.parse("""{"ok":true,"data":{"version":"0.1.0-mobile"}}""") {
            it.get("version").asString
        }
        assertEquals("0.1.0-mobile", v)
    }

    @Test fun `parse error throws CoreException with message`() {
        val ex = assertFailsWith<CoreException> {
            EnvelopeParser.parse("""{"ok":false,"error":"not initialized"}""") { }
        }
        assertTrue(ex.message!!.contains("not initialized"))
    }

    @Test fun `parse error without error field throws CoreException unknown error`() {
        assertFailsWith<CoreException> {
            EnvelopeParser.parse("""{"ok":false}""") { }
        }
    }

    @Test fun `parse invalid JSON throws CoreException`() {
        assertFailsWith<CoreException> {
            EnvelopeParser.parse("not json at all") { }
        }
    }

    @Test fun `parse missing ok field throws CoreException`() {
        assertFailsWith<CoreException> {
            EnvelopeParser.parse("""{"data":{"x":1}}""") { }
        }
    }

    @Test fun `parseVoid success passes with null data`() {
        EnvelopeParser.parseVoid("""{"ok":true,"data":null}""")
    }

    @Test fun `parseVoid error throws CoreException`() {
        assertFailsWith<CoreException> {
            EnvelopeParser.parseVoid("""{"ok":false,"error":"invalid platform"}""")
        }
    }

    @Test fun `parseVoid invalid JSON throws CoreException`() {
        assertFailsWith<CoreException> {
            EnvelopeParser.parseVoid("{bad}")
        }
    }
}

class DtoTest {

    private val gson = Gson()

    @Test fun `InitResult parses from init envelope`() {
        val raw = """{"ok":true,"data":{"baseDir":"/tmp/app","version":"0.1.0-mobile"}}"""
        val result = EnvelopeParser.parse(raw) {
            gson.fromJson(it.toString(), InitResult::class.java)
        }
        assertEquals("/tmp/app", result.baseDir)
        assertEquals("0.1.0-mobile", result.version)
    }

    @Test fun `MobileConfig minimal structure serializes correctly`() {
        val cfg = MobileConfig(MobileConfigSetting(BasicSetting(logLevel = "debug", logModel = 1)))
        val json = gson.toJson(cfg)
        assertTrue(json.contains(""""logLevel":"debug""""))
        assertTrue(json.contains(""""logModel":1"""))
        assertTrue(json.contains(""""basicSetting":{"""))
        assertTrue(json.contains(""""setting":{"""))
        assertTrue(json.contains(""""users":[]"""))
    }

    @Test fun `MobileConfig full structure includes emailInform aiSetting and question bank setting`() {
        val cfg = MobileConfig(
            MobileConfigSetting(
                basicSetting = BasicSetting(logLevel = "info"),
                emailInform = EmailInform(smtpHost = "smtp.test"),
                aiSetting = AiSetting(aiType = "OPENAI"),
                apiQueSetting = ApiQueSetting(url = "https://bank.test/query", exType = "YANXI", exToken = "tk"),
            )
        )
        val json = gson.toJson(cfg)
        assertTrue(json.contains(""""emailInform":{"""))
        assertTrue(json.contains(""""smtpHost":"smtp.test""""))
        assertTrue(json.contains(""""aiSetting":{"""))
        assertTrue(json.contains(""""aiType":"OPENAI""""))
        assertTrue(json.contains(""""apiQueSetting":{"""))
        assertTrue(json.contains(""""url":"https://bank.test/query""""))
        assertTrue(json.contains(""""exType":"YANXI""""))
        assertTrue(json.contains(""""exToken":"tk""""))
    }

    @Test fun `ApiQueSetting keeps old url-only config readable`() {
        val parsed = gson.fromJson("""{"url":"https://bank.test/query"}""", ApiQueSetting::class.java)
        assertEquals("https://bank.test/query", parsed.url)
        assertEquals("CUSTOM", parsed.exType ?: "CUSTOM")
        assertEquals("", parsed.exToken ?: "")
    }

    @Test fun `LoginResult Done parses correctly`() {
        val raw = """{"ok":true,"data":{"status":"done","session":{"platform":"xuexitong","account":"u","token":"","extra":{}}}}"""
        val result = EnvelopeParser.parse(raw) { data ->
            LoginResult.Done(gson.fromJson(data.getAsJsonObject("session").toString(), SessionData::class.java))
        }
        assertIs<LoginResult.Done>(result)
        assertEquals("xuexitong", result.session.platform)
    }

    @Test fun `LoginResult Challenge parses correctly`() {
        val raw = """{"ok":true,"data":{"status":"challenge","taskId":"login-1","challenge":{"taskId":"login-1","platform":"yinghua","type":"image_ocr","imageBase64":"abc==","outputCols":18,"hint":"输入验证码"}}}"""
        val result = EnvelopeParser.parse(raw) { data ->
            LoginResult.Challenge(
                data.get("taskId").asString,
                gson.fromJson(data.getAsJsonObject("challenge").toString(), OcrChallenge::class.java)
            )
        }
        assertIs<LoginResult.Challenge>(result)
        assertEquals("login-1", result.taskId)
        assertEquals("image_ocr", result.challenge.type)
        assertEquals(18, result.challenge.outputCols)
    }

    @Test fun `LogEntry uses id time level source platform message - no msg or ts`() {
        val raw = """{"ok":true,"data":{"nextCursor":"1","oldestCursor":"1","truncated":false,"logs":[{"id":1,"time":"2026-06-23T06:00:00Z","level":"info","source":"mobilecore","platform":"yinghua","message":"StartLogin started"}]}}"""
        val result = EnvelopeParser.parse(raw) { gson.fromJson(it.toString(), LogResult::class.java) }
        val e = result.logs[0]
        assertEquals(1L, e.id)
        assertEquals("2026-06-23T06:00:00Z", e.time)
        assertEquals("info", e.level)
        assertEquals("mobilecore", e.source)
        assertEquals("yinghua", e.platform)
        assertEquals("StartLogin started", e.message)
        assertNull(e.timestampMicros)
    }

    @Test fun `LogEntry event time prefers numeric micros and falls back to RFC3339`() {
        val numeric = LogEntry(
            id = 1,
            time = "2026-06-23T06:00:00Z",
            level = "info",
            source = "mobilecore",
            message = "numeric",
            timestampMicros = 123L,
        )
        val legacy = numeric.copy(timestampMicros = null)

        assertEquals(123L, numeric.eventTimeMicros())
        assertNotNull(legacy.eventTimeMicros())
    }

    @Test fun `LogEntry local time converts UTC timestamp to requested zone`() {
        val entry = LogEntry(
            id = 1,
            time = "2026-06-23T06:00:00Z",
            level = "info",
            source = "mobilecore",
            platform = "yinghua",
            message = "hello",
        )
        val zone = ZoneId.of("Asia/Shanghai")

        assertEquals("2026-06-23 14:00:00", entry.localFullTime(zone))
        assertEquals("06-23 14:00", entry.localShortTime(zone))
        assertEquals("2026-06-23 14:00:00 [INFO] mobilecore(yinghua): hello", entry.localReadableLine(zone))
    }

    @Test fun `SessionData extra round-trips without loss including unknown fields`() {
        val extra = JsonObject().apply {
            addProperty("sign", "sig-abc")
            addProperty("preUrl", "https://yh.test")
            addProperty("unknownField", "keep-me")
            add("nested", JsonObject().apply { addProperty("key", "val") })
        }
        val sess = SessionData("yinghua", "user", "tok", "", extra)
        val json = gson.toJson(sess)
        val parsed = gson.fromJson(json, SessionData::class.java)
        assertEquals("sig-abc", parsed.extra.get("sign").asString)
        assertEquals("keep-me", parsed.extra.get("unknownField").asString)
        assertEquals("val", parsed.extra.getAsJsonObject("nested").get("key").asString)
    }

    @Test fun `AccountInput extra serializes without loss`() {
        val extra = JsonObject().apply { addProperty("schoolId", "42") }
        val input = AccountInput("user", "pass", "https://example.test", extra)
        val parsed = gson.fromJson(gson.toJson(input), AccountInput::class.java)
        assertEquals("42", parsed.extra.get("schoolId").asString)
    }

    @Test fun `CourseItem raw round-trips without loss`() {
        val raw = JsonObject().apply { addProperty("courseId", "1001"); addProperty("preUrl", "https://test") }
        val item = CourseItem("1001", "课程A", raw = raw)
        val parsed = gson.fromJson(gson.toJson(item), CourseItem::class.java)
        assertEquals("1001", parsed.raw.get("courseId").asString)
        assertEquals("https://test", parsed.raw.get("preUrl").asString)
    }

    @Test fun `TaskItem raw round-trips without loss`() {
        val raw = JsonObject().apply {
            addProperty("nodeId", "200")
            addProperty("courseId", "1001")
            addProperty("videoDuration", "1235")
            addProperty("nodeLock", 0)
        }
        val item = TaskItem("200", "第1节", raw = raw)
        val parsed = gson.fromJson(gson.toJson(item), TaskItem::class.java)
        assertEquals("200", parsed.raw.get("nodeId").asString)
        assertEquals("1235", parsed.raw.get("videoDuration").asString)
    }

    @Test fun `RunTaskResult raw round-trips without loss`() {
        val raw = JsonObject().apply {
            addProperty("submitMode", "progress")
            addProperty("intervalSeconds", 30)
            addProperty("tickerUrl", "https://ttcdw.test/tick")
        }
        val result = RunTaskResult("ttcdw", "video-1", "prepared", raw = raw)
        val parsed = gson.fromJson(gson.toJson(result), RunTaskResult::class.java)
        assertEquals("progress", parsed.raw.get("submitMode").asString)
        assertEquals(30, parsed.raw.get("intervalSeconds").asInt)
        assertEquals("https://ttcdw.test/tick", parsed.raw.get("tickerUrl").asString)
    }
}
