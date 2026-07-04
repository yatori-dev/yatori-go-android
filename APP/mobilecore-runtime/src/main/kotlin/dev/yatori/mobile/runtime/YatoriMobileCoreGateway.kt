package dev.yatori.mobile.runtime

import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.YatoriMobileCore
import dev.yatori.mobile.api.dto.*

/** Adapts the real [YatoriMobileCore] to [CoreGateway]. */
class YatoriMobileCoreGateway(
    private val core: YatoriMobileCore = YatoriMobileCore(),
) : CoreGateway {
    override suspend fun init(baseDir: String) = core.init(baseDir)
    override suspend fun healthCheck() = core.healthCheck()

    override suspend fun getConfigSchema() = core.getConfigSchema()
    override suspend fun setConfig(config: MobileConfig) = core.setConfig(config)
    override suspend fun setXuexitongFontTables(glyfJson: String, cmapJson: String) =
        core.setXuexitongFontTables(glyfJson, cmapJson)
    override suspend fun getConfig() = core.getConfig()

    override suspend fun startLogin(platform: Platform, account: AccountInput) = core.startLogin(platform, account)
    override suspend fun continueLogin(taskId: String, result: OcrResult) = core.continueLogin(taskId, result)
    override suspend fun cancelLogin(taskId: String) = core.cancelLogin(taskId)

    override suspend fun getCourses(session: SessionData) = core.getCourses(session)
    override suspend fun getCourseDetail(session: SessionData, course: CourseItem) = core.getCourseDetail(session, course)
    override suspend fun getTasks(session: SessionData, course: CourseItem) = core.getTasks(session, course)
    override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>) =
        core.runTask(session, task, options)

    override suspend fun getLogs(cursor: String) = core.getLogs(cursor)
    override suspend fun clearLogs() = core.clearLogs()
    override suspend fun setLogLevel(level: String) = core.setLogLevel(level)
}
