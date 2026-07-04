package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.yatori.mobile.api.dto.AiSetting
import dev.yatori.mobile.api.dto.ApiQueSetting
import dev.yatori.mobile.api.dto.SessionData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerProviderTest {
    @Test
    fun `external question bank posts original protocol and reads top level answers`() = runTest {
        val http = FakeHttpClient("""{"code":200,"answers":["A","B"]}""")
        val provider = ExternalQuestionBankAnswerProvider(
            settingProvider = { ApiQueSetting("https://bank.example/ask") },
            httpClient = http,
        )

        val answers = provider.answers(request(question()))

        assertEquals(listOf("A", "B"), answers)
        assertEquals("https://bank.example/ask", http.urls.single())
        assertTrue(http.bodies.single().contains(""""content":"Question""""))
        assertTrue(http.bodies.single().contains(""""options":["Option A","Option B"]"""))
        assertTrue(http.bodies.single().contains(""""md5":"""))
    }

    @Test
    fun `external question bank reads embedded question answers`() = runTest {
        val provider = ExternalQuestionBankAnswerProvider(
            settingProvider = { ApiQueSetting("https://bank.example/ask") },
            httpClient = FakeHttpClient("""{"code":200,"question":{"answers":["Embedded"]}}"""),
        )

        assertEquals(listOf("Embedded"), provider.answers(request(question())))
    }

    @Test
    fun `external question bank 404 returns empty so caller can fallback`() = runTest {
        val provider = ExternalQuestionBankAnswerProvider(
            settingProvider = { ApiQueSetting("https://bank.example/ask") },
            httpClient = FakeHttpClient("""{"code":404,"msg":"not found"}"""),
        )

        assertEquals(emptyList(), provider.answers(request(question())))
    }

    @Test
    fun `external yanxi bank uses original get protocol and maps letter answers`() = runTest {
        val http = FakeHttpClient("""{"code":1,"data":{"answer":"[A,B]","question":"Question"},"message":"ok"}""")
        val provider = ExternalQuestionBankAnswerProvider(
            settingProvider = { ApiQueSetting(exType = "YANXI", exToken = "token") },
            httpClient = http,
        )

        assertEquals(listOf("Option A", "Option B"), provider.answers(request(question())))
        assertEquals("GET", http.methods.single())
        assertTrue(http.urls.single().startsWith("https://tk.enncy.cn/query?"))
        assertTrue(http.urls.single().contains("token=token"))
        assertTrue(http.urls.single().contains("title=Question"))
        assertTrue(http.urls.single().contains("type=single"))
    }

    @Test
    fun `external max bank parses triple hash answers`() = runTest {
        val http = FakeHttpClient("""{"code":1,"data":"A###B"}""")
        val provider = ExternalQuestionBankAnswerProvider(
            settingProvider = { ApiQueSetting(exType = "MAX", exToken = "token") },
            httpClient = http,
        )

        assertEquals(listOf("Option A", "Option B"), provider.answers(request(question())))
        assertTrue(http.urls.single().startsWith("https://max.tlicf.com/Interface/xxt/?key=token"))
        assertEquals("maxq.tlicf.com", http.headers.single()["Host"])
    }

    @Test
    fun `external zds bank parses newline answers`() = runTest {
        val http = FakeHttpClient("""{"code":1,"data":"A\nB"}""")
        val provider = ExternalQuestionBankAnswerProvider(
            settingProvider = { ApiQueSetting(exType = "ZDS", exToken = "token") },
            httpClient = http,
        )

        assertEquals(listOf("Option A", "Option B"), provider.answers(request(question())))
        assertTrue(http.urls.single().startsWith("http://tiku2.mfax.top/cs?token=token"))
        assertEquals("tiku2.mfax.top", http.headers.single()["Host"])
    }

    @Test
    fun `host ai uses built-in provider url and console default model`() = runTest {
        val http = FakeHttpClient("""{"choices":[{"message":{"content":"[\"Option A\"]"}}]}""")
        val provider = HostAiAnswerProvider(
            settingProvider = {
                AiSetting(
                    aiType = "DEEPSEEK",
                    aiUrl = "https://stale.example/ignored",
                    apiKey = "sk-test",
                )
            },
            httpClient = http,
        )

        assertEquals(listOf("Option A"), provider.answers(request(question())))
        assertEquals("https://api.deepseek.com/chat/completions", http.urls.single())
        assertEquals("deepseek-chat", JsonParser.parseString(http.bodies.single()).asJsonObject.string("model"))
    }

    @Test
    fun `host ai other uses custom url and configured model`() = runTest {
        val http = FakeHttpClient("""{"choices":[{"message":{"content":"[\"Answer\"]"}}]}""")
        val provider = HostAiAnswerProvider(
            settingProvider = {
                AiSetting(
                    aiType = "OTHER",
                    aiUrl = "https://ai.example/chat",
                    model = "custom-model",
                    apiKey = "sk-test",
                )
            },
            httpClient = http,
        )

        assertEquals(listOf("Answer"), provider.answers(request(question())))
        assertEquals("https://ai.example/chat", http.urls.single())
        val body = JsonParser.parseString(http.bodies.single()).asJsonObject
        assertEquals("custom-model", body.string("model"))
        assertTrue(body.get("messages").asJsonArray.all { it.asJsonObject.string("role") == "user" })
    }

    @Test
    fun `host ai metaso uses metaso request body and answer field`() = runTest {
        val http = FakeHttpClient("""{"answer":"[\"Meta Answer\"]"}""")
        val provider = HostAiAnswerProvider(
            settingProvider = { AiSetting(aiType = "METAAI", apiKey = "meta-key") },
            httpClient = http,
        )

        assertEquals(listOf("Meta Answer"), provider.answers(request(question())))
        assertEquals("https://metaso.cn/api/v1/chat/completions", http.urls.single())
        val body = JsonParser.parseString(http.bodies.single()).asJsonObject
        assertEquals("fast", body.string("model"))
        assertEquals("simple", body.string("format"))
        assertTrue(body.string("q").contains("题目类型：单选题"))
        assertEquals("metaso.cn", http.headers.single()["Host"])
    }

    @Test
    fun `host ai openai responses output is parsed`() = runTest {
        val http = FakeHttpClient(
            """{"output":[{"content":[{"type":"output_text","text":"[\"OpenAI Answer\"]"}]}]}""",
        )
        val provider = HostAiAnswerProvider(
            settingProvider = { AiSetting(aiType = "OPENAI", model = "gpt-4.1-mini", apiKey = "sk-test") },
            httpClient = http,
        )

        assertEquals(listOf("OpenAI Answer"), provider.answers(request(question())))
        assertEquals("https://api.openai.com/v1/responses", http.urls.single())
        val body = JsonParser.parseString(http.bodies.single()).asJsonObject
        assertTrue(body.has("input"))
    }

    @Test
    fun `host ai retries when answer is not json array`() = runTest {
        val http = FakeHttpClient(
            """{"choices":[{"message":{"content":"Option A"}}]}""",
            """{"choices":[{"message":{"content":"[\"Option A\"]"}}]}""",
        )
        val provider = HostAiAnswerProvider(
            settingProvider = { AiSetting(aiType = "TONGYI", apiKey = "sk-test") },
            httpClient = http,
        )

        assertEquals(listOf("Option A"), provider.answers(request(question())))
        assertEquals(2, http.bodies.size)
        assertTrue(http.bodies.last().contains("不是合法 JSON 数组"))
    }

    @Test
    fun `host ai bbs returns plain content instead of json answer parsing`() = runTest {
        val http = FakeHttpClient("""{"choices":[{"message":{"content":"讨论回复内容"}}]}""")
        val provider = HostAiAnswerProvider(
            settingProvider = { AiSetting(aiType = "DEEPSEEK", apiKey = "sk-test") },
            httpClient = http,
        )

        assertEquals("讨论回复内容", provider.bbs(request(question())))
    }

    private class FakeHttpClient(private vararg val responses: String) : AnswerHttpClient {
        val methods = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override fun postJson(
            url: String,
            body: String,
            bearerToken: String,
            timeoutMillis: Int,
            headers: Map<String, String>,
        ): String {
            methods.add("POST")
            urls.add(url)
            bodies.add(body)
            this.headers.add(headers)
            return responses.getOrElse(bodies.lastIndex) { responses.last() }
        }

        override fun get(url: String, timeoutMillis: Int, headers: Map<String, String>): String {
            methods.add("GET")
            urls.add(url)
            this.headers.add(headers)
            return responses.getOrElse(urls.lastIndex) { responses.last() }
        }
    }

    private companion object {
        fun request(question: JsonObject): AnswerRequest = AnswerRequest(
            session = SessionData("xuexitong", "stu"),
            ctx = JsonObject(),
            question = question,
            prompt = "Question",
            dryRun = false,
            label = "question",
        )

        fun question(): JsonObject = JsonObject().apply {
            addProperty("typeCode", "single")
            addProperty("content", "Question")
            add(
                "options",
                JsonObject().apply {
                    addProperty("A", "Option A")
                    addProperty("B", "Option B")
                },
            )
        }
    }
}
