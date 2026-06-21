package com.yatori.android.engine

import com.yatori.android.data.local.dao.LogDao
import com.yatori.android.data.local.dao.TaskDao
import com.yatori.android.data.local.entity.LogEntity
import com.yatori.android.data.local.entity.TaskEntity
import com.yatori.android.data.local.entity.TaskStatus
import com.yatori.android.domain.model.*
import com.yatori.android.engine.notify.CompletionNotifyService
import com.yatori.android.engine.notify.EmailNotifyService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrushEngine @Inject constructor(
    private val logDao: LogDao,
    private val taskDao: TaskDao,
    private val emailService: EmailNotifyService,
    private val completionNotify: CompletionNotifyService,
    private val yinghuaRunner: YinghuaBrushRunner,
    private val xuexitongRunner: XuexitongBrushRunner,
    private val enaeaRunner: EnaeaBrushRunner,
    private val cqieRunner: CqieBrushRunner,
    private val ketangxRunner: KetangxBrushRunner,
    private val welearnRunner: WeLearnBrushRunner,
    private val icveRunner: IcveBrushRunner,
    private val qsxtRunner: QsxtBrushRunner,
    private val hqkjRunner: HqkjBrushRunner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeTasks = MutableStateFlow<Map<String, TaskProgress>>(emptyMap())
    val activeTasks: StateFlow<Map<String, TaskProgress>> = _activeTasks
    private val jobMap = mutableMapOf<String, Job>()

    fun startAll(accounts: List<Account>, settings: AppSettings) =
        accounts.filter { it.enabled && !isRunning(it) }.forEach { start(it, settings) }

    fun isRunning(account: Account): Boolean =
        _activeTasks.value[account.id.toString()]?.state == BrushState.RUNNING

    fun start(account: Account, settings: AppSettings): String {
        val taskId = account.id.toString()
        // 取消同一账号的旧任务（如果有）
        jobMap.remove(taskId)?.cancel()
        jobMap[taskId] = scope.launch {
            taskDao.insert(TaskEntity(id = taskId, accountId = account.id,
                platform = account.platform.code, account = account.displayName))
            updateProgress(taskId, TaskProgress(taskId, account, BrushState.RUNNING))
            log(taskId, "INFO", account, "开始刷课")
            try {
                val logFn: suspend (String, String) -> Unit = { level, msg ->
                    log(taskId, level, account, msg)
                }
                runnerFor(account.platform).run(taskId, account, settings, this, { p ->
                    updateProgress(taskId, p)
                    scope.launch { taskDao.updateProgress(taskId, p.doneCourses, p.doneVideos, p.totalCourses, p.totalVideos) }
                }, logFn)
                log(taskId, "INFO", account, "所有课程学习完毕 ✓")
                taskDao.finish(taskId, TaskStatus.SUCCESS.name, System.currentTimeMillis())
                updateProgress(taskId, _activeTasks.value[taskId]?.copy(state = BrushState.SUCCESS) ?: return@launch)
                // 等价 Go userBlock 末尾的邮件通知逻辑
                emailService.sendCompletion(settings, account.informEmails, account.platform.displayName, account.displayName)
                completionNotify.notify(account.platform.displayName, account.displayName)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    taskDao.finish(taskId, TaskStatus.CANCELLED.name, System.currentTimeMillis())
                    updateProgress(taskId, _activeTasks.value[taskId]?.copy(state = BrushState.CANCELLED) ?: return@withContext)
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    log(taskId, "ERROR", account, "任务异常: ${e.message}")
                    taskDao.finish(taskId, TaskStatus.FAILED.name, System.currentTimeMillis(), e.message ?: "")
                    updateProgress(taskId, _activeTasks.value[taskId]?.copy(state = BrushState.FAILED, errorMessage = e.message) ?: return@withContext)
                }
            }
        }
        return taskId
    }

    fun cancel(taskId: String) { jobMap.remove(taskId)?.cancel() }
    fun cancelAll() = jobMap.keys.toList().forEach { cancel(it) }

    private fun runnerFor(platform: Platform): PlatformBrushRunner = when (platform) {
        Platform.YINGHUA, Platform.CANGHUI -> yinghuaRunner
        Platform.XUEXITONG               -> xuexitongRunner
        Platform.ENAEA                   -> enaeaRunner
        Platform.CQIE                    -> cqieRunner
        Platform.KETANGX                 -> ketangxRunner
        Platform.WELEARN                 -> welearnRunner
        Platform.ICVE                    -> icveRunner
        Platform.QSXT                    -> qsxtRunner
        Platform.HQKJ                    -> hqkjRunner
    }

    private fun updateProgress(id: String, p: TaskProgress) {
        _activeTasks.value = _activeTasks.value.toMutableMap().apply { put(id, p) }
    }

    suspend fun log(taskId: String, level: String, account: Account, message: String) {
        logDao.insert(LogEntity(level = level, platform = account.platform.displayName,
            account = account.displayName, message = message, taskId = taskId))
    }
}
