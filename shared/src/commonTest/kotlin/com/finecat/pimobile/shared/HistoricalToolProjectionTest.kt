package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HistoricalToolProjectionTest {
    private fun event(seq: Int, event: GatewayEvent, entry: String) = SessionEvent("history", seq, 100.0, event, entryId = entry)
    private fun history() = listOf(
        event(-3, GatewayEvent("assistant/message", toolCalls = listOf(
            ToolCall("bash", "bash", JsonValue.StringValue("pwd")),
            ToolCall("code", "run_code", JsonValue.StringValue("print(1)")),
        )), "assistant"),
        event(-2, GatewayEvent("tool/result", callId = "code", preview = "1"), "result-code"),
        event(-1, GatewayEvent("tool/result", callId = "bash", preview = "/tmp"), "result-bash"),
    )

    @Test fun historyRestoresEachCallIdAndJsonToolKindWithoutReorderingResults() {
        val items = ConversationProjector.make(history())
        assertEquals(listOf("bash", "code", "code", "bash"), items.map { it.toolCallId })
        assertEquals(listOf(ConversationItemKind.TOOL, ConversationItemKind.JSON_TOOL,
            ConversationItemKind.TOOL_RESULT, ConversationItemKind.TOOL_RESULT), items.map { it.kind })
        assertEquals(listOf("\"pwd\"", "\"print(1)\"", "1", "/tmp"), items.map { it.text })
        assertEquals(4, items.map { it.id }.distinct().size)
    }

    @Test fun snapshotRollbackRestoresProjectionBeforeHistoricalFold() {
        val projector = ConversationProjector()
        val before = projector.snapshot()
        val operations = projector.foldWithOperations(history())
        assertEquals(4, operations.size)
        assertEquals(4, operations.count { it.kind == "insert" })
        projector.restore(before)
        assertEquals(before, projector.snapshot())
        assertEquals(operations, projector.foldWithOperations(history()))
    }

    @Test fun missingOrInvalidCallIdentityNeverInventsArguments() {
        val items = ConversationProjector.make(listOf(event(-1, GatewayEvent("assistant/message",
            toolCalls = listOf(ToolCall("", "bash", JsonValue.StringValue("bad")),
                ToolCall("no-args", "bash"))), "assistant")))
        assertEquals(1, items.size)
        assertEquals("no-args", items.single().toolCallId)
        assertEquals("", items.single().text)
        assertFalse(items.any { it.text == "bad" })
    }
}
