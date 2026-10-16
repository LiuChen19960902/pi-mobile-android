package com.finecat.pimobile.android.platform

/** 主线程调用：先满足每次前台启动，再按最新运行状态和 startId 收尾。 */
internal class GatewayForegroundLifecycle(
    private val promote: () -> Boolean,
    private val isNeeded: () -> Boolean,
    private val stopSelf: (Int) -> Boolean,
    private val removeNotification: () -> Unit,
) {
    private var latestStartId = 0
    private var registered = false

    fun beginStart(startId: Int): Boolean {
        latestStartId = startId
        registered = promote()
        if (!registered) stopSelf(startId)
        return registered
    }

    fun stopIfIdle() {
        if (!registered || isNeeded()) return
        // 有较新的启动尚未送达时拒绝旧 stop；也不能移除新运行需要的通知。
        if (stopSelf(latestStartId)) {
            registered = false
            removeNotification()
        }
    }
}
