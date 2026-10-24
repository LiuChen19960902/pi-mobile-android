package com.finecat.pimobile.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import org.junit.Test

class AndroidErrorPresentationTest {
    @Test
    fun automaticReadsAndDiscardedFramesAreSilentButActualUserFailuresAreNot() {
        val holder = AndroidSharedStateHolder()
        try {
            for (type in listOf("state", "subscribe", "sessions", "host-mode")) {
                assertTrue(holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected(
                    type, "internal", automaticRefresh = true, serverResponse = true)))
                assertFalse(holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected(
                    type, "internal", serverResponse = true)))
            }
            assertTrue(holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected("transport", "stale-frame")))
            assertTrue(holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected("state", "no-active-request")))
            assertTrue(holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected("transport", "session-mismatch")))
            for (type in listOf("sent", "aborted", "dequeued", "subscribed", "model-scope-saved")) {
                assertTrue(holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected(type, "no-active-request")))
            }
            for (type in listOf("message", "abort", "dequeue", "provider-put", "provider-delete", "set-model", "compact", "host-mode-put", "model-scope-put", "bridge-start", "restart-session", "worktree-create", "worktree-continue", "entry-copy", "label-put")) {
                for (reason in listOf("internal", "send-failed", "no-active-request", "session-mismatch")) {
                    for (server in listOf(false, true)) for (automatic in listOf(false, true)) {
                        assertFalse("$type/$reason/server=$server/auto=$automatic must remain visible",
                            holder.isPassiveRecoveryRejection(GatewayRuntimeEvent.RequestRejected(
                                type, reason, serverResponse = server, automaticRefresh = automatic)))
                    }
                }
            }
        } finally { holder.close() }
    }

    @Test
    fun localFailureDoesNotInheritReconnectAction() {
        val holder = AndroidSharedStateHolder()
        holder.showPlatformError("connect-failed")
        assertEquals(AndroidSharedStateHolder.PlatformErrorAction.RECONNECT, holder.platformErrorAction)
        holder.showPlatformError("image-preprocess-failed")
        assertEquals("图片无法读取，请重新选择", holder.platformError)
        assertEquals(AndroidSharedStateHolder.PlatformErrorAction.DISMISS_ONLY, holder.platformErrorAction)
        holder.showPlatformError("私密异常 /secret/path")
        assertEquals("操作失败，请重试", holder.platformError)
        holder.clearPlatformError()
        assertEquals(null, holder.platformError)
        holder.close()
    }
}
