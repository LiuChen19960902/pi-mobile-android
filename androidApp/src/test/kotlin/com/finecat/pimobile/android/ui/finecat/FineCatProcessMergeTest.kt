package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.displayRoundKey
import com.finecat.pimobile.android.ui.groupAssistantRounds
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatSteps
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatWorkingGroupId
import com.finecat.pimobile.android.ui.finecat.conversation.mergeFineCatProcessEntries
import com.finecat.pimobile.android.ui.finecat.conversation.timeSeparatorsByRoundKey
import com.finecat.pimobile.android.ui.finecat.conversation.turnTimes
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.ToolCall
import com.finecat.pimobile.shared.protocol.ToolDelta
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

    @Test fun visibleProseRemainsBetweenItsPrecedingAndFollowingSteps() {
        val raw = makeConversationDisplayEntries(listOf(
            item("c1", ConversationItemKind.TOOL, "first"),
            item("a", ConversationItemKind.ASSISTANT, "visible prose"),
            item("c2", ConversationItemKind.TOOL, "second"),
        ))
        val merged = mergeFineCatProcessEntries(raw)
        val round = groupAssistantRounds(merged).single()
        assertEquals(raw, merged)
        assertEquals(merged, round)
        assertTrue(round[0] is ConversationDisplayEntry.Process)
        assertEquals("a", round[1].id)
        assertTrue(round[2] is ConversationDisplayEntry.Process)
    }

    @Test fun runningOperationsStayAfterTheProseThatPrecededThem() {
        val displayed = entries(listOf(
            item("u", ConversationItemKind.USER, "request"),
            item("a", ConversationItemKind.ASSISTANT, "I will inspect the files"),
            item("r", ConversationItemKind.REASONING, "check the details"),
            item("call", ConversationItemKind.TOOL, "read README.md", toolId = "t"),
        ))
        val rounds = groupAssistantRounds(displayed)
        assertEquals(displayed, rounds.flatten())
        assertEquals(listOf("a", "process:a:0"), rounds.last().map { it.id })
        assertEquals("a", displayRoundKey(rounds.last()))
        assertEquals(rounds.last().last().id, fineCatWorkingGroupId(displayed, isStreaming = true))
        assertEquals(listOf("check the details", "read README.md"),
            fineCatSteps(groups(displayed).single().group).map { it.detail ?: it.command })
    }

    @Test fun projectedHistoryKeepsReasoningThenProseThenToolInTheRootDisplayChain() {
        val projected = ConversationProjector.make(listOf(
            SessionEvent("history", -2, 100.0, GatewayEvent(
                "assistant/message", text = "I will inspect the files", reasoning = "first think",
                toolCalls = listOf(ToolCall("t", "read", JsonValue.StringValue("README.md"))),
            ), entryId = "assistant"),
            SessionEvent("history", -1, 101.0, GatewayEvent(
                "tool/result", callId = "t", preview = "file contents",
            ), entryId = "result"),
        ))
        val displayed = entries(projected)
        val round = groupAssistantRounds(displayed).single()
        assertEquals(displayed, round)
        assertEquals(3, round.size)
        assertEquals("first think", fineCatSteps((round[0] as ConversationDisplayEntry.Process).group).single().detail)
        assertEquals("I will inspect the files", (round[1] as ConversationDisplayEntry.Message).item.text)
        val tool = fineCatSteps((round[2] as ConversationDisplayEntry.Process).group).single()
        assertEquals("file contents", tool.detail)
        assertTrue(tool.completed)
    }

    @Test fun projectedLiveStepsStayAfterEarlierProseThroughStreamFinalization() {
        val projector = ConversationProjector()
        projector.fold(listOf(
            SessionEvent("live", 1, 100.0, GatewayEvent("user/message", text = "request")),
            SessionEvent("live", 2, 101.0, GatewayEvent(
                "assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "Before tools",
            )),
            SessionEvent("live", 3, 102.0, GatewayEvent(
                "assistant/message", turn = 1, step = 0, text = "Before tools",
            )),
        ))
        projector.fold(listOf(
            SessionEvent("live", 4, 103.0, GatewayEvent(
                "assistant/chunk", turn = 1, step = 1, chunkType = "reasoning-delta", text = "Inspect files",
            )),
            SessionEvent("live", 5, 104.0, GatewayEvent(
                "assistant/chunk", turn = 1, step = 1, chunkType = "tool-call-delta",
                tool = ToolDelta("t", "read", "README.md"),
            )),
        ))
        val streaming = entries(projector.items)
        val streamingRound = groupAssistantRounds(streaming).last()
        assertEquals(streaming, groupAssistantRounds(streaming).flatten())
        assertEquals("Before tools", (streamingRound.first() as ConversationDisplayEntry.Message).item.text)
        val runningGroup = streamingRound.last() as ConversationDisplayEntry.Process
        assertEquals(runningGroup.id, fineCatWorkingGroupId(streaming, isStreaming = true))
        assertEquals(listOf("Inspect files", "README.md"),
            fineCatSteps(runningGroup.group).map { it.detail ?: it.command })

        projector.fold(listOf(
            SessionEvent("live", 6, 105.0, GatewayEvent(
                "assistant/message", turn = 1, step = 1, reasoning = "Inspect files",
                toolCalls = listOf(ToolCall("t", "read", JsonValue.StringValue("README.md"))),
            )),
            SessionEvent("live", 7, 106.0, GatewayEvent(
                "tool/call", callId = "t", name = "read", arguments = JsonValue.StringValue("README.md"),
            )),
            SessionEvent("live", 8, 107.0, GatewayEvent("tool/result", callId = "t", preview = "file contents")),
        ))
        val completed = entries(projector.items)
        val completedRound = groupAssistantRounds(completed).last()
        assertEquals(completed, groupAssistantRounds(completed).flatten())
        assertEquals("Before tools", (completedRound.first() as ConversationDisplayEntry.Message).item.text)
        assertEquals(displayRoundKey(streamingRound), displayRoundKey(completedRound))
        val completedGroup = completedRound.last() as ConversationDisplayEntry.Process
        assertEquals(runningGroup.id, completedGroup.id)
        val steps = fineCatSteps(completedGroup.group)
        assertEquals(2, steps.size)
        assertEquals("Inspect files", steps.first().detail)
        assertEquals("file contents", steps.last().detail)
        assertTrue(steps.last().completed)
        assertNull(fineCatWorkingGroupId(completed, isStreaming = false))
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
