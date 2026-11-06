package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatHoldsLeadingGroup
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatLeadingProcessRunLength
import com.finecat.pimobile.android.ui.finecat.conversation.mergeFineCatProcessEntries
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FineCatLeadingGroupTest {
    private fun item(id: String, kind: ConversationItemKind, text: String = "") =
        ConversationItem(id, kind, "read", text, epochSeconds = 1.0, toolCallId = if (kind == ConversationItemKind.TOOL || kind == ConversationItemKind.TOOL_RESULT) "t-$id" else null)

    private fun runLength(items: List<ConversationItem>): Int =
        fineCatLeadingProcessRunLength(mergeFineCatProcessEntries(makeConversationDisplayEntries(items)))

    @Test fun userMessageFirstMeansNoPartialGroup() {
        val items = listOf(
            item("u", ConversationItemKind.USER, "问"),
            item("c", ConversationItemKind.TOOL, "cmd"),
            item("r", ConversationItemKind.TOOL_RESULT, "out"),
            item("a", ConversationItemKind.ASSISTANT, "答"),
        )
        assertEquals(0, runLength(items))
    }

    @Test fun leadingStepsAreAPartialGroup() {
        val items = listOf(
            item("r", ConversationItemKind.TOOL_RESULT, "out"),
            item("c2", ConversationItemKind.TOOL, "cmd"),
            item("r2", ConversationItemKind.TOOL_RESULT, "out2"),
            item("a", ConversationItemKind.ASSISTANT, "答"),
        )
        assertEquals(1, runLength(items))
    }

    @Test fun blankAssistantsBeforeAndBetweenStepBatchesAreIncludedInTheRun() {
        val items = listOf(
            item("a0", ConversationItemKind.ASSISTANT, " \n"),
            item("c0", ConversationItemKind.TOOL, "cmd0"),
            item("a1", ConversationItemKind.ASSISTANT, ""),
            item("c1", ConversationItemKind.TOOL, "cmd1"),
            item("final", ConversationItemKind.ASSISTANT, "答"),
        )
        val merged = mergeFineCatProcessEntries(makeConversationDisplayEntries(items))
        assertEquals(1, merged.filterIsInstance<ConversationDisplayEntry.Process>().size)
        assertEquals(merged.size - 1, runLength(items))
    }

    @Test fun onlyBlankAssistantsAreNotAGroup() {
        val items = listOf(
            item("a0", ConversationItemKind.ASSISTANT, " "),
            item("u", ConversationItemKind.USER, "问"),
        )
        assertEquals(0, runLength(items))
    }

    @Test fun emptyListHasNoRun() {
        assertEquals(0, runLength(emptyList()))
    }

    @Test fun visibleAssistantTextStopsTheRun() {
        val items = listOf(
            item("c", ConversationItemKind.TOOL, "cmd"),
            item("a", ConversationItemKind.ASSISTANT, "有正文"),
            item("c2", ConversationItemKind.TOOL, "cmd2"),
        )
        assertEquals(1, runLength(items))
    }

    @Test fun gateHoldsOnlyWhenRunHasCursorNoErrorAndBudgetLeft() {
        assertEquals(true, fineCatHoldsLeadingGroup(2, true, false, 0, 8))
        assertEquals(false, fineCatHoldsLeadingGroup(0, true, false, 0, 8))
        assertEquals(false, fineCatHoldsLeadingGroup(2, false, false, 0, 8))
        assertEquals(false, fineCatHoldsLeadingGroup(2, true, true, 0, 8))
        assertEquals(true, fineCatHoldsLeadingGroup(2, true, false, 7, 8))
        assertEquals(false, fineCatHoldsLeadingGroup(2, true, false, 8, 8))
        assertEquals(false, fineCatHoldsLeadingGroup(2, true, false, 0, 8, stalled = true))
    }

    @Test fun trailingBlankAssistantAfterTheGroupJoinsTheRunButErrorAssistantStopsIt() {
        val blankTail = listOf(
            item("c", ConversationItemKind.TOOL, "cmd"),
            item("a0", ConversationItemKind.ASSISTANT, " "),
            item("u", ConversationItemKind.USER, "问"),
        )
        assertEquals(2, runLength(blankTail))
        val errorTail = listOf(
            item("c", ConversationItemKind.TOOL, "cmd"),
            ConversationItem("e", ConversationItemKind.ASSISTANT, "x", "", epochSeconds = 1.0, isError = true),
        )
        assertEquals(1, runLength(errorTail))
    }
}
