package com.finecat.pimobile.android

import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayRuntime
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
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
