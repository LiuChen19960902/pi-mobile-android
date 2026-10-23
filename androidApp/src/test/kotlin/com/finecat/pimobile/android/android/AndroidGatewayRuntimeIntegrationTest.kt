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
}
