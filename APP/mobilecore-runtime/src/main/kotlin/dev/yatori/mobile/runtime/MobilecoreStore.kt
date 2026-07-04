package dev.yatori.mobile.runtime

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.yatori.mobile.api.dto.AccountInput
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.runtime.internal.PlaintextFileIo
import dev.yatori.mobile.runtime.internal.SecureFileIo
import java.io.File
import java.security.MessageDigest

/**
 * Local persistence for config / sessions / credentials / log cursor / course cache.
 *
 * All sensitive blobs are written through an injectable [SecureFileIo]:
 *  - production app injects an EncryptedFile-backed implementation (Keystore AES-GCM),
 *  - JVM unit tests inject a plaintext implementation (EncryptedFile needs Android).
 *
 * Logs are NOT stored here; they use the append-friendly EncryptedLogStore.
 */
class MobilecoreStore(
    rootDir: File,
    private val io: SecureFileIo = PlaintextFileIo(),
) {

    private val stateDir = File(rootDir, "mobilecore-state")
    private val configFile = File(stateDir, "config.json")
    private val logCursorFile = File(stateDir, "log_cursor.json")
    private val sessionsDir = File(stateDir, "sessions")
    private val courseCacheDir = File(stateDir, "courses")
    private val credentialsDir = File(stateDir, "credentials")
    private val actionStatesDir = File(stateDir, "action-states")

    // serializeNulls preserves JsonObject fields (extra, raw)
    private val gson = GsonBuilder().serializeNulls().create()
    private val courseListType = object : TypeToken<List<CourseItem>>() {}.type

    // ── config ────────────────────────────────────────────────────────────────

    fun saveConfig(config: MobileConfig) = io.write(configFile, gson.toJson(config))
    fun loadConfig(): MobileConfig? =
        io.read(configFile)?.let { gson.fromJson(it, MobileConfig::class.java) }
    fun clearConfig() { io.delete(configFile) }

    // ── sessions ──────────────────────────────────────────────────────────────

    fun saveSession(session: SessionData) {
        val url = session.extra["preUrl"]?.asString?.takeIf { it.isNotBlank() } ?: ""
        val stored = StoredSession(session.platform, session.account, session, System.currentTimeMillis())
        io.write(File(sessionsDir, sessionFileName(session.platform, session.account, url)), gson.toJson(stored))
    }

    fun loadSession(platform: String, account: String, url: String = ""): SessionData? =
        io.read(File(sessionsDir, sessionFileName(platform, account, url)))
            ?.let { gson.fromJson(it, StoredSession::class.java).session }

    fun listSessions(): List<StoredSession> =
        sessionsDir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { gson.fromJson(io.read(f), StoredSession::class.java) }.getOrNull() }
            ?: emptyList()

    fun deleteSession(platform: String, account: String, url: String = ""): Boolean =
        io.delete(File(sessionsDir, sessionFileName(platform, account, url)))

    fun clearSessions() { sessionsDir.listFiles()?.forEach { it.delete() } }

    // credentials ------------------------------------------------------------

    fun saveCredential(platform: String, credential: AccountInput) {
        val stored = StoredCredential(platform, credential.account, credential, System.currentTimeMillis())
        io.write(File(credentialsDir, credentialFileName(platform, credential.account)), gson.toJson(stored))
    }

    fun loadCredential(platform: String, account: String): AccountInput? =
        io.read(File(credentialsDir, credentialFileName(platform, account)))
            ?.let { gson.fromJson(it, StoredCredential::class.java).credential }

    fun listCredentials(): List<StoredCredential> =
        credentialsDir.listFiles { f -> f.name.endsWith(".credential") }
            ?.mapNotNull { f -> runCatching { gson.fromJson(io.read(f), StoredCredential::class.java) }.getOrNull() }
            ?: emptyList()

    fun deleteCredential(platform: String, account: String): Boolean =
        io.delete(File(credentialsDir, credentialFileName(platform, account)))

    fun clearCredentials() { credentialsDir.listFiles()?.forEach { it.delete() } }

    // action states ----------------------------------------------------------

    fun saveActionState(state: StoredActionState) =
        io.write(File(actionStatesDir, actionStateFileName(state.platform, state.account, state.taskId, state.scope)), gson.toJson(state))

    fun loadActionState(platform: String, account: String, taskId: String, scope: String = "default"): StoredActionState? =
        io.read(File(actionStatesDir, actionStateFileName(platform, account, taskId, scope)))
            ?.let { gson.fromJson(it, StoredActionState::class.java) }

    fun listActionStates(): List<StoredActionState> =
        actionStatesDir.listFiles { f -> f.name.endsWith(".action") }
            ?.mapNotNull { f -> runCatching { gson.fromJson(io.read(f), StoredActionState::class.java) }.getOrNull() }
            ?: emptyList()

    fun deleteActionState(platform: String, account: String, taskId: String, scope: String = "default"): Boolean =
        io.delete(File(actionStatesDir, actionStateFileName(platform, account, taskId, scope)))

    fun deleteActionStates(platform: String, account: String): Int {
        val states = listActionStates().filter { it.platform == platform && it.account == account }
        states.forEach { deleteActionState(it.platform, it.account, it.taskId, it.scope) }
        return states.size
    }

    fun clearActionStates() { actionStatesDir.listFiles()?.forEach { it.delete() } }

    // ── course cache ────────────────────────────────────────────────────────────
    //
    // Cached course list per (platform, account), refreshed on each getCourses().
    // Same naming strategy as sessions: account is hashed, never exposed in the filename.

    fun saveCourseCache(platform: String, account: String, courses: List<CourseItem>) =
        io.write(File(courseCacheDir, courseCacheFileName(platform, account)), gson.toJson(courses))

    fun loadCourseCache(platform: String, account: String): List<CourseItem> =
        io.read(File(courseCacheDir, courseCacheFileName(platform, account)))
            ?.let { gson.fromJson<List<CourseItem>>(it, courseListType) }
            ?: emptyList()

    fun hasCourseCache(platform: String, account: String): Boolean =
        io.exists(File(courseCacheDir, courseCacheFileName(platform, account)))

    fun deleteCourseCache(platform: String, account: String): Boolean =
        io.delete(File(courseCacheDir, courseCacheFileName(platform, account)))

    /** Total cached courses across all accounts (used by the Home metric card). */
    fun cachedCourseCount(): Int =
        courseCacheDir.listFiles { f -> f.name.endsWith(".courses") }
            ?.sumOf { f ->
                runCatching { gson.fromJson<List<CourseItem>>(io.read(f), courseListType)?.size ?: 0 }.getOrDefault(0)
            }
            ?: 0

    fun clearCourseCache() { courseCacheDir.listFiles()?.forEach { it.delete() } }

    // ── log cursor ────────────────────────────────────────────────────────────

    fun saveLogCursor(cursor: String) =
        io.write(logCursorFile, gson.toJson(LogCursorState(cursor, System.currentTimeMillis())))

    fun loadLogCursor(): String =
        io.read(logCursorFile)
            ?.let { gson.fromJson(it, LogCursorState::class.java).cursor }
            ?: ""

    fun clearLogCursor() { io.delete(logCursorFile) }

    // ── clear all ─────────────────────────────────────────────────────────────

    fun clearAll() {
        clearConfig()
        clearSessions()
        clearCredentials()
        clearActionStates()
        clearCourseCache()
        clearLogCursor()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    companion object {
        /**
         * File name: <safePlatform>__<sha256(account)>.json
         * - platform gets a readable safe prefix (unsafe chars → _)
         * - account is never exposed; SHA-256 hex ensures no collision from sanitisation
         * - hash is computed from the original account string, not the sanitised one
         */
        internal fun sessionFileName(platform: String, account: String, url: String = ""): String =
            if (url.isBlank()) "${safeSegment(platform)}__${sha256(account)}.json"
            else "${safeSegment(platform)}__${sha256(account)}__${sha256(url)}.json"

        internal fun courseCacheFileName(platform: String, account: String, url: String = ""): String =
            if (url.isBlank()) "${safeSegment(platform)}__${sha256(account)}.courses"
            else "${safeSegment(platform)}__${sha256(account)}__${sha256(url)}.courses"

        internal fun credentialFileName(platform: String, account: String): String =
            "${safeSegment(platform)}__${sha256(account)}.credential"

        internal fun actionStateFileName(platform: String, account: String, taskId: String, scope: String): String =
            "${safeSegment(platform)}__${sha256(account)}__${safeSegment(scope)}__${sha256(taskId)}.action"

        private fun safeSegment(value: String): String =
            value.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "unknown" }

        private fun sha256(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
