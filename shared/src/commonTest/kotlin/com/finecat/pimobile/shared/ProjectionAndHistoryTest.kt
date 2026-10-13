package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.projection.ConversationHistoryRebase
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.ConversationProjector
import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import com.finecat.pimobile.shared.projection.TrajectoryProjection
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.protocol.ToolCall
import com.finecat.pimobile.shared.protocol.ToolDelta
import com.finecat.pimobile.shared.sync.HistoryAction
import com.finecat.pimobile.shared.sync.HistoryBatchKind
import com.finecat.pimobile.shared.sync.HistoryEventMerger
import com.finecat.pimobile.shared.sync.HistoryFailureCode
import com.finecat.pimobile.shared.sync.HistoryReducer
import com.finecat.pimobile.shared.sync.HistoryResult
import com.finecat.pimobile.shared.sync.HistorySessionState
import com.finecat.pimobile.shared.sync.HistoryState
import com.finecat.pimobile.shared.sync.HistorySyncConfiguration
import com.finecat.pimobile.shared.sync.HistorySyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectionAndHistoryTest {
    @Test
    fun conversationProjectionJoinsChunksAndFinalMessageReplacesStream() {
        val projector = ConversationProjector()
        projector.fold(
            listOf(
                event(1, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "Hel")),
                event(2, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "lo"))
            )
        )
        assertEquals("Hello", projector.items.single().text)
        projector.fold(listOf(event(3, GatewayEvent("assistant/message", turn = 1, step = 1, text = "Hello!"))))
        assertEquals(1, projector.items.size)
        assertEquals("Hello!", projector.items.single().text)
        assertEquals(ConversationItemKind.ASSISTANT, projector.items.single().kind)
    }

    @Test
    fun conversationProjectionRendersEmptyAssistantErrorAndDeduplicatesRetryNotice() {
        val projector = ConversationProjector()
        projector.fold(
            listOf(
                event(1, GatewayEvent("user/message", text = "hi")),
                event(2, GatewayEvent("assistant/message", turn = 1, step = 0, isError = true, errorMessage = "401 余额不足")),
                // 扩展或历史回放可能随后给出同一条 notice；不能重复显示。
                event(3, GatewayEvent("notice/upstream", text = "401 余额不足", isError = true)),
                // 下一次重试返回不同根因时，不能被上一条错误的去重状态吞掉。
                event(4, GatewayEvent("assistant/message", turn = 2, step = 0, isError = true, errorMessage = "429 overloaded"))
            )
        )

        assertEquals(2, projector.items.count { it.kind == ConversationItemKind.SYSTEM })
        val errors = projector.items.filter { it.kind == ConversationItemKind.SYSTEM }
        assertEquals(listOf("401 余额不足", "429 overloaded"), errors.map { it.text })
        assertTrue(errors.all { it.isError })
    }

    @Test
    fun conversationProjectionKeepsPartialAssistantTextAlongsideError() {
        val projector = ConversationProjector()
        projector.fold(
            listOf(
                event(1, GatewayEvent("assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "部分")),
                event(2, GatewayEvent("assistant/message", turn = 1, step = 0, text = "部分", isError = true, errorMessage = "上游断开"))
            )
        )

        assertEquals(listOf(ConversationItemKind.ASSISTANT, ConversationItemKind.SYSTEM), projector.items.map { it.kind })
        assertEquals("部分", projector.items[0].text)
        assertEquals("上游断开", projector.items[1].text)
    }

    @Test
    fun conversationProjectionRendersCompactionTraceAndFailureNotice() {
        val projector = ConversationProjector()
        projector.fold(
            listOf(
                event(1, GatewayEvent("user/message", text = "hi")),
                event(2, GatewayEvent("compact/done", text = "压缩前 118k tokens"))
            )
        )
        val done = projector.items.last()
        assertEquals(ConversationItemKind.SYSTEM, done.kind)
        assertEquals("上下文已压缩", done.title)
        assertEquals("压缩前 118k tokens", done.text)
        assertTrue(!done.isError)
        projector.fold(listOf(event(3, GatewayEvent("notice/compact", text = "上下文压缩失败：overloaded", isError = true))))
        val notice = projector.items.last()
        assertEquals(ConversationItemKind.SYSTEM, notice.kind)
        assertEquals("上下文压缩", notice.title)
        assertTrue(notice.isError)
    }

    @Test
    fun historyRebasePrefersLiveDuplicateAndAppendsOnlyNewTail() {
        val history = listOf(event(1, GatewayEvent("user/message", text = "history")))
        val current = listOf(event(1, GatewayEvent("user/message", text = "live")), event(2, GatewayEvent("assistant/message", text = "two")))
        val rebase = ConversationHistoryRebase.build(history, current)
        assertEquals("live", rebase.events.first().event.text)
        val appended = rebase.appendingLiveTail(current + event(3, GatewayEvent("assistant/message", text = "three")))
        assertEquals(listOf(1, 2, 3), appended.events.map { it.seq })
    }

    @Test
    fun trajectoryProjectionBuildsRequestAssistantAndToolNodes() {
        val usage = JsonValue.ObjectValue(mapOf("outputTokens" to JsonValue.NumberValue(12.0)))
        val source = listOf(
            event(1, GatewayEvent("user/message", text = "执行")),
            event(2, GatewayEvent("assistant/message", turn = 1, step = 1, text = "开始", usage = usage)),
            event(3, GatewayEvent("tool/call", turn = 1, step = 1, callId = "call-1", name = "shell", arguments = JsonValue.ObjectValue(mapOf("cmd" to JsonValue.StringValue("pwd"))))),
            event(4, GatewayEvent("tool/result", turn = 1, step = 1, callId = "call-1", preview = "/tmp"))
        )
        val nodes = TrajectoryProjection.make(source)
        assertTrue(nodes.any { it.kind == TrajectoryNodeKind.REQUEST && it.request?.usage?.output == 12 })
        assertTrue(nodes.any { it.kind == TrajectoryNodeKind.ASSISTANT })
        assertTrue(nodes.any { it.kind == TrajectoryNodeKind.TOOL && it.subtitle.contains("/tmp") })
    }

    @Test
    fun trajectoryRequestNodeCarriesProviderAndModelFromAssistantMessage() {
        // F04：REQUEST 详情标题要显示真实模型名。pi 的 assistant message 自带
        // provider/model 顶层字段，归一透传后 requestNode 必须取到（旧链读
        // raw.message.source 是无产生方的假设形状，恒 null）。
        val source = listOf(
            event(1, GatewayEvent("user/message", text = "执行")),
            event(
                2,
                GatewayEvent(
                    "assistant/message", turn = 1, step = 1, text = "开始",
                    provider = "newapi", model = "glm-4-f04",
                ),
            ),
        )
        val request = TrajectoryProjection.make(source).single { it.kind == TrajectoryNodeKind.REQUEST }
        assertEquals("newapi", request.request?.provider)
        assertEquals("glm-4-f04", request.request?.model)
    }

    @Test
    fun conversationProjectionRemovesAssemblingToolRowWhenAssistantFinalizes() {
        val projector = ConversationProjector()
        projector.fold(
            listOf(
                event(1, GatewayEvent("assistant/chunk", turn = 1, step = 0, chunkType = "tool-call-delta", tool = ToolDelta(id = "tc-1", name = "Bash", argumentsDelta = "{\"cmd\":"))),
                event(2, GatewayEvent("assistant/chunk", turn = 1, step = 0, chunkType = "tool-call-delta", tool = ToolDelta(id = "tc-1", name = "Bash", argumentsDelta = "\"ls\"}")))
            )
        )
        // 组装态行（toolCallId=null 的半截 JSON）。
        assertEquals(1, projector.items.size)
        projector.fold(
            listOf(
                event(3, GatewayEvent("assistant/message", turn = 1, step = 0, text = "done", toolCalls = listOf(ToolCall("tc-1", "Bash"))))
            )
        )
        // finalize 摘组装行：只剩终态 assistant 行。
        assertEquals(1, projector.items.size)
        assertEquals(ConversationItemKind.ASSISTANT, projector.items.single().kind)
        projector.fold(
            listOf(
                event(4, GatewayEvent("tool/call", callId = "tc-1", name = "Bash")),
                event(5, GatewayEvent("tool/result", callId = "tc-1", preview = "ok"))
            )
        )
        // 真身行与结果行精确配对，无半截行残留。
        assertEquals(listOf("tc-1", "tc-1"), projector.items.mapNotNull { it.toolCallId })
    }

    @Test
    fun projectorSnapshotRestoreRoundTripsUncommittedFold() {
        val projector = ConversationProjector()
        projector.fold(listOf(event(1, GatewayEvent("user/message", text = "hi"))))
        val snapshot = projector.snapshot()
        projector.fold(listOf(event(2, GatewayEvent("assistant/chunk", turn = 1, step = 1, chunkType = "text-delta", text = "Hello"))))
        assertEquals(2, projector.items.size)
        projector.restore(snapshot)
        assertEquals(1, projector.items.size)
        assertEquals("hi", projector.items.single().text)
        assertEquals(1, projector.lastSequence)
    }

    @Test
    fun historyReducerFallsBackToEarliestEntryIdWhenNextCursorMissing() {
        // pagesPerBatch=2 的批次内：hasMore=true 但 nextCursor 缺省时用 earliestEntryId 继续，
        // 不整批失败（历史只加载一半）。
        val state = HistoryState(
            sessions = mapOf("s1" to HistorySessionState(hasMore = true, isLoading = true, batchKind = HistoryBatchKind.OLDER))
        )
        val committed = HistoryReducer.reduce(
            state,
            HistoryAction.PageCommitted(
                sessionId = "s1",
                eventCount = 2,
                byteCount = 20,
                hasMore = true,
                nextCursor = null,
                earliestEntryId = "e-old",
                remoteActivityTimestamp = null
            ),
            HistorySyncConfiguration(pagesPerBatch = 2)
        )
        val request = assertIs<HistoryResult.RequestPage>(committed.result)
        assertEquals("e-old", request.beforeEntryId)
    }

    @Test
    fun historyReducerRejectsEntryIdCursorLoopAndMergerKeepsSequenceOrder() {
        // pi 历史游标是字符串 entryId（PROTOCOL.md §4.6）。
        var state = HistoryState(sessions = mapOf("s1" to HistorySessionState(hasMore = true, nextCursor = "e100")))
        state = HistoryReducer.reduce(
            state,
            HistoryAction.Start("s1", older = true, hasLocalEvents = true, earliestEntryId = "e100")
        ).state
        val reduction = HistoryReducer.reduce(
            state,
            HistoryAction.PageCommitted(
                sessionId = "s1",
                eventCount = 10,
                byteCount = 100,
                hasMore = true,
                nextCursor = "e100",
                earliestEntryId = "e100",
                remoteActivityTimestamp = null
            )
        )
        val failure = assertIs<HistoryResult.Failed>(reduction.result)
        assertEquals(HistoryFailureCode.REPEATED_CURSOR, failure.code)
        assertEquals("e100", failure.cursor)
        assertFalse(reduction.state.sessions.getValue("s1").isLoading)

        var events = listOf(event(1, GatewayEvent("user/message", text = "one")), event(3, GatewayEvent("assistant/message", text = "old")))
        events = HistoryEventMerger.merge(event(2, GatewayEvent("assistant/message", text = "two")), events).events
        events = HistoryEventMerger.merge(event(3, GatewayEvent("assistant/message", text = "new")), events).events
        assertEquals(listOf(1, 2, 3), events.map { it.seq })
        assertEquals("new", events.last().event.text)
    }

    @Test
    fun historyReducerRequestsOldestEntryIdWhenLocalCursorIsAbsent() {
        // 本地没有任何事件时，翻旧页以服务端返回的 oldestEntryId 为起点。
        val state = HistoryState(
            sessions = mapOf("s1" to HistorySessionState(hasMore = true))
        )
        val started = HistoryReducer.reduce(
            state,
            HistoryAction.Start("s1", older = true, hasLocalEvents = true, earliestEntryId = "e-oldest")
        )
        val request = assertIs<HistoryResult.RequestPage>(started.result)
        assertEquals("e-oldest", request.beforeEntryId)

        val committed = HistoryReducer.reduce(
            started.state,
            HistoryAction.PageCommitted(
                sessionId = "s1",
                eventCount = 2,
                byteCount = 20,
                hasMore = false,
                nextCursor = null,
                earliestEntryId = null,
                remoteActivityTimestamp = null
            )
        )
        val completed = assertIs<HistoryResult.Completed>(committed.result)
        assertEquals(2, completed.eventCount)
        assertFalse(committed.state.sessions.getValue("s1").hasMore)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun historySyncEngineInvalidatesProcessingTimeoutAndFiresCurrentTimeout() = runTest {
        val engine = HistorySyncEngine(scope = this)
        var timeouts = 0
        engine.beginRequest("s1", 100) { timeouts += 1 }
        val processing = engine.beginProcessing("s1")
        advanceTimeBy(150)
        runCurrent()
        assertEquals(0, timeouts)
        assertTrue(engine.isCurrent(processing, "s1"))

        engine.beginRequest("s1", 100) { timeouts += 1 }
        advanceTimeBy(101)
        runCurrent()
        assertEquals(1, timeouts)
        assertFalse(engine.isActive("s1"))
        engine.close()
    }

    private fun event(sequence: Int, gatewayEvent: GatewayEvent) =
        SessionEvent("s1", sequence, sequence.toDouble(), gatewayEvent)
}
