package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.displayRoundKey
import com.finecat.pimobile.android.ui.groupAssistantRounds
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatSteps
import com.finecat.pimobile.android.ui.finecat.conversation.mergeFineCatProcessEntries
import com.finecat.pimobile.android.ui.finecat.conversation.timeSeparatorsByRoundKey
import com.finecat.pimobile.android.ui.finecat.conversation.turnTimes
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class FineCatProcessMergeTest {
    private fun item(id: String, kind: ConversationItemKind, text: String = "", time: Double = 1.0,
                     toolId: String? = null) =
        ConversationItem(id, kind, "read", text, epochSeconds = time, toolCallId = toolId)

    private fun entries(items: List<ConversationItem>) =
        mergeFineCatProcessEntries(makeConversationDisplayEntries(items))

    private fun groups(entries: List<ConversationDisplayEntry>) =
        entries.filterIsInstance<ConversationDisplayEntry.Process>()

    @Test fun fourBatchesBecomeOneHeaderWithAllStepsInOrder() {
        val source = buildList {
            add(item("u", ConversationItemKind.USER, "request"))
            repeat(4) { n ->
                // Historical assistant text is projected before its tool calls.
                add(item("a$n", ConversationItemKind.ASSISTANT, " \n"))
                add(item("r$n", ConversationItemKind.REASONING, "thought $n"))
                add(item("c$n", ConversationItemKind.TOOL, "command $n", toolId = "t$n"))
                add(item("o$n", ConversationItemKind.TOOL_RESULT, "output $n", toolId = "t$n"))
            }
            add(item("final", ConversationItemKind.ASSISTANT, "visible final answer"))
        }
        assertEquals(4, groups(makeConversationDisplayEntries(source)).size)
        val merged = groups(entries(source)).single()
        val steps = fineCatSteps(merged.group)
        assertEquals(8, steps.size)
        assertEquals(List(4) { "thought $it" }, steps.filter { it.isThought }.map { it.detail })
        assertEquals(List(4) { "command $it" }, steps.filterNot { it.isThought }.map { it.command })
        assertEquals(List(4) { "output $it" }, steps.filterNot { it.isThought }.map { it.detail })
        assertTrue(steps.filterNot { it.isThought }.all { it.completed })
        assertEquals(source.filter { it.kind in setOf(ConversationItemKind.REASONING,
            ConversationItemKind.TOOL, ConversationItemKind.TOOL_RESULT) }, merged.group.items)
    }

    @Test fun visibleProseIsABarrierBeforeRoundSorting() {
        val raw = makeConversationDisplayEntries(listOf(
            item("c1", ConversationItemKind.TOOL, "first"),
            item("a", ConversationItemKind.ASSISTANT, "visible prose"),
            item("c2", ConversationItemKind.TOOL, "second"),
        ))
        // Existing round sorting makes these two processes visually adjacent.
        val sorted = groupAssistantRounds(raw).single()
        assertTrue(sorted[0] is ConversationDisplayEntry.Process)
        assertTrue(sorted[1] is ConversationDisplayEntry.Process)
        assertEquals("Do not merge across real prose just because rounds are sorted", raw,
            mergeFineCatProcessEntries(raw))
    }

    @Test fun userSystemAndStatusEachStopMerging() {
        listOf(ConversationItemKind.USER, ConversationItemKind.SYSTEM, ConversationItemKind.STATUS).forEach { kind ->
            assertEquals("Barrier $kind", 2, groups(entries(listOf(
                item("c1", ConversationItemKind.TOOL), item("barrier", kind),
                item("c2", ConversationItemKind.TOOL),
            ))).size)
        }
    }

    @Test fun imageOnlyAssistantAndEmptyErrorStopMerging() {
        listOf(
            item("image", ConversationItemKind.ASSISTANT).copy(
                images = listOf(GatewayImageAttachment("img", "image/png", 12))),
            item("error", ConversationItemKind.ASSISTANT).copy(isError = true),
        ).forEach { barrier ->
            assertEquals(2, groups(entries(listOf(item("c1", ConversationItemKind.TOOL), barrier,
                item("c2", ConversationItemKind.TOOL)))).size)
        }
    }

    @Test fun emptyMessagesKeepIdentityOrderAndTimestampsWithoutMutatingInput() {
        val source = listOf(item("c1", ConversationItemKind.TOOL),
            item("a1", ConversationItemKind.ASSISTANT, "", 123.0),
            item("a2", ConversationItemKind.ASSISTANT, " \n", 456.0),
            item("c2", ConversationItemKind.TOOL))
        val raw = makeConversationDisplayEntries(source)
        val original = raw.toList()
        val merged = mergeFineCatProcessEntries(raw)
        val before = raw.filterIsInstance<ConversationDisplayEntry.Message>()
        val after = merged.filterIsInstance<ConversationDisplayEntry.Message>()
        assertEquals(before, after)
        before.zip(after).forEach { (a, b) -> assertSame(a, b) }
        assertEquals(original, raw)
        assertEquals(2, groups(raw).size)
        assertEquals(1, groups(merged).size)
        assertEquals(merged, mergeFineCatProcessEntries(merged))
    }

    @Test fun lateAndOutOfOrderResultsPairAcrossEmptyAssistant() {
        val source = listOf(item("c1", ConversationItemKind.TOOL, "one", toolId = "t1"),
            item("c2", ConversationItemKind.TOOL, "two", toolId = "t2"),
            item("a", ConversationItemKind.ASSISTANT, " \n"),
            item("o2", ConversationItemKind.TOOL_RESULT, "result two", toolId = "t2"),
            item("o1", ConversationItemKind.TOOL_RESULT, "result one", toolId = "t1"))
        val steps = fineCatSteps(groups(entries(source)).single().group)
        assertEquals(listOf("one", "two"), steps.map { it.command })
        assertEquals(listOf("result one", "result two"), steps.map { it.detail })
        assertEquals(2, steps.map { it.id }.toSet().size)
        assertTrue(steps.all { it.completed })
    }

    @Test fun appendingBatchAndFinalizingReasoningKeepFirstGroupAndExistingStepKeys() {
        val user = item("u", ConversationItemKind.USER, "request")
        val streaming = item("stream-reason-1-1", ConversationItemKind.REASONING, "thinking")
        val before = groups(entries(listOf(user, streaming))).single()
        val after = groups(entries(listOf(user, streaming.copy(id = "settled", text = "complete thought"),
            item("a", ConversationItemKind.ASSISTANT, " \n"),
            item("r2", ConversationItemKind.REASONING, "second thought")))).single()
        assertEquals(before.id, after.id)
        assertEquals(fineCatSteps(before.group).first().id, fineCatSteps(after.group).first().id)
        assertEquals(listOf("complete thought", "second thought"), fineCatSteps(after.group).map { it.detail })
    }

    @Test fun proseAppearingInEmptyMessageSeparatesGroupsAgain() {
        val source = listOf(item("c1", ConversationItemKind.TOOL),
            item("a", ConversationItemKind.ASSISTANT, " \n"), item("c2", ConversationItemKind.TOOL))
        val before = groups(entries(source)).single()
        val after = groups(entries(source.map { if (it.id == "a") it.copy(text = "new visible text") else it }))
        assertEquals(2, after.size)
        assertEquals(before.id, after.first().id)
        assertNotEquals(after[0].id, after[1].id)
        assertEquals(listOf("c1"), after[0].group.items.map { it.id })
        assertEquals(listOf("c2"), after[1].group.items.map { it.id })
    }

    @Test fun timestampFallbackAndFirstRoundKeySurviveMerging() {
        val raw = makeConversationDisplayEntries(listOf(
            item("c1", ConversationItemKind.TOOL, time = 0.0),
            item("a1", ConversationItemKind.ASSISTANT, " \n", 18000.0),
            item("c2", ConversationItemKind.TOOL, time = 0.0),
            item("a2", ConversationItemKind.ASSISTANT, "", 18300.0),
        ))
        val before = groupAssistantRounds(raw)
        val after = groupAssistantRounds(mergeFineCatProcessEntries(raw))
        assertEquals(displayRoundKey(before.first()), displayRoundKey(after.first()))
        assertEquals(turnTimes(before), turnTimes(after))
        val zone = ZoneId.of("UTC")
        assertEquals(timeSeparatorsByRoundKey(before, zone), timeSeparatorsByRoundKey(after, zone))
    }
}
