package com.yatori.android.engine.ai

import com.yatori.android.domain.model.AiProvider
import com.yatori.android.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 答题引擎
 * 等价移植 Go que-core/aiq/AiQuestion.go AggregationAIApi
 * 所有提供商均使用 OpenAI 兼容格式，仅 endpoint 和默认模型不同
 */
@Singleton
class AiAnswerEngine @Inject constructor() {

    // 等价 Go AiSem = make(chan struct, 2)，限制并发 AI 调用数
    private val semaphore = Semaphore(2)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 向 AI 提问，返回 JSON 数组字符串（如 ["答案1","答案2"]） */
    suspend fun ask(settings: AppSettings, messages: List<AiMessage>): String = withContext(Dispatchers.IO) {
        semaphore.acquire()
        try {
            val (url, model) = endpointAndModel(settings)
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply { put("role", m.role); put("content", m.content) })
                    }
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val resp = client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer ${settings.aiApiKey}")
                    .post(body).build()
            ).execute()

            val json = JSONObject(resp.body?.string() ?: return@withContext "")
            // OpenAI 兼容格式：choices[0].message.content
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""
        } finally {
            semaphore.release()
        }
    }

    /** 可用性检测，等价 Go AICheck */
    suspend fun check(settings: AppSettings): Boolean {
        if (settings.aiApiKey.isBlank()) return false
        val result = ask(settings, listOf(AiMessage("user", "请原模原样输出：[\"测试成功\"]")))
        return result.contains("测试成功")
    }

    /** 各提供商的 endpoint 和默认模型，等价 Go switch aiType */
    private fun endpointAndModel(s: AppSettings): Pair<String, String> {
        val model = s.aiModel
        return when (s.aiType) {
            AiProvider.TONGYI   -> "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" to (model.ifBlank { "qwen-plus-latest" })
            AiProvider.DEEPSEEK -> "https://api.deepseek.com/v1/chat/completions"                       to (model.ifBlank { "deepseek-chat" })
            AiProvider.OPENAI   -> "https://api.openai.com/v1/chat/completions"                         to (model.ifBlank { "gpt-4o-mini" })
            AiProvider.SILICON  -> "https://api.siliconflow.cn/v1/chat/completions"                     to (model.ifBlank { "Qwen/Qwen2.5-7B-Instruct" })
            AiProvider.DOUBAO   -> "https://ark.cn-beijing.volces.com/api/v3/chat/completions"           to model  // 豆包必须填模型(接入点ID)
            AiProvider.CHATGLM  -> "https://open.bigmodel.cn/api/paas/v4/chat/completions"              to (model.ifBlank { "glm-4-flash" })
            AiProvider.XINGHUO  -> "https://spark-api-open.xf-yun.com/v1/chat/completions"              to (model.ifBlank { "4.0Ultra" })
            AiProvider.METAAI   -> "https://api.metaso.cn/v1/chat/completions"                          to (model.ifBlank { "meta-default" })
            AiProvider.OTHER    -> s.aiUrl                                                               to model
        }
    }
}

data class AiMessage(val role: String, val content: String)
