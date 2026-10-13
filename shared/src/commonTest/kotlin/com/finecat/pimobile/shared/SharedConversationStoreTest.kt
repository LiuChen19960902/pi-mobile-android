package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.facade.SharedConversationBootstrap
import com.finecat.pimobile.shared.facade.SharedConversationPatch
import com.finecat.pimobile.shared.facade.SharedConversationStore
import com.finecat.pimobile.shared.facade.SharedMviEvent
import com.finecat.pimobile.shared.facade.SharedMviEventObserver
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationStoreTest {
    @Test
    fun streamingTextUsesInsertThenDeltaOnlyPatchAndFinalReplacement() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        assertEquals(1, wireJson.decodeFromString<SharedConversationBootstrap>(received.single().statePayloadJson!!).schema)

        assertTrue(store.receiveEvent(eventJson(1, chunk("Hel"))).accepted)
        val first = patch(received.last())
        assertEquals("insert", first.operations.single().kind)
        assertEquals("Hel", first.operations.single().item?.text)

        assertTrue(store.receiveEvent(eventJson(2, chunk("lo"))).accepted)
        val secondPayload = received.last().statePayloadJson!!
        val second = wireJson.decodeFromString<SharedConversationPatch>(secondPayload)
        assertEquals("append-text", second.operations.single().kind)
        assertEquals("lo", second.operations.single().delta)
        assertNull(second.operations.single().item)
        assertFalse("Hello" in secondPayload)

        assertTrue(store.receiveEvent(eventJson(3, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Hello!"))).accepted)
        val final = patch(received.last())
        assertEquals(listOf("remove", "insert"), final.operations.map { it.kind })
        assertEquals("Hello!", final.operations.last().item?.text)
    }

    @Test
    fun tokenPatchSizeDependsOnDeltaRatherThanAccumulatedText() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.receiveEvent(eventJson(1, chunk("x".repeat(4_096))))
        store.receiveEvent(eventJson(2, chunk("a")))
        val shortPatchSize = received.last().statePayloadJson!!.length
        store.receiveEvent(eventJson(3, chunk("b")))
        val laterPatchSize = received.last().statePayloadJson!!.length

        assertEquals(shortPatchSize, laterPatchSize)
        assertTrue(laterPatchSize < 256)
    }

    @Test
    fun historyBaselineReplacesAllAndDuplicateLiveEventFailsWithoutTransition() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        val baseline = listOf(
            event(1, GatewayEvent("user/message", text = "question")),
            event(2, GatewayEvent("assistant/message", turn = 1, step = 1, text = "answer"))
        )

        assertTrue(store.replaceSession("s1", wireJson.encodeToString(baseline)).accepted)
        val replacement = patch(received.last())
        assertTrue(replacement.replacesAll)
        assertEquals(listOf("question", "answer"), replacement.replacementItems?.map { it.text })
        assertEquals(2, replacement.lastSequence)

        val duplicate = store.receiveEvent(eventJson(2, GatewayEvent("assistant/message", text = "duplicate")))
        assertFalse(duplicate.accepted)
        assertEquals("conversation-event-failed", duplicate.errorCode)
        assertEquals("error", received.last().kind)

        assertTrue(store.receiveEvent(eventJson(3, GatewayEvent("assistant/message", turn = 2, step = 1, text = "tail"))).accepted)
        assertEquals("tail", patch(received.last()).operations.single().item?.text)
    }

    @Test
    fun clearEmitsEmptyReplacementAndCancelledObserverStopsReceiving() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        val subscription = store.subscribe(SharedMviEventObserver(received::add))
        store.receiveEvent(eventJson(1, GatewayEvent("user/message", text = "one")))
        assertTrue(store.clearSession("s1").accepted)
        val cleared = patch(received.last())
        assertTrue(cleared.replacesAll)
        assertTrue(cleared.replacementItems.orEmpty().isEmpty())

        subscription.cancel()
        store.receiveEvent(eventJson(2, GatewayEvent("user/message", text = "two")))
        assertEquals(3, received.size)
    }

    /**
     * 同一条目在整窗重拉后合成 seq 会变（历史负空间重新分配）：item id 必须由
     * entryId 派生并保持不变，否则滚动位置与过程组折叠态会整片丢失。
     */
    @Test
    fun entryIdentityKeepsItemIdsAcrossBaselineReplacement() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        val firstWindow = listOf(
            event(1, GatewayEvent("user/message", text = "一问")).copy(entryId = "e1"),
            event(2, GatewayEvent("assistant/message", turn = 1, step = 0, text = "二答")).copy(entryId = "e2")
        )
        assertTrue(store.replaceSession("s1", wireJson.encodeToString(firstWindow)).accepted)
        val beforeIds = patch(received.last()).replacementItems!!.map { it.id }

        // 重拉：同两个条目落在新的合成 seq 上，另加一条实时记录。
        val secondWindow = listOf(
            event(-4, GatewayEvent("user/message", text = "一问")).copy(entryId = "e1"),
            event(-3, GatewayEvent("assistant/message", turn = 1, step = 0, text = "二答")).copy(entryId = "e2"),
            event(-2, GatewayEvent("user/message", text = "三问")).copy(entryId = "e3"),
            event(5, GatewayEvent("user/message", text = "四问", source = "user")).copy(entryId = "e4")
        )
        assertTrue(store.replaceSession("s1", wireJson.encodeToString(secondWindow)).accepted)
        val after = patch(received.last()).replacementItems!!
        assertEquals(listOf("一问", "二答", "三问", "四问"), after.map { it.text })
        assertEquals(beforeIds, after.take(2).map { it.id })
    }

    /** 同身份副本只能投影一次：否则 replacement 里出现重复 item id，整批会被拒。 */
    @Test
    fun duplicateIdentityRecordsCollapseToOneItemInBaseline() {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        val overlapping = listOf(
            event(2, GatewayEvent("user/message", text = "同一条")).copy(entryId = "e1"),
            // 旧网关重叠窗口 / 重复投递：同一条目的另一份副本（seq 不同）。
            event(5, GatewayEvent("user/message", text = "同一条")).copy(entryId = "e1")
        )
        assertTrue(store.replaceSession("s1", wireJson.encodeToString(overlapping)).accepted)
        assertEquals(1, patch(received.last()).replacementItems!!.size)
    }

    private fun patch(event: SharedMviEvent): SharedConversationPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    private fun chunk(text: String) =
        GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = text)

    private fun event(sequence: Int, gatewayEvent: GatewayEvent) =
        SessionEvent("s1", sequence, sequence.toDouble(), gatewayEvent)

    private fun eventJson(sequence: Int, gatewayEvent: GatewayEvent): String =
        wireJson.encodeToString(event(sequence, gatewayEvent))
}
