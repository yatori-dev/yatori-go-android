package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.LoginResult
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import dev.yatori.mobile.runtime.StoredActionState
import dev.yatori.mobile.runtime.YatoriCoreRepository

data class TtcdwTickInput(
    val progress: Double,
    val realSubmit: Boolean = false,
    val finish: Boolean? = null,
)

data class TtcdwTickerInput(
    val playedStart: Double = 0.0,
    val playedEnd: Double,
    val realSubmit: Boolean = false,
    val tickerTime: Long? = null,
)

data class PercentTickInput(
    val progress: Int,
    val finish: Boolean = false,
)

data class WelearnTickInput(
    val sessionTime: Int,
    val totalTime: Int = sessionTime,
    val finish: Boolean = false,
    val progress: Int = 100,
    val cstatus: String = "completed",
)

data class CqieTickInput(
    val startPos: Int,
    val stopPos: Int,
    val maxPos: Int = stopPos,
    val finish: Boolean = false,
)

data class QingshuxuetangTickInput(
    val position: Int,
    val finish: Boolean = false,
)

data class EnaeaTickInput(
    val studyTime: Long? = null,
    val fast: Boolean = false,
    val dryRun: Boolean = false,
)

data class XuexitongMediaTickInput(
    val progress: Int,
    val realSubmit: Boolean = true,
    val isDrag: Int = 0,
)

data class YinghuaTickInput(
    val studyTime: Int,
)

data class XuexitongBbsSubmitInput(
    val content: String,
    val web: Boolean = false,
    val realSubmit: Boolean = true,
)

data class KeepAliveResult(
    val result: RunTaskResult,
    val loginResult: LoginResult? = null,
    val relogin: Boolean = loginResult is LoginResult.Done,
)

/**
 * Platform-specific host scheduler primitives.
 *
 * This class owns Android-side action sequencing and state persistence. It intentionally
 * does not sleep or loop by itself; callers such as WorkManager / foreground service decide
 * when to call the next tick.
 */
class PlatformActionScheduler(
    private val repository: YatoriCoreRepository,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun startHaiqikejiProgress(
        session: SessionData,
        task: TaskItem,
        initialProgress: Double? = null,
    ): StoredActionState {
        require(session.platform == "haiqikeji") { "haiqikeji progress requires a haiqikeji session" }
        val progress = initialProgress ?: jsonDouble(getHaiqikejiProgress(session, task).raw, "progress")
        val result = repository.runTask(session, task, mapOf("action" to "start"))
        return saveState(session, task, "haiqikeji-progress", result, progress = progress, intervalSeconds = 30)
    }

    suspend fun tickHaiqikejiProgress(
        session: SessionData,
        taskId: String,
        input: PercentTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "haiqikeji-progress")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to if (input.finish) "end" else "submit",
            "progress" to input.progress,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "haiqikeji-progress", result, progress = input.progress.toDouble(), intervalSeconds = 30)
        return result
    }

    suspend fun finishHaiqikejiProgress(session: SessionData, taskId: String): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "haiqikeji-progress")
        val result = repository.runTask(session, state.task, state.raw.toKotlinMap() + mapOf("action" to "end"))
        saveState(session, state.task, "haiqikeji-progress", result, progress = state.progress, intervalSeconds = 30)
        return result
    }

    suspend fun getHaiqikejiProgress(session: SessionData, task: TaskItem): RunTaskResult =
        repository.runTask(session, task, mapOf("action" to "getProgress"))

    suspend fun getHaiqikejiProgress(session: SessionData, taskId: String): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "haiqikeji-progress")
        return getHaiqikejiProgress(session, state.task)
    }

    suspend fun startWelearnProgress(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "welearn") { "welearn progress requires a welearn session" }
        val result = repository.runTask(session, task, mapOf("action" to "start"))
        return saveState(session, task, "welearn-progress", result, progress = 0.0, intervalSeconds = 60)
    }

    /** Completes a welearn point in one shot (mirrors console mode2 WeLearnCompletePointAction). */
    suspend fun completeWelearnPoint(session: SessionData, task: TaskItem): RunTaskResult {
        require(session.platform == "welearn") { "welearn complete requires a welearn session" }
        return repository.runTask(session, task, mapOf("complete" to true))
    }

    suspend fun tickWelearnProgress(
        session: SessionData,
        taskId: String,
        input: WelearnTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "welearn-progress")
        val options = state.raw.toKotlinMap() + if (input.finish) {
            mapOf(
                "action" to "finalize",
                "progress" to input.progress,
                "cstatus" to input.cstatus,
            )
        } else {
            mapOf(
                "action" to "keep",
                "sessionTime" to input.sessionTime,
                "totalTime" to input.totalTime,
            )
        }
        val result = repository.runTask(session, state.task, options)
        val progress = if (input.finish) {
            input.progress.toDouble()
        } else {
            percent(input.sessionTime, input.totalTime)
        }
        saveState(session, state.task, "welearn-progress", result, progress = progress, intervalSeconds = 60)
        return result
    }

    suspend fun tickEnaeaProgress(
        session: SessionData,
        task: TaskItem,
        input: EnaeaTickInput = EnaeaTickInput(),
    ): RunTaskResult {
        require(session.platform == "enaea") { "enaea progress requires an enaea session" }
        val previous = repository.getActionState(session.platform, session.account, task.id, "enaea-progress")
        val taskForRun = previous?.task ?: task
        val options = buildMap<String, Any> {
            putAll(previous?.raw?.toKotlinMap().orEmpty())
            put("fast", input.fast)
            if (input.dryRun) put("dryRun", true)
            if (input.studyTime != null) put("studyTime", input.studyTime)
        }
        val result = repository.runTask(session, taskForRun, options)
        saveState(
            session = session,
            task = taskForRun,
            scope = "enaea-progress",
            result = result,
            progress = jsonDouble(result.raw, "progress"),
            intervalSeconds = 25,
        )
        return result
    }

    suspend fun startCqieProgress(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "cqie") { "cqie progress requires a cqie session" }
        val result = repository.runTask(session, task, mapOf("action" to "start"))
        return saveState(
            session = session,
            task = task,
            scope = "cqie-progress",
            result = result,
            progress = jsonDouble(result.raw, "maxCurrentPos"),
            intervalSeconds = 3,
        )
    }

    suspend fun tickCqieProgress(
        session: SessionData,
        taskId: String,
        input: CqieTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "cqie-progress")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to "submit",
            "startPos" to input.startPos,
            "stopPos" to input.stopPos,
            "maxPos" to input.maxPos,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "cqie-progress", result, progress = input.maxPos.toDouble(), intervalSeconds = 3)
        return result
    }

    suspend fun finishCqieProgress(
        session: SessionData,
        taskId: String,
        position: Int,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "cqie-progress")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to "end",
            "startPos" to position,
            "stopPos" to position,
            "maxPos" to position,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "cqie-progress", result, progress = position.toDouble(), intervalSeconds = 3)
        return result
    }

    suspend fun getCqieProgress(session: SessionData, taskId: String): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "cqie-progress")
        val result = repository.runTask(session, state.task, state.raw.toKotlinMap() + mapOf("action" to "getProgress"))
        saveState(
            session,
            state.task,
            "cqie-progress",
            result,
            progress = jsonDouble(result.raw, "progress"),
            intervalSeconds = 3,
        )
        return result
    }

    suspend fun startQingshuxuetangProgress(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "qingshuxuetang") { "qingshuxuetang progress requires a qingshuxuetang session" }
        val result = repository.runTask(session, task, mapOf("action" to "start"))
        return saveState(session, task, "qingshuxuetang-progress", result, progress = 0.0, intervalSeconds = 60)
    }

    suspend fun tickQingshuxuetangProgress(
        session: SessionData,
        taskId: String,
        input: QingshuxuetangTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "qingshuxuetang-progress")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to if (input.finish) "end" else "continue",
            "position" to input.position,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "qingshuxuetang-progress", result, progress = input.position.toDouble(), intervalSeconds = 60)
        return result
    }

    suspend fun prepareTtcdwProgress(session: SessionData, task: TaskItem): StoredActionState {
        val result = repository.runTask(session, task, mapOf("action" to "prepare"))
        return saveState(session, task, "ttcdw-progress", result, progress = jsonDouble(result.raw, "playProgress"), intervalSeconds = 30)
    }

    suspend fun tickTtcdwProgress(
        session: SessionData,
        taskId: String,
        input: TtcdwTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "ttcdw-progress")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to "tick",
            "progress" to input.progress,
            "playProgress" to input.progress,
            "currentTime" to input.progress,
            "realSubmit" to input.realSubmit,
        ) + input.finish?.let { mapOf("finish" to it) }.orEmpty()
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "ttcdw-progress", result, progress = input.progress, intervalSeconds = 30)
        return result
    }

    suspend fun prepareTtcdwTicker(
        session: SessionData,
        task: TaskItem,
        input: TtcdwTickerInput,
    ): StoredActionState {
        require(session.platform == "ttcdw") { "ttcdw ticker requires a ttcdw session" }
        val result = repository.runTask(session, task, ttcdwTickerOptions("desPrepare", input))
        return saveState(session, task, "ttcdw-ticker", result, progress = input.playedEnd, intervalSeconds = 30)
    }

    suspend fun tickTtcdwTicker(
        session: SessionData,
        taskId: String,
        input: TtcdwTickerInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "ttcdw-ticker")
        val options = state.raw.toKotlinMap() + ttcdwTickerOptions("desTick", input)
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "ttcdw-ticker", result, progress = input.playedEnd, intervalSeconds = 30)
        return result
    }

    suspend fun tickYinghuaKeepAlive(
        session: SessionData,
        reloginOnExpired: Boolean = false,
    ): KeepAliveResult {
        require(session.platform == "yinghua") { "yinghua keepAlive requires a yinghua session" }
        val task = TaskItem(
            id = "keepAlive",
            name = "keepAlive",
            type = "keepAlive",
            platform = "yinghua",
            raw = session.extra.deepCopy(),
        )
        val result = repository.runTask(session, task, mapOf("action" to "keepAlive"))
        val expired = jsonBool(result.raw, "expired")
        saveState(session, task, "yinghua-keepalive", result, progress = 0.0, intervalSeconds = 300)

        if (!expired || !reloginOnExpired) return KeepAliveResult(result, relogin = false)

        val credential = repository.getCredential(session.platform, session.account)
            ?: return KeepAliveResult(result, relogin = false)
        val loginResult = repository.startLoginAndPersist(Platform.YINGHUA, credential)
        return KeepAliveResult(result, loginResult = loginResult)
    }

    suspend fun startYinghuaProgress(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "yinghua") { "yinghua progress requires a yinghua session" }
        val result = repository.runTask(session, task, mapOf("action" to "state"))
        return saveState(session, task, "yinghua-progress", result, progress = jsonDouble(task.raw, "viewedDuration"), intervalSeconds = 5)
    }

    suspend fun tickYinghuaProgress(
        session: SessionData,
        taskId: String,
        input: YinghuaTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "yinghua-progress")
        val options = state.raw.toKotlinMap() + mapOf("studyTime" to input.studyTime)
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "yinghua-progress", result, progress = input.studyTime.toDouble(), intervalSeconds = 5)
        return result
    }

    /**
     * Mirrors console videoBadRedAction exactly: submit the recorded viewedDuration once with
     * studyId="0". Do not call the video-state endpoint first, because that allocates a new study
     * session and changes the request used by the original red-removal flow.
     */
    suspend fun submitYinghuaRedProgress(
        session: SessionData,
        task: TaskItem,
        input: YinghuaTickInput,
    ): RunTaskResult {
        require(session.platform == "yinghua") { "yinghua red progress requires a yinghua session" }
        val redTask = task.copy(raw = task.raw.deepCopy().apply { addProperty("studyId", "0") })
        val result = repository.runTask(session, redTask, mapOf("studyTime" to input.studyTime))
        saveState(session, redTask, "yinghua-progress", result, progress = input.studyTime.toDouble(), intervalSeconds = 8)
        return result
    }

    suspend fun prepareXuexitongAudio(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "xuexitong") { "xuexitong audio requires a xuexitong session" }
        val result = repository.runTask(session, task, mapOf("action" to "audioPrepare"))
        return saveState(session, task, "xuexitong-audio", result, progress = 0.0, intervalSeconds = 58)
    }

    suspend fun tickXuexitongAudio(
        session: SessionData,
        taskId: String,
        input: XuexitongMediaTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "xuexitong-audio")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to "audioTick",
            "playingTime" to input.progress,
            "progress" to input.progress,
            "currentTime" to input.progress,
            "realSubmit" to input.realSubmit,
            "isdrag" to input.isDrag,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "xuexitong-audio", result, progress = input.progress.toDouble(), intervalSeconds = 58)
        return result
    }

    suspend fun prepareXuexitongVideo(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "xuexitong") { "xuexitong video requires a xuexitong session" }
        val result = repository.runTask(session, task, mapOf("action" to "videoPrepare"))
        return saveState(session, task, "xuexitong-video", result, progress = 0.0, intervalSeconds = 58)
    }

    suspend fun tickXuexitongVideo(
        session: SessionData,
        taskId: String,
        input: XuexitongMediaTickInput,
    ): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "xuexitong-video")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to "videoTick",
            "playingTime" to input.progress,
            "progress" to input.progress,
            "currentTime" to input.progress,
            "realSubmit" to input.realSubmit,
            "isdrag" to input.isDrag,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "xuexitong-video", result, progress = input.progress.toDouble(), intervalSeconds = 58)
        return result
    }

    suspend fun prepareXuexitongLive(session: SessionData, task: TaskItem): StoredActionState {
        require(session.platform == "xuexitong") { "xuexitong live requires a xuexitong session" }
        val result = repository.runTask(session, task, mapOf("action" to "livePrepare"))
        return saveState(session, task, "xuexitong-live", result, progress = jsonDouble(result.raw, "percent"), intervalSeconds = 30)
    }

    suspend fun tickXuexitongLive(session: SessionData, taskId: String, realSubmit: Boolean = true): RunTaskResult {
        val state = requireState(session.platform, session.account, taskId, "xuexitong-live")
        val options = state.raw.toKotlinMap() + mapOf(
            "action" to "liveTick",
            "realSubmit" to realSubmit,
        )
        val result = repository.runTask(session, state.task, options)
        saveState(session, state.task, "xuexitong-live", result, progress = jsonDouble(result.raw, "percent"), intervalSeconds = 30)
        return result
    }

    suspend fun runXuexitongDocument(session: SessionData, task: TaskItem, action: String = xuexitongDocumentAction(task)): RunTaskResult {
        require(session.platform == "xuexitong") { "xuexitong document requires a xuexitong session" }
        val prepareAction = if (action == "read" || action == "readv2") "readPrepare" else "documentPrepare"
        val prepared = repository.runTask(session, task, mapOf("action" to prepareAction))
        val state = saveState(session, task, "xuexitong-document", prepared, progress = 0.0, intervalSeconds = 0)
        val result = repository.runTask(session, state.task, state.raw.toKotlinMap() + mapOf("action" to action, "realSubmit" to true))
        saveState(session, state.task, "xuexitong-document", result, progress = 100.0, intervalSeconds = 0)
        return result
    }

    suspend fun runXuexitongHyperlink(session: SessionData, task: TaskItem): RunTaskResult {
        require(session.platform == "xuexitong") { "xuexitong hyperlink requires a xuexitong session" }
        val prepared = repository.runTask(session, task, mapOf("action" to "hyperlinkPrepare"))
        val state = saveState(session, task, "xuexitong-hyperlink", prepared, progress = 0.0, intervalSeconds = 0)
        val result = repository.runTask(session, state.task, state.raw.toKotlinMap() + mapOf("action" to "hyperlink", "realSubmit" to true))
        saveState(session, state.task, "xuexitong-hyperlink", result, progress = 100.0, intervalSeconds = 0)
        return result
    }

    suspend fun prepareXuexitongBbs(session: SessionData, task: TaskItem, web: Boolean = false): StoredActionState {
        require(session.platform == "xuexitong") { "xuexitong bbs requires a xuexitong session" }
        val action = if (web) "bbsWebPrepare" else "bbsPrepare"
        val scope = if (web) "xuexitong-bbs-web" else "xuexitong-bbs"
        val result = repository.runTask(session, task, mapOf("action" to action))
        return saveState(session, task, scope, result, progress = 0.0, intervalSeconds = 0)
    }

    suspend fun submitXuexitongBbs(
        session: SessionData,
        taskId: String,
        input: XuexitongBbsSubmitInput,
    ): RunTaskResult {
        val scope = if (input.web) "xuexitong-bbs-web" else "xuexitong-bbs"
        val state = requireState(session.platform, session.account, taskId, scope)
        val action = if (input.web) "bbsWeb" else "bbs"
        val result = repository.runTask(
            session,
            state.task,
            state.raw.toKotlinMap() + mapOf(
                "action" to action,
                "content" to input.content,
                "realSubmit" to input.realSubmit,
            ),
        )
        saveState(session, state.task, scope, result, progress = 100.0, intervalSeconds = 0)
        return result
    }

    suspend fun tickXuexitongMonitor(session: SessionData, realSubmit: Boolean = true): RunTaskResult {
        require(session.platform == "xuexitong") { "xuexitong monitor requires a xuexitong session" }
        val task = TaskItem("monitor", name = "monitor", type = "monitor", platform = "xuexitong", raw = session.extra.deepCopy())
        val result = repository.runTask(session, task, mapOf("action" to "monitor", "realSubmit" to realSubmit))
        saveState(session, task, "xuexitong-monitor", result, progress = 0.0, intervalSeconds = 300)
        return result
    }

    fun loadState(platform: String, account: String, taskId: String, scope: String): StoredActionState? =
        repository.getActionState(platform, account, taskId, scope)

    private fun requireState(platform: String, account: String, taskId: String, scope: String): StoredActionState =
        loadState(platform, account, taskId, scope)
            ?: error("missing action state for $platform/$taskId ($scope); call prepare first")

    private fun saveState(
        session: SessionData,
        task: TaskItem,
        scope: String,
        result: RunTaskResult,
        progress: Double,
        intervalSeconds: Int? = null,
    ): StoredActionState {
        val previous = repository.getActionState(session.platform, session.account, task.id, scope)
        val time = now()
        val state = StoredActionState(
            platform = session.platform,
            account = session.account,
            taskId = task.id,
            scope = scope,
            task = task.copy(raw = mergeRaw(task.raw, result.raw)),
            status = result.status,
            raw = result.raw,
            intervalSeconds = intervalSeconds ?: jsonInt(result.raw, "intervalSeconds"),
            progress = progress,
            createdAt = previous?.createdAt ?: time,
            updatedAt = time,
        )
        repository.saveActionState(state)
        return state
    }

    private fun mergeRaw(base: JsonObject, extra: JsonObject): JsonObject =
        base.deepCopy().also { out ->
            for ((key, value) in extra.entrySet()) out.add(key, value)
        }

    private fun JsonObject.toKotlinMap(): Map<String, Any> =
        entrySet().associate { (key, value) -> key to value.toKotlinValue() }

    private fun JsonElement.toKotlinValue(): Any = when {
        isJsonNull -> ""
        isJsonPrimitive -> {
            val p = asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble
                else -> p.asString
            }
        }
        else -> this
    }

    private fun jsonInt(raw: JsonObject, key: String): Int =
        runCatching { raw.get(key)?.asInt ?: 0 }.getOrDefault(0)

    private fun jsonDouble(raw: JsonObject, key: String): Double =
        runCatching { raw.get(key)?.asDouble ?: 0.0 }.getOrDefault(0.0)

    private fun jsonBool(raw: JsonObject, key: String): Boolean =
        runCatching { raw.get(key)?.asBoolean ?: false }.getOrDefault(false)

    private fun percent(done: Int, total: Int): Double =
        if (total <= 0) 0.0 else ((done.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0)

    private fun ttcdwTickerOptions(action: String, input: TtcdwTickerInput): Map<String, Any> =
        buildMap {
            put("action", action)
            put("playedStart", input.playedStart)
            put("playedEnd", input.playedEnd)
            put("realSubmit", input.realSubmit)
            if (input.tickerTime != null) put("tickerTime", input.tickerTime)
        }

    private fun xuexitongDocumentAction(task: TaskItem): String {
        val ext = runCatching { task.raw.get("extension")?.asString ?: "" }.getOrDefault("")
        return when {
            ext.equals("insertreadV2", true) -> "readv2"
            task.type.equals("read", true) -> "read"
            else -> "document"
        }
    }
}
