package dev.yatori.mobile.app.ui.courses

import dev.yatori.captcha.OcrEngine
import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.AccountInput
import dev.yatori.mobile.api.dto.LoginResult
import dev.yatori.mobile.api.dto.OcrChallenge
import dev.yatori.mobile.api.dto.OcrResult
import dev.yatori.mobile.runtime.YatoriCoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Outcome of an automatic-OCR login attempt loop. */
sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data class Error(val message: String) : LoginOutcome
    /** User asked to switch to manual captcha entry; carries the current taskId + challenge. */
    data class Manual(val taskId: String, val challenge: OcrChallenge) : LoginOutcome
    data object Cancelled : LoginOutcome
}

/** Progress callback for the auto-OCR loop UI ("识别中（第N次）…"). */
fun interface LoginProgress {
    fun onAttempt(attempt: Int, networkRetry: Boolean)
}

/**
 * Drives the login + automatic-OCR loop entirely on the Android side (brief §5).
 *
 * Default flow is background auto-OCR: recognize → continueLogin, looping until success, a
 * substantive login error, or user cancellation. The loop is infinite-but-cancellable (no fixed
 * retry cap) — cancellation is via coroutine job cancellation by the caller. cancelLogin is
 * called only for the live Challenge taskId. The password is used only in-memory here and is
 * never persisted.
 */
class LoginController(
    private val repo: YatoriCoreRepository,
    private val ocr: OcrEngine,
) {
    @Volatile private var requestManual = false

    /** Caller sets this (from the UI "手动输入" button) to break out into manual entry. */
    fun requestManual() { requestManual = true }

    suspend fun login(
        platform: Platform,
        account: AccountInput,
        progress: LoginProgress,
    ): LoginOutcome {
        requestManual = false
        val start = runCatching { repo.startLoginAndPersist(platform, account) }
            .getOrElse { return classifyError(it) }

        var state = start
        var attempt = 0
        var sameImageAttempts = 0
        while (true) {
            if (!coroutineContext.isActive) return finishCancel(currentTaskId(state))
            when (state) {
                is LoginResult.Done -> return LoginOutcome.Success
                is LoginResult.Challenge -> {
                    if (requestManual) return LoginOutcome.Manual(state.taskId, state.challenge)
                    attempt++
                    sameImageAttempts++
                    progress.onAttempt(attempt, networkRetry = false)
                    val cols = OcrEngine.outputColsFor(platform.id)
                    val text = runCatching {
                        ocr.recognizeCaptchaBase64(platform.id, state.challenge.imageBase64, cols)
                    }.getOrNull()
                    if (text == null || !OcrEngine.isValidCaptchaText(platform.id, text)) {
                        if (sameImageAttempts >= 5) {
                            // Same image failed 5 times — cancel and restart to fetch a fresh captcha.
                            runCatching { repo.cancelLogin(state.taskId) }
                            state = runCatching { repo.startLoginAndPersist(platform, account) }
                                .getOrElse { return classifyError(it) }
                            sameImageAttempts = 0
                        } else {
                            delay(400)
                        }
                        continue
                    }
                    sameImageAttempts = 0
                    when (val next = runCatching {
                        repo.continueLoginAndPersist(state.taskId, OcrResult(state.taskId, text = text))
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        return classifyError(e)
                    }) {
                        is LoginResult.Done -> return LoginOutcome.Success
                        is LoginResult.Challenge -> {
                            // Server rejected the captcha and issued a new challenge — loop back
                            // and retry OCR on the new image instead of going to manual mode.
                            state = next
                            sameImageAttempts = 0
                        }
                    }
                }
            }
        }
    }

    private fun currentTaskId(state: LoginResult): String? =
        (state as? LoginResult.Challenge)?.taskId

    private suspend fun finishCancel(taskId: String?): LoginOutcome {
        if (taskId != null) runCatching { repo.cancelLogin(taskId) }
        return LoginOutcome.Cancelled
    }

    /** Continue from a manual captcha submission (ChallengeManualScreen). */
    suspend fun submitManual(taskId: String, text: String): LoginOutcome {
        val result = runCatching { repo.continueLoginAndPersist(taskId, OcrResult(taskId, text = text)) }
            .getOrElse { return classifyError(it) }
        return when (result) {
            is LoginResult.Done -> LoginOutcome.Success
            is LoginResult.Challenge -> LoginOutcome.Manual(result.taskId, result.challenge)
        }
    }

    suspend fun cancel(taskId: String?) { if (taskId != null) runCatching { repo.cancelLogin(taskId) } }

    private fun classifyError(t: Throwable): LoginOutcome =
        LoginOutcome.Error(t.message ?: "登录失败")
}
