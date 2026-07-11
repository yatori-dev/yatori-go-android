package dev.yatori.mobile.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.runtime.operation.AnswerMode
import dev.yatori.mobile.runtime.operation.AnswerPolicy
import dev.yatori.mobile.runtime.operation.CourseSelectionRule
import dev.yatori.mobile.runtime.operation.RunPlan
import dev.yatori.mobile.runtime.operation.SyncEvent
import dev.yatori.mobile.runtime.operation.XuexitongRunOptions
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker that resumes / runs a batch course-sync plan across process death and
 * reboots (brief §7.4 — WorkManager as the deferred/recoverable execution supplement). The
 * plan is described entirely by [inputData] so it survives serialization; already-submitted
 * task ids are passed in to avoid redoing work on resume.
 *
 * For real-time progress the app uses the Foreground Service; this worker is the durable
 * backstop. Real-account end-to-end validation of the full run is pending.
 */
class CourseSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.from(applicationContext)
        val platform = inputData.getString(KEY_PLATFORM) ?: return Result.failure()
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: ""
        val dryRun = inputData.getBoolean(KEY_DRY_RUN, false)
        val keywords = inputData.getStringArray(KEY_KEYWORDS)?.toList() ?: emptyList()
        val completed = inputData.getStringArray(KEY_COMPLETED)?.toList() ?: emptyList()

        container.ensureCoreReady()
        // Silently re-login with saved credentials to refresh the session cookie before the run.
        // Skips if: no stored credentials, platform requires captcha, or refreshed recently.
        val refreshedSession = tryRefreshSession(container, platform, account)
        val session = refreshedSession
            ?: container.repository.getSession(platform, account, url)
            // Last-resort fallback: scan all stored sessions for a matching (platform, account).
            // This handles URL hash mismatches and legacy no-URL session files.
            ?: container.repository.listSessions()
                .firstOrNull { it.platform == platform && it.account == account }
                ?.session
            ?: run {
                appendRunEvent(
                    container,
                    SyncEvent(
                        level = "error",
                        message = "未找到 $platform 账号的 session，请重新登录后再试",
                        platform = platform,
                        account = account,
                    ),
                )
                return Result.failure()
            }
        val rule = ruleFromSavedConfig(container, platform, account, keywords)

        val plan = RunPlan(
            planId = "work-$platform-$account",
            platform = platform,
            account = account,
            rule = rule,
            dryRun = dryRun,
            answerPolicy = answerPolicyFromSavedConfig(container, platform, account),
            xuexitong = xuexitongOptionsFromSavedConfig(container, platform, account),
            haiqikejiVideoModel = haiqikejiVideoModelFromSavedConfig(container, platform, account),
            qingshuxuetangVideoModel = qingshuxuetangVideoModelFromSavedConfig(container, platform, account),
            ketangxVideoModel = ketangxVideoModelFromSavedConfig(container, platform, account),
            enaeaVideoModel = enaeaVideoModelFromSavedConfig(container, platform, account),
            yinghuaVideoModel = yinghuaVideoModelFromSavedConfig(container, platform, account),
            welearnVideoModel = welearnVideoModelFromSavedConfig(container, platform, account),
            welearnStudyTimeRange = welearnStudyTimeFromSavedConfig(container, platform, account),
            cqieVideoModel = cqieVideoModelFromSavedConfig(container, platform, account),
            completedTaskIds = completed,
        )

        var lastEventWasError = false
        var currentSession = session

        // Run with at most one auto-relogin retry on session expiry.
        for (attempt in 0..1) {
            val syncResult = runCatching {
                container.courseSyncManager.run(currentSession, plan) { event ->
                    lastEventWasError = event.level.equals("error", ignoreCase = true)
                    appendRunEvent(container, event)
                }
            }

            if (syncResult.isSuccess) {
                sendCompletionEmailIfConfigured(container, platform, account)
                return Result.success(workDataOf(KEY_SUBMITTED to syncResult.getOrThrow().toTypedArray()))
            }

            val error = syncResult.exceptionOrNull()!!
            // On first attempt, check for session expiry and try auto-relogin.
            if (attempt == 0 && isSessionExpiredError(error)) {
                appendRunEvent(container, SyncEvent(level = "warn", message = "会话已过期，尝试自动重新登录…", platform = platform, account = account))
                val newSession = autoReloginWithOcr(container, platform, account, url)
                if (newSession != null) {
                    currentSession = newSession
                    lastEventWasError = false
                    appendRunEvent(container, SyncEvent(level = "info", message = "重新登录成功，继续执行任务", platform = platform, account = account))
                    continue
                }
                appendRunEvent(container, SyncEvent(level = "error", message = "自动重新登录失败，请手动登录后重试", platform = platform, account = account))
                return Result.failure()
            }

            // Normal error path.
            if (!lastEventWasError) {
                appendRunEvent(container, SyncEvent(level = "error", message = "任务执行失败：${error.message ?: error::class.java.simpleName}", platform = platform, account = account))
            }
            return Result.retry()
        }

        return Result.retry()
    }

    companion object {
        const val KEY_PLATFORM = "platform"
        const val KEY_ACCOUNT = "account"
        const val KEY_URL = "url"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_KEYWORDS = "keywords"
        const val KEY_COMPLETED = "completed"
        const val KEY_SUBMITTED = "submitted"

        /** Cooldown between silent session refreshes per (platform, account). 30 minutes. */
        internal const val REFRESH_COOLDOWN_MS = 30L * 60 * 1000
        internal val lastRefreshAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /**
         * Platforms that require captcha for login — starting a login for these may invalidate
         * the current session token, so tryRefreshSession skips them entirely.
         */
        internal val CAPTCHA_PLATFORMS = setOf("yinghua", "cqie", "qingshuxuetang")

        /** Enqueues a unique resumable sync plan for (platform, account). */
        fun enqueue(
            context: Context,
            platform: String,
            account: String,
            url: String = "",
            keywords: List<String> = emptyList(),
            dryRun: Boolean = false,
            completed: List<String> = emptyList(),
        ) {
            val request = OneTimeWorkRequestBuilder<CourseSyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PLATFORM to platform,
                        KEY_ACCOUNT to account,
                        KEY_URL to url,
                        KEY_DRY_RUN to dryRun,
                        KEY_KEYWORDS to keywords.toTypedArray(),
                        KEY_COMPLETED to completed.toTypedArray(),
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-$platform-$account",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

/**
 * Silently re-logs in with stored credentials to refresh the session cookie before a sync run.
 * Returns the fresh [SessionData] on success, or null if refresh was skipped / not possible.
 *
 * Skips when:
 *  - No credentials stored for this account (never saved or user deleted them).
 *  - Platform login returns a captcha challenge (can't auto-solve here; skip silently).
 *  - Last refresh was within the cooldown window (avoids hammering the auth server).
 */
private suspend fun tryRefreshSession(
    container: AppContainer,
    platform: String,
    account: String,
): dev.yatori.mobile.api.dto.SessionData? {
    // Captcha platforms (yinghua, cqie, qingshuxuetang) cannot be auto-refreshed here.
    // More importantly, initiating a StartLogin for these platforms may invalidate the
    // current server-side session token, breaking the subsequent getCourses call.
    // The UI-driven LoginController (with OCR) handles re-login for these platforms.
    if (platform in CourseSyncWorker.CAPTCHA_PLATFORMS) return null

    val key = "$platform/$account"
    val now = System.currentTimeMillis()
    val last = CourseSyncWorker.lastRefreshAt[key] ?: 0L
    if (now - last < CourseSyncWorker.REFRESH_COOLDOWN_MS) return null  // still within cooldown

    val credential = container.repository.getCredential(platform, account) ?: return null
    val platformEnum = dev.yatori.mobile.api.Platform.entries.firstOrNull {
        it.id.equals(platform, ignoreCase = true)
    } ?: return null

    return runCatching {
        when (val result = container.repository.startLoginAndPersist(platformEnum, credential)) {
            is dev.yatori.mobile.api.dto.LoginResult.Done -> {
                CourseSyncWorker.lastRefreshAt[key] = now
                result.session
            }
            // Challenge required — can't auto-refresh; use existing session.
            is dev.yatori.mobile.api.dto.LoginResult.Challenge -> {
                // Cancel the dangling task so Go core cleans up the challenge state.
                runCatching { container.repository.cancelLogin(result.taskId) }
                null
            }
        }
    }.getOrNull()  // any network/parse error → fall back to existing session
}

internal suspend fun autoReloginWithOcr(
    container: dev.yatori.mobile.app.di.AppContainer,
    platform: String,
    account: String,
    url: String,
): dev.yatori.mobile.api.dto.SessionData? {
    val platformEnum = dev.yatori.mobile.api.Platform.entries.firstOrNull {
        it.id.equals(platform, ignoreCase = true)
    } ?: return null
    val credential = container.repository.getCredential(platform, account) ?: return null

    val outcome = container.loginController.login(
        platform = platformEnum,
        account = credential,
        progress = { _, _ -> },
    )

    if (outcome !is dev.yatori.mobile.app.ui.courses.LoginOutcome.Success) return null

    // Return the freshly saved URL-keyed session; saved credentials retain the institution URL.
    val lookupUrl = url.ifBlank { credential.url }
    return container.repository.getSession(platform, account, lookupUrl)
        ?: container.repository.listSessions()
            .firstOrNull { it.platform == platform && it.account == account }
            ?.session
}

private fun appendRunEvent(container: AppContainer, event: SyncEvent) {
    val id = -System.nanoTime()
    container.logStore.appendEntries(
        listOf(
            LogEntry(
                id = id,
                time = Instant.now().toString(),
                level = event.level,
                source = "android-runtime",
                platform = event.platform,
                message = event.message,
                account = event.account,
            ),
        ),
    )
}

/**
 * Sends a course-completion notification email when the global EmailInform toggle is on and the
 * per-user [informEmails] list is non-empty. Mirrors console userBlock() email trigger.
 * Runs on IO dispatcher so it never blocks the WorkManager thread pool.
 */
private suspend fun sendCompletionEmailIfConfigured(container: AppContainer, platform: String, account: String) {
    val cfg = container.repository.loadSavedConfig() ?: return
    val emailInform = cfg.setting.emailInform
    if (emailInform.sw != 1) return

    val user = cfg.users.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return
    val toAddresses = user.strings("informEmails").filter { it.isNotBlank() }
    if (toAddresses.isEmpty()) return

    val platformDisplay = dev.yatori.mobile.app.platform.platformDisplayName(platform)
    val content = "账号：[$account]<br>平台：[$platformDisplay]<br>通知：所有课程已执行完毕"
    withContext(Dispatchers.IO) {
        EmailSender.send(emailInform, toAddresses, content)
    }
}

internal fun answerPolicyFromSavedConfig(container: AppContainer, platform: String, account: String): AnswerPolicy {
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return AnswerPolicy()
    val cc = user.obj("coursesCustom")
    return answerPolicyFromCoursesCustom(platform, cc)
}

internal fun answerPolicyFromCoursesCustom(platform: String, cc: JsonObject): AnswerPolicy {
    val answerMode = AnswerMode.fromConfig(cc.int("autoExam"))
    if (answerMode == AnswerMode.DISABLED) return AnswerPolicy()
    if (platform != "xuexitong" && answerMode == AnswerMode.XUEXITONG_BUILT_IN) return AnswerPolicy()
    if (platform != "xuexitong") {
        val submitMode = cc.int("examAutoSubmit")
        val submitFinal = submitMode == 1
        return AnswerPolicy(
            enabled = true,
            answerMode = answerMode,
            runWork = true,
            runExam = true,
            runChapterTest = false,
            submitWorkFinal = submitFinal,
            submitExamFinal = submitFinal,
            realSubmitExam = submitFinal,
        )
    }
    val submitMode = cc.int("examAutoSubmit")
    val submitFinal = submitMode == 1
    val submitFinalWhenComplete = submitMode == 2
    return AnswerPolicy(
        enabled = true,
        answerMode = answerMode,
        runWork = cc.int("cxWorkSw", 1) == 1,
        runExam = cc.int("cxExamSw", 1) == 1,
        runChapterTest = cc.int("cxChapterTestSw", 1) == 1,
        submitWorkFinal = submitFinal,
        submitExamFinal = submitFinal,
        submitChapterTestFinal = submitFinal,
        submitFinalWhenComplete = submitFinalWhenComplete,
        realSubmitExam = submitFinal || submitFinalWhenComplete,
        realSubmitChapterTest = submitFinal || submitFinalWhenComplete,
    )
}

internal fun xuexitongOptionsFromSavedConfig(container: AppContainer, platform: String, account: String): XuexitongRunOptions {
    if (platform != "xuexitong") return XuexitongRunOptions()
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return XuexitongRunOptions()
    return xuexitongOptionsFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun haiqikejiVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "haiqikeji") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return haiqikejiVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun haiqikejiVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..2 } ?: 1

internal fun qingshuxuetangVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "qingshuxuetang") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return qingshuxuetangVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun qingshuxuetangVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..1 } ?: 1

internal fun ketangxVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "ketangx") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return ketangxVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun ketangxVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..1 } ?: 1

internal fun enaeaVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "enaea") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return enaeaVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun enaeaVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..2 } ?: 1

internal fun yinghuaVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "yinghua") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return yinghuaVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun yinghuaVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..3 } ?: 1

internal fun welearnVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "welearn") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return welearnVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun welearnVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..2 } ?: 1

internal fun welearnStudyTimeFromSavedConfig(container: AppContainer, platform: String, account: String): String {
    if (platform != "welearn") return ""
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return ""
    return user.obj("coursesCustom").str("studyTime").trim()
}

internal fun cqieVideoModelFromSavedConfig(container: AppContainer, platform: String, account: String): Int {
    if (platform != "cqie") return 1
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    } ?: return 1
    return cqieVideoModelFromCoursesCustom(user.obj("coursesCustom"))
}

internal fun cqieVideoModelFromCoursesCustom(cc: JsonObject): Int =
    cc.int("videoModel", 1).takeIf { it in 0..2 } ?: 1

internal fun xuexitongOptionsFromCoursesCustom(cc: JsonObject): XuexitongRunOptions =
    XuexitongRunOptions(
        videoModel = cc.int("videoModel", 1).takeIf { it in 0..3 } ?: 1,
        cxNode = cc.int("cxNode", 3).takeIf { it > 0 } ?: 3,
        shuffle = cc.int("shuffleSw") == 1,
    )

internal fun ruleFromSavedConfig(
    container: AppContainer,
    platform: String,
    account: String,
    overrideKeywords: List<String>,
): CourseSelectionRule {
    if (overrideKeywords.isNotEmpty()) {
        return CourseSelectionRule(CourseSelectionRule.Mode.MANUAL_CONTAINS, includeKeywords = overrideKeywords)
    }
    val user = container.repository.loadSavedConfig()?.users?.firstOrNull {
        it.str("account").equals(account, ignoreCase = false) &&
            it.str("accountType").equals(platform, ignoreCase = true)
    }
    val cc = user?.obj("coursesCustom") ?: JsonObject()
    return courseSelectionRuleFromCoursesCustom(platform, cc)
}

internal fun courseSelectionRuleFromCoursesCustom(platform: String, cc: JsonObject): CourseSelectionRule {
    val include = cc.strings("includeCourses")
    val exclude = cc.strings("excludeCourses")
    val mode = when {
        platform == "haiqikeji" || platform == "ketangx" -> CourseSelectionRule.Mode.EXACT_NAME
        platform == "enaea" -> CourseSelectionRule.Mode.ENAEA_PROJECT_CATEGORY
        include.isEmpty() -> CourseSelectionRule.Mode.ALL
        else -> CourseSelectionRule.Mode.MANUAL_CONTAINS
    }
    return CourseSelectionRule(mode = mode, includeKeywords = include, excludeKeywords = exclude)
}

private fun JsonObject.obj(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.str(key: String): String =
    runCatching { get(key)?.asString.orEmpty() }.getOrDefault("")

private fun JsonObject.int(key: String, default: Int = 0): Int =
    runCatching { get(key)?.asInt ?: default }.getOrDefault(default)

private fun JsonObject?.strings(key: String): List<String> =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank) }
        .orEmpty()
