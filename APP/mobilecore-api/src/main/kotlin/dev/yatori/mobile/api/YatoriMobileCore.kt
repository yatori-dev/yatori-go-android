package dev.yatori.mobile.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.yatori.mobile.api.dto.*
import dev.yatori.mobile.api.internal.EnvelopeParser
import dev.yatori.mobile.mobilecore.Mobilecore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YatoriMobileCore {

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    suspend fun init(baseDir: String): InitResult = io {
        EnvelopeParser.parse(Mobilecore.init(baseDir)) {
            gson.fromJson(it.toString(), InitResult::class.java)
        }
    }

    suspend fun healthCheck(): HealthInfo = io {
        EnvelopeParser.parse(Mobilecore.healthCheck()) {
            gson.fromJson(it.toString(), HealthInfo::class.java)
        }
    }

    suspend fun setConfig(config: MobileConfig) = io {
        EnvelopeParser.parseVoid(Mobilecore.setConfig(gson.toJson(config)))
    }

    suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) = io {
        EnvelopeParser.parseVoid(Mobilecore.setXuexitongFontTables(glyfJson, cmapJson))
    }

    suspend fun getConfig(): MobileConfig = io {
        EnvelopeParser.parse(Mobilecore.getConfig()) {
            gson.fromJson(it.toString(), MobileConfig::class.java)
        }
    }

    suspend fun getConfigSchema(): List<ConfigField> = io {
        EnvelopeParser.parse(Mobilecore.getConfigSchema()) { data ->
            val type = object : TypeToken<List<ConfigField>>() {}.type
            gson.fromJson(data.getAsJsonArray("fields"), type)
        }
    }

    suspend fun startLogin(platform: Platform, account: AccountInput): LoginResult = io {
        EnvelopeParser.parse(Mobilecore.startLogin(platform.id, gson.toJson(account))) { data ->
            when (val status = data.get("status").asString) {
                "done" -> LoginResult.Done(gson.fromJson(data.getAsJsonObject("session").toString(), SessionData::class.java))
                "challenge" -> LoginResult.Challenge(
                    data.get("taskId").asString,
                    gson.fromJson(data.getAsJsonObject("challenge").toString(), OcrChallenge::class.java)
                )
                else -> throw CoreException("unexpected login status: $status")
            }
        }
    }

    suspend fun continueLogin(taskId: String, result: OcrResult): LoginResult = io {
        EnvelopeParser.parse(Mobilecore.continueLogin(taskId, gson.toJson(result))) { data ->
            when (val status = data.get("status").asString) {
                "done" -> LoginResult.Done(gson.fromJson(data.getAsJsonObject("session").toString(), SessionData::class.java))
                "challenge" -> LoginResult.Challenge(
                    data.get("taskId").asString,
                    gson.fromJson(data.getAsJsonObject("challenge").toString(), OcrChallenge::class.java)
                )
                else -> throw CoreException("unexpected login status: $status")
            }
        }
    }

    suspend fun cancelLogin(taskId: String) = io { EnvelopeParser.parseVoid(Mobilecore.cancelLogin(taskId)) }

    suspend fun getCourses(session: SessionData): List<CourseItem> = io {
        EnvelopeParser.parse(Mobilecore.getCourses(gson.toJson(session))) { data ->
            val type = object : TypeToken<List<CourseItem>>() {}.type
            gson.fromJson(platformFilledArray(data.getAsJsonArray("courses"), session.platform), type)
        }
    }

    suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = io {
        EnvelopeParser.parse(Mobilecore.getCourseDetail(gson.toJson(session), gson.toJson(course))) { data ->
            val type = object : TypeToken<List<CourseItem>>() {}.type
            gson.fromJson(platformFilledArray(data.getAsJsonArray("items"), session.platform), type)
        }
    }

    suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = io {
        EnvelopeParser.parse(Mobilecore.getTasks(gson.toJson(session), gson.toJson(course))) { data ->
            val type = object : TypeToken<List<TaskItem>>() {}.type
            gson.fromJson(platformFilledArray(data.getAsJsonArray("tasks"), session.platform), type)
        }
    }

    suspend fun runTask(
        session: SessionData,
        task: TaskItem,
        options: Map<String, Any> = emptyMap(),
    ): RunTaskResult = io {
        val taskJson = if (options.isEmpty()) gson.toJson(task)
        else {
            val obj = gson.toJsonTree(task).asJsonObject
            obj.add("options", gson.toJsonTree(options))
            obj.toString()
        }
        EnvelopeParser.parse(Mobilecore.runTask(gson.toJson(session), taskJson)) {
            gson.fromJson(it.toString(), RunTaskResult::class.java)
        }
    }

    suspend fun getLogs(cursor: String = ""): LogResult = io {
        EnvelopeParser.parse(Mobilecore.getLogs(cursor)) {
            gson.fromJson(it.toString(), LogResult::class.java)
        }
    }

    suspend fun clearLogs() = io { EnvelopeParser.parseVoid(Mobilecore.clearLogs()) }
    suspend fun setLogLevel(level: String) = io { EnvelopeParser.parseVoid(Mobilecore.setLogLevel(level)) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun platformFilledArray(array: JsonArray, fallbackPlatform: String): JsonArray {
        val out = JsonArray()
        array.forEach { item -> out.add(item.withPlatformFallback(fallbackPlatform)) }
        return out
    }

    private fun JsonElement.withPlatformFallback(fallbackPlatform: String): JsonElement {
        if (!isJsonObject) return this
        val obj = deepCopy().asJsonObject
        if (obj.string("platform").isBlank()) {
            obj.addProperty("platform", fallbackPlatform)
        }
        return obj
    }

    private fun JsonObject.string(key: String): String =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")
}
