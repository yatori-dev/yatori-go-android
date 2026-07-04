package dev.yatori.mobile.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.yatori.mobile.app.MainActivity
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.platform.platformDisplayName
import dev.yatori.mobile.runtime.operation.Operation
import dev.yatori.mobile.runtime.operation.OperationStatus
import dev.yatori.mobile.runtime.operation.OperationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps course-sync operations alive while the app is backgrounded.
 * Scheduling stays in CourseSyncManager; this service owns only foreground lifetime,
 * progress notification, cancellation entry, and finished-task alerts.
 */
class OperationForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val notifiedFinishedIds = mutableSetOf<String>()
    private var observeJob: Job? = null
    private var keepAliveJob: Job? = null
    @Volatile private var allowIdleStop = false
    private lateinit var container: AppContainer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(this)
        createChannels()
        startForeground(ONGOING_NOTIF_ID, buildOngoingNotification(null))
        keepAliveJob = scope.launch {
            delay(15_000)
            allowIdleStop = true
            if (container.operationController.active.isEmpty()) stopSelf()
        }
        observeJob = scope.launch {
            container.operationController.operations.collect { ops ->
                notifyFinishedOperations(ops)
                val active = ops.firstOrNull {
                    it.status == OperationStatus.RUNNING || it.status == OperationStatus.CANCELLING
                }
                if (active == null && allowIdleStop) {
                    stopSelf()
                } else {
                    notificationManager().notify(ONGOING_NOTIF_ID, buildOngoingNotification(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_OP_ID)
            container.operationController.operations.value
                .filter { id == null || it.id == id }
                .forEach { container.operationController.cancel(it.id) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        keepAliveJob?.cancel()
        super.onDestroy()
    }

    private fun buildOngoingNotification(active: Operation?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setContentTitle(active?.let { "正在进行：${opDisplayTitle(it)}" } ?: "Yatori 运行中")
            .setContentText(active?.detail?.ifBlank { null } ?: "等待任务")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent)

        // No progress bar — subtitle already shows the current task name.
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OperationForegroundService::class.java).apply {
                action = ACTION_CANCEL
                if (active != null) putExtra(EXTRA_OP_ID, active.id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
        return builder.build()
    }

    private fun notifyFinishedOperations(ops: List<Operation>) {
        ops.filter {
            it.type != OperationType.LOGIN &&
                (it.status == OperationStatus.DONE || it.status == OperationStatus.FAILED) &&
                notifiedFinishedIds.add(it.id)
        }.forEach { op ->
            val failed = op.status == OperationStatus.FAILED
            val title = if (failed) "任务执行失败" else "任务已完成"
            val detail = buildString {
                append(opDisplayTitle(op))
                if (op.detail.isNotBlank()) append("  ").append(op.detail)
            }
            notificationManager().notify(finishedNotificationId(op.id), buildFinishedNotification(title, detail, failed))
        }
    }

    private fun opDisplayTitle(op: Operation): String {
        val platformName = platformDisplayName(op.platform, fallback = op.platform)
        return if (op.accountMasked.isNotBlank()) "$platformName（${op.accountMasked}）"
        else platformName
    }

    private fun buildFinishedNotification(title: String, detail: String, failed: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        return NotificationCompat.Builder(this, FINISHED_CHANNEL_ID)
            .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail.ifBlank { "课程任务已经结束" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.ifBlank { "课程任务已经结束" }))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(FINISHED_VIBRATION)
            .setSound(soundUri)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL_ID, "任务执行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "课程任务运行时的常驻通知"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(FINISHED_CHANNEL_ID, "任务完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "课程任务完成或失败时响铃、振动并以高优先级提醒"
                enableVibration(true)
                vibrationPattern = FINISHED_VIBRATION
                setSound(soundUri, audioAttrs)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val ONGOING_CHANNEL_ID = "yatori_operations"
        private const val FINISHED_CHANNEL_ID = "yatori_operation_finished"
        private const val ONGOING_NOTIF_ID = 1001
        private val FINISHED_VIBRATION = longArrayOf(0L, 250L, 120L, 250L)
        const val ACTION_CANCEL = "dev.yatori.mobile.app.CANCEL_OPERATION"
        const val EXTRA_OP_ID = "op_id"

        fun start(context: Context) {
            val intent = Intent(context, OperationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        private fun finishedNotificationId(id: String): Int =
            20_000 + (id.hashCode() and 0x3FFF)
    }
}
