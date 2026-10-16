package com.finecat.pimobile.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.finecat.pimobile.android.platform.GatewayLifecycleEvent
import com.finecat.pimobile.android.platform.GatewayForegroundLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 仅在用户已发起且尚未收到 turn/end 的 Agent turn 期间运行。
 * 普通前台浏览不常驻服务；通知的“断开”操作会显式结束 socket 与后台保活。
 */
class GatewayConnectionService : Service() {
    private val graph: AndroidAppGraph
        get() = (application as PiAndroidApplication).graph
    private var idleObserver: Job? = null
    private val foregroundLifecycle = GatewayForegroundLifecycle(
        promote = {
            runCatching { startForeground(NOTIFICATION_ID, makeNotification()) }
                .onFailure {
                    graph.diagnostics.lifecycle(GatewayLifecycleEvent.KEEP_ALIVE_START_FAILED)
                }.isSuccess
        },
        isNeeded = { graph.gatewayRuntime.state.value.shouldKeepAliveInBackground },
        stopSelf = { startId -> stopSelfResult(startId) },
        removeNotification = {
            graph.diagnostics.lifecycle(GatewayLifecycleEvent.KEEP_ALIVE_STOP, keepAlive = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
        },
    )

    override fun onCreate() {
        super.onCreate()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.SERVICE_CREATED)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.SERVICE_STARTED)
        // 包括通知“断开”在内的每个入口先注册前台，之后才能停止。
        if (!foregroundLifecycle.beginStart(startId)) return START_NOT_STICKY
        if (intent?.action == ACTION_DISCONNECT) {
            graph.diagnostics.lifecycle(GatewayLifecycleEvent.SERVICE_DISCONNECT)
            graph.gatewayScope.launch { graph.gatewayRuntime.disconnect() }
        }
        if (idleObserver == null) {
            idleObserver = graph.applicationScope.launch {
                graph.gatewayRuntime.state
                    .map { it.shouldKeepAliveInBackground }
                    .distinctUntilChanged()
                    .collect { active -> if (!active) foregroundLifecycle.stopIfIdle() }
            }
        }
        // 新 startId 可能在观察器已处理过空闲之后才送达，不能只等待下一次状态变化。
        foregroundLifecycle.stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        idleObserver?.cancel()
        idleObserver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun makeNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GatewayConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.gateway_active_notification_title))
            .setContentText(getString(R.string.gateway_active_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.gateway_disconnect_action), disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gateway_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.gateway_notification_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "gateway-active-turn"
        private const val NOTIFICATION_ID = 12_001
        private const val ACTION_DISCONNECT = "com.finecat.pimobile.android.action.DISCONNECT_GATEWAY"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GatewayConnectionService::class.java))
        }

    }
}
