package com.finecat.pimobile.android

import com.finecat.pimobile.android.ui.TrajectoryUiProjection
import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.SessionEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTrajectoryRealtimeProjectionTest {
    @Test
    fun streamingFramesContinuouslyUpdateTrajectoryBeforeFinalMessage() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val normalizer = PiEventNormalizer("session-a")
        normalizer.normalize(
            1, 100.0,
            piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        )

        projection.accept(chunk(normalizer, 2, "A"))
        val first = projection.trajectory("session-a")
        assertEquals("A", first.single { it.kind == TrajectoryNodeKind.ASSISTANT }.subtitle)
        assertTrue(first.any { it.kind == TrajectoryNodeKind.REQUEST })

        projection.accept(chunk(normalizer, 3, "B"))
        val second = projection.trajectory("session-a")
        assertEquals("AB", second.single { it.kind == TrajectoryNodeKind.ASSISTANT }.subtitle)
        assertTrue(second.single { it.kind == TrajectoryNodeKind.ASSISTANT }.endSequence > first.single {
            it.kind == TrajectoryNodeKind.ASSISTANT
        }.endSequence)

        projection.accept(
            live(
                normalizer, 4, 104.0,
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"完成"}]}}"""
            )
        )
        val completed = projection.trajectory("session-a")
        assertEquals("完成", completed.single { it.kind == TrajectoryNodeKind.ASSISTANT }.subtitle)
        assertTrue(completed.none { it.id.startsWith("assistant-stream-") })
        projection.close()
    }

    @Test
    fun historyPageWithoutUserOrTurnStillDisplaysAssistantAndTool() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val raw = """{"kind":"history","sessionId":"session-a","messages":[
                {"role":"assistant","content":[{"type":"text","text":"历史回答"}],"timestamp":1000},
                {"role":"toolResult","toolCallId":"call-1","toolName":"read","content":[{"type":"text","text":"历史工具输出"}],"timestamp":2000}
            ],"entryIds":["a1","t1"],"hasMore":false,"oldestEntryId":"a1"}"""
            projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")
            val rows = TrajectoryUiProjection.displayRows(projection.trajectory("session-a"))
            val displayed = rows.filter { it.startsTurn || it.turn == null }
            assertEquals(listOf(TrajectoryNodeKind.ASSISTANT, TrajectoryNodeKind.TOOL), displayed.map { it.node.kind })
            assertTrue(displayed.all { it.turn == null })
            assertEquals(listOf("a1", "t1"), displayed.map { it.node.records.single().entryId })
            assertEquals("历史回答", displayed.first().node.subtitle)
            assertEquals("历史工具输出", displayed.last().node.subtitle)
        } finally {
            projection.close()
        }
    }

    @Test
    fun historyRowsRemainOutsideLiveTurnWhenRealtimeEventsArrive() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val raw = """{"kind":"history","sessionId":"session-a","messages":[
                {"role":"user","content":"旧问题","timestamp":1000},
                {"role":"assistant","content":[{"type":"text","text":"旧回答"}],"timestamp":2000}
            ],"entryIds":["u1","a1"],"hasMore":false,"oldestEntryId":"u1"}"""
            projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")
            val normalizer = PiEventNormalizer("session-a")
            projection.accept(live(normalizer, 1, 3.0,
                """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"新问题"}]}}"""))
            projection.accept(live(normalizer, 2, 4.0,
                """{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
            projection.accept(chunk(normalizer, 3, "实时回答"))
            val rows = TrajectoryUiProjection.displayRows(projection.trajectory("session-a"))
            val displayed = rows.filter { it.startsTurn || it.turn == null }
            assertEquals(3, displayed.size)
            assertTrue(displayed.take(2).all { it.turn == null })
            assertTrue(displayed.last().startsTurn)
            assertEquals("旧回答", displayed[1].node.subtitle)
        } finally {
            projection.close()
        }
    }

    @Test
    fun subscribingDuringAssistantStreamDoesNotRegroupHistoricalRows() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val raw = """{"kind":"history","sessionId":"session-a","messages":[
                {"role":"user","content":"旧问题","timestamp":1000},
                {"role":"assistant","content":[{"type":"text","text":"旧回答"}],"timestamp":2000}
            ],"entryIds":["u1","a1"],"hasMore":false,"oldestEntryId":"u1"}"""
            projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "session-a")
            val normalizer = PiEventNormalizer("session-a")
            projection.accept(live(normalizer, 2, 4.0,
                """{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
            projection.accept(chunk(normalizer, 3, "实时回答"))
            val rows = TrajectoryUiProjection.displayRows(projection.trajectory("session-a"))
            val displayed = rows.filter { it.startsTurn || it.turn == null }
            assertEquals(3, displayed.size)
            assertTrue(displayed.take(2).all { it.turn == null })
            assertTrue(displayed.last().startsTurn)
            assertEquals("旧回答", displayed[1].node.subtitle)
        } finally {
            projection.close()
        }
    }

    private fun AndroidGatewayProjection.accept(record: LiveFrame) {
        acceptFrame(record.raw, record.frame, "session-a", record.events)
    }

    private fun chunk(normalizer: PiEventNormalizer, sequence: Int, delta: String): LiveFrame = live(
        normalizer, sequence, (100 + sequence).toDouble(),
        """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"$delta","contentIndex":0}}"""
    )

    private data class LiveFrame(
        val raw: String,
        val frame: GatewayFrame,
        val events: List<SessionEvent>
    )

    private fun live(
        normalizer: PiEventNormalizer,
        sequence: Int,
        time: Double,
        eventJson: String
    ): LiveFrame {
        val raw = """{"kind":"event","sessionId":"session-a","seq":$sequence,"time":$time,"event":$eventJson}"""
        val frame = GatewayWireDecoder.decode(raw)
        val events = normalizer.normalize(sequence, time, requireNotNull(frame.event))
        return LiveFrame(raw, frame, events)
    }

    private fun piEvent(json: String): JsonValue =
        JsonValue.fromJsonElement(Json.parseToJsonElement(json))
}
