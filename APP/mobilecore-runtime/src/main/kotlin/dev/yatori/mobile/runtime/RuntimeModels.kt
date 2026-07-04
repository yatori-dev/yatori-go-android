package dev.yatori.mobile.runtime

import dev.yatori.mobile.api.dto.AccountInput
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import com.google.gson.JsonObject

data class StoredSession(
    val platform: String,
    val account: String,
    val session: SessionData,
    val updatedAt: Long,
)

data class StoredCredential(
    val platform: String,
    val account: String,
    val credential: AccountInput,
    val updatedAt: Long,
)

data class StoredActionState(
    val platform: String,
    val account: String,
    val taskId: String,
    val scope: String,
    val task: TaskItem,
    val status: String,
    val raw: JsonObject = JsonObject(),
    val intervalSeconds: Int = 0,
    val progress: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long,
)

data class LogCursorState(
    val cursor: String,
    val updatedAt: Long,
)
