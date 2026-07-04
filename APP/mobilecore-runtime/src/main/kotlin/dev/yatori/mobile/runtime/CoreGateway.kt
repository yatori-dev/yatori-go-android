package dev.yatori.mobile.runtime

import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.*

/**
 * Isolation boundary over the real [dev.yatori.mobile.api.YatoriMobileCore].
 *
 * Every capability the UI needs from the Go core MUST be exposed here so the UI
 * layer never reaches into mobilecore-api directly. Mirrors the full set of
 * exported functions in API_CONTRACT.md.
 */
interface CoreGateway {
    suspend fun init(baseDir: String): InitResult
    suspend fun healthCheck(): HealthInfo

    // config
    suspend fun getConfigSchema(): List<ConfigField>
    suspend fun setConfig(config: MobileConfig)
    suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String)
    suspend fun getConfig(): MobileConfig

    // login
    suspend fun startLogin(platform: Platform, account: AccountInput): LoginResult
    suspend fun continueLogin(taskId: String, result: OcrResult): LoginResult
    suspend fun cancelLogin(taskId: String)

    // courses / tasks
    suspend fun getCourses(session: SessionData): List<CourseItem>
    suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem>
    suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem>
    suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any> = emptyMap()): RunTaskResult

    // logs
    suspend fun getLogs(cursor: String = ""): LogResult
    suspend fun clearLogs()
    suspend fun setLogLevel(level: String)
}
