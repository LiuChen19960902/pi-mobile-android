package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiContractCompatibilityTest {
    private fun event(text: String) = JsonValue.fromJsonElement(wireJson.parseToJsonElement(text))

    @Test
    fun visibleCustomTextDoesNotBorrowSemanticsFromCustomType() {
        val normalizer = PiEventNormalizer("s")
        for (customType in listOf("notice", "compaction", "empty-stop-retry")) {
            val records = normalizer.normalize(1, 1.0, event(
                """{"type":"message_end","message":{"role":"custom","customType":"$customType","display":true,"content":[{"type":"text","text":"可见内容"}]}}"""
            ), "visible-$customType")
            val item = ConversationProjector.make(records).single()
            assertEquals("notice/custom", records.single().event.type)
            assertEquals(ConversationItemKind.SYSTEM, item.kind)
            assertEquals("通知", item.title)
            assertEquals("可见内容", item.text)
            assertFalse(item.isError)
        }
    }

    @Test
    fun hiddenMessagesAndInternalEntriesNeverBecomeVisibleErrors() {
        val normalizer = PiEventNormalizer("s")
        for (customType in listOf("notice", "compaction", "empty-stop-retry")) {
            assertTrue(normalizer.normalize(1, 1.0, event(
                """{"type":"message_end","message":{"role":"custom","customType":"$customType","display":false,"content":"hidden"}}"""
            )).isEmpty())
            assertTrue(normalizer.normalize(2, 2.0, event(
                """{"type":"entry_appended","entry":{"type":"custom","customType":"$customType","data":{"attempt":1,"reason":"private"}}}"""
            )).isEmpty())
        }
    }

    @Test
    fun publicCustomEntryAndMessageHaveTheSameStableIdentity() {
        val normalizer = PiEventNormalizer("s")
        val entry = normalizer.normalize(1, 1.0, event(
            """{"type":"entry_appended","entry":{"type":"custom_message","id":"n1","customType":"notice","display":true,"content":"same"}}"""
        ))
        val message = normalizer.normalize(2, 2.0, event(
            """{"type":"message_end","message":{"role":"custom","customType":"notice","display":true,"content":"same"}}"""
        ), "n1")
        assertEquals(ConversationProjector.make(entry).single().id, ConversationProjector.make(message).single().id)
    }

    @Test
    fun nestedEndClosesTheMatchingCallAndTopLevelResultRemainsAuthoritative() {
        val normalizer = PiEventNormalizer("s")
        val parent = normalizer.normalize(1, 1.0, event(
            """{"type":"tool_execution_start","toolCallId":"parent","toolName":"custom-tool","args":{}}"""
        ))
        val child = normalizer.normalize(2, 2.0, event(
            """{"type":"tool_execution_start","toolCallId":"child","parentToolCallId":"parent","toolName":"read","args":{"path":"README.md"}}"""
        ))
        val childEndJson = event(
            """{"type":"tool_execution_end","toolCallId":"child","parentToolCallId":"parent","toolName":"read","result":{"content":[{"type":"text","text":"child result"}]},"isError":false}"""
        )
        val childEnd = normalizer.normalize(3, 3.0, childEndJson)
        assertTrue(normalizer.normalize(4, 4.0, childEndJson).isEmpty())
        assertTrue(normalizer.normalize(5, 5.0, event(
            """{"type":"tool_execution_end","toolCallId":"parent","toolName":"custom-tool","result":{"content":[{"type":"text","text":"not duplicated"}]},"isError":false}"""
        )).isEmpty())
        val parentResult = normalizer.normalize(6, 6.0, event(
            """{"type":"message_end","message":{"role":"toolResult","toolCallId":"parent","toolName":"custom-tool","content":[{"type":"text","text":"parent result"}],"isError":false}}"""
        ))
        val items = ConversationProjector.make(parent + child + childEnd + parentResult)
        assertEquals(2, items.count { it.kind == ConversationItemKind.TOOL })
        val results = items.filter { it.kind == ConversationItemKind.TOOL_RESULT }
        assertEquals(listOf("child", "parent"), results.map { it.toolCallId })
        assertEquals(listOf("child result", "parent result"), results.map { it.text })
        assertEquals("parent", results.first().parentToolCallId)
        assertEquals("parent", items.first { it.toolCallId == "child" }.parentToolCallId)
    }
    @Test
    fun missingDisplayIsHiddenLikePiTui() {
        val normalizer = PiEventNormalizer("s")
        assertTrue(normalizer.normalize(1, 1.0, event(
            """{"type":"message_end","message":{"role":"custom","customType":"notice","content":"not explicitly visible"}}"""
        )).isEmpty())
        assertTrue(normalizer.normalize(2, 2.0, event(
            """{"type":"entry_appended","entry":{"type":"custom_message","id":"n","customType":"notice","content":"not explicitly visible"}}"""
        )).isEmpty())
    }

    @Test
    fun nativeAssistantErrorRemainsVisibleWithoutPrivateEntries() {
        val records = PiEventNormalizer("s").normalize(1, 1.0, event(
            """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"upstream failed"}}"""
        ))
        val error = ConversationProjector.make(records).single()
        assertTrue(error.isError)
        assertTrue(error.text.orEmpty().contains("upstream failed"))
    }

    @Test
    fun nestedResultIsEmittedOnceInEitherCompletionOrder() {
        for (endFirst in listOf(true, false)) {
            val normalizer = PiEventNormalizer("s")
            normalizer.normalize(1, 1.0, event(
                """{"type":"tool_execution_start","toolCallId":"child","parentToolCallId":"parent","toolName":"read","args":{}}"""
            ))
            val end = event("""{"type":"tool_execution_end","toolCallId":"child","parentToolCallId":"parent","toolName":"read","result":{"content":[{"type":"text","text":"done"}]},"isError":true}""")
            val message = event("""{"type":"message_end","message":{"role":"toolResult","toolCallId":"child","toolName":"read","content":[{"type":"text","text":"done"}],"isError":true}}""")
            val first = normalizer.normalize(2, 2.0, if (endFirst) end else message)
            val second = normalizer.normalize(3, 3.0, if (endFirst) message else end)
            assertEquals("parent", first.single().event.parentCallId)
            assertEquals(true, first.single().event.isError)
            assertTrue(second.isEmpty())
            assertTrue(normalizer.normalize(4, 4.0, message).isEmpty())
        }
    }

}
