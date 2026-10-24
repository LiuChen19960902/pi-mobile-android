package com.finecat.pimobile.android

import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终态预览的载荷上限回收。
 *
 * 一条预览同时持有渲染用的 images（base64）和恢复用的 restoreImages（原始 base64），
 * 单张图就是 MB 级；只有用户点"恢复草稿"才会消费它们。没有上限时，一晚上若干次
 * 失败/未确认的带图发送会把整段载荷留在内存里。
 */
class OutgoingImagePreviewTrackerTest {

    @Test
    fun sendTokensAreUniqueAcrossTrackersAndProcessIncarnations() {
        // 请求身份会经 sent 帧进入 operationId；两个 tracker 各自从 send-1 起号时，
        // 不同进程/实例的请求会撞成同一个 operationId，网关只按操作 ID 去重。
        val first = OutgoingImagePreviewTracker().newSendToken()
        val second = OutgoingImagePreviewTracker().newSendToken()
        assertTrue("sendToken 必须跨 tracker 唯一：$first / $second", first != second)
        assertTrue("sendToken 需要带随机段：$first", Regex("^send-[0-9a-f]{12,}-1$").matches(first))
    }

    @Test
    fun handledConsumesOnlyItsOwnPreviewWithoutWaitingForUserEcho() {
        val tracker = OutgoingImagePreviewTracker()
        for (token in listOf("handled", "other")) {
            tracker.begin(token, "s-1", 0L, "draft", images(token), restoreImages(token))
        }
        tracker.trackAwaiting("handled", "s-1", 1L)
        assertEquals("handled", tracker.consumeSent("s-1", 10L, submissionAcknowledged = true)?.sendToken)
        assertNull(tracker.headAwaitingToken())
        assertEquals(listOf("other"), tracker.previews.map { it.sendToken })
        assertNull(tracker.settleLocalFailure("handled"))
    }

    @Test
    fun terminalPreviewsBeyondTheCapAreEvictedOldestFirst() {
        val tracker = OutgoingImagePreviewTracker()
        (1..8).forEach { index -> failedSend(tracker, "send-$index", "s-1") }

        assertTrue(tracker.pruneRestorable("s-1", 0L))

        val tokens = tracker.previews.map { it.sendToken }
        assertEquals(listOf("send-3", "send-4", "send-5", "send-6", "send-7", "send-8"), tokens)
        // 最近一次失败仍可恢复；被回收的那条不再提供恢复入口。
        assertNotNull(tracker.peekRestorable("send-8"))
        assertNull(tracker.peekRestorable("send-1"))
    }

    @Test
    fun inFlightPreviewsAreNeverEvicted() {
        val tracker = OutgoingImagePreviewTracker()
        // 10 条还在等 sent/回显的预览：不是回收对象。
        (1..10).forEach { index ->
            tracker.begin(
                token = "live-$index",
                sessionId = "s-1",
                newSessionEpoch = 0L,
                draftText = "草稿",
                images = images("live-$index"),
                restoreImages = restoreImages("live-$index")
            )
        }
        (1..8).forEach { index -> failedSend(tracker, "dead-$index", "s-1") }

        tracker.pruneRestorable("s-1", 0L)

        assertEquals(
            (1..10).map { "live-$it" } + (3..8).map { "dead-$it" },
            tracker.previews.map { it.sendToken }
        )
        assertTrue(tracker.previews.count { it.state == OutgoingImagePreviewState.SENDING } == 10)
    }

    /** 当前会话刚失败的那条，即便按全局顺序已经很旧，也必须留着恢复入口。 */
    @Test
    fun newestFailuresOfCurrentSessionStayRestorable() {
        val tracker = OutgoingImagePreviewTracker()
        failedSend(tracker, "old-in-s1", "s-1")
        (1..7).forEach { index -> failedSend(tracker, "s2-$index", "s-2") }
        failedSend(tracker, "new-in-s1", "s-1")

        tracker.pruneRestorable(currentSessionId = "s-1", currentNewSessionEpoch = 0L)

        val tokens = tracker.previews.map { it.sendToken }
        assertTrue("当前会话最近的失败要能恢复：$tokens", "old-in-s1" in tokens)
        assertTrue("new-in-s1" in tokens)
        assertEquals(6, tokens.size)
        assertNotNull(tracker.restoreDraft("old-in-s1"))
    }

    /**
     * 超时/断连出队的请求，它的迟到 sent 不能吞掉下一个请求的确认。
     *
     * sent 帧不带请求级 correlationId（`GatewayRequests.message` 也不带），唯一可用
     * 的关联是 sessionId：队头目标会话已知且与帧不等时，这一帧不可能是队头的应答。
     */
    @Test
    fun lateSentFromAnotherSessionDoesNotConsumeNextRequest() {
        val tracker = OutgoingImagePreviewTracker()
        tracker.begin(
            token = "send-1", sessionId = "s-1", newSessionEpoch = 0L,
            draftText = "带图草稿", images = images("send-1"), restoreImages = restoreImages("send-1")
        )
        tracker.trackAwaiting("send-1", "s-1", 1L)
        // 超时：只意味着未确认，token 已出队。
        assertEquals(
            OutgoingImagePreviewState.UNCONFIRMED,
            tracker.consumeFailure(asUnconfirmed = true)?.state
        )

        // 切到另一个会话再发一条带图消息。
        tracker.begin(
            token = "send-2", sessionId = "s-2", newSessionEpoch = 0L,
            draftText = "带图草稿", images = images("send-2"), restoreImages = restoreImages("send-2")
        )
        tracker.trackAwaiting("send-2", "s-2", 1L)

        // A 的迟到 sent：既不消费队头，也不把 B 绑到 s-1。
        assertNull(tracker.consumeSent("s-1", 20L))
        val pending = tracker.previews.last()
        assertEquals("send-2", pending.sendToken)
        assertEquals(OutgoingImagePreviewState.SENDING, pending.state)
        assertEquals("s-2", pending.sessionId)

        // B 自己的 sent 照常确认。
        val confirmed = tracker.consumeSent("s-2", 30L)
        assertEquals("send-2", confirmed?.sendToken)
        assertEquals(OutgoingImagePreviewState.CONFIRMED, confirmed?.state)
    }

    /** 同一会话重试：队头会话与帧会话一致，不能被会话守卫卡住（否则预览永久 SENDING）。 */
    @Test
    fun sameSessionRetryAfterTimeoutIsConfirmedByNextSent() {
        val tracker = OutgoingImagePreviewTracker()
        tracker.begin(
            token = "send-1", sessionId = "s-1", newSessionEpoch = 0L,
            draftText = "带图草稿", images = images("send-1"), restoreImages = restoreImages("send-1")
        )
        tracker.trackAwaiting("send-1", "s-1", 1L)
        tracker.consumeFailure(asUnconfirmed = true)

        tracker.begin(
            token = "send-2", sessionId = "s-1", newSessionEpoch = 0L,
            draftText = "带图草稿", images = images("send-2"), restoreImages = restoreImages("send-2")
        )
        tracker.trackAwaiting("send-2", "s-1", 1L)

        val confirmed = tracker.consumeSent("s-1", 40L)
        assertEquals("send-2", confirmed?.sendToken)
        assertEquals(OutgoingImagePreviewState.CONFIRMED, confirmed?.state)
    }

    /** 新会话首条：队头目标会话未知，帧内无请求级关联可依，仍按 FIFO 消费
     *  （与 runtime 对 "sent" lane 的完成顺序一致）。 */
    @Test
    fun sentForUnknownTargetSessionStillConsumesFifoHead() {
        val tracker = OutgoingImagePreviewTracker()
        tracker.begin(
            token = "send-1", sessionId = null, newSessionEpoch = 7L,
            draftText = "带图草稿", images = images("send-1"), restoreImages = restoreImages("send-1")
        )
        tracker.trackAwaiting("send-1", null, 1L)

        val confirmed = tracker.consumeSent("s-9", 10L)
        assertEquals("send-1", confirmed?.sendToken)
        assertEquals("s-9", confirmed?.sessionId)
    }

    @Test
    fun pruningIsANoOpWhileNothingIsTerminal() {
        val tracker = OutgoingImagePreviewTracker()
        failedSend(tracker, "send-1", "s-1")
        tracker.restoreDraft("send-1")

        assertEquals(false, tracker.pruneRestorable("s-1", 0L))
        assertTrue(tracker.previews.isEmpty())
    }

    private fun failedSend(tracker: OutgoingImagePreviewTracker, token: String, sessionId: String) {
        tracker.begin(
            token = token,
            sessionId = sessionId,
            newSessionEpoch = 0L,
            draftText = "带图草稿",
            images = images(token),
            restoreImages = restoreImages(token)
        )
        assertNotNull(tracker.settleLocalFailure(token))
    }

    private fun images(token: String) = listOf(
        GatewayImageAttachment(
            attachmentId = "att-$token",
            mediaType = "image/png",
            bytes = 8,
            dataBase64 = "AAECAw"
        )
    )

    private fun restoreImages(token: String) = listOf(
        AndroidPreparedImage(
            outgoing = GatewayOutgoingImage("image/png", "AAECAw", "att-$token"),
            width = 1,
            height = 1,
            byteCount = 8
        )
    )
}
