package dev.yatori.mobile.app.ui.courses

import dev.yatori.captcha.OcrEngine
import dev.yatori.mobile.api.dto.OcrChallenge
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import dev.yatori.mobile.runtime.operation.CourseTaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

data class PendingTaskChallenge(
    val id: String,
    val session: SessionData,
    val task: TaskItem,
    val retryOptions: Map<String, Any>,
    val challenge: OcrChallenge,
)

sealed interface TaskChallengeOutcome {
    data class Success(val result: RunTaskResult) : TaskChallengeOutcome
    data class Manual(val pending: PendingTaskChallenge) : TaskChallengeOutcome
    data class Error(val message: String) : TaskChallengeOutcome
    data object Cancelled : TaskChallengeOutcome
}

fun interface TaskChallengeProgress {
    fun onAttempt(attempt: Int)
}

fun interface CaptchaRecognizer {
    suspend fun recognizeCaptchaBase64(platformId: String, imageBase64: String, outputCols: Int?): String?
}

class TaskChallengeController(
    private val runner: CourseTaskRunner,
    private val recognizer: CaptchaRecognizer,
) {

    @Volatile private var requestManualAll = false
    private val currentById = mutableMapOf<String, PendingTaskChallenge>()
    private val manualRequestIds = mutableSetOf<String>()

    fun requestManual() {
        requestManualAll = true
    }

    fun requestManual(id: String) {
        synchronized(manualRequestIds) { manualRequestIds.add(id) }
    }

    fun pending(): PendingTaskChallenge? = synchronized(currentById) { currentById.values.firstOrNull() }

    fun clear() {
        requestManualAll = false
        synchronized(manualRequestIds) { manualRequestIds.clear() }
        synchronized(currentById) { currentById.clear() }
    }

    fun clear(id: String) {
        synchronized(manualRequestIds) { manualRequestIds.remove(id) }
        synchronized(currentById) { currentById.remove(id) }
    }

    fun capture(
        session: SessionData,
        task: TaskItem,
        retryOptions: Map<String, Any>,
        result: RunTaskResult,
    ): PendingTaskChallenge? {
        if (result.status != "captcha") return null
        val image = jsonString(result.raw, "captchaImage")
        if (image.isBlank()) return null
        val id = "task-${session.platform}-${task.id}-${System.currentTimeMillis()}"
        return PendingTaskChallenge(
            id = id,
            session = session,
            task = task,
            retryOptions = retryOptions,
            challenge = OcrChallenge(
                taskId = id,
                platform = session.platform,
                type = "image_ocr",
                imageBase64 = image,
                outputCols = OcrEngine.outputColsFor(session.platform) ?: 0,
                hint = result.message,
            ),
        ).also { pending ->
            synchronized(currentById) { currentById[pending.id] = pending }
        }
    }

    suspend fun solveAutomatically(
        pending: PendingTaskChallenge,
        progress: TaskChallengeProgress,
    ): TaskChallengeOutcome {
        synchronized(currentById) { currentById[pending.id] = pending }
        var state = pending
        var attempt = 0
        while (true) {
            if (!coroutineContext.isActive) return TaskChallengeOutcome.Cancelled
            if (shouldRequestManual(state.id)) return TaskChallengeOutcome.Manual(state)
            attempt++
            progress.onAttempt(attempt)
            val cols = state.challenge.outputCols.takeIf { it > 0 } ?: OcrEngine.outputColsFor(state.challenge.platform) ?: 0
            val text = runCatching {
                recognizer.recognizeCaptchaBase64(state.challenge.platform, state.challenge.imageBase64, cols)
            }.getOrNull()
            if (text == null || !OcrEngine.isValidCaptchaText(state.challenge.platform, text)) {
                delay(400)
                continue
            }
            when (val outcome = submitCode(state, text)) {
                is SubmitCodeOutcome.Success -> {
                    clear(state.id)
                    return TaskChallengeOutcome.Success(outcome.result)
                }
                is SubmitCodeOutcome.Captcha -> {
                    val oldId = state.id
                    state = outcome.pending
                    synchronized(currentById) {
                        currentById.remove(oldId)
                        currentById[state.id] = state
                    }
                    return TaskChallengeOutcome.Manual(state)
                }
                is SubmitCodeOutcome.Error -> return TaskChallengeOutcome.Error(outcome.message)
            }
        }
    }

    private fun shouldRequestManual(id: String): Boolean {
        if (requestManualAll) return true
        return synchronized(manualRequestIds) { manualRequestIds.remove(id) }
    }

    suspend fun submitManual(id: String, text: String): TaskChallengeOutcome {
        val pending = synchronized(currentById) { currentById[id] }
            ?: return TaskChallengeOutcome.Error("Task challenge expired")
        return when (val outcome = submitCode(pending, text)) {
            is SubmitCodeOutcome.Success -> {
                clear(id)
                TaskChallengeOutcome.Success(outcome.result)
            }
            is SubmitCodeOutcome.Captcha -> {
                synchronized(currentById) {
                    currentById.remove(id)
                    currentById[outcome.pending.id] = outcome.pending
                }
                TaskChallengeOutcome.Manual(outcome.pending)
            }
            is SubmitCodeOutcome.Error -> TaskChallengeOutcome.Error(outcome.message)
        }
    }

    private suspend fun submitCode(pending: PendingTaskChallenge, text: String): SubmitCodeOutcome {
        if (text.isBlank()) return SubmitCodeOutcome.Error("Captcha text is empty")
        val passResult = runCatching {
            runner.runTask(
                pending.session,
                pending.task,
                mapOf("action" to "passChapterCaptcha", "code" to text),
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            return SubmitCodeOutcome.Error(e.message ?: "Captcha rejected")
        }
        if (passResult.status != "done") {
            return SubmitCodeOutcome.Error(passResult.message.ifBlank { "Captcha rejected" })
        }
        val retried = runCatching { runner.runTask(pending.session, pending.task, pending.retryOptions) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return SubmitCodeOutcome.Error(e.message ?: "Task retry failed")
            }
        if (retried.status == "captcha") {
            val next = capture(pending.session, pending.task, pending.retryOptions, retried)
                ?: return SubmitCodeOutcome.Error("Task returned captcha without image")
            return SubmitCodeOutcome.Captcha(next)
        }
        return SubmitCodeOutcome.Success(retried)
    }

    private sealed interface SubmitCodeOutcome {
        data class Success(val result: RunTaskResult) : SubmitCodeOutcome
        data class Captcha(val pending: PendingTaskChallenge) : SubmitCodeOutcome
        data class Error(val message: String) : SubmitCodeOutcome
    }

    private fun jsonString(raw: com.google.gson.JsonObject, key: String): String =
        runCatching { raw.get(key)?.asString ?: "" }.getOrDefault("")
}
