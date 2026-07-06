package dev.yatori.mobile.app.di

import android.content.Context
import dev.yatori.captcha.OcrDecoder
import dev.yatori.captcha.OcrEngine
import dev.yatori.mobile.app.ui.theme.ThemePrefs
import dev.yatori.mobile.app.update.AppUpdateController
import dev.yatori.mobile.runtime.MobilecoreStore
import dev.yatori.mobile.runtime.YatoriCoreRepository
import dev.yatori.mobile.runtime.YatoriMobileCoreGateway
import dev.yatori.mobile.api.dto.InitResult
import dev.yatori.mobile.runtime.internal.EncryptedFileIo
import dev.yatori.mobile.runtime.log.EncryptedLogStore
import dev.yatori.mobile.runtime.log.KeystoreLogCrypto
import dev.yatori.mobile.runtime.operation.AnswerEditRequest
import dev.yatori.mobile.runtime.operation.AnswerEditResolver
import dev.yatori.mobile.runtime.operation.AnswerMode
import dev.yatori.mobile.runtime.operation.AnswerRequest
import dev.yatori.mobile.runtime.operation.BuiltInXuexitongAnswerProvider
import dev.yatori.mobile.runtime.operation.CompositeAnswerProviderFactory
import dev.yatori.mobile.runtime.operation.CourseSyncManager
import dev.yatori.mobile.runtime.operation.ExternalQuestionBankAnswerProvider
import dev.yatori.mobile.runtime.operation.HostAiAnswerProvider
import dev.yatori.mobile.runtime.operation.OperationController
import dev.yatori.mobile.runtime.operation.PlatformActionScheduler
import dev.yatori.mobile.runtime.operation.PlatformTaskScheduler
import dev.yatori.mobile.runtime.operation.TaskChallengeResolver
import dev.yatori.mobile.runtime.operation.XuexitongSessionProvider
import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.LogEntry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manual dependency container (no Hilt — keeps the build simple and KSP-free).
 *
 * Owns the single instances of the runtime repository, encrypted log store, operation
 * controller, course sync manager, theme prefs, and the lazily-loaded OCR engine. The UI
 * obtains everything from here via [AppContainer.from] and never constructs core/gateway
 * objects itself.
 */
class AppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext

    /** Base dir passed to the Go core init (Android filesDir). */
    val baseDir: String = appContext.filesDir.absolutePath

    val themePrefs: ThemePrefs = ThemePrefs(appContext)

    val updateController: AppUpdateController = AppUpdateController()

    val operationController: OperationController = OperationController()

    private val store: MobilecoreStore = MobilecoreStore(appContext.filesDir, EncryptedFileIo(appContext))
    private val fontAssetLoader: XuexitongFontAssetLoader = XuexitongFontAssetLoader(appContext.assets)
    @Volatile private var fontAssetResult: XuexitongFontAssetResult? = null

    val logStore: EncryptedLogStore = EncryptedLogStore(
        dir = File(appContext.filesDir, "yatori-logs"),
        crypto = KeystoreLogCrypto(),
        sessionStartMillis = System.currentTimeMillis(),
    )

    val repository: YatoriCoreRepository = YatoriCoreRepository(
        gateway = YatoriMobileCoreGateway(),
        store = store,
        logStore = logStore,
    )

    val platformActionScheduler: PlatformActionScheduler = PlatformActionScheduler(repository)

    val platformTaskScheduler: PlatformTaskScheduler = PlatformTaskScheduler(platformActionScheduler)

    // ── centralized log pipeline ─────────────────────────────────────────────────
    //
    // ONE process-wide coroutine drains the Go-core log ring into the encrypted store; each
    // append pushes into the store's bounded in-memory buffer ([EncryptedLogStore.live]).
    // Screens observe [liveLogs] instead of decrypting the whole session file on a timer — that
    // per-poll full-file decrypt was what made the app progressively laggier the longer a task
    // ran. The poll cadence is fast while a screen is actively viewing logs (viewer ref-count
    // > 0) and slow otherwise, but it never stops, so logs are still drained during background
    // runs and nothing is lost from the bounded Go ring.

    /** Process-lifetime scope; never cancelled (AppContainer is a singleton). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bounded (≤ LIVE_CAP), newest-last current-session logs, pushed on every append. */
    val liveLogs: StateFlow<List<LogEntry>> = logStore.live

    private val logViewers = MutableStateFlow(0)

    /** A log-displaying screen calls this while visible to raise the poll cadence. */
    fun retainLogView() { logViewers.update { it + 1 } }

    /** Balances a prior [retainLogView]; floors at 0. */
    fun releaseLogView() { logViewers.update { (it - 1).coerceAtLeast(0) } }

    init {
        // Trim old session files once per process, then drain the Go-core log buffer forever.
        appScope.launch {
            runCatching { logStore.enforceRetention() }
            while (true) {
                runCatching { repository.pollLogs() }
                delay(if (logViewers.value > 0) ACTIVE_LOG_POLL_MS else IDLE_LOG_POLL_MS)
            }
        }
    }

    private val answerProviderFactory by lazy {
        CompositeAnswerProviderFactory(
            builtIn = BuiltInXuexitongAnswerProvider(repository),
            hostAi = HostAiAnswerProvider(settingProvider = { repository.loadSavedConfig()?.setting?.aiSetting }),
            external = ExternalQuestionBankAnswerProvider(settingProvider = { repository.loadSavedConfig()?.setting?.apiQueSetting }),
        )
    }

    val courseSyncManager: CourseSyncManager by lazy {
        CourseSyncManager(
            runner = repository,
            controller = operationController,
            platformRunner = platformTaskScheduler,
            taskChallengeResolver = TaskChallengeResolver { request ->
                val pending = taskChallengeController.capture(
                    request.session,
                    request.task,
                    request.retryOptions,
                    request.result,
                ) ?: error("验证码图片缺失")
                pendingTaskChallenge = pending
                when (val outcome = taskChallengeController.solveAutomatically(pending) { }) {
                    is dev.yatori.mobile.app.ui.courses.TaskChallengeOutcome.Success -> {
                        removePendingTaskChallenge(pending.id)
                        outcome.result
                    }
                    is dev.yatori.mobile.app.ui.courses.TaskChallengeOutcome.Manual -> {
                        if (outcome.pending.id != pending.id) removePendingTaskChallenge(pending.id)
                        awaitPendingTaskChallenge(outcome.pending)
                    }
                    is dev.yatori.mobile.app.ui.courses.TaskChallengeOutcome.Error -> {
                        removePendingTaskChallenge(pending.id)
                        error(outcome.message)
                    }
                    is dev.yatori.mobile.app.ui.courses.TaskChallengeOutcome.Cancelled -> {
                        removePendingTaskChallenge(pending.id)
                        error("验证码处理已取消")
                    }
                }
            },
            answerProviderFactory = answerProviderFactory,
            answerEditResolver = AnswerEditResolver { request ->
                awaitPendingAnswerEdit(request)
            },
            xuexitongSessionProvider = XuexitongSessionProvider { primary, index ->
                val credential = repository.getCredential(primary.platform, primary.account)
                    ?: error("学习通 mode3 需要本机已保存凭据，无法建立第 ${index + 1} 个登录会话")
                when (val result = repository.startLoginTransient(Platform.XUEXITONG, credential)) {
                    is dev.yatori.mobile.api.dto.LoginResult.Done -> result.session
                    is dev.yatori.mobile.api.dto.LoginResult.Challenge -> error("学习通 mode3 重登录触发验证码，已停止该并发会话")
                }
            },
        )
    }

    /** Total cached courses across all saved accounts (Home metric). */
    fun cachedCourseCount(): Int = store.cachedCourseCount()

    suspend fun ensureCoreReady(): InitResult {
        val result = repository.init(baseDir)
        if (fontAssetResult == null) {
            fontAssetResult = fontAssetLoader.inject(repository)
        }
        return result
    }

    fun xuexitongFontAssetResult(): XuexitongFontAssetResult? = fontAssetResult

    /** Wipes config / sessions / course cache / log cursor and all log files. */
    fun clearAllLocalData() {
        store.clearAll()
        logStore.clearCurrentSession()
        logStore.listHistory().forEach { logStore.deleteHistory(it.name) }
    }

    /**
     * Transient holder for an in-progress manual captcha challenge, set by AddAccount before
     * navigating to ChallengeManualScreen (which has no other way to receive the image). Pure
     * in-memory UI state; cleared on submit/cancel. Never persisted.
     */
    @Volatile
    var pendingChallenge: dev.yatori.mobile.api.dto.OcrChallenge? = null

    private val pendingTaskChallengeCompletions =
        mutableMapOf<String, CompletableDeferred<dev.yatori.mobile.api.dto.RunTaskResult>>()

    private val pendingAnswerEditCompletions = mutableMapOf<String, CompletableDeferred<List<String>>>()

    private val _pendingTaskChallenges =
        MutableStateFlow<List<dev.yatori.mobile.app.ui.courses.PendingTaskChallenge>>(emptyList())
    val pendingTaskChallengesFlow: StateFlow<List<dev.yatori.mobile.app.ui.courses.PendingTaskChallenge>> =
        _pendingTaskChallenges

    private val _pendingTaskChallenge =
        MutableStateFlow<dev.yatori.mobile.app.ui.courses.PendingTaskChallenge?>(null)
    val pendingTaskChallengeFlow: StateFlow<dev.yatori.mobile.app.ui.courses.PendingTaskChallenge?> =
        _pendingTaskChallenge

    var pendingTaskChallenge: dev.yatori.mobile.app.ui.courses.PendingTaskChallenge?
        get() = _pendingTaskChallenges.value.firstOrNull()
        set(value) {
            if (value == null) clearAllPendingTaskChallenges()
            else upsertPendingTaskChallenge(value)
        }

    private val _pendingAnswerEdits = MutableStateFlow<List<AnswerEditRequest>>(emptyList())
    val pendingAnswerEditsFlow: StateFlow<List<AnswerEditRequest>> = _pendingAnswerEdits

    private val _pendingAnswerEdit = MutableStateFlow<AnswerEditRequest?>(null)
    val pendingAnswerEditFlow: StateFlow<AnswerEditRequest?> =
        _pendingAnswerEdit

    var pendingAnswerEdit: AnswerEditRequest?
        get() = _pendingAnswerEdits.value.firstOrNull()
        set(value) {
            if (value == null) clearAllPendingAnswerEdits()
            else upsertPendingAnswerEdit(value)
        }

    private fun upsertPendingTaskChallenge(pending: dev.yatori.mobile.app.ui.courses.PendingTaskChallenge) {
        val next = _pendingTaskChallenges.value
            .filterNot { it.id == pending.id } + pending
        _pendingTaskChallenges.value = next
        _pendingTaskChallenge.value = next.firstOrNull()
    }

    private fun removePendingTaskChallenge(id: String) {
        val next = _pendingTaskChallenges.value.filterNot { it.id == id }
        _pendingTaskChallenges.value = next
        _pendingTaskChallenge.value = next.firstOrNull()
    }

    private fun clearAllPendingTaskChallenges() {
        _pendingTaskChallenges.value = emptyList()
        _pendingTaskChallenge.value = null
    }

    private fun upsertPendingAnswerEdit(request: AnswerEditRequest) {
        val next = _pendingAnswerEdits.value
            .filterNot { it.id == request.id } + request
        _pendingAnswerEdits.value = next
        _pendingAnswerEdit.value = next.firstOrNull()
    }

    private fun removePendingAnswerEdit(id: String) {
        val next = _pendingAnswerEdits.value.filterNot { it.id == id }
        _pendingAnswerEdits.value = next
        _pendingAnswerEdit.value = next.firstOrNull()
    }

    private fun clearAllPendingAnswerEdits() {
        _pendingAnswerEdits.value = emptyList()
        _pendingAnswerEdit.value = null
    }

    suspend fun awaitPendingTaskChallenge(
        pending: dev.yatori.mobile.app.ui.courses.PendingTaskChallenge,
    ): dev.yatori.mobile.api.dto.RunTaskResult {
        val deferred = CompletableDeferred<dev.yatori.mobile.api.dto.RunTaskResult>()
        synchronized(pendingTaskChallengeCompletions) {
            pendingTaskChallengeCompletions[pending.id] = deferred
        }
        upsertPendingTaskChallenge(pending)
        return try {
            deferred.await()
        } finally {
            synchronized(pendingTaskChallengeCompletions) {
                if (pendingTaskChallengeCompletions[pending.id] === deferred) {
                    pendingTaskChallengeCompletions.remove(pending.id)
                }
            }
            removePendingTaskChallenge(pending.id)
        }
    }

    fun completePendingTaskChallenge(id: String, result: dev.yatori.mobile.api.dto.RunTaskResult) {
        removePendingTaskChallenge(id)
        synchronized(pendingTaskChallengeCompletions) {
            pendingTaskChallengeCompletions.remove(id)
        }?.complete(result)
    }

    fun completePendingTaskChallenge(result: dev.yatori.mobile.api.dto.RunTaskResult) {
        pendingTaskChallenge?.let { completePendingTaskChallenge(it.id, result) }
    }

    fun failPendingTaskChallenge(id: String, message: String) {
        removePendingTaskChallenge(id)
        synchronized(pendingTaskChallengeCompletions) {
            pendingTaskChallengeCompletions.remove(id)
        }?.completeExceptionally(IllegalStateException(message))
    }

    fun failPendingTaskChallenge(message: String) {
        pendingTaskChallenge?.let { failPendingTaskChallenge(it.id, message) }
    }

    fun cancelPendingTaskChallenge(id: String, message: String = "Task challenge cancelled") {
        removePendingTaskChallenge(id)
        synchronized(pendingTaskChallengeCompletions) {
            pendingTaskChallengeCompletions.remove(id)
        }?.completeExceptionally(CancellationException(message))
    }

    fun cancelPendingTaskChallenge(message: String = "Task challenge cancelled") {
        val ids = _pendingTaskChallenges.value.map { it.id }
        clearAllPendingTaskChallenges()
        synchronized(pendingTaskChallengeCompletions) {
            ids.mapNotNull { pendingTaskChallengeCompletions.remove(it) }
        }.forEach { it.completeExceptionally(CancellationException(message)) }
    }

    suspend fun awaitPendingAnswerEdit(request: AnswerEditRequest): List<String> {
        val deferred = CompletableDeferred<List<String>>()
        synchronized(pendingAnswerEditCompletions) {
            pendingAnswerEditCompletions[request.id] = deferred
        }
        upsertPendingAnswerEdit(request)
        return try {
            deferred.await()
        } finally {
            synchronized(pendingAnswerEditCompletions) {
                if (pendingAnswerEditCompletions[request.id] === deferred) {
                    pendingAnswerEditCompletions.remove(request.id)
                }
            }
            removePendingAnswerEdit(request.id)
        }
    }

    fun completePendingAnswerEdit(id: String, answers: List<String>) {
        removePendingAnswerEdit(id)
        synchronized(pendingAnswerEditCompletions) {
            pendingAnswerEditCompletions.remove(id)
        }?.complete(answers)
    }

    fun completePendingAnswerEdit(answers: List<String>) {
        pendingAnswerEdit?.let { completePendingAnswerEdit(it.id, answers) }
    }

    fun failPendingAnswerEdit(id: String, message: String) {
        removePendingAnswerEdit(id)
        synchronized(pendingAnswerEditCompletions) {
            pendingAnswerEditCompletions.remove(id)
        }?.completeExceptionally(IllegalStateException(message))
    }

    fun failPendingAnswerEdit(message: String) {
        pendingAnswerEdit?.let { failPendingAnswerEdit(it.id, message) }
    }

    fun cancelPendingAnswerEdit(id: String, message: String = "Answer edit cancelled") {
        removePendingAnswerEdit(id)
        synchronized(pendingAnswerEditCompletions) {
            pendingAnswerEditCompletions.remove(id)
        }?.completeExceptionally(CancellationException(message))
    }

    fun cancelPendingAnswerEdit(message: String = "Answer edit cancelled") {
        val ids = _pendingAnswerEdits.value.map { it.id }
        clearAllPendingAnswerEdits()
        synchronized(pendingAnswerEditCompletions) {
            ids.mapNotNull { pendingAnswerEditCompletions.remove(it) }
        }.forEach { it.completeExceptionally(CancellationException(message)) }
    }

    suspend fun refillPendingAnswerWithHostAi(request: AnswerEditRequest): List<String> =
        answerProviderFactory.provider(AnswerMode.HOST_AI)?.let { provider ->
            if (request.scope == "bbs") {
                listOf(
                    provider.bbs(
                        AnswerRequest(
                            session = request.session,
                            ctx = request.ctx,
                            question = request.question,
                            prompt = request.prompt,
                            dryRun = request.dryRun,
                            label = request.label,
                        ),
                    ),
                ).filter { it.isNotBlank() }
            } else {
                provider.answers(
                    AnswerRequest(
                        session = request.session,
                        ctx = request.ctx,
                        question = request.question,
                        prompt = request.prompt,
                        dryRun = request.dryRun,
                        label = request.label,
                    ),
                )
            }
        }.orEmpty()

    /** Shared login controller so AddAccount and ChallengeManual continue the same flow. */
    val loginController by lazy {
        dev.yatori.mobile.app.ui.courses.LoginController(repository, ocrEngine)
    }

    /** Shared task captcha controller for chapter-test / BBS web character challenges. */
    val taskChallengeController by lazy {
        dev.yatori.mobile.app.ui.courses.TaskChallengeController(repository, ocrEngine)
    }

    /** OCR engine is lazy + heavy; loaded on first captcha need. */
    val ocrEngine: OcrEngine by lazy {
        val model = appContext.assets.let { runCatching { it.open("common_old.onnx").use { s -> s.readBytes() } } }
            .getOrElse { ByteArray(0) }
        val chars = appContext.assets.let { runCatching { it.open("charcode.json").use { s -> s.bufferedReader().readText() } } }
            .getOrElse { "[]" }
        // calc_det.onnx: optional arithmetic-captcha detection model (qingshuxuetang)
        val calcDet = appContext.assets.let { runCatching { it.open("calc_det.onnx").use { s -> s.readBytes() } } }
            .getOrNull()
        OcrEngine(model, OcrDecoder(chars), calcDetModelBytes = calcDet)
    }

    companion object {
        /** Log-drain cadence while a screen is actively viewing logs. */
        private const val ACTIVE_LOG_POLL_MS = 3_000L

        /** Log-drain cadence when no screen is viewing (still drains background runs). */
        private const val IDLE_LOG_POLL_MS = 15_000L

        @Volatile private var instance: AppContainer? = null

        fun from(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
