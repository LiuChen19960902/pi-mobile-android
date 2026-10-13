package com.finecat.pimobile.android

import com.finecat.pimobile.shared.protocol.GatewayFrame

/** subscribed 证明 SDK 会话已打开；迟到的冷 history 可能再次覆盖其统计。 */
internal class SessionControlsRefresh {
    private var subscribedSessionId: String? = null

    fun reset() {
        subscribedSessionId = null
    }

    fun afterFrame(frame: GatewayFrame, selectedSessionId: String?): String? {
        val sessionId = frame.sessionId?.takeIf { it == selectedSessionId } ?: return null
        if (frame.kind == "subscribed") {
            subscribedSessionId = sessionId
            return sessionId
        }
        return sessionId.takeIf {
            frame.kind == "history" && frame.contextUsage == null && subscribedSessionId == sessionId
        }
    }
}
