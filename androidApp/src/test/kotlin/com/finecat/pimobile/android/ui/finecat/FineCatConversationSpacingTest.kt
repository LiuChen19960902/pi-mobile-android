package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.ConversationProcessGroup
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.android.ui.groupAssistantRounds
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatEntryGap
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatEntryHasVisibleBody
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import org.junit.Assert.*
import org.junit.Test

class FineCatConversationSpacingTest {
    private val body = ConversationDisplayEntry.Message(
        ConversationItem("body", ConversationItemKind.ASSISTANT, "Pi", "正文", epochSeconds = 0.0))
    private fun steps(id: String) = ConversationDisplayEntry.Process(ConversationProcessGroup(id,
        listOf(ConversationItem("tool-$id", ConversationItemKind.TOOL, "read", "参数", epochSeconds = 0.0))))

    @Test fun collapsedGroupIsCompactOnBothSides() {
        assertEquals(4, fineCatEntryGap(body, steps("a"), 24))
        assertEquals(4, fineCatEntryGap(steps("a"), body, 12))
        assertEquals(4, fineCatEntryGap(steps("a"), steps("b"), 24))
    }

    @Test fun processGapDoesNotDependOnRoundBoundary() {
        assertEquals(4, fineCatEntryGap(steps("a"), body, 12))
        assertEquals(4, fineCatEntryGap(steps("a"), body, 24))
        assertEquals(4, fineCatEntryGap(body, steps("a"), 12))
        assertEquals(4, fineCatEntryGap(body, steps("a"), 24))
    }

    @Test fun processFooterIsCompactButFirstEntryHasNoLeadingGap() {
        assertEquals(4, fineCatEntryGap(steps("a"), null, 24))
        assertEquals(0, fineCatEntryGap(null, steps("a"), 24))
    }

    @Test fun ordinaryMessagesAndFooterKeepTheirExistingSpacing() {
        assertEquals(24, fineCatEntryGap(body, body, 24))
        assertEquals(24, fineCatEntryGap(body, null, 24))
        assertEquals(0, fineCatEntryGap(null, body, 24))
    }

    @Test fun blankAssistantHasNoSpacingButImagesAndUserBubbleArePreserved() {
        val blank = body.copy(item = body.item.copy(text = " \n\t"))
        assertFalse(fineCatEntryHasVisibleBody(blank))
        assertTrue(fineCatEntryHasVisibleBody(blank.copy(item = blank.item.copy(images =
            listOf(GatewayImageAttachment("image", "image/png", 1))))))
        assertTrue(fineCatEntryHasVisibleBody(blank.copy(item = blank.item.copy(kind = ConversationItemKind.USER))))
        assertTrue(fineCatEntryHasVisibleBody(steps("a")))
    }

    @Test fun whitespaceAssistantKeepsBatchAndFinalBodyInSeparateRounds() {
        val messages = (0..1).flatMap { index ->
            listOf(
                body.item.copy(id = "blank-$index", text = " \n"),
                body.item.copy(id = "tool-$index", kind = ConversationItemKind.TOOL, toolCallId = "call-$index"),
                body.item.copy(id = "result-$index", kind = ConversationItemKind.TOOL_RESULT, toolCallId = "call-$index"),
            )
        } + body.item
        val rounds = groupAssistantRounds(makeConversationDisplayEntries(messages))
        assertEquals(3, rounds.size)
        assertEquals(listOf(2, 2, 1), rounds.map { it.size })
        assertTrue(rounds[0][0] is ConversationDisplayEntry.Process)
        assertTrue(rounds[1][0] is ConversationDisplayEntry.Process)
        assertEquals("body", rounds[2].single().id)
        assertEquals(4, fineCatEntryGap(rounds[1].first(), rounds[2].first(), 24))
    }
}
