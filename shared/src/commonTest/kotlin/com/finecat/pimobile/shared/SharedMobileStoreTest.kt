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
    fun dequeuedSnapshotClearsQueueButPreservesCurrentRun() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"state","sessionId":"android-demo","isStreaming":true,"queuedMessages":{"steering":["s"],"followUp":["f"]}}""")
        val snapshot = store.acceptFrame("""{"kind":"dequeued","sessionId":"android-demo","queueRequestId":"q-1","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["s","f"]}""")
        assertTrue(snapshot.sessionStates.getValue("android-demo").isStreaming)
        assertNull(snapshot.sessionStates.getValue("android-demo").queuedMessages)
    }

    @Test
    fun abortedSnapshotClearsWorkingQueueWithoutInventingAgentEvents_andLegacyAckKeepsQueue() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"state","sessionId":"android-demo","isStreaming":true,"queuedMessages":{"steering":[],"followUp":["pending"]}}""")
        val legacy = store.acceptFrame("""{"kind":"aborted","sessionId":"android-demo"}""")
        assertEquals(listOf("pending"), legacy.sessionStates.getValue("android-demo").queuedMessages?.followUp)
        val stopped = store.acceptFrame("""{"kind":"aborted","sessionId":"android-demo","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["pending"]}""")
        val state = stopped.sessionStates.getValue("android-demo")
        assertEquals(false, state.isStreaming)
        assertEquals(false, state.isCompacting)
        assertNull(state.queuedMessages)
        assertEquals(2, stopped.conversation.size)
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
    fun manualCompactionUpdatesDrawerWithoutAnAgentSettledEvent() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        fun feed(seq: Int, type: String) = store.acceptLiveEvents(listOf(
            SessionEvent("android-demo", seq, 100.0 + seq, GatewayEvent(type = type))
        ))
        feed(1, "compact/start")
        assertTrue(store.snapshot().sessions.single().isRunning)
        feed(2, "compact/end")
        assertTrue(!store.snapshot().sessions.single().isRunning)
        assertTrue(!store.snapshot().sessions.single().hasUnread)
        assertTrue(store.snapshot().conversation.none { it.title == "compact/start" || it.title == "compact/end" })
    }

    @Test
    fun finishedSnapshotClearsStaleRemoteRunningAndMarksOnlyUnviewedSessionUnread() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"sessions","items":[{"id":"a","running":true},{"id":"b","running":false}]}""")
        store.selectSession("b")
        store.acceptFrame("""{"kind":"compact-finished","sessionId":"a","isStreaming":false,"isCompacting":false}""")
        val sessions = store.snapshot().sessions.associateBy { it.id }
        assertTrue(!sessions.getValue("a").isRunning)
        assertTrue(sessions.getValue("a").hasUnread)
        assertTrue(!sessions.getValue("b").hasUnread)
        assertTrue(!store.selectSession("a").sessions.first { it.id == "a" }.hasUnread)
    }

    @Test
    fun lifecycleKeepsRunningUntilBothChatAndCompactionHaveEnded() {
        for (firstEnd in listOf("turn/end", "compact/end")) {
            val store = SharedMobileStore()
            store.loadManualTestFixture()
            listOf("turn/start", "compact/start", firstEnd).forEachIndexed { i, type ->
                store.acceptLiveEvents(listOf(SessionEvent("android-demo", i + 1, 100.0 + i, GatewayEvent(type))))
            }
            assertTrue(store.snapshot().sessions.single().isRunning)
            val lastEnd = if (firstEnd == "turn/end") "compact/end" else "turn/end"
            store.acceptLiveEvents(listOf(SessionEvent("android-demo", 4, 104.0, GatewayEvent(lastEnd))))
            assertTrue(!store.snapshot().sessions.single().isRunning)
        }
    }

    @Test
    fun stateAndHistorySnapshotsReconcileDrawerAfterMissedEndEvent() {
        for (kind in listOf("state", "history", "model-set", "compact-finished")) {
            val store = SharedMobileStore()
            store.loadManualTestFixture()
            store.acceptFrame("""{"kind":"compact-started","sessionId":"android-demo","isStreaming":false,"isCompacting":true}""")
            assertTrue(store.snapshot().sessions.single().isRunning)
            store.acceptFrame("""{"kind":"$kind","sessionId":"android-demo","isStreaming":false,"isCompacting":false}""")
            assertTrue(!store.snapshot().sessions.single().isRunning)
            assertTrue(!store.snapshot().sessions.single().hasUnread)
        }
    }

    @Test
    fun backgroundCompactionBecomesUnreadOnlyWhenItFinishes() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"sessions","items":[{"id":"a","running":false},{"id":"b","running":false}]}""")
        store.selectSession("b")
        store.acceptLiveEvents(listOf(SessionEvent("a", 1, 100.0, GatewayEvent("compact/start"))))
        val started = store.snapshot().sessions.first { it.id == "a" }
        assertTrue(started.isRunning)
        assertTrue(!started.hasUnread)
        store.acceptLiveEvents(listOf(SessionEvent("a", 2, 101.0, GatewayEvent("compact/end"))))
        val finished = store.snapshot().sessions.first { it.id == "a" }
        assertTrue(!finished.isRunning)
        assertTrue(finished.hasUnread)
    }

    @Test
    fun finishedSnapshotDoesNotStopAnActuallyRunningChat() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"compact-finished","sessionId":"android-demo","isStreaming":true,"isCompacting":false}""")
        assertTrue(store.snapshot().sessions.single().isRunning)
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

    @Test
    fun extensionStatusPushReplacesWholeSessionSnapshotAndNeverTouchesSessionState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"state","sessionId":"android-demo","isStreaming":true,"isCompacting":false}""")

        val first = store.acceptFrame(
            """{"kind":"extension-status","sessionId":"android-demo","statuses":[{"key":"subagents","text":"1 agent running"},{"key":"review","text":"审查中"}]}"""
        )
        assertEquals(
            listOf("subagents", "review"),
            first.extensionStatuses.getValue("android-demo").map { it.key }
        )
        assertEquals(
            listOf("1 agent running", "审查中"),
            first.extensionStatuses.getValue("android-demo").map { it.text }
        )
        // 状态只是显示提示：不能用来推断忙闲。
        assertTrue(first.sessionStates.getValue("android-demo").isStreaming)

        // 完整快照：新帧没带的 key 即消失；重复 key 后者覆盖；空白文本不进入显示。
        val second = store.acceptFrame(
            """{"kind":"extension-status","sessionId":"android-demo","statuses":[{"key":"subagents","text":"2 agents running"},{"key":"subagents","text":"3 agents running"},{"key":"blank","text":"   "}]}"""
        )
        val entries = second.extensionStatuses.getValue("android-demo")
        assertEquals(listOf("subagents"), entries.map { it.key })
        assertEquals("3 agents running", entries.single().text)

        // 空数组清除该会话条目。
        val cleared = store.acceptFrame("""{"kind":"extension-status","sessionId":"android-demo","statuses":[]}""")
        assertTrue(cleared.extensionStatuses.isEmpty())
    }

    @Test
    fun extensionStatusWithoutSessionIdIsIgnored() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"extension-status","statuses":[{"key":"subagents","text":"1 agent running"}]}"""
        )
        assertTrue(snapshot.extensionStatuses.isEmpty())
        assertNull(snapshot.lastError)
    }

    @Test
    fun subscribedAndSessionRestartedRepliesInitializeExtensionStatuses() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()

        // 切会话/重连后先由 subscribed 应答带上当前快照，不必等下一次 setStatus。
        val subscribed = store.acceptFrame(
            """{"kind":"subscribed","sessionId":"android-demo","statuses":[{"key":"subagents","text":"1 agent running"}]}"""
        )
        assertEquals("1 agent running", subscribed.extensionStatuses.getValue("android-demo").single().text)

        // session-restarted（重载配置）重建会话后同一字段同样覆盖（而不是累加）。
        val restarted = store.acceptFrame(
            """{"kind":"session-restarted","sessionId":"android-demo","isStreaming":false,"isCompacting":false,"statuses":[{"key":"subagents","text":"2 agents running"}]}"""
        )
        assertEquals(listOf("2 agents running"), restarted.extensionStatuses.getValue("android-demo").map { it.text })
        assertTrue(!restarted.sessionStates.getValue("android-demo").isStreaming)

        // reset 清空状态行（换网关/配对重置不残留上一个连接的状态）。
        assertTrue(store.reset().extensionStatuses.isEmpty())
    }
}
