package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiToolEventsChainTest {
    private val wireJson = Json { ignoreUnknownKeys = true }

    @Test
    fun nestedResultWithoutStartDoesNotCloseAnotherKnownCall() {
        val normalizer = PiEventNormalizer("s")
        val jsons = listOf(
            """{"type":"tool_execution_start","toolCallId":"parent","toolName":"custom-tool","args":{}}""",
            """{"type":"tool_execution_end","toolCallId":"missing-child","parentToolCallId":"parent","toolName":"read","result":{"content":[{"type":"text","text":"child result"}]},"isError":false}"""
        )
        val items = ConversationProjector.make(jsons.flatMapIndexed { index, json ->
            normalizer.normalize(index + 1, index + 1.0,
                JsonValue.fromJsonElement(wireJson.parseToJsonElement(json)))
        })
        val group = makeConversationDisplayEntries(items).filterIsInstance<ConversationDisplayEntry.Process>().single().group
        assertEquals(2, group.tools.size)
        assertEquals(null, group.tools.first().result)
        assertEquals(null, group.tools.last().call)
        assertEquals("child result", group.tools.last().result?.text)
    }

    @Test
    fun arbitraryToolsKeepOrdinaryCallAndResultRowsWithoutPrivateStripRules() {
        for (name in listOf("read", "Agent", "another-custom-tool")) {
            val normalizer = PiEventNormalizer("s")
            val jsons = listOf(
                """{"type":"tool_execution_start","toolCallId":"call","toolName":"$name","args":{"path":"README.md"}}""",
                """{"type":"message_end","message":{"role":"toolResult","toolCallId":"call","toolName":"$name","content":[{"type":"text","text":"done"}],"isError":false}}"""
            )
            val events = jsons.flatMapIndexed { index, json ->
                normalizer.normalize(index + 1, index + 1.0,
                    JsonValue.fromJsonElement(wireJson.parseToJsonElement(json)))
            }
            val items = ConversationProjector.make(events)
            assertEquals(listOf(ConversationItemKind.TOOL, ConversationItemKind.TOOL_RESULT), items.map { it.kind })
            val group = makeConversationDisplayEntries(items).filterIsInstance<ConversationDisplayEntry.Process>().single().group
            assertEquals(name, group.tools.single().call?.title)
            assertEquals("done", group.tools.single().result?.text)
            assertTrue(group.tools.single().result != null)
        }
    }
}
