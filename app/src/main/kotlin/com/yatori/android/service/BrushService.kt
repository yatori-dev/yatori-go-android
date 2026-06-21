package com.yatori.android.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yatori.android.MainActivity
import com.yatori.android.R
import com.yatori.android.engine.BrushEngine
import com.yatori.android.engine.BrushState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

/** 刷课前台服务 — 保证 App 切到后台后任务不被系统杀死 */
@AndroidEntryPoint
class BrushService : Service() {

    @Inject lateinit var brushEngine: BrushEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val CHANNEL_ID = "brush_channel"
    private val NOTIF_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("刷课任务运行中"))
        observeProgress()
    }

    private fun observeProgress() {
        scope.launch {
            brushEngine.activeTasks.collectLatest { tasks ->
                val running = tasks.values.count { it.state == BrushState.RUNNING }
                val done = tasks.values.count { it.state == BrushState.SUCCESS }
                val text = when {
                    running > 0 -> "正在刷课：$running 个账号进行中，已完成 $done 个"
                    tasks.isEmpty() -> "无进行中任务"
                    else -> "所有任务已完成 ($done 个)"
                }
                updateNotification(text)
                if (running == 0 && tasks.isNotEmpty()) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            brushEngine.cancelAll()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BrushService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Yatori Android")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "刷课进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "刷课任务运行时的常驻通知"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(COMPLETION_CHANNEL_ID, "刷课完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "账号刷课完成时发送"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    companion object {
        const val ACTION_STOP = "com.yatori.android.STOP_BRUSH"
        val COMPLETION_CHANNEL_ID = "completion_channel"
    }
}
