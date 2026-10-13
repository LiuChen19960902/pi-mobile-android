package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.facade.SharedMobileFacade
import com.finecat.pimobile.shared.facade.SharedMobileStore
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.SessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMobileStoreTest {
    @Test
    fun facadeRejectsFrameWithoutKindInsteadOfBeautifying() {
        // 缺 kind 即违背协议骨架：decodeFrameKind 返回 null（上游按 decode-error 处理），
        // 不再美化成 event。
        assertNull(
            SharedMobileFacade().decodeFrameKind(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        )
    }

    @Test
    fun manualFixtureUsesSharedReducersAndProjection() {
        val store = SharedMobileStore()
        val snapshot = store.loadManualTestFixture()
        assertEquals("android-demo", snapshot.selectedSessionId)
        assertEquals(1, snapshot.sessions.size)
        assertEquals("请验证 Android 是否使用共享 Reducer", snapshot.sessions.single().title)
        assertEquals(2, snapshot.conversation.size)
        assertEquals("共享协议解码、Reducer 与投影已接入。", snapshot.conversation.last().text)
        assertEquals("sessions", snapshot.lastFrameKind)
        assertNull(snapshot.lastError)
    }

    @Test
    fun piModelsFramePopulatesModelCatalog() {
        val store = SharedMobileStore()
        val snapshot = store.acceptFrame(GatewayProtocolFixtures.PI_MODELS)
        val group = snapshot.modelCatalog?.groups?.single()
        assertEquals("relayB", group?.provider)
        val model = group?.models?.single()
        assertEquals("example-model", model?.id)
        assertEquals(1_000_000L, model?.contextWindow)
        assertEquals(true, model?.reasoning)
        assertEquals(listOf("text"), model?.input)
        assertEquals(listOf("off", "minimal", "low", "medium", "high"), model?.thinkingLevels)
    }

    @Test
    fun piStateAndModelSetFramesPopulatePerSessionStates() {
        val store = SharedMobileStore()

        val state = store.acceptFrame(GatewayProtocolFixtures.PI_STATE)
        val stateEntry = state.sessionStates.getValue("s1")
        assertEquals("relayB", stateEntry.model?.provider)
        assertEquals("example-model", stateEntry.model?.modelId)
        assertEquals("high", stateEntry.thinkingLevel)
        assertEquals(false, stateEntry.isStreaming)
        assertEquals(12.5, stateEntry.contextUsage?.percent)
        assertEquals(1_000_000L, stateEntry.contextUsage?.contextWindow)

        val modelSet = store.acceptFrame(GatewayProtocolFixtures.PI_MODEL_SET)
        val modelSetEntry = modelSet.sessionStates.getValue("s1")
        assertEquals("example-model", modelSetEntry.model?.modelId)
        assertNull(modelSetEntry.contextUsage?.percent)

        // 无 sessionId 的 model-set 无法归属到会话，忽略。
        val orphan = store.acceptFrame(
            """{"kind":"model-set","isStreaming":false,"model":{"provider":"p","modelId":"m"}}"""
        )
        assertEquals(setOf("s1"), orphan.sessionStates.keys)
    }

    @Test
    fun sessionNamedFrameRefreshesListTitleInOneDataFlow() {
        // 网关 session-named 应答带 name：store 单一数据流刷新会话列表标题，
        // 顶栏点击/列表长按改名后 UI 无需本地补丁。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"session-named","sessionId":"android-demo","name":"重构计划","isStreaming":false,"isCompacting":false}"""
        )
        assertEquals("重构计划", snapshot.sessions.single().title)
        assertTrue(!snapshot.sessionStates.getValue("android-demo").isStreaming)
        // 空 name 不动列表（容错）。
        store.acceptFrame(
            """{"kind":"session-named","sessionId":"android-demo","isStreaming":false,"isCompacting":false}"""
        )
        assertEquals("重构计划", store.snapshot().sessions.single().title)
    }

    @Test
    fun invalidFrameIsReportedWithoutDestroyingCurrentState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame("not-json")
        assertEquals(1, snapshot.sessions.size)
        assertTrue(!snapshot.lastError.isNullOrBlank())
    }

    @Test
    fun branchSwitchedFrameUpdatesSessionStateSnapshot() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"branch-switched","sessionId":"android-demo","entryId":"e9","leafId":"e9","alreadyAt":false,"editorText":null,"queued":[],"isStreaming":false,"isCompacting":false,"model":{"provider":"relayB","modelId":"example-model"},"thinkingLevel":"high","queuedMessages":{"steering":[],"followUp":[]},"contextUsage":{"percent":8.0,"contextWindow":1000000,"tokens":80000}}"""
        )
        val state = snapshot.sessionStates.getValue("android-demo")
        assertTrue(!state.isStreaming)
        assertEquals(8.0, state.contextUsage?.percent)
    }

    @Test
    fun historyFrameAppliesSessionStateSnapshotLikeStateFrame() {
        // 断连期间 turn/end 丢失 → 重连 catch-up 的 history 帧必须纠正
        // isStreaming，否则 Working 指示器陈旧（拉式 state 轮内不会来）。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"history","sessionId":"android-demo","messages":[],"entryIds":[],"hasMore":false,"bytes":0,"isStreaming":false,"isCompacting":false,"model":{"provider":"relayB","modelId":"example-model"},"thinkingLevel":"high","queuedMessages":{"steering":[],"followUp":[]},"contextUsage":{"percent":8.0,"contextWindow":1000000,"tokens":80000},"latestEntryId":null}"""
        )
        val state = snapshot.sessionStates.getValue("android-demo")
        assertTrue(!state.isStreaming)
        assertEquals(8.0, state.contextUsage?.percent)
    }

    @Test
    fun queueUpdateLiveEventsRefreshQueuedMessagesSnapshot() {
        // pi 原生 queue_update 归一：steer 消费后吸附条立即消失（对齐 TUI），
        // 不再依赖 turn/end 后的 state 请求-应答。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(steering: List<String>, followUp: List<String>) = store.acceptLiveEvents(
            listOf(
                SessionEvent(sessionId, (0..100).random(), 10.0, GatewayEvent(type = "queue/update", steering = steering, followUp = followUp))
            )
        )
        feed(listOf("你可以看看"), emptyList())
        assertEquals(listOf("你可以看看"), store.snapshot().sessionStates.getValue(sessionId).queuedMessages?.steering)
        feed(emptyList(), emptyList())
        assertNull(store.snapshot().sessionStates.getValue(sessionId).queuedMessages)
    }

    @Test
    fun compactionStatusEventsUpdateSessionCompactingState() {
        // 实时事件流（归一后）驱动 isCompacting：compact/start 置真，
        // compact/end 收尾；notice/compact（失败可见行）既收尾又进对话流。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(type: String, extra: String = "") = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    sessionId, (0..100).random(), 10.0,
                    GatewayEvent(type = type, text = if (type == "notice/compact") "上下文压缩失败：x" else null, isError = type == "notice/compact")
                )
            )
        )
        feed("compact/start")
        assertTrue(store.snapshot().sessionStates.getValue(sessionId).isCompacting)
        feed("compact/end")
        assertTrue(!store.snapshot().sessionStates.getValue(sessionId).isCompacting)
        feed("compact/start")
        val failure = feed("notice/compact")
        assertTrue(!store.snapshot().sessionStates.getValue(sessionId).isCompacting)
        // notice/compact 进对话流；compact/start/end 不进。
        assertTrue(failure.conversation.any { it.title == "上下文压缩" && it.isError })
    }

    @Test
    fun turnEventsFlipSessionStreamingState() {
        // 运行中发送键变停止键后，isStreaming 卡死会永久阻塞输入；轮末 state
        // 应答丢失时，事件流（turn/end = agent_settled）本身就是纠正信号。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(type: String) = store.acceptLiveEvents(
            listOf(
                SessionEvent(sessionId, (0..100).random(), 10.0, GatewayEvent(type = type))
            )
        )
        fun isStreaming() = store.snapshot().sessionStates[sessionId]?.isStreaming == true
        assertTrue(!isStreaming())
        feed("turn/start")
        assertTrue(isStreaming())
        feed("turn/end")
        assertTrue(!isStreaming())
    }

    @Test
    fun upstreamErrorDuringRetryKeepsWorkingUntilSettled() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(seq: Int, type: String, errorMessage: String? = null) = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    sessionId,
                    seq,
                    10.0 + seq,
                    GatewayEvent(type = type, isError = errorMessage != null, errorMessage = errorMessage)
                )
            )
        )

        feed(10, "turn/start")
        feed(11, "assistant/message", "上游 502")
        // agent_end(willRetry=true) 不会归一成 turn/end；退避和重试期间仍 working。
        assertTrue(store.snapshot().sessionStates.getValue(sessionId).isStreaming)
        assertTrue(store.snapshot().conversation.any { it.isError && it.text == "上游 502" })

        // 只有 agent_settled → turn/end 才结束 working。
        feed(12, "turn/end")
        assertTrue(!store.snapshot().sessionStates.getValue(sessionId).isStreaming)
    }

    @Test
    fun piSessionsFramePopulatesSessionListWithIsoTimestamps() {
        val store = SharedMobileStore()
        val snapshot = store.acceptFrame(GatewayProtocolFixtures.PI_SESSIONS)
        assertEquals(1, snapshot.sessions.size)
        assertEquals("s1", snapshot.sessions.single().id)
        assertEquals("第一句话", snapshot.sessions.single().title)
        assertEquals("/tmp/pi-demo", snapshot.sessions.single().cwd)
        assertEquals(1_788_248_176.694, snapshot.sessions.single().lastActivityEpochSeconds)
        assertTrue(!snapshot.sessions.single().isRunning)
    }

    @Test
    fun liveEventsCreateSessionsWithReliableTimestamps() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        val snapshot = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    "stream", 1, 1_800_000_001_000.0,
                    GatewayEvent(type = "assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "delta")
                )
            )
        )
        assertEquals(
            1_800_000_001.0,
            snapshot.sessions.first { it.id == "stream" }.lastActivityEpochSeconds
        )

        store.clearSessionEvents("stream")
    }

    @Test
    fun sentResponseSelectsTheServerAssignedSessionForImmediateLiveProjection() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        var snapshot = store.acceptFrame(
            """{"kind":"sent","sessionId":"session-new","mode":"followUp"}"""
        )
        assertEquals("session-new", snapshot.selectedSessionId)
        assertEquals("session-new", snapshot.sessions.single().id)

        snapshot = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    "session-new", 1, 1_800_000_001.0,
                    GatewayEvent(type = "user/message", text = "第一句话", source = "user")
                )
            )
        )
        assertEquals(listOf("第一句话"), snapshot.conversation.map { it.text })
    }

    @Test
    fun turnBoundariesKeepRunningStateInSyncWithPiEvents() {
        val store = SharedMobileStore()
        store.acceptFrame(GatewayProtocolFixtures.PI_SESSIONS)
        store.selectSession("s1")

        var snapshot = store.acceptLiveEvents(
            listOf(
                SessionEvent("s1", 1, 1_788_248_180.0, GatewayEvent(type = "turn/start")),
                SessionEvent("s1", 2, 1_788_248_181.0, GatewayEvent(type = "assistant/message", text = "进行中"))
            )
        )
        assertTrue(snapshot.sessions.single().isRunning)
        assertTrue(snapshot.sessions.single().hasUnread.not())

        snapshot = store.acceptLiveEvents(
            listOf(SessionEvent("s1", 3, 1_788_248_182.0, GatewayEvent(type = "turn/end")))
        )
        assertTrue(!snapshot.sessions.single().isRunning)

        // 会话事件清理后不再投影。
        store.clearSessionEvents("s1")
        assertEquals(0, store.snapshot().conversation.size)
    }
}
