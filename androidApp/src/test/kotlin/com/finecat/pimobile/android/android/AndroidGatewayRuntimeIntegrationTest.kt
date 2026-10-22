package com.finecat.pimobile.android

import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayRuntime
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayConnectionSpec
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayNetworkState
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import com.finecat.pimobile.shared.platform.GatewayTransport
import com.finecat.pimobile.shared.platform.GatewayTransportFrame
import com.finecat.pimobile.shared.platform.GatewayTransportEvent
import com.finecat.pimobile.shared.platform.GatewayTransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGatewayRuntimeIntegrationTest {
    @Test
    fun bridgeQueueSurvivesStateAndCanBeRecalledWithoutStoppingCurrentReply() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), ImmediateClock, backgroundScope)
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                if (event is GatewayRuntimeEvent.Frame) projection.acceptFrame(
                    event.rawJson, event.frame, event.correlatedSessionId, event.normalizedEvents)
            }
        }
        fun receive(raw: String) { transport.receive(raw); runCurrent() }
        try {
            runCurrent(); runtime.connectStoredIfPaired(); runCurrent(); transport.open()
            receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["bridge","host-mode","state","dequeue","history","subscribe"]}""")
            receive("""{"kind":"host-mode","mode":"bridge","switchable":true}""")
            projection.selectSession("s-queue")
            receive("""{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i1","epoch":"e1","bridgeSeq":10,"busy":true,"pending":true,"partial":{"role":"assistant","content":[{"type":"text","text":"BEGIN"}]},"tools":[]}""")
            assertTrue(projection.snapshot().sessionStates.getValue("s-queue").hasQueuedMessages)
            assertTrue(runtime.requestState("s-queue")); runCurrent()
            receive("""{"kind":"state","sessionId":"s-queue","isStreaming":true,"isCompacting":false,"pendingMessages":true,"queuedMessages":{"steering":[],"followUp":[]}}""")
            assertTrue(projection.snapshot().sessionStates.getValue("s-queue").hasQueuedMessages)
            assertTrue(runtime.sendRequest(GatewayRequests.dequeue("s-queue", "q-recall"))); runCurrent()
            receive("""{"kind":"dequeued","sessionId":"s-queue","queueRequestId":"q-recall","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["queued draft"]}""")
            val after = projection.snapshot().sessionStates.getValue("s-queue")
            assertFalse(after.hasQueuedMessages)
            assertTrue(after.isStreaming)
            assertEquals(listOf("BEGIN"), projection.snapshot().conversation.map { it.text })
            receive("""{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i1","epoch":"e1","bridgeSeq":11,"busy":true,"pending":true,"partial":null,"tools":[]}""")
            receive("""{"kind":"event","sessionId":"s-queue","instanceId":"i1","epoch":"e1","bridgeSeq":12,"seq":1,"time":1,"event":{"type":"agent_settled"}}""")
            assertFalse(projection.snapshot().sessionStates.getValue("s-queue").hasQueuedMessages)
            assertFalse(projection.snapshot().sessionStates.getValue("s-queue").isStreaming)
        } finally { projection.close(); runtime.disconnect() }
    }

    @Test
    fun bridgeForegroundCatchUpRetiresCompletedSnapshotWithoutTerminalEvent() = runTest {
        bridgeForegroundHistoryScenario("completed")
    }

    @Test
    fun bridgeForegroundCatchUpRetiresCompletedPrefixDespiteInFlightProgress() = runTest {
        bridgeForegroundHistoryScenario("completed-progress")
    }

    @Test
    fun bridgeForegroundCatchUpKeepsActivePrefix() = runTest {
        bridgeForegroundHistoryScenario("active")
    }

    @Test
    fun idleHistoryCannotRetireANewerBridgeSnapshotOrGeneration() = runTest {
        bridgeForegroundHistoryScenario("newer-snapshot")
        bridgeForegroundHistoryScenario("new-generation")
        bridgeForegroundHistoryScenario("unknown-compacting")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.bridgeForegroundHistoryScenario(mode: String) {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), ImmediateClock, backgroundScope)
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { session, cursor, reset ->
            backgroundScope.launch { runtime.requestHistory(session, cursor, resetLeaf = reset) }
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                if (event is GatewayRuntimeEvent.Frame) projection.acceptFrame(
                    event.rawJson, event.frame, event.correlatedSessionId, event.normalizedEvents)
            }
        }
        fun receive(raw: String) { transport.receive(raw); runCurrent() }
        fun snapshot(seq: Int, epoch: String, text: String) {
            receive("""{"kind":"bridge-snapshot","sessionId":"s-foreground","instanceId":"i1","epoch":"$epoch","bridgeSeq":$seq,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"$text"}]},"tools":[]}""")
        }
        val full = "BEGIN MIDDLE END"
        try {
            runCurrent(); runtime.connectStoredIfPaired(); runCurrent(); transport.open()
            receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["bridge","host-mode","history","subscribe","state"]}""")
            receive("""{"kind":"host-mode","mode":"bridge","switchable":true}""")
            receive("""{"kind":"sessions","mode":"bridge","items":[]}""")
            projection.selectSession("s-foreground"); runCurrent()
            receive("""{"kind":"history","sessionId":"s-foreground","messages":[{"role":"user","content":"question"}],"entryIds":["u1"],"hasMore":false,"isStreaming":true,"isCompacting":false}""")
            snapshot(20, "e1", "BEGIN")
            assertEquals(listOf("question", "BEGIN"), projection.snapshot().conversation.map { it.text })

            // App未收到结束事件，回前台走生产catch-up；history与新snapshot可竞序。
            projection.catchUpSelectedHistoryAfterReconnect(); runCurrent()
            if (mode == "completed-progress") {
                // 同一轮继续输出后，补历史先于终态到达；不能把这次delta误当成新快照。
                receive("""{"kind":"event","sessionId":"s-foreground","instanceId":"i1","epoch":"e1","bridgeSeq":21,"seq":1,"time":10,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" MIDDLE"}}}""")
            }
            if (mode == "newer-snapshot") snapshot(21, "e1", "new active prefix")
            if (mode == "new-generation") snapshot(20, "e2", "new active prefix")
            val active = mode == "active"
            val finalMessage = if (active) "" else """,{"role":"assistant","content":[{"type":"text","text":"$full"}],"stopReason":"stop"}"""
            val finalId = if (active) "" else ",\"a1\""
            val compactingField = if (mode == "unknown-compacting") "" else ",\"isCompacting\":false"
            receive("""{"kind":"history","sessionId":"s-foreground","messages":[{"role":"user","content":"question"}$finalMessage],"entryIds":["u1"$finalId],"latestEntryId":"${if (active) "u1" else "a1"}","hasMore":false,"isStreaming":$active$compactingField}""")
            when (mode) {
                "completed", "completed-progress" -> {
                    assertEquals("idle history must not re-seed cached BEGIN after the full reply", listOf(full),
                        projection.snapshot().conversation.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text })
                    assertFalse(projection.snapshot().conversation.any { it.id.startsWith("stream-") })
                    // 继续补尾部历史也不能让原始chunk重放复活；不只检查当前可见流。
                    projection.loadHistory("s-foreground", older = false, preserveLive = true); runCurrent()
                    receive("""{"kind":"history","sessionId":"s-foreground","messages":[{"role":"user","content":"question"},{"role":"assistant","content":[{"type":"text","text":"$full"}],"stopReason":"stop"}],"entryIds":["u1","a1"],"hasMore":false,"isStreaming":false,"isCompacting":false}""")
                    assertEquals(listOf(full), projection.snapshot().conversation.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text })
                    assertFalse(projection.snapshot().conversation.any { it.id.startsWith("stream-") })
                }
                "unknown-compacting" -> assertEquals(listOf(full, "BEGIN"),
                    projection.snapshot().conversation.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text })
                "active" -> {
                    receive("""{"kind":"event","sessionId":"s-foreground","instanceId":"i1","epoch":"e1","bridgeSeq":21,"seq":1,"time":10,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" MIDDLE"}}}""")
                    assertEquals(listOf("question", "BEGIN MIDDLE"), projection.snapshot().conversation.map { it.text })
                }
                else -> assertEquals("older idle history must not discard newer active output", listOf(full, "new active prefix"),
                    projection.snapshot().conversation.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text })
            }
            assertEquals(null, projection.snapshot().lastError)
        } finally { projection.close(); runtime.disconnect() }
    }

    @Test
    fun bridgeResubscribeCompletionAndHistoryDoNotDuplicateRepliesOrReviveChunks() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), ImmediateClock, backgroundScope)
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                if (event is GatewayRuntimeEvent.Frame) projection.acceptFrame(
                    event.rawJson, event.frame, event.correlatedSessionId, event.normalizedEvents)
            }
        }
        fun receive(raw: String) { transport.receive(raw); runCurrent() }
        val question = "phone-check-2"
        val full = "BEGIN:$question\nMIDDLE:$question\nEND:$question"
        val escaped = full.replace("\n", "\\n")
        var nativeSeq = 10
        var wireSeq = 0
        var epoch = "e1"
        fun native(event: String, entryId: String? = null) {
            val id = entryId?.let { ",\"entryId\":\"$it\"" }.orEmpty()
            receive("""{"kind":"event","sessionId":"s-resubscribe","instanceId":"i1","epoch":"$epoch","bridgeSeq":${++nativeSeq},"seq":${++wireSeq},"time":10.0$id,"event":$event}""")
        }
        fun snapshot(partial: String) {
            receive("""{"kind":"bridge-snapshot","sessionId":"s-resubscribe","instanceId":"i1","epoch":"$epoch","bridgeSeq":$nativeSeq,"busy":true,"partial":$partial,"tools":[]}""")
            wireSeq = 0 // 生产 router 每次重订阅都从1重发普通seq，bridgeSeq仍单调。
        }
        val historyMessages = mutableListOf<String>()
        val historyIds = mutableListOf<String>()
        try {
            runCurrent(); runtime.connectStoredIfPaired(); runCurrent(); transport.open()
            receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["bridge","host-mode","history","subscribe","state"]}""")
            receive("""{"kind":"host-mode","mode":"bridge","switchable":true}""")
            receive("""{"kind":"sessions","mode":"bridge","items":[]}""")
            projection.selectSession("s-resubscribe")
            assertTrue(runtime.requestHistory("s-resubscribe"))
            receive("""{"kind":"history","sessionId":"s-resubscribe","messages":[],"entryIds":[],"hasMore":false}""")
            snapshot("null")
            for (index in 1..3) {
                if (index == 3) {
                    native("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                    native("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"dead-old-generation"}}""")
                    epoch = "e2"; nativeSeq = 0; snapshot("null")
                    assertFalse("新代际不能重放死实例尚未落盘的chunk",
                        projection.snapshot().conversation.any { "dead-old-generation" in it.text })
                }
                native("""{"type":"agent_start"}""")
                native("""{"type":"message_end","message":{"role":"user","content":"$question"}}""", "u$index")
                native("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                native("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"BEGIN:$question\n"}}""")
                native("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"MIDDLE:$question\n"}}""")
                snapshot("""{"role":"assistant","content":[{"type":"text","text":"BEGIN:$question\nMIDDLE:$question\n"}]}""")
                assertEquals("快照替换当前前缀且保留此前合法同文消息", index,
                    projection.snapshot().conversation.count { it.kind == ConversationItemKind.ASSISTANT })
                native("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"END:$question"}}""")
                native("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$escaped"}],"stopReason":"stop"}}""", "a$index")
                native("""{"type":"agent_settled"}""")
                historyMessages += """{"role":"user","content":"$question"}"""
                historyMessages += """{"role":"assistant","content":[{"type":"text","text":"$escaped"}],"stopReason":"stop"}"""
                historyIds += "\"u$index\""; historyIds += "\"a$index\""
                projection.loadHistory("s-resubscribe", older = false, preserveLive = true)
                assertTrue(runtime.requestHistory("s-resubscribe"))
                receive("""{"kind":"history","sessionId":"s-resubscribe","messages":[${historyMessages.joinToString(",")}],"entryIds":[${historyIds.joinToString(",")}],"hasMore":false,"latestEntryId":"a$index"}""")
                val items = projection.snapshot().conversation
                assertEquals("只按条目身份收敛，每次合法同文回复均保留；无旧chunk残留", List(index) { full },
                    items.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text })
                assertEquals(List(index) { question }, items.filter { it.kind == ConversationItemKind.USER }.map { it.text })
                assertFalse(items.any { it.id.startsWith("stream-") })
            }
        } finally { projection.close(); runtime.disconnect() }
    }

    @Test
    fun lateOperationStatusCannotConsumeTheNextReceiptLane() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), ImmediateClock, backgroundScope)
        val frames = mutableListOf<GatewayRuntimeEvent.Frame>()
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                events += event
                if (event is GatewayRuntimeEvent.Frame && event.frame.kind == "operation-status") frames += event
            }
        }
        try {
            runCurrent()
            runtime.connectStoredIfPaired(); runCurrent()
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["bridge","host-mode"]}""")
            runCurrent()
            // hello 后的 host-mode/sessions 预请求先结算，避免它们和收据一起超时。
            transport.receive("""{"kind":"host-mode","mode":"bridge","switchable":true}""")
            transport.receive("""{"kind":"sessions","items":[]}""")
            runCurrent()

            assertTrue(runtime.operationStatus("s", "receipt-a", "i1", "e1"))
            advanceTimeBy(30_001); runCurrent()
            assertTrue(
                "B 未发出：connection=${runtime.state.value.connection} events=$events",
                runtime.operationStatus("s", "receipt-b", "i1", "e1")
            )
            runCurrent()
            // A 已超时；它的迟到帧不能把仍在等待的 B 当成自己的应答。
            transport.receive("""{"kind":"operation-status","sessionId":"s","operationId":"receipt-a","status":"settled"}""")
            runCurrent()
            assertTrue("迟到 A 不得发布成 B 的结果：$frames", frames.isEmpty())

            transport.receive("""{"kind":"operation-status","sessionId":"s","operationId":"receipt-b","status":"submitted"}""")
            runCurrent()
            assertEquals(listOf("receipt-b"), frames.map { it.frame.operationId })
        } finally {
            runtime.disconnect()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun compactAcknowledgementLongStartupAndFinalStateUseProductionHooks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val clock = object : GatewayClock {
            override fun nowEpochMilliseconds(): Long = 0L
            override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
        }
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), clock, backgroundScope)
        val holder = AndroidSharedStateHolder(compactWatchScopeForTest = backgroundScope)
        holder.compactGateForceOpenForTest = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                when (event) {
                    is GatewayRuntimeEvent.Frame -> {
                        holder.wirePayload = event.rawJson
                        holder.submitWirePayload()
                    }
                    is GatewayRuntimeEvent.RequestRejected -> holder.finishManualCompactRequest(
                        event.requestType, event.targetSessionId, "压缩请求被拒绝")
                    is GatewayRuntimeEvent.RequestCancelled -> holder.finishManualCompactRequest(
                        event.requestType, event.targetSessionId, "压缩请求未确认")
                    is GatewayRuntimeEvent.RequestTimedOut -> holder.finishManualCompactRequest(
                        event.requestType, event.targetSessionId, "压缩请求未确认")
                    else -> Unit
                }
            }
        }
        try {
            runCurrent()
            runtime.connectStoredIfPaired(); runCurrent()
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["compact","state"]}""")
            runCurrent()
            holder.selectSession("s-compact")
            holder.messageDraft = "保留草稿"
            holder.startManualCompact()
            assertTrue(runtime.sendRequest(GatewayRequests.compact("s-compact")))
            transport.receive("""{"kind":"compact-started","sessionId":"s-compact","isStreaming":true,"isCompacting":false}""")
            runCurrent()
            advanceTimeBy(9_000); runCurrent()
            assertFalse(holder.canStartManualCompact())
            holder.startManualCompact()
            assertEquals(listOf("s-compact"), holder.compactRequestsForTest)
            assertEquals(1, transport.sentPayloads.count { "\"type\":\"compact\"" in it })
            transport.receive("""{"kind":"event","sessionId":"s-compact","seq":1,"time":1,"event":{"type":"compaction_end","aborted":true,"willRetry":false}}""")
            runCurrent()
            assertTrue(holder.canStartManualCompact())
            assertEquals("保留草稿", holder.messageDraft)
            // 新网关在SDK开始前已报告占用，最终state无Pi事件也能释放显示状态。
            holder.startManualCompact()
            assertTrue(runtime.sendRequest(GatewayRequests.compact("s-compact")))
            // 上一轮完成通知不得认领/释放尚未收到ACK的第二轮请求。
            transport.receive("""{"kind":"compact-finished","sessionId":"s-compact","isStreaming":false,"isCompacting":false}""")
            runCurrent()
            assertFalse(holder.canStartManualCompact())
            transport.receive("""{"kind":"compact-started","sessionId":"s-compact","isStreaming":false,"isCompacting":true}""")
            runCurrent()
            assertFalse(holder.canStartManualCompact())
            transport.receive("""{"kind":"compact-finished","sessionId":"s-compact","isStreaming":false,"isCompacting":false}""")
            runCurrent()
            assertTrue(holder.canStartManualCompact())
            holder.startManualCompact()
            assertTrue(runtime.sendRequest(GatewayRequests.compact("s-compact")))
            holder.selectSession("s-other")
            val oldNotice = holder.transientNotice
            transport.failWebSocket(); runCurrent()
            assertEquals(oldNotice, holder.transientNotice)
        } finally {
            holder.reset()
            holder.close()
            runtime.disconnect()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun directoryWebSocketFailureKeepsListingAndRetryResponseClearsError() = runTest {
        val transport = FakeTransport()
        val clock = object : GatewayClock {
            override fun nowEpochMilliseconds(): Long = 0L
            override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
        }
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), clock, backgroundScope)
        val holder = AndroidSharedStateHolder()
        val cancelled = mutableListOf<GatewayRuntimeEvent.RequestCancelled>()
        // 使用真Runtime与生产取消处理；Application collector和触控另由设备用例覆盖。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                when (event) {
                    is GatewayRuntimeEvent.RequestCancelled -> {
                        cancelled += event
                        holder.handleWorkspaceRequestCancelled(event)
                    }
                    is GatewayRuntimeEvent.Frame -> if (event.frame.kind == "directories") {
                        holder.wirePayload = event.rawJson
                        holder.submitWirePayload()
                    }
                    else -> Unit
                }
            }
        }
        try {
            runCurrent()
            runtime.connectStoredIfPaired(); runCurrent()
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["directories"]}""")
            runCurrent()
            assertTrue(runtime.sendRequest(GatewayRequests.directories("/workspace")))
            transport.receive("""{"kind":"directories","path":"/workspace","home":"/workspace","entries":[{"name":"project","path":"/workspace/project","hidden":false}]}""")
            runCurrent()
            val entries = holder.directoryEntries
            assertEquals("/workspace", holder.directoryPath)
            assertEquals(1, entries.size)
            holder.messageDraft = "保留新稿"

            assertTrue(runtime.sendRequest(GatewayRequests.directories("/workspace/project")))
            transport.failWebSocket()
            runCurrent()
            assertTrue(cancelled.any { it.requestType == "directories" && it.reason == "websocket-failure" })
            assertEquals("连接已断开，请重试", holder.directoryError)
            assertEquals("/workspace", holder.directoryPath)
            assertEquals(entries, holder.directoryEntries)
            assertEquals("保留新稿", holder.messageDraft)

            runtime.connectStoredIfPaired(); runCurrent()
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["directories"]}""")
            runCurrent()
            assertTrue(runtime.sendRequest(GatewayRequests.directories("/workspace/project")))
            transport.receive("""{"kind":"directories","path":"/workspace/project","home":"/workspace","entries":[]}""")
            runCurrent()
            assertEquals(null, holder.directoryError)
            assertEquals("/workspace/project", holder.directoryPath)
            assertTrue(holder.directoryEntries.isEmpty())
            assertEquals("保留新稿", holder.messageDraft)
        } finally {
            holder.close()
            runtime.disconnect()
        }
    }

    @Test
    fun runtimeMessageTerminalsUseProductionHolderHook_unknownRejectBusyAndMalformedAck() = runTest {
        val transport = FakeTransport()
        val clock = object : GatewayClock {
            override fun nowEpochMilliseconds(): Long = 0L
            override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
        }
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), clock, backgroundScope)
        val holder = AndroidSharedStateHolder().also { it.elapsedRealtimeMs = { 0L } }
        val errors = mutableListOf<GatewayRuntimeEvent.RequestRejected>()
        // 真Runtime事件接生产终态方法；不构造Application，不能据此称整个Android collector已实跑。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                when (event) {
                    is GatewayRuntimeEvent.RequestRejected -> {
                        errors += event
                        holder.handleOutgoingMessageRejection(event)
                    }
                    is GatewayRuntimeEvent.Frame -> if (event.frame.kind == "sent") {
                        holder.recordOutgoingSent(event.frame.sessionId)
                    }
                    is GatewayRuntimeEvent.RequestCancelled -> if (event.requestType == "message") {
                        holder.handleOutgoingSendUnconfirmed()
                    }
                    is GatewayRuntimeEvent.RequestTimedOut -> if (event.requestType == "message") {
                        holder.handleOutgoingSendUnconfirmed()
                    }
                    else -> Unit
                }
            }
        }
        try {
            runCurrent()
            runtime.connectStoredIfPaired(); runCurrent()
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["sessions","message"]}""")
            transport.receive("""{"kind":"sessions","items":[]}""")
            runCurrent()
            holder.selectSession("s-a")
            suspend fun send(text: String): AndroidSharedStateHolder.PreparedOutgoingSend {
                holder.messageDraft = text
                val submission = holder.captureMessageSubmissionForTest()
                val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
                val accepted = runtime.sendMessage(text, emptyList(), submission.sessionId, null)
                holder.settleOutgoingSendLocally(submission, outgoing, accepted, clearInputOnSuccess = true)
                runCurrent()
                return outgoing
            }

            // 新拒绝码与本地busy同名也必须按来源区分，不能只靠字符串白名单。
            for (code in listOf("future-message-rejection", "request-busy")) {
                val outgoing = send("待恢复原稿")
                transport.receive("""{"kind":"error","requestType":"message","sessionId":"s-a","code":"$code"}""")
                runCurrent()
                assertTrue(errors.any { it.reason == code && it.serverResponse })
                assertFalse(runtime.state.value.shouldKeepAliveInBackground)
                assertEquals(outgoing.sendToken, holder.outgoingPreviews.single().sendToken)
                assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
                assertEquals("待恢复原稿", holder.messageDraft)
                holder.messageDraft = ""
                holder.restoreOutgoingPreviewDraft(outgoing.sendToken)
                assertEquals("待恢复原稿", holder.messageDraft)
                assertTrue(holder.outgoingPreviews.isEmpty())
            }

            send("A")
            val beforeBusy = transport.sentPayloads.count { "\"type\":\"message\"" in it }
            send("B") // 本地busy只结束B；A仍应等待自己的ACK。
            assertEquals(beforeBusy, transport.sentPayloads.count { "\"type\":\"message\"" in it })
            assertTrue(errors.any { it.reason == "request-busy" && !it.serverResponse })
            assertEquals("B", holder.messageDraft)
            assertTrue(holder.outgoingPreviews.isEmpty())
            transport.receive("""{"kind":"sent","sessionId":"wrong-session"}""")
            transport.receive("""{"kind":"sent"}""")
            runCurrent()
            assertEquals(setOf("s-a"), runtime.state.value.activeTurnSessionIds)
            transport.receive("""{"kind":"error","requestType":"message","sessionId":"s-a","code":"future-message-rejection"}""")
            runCurrent()
            assertEquals("A", holder.outgoingPreviews.single().draftText)
            assertEquals("B", holder.messageDraft)
            assertFalse(holder.canRestoreOutgoingDraft)
            assertFalse(runtime.state.value.shouldKeepAliveInBackground)

            holder.messageDraft = ""
            holder.restoreOutgoingPreviewDraft(holder.outgoingPreviews.single().sendToken)
            val unconfirmed = send("通用错误前的原稿")
            transport.receive("""{"kind":"error","code":"internal"}""")
            runCurrent()
            // 通用错误取消 message 并保留未确认稿；随后 gateway 拒绝不二次消费或改成失败。
            assertEquals(unconfirmed.sendToken, holder.outgoingPreviews.single().sendToken)
            assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)
            val wireCount = transport.sentPayloads.count { "\"type\":\"message\"" in it }
            holder.restoreOutgoingPreviewDraft(unconfirmed.sendToken)
            assertEquals("通用错误前的原稿", holder.messageDraft)
            assertEquals(wireCount, transport.sentPayloads.count { "\"type\":\"message\"" in it })
            assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        } finally {
            holder.close()
        }
    }

    @Test
    fun oversizedHistoryRecoversWithoutCancellingLoading_andPartialTurnPagesMergeWithoutDuplicates() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(transport, FakePreferences(), FakeCredentials(),
            FakeAttachmentCache(), FakeNetworkMonitor(), ImmediateClock, backgroundScope)
        val errors = mutableListOf<GatewayRuntimeEvent.RequestRejected>()
        val projection = AndroidGatewayProjection(onHistoryPageRequested = { session, before, reset ->
            backgroundScope.launch { runtime.requestHistory(session, before, resetLeaf = reset) }
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect { event ->
                when (event) {
                    is GatewayRuntimeEvent.Frame -> projection.acceptFrame(event.rawJson, event.frame,
                        event.correlatedSessionId, event.normalizedEvents)
                    is GatewayRuntimeEvent.RequestRejected -> {
                        errors += event
                        if (event.requestType == "history") event.targetSessionId?.let(projection::historyCancelled)
                    }
                    else -> Unit
                }
            }
        }
        try {
            runCurrent()
            runtime.connectStoredIfPaired();runCurrent()
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["sessions","history","history-turns-v1"]}""")
            transport.receive("""{"kind":"sessions","items":[{"id":"large","name":"合成大轮次","running":false}]}""")
            runCurrent()
            projection.selectSession("large");runCurrent()
            assertTrue(projection.snapshot().selectedHistoryIsLoading)
            transport.receive("""{"kind":"error","requestType":"history","code":"history-turn-too-large","sessionId":"large"}""")
            runCurrent()
            assertTrue("中间超限不能提前结束投影加载", projection.snapshot().selectedHistoryIsLoading)
            assertTrue(errors.isEmpty())
            assertEquals(2, transport.sentPayloads.count { "\"type\":\"history\"" in it })
            assertFalse(transport.sentPayloads.last().contains("turnLimit"))

            // 最新页在轮中间开始，没有user或对应toolCall；最终正文仍必须可见。
            val tail = """{"kind":"history","sessionId":"large","messages":[{"role":"toolResult","toolCallId":"c","toolName":"read","content":[{"type":"text","text":"合成工具结果"}]},{"role":"assistant","content":[{"type":"text","text":"合成最终回复"}],"stopReason":"stop"}],"entryIds":["e3","e4"],"hasMore":true,"oldestEntryId":"e3","bytes":200}"""
            transport.receive(tail);runCurrent()
            assertFalse(projection.snapshot().selectedHistoryIsLoading)
            assertEquals(1, projection.snapshot().conversation.count { it.text == "合成最终回复" })
            assertEquals("e3", projection.snapshot().selectedHistoryNextCursor)
            projection.loadHistory("large", older = true);runCurrent()
            assertTrue(projection.snapshot().selectedHistoryIsLoading)
            assertTrue(transport.sentPayloads.last().contains("\"beforeEntryId\":\"e3\""))
            assertFalse(transport.sentPayloads.last().contains("turnLimit"))
            transport.receive("""{"kind":"history","sessionId":"large","messages":[{"role":"user","content":[{"type":"text","text":"合成开头"}]},{"role":"assistant","content":[{"type":"thinking","thinking":"合成思考"},{"type":"toolCall","id":"c","name":"read","arguments":{"path":"synthetic.txt"}}],"stopReason":"toolUse"}],"entryIds":["e1","e2"],"hasMore":false,"oldestEntryId":"e1","bytes":200}""")
            runCurrent()
            val loaded = projection.snapshot()
            assertFalse(loaded.selectedHistoryIsLoading)
            assertFalse(loaded.selectedHistoryHasMore)
            assertEquals(1, loaded.conversation.count { it.text == "合成开头" })
            assertEquals(1, loaded.conversation.count { it.text == "合成最终回复" })
            assertTrue(loaded.conversation.indexOfFirst { it.text == "合成开头" } <
                loaded.conversation.indexOfFirst { it.text == "合成最终回复" })
            projection.loadHistory("large", older = false, preserveLive = true);runCurrent()
            assertFalse(transport.sentPayloads.last().contains("turnLimit"))
            transport.receive(tail);runCurrent()
            assertEquals(1, projection.snapshot().conversation.count { it.text == "合成开头" })
            assertEquals(1, projection.snapshot().conversation.count { it.text == "合成最终回复" })
            assertTrue(errors.isEmpty())

            // 重连重载按既有语义重建最新窗口，分页模式仍不能退回turn。
            projection.catchUpSelectedHistoryAfterReconnect();runCurrent()
            assertFalse(transport.sentPayloads.last().contains("turnLimit"))
            assertTrue(transport.sentPayloads.last().contains("\"resetLeaf\":true"))
            transport.receive(tail);runCurrent()
            assertFalse(projection.snapshot().selectedHistoryIsLoading)
            assertEquals(1, projection.snapshot().conversation.count { it.text == "合成最终回复" })
            projection.loadHistory("large", older = true);runCurrent()
            assertTrue(transport.sentPayloads.last().contains("\"beforeEntryId\":\"e3\""))
            assertFalse(transport.sentPayloads.last().contains("turnLimit"))
        } finally { projection.close() }
    }

    @Test
    fun androidHostReplaysFakeGatewayWithoutLosingStateAcrossReconnect() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val network = FakeNetworkMonitor()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = preferences,
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = network,
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        val frames = mutableListOf<GatewayRuntimeEvent.Frame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    frames += it
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId, it.normalizedEvents)
                }
            }
        }
        runCurrent()

        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        assertEquals("Bearer token is passed out of ordinary preferences", "stored-token", transport.specs.single().bearerToken)
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(frames.any { it.frame.kind == "sessions" })

        projection.selectSession("session-a")
        assertTrue(runtime.subscribe("session-a"))
        assertTrue(runtime.requestHistory("session-a"))
        transport.receive(
            """{"kind":"history","sessionId":"session-a","messages":[{"role":"user","content":[{"type":"text","text":"history"}]}],"entryIds":["e1"],"hasMore":false,"oldestEntryId":"e1","bytes":64}"""
        )
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":2,"time":101,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"partial","contentIndex":0}}}"""
        )
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":3,"time":102,"event":{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"final"}]}}}"""
        )
        runCurrent()
        assertEquals(listOf("history", "final"), projection.snapshot().conversation.map { it.text })
        assertTrue(transport.sentPayloads.any { "\"type\":\"subscribe\"" in it && "session-a" in it })

        network.mutable.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        network.mutable.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        assertEquals(2, transport.specs.size)
        assertEquals("wss://gateway.example/ws/mobile", preferences.load().endpoint)
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true}""")
        runCurrent()
        assertEquals(listOf("history", "final"), projection.snapshot().conversation.map { it.text })
        assertTrue(transport.sentPayloads.count { "\"type\":\"subscribe\"" in it } >= 2)
        projection.close()
    }

    @Test
    fun gatewayRuntimeNormalizesUserImageBlockWithAttachmentIdIntoConversation() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId, it.normalizedEvents)
                }
            }
        }
        runCurrent()

        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        projection.selectSession("session-img")
        assertTrue(runtime.subscribe("session-img"))

        // 真实网关形状：live message_end(user) 带 attachmentId（可能带 data 或
        // dataOmitted）；must land in ConversationItem.images。
        transport.receive(
            """{"kind":"event","sessionId":"session-img","seq":1,"time":100,"event":{"type":"message_end","message":{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","mimeType":"image/png","attachmentId":"img-e2e-1","dataOmitted":true,"dataBytes":2048}]}}}"""
        )
        runCurrent()
        val user = projection.snapshot().conversation.filter { it.kind == ConversationItemKind.USER }
        assertEquals(1, user.size)
        assertEquals("img-e2e-1", user.single().images.singleOrNull()?.attachmentId)
        projection.close()
    }

    private class FakeTransport : GatewayTransport {
        private val mutableState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val eventsFlow = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 32)
        val specs = mutableListOf<GatewayConnectionSpec>()
        val sentPayloads = mutableListOf<String>()
        override val state: StateFlow<GatewayTransportState> = mutableState
        override val events: Flow<GatewayTransportEvent> = eventsFlow
        override suspend fun open(spec: GatewayConnectionSpec) {
            specs += spec
            emitState(GatewayTransportState.Opening(spec.generation))
        }
        override suspend fun send(text: String) {
            sentPayloads += text
        }
        override suspend fun close() {
            emitState(GatewayTransportState.Closed(specs.lastOrNull()?.generation ?: 0))
        }
        fun open() {
            emitState(GatewayTransportState.Open(specs.last().generation))
        }
        fun failWebSocket() {
            emitState(GatewayTransportState.Failed(
                generation = specs.last().generation,
                reason = "websocket-failure",
                recoverable = false,
            ))
        }
        fun receive(json: String) {
            assertTrue(
                eventsFlow.tryEmit(
                    GatewayTransportEvent.Frame(
                        GatewayTransportFrame(
                            specs.last().generation,
                            json,
                            json.encodeToByteArray().size
                        )
                    )
                )
            )
        }

        private fun emitState(value: GatewayTransportState) {
            mutableState.value = value
            assertTrue(eventsFlow.tryEmit(GatewayTransportEvent.State(value)))
        }
    }

    private class FakePreferences : GatewayPreferences {
        private val value = MutableStateFlow(
            GatewayPreferencesSnapshot(endpoint = "wss://gateway.example/ws/mobile")
        )
        override val snapshots: Flow<GatewayPreferencesSnapshot> = value
        override suspend fun load(): GatewayPreferencesSnapshot = value.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
            value.value = snapshot
        }
    }

    private class FakeCredentials : GatewayCredentialStore {
        override suspend fun loadOrCreateDeviceId(): String = "android-installation"
        override suspend fun loadToken(): String = "stored-token"
        override suspend fun saveToken(token: String) = Unit
        override suspend fun deleteToken() = Unit
    }

    private class FakeAttachmentCache : GatewayAttachmentCache {
        override suspend fun read(attachmentId: String): ByteArray? = null
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = true
        override suspend fun removeExpired() = Unit
    }

    private class FakeNetworkMonitor : GatewayNetworkMonitor {
        val mutable = MutableStateFlow(GatewayNetworkState.AVAILABLE)
        override val state: StateFlow<GatewayNetworkState> = mutable
    }

    private object ImmediateClock : GatewayClock {
        override fun nowEpochMilliseconds(): Long = 0
        override suspend fun delay(milliseconds: Long) = Unit
    }
    @Test
    fun bridgeSnapshotProjectsPartialAndToolsThenReplacesByGeneration() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId, it.normalizedEvents)
                }
            }
        }
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        projection.selectSession("session-b")

        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-b","instanceId":"i1","epoch":"e1","bridgeSeq":5,"busy":true,"partial":{"role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"partial answer"}]},"tools":[{"toolCallId":"t1","toolName":"bash","args":{"command":"ls"}}]}"""
        )
        runCurrent()
        var conversation = projection.snapshot().conversation
        assertEquals(listOf("plan", "partial answer", "{\n  \"command\": \"ls\"\n}"), conversation.map { it.text })
        assertEquals("bash", conversation.last().title)
        assertEquals(ConversationItemKind.TOOL, conversation.last().kind)

        // 工具事件只接管工具项，不能把快照里的 assistant partial 一起清掉。
        transport.receive(
            """{"kind":"event","sessionId":"session-b","instanceId":"i1","epoch":"e1","bridgeSeq":6,"seq":1,"event":{"type":"tool_execution_start","toolCallId":"t2","toolName":"read","args":{"path":"a"}}}"""
        )
        runCurrent()
        assertEquals("partial answer", projection.snapshot().conversation.first { it.kind == ConversationItemKind.ASSISTANT }.text)

        // 同一代际倒退的 bridgeSeq 是迟到帧，不能覆盖快照。
        transport.receive(
            """{"kind":"event","sessionId":"session-b","instanceId":"i1","epoch":"e1","bridgeSeq":4,"seq":1,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"stale","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(listOf("plan", "partial answer", "{\n  \"command\": \"ls\"\n}"), projection.snapshot().conversation.map { it.text })

        // 代际变化先丢弃旧快照，再由新快照整体重建；不同实例/epoch 的 seq 不互相比对。
        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-b","instanceId":"i1","epoch":"e2","bridgeSeq":1,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"new epoch"}]},"tools":[]}"""
        )
        runCurrent()
        conversation = projection.snapshot().conversation
        assertEquals(listOf("new epoch"), conversation.map { it.text })

        // 历史是同一会话文件，代际切换应保留；新快照只替换实时段。
        assertTrue(runtime.requestHistory("session-b"))
        runCurrent()
        transport.receive(
            """{"kind":"history","sessionId":"session-b","messages":[{"role":"user","content":[{"type":"text","text":"loaded history"}]}],"entryIds":["h1"],"hasMore":false,"oldestEntryId":"h1","bytes":32}"""
        )
        runCurrent()
        assertEquals(listOf("loaded history", "new epoch"), projection.snapshot().conversation.map { it.text })
        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-b","instanceId":"i1","epoch":"e2","bridgeSeq":7,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"newer partial"}]},"tools":[]}"""
        )
        runCurrent()
        assertEquals(listOf("loaded history", "newer partial"), projection.snapshot().conversation.map { it.text })

        // 旧 epoch 的迟到事件不能把代际切回去，也不能污染新快照。
        transport.receive(
            """{"kind":"event","sessionId":"session-b","instanceId":"i1","epoch":"e1","bridgeSeq":100,"seq":2,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"old epoch late","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(listOf("loaded history", "newer partial"), projection.snapshot().conversation.map { it.text })

        // 旧代的 bridge-offline 也不能把代际切回去；新代际后续事件必须继续投影。
        transport.receive(
            """{"kind":"bridge-offline","sessionId":"session-b","instanceId":"i1","epoch":"e1","reason":"process-exited"}"""
        )
        runCurrent()
        transport.receive(
            """{"kind":"event","sessionId":"session-b","instanceId":"i1","epoch":"e2","bridgeSeq":8,"seq":3,"time":4.0,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" late","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(
            listOf("loaded history", "newer partial late"),
            projection.snapshot().conversation.map { it.text }
        )

        // 同代际重连会再下发快照，但网关侧 normalizer 可能已见过 message_start、turn 已推进。
        // 快照到达必须把两侧口径一起重置，否则前缀在 text-0-0、后续 delta 进 text-1-0。
        transport.receive(
            """{"kind":"event","sessionId":"session-b","instanceId":"i1","epoch":"e2","bridgeSeq":9,"seq":4,"time":5.0,"event":{"type":"message_start","message":{"role":"assistant","content":[]}}}"""
        )
        runCurrent()
        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-b","instanceId":"i1","epoch":"e2","bridgeSeq":10,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"reset prefix"}]},"tools":[]}"""
        )
        runCurrent()
        transport.receive(
            """{"kind":"event","sessionId":"session-b","instanceId":"i1","epoch":"e2","bridgeSeq":11,"seq":5,"time":6.0,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" tail","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(
            "reset prefix tail",
            projection.snapshot().conversation.last { it.kind == ConversationItemKind.ASSISTANT }.text
        )
        projection.close()
    }

    @Test
    fun staleSameGenerationSnapshotCannotRollBackPrefix() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId, it.normalizedEvents)
                }
            }
        }
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        projection.selectSession("session-stale")

        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-stale","instanceId":"i1","epoch":"e1","bridgeSeq":10,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"prefix"}]},"tools":[]}"""
        )
        runCurrent()
        transport.receive(
            """{"kind":"event","sessionId":"session-stale","instanceId":"i1","epoch":"e1","bridgeSeq":11,"seq":1,"time":1.0,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" suffix","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(
            listOf("prefix suffix"),
            projection.snapshot().conversation.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text }
        )

        // 同代际低水位快照是迟到重放，不能把更长的正文退回旧 prefix。
        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-stale","instanceId":"i1","epoch":"e1","bridgeSeq":8,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"old prefix"}]},"tools":[]}"""
        )
        runCurrent()
        val assistant = projection.snapshot().conversation.filter { it.kind == ConversationItemKind.ASSISTANT }.map { it.text }
        assertEquals(listOf("prefix suffix"), assistant)
        assertFalse(assistant.any { it.contains("old prefix") })
        projection.close()
    }

    @Test
    fun equalGenerationSnapshotAfterOfflineRecoversPartialAndDelta() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId, it.normalizedEvents)
                }
            }
        }
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        projection.selectSession("session-equal")
        assertTrue(runtime.requestHistory("session-equal"))
        runCurrent()
        transport.receive(
            """{"kind":"history","sessionId":"session-equal","messages":[{"role":"user","content":[{"type":"text","text":"question"}]}],"entryIds":["u1"],"hasMore":false,"latestEntryId":"u1"}"""
        )
        runCurrent()
        assertEquals(listOf("question"), projection.snapshot().conversation.map { it.text })
        val snapshot = """{"kind":"bridge-snapshot","sessionId":"session-equal","instanceId":"i1","epoch":"e1","bridgeSeq":10,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"prefix"}]},"tools":[]}"""
        transport.receive(snapshot)
        runCurrent()
        transport.receive(
            """{"kind":"bridge-offline","sessionId":"session-equal","instanceId":"i1","epoch":"e1","reason":"process-exited"}"""
        )
        runCurrent()
        assertEquals(listOf("question"), projection.snapshot().conversation.map { it.text })

        // 原生实例未换代且没有新事件时，恢复快照允许与已消费水位相等；重复仍幂等。
        transport.receive(snapshot)
        transport.receive(snapshot)
        runCurrent()
        assertEquals(listOf("question", "prefix"), projection.snapshot().conversation.map { it.text })
        transport.receive(
            """{"kind":"event","sessionId":"session-equal","instanceId":"i1","epoch":"e1","bridgeSeq":11,"seq":1,"time":2.0,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" suffix","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(
            listOf("question", "prefix suffix"),
            projection.snapshot().conversation.map { it.text }
        )
        projection.close()
    }

    @Test
    fun explicitDisconnectReconnectAllowsEqualSnapshotAndDelta() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        val projection = AndroidGatewayProjection()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.events.collect {
                if (it is GatewayRuntimeEvent.Frame) {
                    projection.acceptFrame(it.rawJson, it.frame, it.correlatedSessionId, it.normalizedEvents)
                }
            }
        }
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        projection.selectSession("session-reconnect")
        assertTrue(runtime.requestHistory("session-reconnect"))
        runCurrent()
        transport.receive(
            """{"kind":"history","sessionId":"session-reconnect","messages":[{"role":"user","content":[{"type":"text","text":"question"}]}],"entryIds":["u1"],"hasMore":false,"latestEntryId":"u1"}"""
        )
        runCurrent()
        assertEquals(listOf("question"), projection.snapshot().conversation.map { it.text })
        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-reconnect","instanceId":"i1","epoch":"e1","bridgeSeq":10,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"prefix"}]},"tools":[]}"""
        )
        runCurrent()
        // 让 normalizer 的 turn 推进；显式断开只清 normalizer，不清投影旧位置。
        transport.receive(
            """{"kind":"event","sessionId":"session-reconnect","instanceId":"i1","epoch":"e1","bridgeSeq":11,"seq":1,"time":1.0,"event":{"type":"message_start","message":{"role":"assistant","content":[]}}}"""
        )
        transport.receive(
            """{"kind":"event","sessionId":"session-reconnect","instanceId":"i1","epoch":"e1","bridgeSeq":12,"seq":2,"time":2.0,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" suffix","contentIndex":0}}}"""
        )
        runCurrent()

        runtime.disconnect()
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()

        // 用户设置页断开后重连，源没有新事件，快照仍为 seq=12。
        transport.receive(
            """{"kind":"bridge-snapshot","sessionId":"session-reconnect","instanceId":"i1","epoch":"e1","bridgeSeq":12,"busy":true,"partial":{"role":"assistant","content":[{"type":"text","text":"prefix suffix"}]},"tools":[]}"""
        )
        runCurrent()
        transport.receive(
            """{"kind":"event","sessionId":"session-reconnect","instanceId":"i1","epoch":"e1","bridgeSeq":13,"seq":3,"time":3.0,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":" more","contentIndex":0}}}"""
        )
        runCurrent()
        assertEquals(
            listOf("question", "prefix suffix more"),
            projection.snapshot().conversation.map { it.text }
        )
        projection.close()
    }

    @Test
    fun bridgeWriteBindsOperationAndEpochAndCanQueryStatus() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}""")
        runCurrent()
        assertTrue(
            runtime.sendMessage(
                text = "hello",
                images = emptyList(),
                sessionId = "s-1",
                operationId = "op-1",
                instanceId = "instance-1",
                epoch = "epoch-1"
            )
        )
        runCurrent()
        val sent = transport.sentPayloads.last()
        assertTrue(sent.contains("\"operationId\":\"op-1\""))
        assertTrue(sent.contains("\"instanceId\":\"instance-1\""))
        assertTrue(sent.contains("\"epoch\":\"epoch-1\""))
        assertTrue(runtime.operationStatus("s-1", "op-1"))
        runCurrent()
        assertTrue(transport.sentPayloads.last().contains("\"type\":\"operation-status\""))
        assertTrue(transport.sentPayloads.last().contains("\"operationId\":\"op-1\""))
    }

    @Test
    fun bridgeStartSendsOperationAndReleasesLaneOnMatchingReply() = runTest {
        val transport = FakeTransport()
        val runtime = GatewayRuntime(
            transport = transport,
            preferences = FakePreferences(),
            credentials = FakeCredentials(),
            attachmentCache = FakeAttachmentCache(),
            networkMonitor = FakeNetworkMonitor(),
            clock = ImmediateClock,
            scope = backgroundScope
        )
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        runCurrent()
        transport.open()
        transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","bridge","bridge-start"]}""")
        runCurrent()
        assertTrue(runtime.startBridgeSession("s-offline", "start-1"))
        runCurrent()
        assertTrue(transport.sentPayloads.last().contains("\"type\":\"bridge-start\""))
        assertTrue(transport.sentPayloads.last().contains("\"sessionId\":\"s-offline\""))
        assertTrue(transport.sentPayloads.last().contains("\"operationId\":\"start-1\""))
        transport.receive(
            """{"kind":"bridge-started","operationId":"start-1","sessionId":"s-offline","instanceId":"i1","epoch":"e1","pid":1,"duplicate":false,"attachHint":"pi-mobile attach s-offline","requestType":"bridge-start"}"""
        )
        runCurrent()
        // 匹配的回执必须释放 lane，否则用户重试会一直 REJECT_IF_BUSY。
        assertTrue(runtime.startBridgeSession("s-offline", "start-2"))
    }

}
