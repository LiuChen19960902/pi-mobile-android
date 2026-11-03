package com.finecat.pimobile.android.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.finecat.pimobile.android.MainActivity
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.domain.SessionSummary
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.SessionEvent

/** 一轮结束的系统通知内容；只放会话身份和结束类型，不复制正文。 */
data class TurnCompletionNotice(
    val sessionId: String,
    val sessionTitle: String,
    val outcome: TurnCompletionOutcome,
)

enum class TurnCompletionOutcome { COMPLETED, ABORTED }

/**
 * 只有“App 可见、屏幕亮着、设备已解锁”同时成立时才静默。
 * 亮屏但仍停在锁屏时，进程生命周期可能已经回到 foreground，
 * 不能单凭 Activity 可见性判断用户是否真的看得到会话。
 */
internal object TurnNotificationGate {
    fun shouldPost(
        appInForeground: Boolean,
        deviceInteractive: Boolean,
        keyguardLocked: Boolean,
    ): Boolean = !(appInForeground && deviceInteractive && !keyguardLocked)
}

/**
 * 从既有事件流提取通知：只认 Pi 的 agent_settled（归一为 turn/end）。
 * message_end 可能先于回合真正落定，不能作为完成信号。
 */
object TurnCompletionNoticeFactory {
    fun from(
        frame: GatewayFrame,
        events: List<SessionEvent>,
        sessions: List<SessionSummary>,
    ): TurnCompletionNotice? {
        if (frame.kind != "event") return null
        val end = events.firstOrNull { it.event.type == "turn/end" } ?: return null
        val sessionId = end.sessionId.takeIf(String::isNotBlank)
            ?: frame.sessionId?.takeIf(String::isNotBlank)
            ?: return null
        val session = sessions.firstOrNull { it.id == sessionId }
        return TurnCompletionNotice(
            sessionId = sessionId,
            sessionTitle = sessionTitle(session),
            outcome = if (frame.event?.get("aborted")?.booleanValue == true) {
                TurnCompletionOutcome.ABORTED
            } else {
                TurnCompletionOutcome.COMPLETED
            },
        )
    }

    private fun sessionTitle(session: SessionSummary?): String {
        val title = session?.title?.trim().orEmpty()
        if (title.isNotEmpty()) return title
        return session?.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "Pi 会话"
    }
}

/**
 * Android 通知出口。App 可见、屏幕亮着且设备已解锁时静默；
 * 完成事件实际到达时，后台、锁屏或熄屏都发通知；连接已断则不会补发。
 * 通知不带消息正文，锁屏公开版本只显示通用文案。
 */
class SessionTurnNotifier(
    context: Context,
    private val isAppInForeground: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    init {
        createChannel()
    }

    @SuppressLint("MissingPermission")
    fun notify(notice: TurnCompletionNotice) {
        if (!TurnNotificationGate.shouldPost(
                appInForeground = isAppInForeground(),
                deviceInteractive = powerManager?.isInteractive == true,
                keyguardLocked = keyguardManager?.isKeyguardLocked == true,
            )
        ) return
        if (!canPostNotifications()) return
        val title = notice.sessionTitle
        val body = appContext.getString(
            if (notice.outcome == TurnCompletionOutcome.ABORTED) {
                R.string.session_turn_notification_aborted
            } else {
                R.string.session_turn_notification_completed
            }
        )
        val openIntent = Intent(appContext, MainActivity::class.java)
            .setData(Uri.parse("pimobile://session/${Uri.encode(notice.sessionId)}"))
            .putExtra(MainActivity.EXTRA_SESSION_ID, notice.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.pi_logo)
            .setContentTitle(appContext.getString(R.string.session_turn_notification_public_title))
            .setContentText(appContext.getString(R.string.session_turn_notification_public_text))
            .setContentIntent(contentIntent)
            .build()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.pi_logo)
            .setColor(ContextCompat.getColor(appContext, R.color.pi_logo_coral))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        runCatching { manager.notify(notificationTag(notice.sessionId), NOTIFICATION_ID, notification) }
    }

    fun cancel(sessionId: String) {
        runCatching { manager.cancel(notificationTag(sessionId), NOTIFICATION_ID) }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.session_turn_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.session_turn_notification_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationTag(sessionId: String) = "turn-complete:$sessionId"

    companion object {
        private const val CHANNEL_ID = "session-turn-complete"
        private const val NOTIFICATION_ID = 21_001
    }
}
