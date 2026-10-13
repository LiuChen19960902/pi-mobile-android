package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.facade.SharedHistoryBootstrap
import com.finecat.pimobile.shared.facade.SharedHistoryStore
import com.finecat.pimobile.shared.facade.SharedMviEvent
import com.finecat.pimobile.shared.facade.SharedMviEventObserver
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.wireJson
import com.finecat.pimobile.shared.sync.HistorySyncConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LATEST 批次第一页 = 该会话"最新窗口"的整窗替换语义。
 *
 * 为什么要有这条规则：turn/end 自动重拉走 preserveLive（不先清空缓冲），而历史合成
 * seq 每轮继续向负方向递减——若按普通合并，重拉找回的条目会被排到既有内容之前（顺序
 * 错乱）。整窗替换让新窗口按自身单调 seq 落位，同时保住实时（正 seq）尾部。
 */
class LatestHistoryWindowReplacementTest {
    private val store = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 1))

    @Test
    fun firstLatestPageReplacesPreviousWindowAndKeepsLiveTail() {
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(
                listOf(record(-2, "一问", "e1"), record(-1, "二答", "e2"))
            ),
            byteCount = 20,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = "e1",
            remoteActivityTimestamp = 2.0
        )
        // 强退期间的本轮消息以实时事件到达（正 seq）：中间那条 “三问” 本地没见过。
        store.liveEventReceived(wireJson.encodeToString(record(5, "四问", "e4")))
        assertEquals(listOf(-2, -1, 5), currentEvents().map { it.seq })

        // turn/end 重拉：这次文件里 e1..e4 齐全（e3 刚落盘）。
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(
                listOf(
                    record(-4, "一问", "e1"),
                    record(-3, "二答", "e2"),
                    record(-2, "三问", "e3"),
                    record(-1, "四问", "e4")
                )
            ),
            byteCount = 40,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = "e1",
            remoteActivityTimestamp = 5.0
        )

        val merged = currentEvents()
        // 旧窗口的历史条目整窗作废，新窗口按自身单调 seq 整窗落位：补回的 “三问”
        // 落在二答之后（不整窗替换时它的合成 seq 比旧窗口更负，会跳到列表最前）；
        // 实时尾部 (e4) 保住，且与页内同 entryId 副本只留一条。
        assertEquals(listOf("一问", "二答", "三问", "四问"), merged.map { it.event.text })
        assertEquals(listOf(-4, -3, -2, 5), merged.map { it.seq })
        assertEquals(1, merged.count { it.entryId == "e4" })
    }

    @Test
    fun overlappingLatestRefreshKeepsLoadedOlderPrefixAndItsCursor() {
        store.start("s1", false, false, null)
        store.pageReceived("s1", wireJson.encodeToString(listOf(record(-3,"old","e1"), record(-2,"middle","e2"), record(-1,"last","e3"))),
            30, true, "turn-start", "e1", 1.0)
        store.start("s1", false, true, "e1")
        store.pageReceived("s1", wireJson.encodeToString(listOf(record(-5,"middle","e2"), record(-4,"last","e3"), record(-3,"new","e4"))),
            30, true, "e2", "e2", 2.0)
        assertEquals(listOf("e1","e2","e3","e4"), currentEvents().map { it.entryId })
        assertEquals(listOf(-4,-3,-2,-1), currentEvents().map { it.seq })
        val observed = mutableListOf<SharedMviEvent>()
        val subscription = store.subscribe(SharedMviEventObserver(observed::add))
        val state = wireJson.decodeFromString<SharedHistoryBootstrap>(requireNotNull(observed.first().statePayloadJson))
        assertEquals("turn-start", state.state.sessions["s1"]!!.nextCursor)
        subscription.cancel()
    }

    @Test
    fun emptyLatestPageKeepsTheWindowThatIsOnScreen() {
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(record(-1, "一答", "e1"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = "e1",
            remoteActivityTimestamp = 1.0
        )

        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(emptyList<SessionEvent>()),
            byteCount = 0,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = null,
            remoteActivityTimestamp = 2.0
        )

        // 页内无可渲染记录（整页 display:false / 尚无消息）时不替换：
        // 否则已显示的历史会凭空消失，只剩"暂无消息"。
        assertEquals(listOf("一答"), currentEvents().map { it.event.text })
    }

    @Test
    fun secondPageOfOneLatestBatchMergesInsteadOfReplacing() {
        val twoPageStore = SharedHistoryStore(HistorySyncConfiguration(pagesPerBatch = 2))
        twoPageStore.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        twoPageStore.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(record(-1, "最新", "e2"))),
            byteCount = 10,
            hasMore = true,
            nextCursor = "e2",
            earliestEntryId = "e2",
            remoteActivityTimestamp = 2.0
        )
        twoPageStore.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(record(-2, "更早", "e1"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = "e1",
            remoteActivityTimestamp = 3.0
        )

        // 同一批次内的续页是"往更旧取"，只有第一页才代表最新窗口。
        assertEquals(
            listOf("更早", "最新"),
            bootstrapOf(twoPageStore).map { it.event.text }
        )
    }

    @Test
    fun olderBatchKeepsAlreadyPagedContent() {
        store.start("s1", older = false, hasLocalEvents = false, earliestEntryId = null)
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(record(-1, "最新", "e2"))),
            byteCount = 10,
            hasMore = true,
            nextCursor = "e2",
            earliestEntryId = "e2",
            remoteActivityTimestamp = 2.0
        )
        store.start("s1", older = true, hasLocalEvents = true, earliestEntryId = "e2")
        store.pageReceived(
            sessionId = "s1",
            eventsJson = wireJson.encodeToString(listOf(record(-2, "更早", "e1"))),
            byteCount = 10,
            hasMore = false,
            nextCursor = null,
            earliestEntryId = "e1",
            remoteActivityTimestamp = 3.0
        )

        // 用户翻页加载到的旧内容不能被他还没看到的窗口替换抹掉。
        assertEquals(listOf("更早", "最新"), currentEvents().map { it.event.text })
    }

    private fun record(sequence: Int, text: String, entryId: String) = SessionEvent(
        sessionId = "s1",
        seq = sequence,
        time = sequence.toDouble(),
        event = GatewayEvent(type = "assistant/message", text = text),
        entryId = entryId
    )

    private fun currentEvents(): List<SessionEvent> = bootstrapOf(store)

    /** subscribe 即下发当前快照（MVI 设初值），拿它当作断言用的当前事件表。 */
    private fun bootstrapOf(target: SharedHistoryStore): List<SessionEvent> {
        val snapshotEvents = mutableListOf<SharedMviEvent>()
        target.subscribe(SharedMviEventObserver(snapshotEvents::add))
        return wireJson.decodeFromString<SharedHistoryBootstrap>(
            requireNotNull(snapshotEvents.first().statePayloadJson)
        ).eventsBySession.getValue("s1")
    }
}
