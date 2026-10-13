package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.AndroidGatewayProjection
import com.finecat.pimobile.android.ui.ConversationProcessGroup
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatSteps
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.ToolCall
import org.junit.Assert.*
import org.junit.Test

class FineCatHistoricalCommandTest {
    private val args = JsonValue.ObjectValue(mapOf("command" to JsonValue.StringValue("echo " + "历史参数".repeat(100))))
    private fun record(seq: Int, event: GatewayEvent, entryId: String? = null) =
        SessionEvent("history", seq, 100.0 + seq, event, entryId = entryId)
    private fun assistant(seq: Int = -2) = record(seq, GatewayEvent("assistant/message",
        toolCalls = listOf(ToolCall("bash-1", "bash", args))), "entry-assistant")
    private fun result(seq: Int = -1) = record(seq, GatewayEvent("tool/result", callId = "bash-1", preview = "output"), "entry-result")

    @Test fun actualHistoryWireShapeReachesFineCatCommandAndOutput() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("history")
            val raw = """{"kind":"history","sessionId":"history","messages":[
                {"role":"assistant","content":[{"type":"toolCall","toolCallId":"bash-1","toolName":"bash","input":{"command":"pwd"}}],"timestamp":1000},
                {"role":"toolResult","toolCallId":"bash-1","toolName":"bash","content":[{"type":"text","text":"/tmp"}],"timestamp":2000}
            ],"entryIds":["assistant-entry","result-entry"],"hasMore":false,"oldestEntryId":"assistant-entry"}"""
            projection.acceptFrame(raw, GatewayWireDecoder.decode(raw), "history")
            val step = fineCatSteps(ConversationProcessGroup("group", projection.snapshot().conversation)).single()
            assertTrue(step.command!!.contains("pwd"))
            assertEquals("/tmp", step.detail)
            assertTrue(step.completed)
        } finally {
            projection.close()
        }
    }

    @Test fun historyToolCallsReachFullCommandDetailAndPairWithResult() {
        val items = ConversationProjector.make(listOf(assistant(), result()))
        val step = fineCatSteps(ConversationProcessGroup("group", items)).single()
        assertTrue(step.command!!.contains("历史参数".repeat(100)))
        assertEquals("output", step.detail)
        assertTrue(step.completed)
        assertFalse(step.failed)
        assertNull("历史assistant时间不能冒充工具执行时间", step.durationText)
    }

    @Test fun recoveredCallIdsRemainStableWhenOlderHistoryChangesSequences() {
        val initial = ConversationProjector.make(listOf(assistant(), result()))
        val older = record(-20, GatewayEvent("user/message", text = "older"), "older-entry")
        val paged = ConversationProjector.make(listOf(older, assistant(-10), result(-9)))
        assertEquals(initial.single { it.kind == ConversationItemKind.TOOL }.id,
            paged.single { it.kind == ConversationItemKind.TOOL }.id)
    }

    @Test fun liveAssistantToolCallsDoNotCreateDuplicateExecutionRows() {
        val items = ConversationProjector.make(listOf(assistant(1),
            record(2, GatewayEvent("tool/call", callId = "bash-1", name = "bash", arguments = args)), result(3)))
        assertEquals(1, items.count { it.kind == ConversationItemKind.TOOL })
        assertEquals(1, fineCatSteps(ConversationProcessGroup("group", items)).size)
    }

    @Test fun explicitCallInMixedHistoryAndLiveBatchWinsOverEmbeddedArguments() {
        val actual = JsonValue.StringValue("actual live arguments")
        val items = ConversationProjector.make(listOf(assistant(), result(),
            record(1, GatewayEvent("tool/call", callId = "bash-1", name = "bash", arguments = actual))))
        assertEquals(1, items.count { it.kind == ConversationItemKind.TOOL })
        assertEquals("\"actual live arguments\"", items.single { it.kind == ConversationItemKind.TOOL }.text)
        val step = fineCatSteps(ConversationProcessGroup("group", items)).single()
        assertEquals("output", step.detail)
        assertEquals("\"actual live arguments\"", step.command)
    }

    @Test fun replayAfterHistoryDoesNotAppendAnotherCall() {
        val projector = ConversationProjector().apply { rebuild(listOf(assistant(), result())) }
        val id = projector.items.single { it.kind == ConversationItemKind.TOOL }.id
        projector.fold(listOf(record(1, GatewayEvent("tool/call", callId = "bash-1", name = "bash", arguments = args))))
        assertEquals(id, projector.items.single { it.kind == ConversationItemKind.TOOL }.id)
    }

    @Test fun genuinelyMissingCallStillHasNoInventedCommand() {
        val step = fineCatSteps(ConversationProcessGroup("group", ConversationProjector.make(listOf(result())))).single()
        assertNull(step.command)
        assertEquals("output", step.detail)
    }
}
