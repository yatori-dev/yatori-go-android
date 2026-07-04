package dev.yatori.mobile.api.dto

import com.google.gson.JsonObject

data class InitResult(val baseDir: String, val version: String)

data class HealthInfo(
    val version: String,
    val goVersion: String,
    val initialized: Boolean,
    val configured: Boolean,
)

data class BasicSetting(
    val completionTone: Int = 0,
    val colorLog: Int = 0,
    val logOutFileSw: Int = 0,
    val logLevel: String = "info",
    val logModel: Int = 1,
)

data class EmailInform(
    val sw: Int = 0,
    val smtpHost: String = "",
    val smtpPort: String = "",
    val email: String = "",
    val password: String = "",
)

data class AiSetting(
    val aiType: String = "",
    val aiUrl: String = "",
    val model: String = "",
    @com.google.gson.annotations.SerializedName("API_KEY") val apiKey: String = "",
)

data class ApiQueSetting(
    val url: String? = "",
    val exType: String? = "CUSTOM",
    val exToken: String? = "",
)

data class MobileConfigSetting(
    val basicSetting: BasicSetting = BasicSetting(),
    val emailInform: EmailInform = EmailInform(),
    val aiSetting: AiSetting = AiSetting(),
    val apiQueSetting: ApiQueSetting = ApiQueSetting(),
)

data class MobileConfig(
    val setting: MobileConfigSetting = MobileConfigSetting(),
    val users: List<JsonObject> = emptyList(),
)

data class ConfigField(val path: String, val type: String, val description: String = "")

data class AccountInput(
    val account: String,
    val password: String,
    val url: String = "",
    val extra: JsonObject = JsonObject(),
)

data class OcrChallenge(
    val taskId: String, val platform: String, val type: String,
    val imageBase64: String, val outputCols: Int = 0, val hint: String = "",
)

data class OcrResult(val taskId: String, val type: String = "image_ocr", val text: String)

/** extra is opaque — echo back unchanged. */
data class SessionData(
    val platform: String, val account: String, val token: String = "",
    val cookies: String = "", val extra: JsonObject = JsonObject(),
)

/** raw is opaque — echo back unchanged. */
data class CourseItem(
    val id: String, val name: String = "", val cover: String = "",
    val progress: Double = 0.0, val raw: JsonObject = JsonObject(), val platform: String = "",
)

/** raw is opaque — echo back unchanged. */
data class TaskItem(
    val id: String, val name: String = "", val type: String = "",
    val status: String = "", val progress: Double = 0.0,
    val raw: JsonObject = JsonObject(), val platform: String = "",
)

data class RunTaskResult(
    val platform: String, val taskId: String, val status: String, val message: String = "",
    val raw: JsonObject = JsonObject(),
)

sealed class LoginResult {
    data class Done(val session: SessionData) : LoginResult()
    data class Challenge(val taskId: String, val challenge: OcrChallenge) : LoginResult()
}

data class LogEntry(
    val id: Long, val time: String, val level: String,
    val source: String, val platform: String = "", val message: String,
    val account: String? = "",
)

data class LogResult(
    val nextCursor: String, val oldestCursor: String,
    val truncated: Boolean, val logs: List<LogEntry>,
)
