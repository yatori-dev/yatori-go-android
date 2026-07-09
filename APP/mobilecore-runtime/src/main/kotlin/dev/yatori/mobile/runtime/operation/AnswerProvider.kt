package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.yatori.mobile.api.dto.AiSetting
import dev.yatori.mobile.api.dto.ApiQueSetting
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

private const val AI_MAX_ATTEMPTS = 7
private const val AI_TEMPERATURE = 0.2
private const val QUESTION_BANK_TIMEOUT_MILLIS = 120_000

enum class AnswerMode(val configValue: Int) {
    DISABLED(0),
    HOST_AI(1),
    EXTERNAL_QUESTION_BANK(2),
    XUEXITONG_BUILT_IN(3);

    companion object {
        fun fromConfig(value: Int): AnswerMode = entries.firstOrNull { it.configValue == value } ?: DISABLED
    }
}

data class AnswerRequest(
    val session: SessionData,
    val ctx: JsonObject,
    val question: JsonObject,
    val prompt: String,
    val dryRun: Boolean,
    val label: String,
)

interface AnswerProvider {
    suspend fun answers(request: AnswerRequest): List<String>
    suspend fun bbs(request: AnswerRequest): String =
        answers(request).filter { it.isNotBlank() }.joinToString("\n")
}

class BuiltInXuexitongAnswerProvider(
    private val runner: CourseTaskRunner,
) : AnswerProvider {
    override suspend fun answers(request: AnswerRequest): List<String> {
        if (request.dryRun) return listOf("dry-run")
        val raw = JsonObject().apply {
            addProperty("classId", request.ctx.string("classId"))
            addProperty("courseId", request.ctx.string("courseId"))
            addProperty("cpi", request.ctx.string("cpi"))
        }
        val result = runner.runTask(
            request.session,
            TaskItem(raw.string("courseId").ifBlank { "xxt-ai" }, type = "xxtAI", platform = "xuexitong", raw = raw),
            mapOf("action" to "xxtAI", "content" to request.prompt),
        )
        return result.answerList().filter { it.isNotBlank() }
    }
}

class ExternalQuestionBankAnswerProvider(
    private val settingProvider: () -> ApiQueSetting?,
    private val httpClient: AnswerHttpClient = UrlConnectionAnswerHttpClient(),
) : AnswerProvider {
    override suspend fun answers(request: AnswerRequest): List<String> {
        val setting = settingProvider() ?: return emptyList()
        val type = setting.exType.orEmpty().ifBlank {
            if (setting.url.orEmpty().isBlank()) "" else ExternalQuestionBankCatalog.CUSTOM
        }
        val spec = ExternalQuestionBankCatalog.spec(type)
        return if (spec.type == ExternalQuestionBankCatalog.CUSTOM) {
            requestCustomBank(setting, request)
        } else {
            requestBuiltInBank(setting, spec, request)
        }
    }

    private fun requestCustomBank(setting: ApiQueSetting, request: AnswerRequest): List<String> {
        val url = setting.url.orEmpty().trim()
        if (url.isBlank()) return emptyList()
        val body = originalQuestionBankRequest(request.question, request.prompt)
        val token = setting.exToken.orEmpty().trim()
        val raw = runCatching {
            httpClient.postJson(url, body.toString(), bearerToken = token, timeoutMillis = QUESTION_BANK_TIMEOUT_MILLIS, headers = emptyMap())
        }.getOrNull() ?: return emptyList()
        return parseOriginalQuestionBankAnswers(raw)
    }

    private fun requestBuiltInBank(
        setting: ApiQueSetting,
        spec: ExternalQuestionBankSpec,
        request: AnswerRequest,
    ): List<String> {
        val token = setting.exToken.orEmpty().trim()
        if (token.isBlank()) return emptyList()
        val question = bankQuestion(request.question, request.prompt)
        if (question.content.isBlank()) return emptyList()
        val raw = runCatching {
            httpClient.get(
                url = spec.requestUrl(token, question),
                timeoutMillis = QUESTION_BANK_TIMEOUT_MILLIS,
                headers = spec.headers,
            )
        }.getOrNull() ?: return emptyList()
        return spec.parse(raw, question)
    }

    private fun originalQuestionBankRequest(question: JsonObject, prompt: String): JsonObject =
        bankQuestion(question, prompt).let { q ->
            JsonObject().apply {
                addProperty("md5", md5(q.type + q.content))
                addProperty("type", q.type)
                addProperty("content", q.content)
                add("options", JsonArray().apply { q.options.forEach { add(it) } })
                add("answers", JsonArray().apply { jsonStrings(question.get("answers")).forEach { add(it) } })
            }
        }

    private fun parseOriginalQuestionBankAnswers(raw: String): List<String> {
        val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val code = parsed.get("code")?.asIntOrNull()
        if (code != null && code != 0 && code != 1 && code != 200) return emptyList()
        return parsed.answerList().ifEmpty {
            parsed.get("question")?.asJsonObjectOrNull()?.answerList().orEmpty()
        }.ifEmpty {
            parsed.get("data")?.answersFromData().orEmpty()
        }
    }

    private fun md5(raw: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class BankQuestion(
    val type: String,
    val content: String,
    val options: List<String>,
)

data class ExternalQuestionBankSpec(
    val type: String,
    val label: String,
    val requestUrl: (token: String, question: BankQuestion) -> String,
    val parse: (raw: String, question: BankQuestion) -> List<String>,
    val headers: Map<String, String> = emptyMap(),
)

object ExternalQuestionBankCatalog {
    const val CUSTOM = "CUSTOM"

    val providers: List<ExternalQuestionBankSpec> = listOf(
        ExternalQuestionBankSpec(
            type = CUSTOM,
            label = "自定义题库服务",
            requestUrl = { _, _ -> "" },
            parse = { _, _ -> emptyList() },
        ),
        ExternalQuestionBankSpec(
            type = "YANXI",
            label = "言溪题库",
            requestUrl = { token, question ->
                "https://tk.enncy.cn/query?token=${token.urlEncode()}&title=${question.content.urlEncode()}&type=${yanxiType(question.type).urlEncode()}"
            },
            parse = { raw, question ->
                val obj = raw.asJsonObjectOrNull() ?: return@ExternalQuestionBankSpec emptyList()
                if (obj.get("code")?.asIntOrNull() != 1) return@ExternalQuestionBankSpec emptyList()
                obj.get("data")?.asJsonObjectOrNull()
                    ?.string("answer")
                    ?.splitByLongest("#", "\n", "---", ",")
                    ?.mapLettersToOptions(question.options)
                    .orEmpty()
            },
            headers = apifoxHeaders(),
        ),
        ExternalQuestionBankSpec(
            type = "MAX",
            label = "MAX题库",
            requestUrl = { token, question ->
                buildString {
                    append("https://max.tlicf.com/Interface/xxt/?key=")
                    append(token.urlEncode())
                    append("&question=")
                    append(question.content.urlEncode())
                    append("&info=")
                    append(question.type.urlEncode())
                    if (question.options.isNotEmpty()) append(question.options.toJsonArrayString())
                }
            },
            parse = { raw, question ->
                val obj = raw.asJsonObjectOrNull() ?: return@ExternalQuestionBankSpec emptyList()
                if (obj.get("code")?.asIntOrNull() != 1) return@ExternalQuestionBankSpec emptyList()
                obj.string("data")
                    .splitByLongest("###", ",")
                    .mapLettersToOptions(question.options)
            },
            headers = apifoxHeaders("Host" to "maxq.tlicf.com"),
        ),
        ExternalQuestionBankSpec(
            type = "ZDS",
            label = "早点睡题库",
            requestUrl = { token, question ->
                "http://tiku2.mfax.top/cs?token=${token.urlEncode()}&q=${question.content.urlEncode()}"
            },
            parse = { raw, question ->
                val obj = raw.asJsonObjectOrNull() ?: return@ExternalQuestionBankSpec emptyList()
                if (obj.get("code")?.asIntOrNull() != 1) return@ExternalQuestionBankSpec emptyList()
                obj.string("data")
                    .splitByLongest("###", "\n", "---")
                    .mapLettersToOptions(question.options)
            },
            headers = apifoxHeaders("Host" to "tiku2.mfax.top"),
        ),
    )

    fun spec(type: String): ExternalQuestionBankSpec =
        providers.firstOrNull { it.type.equals(type, ignoreCase = true) } ?: providers.first()
}

class HostAiAnswerProvider(
    private val settingProvider: () -> AiSetting?,
    private val httpClient: AnswerHttpClient = UrlConnectionAnswerHttpClient(),
) : AnswerProvider {
    override suspend fun answers(request: AnswerRequest): List<String> {
        val content = requestAiContent(request, jsonAnswer = true) ?: return emptyList()
        return parseStrictJsonAnswers(content).orEmpty()
    }

    override suspend fun bbs(request: AnswerRequest): String =
        requestAiContent(request, jsonAnswer = false)?.let(::cleanPlainAiText).orEmpty()

    private fun requestAiContent(request: AnswerRequest, jsonAnswer: Boolean): String? {
        val setting = settingProvider() ?: return null
        if (setting.apiKey.isBlank()) return null
        val spec = AiProviderCatalog.spec(setting.aiType)
        val url = spec.requestUrl(setting).ifBlank { return null }
        val model = spec.requestModel(setting).ifBlank { return null }
        var messages = if (jsonAnswer) answerMessages(request) else plainMessages(request)
        repeat(AI_MAX_ATTEMPTS) { attempt ->
            val requestMessages = if (spec.type == "OTHER") messages.withSystemRolesAsUser() else messages
            val raw = runCatching {
                httpClient.postJson(
                    url = url,
                    body = spec.requestBody(model, requestMessages).toString(),
                    bearerToken = setting.apiKey,
                    timeoutMillis = 60_000,
                    headers = spec.extraHeaders,
                )
            }.getOrNull() ?: return null
            val content = aiContent(raw).firstOrNull { it.isNotBlank() } ?: return null
            if (!jsonAnswer) return content
            if (!parseStrictJsonAnswers(content).isNullOrEmpty()) return content
            if (attempt == AI_MAX_ATTEMPTS - 1) return null
            messages = messages.withRetryPrompt(content)
        }
        return null
    }

    private fun answerMessages(request: AnswerRequest): JsonArray =
        JsonArray().apply {
            add(message("system", answerSystemPrompt(request.question)))
            add(message("user", questionProblem(request)))
        }

    private fun plainMessages(request: AnswerRequest): JsonArray =
        JsonArray().apply {
            add(message("user", request.prompt))
        }

    private fun message(role: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }

    private fun JsonArray.withRetryPrompt(content: String): JsonArray =
        JsonArray().also { next ->
            forEach { next.add(it) }
            next.add(message("system", content))
            next.add(message("user", "你刚才的回复不是合法 JSON 数组，我无法解析。请只重新输出 JSON 数组，例如：[\"答案\"]。"))
        }

    private fun JsonArray.withSystemRolesAsUser(): JsonArray =
        JsonArray().also { next ->
            forEach { item ->
                val obj = item.asJsonObjectOrNull()
                if (obj == null) {
                    next.add(item)
                } else {
                    next.add(
                        message(
                            role = if (obj.string("role").equals("system", ignoreCase = true)) "user" else obj.string("role"),
                            content = obj.string("content"),
                        ),
                    )
                }
            }
        }

    private fun AiProviderSpec.requestUrl(setting: AiSetting): String =
        if (requiresCustomUrl) setting.aiUrl.trim() else defaultUrl

    private fun AiProviderSpec.requestModel(setting: AiSetting): String =
        setting.model.trim().ifBlank { defaultModel }

    private fun AiProviderSpec.requestBody(model: String, messages: JsonArray): JsonObject =
        when (apiStyle) {
            AiProviderCatalog.API_STYLE_RESPONSES -> JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", AI_TEMPERATURE)
                add("input", messages)
            }
            AiProviderCatalog.API_STYLE_META -> JsonObject().apply {
                addProperty("q", messages.joinToString("\n") { it.asJsonObjectOrNull()?.string("content").orEmpty() })
                addProperty("model", model)
                addProperty("format", "simple")
                addProperty("scope", "ducument")
            }
            else -> JsonObject().apply {
                addProperty("model", model)
                addProperty("temperature", AI_TEMPERATURE)
                add("messages", messages)
            }
        }

    private fun aiContent(raw: String): List<String> {
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val choices = obj.get("choices")
        if (choices != null && choices.isJsonArray) {
            return choices.asJsonArray.mapNotNull { choice ->
                val choiceObj = choice.asJsonObjectOrNull() ?: return@mapNotNull null
                val message = choiceObj.get("message")?.asJsonObjectOrNull()
                message?.string("content") ?: choiceObj.string("text")
            }
        }
        val outputText = obj.string("output_text")
        if (outputText.isNotBlank()) return listOf(outputText)
        val outputTexts = mutableListOf<String>()
        obj.get("output")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { outputItem ->
            val outputObj = outputItem.asJsonObjectOrNull() ?: return@forEach
            outputObj.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { contentItem ->
                val contentObj = contentItem.asJsonObjectOrNull() ?: return@forEach
                val text = contentObj.string("text").ifBlank { contentObj.string("content") }
                if (text.isNotBlank()) outputTexts.add(text)
            }
        }
        // Responses API may split one answer across multiple output_text content items.
        // They are chunks of the same answer, not alternative choices, so preserve all of them.
        if (outputTexts.isNotEmpty()) return listOf(outputTexts.joinToString(""))
        return listOfNotNull(
            obj.string("answer").takeIf { it.isNotBlank() },
            obj.string("content").takeIf { it.isNotBlank() },
        )
    }
}

data class AiProviderSpec(
    val type: String,
    val label: String,
    val defaultUrl: String = "",
    val defaultModel: String = "",
    val apiStyle: String = AiProviderCatalog.API_STYLE_CHAT,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    val requiresCustomUrl: Boolean get() = defaultUrl.isBlank()
    val hasDefaultModel: Boolean get() = defaultModel.isNotBlank()
    val requiresModel: Boolean get() = defaultModel.isBlank()
}

object AiProviderCatalog {
    const val API_STYLE_CHAT = "chat"
    const val API_STYLE_RESPONSES = "responses"
    const val API_STYLE_META = "meta"

    val providers: List<AiProviderSpec> = listOf(
        AiProviderSpec(
            type = "CHATGLM",
            label = "ChatGLM",
            defaultUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            defaultModel = "glm-4",
        ),
        AiProviderSpec(
            type = "XINGHUO",
            label = "讯飞星火",
            defaultUrl = "https://spark-api-open.xf-yun.com/v1/chat/completions",
            defaultModel = "generalv3.5",
        ),
        AiProviderSpec(
            type = "TONGYI",
            label = "通义千问",
            defaultUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            defaultModel = "qwen-plus-latest",
        ),
        AiProviderSpec(
            type = "DOUBAO",
            label = "豆包",
            defaultUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        ),
        AiProviderSpec(
            type = "OPENAI",
            label = "OpenAI",
            defaultUrl = "https://api.openai.com/v1/responses",
            apiStyle = API_STYLE_RESPONSES,
        ),
        AiProviderSpec(
            type = "DEEPSEEK",
            label = "DeepSeek",
            defaultUrl = "https://api.deepseek.com/chat/completions",
            defaultModel = "deepseek-chat",
        ),
        AiProviderSpec(
            type = "METAAI",
            label = "MetaAI",
            defaultUrl = "https://metaso.cn/api/v1/chat/completions",
            defaultModel = "fast",
            apiStyle = API_STYLE_META,
            extraHeaders = mapOf(
                "User-Agent" to "Apifox/1.0.0 (https://apifox.com)",
                "Accept" to "*/*",
                "Host" to "metaso.cn",
                "Connection" to "keep-alive",
            ),
        ),
        AiProviderSpec(
            type = "SILICON",
            label = "Silicon",
            defaultUrl = "https://api.siliconflow.cn/v1/chat/completions",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
        ),
        AiProviderSpec(
            type = "OTHER",
            label = "其他",
        ),
    )

    fun spec(type: String): AiProviderSpec =
        providers.firstOrNull { it.type.equals(type, ignoreCase = true) } ?: providers.last()
}

fun interface AnswerProviderFactory {
    fun provider(mode: AnswerMode): AnswerProvider?
}

class CompositeAnswerProviderFactory(
    private val builtIn: AnswerProvider,
    private val hostAi: AnswerProvider,
    private val external: AnswerProvider,
) : AnswerProviderFactory {
    override fun provider(mode: AnswerMode): AnswerProvider? = when (mode) {
        AnswerMode.DISABLED -> null
        AnswerMode.HOST_AI -> hostAi
        AnswerMode.EXTERNAL_QUESTION_BANK -> external
        AnswerMode.XUEXITONG_BUILT_IN -> builtIn
    }
}

interface AnswerHttpClient {
    fun postJson(
        url: String,
        body: String,
        bearerToken: String,
        timeoutMillis: Int,
        headers: Map<String, String>,
    ): String

    fun get(
        url: String,
        timeoutMillis: Int,
        headers: Map<String, String>,
    ): String
}

class UrlConnectionAnswerHttpClient : AnswerHttpClient {
    override fun postJson(
        url: String,
        body: String,
        bearerToken: String,
        timeoutMillis: Int,
        headers: Map<String, String>,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    override fun get(
        url: String,
        timeoutMillis: Int,
        headers: Map<String, String>,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }
}

internal fun AnswerHttpClient.postJson(url: String, body: String, timeoutMillis: Int): String =
    postJson(url, body, bearerToken = "", timeoutMillis = timeoutMillis, headers = emptyMap())

internal fun RunTaskResult.answerList(): List<String> =
    raw.answerList().ifEmpty {
        raw.string("answer").takeIf { it.isNotBlank() }?.let(::parseAnswerText).orEmpty()
    }

internal fun JsonObject.answerList(): List<String> =
    jsonStrings(get("answers")).ifEmpty { jsonStrings(get("answer")) }

internal fun parseAnswerText(raw: String): List<String> {
    val cleaned = normalizeJsonAnswer(raw)
    return runCatching {
        val parsed = JsonParser.parseString(cleaned)
        when {
            parsed.isJsonArray -> jsonStrings(parsed)
            parsed.isJsonObject -> parsed.asJsonObject.answerList()
            else -> splitAnswers(cleaned)
        }
    }.getOrDefault(splitAnswers(cleaned))
}

internal fun parseStrictJsonAnswers(raw: String): List<String>? {
    val cleaned = normalizeJsonAnswer(raw)
    return runCatching {
        val parsed = JsonParser.parseString(cleaned)
        when {
            parsed.isJsonArray -> jsonStrings(parsed)
            parsed.isJsonObject -> parsed.asJsonObject.answerList()
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

internal fun normalizeJsonAnswer(raw: String): String {
    var s = raw.trim()
    s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    while (true) {
        val lower = s.lowercase()
        val start = lower.indexOf("<think>")
        if (start < 0) break
        val end = lower.indexOf("</think>", start)
        s = if (end < 0) {
            s.substring(0, start).trim()
        } else {
            (s.substring(0, start) + " " + s.substring(end + "</think>".length)).trim()
        }
    }
    val left = s.indexOf('[')
    val right = s.lastIndexOf(']')
    return if (left >= 0 && right > left) s.substring(left, right + 1).trim() else s
}

internal fun splitAnswers(raw: String): List<String> =
    raw.split('\n', ',', '\uFF0C').map { it.trim() }.filter { it.isNotBlank() }

internal fun jsonStrings(element: JsonElement?): List<String> = when {
    element == null || element.isJsonNull -> emptyList()
    element.isJsonArray -> element.asJsonArray.mapNotNull { it.asStringOrNull() }
    element.isJsonObject -> element.asJsonObject.answerList()
    else -> element.asStringOrNull()?.let(::splitAnswers).orEmpty()
}

internal fun JsonElement.answersFromData(): List<String> = when {
    isJsonObject -> asJsonObject.answerList()
    isJsonArray -> jsonStrings(this)
    else -> asStringOrNull()?.splitByLongest("###", "#", "\n", "---", ",").orEmpty()
}

internal fun orderedOptionTexts(element: JsonElement?): List<String> {
    if (element == null || element.isJsonNull) return emptyList()
    if (element.isJsonArray) return jsonStrings(element)
    if (!element.isJsonObject) return emptyList()
    val obj = element.asJsonObject
    return optionKeys.mapNotNull { key -> obj.get(key)?.asStringOrNull() }
}

internal fun bankQuestion(question: JsonObject, fallbackPrompt: String = ""): BankQuestion =
    BankQuestion(
        type = question.string("type").ifBlank { question.string("typeCode") },
        content = removeLeadingQuestionLabel(
            question.string("content").ifBlank { question.string("prompt") }.ifBlank { fallbackPrompt },
        ),
        options = orderedOptionTexts(question.get("options")),
    )

internal fun String.splitByLongest(vararg delimiters: String): List<String> =
    delimiters
        .map { delimiter -> split(delimiter).map { it.cleanBankAnswerToken() }.filter { it.isNotBlank() } }
        .maxByOrNull { it.size }
        .orEmpty()

internal fun String.cleanBankAnswerToken(): String =
    trim().trim { it == '[' || it == ']' || it == '"' || it == '\'' || it == ' ' || it == '\t' }

internal fun List<String>.mapLettersToOptions(options: List<String>): List<String> =
    map { answer ->
        when (answer.trim().uppercase()) {
            "A" -> options.getOrNull(0) ?: answer
            "B" -> options.getOrNull(1) ?: answer
            "C" -> options.getOrNull(2) ?: answer
            "D" -> options.getOrNull(3) ?: answer
            "E" -> options.getOrNull(4) ?: answer
            "F" -> options.getOrNull(5) ?: answer
            else -> answer.trim()
        }
    }.filter { it.isNotBlank() }

internal fun removeLeadingQuestionLabel(raw: String): String =
    raw.replace(Regex("""(?m)^\s*\d+\.(?:[【\[][^\]】]+[\]】]|\s*[^\s【\[]+)\s*"""), "").trim()

internal fun yanxiType(type: String): String =
    when (questionTypeLabel(JsonObject().apply { addProperty("type", type) })) {
        "单选题" -> "single"
        "多选题" -> "multiple"
        "判断题" -> "judgement"
        "简答题" -> "completion"
        else -> type
    }

internal fun String.urlEncode(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name())

internal fun List<String>.toJsonArrayString(): String =
    JsonArray().also { arr -> forEach { arr.add(it) } }.toString()

private fun String.asJsonObjectOrNull(): JsonObject? =
    runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()

private fun apifoxHeaders(vararg extra: Pair<String, String>): Map<String, String> =
    buildMap {
        put("User-Agent", "Apifox/1.0.0 (https://apifox.com)")
        put("Accept", "*/*")
        put("Connection", "keep-alive")
        extra.forEach { (key, value) -> put(key, value) }
    }

internal fun questionProblem(request: AnswerRequest): String {
    val q = request.question
    val type = questionTypeLabel(q)
    val content = q.string("content").ifBlank { q.string("prompt") }.ifBlank { request.prompt }
    return buildString {
        append("题目类型：").append(type).append('\n')
        append("题目内容：\n").append(content).append('\n')
        val options = orderedOptionTexts(q.get("options"))
        if (options.isNotEmpty()) {
            optionKeys.take(options.size).zip(options).forEach { (key, value) ->
                append(key).append('.').append(value).append('\n')
            }
        }
    }
}

internal fun answerSystemPrompt(question: JsonObject): String {
    val type = questionTypeLabel(question)
    val base = "最终只输出合法 JSON 数组，不要解释、不要题目、不要 Markdown。"
    return when (type) {
        "单选题" -> "$base 数组里只能有一个选项内容，例如：[\"选项内容\"]。不能输出 A/B/C/D。不会也必须选一个。"
        "多选题" -> "$base 数组里放所有选中的选项内容，例如：[\"选项内容1\",\"选项内容2\"]。不能输出 A/B/C/D。"
        "判断题" -> "$base 只回答题目中对应的“正确”或“错误”，例如：[\"正确\"]。"
        "填空题" -> "$base 按空的顺序输出答案，例如：[\"答案1\",\"答案2\"]。"
        "名词解释", "简答题" -> "$base 数组里只放一个完整答案，例如：[\"答案\"]。"
        "论述题" -> "$base 数组里只放一个完整答案，例如：[\"答案\"]，答案内容尽量完整。"
        "连线题" -> "$base 使用“左侧->右侧”格式，例如：[\"A->B\",\"C->D\"]。"
        else -> "$base 数组里只放答案内容，例如：[\"答案\"]。"
    }
}

internal fun questionTypeLabel(question: JsonObject): String {
    val raw = question.string("type").ifBlank { question.string("typeCode") }.trim()
    return when (raw) {
        "0", "single", "singleChoice", "单选", "单选题" -> "单选题"
        "1", "multiple", "multi", "mulChoice", "multiChoice", "多选", "多选题" -> "多选题"
        "2", "fill", "blank", "completion", "填空", "填空题" -> "填空题"
        "3", "judge", "judgement", "truefalse", "判断", "判断题" -> "判断题"
        "4", "short", "shortAnswer", "简答", "简答题" -> "简答题"
        "5", "term", "termExplanation", "名词解释" -> "名词解释"
        "6", "essay", "论述", "论述题" -> "论述题"
        "8", "other", "其它", "其他" -> "其它"
        "11", "matching", "连线", "连线题" -> "连线题"
        else -> raw.ifBlank { "其它" }
    }
}

internal fun cleanPlainAiText(raw: String): String =
    raw.trim()
        .removePrefix("```text")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

internal fun JsonObject.string(key: String): String =
    runCatching { get(key)?.asString ?: "" }.getOrDefault("")

private fun JsonElement.asStringOrNull(): String? =
    runCatching { asString }.getOrNull()?.takeIf { it.isNotBlank() }

private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
    runCatching { if (isJsonObject) asJsonObject else null }.getOrNull()

private fun JsonElement.asIntOrNull(): Int? =
    runCatching { asInt }.getOrNull()

private val optionKeys = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "1", "2", "3", "4", "5", "6")
