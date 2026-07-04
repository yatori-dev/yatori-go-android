package dev.yatori.mobile.runtime

import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.*
import dev.yatori.mobile.runtime.log.EncryptedLogStore
import dev.yatori.mobile.runtime.operation.CourseTaskRunner

/**
 * Single entry point for the UI / operation layer.
 *
 * The UI must call this repository (or the operation layer above it) and never
 * touch [dev.yatori.mobile.api.YatoriMobileCore] / [CoreGateway] directly.
 * Combines the isolated [gateway] with local [store] (config / session / cursor /
 * course cache persistence) and the optional [logStore] (encrypted log session).
 *
 * Implements [CourseTaskRunner] so the CourseSyncManager scheduler can drive it directly.
 */
class YatoriCoreRepository(
    private val gateway: CoreGateway,
    private val store: MobilecoreStore,
    private val logStore: EncryptedLogStore? = null,
) : CourseTaskRunner {
    // ── lifecycle / health ──────────────────────────────────────────────────

    /** Cached first successful init — the Go core only needs initializing once per process. */
    @Volatile private var initResult: InitResult? = null
    private val pendingCredentials = mutableMapOf<String, Pair<Platform, AccountInput>>()

    /**
     * Idempotent: initializes the Go core once per process and caches the result. Repeat calls
     * (e.g. Home re-entry on every tab switch) return the cached value WITHOUT re-invoking the
     * core, so its one-shot "Init completed" log is not emitted again and again.
     */
    suspend fun init(baseDir: String): InitResult =
        initResult ?: gateway.init(baseDir).also { initResult = it }

    suspend fun healthCheck(): HealthInfo = gateway.healthCheck()

    // ── config ──────────────────────────────────────────────────────────────

    suspend fun getConfigSchema(): List<ConfigField> = gateway.getConfigSchema()

    /** Calls gateway first; only persists locally on success. */
    suspend fun applyConfig(config: MobileConfig) {
        gateway.setConfig(config)
        store.saveConfig(config)
    }

    /** Injects xuexitong font anti-scrape tables loaded by the Android host. */
    suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) =
        gateway.setXuexitongFontTables(glyfJson, cmapJson)

    suspend fun fetchConfig(): MobileConfig = gateway.getConfig()
    fun loadSavedConfig(): MobileConfig? = store.loadConfig()

    // ── login ─────────────────────────────────────────────────────────────────

    /** Persists session and encrypted credential when gateway returns Done. */
    suspend fun startLoginAndPersist(platform: Platform, account: AccountInput): LoginResult {
        val credential = platform to account
        val result = runCatching { gateway.startLogin(platform, account) }
            .getOrThrow()
        trackOrPersistLogin(result, credential)
        return result
    }

    /**
     * Starts a login flow without persisting the returned session or credential.
     * Used by xuexitong mode3 to build an isolated multi-login pool.
     */
    suspend fun startLoginTransient(platform: Platform, account: AccountInput): LoginResult =
        gateway.startLogin(platform, account)

    suspend fun continueLoginAndPersist(taskId: String, result: OcrResult): LoginResult {
        val credential = synchronized(pendingCredentials) { pendingCredentials.remove(taskId) }
        val loginResult = runCatching { gateway.continueLogin(taskId, result) }
            .onFailure {
                if (credential != null) synchronized(pendingCredentials) { pendingCredentials[taskId] = credential }
            }
            .getOrThrow()
        trackOrPersistLogin(loginResult, credential)
        return loginResult
    }

    /** Login-challenge cancel ONLY. Never use this for runTask / batch-learn cancellation. */
    suspend fun cancelLogin(taskId: String) {
        try {
            gateway.cancelLogin(taskId)
        } finally {
            synchronized(pendingCredentials) { pendingCredentials.remove(taskId) }
        }
    }

    // ── sessions ──────────────────────────────────────────────────────────────

    fun listSessions(): List<StoredSession> = store.listSessions()
    fun getSession(platform: String, account: String, url: String = ""): SessionData? = store.loadSession(platform, account, url)
    fun deleteSession(platform: String, account: String, url: String = ""): Boolean = store.deleteSession(platform, account, url)
    fun listCredentials(): List<StoredCredential> = store.listCredentials()
    fun getCredential(platform: String, account: String): AccountInput? = store.loadCredential(platform, account)
    fun deleteCredential(platform: String, account: String): Boolean = store.deleteCredential(platform, account)
    fun deleteCourseCache(platform: String, account: String): Boolean = store.deleteCourseCache(platform, account)
    fun saveActionState(state: StoredActionState) = store.saveActionState(state)
    fun getActionState(platform: String, account: String, taskId: String, scope: String = "default"): StoredActionState? =
        store.loadActionState(platform, account, taskId, scope)
    fun listActionStates(): List<StoredActionState> = store.listActionStates()
    fun deleteActionState(platform: String, account: String, taskId: String, scope: String = "default"): Boolean =
        store.deleteActionState(platform, account, taskId, scope)
    fun deleteActionStates(platform: String, account: String): Int = store.deleteActionStates(platform, account)

    // ── courses / tasks ────────────────────────────────────────────────────────

    /** Fetches the course list from the core and refreshes the encrypted local cache. */
    override suspend fun getCourses(session: SessionData): List<CourseItem> {
        val courses = gateway.getCourses(session)
        store.saveCourseCache(session.platform, session.account, courses)
        return courses
    }

    fun loadCachedCourses(platform: String, account: String): List<CourseItem> =
        store.loadCourseCache(platform, account)

    fun hasCourseCache(platform: String, account: String): Boolean =
        store.hasCourseCache(platform, account)

    override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> =
        gateway.getCourseDetail(session, course)

    override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
        gateway.getTasks(session, course)

    /**
     * Single-step task submission. The Go core does NOT loop or sleep; batch
     * scheduling is owned by the Android operation layer.
     */
    override suspend fun runTask(
        session: SessionData,
        task: TaskItem,
        options: Map<String, Any>,
    ): RunTaskResult = gateway.runTask(session, task, options)

    // ── logs ──────────────────────────────────────────────────────────────────

    /**
     * Reads cursor from store, polls gateway, writes the returned Go-core logs into the
     * current encrypted log session (unchanged, unmerged), then saves nextCursor.
     */
    suspend fun pollLogs(): LogResult {
        val cursor = store.loadLogCursor()
        val result = gateway.getLogs(cursor)
        if (result.logs.isNotEmpty()) logStore?.appendEntries(result.logs)
        store.saveLogCursor(result.nextCursor)
        return result
    }

    /** Decrypted current log session for UI display (empty if no log store wired). */
    fun currentSessionLogs(): List<LogEntry> = logStore?.readCurrentSession() ?: emptyList()

    /** Clears the Go-core log buffer AND the local cursor (full reset). */
    suspend fun clearLogs() {
        gateway.clearLogs()
        store.clearLogCursor()
    }

    fun clearLogCursor() = store.clearLogCursor()

    /**
     * Sets the Go-core log generation level (debug/info/warn/error). This affects
     * which logs the core PRODUCES going forward — it is NOT the Logs-screen view
     * filter (that filters already-collected entries in the UI only).
     */
    suspend fun setLogLevel(level: String) = gateway.setLogLevel(level)

    private fun trackOrPersistLogin(result: LoginResult, credential: Pair<Platform, AccountInput>?) {
        when (result) {
            is LoginResult.Challenge -> {
                if (credential != null) synchronized(pendingCredentials) {
                    pendingCredentials[result.taskId] = credential
                }
            }
            is LoginResult.Done -> {
                store.saveSession(result.session)
                if (credential != null) {
                    store.saveCredential(
                        result.session.platform,
                        credential.second.copy(account = result.session.account),
                    )
                }
            }
        }
    }
}
