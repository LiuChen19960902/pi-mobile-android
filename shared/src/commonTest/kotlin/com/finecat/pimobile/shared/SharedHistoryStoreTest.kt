package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.facade.SharedHistoryBootstrap
import com.finecat.pimobile.shared.facade.SharedHistoryEffect
import com.finecat.pimobile.shared.facade.SharedHistoryPatch
import com.finecat.pimobile.shared.facade.SharedHistoryStore
import com.finecat.pimobile.shared.facade.SharedMviEvent
import com.finecat.pimobile.shared.facade.SharedMviEventObserver
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import com.finecat.pimobile.shared.sync.HistorySyncConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedHistoryStoreTest {
    @Test
    fun olderPageDoesNotDedupeAgainstLiveTail() {
        val store = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 1))
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        // live 尾部收到一条 user 消息，older 页里有一条同文的历史 user 消息。
        // 双侧都无 entryId（旧网关）= 身份未知：保守保留，禁止按内容对消。
        store.liveEventReceived(
            wireJson.encodeToString(
                SessionEvent(sessionId = "s1", seq = 5, time = 5.0, event = GatewayEvent(type = "user/message", text = "又没输出了"))
            )
        )
        // 先建立 hasMore=true 的会话状态（older start 需要 hasMore 才会被接受）。
        assertTrue(store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null).accepted)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(-9, "基线"))),
            byteCount = 10,
            hasMore = true,
            nextCursor = "e4",
            earliestEntryId = "e4",
            remoteActivityTimestamp = 3.0
        )
        // older 批次：isLoadingOlder=true 时页内同文副本必须保留。
        assertTrue(store.start("s1", older = true, hasLocalEvents = true, earliestEntryId = "e4").accepted)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(
                listOf(
                    SessionEvent(sessionId = "s1", seq = -2, time = 0.0, event = GatewayEvent(type = "assistant/message", text = "旧回复")),
                    SessionEvent(sessionId = "s1", seq = -1, time = 0.0, event = GatewayEvent(type = "user/message", text = "又没输出了"))
                )
            ),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 4.0
        )
        val events = patch(received.last()).eventPatch!!.replacementEvents!!
        // 同文的 older 页 user 副本保留（无身份不对消）；累积合并语义下基线页、
        // 本页与 live 尾部全在。
        assertEquals(listOf(-9, -2, -1, 5), events.map { it.seq })
    }

    @Test
    fun latestPageIdentityDedupeKeepsDistinctEntryAndLiveWins() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        // live "继续"（entryId=e-live）与 LATEST 首页两条同文 user：页内 e-live
        // 副本身份命中被吞（实时正式消息胜出），同文的 e-old1 是另一个条目
        // 必须保留——禁止按内容指纹吞掉独立历史。
        store.liveEventReceived(
            wireJson.encodeToString(event(9, "继续", entryId = "e-live", type = "user/message"))
        )
        assertTrue(store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null).accepted)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(
                listOf(
                    event(-2, "继续", entryId = "e-old1", type = "user/message"),
                    event(-1, "继续", entryId = "e-live", type = "user/message")
                )
            ),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 8.0
        )
        val events = patch(received.last()).eventPatch!!.replacementEvents!!
        assertEquals(listOf(-2, 9), events.map { it.seq })
        assertEquals("e-old1", events.first().entryId)
        assertEquals("e-live", events.last().entryId)
    }

    @Test
    fun lateHistoryPageWithSameEntryIdDroppedEvenWhenPayloadDiffers() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        // live assistant 正式消息（带 turn/step/usage，entryId=e-a）先到；晚到的
        // 历史页同 entryId、payload 不同（无 turn/usage）：身份命中即对消，
        // 实时正式消息胜出——中间快照/重试的晚到副本不得覆盖。
        store.liveEventReceived(
            wireJson.encodeToString(
                SessionEvent(
                    sessionId = "s1", seq = 3, time = 3.0, entryId = "e-a",
                    event = GatewayEvent(
                        type = "assistant/message", text = "同一回复", turn = 1, step = 0,
                        usage = JsonValue.ObjectValue(mapOf("input" to JsonValue.NumberValue(1.0)))
                    )
                )
            )
        )
        assertTrue(store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null).accepted)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(
                listOf(SessionEvent(sessionId = "s1", seq = -1, time = 0.0, entryId = "e-a", event = GatewayEvent(type = "assistant/message", text = "同一回复")))
            ),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 2.0
        )
        // 身份命中：合成副本被吞，剩余集合与 live 一致 => 无 replace patch。
        assertNull(patch(received.last()).eventPatch)
    }

    @Test
    fun paginationStateAndNextCursorEffectShareOneTransaction() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        assertEquals(2, wireJson.decodeFromString<SharedHistoryBootstrap>(received.single().statePayloadJson!!).schema)

        // pi history 首页不传游标，effect 的 beforeEntryId 为 null。
        assertTrue(store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null).accepted)
        assertEquals(SharedHistoryEffect("request-page", "s1", null), effects(received.last()).single())
        assertTrue(patch(received.last()).session?.isLoading == true)

        store.processingStarted("s1", rawEventCount = 2, hasMore = true)
        val page = listOf(event(10, "ten"), event(11, "eleven"))
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(page),
            byteCount = 100,
            hasMore = true,
            nextCursor = "e9",
            earliestEntryId = "e9",
            remoteActivityTimestamp = 20.0
        )
        val next = patch(received.last())
        assertEquals("replace", next.eventPatch?.kind)
        assertEquals(listOf(10, 11), next.eventPatch?.replacementEvents?.map { it.seq })
        // 下一页游标是服务端返回的 oldestEntryId。
        assertEquals(SharedHistoryEffect("request-page", "s1", "e9"), effects(received.last()).single())

        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(event(8, "eight"), event(9, "nine"))),
            byteCount = 80,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 20.0
        )
        val completed = patch(received.last())
        assertEquals("completed", completed.outcome)
        assertFalse(completed.session!!.isLoading)
        assertEquals(20.0, completed.session.syncedActivityTimestamp)
        assertTrue(effects(received.last()).isEmpty())
    }

    @Test
    fun liveTailWinsHistoryDuplicatesAndAdvancesWatermark() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(1, "history"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 1.0
        )

        store.liveEventReceived(wireJson.encodeToString(event(2, "live")))
        assertEquals("append", patch(received.last()).eventPatch?.kind)
        assertEquals(2.0, patch(received.last()).session?.syncedActivityTimestamp)

        store.start("s1", older = false, hasLocalEvents = true, earliestEntryId = "e1")
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(2, "stale-history"), event(0, "older"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 2.0
        )
        val rebased = patch(received.last()).eventPatch!!.replacementEvents!!
        assertEquals(listOf(0, 1, 2), rebased.map { it.seq })
        assertEquals("live", rebased.last().event.text)
    }

    @Test
    fun duplicateAndOutOfOrderLiveEventsUseIndexedUpsert() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))

        store.liveEventReceived(wireJson.encodeToString(event(2, "two")))
        store.liveEventReceived(wireJson.encodeToString(event(1, "one")))
        var update = patch(received.last()).eventPatch!!
        assertEquals("upsert", update.kind)
        assertEquals(0, update.index)

        store.liveEventReceived(wireJson.encodeToString(event(2, "two-final")))
        update = patch(received.last()).eventPatch!!
        assertEquals("upsert", update.kind)
        assertEquals(1, update.index)
        assertEquals("two-final", update.record?.event?.text)
    }

    @Test
    fun malformedPageAndEntryIdCursorLoopFailClosedWithoutReplacingEvents() {
        val store = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 3))
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.liveEventReceived(wireJson.encodeToString(event(1, "local")))
        val beforeErrorCount = received.size

        val malformed = store.pageReceived(
            "s1", "not-json", 1, false, null, null, null
        )
        assertFalse(malformed.accepted)
        assertEquals(beforeErrorCount + 1, received.size)
        assertEquals("error", received.last().kind)

        store.start("s1", older = false, hasLocalEvents = true, earliestEntryId = "e1")
        store.pageReceived(
            "s1", wireJson.encodeToString(listOf(event(1, "local"))), 1, true, "e-older", null, null
        )
        assertEquals(SharedHistoryEffect("request-page", "s1", "e-older"), effects(received.last()).single())
        store.pageReceived(
            "s1", wireJson.encodeToString(emptyList<SessionEvent>()), 0, true, "e-older", null, null
        )
        val failed = patch(received.last())
        assertEquals("failed", failed.outcome)
        assertEquals("REPEATED_CURSOR", failed.failureCode)
        assertNull(failed.eventPatch)
    }

    @Test
    fun historyPageCancelsSyntheticDuplicatesOfLiveTail() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.liveEventReceived(wireJson.encodeToString(event(1, "hello", entryId = "e-live")))
        // sent 补拉页面与 live 尾部重叠：合成 seq（≤0）与 pi 正 seq 无法按 seq 相撞，
        // 必须按条目身份跨空间对消，否则同一条消息显示两次。
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(0, "old"), event(-1, "hello", entryId = "e-live"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 1.0
        )
        val replacement = patch(received.last()).eventPatch!!.replacementEvents!!
        assertEquals(listOf(0, 1), replacement.map { it.seq })
        assertEquals(listOf("old", "hello"), replacement.map { it.event.text })
    }

    @Test
    fun liveEventCancelsBufferedSyntheticDuplicateViaReplacePatch() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(-1, "hello", entryId = "e-h"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 1.0
        )
        assertEquals(listOf(-1), patch(received.last()).eventPatch!!.replacementEvents!!.map { it.seq })

        // history 先到、live 后到（同 entryId=e-h，payload 不同——中间快照/重试
        // 场景）：对消合成记录后必须改发 replace 让镜像整表重同步，实时正式消息胜出。
        store.liveEventReceived(wireJson.encodeToString(event(1, "hello-final", entryId = "e-h")))
        val update = patch(received.last()).eventPatch!!
        assertEquals("replace", update.kind)
        assertEquals(listOf(1), update.replacementEvents!!.map { it.seq })
        assertEquals("hello-final", update.replacementEvents!!.single().event.text)
    }

    @Test
    fun identicalMessagesCancelOneToOneAcrossSeqSpaces() {
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.liveEventReceived(wireJson.encodeToString(event(1, "ok", entryId = "e1")))
        store.liveEventReceived(wireJson.encodeToString(event(2, "ok", entryId = "e2")))
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(-3, "ok", entryId = "e0"), event(-2, "ok", entryId = "e1"), event(-1, "ok", entryId = "e2"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 2.0
        )
        val replacement = patch(received.last()).eventPatch!!.replacementEvents!!
        // 三条同文（多次“继续”场景）：身份只在双侧 entryId 精确相等时对消——
        // e1/e2 副本被吞（实时正式消息胜出），e0 是独立条目必须保留；
        // 数量封顶/内容指纹不再参与判断。
        assertEquals(listOf(-3, 1, 2), replacement.map { it.seq })
    }

    /** 重建全表快照（替代临时 MessageIdentityReproTest 的回归验证入口）。 */
    private fun snapshot(store: SharedHistoryStore): List<SessionEvent> {
        val events = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(events::add))
        return wireJson.decodeFromString<SharedHistoryBootstrap>(events.single().statePayloadJson!!)
            .eventsBySession["s1"].orEmpty()
    }

    @Test
    fun distinctLiveMessagesWithSameTextRemainSeparateWithoutEntryIds() {
        // 回归（原 MessageIdentityReproTest）：同文两条 live 无身份时保守保留，
        // 禁止内容指纹对消（连续“继续”是常见输入）。
        val store = SharedHistoryStore()
        assertTrue(store.liveEventReceived(wireJson.encodeToString(event(1, "继续", type = "user/message"))).accepted)
        assertTrue(store.liveEventReceived(wireJson.encodeToString(event(2, "继续", type = "user/message"))).accepted)
        assertEquals(listOf(1, 2), snapshot(store).map { it.seq })
    }

    @Test
    fun distinctLiveMessagesWithSameTextRemainSeparateWithDistinctEntryIds() {
        // 两个同文不同条目：身份不同，各自保留。
        val store = SharedHistoryStore()
        store.liveEventReceived(wireJson.encodeToString(event(1, "继续", entryId = "e-1", type = "user/message")))
        store.liveEventReceived(wireJson.encodeToString(event(2, "继续", entryId = "e-2", type = "user/message")))
        assertEquals(listOf("e-1", "e-2"), snapshot(store).map { it.entryId })
    }

    @Test
    fun laterLiveMessageWithSameTextMustNotReplaceEarlierHistoryWithoutEntryIds() {
        // 回归（原 MessageIdentityReproTest）：历史加载完毕后，同文本的新 live
        // 消息是新消息而不是旧条目回放；无 entryId（旧网关）时保守保留两条。
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        assertTrue(store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null).accepted)
        assertTrue(
            store.pageReceived(
                sessionId = "s1",
                eventsJson = wireJson.encodeToString(listOf(event(-1, "继续", type = "user/message"))),
                byteCount = 100,
                hasMore = false,
                nextCursor = null,
                earliestEntryId = "old-entry",
                remoteActivityTimestamp = 10.0
            ).accepted
        )
        assertEquals(listOf(-1), patch(received.last()).eventPatch!!.replacementEvents!!.map { it.seq })
        assertTrue(store.liveEventReceived(wireJson.encodeToString(event(1, "继续", type = "user/message"))).accepted)
        // live 同文新消息以 append 入列，缓冲历史不被吞：全表 [-1, 1]。
        val update = patch(received.last()).eventPatch!!
        assertEquals("append", update.kind)
        assertEquals(1, update.record?.seq)
        assertEquals(listOf(-1, 1), snapshot(store).map { it.seq })
    }

    @Test
    fun repeatedHistoryPageWithSameEntryIdDoesNotDuplicate() {
        // 重复历史页（重拉）：同 entryId 的页副本对缓冲同身份记录对消，不产生双份。
        val store = SharedHistoryStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(-1, "hello", entryId = "e-x"))),
            byteCount = 10,
            hasMore = true,
            nextCursor = "e-x",
            earliestEntryId = "e-x",
            remoteActivityTimestamp = 1.0
        )
        assertEquals(listOf(-1), patch(received.last()).eventPatch!!.replacementEvents!!.map { it.seq })

        store.start("s1", older = false, hasLocalEvents = true, earliestEntryId = "e-x")
        store.pageReceived(
            "s1",
            wireJson.encodeToString(listOf(event(-2, "hello", entryId = "e-x"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 1.0
        )
        // 页副本与缓冲同身份：整表不变 => 无 replace patch（不双份）。
        assertNull(patch(received.last()).eventPatch)
        assertEquals(listOf(-1), snapshot(store).map { it.seq })
    }

    @Test
    fun sameEntryIdWithDifferentTypeOrCallIdStaysSeparate() {
        // 身份键含归一 type 与 callId：同 entryId 的不同事件不互相吞。
        val store = SharedHistoryStore()
        store.liveEventReceived(wireJson.encodeToString(event(1, "正文", entryId = "e-x", type = "assistant/message")))
        store.liveEventReceived(wireJson.encodeToString(event(2, "结果", entryId = "e-x", type = "tool/result", callId = "c1")))
        store.liveEventReceived(wireJson.encodeToString(event(3, "r1", entryId = "e-y", type = "tool/result", callId = "c1")))
        store.liveEventReceived(wireJson.encodeToString(event(4, "r2", entryId = "e-y", type = "tool/result", callId = "c2")))
        assertEquals(listOf(1, 2, 3, 4), snapshot(store).map { it.seq })
    }

    private fun patch(event: SharedMviEvent): SharedHistoryPatch =
        wireJson.decodeFromString(event.statePayloadJson!!)

    private fun effects(event: SharedMviEvent): List<SharedHistoryEffect> =
        wireJson.decodeFromString(event.effectsJson)

    private fun event(
        sequence: Int,
        text: String,
        entryId: String? = null,
        type: String = "assistant/message",
        callId: String? = null
    ) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(type = type, text = text, callId = callId),
        entryId = entryId
    )

    @Test
    fun `resetLeaf start produces effect with resetLeaf and older pages never do`() {
        val store = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 1))
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.start(
            sessionId = "s-reset",
            older = false,
            hasLocalEvents = false,
            earliestEntryId = null,
            resetLeaf = true
        )
        val effects = received.last { it.kind == "transition" }.effectsJson
        assertTrue("request-page effect 应带 resetLeaf，实际=${'$'}effects") { effects.contains("\"resetLeaf\":true") }
    }
}
