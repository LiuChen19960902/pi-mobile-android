package com.finecat.pimobile.android.platform

import com.finecat.pimobile.shared.domain.SessionSummary
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnCompletionNoticeFactoryTest {
    @Test
    fun foregroundAndUnlockedSuppressesButLockScreenDoesNot() {
        assertFalse(TurnNotificationGate.shouldPost(appInForeground = true, deviceInteractive = true, keyguardLocked = false))
        assertTrue(TurnNotificationGate.shouldPost(appInForeground = true, deviceInteractive = true, keyguardLocked = true))
        assertTrue(TurnNotificationGate.shouldPost(appInForeground = true, deviceInteractive = false, keyguardLocked = false))
        assertTrue(TurnNotificationGate.shouldPost(appInForeground = false, deviceInteractive = true, keyguardLocked = false))
    }

    @Test
    fun agentSettledIsTheTurnEndThatBuildsACompletedNotice() {
        val frame = eventFrame("""{"type":"agent_settled"}""")
        val events = normalize(frame)
        assertEquals(listOf("turn/end"), events.map { it.event.type })

        val notice = TurnCompletionNoticeFactory.from(
            frame,
            events,
            listOf(session("s1", "修复登录")),
        )

        assertEquals("s1", notice?.sessionId)
        assertEquals("修复登录", notice?.sessionTitle)
        assertEquals(TurnCompletionOutcome.COMPLETED, notice?.outcome)
    }

    @Test
    fun assistantMessageEndIsNotATurnEnd() {
        val frame = eventFrame(
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
        )
        val events = normalize(frame)
        assertEquals(listOf("assistant/message"), events.map { it.event.type })
        assertNull(TurnCompletionNoticeFactory.from(frame, events, listOf(session("s1", "修复登录"))))
    }

    @Test
    fun abortedSettledMapsToStoppedOutcome() {
        val frame = eventFrame("""{"type":"agent_settled","aborted":true}""")
        val notice = TurnCompletionNoticeFactory.from(
            frame,
            normalize(frame),
            listOf(session("s1", "修复登录")),
        )
        assertEquals(TurnCompletionOutcome.ABORTED, notice?.outcome)
    }

    @Test
    fun blankTitleFallsBackToCwdAndThenGenericTitle() {
        val frame = eventFrame("""{"type":"agent_settled"}""")
        val events = normalize(frame)
        val fromCwd = TurnCompletionNoticeFactory.from(
            frame,
            events,
            listOf(session("s1", " ", cwd = "/home/user/code/pi-mobile-android/")),
        )
        assertEquals("pi-mobile-android", fromCwd?.sessionTitle)

        val noSession = TurnCompletionNoticeFactory.from(frame, events, emptyList())
        assertEquals("Pi 会话", noSession?.sessionTitle)
    }

    @Test
    fun nonEventFrameNeverNotifies() {
        val frame = GatewayWireDecoder.decode("""{"kind":"state","sessionId":"s1"}""")
        assertNull(TurnCompletionNoticeFactory.from(frame, emptyList(), emptyList()))
    }

    private fun eventFrame(eventJson: String): GatewayFrame = GatewayWireDecoder.decode(
        """{"kind":"event","sessionId":"s1","seq":1,"time":1.0,"event":$eventJson}"""
    )

    private fun normalize(frame: GatewayFrame): List<SessionEvent> =
        PiEventNormalizer("s1").normalize(1, 1.0, requireNotNull(frame.event), frame.entryId)

    private fun session(id: String, title: String, cwd: String? = null) = SessionSummary(
        id = id,
        title = title,
        lastActivityEpochSeconds = 1.0,
        isRunning = false,
        hasUnread = false,
        cwd = cwd,
    )
}
