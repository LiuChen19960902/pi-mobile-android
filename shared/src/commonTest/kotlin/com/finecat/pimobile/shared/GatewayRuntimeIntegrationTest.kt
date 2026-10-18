package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadException
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.gateway.GatewayRuntime
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.gateway.gatewayAttachmentCacheKey
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
import com.finecat.pimobile.shared.protocol.wireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pi 原生协议下的运行时集成行为：配对（version 1）、hello 后 replay（sessions+subscribe）、
 * lane 策略、错误帧 wire code 透传、agent_settled 回合收尾、超时与重连恢复。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRuntimeIntegrationTest {
    private val dispositionHello = """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["sessions","message","dequeue","prompt-disposition-v1"]}"""

    @Test
    fun startedAckBeforeStartEventKeepsActualRunAliveInBackground() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("start", emptyList(), "s1"))
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"started","isStreaming":true,"isCompacting":false}""")
        runCurrent()
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(runtime.state.value.shouldKeepAliveInBackground)
        transport.receive("""{"kind":"event","sessionId":"s1","seq":1,"time":1,"event":{"type":"agent_start"}}""")
        transport.receive("""{"kind":"event","sessionId":"s1","seq":2,"time":2,"event":{"type":"agent_settled"}}""")
        runCurrent()
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
    }

    @Test
    fun malformedDispositionAckDoesNotConsumeInputOrImmediatelyDisconnect() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("pending", emptyList(), "s1"))
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"handled"}""")
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "sent" })
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.reason == "invalid-message-ack" })
        assertFalse(runtime.sendMessage("blocked", emptyList(), "s1"))
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"handled","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(runtime.sendMessage("next", emptyList(), "s1"))
    }

    @Test
    fun authoritativeIdleSnapshotReleasesObservedRunButPreservesPendingOtherInput() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("start", emptyList(), "s1"))
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"started","isStreaming":true,"isCompacting":false}""")
        runCurrent()
        assertTrue(runtime.sendMessage("pending", emptyList(), "s2"))
        assertTrue(runtime.requestState("s1"))
        transport.receive("""{"kind":"state","sessionId":"s1","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertEquals(setOf("s2"), runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"sent","sessionId":"s2","disposition":"handled","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun handledInputWithoutRunReleasesOnlyItsOwnWait() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("handled", emptyList(), null, "/tmp/project"))
        transport.receive("""{"kind":"sent","sessionId":"new","disposition":"handled","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertFalse(runtime.state.value.hasUnassociatedTurn)
        assertTrue(runtime.sendMessage("next", emptyList(), "new"))
    }

    @Test
    fun handledCanStartRealRunAndSettledCannotConsumeNextUnacknowledgedInput() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("handled with run", emptyList(), "s1"))
        transport.receive("""{"kind":"event","sessionId":"s1","seq":1,"time":1,"event":{"type":"agent_start"}}""")
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"handled","isStreaming":true,"isCompacting":false}""")
        runCurrent()
        assertEquals(setOf("s1"), runtime.state.value.activeTurnSessionIds)
        assertTrue(runtime.sendMessage("next input", emptyList(), "s1"))
        transport.receive("""{"kind":"event","sessionId":"s1","seq":2,"time":2,"event":{"type":"agent_settled"}}""")
        runCurrent()
        assertEquals(setOf("s1"), runtime.state.value.activeTurnSessionIds) // 仍等待next input回执。
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"handled","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun queuedInputsUseOneActualRunAndHandledDoesNotStopAnotherSession() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("start", emptyList(), "s1"))
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"started","isStreaming":true,"isCompacting":false}""")
        runCurrent()
        assertTrue(runtime.sendMessage("queued", emptyList(), "s1"))
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"queued","isStreaming":true,"isCompacting":false}""")
        runCurrent()
        assertTrue(runtime.sendMessage("consume", emptyList(), "s2"))
        transport.receive("""{"kind":"sent","sessionId":"s2","disposition":"handled","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertEquals(setOf("s1"), runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"event","sessionId":"s1","seq":5,"time":5,"event":{"type":"agent_settled"}}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun completedRunBeforeSentAndLegacyGatewayKeepTheirOwnAccounting() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, dispositionHello)
        assertTrue(runtime.sendMessage("fast", emptyList(), "s1"))
        transport.receive("""{"kind":"event","sessionId":"s1","seq":1,"time":1,"event":{"type":"agent_start"}}""")
        transport.receive("""{"kind":"event","sessionId":"s1","seq":2,"time":2,"event":{"type":"agent_settled"}}""")
        transport.receive("""{"kind":"sent","sessionId":"s1","disposition":"started","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        val legacyTransport = FakeTransport()
        val legacy = newRuntime(legacyTransport)
        connectHello(legacyTransport, legacy)
        assertTrue(legacy.sendMessage("legacy", emptyList(), "old"))
        legacyTransport.receive("""{"kind":"sent","sessionId":"old"}""")
        runCurrent()
        assertTrue(legacy.state.value.shouldKeepAliveInBackground)
        legacyTransport.receive("""{"kind":"event","sessionId":"old","seq":1,"time":1,"event":{"type":"agent_settled"}}""")
        runCurrent()
        assertFalse(legacy.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun inputQueriesImmediatelyReplaceActiveAndIgnoreOldSuccessAndError() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["input-completions","sessions"]}""")
        assertTrue(runtime.sendRequest(GatewayRequests.inputCompletions("old", "@a", sessionId = "s1")))
        assertTrue(runtime.sendRequest(GatewayRequests.inputCompletions("new", "@ab", sessionId = "s1")))
        assertEquals(2, transport.sentTypes.count { it == "input-completions" })
        transport.receive("""{"kind":"input-completions","queryId":"old","sessionId":"s1","cwd":"/project","token":"@a","prefix":"@a","items":[]}""")
        transport.receive("""{"kind":"error","requestType":"input-completions","queryId":"old","sessionId":"s1","code":"input-timeout"}""")
        transport.receive("""{"kind":"input-completions","queryId":"new","sessionId":"s1","cwd":"/project","token":"@ab","prefix":"@ab","items":[]}""")
        runCurrent()
        assertEquals(listOf("new"), events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .filter { it.frame.kind == "input-completions" }.map { it.frame.queryId })
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any { it.correlationId == "old" })
        transport.receive("""{"kind":"input-completions","queryId":"new","sessionId":"s1","token":"@ab","prefix":"@ab"}""")
        runCurrent()
        assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "input-completions" })
    }

    @Test
    fun inputPreviewRequiresIdAndCannotConsumeSessionReply() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["input-resources","sessions"]}""")
        assertTrue(runtime.sendRequest(GatewayRequests.inputResources("preview", cwd = "/project")))
        transport.receive("""{"kind":"input-resources","queryId":"preview","sessionId":"wrong","cwd":"/project","commands":[]}""")
        transport.receive("""{"kind":"input-resources","cwd":"/project","commands":[]}""")
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "input-resources" })
        transport.receive("""{"kind":"input-resources","queryId":"preview","cwd":"/project","commands":[]}""")
        runCurrent()
        assertEquals("preview", events.filterIsInstance<GatewayRuntimeEvent.Frame>().single { it.frame.kind == "input-resources" }.frame.queryId)
    }

    @Test
    fun inputQueryErrorsTimeoutAndDisconnectRetainTheirQueryIdentity() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 100)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["input-resources","sessions"]}""")
        transport.receive("""{"kind":"sessions","items":[]}"""); runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.inputResources("error", cwd = "/project")))
        transport.receive("""{"kind":"error","requestType":"input-resources","queryId":"error","code":"input-unavailable"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.correlationId == "error" })
        assertTrue(runtime.sendRequest(GatewayRequests.inputResources("timeout", cwd = "/project")))
        advanceTimeBy(101); runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().any { it.correlationId == "timeout" })
        assertTrue(runtime.sendRequest(GatewayRequests.inputResources("disconnect", cwd = "/project")))
        runtime.disconnect(); runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any { it.correlationId == "disconnect" })
    }

    @Test fun inputQueryBuildersRequireOneContextAndUtf16Bounds() {
        assertFailsWith<IllegalArgumentException> { GatewayRequests.inputResources("q") }
        assertFailsWith<IllegalArgumentException> { GatewayRequests.inputResources("q", "s", "/a") }
        assertFailsWith<IllegalArgumentException> { GatewayRequests.inputCompletions("q", "x".repeat(2049), cwd = "/a") }
        val request = GatewayRequests.inputResources("q", cwd = "/a")
        assertNull(request.targetSessionId)
        assertEquals("q", request.correlationId)
        assertEquals("/a", wireJson.parseToJsonElement(request.payload).jsonObject["cwd"]?.jsonPrimitive?.content)
    }

    @Test
    fun providerProbeTimeoutThenRetryCannotConsumeLateSuccessOrError() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["provider-test","provider-test-id-v1","sessions"]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        fun request(id: String) = GatewayRequests.providerTest(model = "m", providerTestId = id)
        assertTrue(runtime.sendRequest(request("old")))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().any { it.correlationId == "old" })
        assertEquals(1, transport.connectionSpecs.size, "探针超时不打断其他业务连接")
        assertTrue(runtime.sendRequest(request("new")))
        transport.receive("""{"kind":"provider-test-result","id":"p","providerTestId":"old","ok":true}""")
        transport.receive("""{"kind":"error","requestType":"provider-test","providerTestId":"old","code":"bad-request"}""")
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "provider-test-result" })
        assertFalse(runtime.sendRequest(request("busy")))
        transport.receive("""{"kind":"provider-test-result","id":"p","providerTestId":"new","ok":false,"code":"auth-rejected"}""")
        runCurrent()
        val accepted = events.filterIsInstance<GatewayRuntimeEvent.Frame>().single { it.frame.kind == "provider-test-result" }.frame
        assertEquals("new", accepted.providerTestId)
        assertEquals(false, accepted.ok)
        transport.receive("""{"kind":"provider-test-result","id":"p","providerTestId":"new","ok":true}""")
        runCurrent()
        assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "provider-test-result" })
        assertTrue(runtime.sendRequest(request("next")))
        transport.receive("""{"kind":"error","requestType":"provider-test","providerTestId":"next","code":"bad-request"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.correlationId == "next" })
        assertTrue(runtime.sendRequest(request("missing")))
        transport.receive("""{"kind":"provider-test-result","id":"p","ok":true}""")
        runCurrent()
        assertFalse(runtime.sendRequest(request("still-busy")))
        assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "provider-test-result" })
    }

    @Test
    fun scopeSaveDoesNotConsumeLateSuccessOrErrorForAnotherRequest() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["model-scope-put","sessions"]}""")
        val request = GatewayRequests.modelScopePut("current", emptyList(), emptyList(), false, true)
        assertTrue(runtime.sendRequest(request))
        transport.receive("""{"kind":"model-scope-saved","scopeRequestId":"old","scope":{"patterns":[],"groups":[],"selected":[]}}""")
        transport.receive("""{"kind":"error","requestType":"model-scope-put","scopeRequestId":"old","code":"scope-conflict"}""")
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "model-scope-saved" })
        assertFalse(runtime.sendRequest(request.copy(correlationId = "next")))
        transport.receive("""{"kind":"error","requestType":"model-scope-put","scopeRequestId":"current","code":"scope-conflict"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "model-scope-put" && it.correlationId == "current" && it.reason == "scope-conflict"
        })
        assertTrue(runtime.sendRequest(request.copy(correlationId = "next")))
        transport.receive("""{"kind":"model-scope-saved","scopeRequestId":"next","scope":{"patterns":[],"groups":[],"selected":[]}}""")
        runCurrent()
        assertEquals("next", events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "model-scope-saved" }.frame.scopeRequestId)
        transport.receive("""{"kind":"model-scope-saved","scopeRequestId":"next"}""")
        runCurrent()
        assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "model-scope-saved" })
    }

    @Test
    fun scopeSaveCancellationRetainsItsTokenForMalformedReplyAndDisconnect() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["model-scope-put","sessions"]}""")

        assertTrue(runtime.sendRequest(GatewayRequests.modelScopePut("malformed", emptyList(), emptyList(), false, true)))
        transport.receive("""{"kind":"error","requestType":"model-scope-put","code":"scope-conflict"}""")
        runCurrent()
        assertEquals("malformed", events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>()
            .single { it.requestType == "model-scope-put" }.correlationId)

        assertTrue(runtime.sendRequest(GatewayRequests.modelScopePut("disconnect", emptyList(), emptyList(), false, true)))
        runtime.disconnect()
        runCurrent()
        assertEquals(listOf("malformed", "disconnect"), events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>()
            .filter { it.requestType == "model-scope-put" }.map { it.correlationId })
    }

    @Test
    fun scopeSaveSendFailureAndBusyRejectionsRetainTheirOwnTokens() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["model-scope-put","sessions"]}""")

        transport.failNextSend = true
        assertFalse(runtime.sendRequest(GatewayRequests.modelScopePut("send-failed", emptyList(), emptyList(), false, true)))
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.modelScopePut("active", emptyList(), emptyList(), false, true)))
        assertFalse(runtime.sendRequest(GatewayRequests.modelScopePut("busy", emptyList(), emptyList(), false, true)))
        runCurrent()
        assertEquals(listOf("send-failed", "busy"), events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>()
            .filter { it.requestType == "model-scope-put" }.map { it.correlationId })

        transport.receive("""{"kind":"model-scope-saved","scopeRequestId":"active","scope":{"patterns":[],"groups":[],"selected":[]}}""")
        runCurrent()
        assertEquals("active", events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .single { it.frame.kind == "model-scope-saved" }.frame.scopeRequestId)
    }

    @Test
    fun scopeSaveTimeoutRetainsItsToken() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["model-scope-put","sessions"]}""")
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.modelScopePut("timeout", emptyList(), emptyList(), false, true)))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("timeout", events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>()
            .single { it.requestType == "model-scope-put" }.correlationId)
    }

    @Test
    fun initialNetworkSnapshotDoesNotRestartPairing() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        runtime.pair(encodeBase64Url(
            """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"code","expiresAt":1786937455000}"""
        ))
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals("code", transport.connectionSpecs.single().pairingCode)
    }

    @Test
    fun queuedForegroundCallbackDoesNotRestartPairing() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        runCurrent()
        runtime.applicationDidBecomeActive()
        runtime.pair(encodeBase64Url(
            """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"code","expiresAt":1786937455000}"""
        ))
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals("code", transport.connectionSpecs.single().pairingCode)
    }

    @Test
    fun pairingFlowStoresTokenReplaysSessionsAndNormalizesPiEvents() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val credentials = FakeCredentials()
        val runtime = newRuntime(transport, preferences = preferences, credentials = credentials)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()

        runtime.pair(
            encodeBase64Url(
                """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile",""" +
                    """"pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
            )
        )
        runCurrent()
        assertEquals(GatewayProtocolFixtures.PAIRING_CODE, transport.connectionSpecs.single().pairingCode)
        assertNull(transport.connectionSpecs.single().bearerToken)
        assertEquals("device-installation", transport.connectionSpecs.single().deviceId)

        transport.opened()
        transport.receive(GatewayProtocolFixtures.PAIRED)
        transport.receive(GatewayProtocolFixtures.HELLO)
        runCurrent()
        assertEquals("long-lived-secret", credentials.token)
        val pairedEvent = events.filterIsInstance<GatewayRuntimeEvent.Frame>().first { it.frame.kind == "paired" }
        assertNull(pairedEvent.frame.token)
        assertFalse("long-lived-secret" in pairedEvent.rawJson)
        assertFalse("long-lived-secret" in pairedEvent.toString())
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(1, runtime.state.value.protocolVersion)
        assertEquals(3082, runtime.state.value.serverPort)
        assertEquals(
            setOf("ping", "sessions", "subscribe", "message", "abort", "history", "state"),
            runtime.state.value.capabilities
        )
        // hello 后 replay 只补 sessions（尚未订阅）。
        assertEquals(listOf("sessions"), transport.sentTypes.takeLast(1))

        transport.receive(GatewayProtocolFixtures.PI_SESSIONS)
        runCurrent()
        val sessionsFrame = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last()
        assertEquals("sessions", sessionsFrame.frame.kind)
        assertNull(sessionsFrame.correlatedSessionId)

        assertTrue(runtime.subscribe("s1"))
        transport.receive("""{"kind":"subscribed","sessionId":"s1"}""")
        transport.receive(GatewayProtocolFixtures.PI_EVENT_TEXT_DELTA)
        runCurrent()
        val normalized = events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .last { it.frame.kind == "event" }.normalizedEvents
        assertEquals(1, normalized.size)
        assertEquals("assistant/chunk", normalized.single().event.type)
        assertEquals("text-delta", normalized.single().event.chunkType)
        assertEquals("你好", normalized.single().event.text)
        assertEquals("s1", normalized.single().sessionId)

        runtime.sendMessage("hello", emptyList(), sessionId = null, cwd = "/tmp/pi-demo")
        assertTrue(runtime.state.value.hasUnassociatedTurn)
        transport.receive("""{"kind":"sent","sessionId":"session-new"}""")
        runCurrent()
        assertEquals(setOf("session-new"), runtime.state.value.activeTurnSessionIds)

        transport.receive(
            """{"kind":"event","sessionId":"session-new","seq":10,"time":1786937356.0,"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        val settledEvents = events.filterIsInstance<GatewayRuntimeEvent.Frame>()
            .last { it.frame.kind == "event" }.normalizedEvents
        assertEquals("turn/end", settledEvents.single().event.type)
    }

    @Test
    fun pairingFailureKeepsTransportClosedAndStateUntouched() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        runCurrent()
        val expired = encodeBase64Url(
            """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"code","expiresAt":99}"""
        )
        assertFailsWith<GatewayPairingPayloadException> {
            runtime.pair(expired)
        }
        assertEquals(0, transport.connectionSpecs.size)
        assertEquals(GatewayConnectionState.DISCONNECTED, runtime.state.value.connection)
    }

    @Test
    fun historyLaneCoalescesLatestAndCompletesOnMatchingHistoryFrame() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()

        runtime.requestHistory("session-a")
        runtime.requestHistory("session-b")
        runtime.requestHistory("session-c")
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "history" })
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestCancelled &&
                it.targetSessionId == "session-b" && it.reason == "request-coalesced"
        })

        // 会话不匹配的 history 应答拒绝提交，且不发布帧。
        transport.receive("""{"kind":"history","sessionId":"wrong-session","messages":[],"entryIds":[],"hasMore":false}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "transport" && it.reason == "session-mismatch" &&
                it.targetSessionId == "wrong-session"
        })
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any {
            it.frame.kind == "history" && it.frame.sessionId == "wrong-session"
        })

        // 带帧内 sessionId 的应答：帧未标注会话时关联到请求目标，lane 完成后排队项接发。
        transport.receive("""{"kind":"history","messages":[],"entryIds":[],"hasMore":false}""")
        runCurrent()
        val completed = events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }
        assertEquals("session-a", completed.correlatedSessionId)
        assertEquals(2, transport.sentTypes.count { it == "history" })
        transport.receive("""{"kind":"history","messages":[],"entryIds":[],"hasMore":false}""")
        runCurrent()
        assertEquals(
            "session-c",
            events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "history" }
                .correlatedSessionId
        )
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun helloReplayRestoresSessionsAndSubscriptionsAfterReconnect() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        assertTrue(runtime.subscribe("session-a"))
        transport.receive("""{"kind":"subscribed","sessionId":"session-a"}""")
        runCurrent()

        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        // hello 后 replay：sessions + 上一次订阅。
        assertEquals(listOf("sessions", "subscribe"), transport.sentTypes.takeLast(2))
        assertEquals("session-a", sentSessionIds(transport.sentPayloads.last()))

        runtime.subscribe(null)
        transport.receive("""{"kind":"unsubscribed","sessionId":"session-a"}""")
        runCurrent()
        assertEquals("unsubscribe", transport.sentTypes.last())
        runtime.subscribe(null)
        runCurrent()
        // 没有 previous 时 subscribe(null) 不再发送。
        assertEquals(1, transport.sentTypes.count { it == "unsubscribe" })
        assertFalse(events.any {
            it is GatewayRuntimeEvent.RequestRejected && it.reason == "no-active-request"
        })
    }

    @Test
    fun errorFramesPassWireCodeThroughAndReleaseTurnsExactly() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        runtime.sendMessage("hello", emptyList(), "session-a", null)
        transport.receive(
            """{"kind":"error","code":"prompt-rejected","message":"无可用模型","requestType":"message","sessionId":"session-a"}"""
        )
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "message" && it.reason == "prompt-rejected" && it.targetSessionId == "session-a"
        })
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        runtime.sendMessage("hi", emptyList(), "session-a", null)
        // 非法ACK缺少真实会话，不能提前结束当前lane；新/空白ID同样拒绝。
        listOf("""{"kind":"sent"}""", """{"kind":"sent","sessionId":""}""",
            """{"kind":"sent","sessionId":"   "}""").forEach { invalid ->
            transport.receive(invalid)
            runCurrent()
        }
        assertEquals(0, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "sent" })
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        // sent 帧仍做严格会话关联：目标不匹配的回显不得提交，也不得发布帧。
        transport.receive("""{"kind":"sent","sessionId":"wrong-session"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "transport" && it.reason == "session-mismatch"
        })
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any {
            it.frame.kind == "sent" && it.frame.sessionId == "wrong-session"
        })
        transport.receive(
            """{"kind":"error","code":"session-not-found","requestType":"message","sessionId":"session-b"}"""
        )
        runCurrent()
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        transport.receive(
            """{"kind":"error","code":"future-message-rejection","requestType":"message","sessionId":"session-a"}"""
        )
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "message" && it.reason == "future-message-rejection" && it.serverResponse
        })

        runtime.sendMessage("new", emptyList(), sessionId = null, cwd = "/tmp/pi-demo")
        assertTrue(runtime.state.value.hasUnassociatedTurn)
        transport.receive("""{"kind":"error","code":"bad-request","message":"cwd 不存在"}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "gateway" && it.reason == "bad-request"
        })
        assertFalse(runtime.state.value.hasUnassociatedTurn)
    }

    @Test
    fun messageRejectsWhileBusyAndKeepsTurnAccountingExact() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        assertFalse(
            runtime.sendMessage("", List(5) { GatewayOutgoingImage("image/png", "AQID") }, "session-a", null)
        )
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        transport.failNextSend = true
        assertFalse(runtime.sendMessage("failed", emptyList(), "session-a", null))
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)

        assertTrue(runtime.sendMessage("one", emptyList(), "session-a", null))
        assertFalse(runtime.sendMessage("two", emptyList(), "session-a", null))
        assertFalse(runtime.sendMessage("three", emptyList(), "session-a", null))
        assertEquals(1, transport.sentTypes.count { it == "message" })
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        transport.receive(
            """{"kind":"error","code":"prompt-rejected","requestType":"message","sessionId":"session-a"}"""
        )
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "error" })
    }

    @Test
    fun duplicateSessionsRequestsCoalesceWithoutReconnectLoop() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()

        // hello 已自动补发 sessions；重复请求按 COALESCE_LATEST 排队，不重复发送。
        runtime.requestSessions()
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestQueued && it.requestType == "sessions" })
        assertEquals(1, transport.sentTypes.count { it == "sessions" })

        runtime.requestSessions()
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any {
            it.requestType == "sessions" && it.reason == "request-coalesced"
        })
        assertEquals(1, transport.sentTypes.count { it == "sessions" })

        // 首个 sessions 帧完成后，排队的重复请求发出并正常完成。
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "sessions" })
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any {
            it.reason == "connection-recycled"
        })
    }

    @Test
    fun alternateUnauthorizedCannotEraseTokenFromSuccessfulPairing() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials()
        val runtime = newRuntime(transport, credentials = credentials)
        runCurrent()
        runtime.setEndpointCandidates(listOf(TAIL_ENDPOINT, LAN_ENDPOINT))
        runtime.pair(encodeBase64Url(
            """{"version":1,"publicUrl":"$TAIL_ENDPOINT","pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
        ))
        transport.opened()
        transport.receive(GatewayProtocolFixtures.PAIRED)
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals("long-lived-secret", credentials.token)

        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true,
        ))
        runCurrent()
        assertEquals(LAN_ENDPOINT, transport.connectionSpecs.last().endpoint)
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            httpStatus = 401, recoverable = false,
        ))
        runCurrent()

        assertEquals("long-lived-secret", credentials.token)
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        assertEquals("long-lived-secret", transport.connectionSpecs.last().bearerToken)
        assertNull(transport.connectionSpecs.last().pairingCode)
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun alternateUnauthorizedWaitsForBackoffWithoutReportingInvalidPrimaryCredentials() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials().apply { token = "synthetic-token" }
        val clock = object : GatewayClock {
            override fun nowEpochMilliseconds() = 1_786_937_355_000L
            override suspend fun delay(milliseconds: Long) = kotlinx.coroutines.delay(milliseconds)
        }
        val runtime = newRuntime(transport, credentials = credentials, clock = clock)
        runCurrent()
        runtime.setEndpointCandidates(listOf(TAIL_ENDPOINT, LAN_ENDPOINT))
        runtime.connect(TAIL_ENDPOINT)
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true,
        ))
        runCurrent()
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            httpStatus = 401, recoverable = false,
        ))
        runCurrent()
        assertEquals("synthetic-token", credentials.token)
        assertEquals("transport-failed", runtime.state.value.lastError)
        assertEquals(2, transport.connectionSpecs.size)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        assertEquals("synthetic-token", transport.connectionSpecs.last().bearerToken)
    }

    @Test
    fun primaryUnauthorizedAfterAlternateRetryStillClearsTokenAndStops() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials().apply { token = "synthetic-token" }
        val runtime = newRuntime(transport, credentials = credentials)
        runCurrent()
        runtime.setEndpointCandidates(listOf(TAIL_ENDPOINT, LAN_ENDPOINT))
        runtime.connect(TAIL_ENDPOINT)
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true,
        ))
        runCurrent()
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            httpStatus = 401, recoverable = false,
        ))
        runCurrent()
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            httpStatus = 401, recoverable = false,
        ))
        runCurrent()
        assertNull(credentials.token)
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("authentication-required", runtime.state.value.lastError)
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size)
    }

    @Test
    fun explicitRevocationOnAlternateStillStopsAutomaticReconnect() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        runCurrent()
        runtime.setEndpointCandidates(listOf(TAIL_ENDPOINT, LAN_ENDPOINT))
        runtime.connect(TAIL_ENDPOINT)
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            reason = "websocket-failure", recoverable = true,
        ))
        runCurrent()
        transport.fail(GatewayTransportState.Failed(
            generation = transport.connectionSpecs.last().generation,
            closeCode = 4003, recoverable = false,
        ))
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
    }

    @Test
    fun authenticationFailureBlocksAutomaticReconnectUntilNewIntent() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network)
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        val failedGeneration = transport.connectionSpecs.last().generation
        transport.fail(
            GatewayTransportState.Failed(
                generation = failedGeneration,
                httpStatus = 401,
                recoverable = false
            )
        )
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        runtime.connect("wss://gateway.example/ws/mobile")
        assertEquals(2, transport.connectionSpecs.size)
    }

    @Test
    fun timeoutRecyclesConnectionRejectsStaleGenerationAndReplaysSessions() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        val oldGeneration = transport.connectionSpecs.last().generation

        // state 请求没有对应应答帧，超时后回收连接并重开。
        assertTrue(runtime.requestState("session-a"))
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "state" })
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestTimedOut && it.requestType == "state" &&
                it.targetSessionId == "session-a"
        })
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(1, transport.closeCount)
        transport.receiveAt(oldGeneration, """{"kind":"state","sessionId":"session-a"}""")
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.reason == "stale-frame" })

        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "sessions" })
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun stalePongCannotCompleteAnotherMeasurement() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.ping("new")))
        transport.receive("""{"kind":"pong","pingId":"old"}""")
        runCurrent()
        assertFalse(runtime.sendRequest(GatewayRequests.ping("next")))
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "pong" })
        transport.receive("""{"kind":"pong","pingId":"new"}""")
        runCurrent()
        assertEquals("new", events.filterIsInstance<GatewayRuntimeEvent.Frame>().last { it.frame.kind == "pong" }.frame.pingId)
        assertTrue(runtime.sendRequest(GatewayRequests.ping("next")))
    }

    @Test
    fun pongCompletesPingLaneInsteadOfTimingOut() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.ping()))
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        // lane 已完成：可立即再 ping，不被 request-busy 拒绝。
        assertTrue(runtime.sendRequest(GatewayRequests.ping()))
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.reason == "request-busy" })
        transport.receive("""{"kind":"pong"}""")
        runCurrent()
        // 两个 lane 都正常结束：超时后不触发整连接回收。
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun pairDoesNotDuplicateExistingEndpointCandidate() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val runtime = newRuntime(transport, preferences = preferences)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        val home = "wss://home.example/ws/mobile"
        val tail = "wss://tail.example/ws/mobile"
        runtime.setEndpointCandidates(listOf(home, tail))
        // 重扫已有入口：显式配对目标置首，不重复，也不删除其他入口。
        runtime.pair(
            encodeBase64Url(
                """{"version":1,"publicUrl":"$tail",""" +
                    """"pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
            )
        )
        runCurrent()
        assertEquals(listOf(tail, home), preferences.load().endpointCandidates)
    }

    @Test
    fun timedOutAbortRecyclesConnection_andLateOrUnsolicitedAckCannotEndNewTurn() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendMessage("old", emptyList(), "session-a"))
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        assertTrue(runtime.abort("session-a"))
        val oldGeneration = transport.connectionSpecs.last().generation
        val closeBefore = transport.closeCount
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestTimedOut && it.requestType == "abort" })
        assertTrue(transport.closeCount > closeBefore)
        assertTrue(transport.connectionSpecs.last().generation != oldGeneration)
        val late = """{"kind":"aborted","sessionId":"session-a","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["old draft"]}"""
        transport.receiveAt(oldGeneration, late)
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "aborted" })
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendMessage("new", emptyList(), "session-a"))
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        // Even an unsolicited ACK labelled with the current generation has no active abort lane.
        transport.receive(late)
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "aborted" })
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(runtime.abort("session-a"))
        transport.receive(late)
        runCurrent()
        assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "aborted" })
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
    }

    @Test
    fun dequeueKeepsRunningTurnUntilSettled_andRejectsOldOperationIds() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        for (session in listOf("session-a", "session-a", "session-a", "session-b")) {
            assertTrue(runtime.sendMessage("queued", emptyList(), session, mode = "followUp"))
            assertFalse(runtime.sendRequest(GatewayRequests.dequeue(session, "before-sent")))
            transport.receive("""{"kind":"sent","sessionId":"$session"}""")
            runCurrent()
        }
        val ack = """{"kind":"dequeued","sessionId":"session-a","queueRequestId":"q-1","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["s","f"]}"""
        assertTrue(runtime.sendRequest(GatewayRequests.dequeue("session-a", "q-1")))
        assertFalse(runtime.sendMessage("new", emptyList(), "session-a"))
        transport.receive(ack.replace(",\"queued\":[\"s\",\"f\"]", "")) // 同ID但缺取回载荷，不得结束lane或发布。
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "dequeued" })
        transport.receive(ack.replace("q-1", "wrong"))
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "dequeued" })
        transport.receive(ack)
        runCurrent()
        assertEquals(setOf("session-a", "session-b"), runtime.state.value.activeTurnSessionIds)
        assertTrue(runtime.sendRequest(GatewayRequests.dequeue("session-a", "q-2")))
        transport.receive(ack) // 旧操作ID即使撞上新lane也不能取回/结束新请求。
        runCurrent()
        assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == "dequeued" })
        transport.receive(ack.replace("q-1", "q-2").replace("[\"s\",\"f\"]", "[]"))
        runCurrent()
        transport.receive("""{"kind":"event","sessionId":"session-a","seq":1,"time":1,"event":{"type":"agent_settled"}}""")
        runCurrent()
        assertEquals(setOf("session-b"), runtime.state.value.activeTurnSessionIds)
        transport.receive(ack)
        runCurrent()
        assertEquals(setOf("session-b"), runtime.state.value.activeTurnSessionIds)
    }

    @Test
    fun dequeueRejectAndTimeoutReleaseOnlyMatchingOperation_andNeverPublishLateDrafts() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendRequest(GatewayRequests.dequeue("session-a", "q-1")))
        transport.receive("""{"kind":"error","requestType":"dequeue","sessionId":"session-a","queueRequestId":"q-1","code":"internal"}""")
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.requestType == "dequeue" && it.correlationId == "q-1" })
        assertTrue(runtime.sendRequest(GatewayRequests.dequeue("session-a", "q-2")))
        val generation = transport.connectionSpecs.last().generation
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestTimedOut && it.requestType == "dequeue" && it.correlationId == "q-2" })
        transport.receiveAt(generation, """{"kind":"dequeued","sessionId":"session-a","queueRequestId":"q-2","queued":["lost ack draft"]}""")
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.Frame>().none { it.frame.kind == "dequeued" })
    }

    @Test
    fun acknowledgedQueueRestoringAbortReleasesAllTargetTurnsButKeepsOtherSession() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        for (session in listOf("session-a", "session-a", "session-b")) {
            assertTrue(runtime.sendMessage("queued", emptyList(), session, null, mode = "followUp"))
            transport.receive("""{"kind":"sent","sessionId":"$session"}""")
            runCurrent()
        }
        assertTrue(runtime.abort("session-a"))
        assertTrue(transport.sentPayloads.last().contains("\"restoreQueued\":true"))
        transport.receive("""{"kind":"aborted","sessionId":"wrong","isStreaming":false,"isCompacting":false,"queued":[]}""")
        runCurrent()
        assertEquals(setOf("session-a", "session-b"), runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"aborted","sessionId":"session-a","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["queued"]}""")
        runCurrent()
        assertEquals(setOf("session-b"), runtime.state.value.activeTurnSessionIds)
        assertTrue(runtime.abort("session-b"))
        transport.receive("""{"kind":"aborted","sessionId":"session-b","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":[]}""")
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun recycleOnUnrelatedTimeoutReleasesMessageTurns() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.requestState("session-b"))
        assertTrue(runtime.sendMessage("hi", emptyList(), "session-a"))
        runCurrent()
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        // state 先超时 → 整连接回收：没等到 agent_settled 的 message turn 必须释放，
        // 否则会话永久“运行中”、后台不断连。
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(events.any {
            it is GatewayRuntimeEvent.RequestTimedOut && it.requestType == "state"
        })
        assertEquals(emptySet<String>(), runtime.state.value.activeTurnSessionIds)
    }

    @Test
    fun backgroundAssociatedTurnStaysAliveUntilAgentSettledSuspends() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)

        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertTrue(runtime.state.value.shouldKeepAliveInBackground)

        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":4,"time":1.0,"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertEquals(emptySet(), runtime.state.value.activeTurnSessionIds)
        assertTrue(transport.closeCount >= 1)

        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)
    }

    @Test
    fun activeTurnSurvivesBackgroundNetworkRecoveryUntilAgentSettled() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network, recoveryWindowMilliseconds = 5_000)
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        runtime.applicationDidEnterBackground()
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        assertEquals(GatewayConnectionState.WAITING_FOR_NETWORK, runtime.state.value.connection)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)

        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":4,"time":1.0,"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
    }

    @Test
    fun activeTurnRecoveryWindowExpiresFailClosed() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, recoveryWindowMilliseconds = 1_000)
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        val expiredGeneration = transport.connectionSpecs.last().generation
        val closesBeforeDeadline = transport.closeCount
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("recovery-timeout", runtime.state.value.lastError)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(transport.closeCount > closesBeforeDeadline)
        transport.receiveAt(expiredGeneration, """{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
    }

    @Test
    fun helloRecoveryDeduplicatesSubscribeAndSessionsWithoutReconnectLoop() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        assertTrue(runtime.subscribe("session-a"))
        assertTrue(runtime.subscribe("session-b"))
        transport.receive("""{"kind":"subscribed"}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(2, transport.sentTypes.count { it == "subscribe" })
        transport.receive("""{"kind":"subscribed"}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)

        // 重复的 sessions 请求排队，首个应答完成后接发并完成。
        assertTrue(runtime.requestSessions())
        assertTrue(runtime.requestSessions())
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        // hello replay 已发过 1 次 + 本次请求 1 次 + 排队项接发 1 次。
        assertEquals(3, transport.sentTypes.count { it == "sessions" })
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
    }

    @Test
    fun storedConnectIsIdempotentWhileConnectingAndConnected() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val credentials = FakeCredentials().apply {
            token = "stored-token"
        }
        val runtime = GatewayRuntime(
            transport,
            preferences,
            credentials,
            FakeAttachmentCache(),
            FakeNetworkMonitor(),
            FakeClock(0),
            backgroundScope
        )
        runCurrent()
        assertTrue(runtime.connectStoredIfPaired())
        assertTrue(runtime.connectStoredIfPaired())
        assertEquals(1, transport.connectionSpecs.size)
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("active turn", emptyList(), "session-a", null))
        assertTrue(runtime.connectStoredIfPaired())
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
    }

    @Test
    fun everyHistoryFailureCarriesSessionAndWireCode() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        assertFalse(runtime.requestHistory("offline-session"))
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "history" && it.targetSessionId == "offline-session"
        })

        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.requestHistory("session-a"))
        transport.receive(
            """{"kind":"error","code":"session-not-found","requestType":"history","sessionId":"session-a"}"""
        )
        runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "history" && it.targetSessionId == "session-a" && it.reason == "session-not-found"
        })
        assertTrue(runtime.requestHistory("session-a"))
    }

    @Test
    fun staleGenerationCannotCompleteNewConnectionRequest() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        val oldGeneration = transport.connectionSpecs.last().generation
        runtime.disconnect()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        runtime.requestHistory("session-new")
        transport.receiveAt(
            oldGeneration,
            """{"kind":"history","messages":[],"entryIds":[],"hasMore":false}"""
        )
        runCurrent()
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.reason == "stale-frame" })
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "history" })
    }

    private fun helloWithHistoryTurns() =
        """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["sessions","history","history-turns-v1"]}"""

    private fun historyPayloads(transport: FakeTransport) = transport.sentPayloads
        .map { wireJson.parseToJsonElement(it).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "history" }

    private fun historyTooLarge(session: String) =
        """{"kind":"error","code":"history-turn-too-large","requestType":"history","sessionId":"$session"}"""

    private fun emptyHistory(session: String) =
        """{"kind":"history","sessionId":"$session","messages":[],"entryIds":[],"hasMore":false}"""

    @Test
    fun oversizedHistoryRetriesOriginalCursorAndFlags_thenKeepsEntryPagingForOlderAndRefresh() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large", "turn-start", limit = 37, resetLeaf = true)
        val original = historyPayloads(transport).single()
        assertEquals("10", original["turnLimit"]?.jsonPrimitive?.content)
        transport.receive(historyTooLarge("large"))
        runCurrent()
        assertEquals(2, historyPayloads(transport).size)
        assertEquals(original - "turnLimit", historyPayloads(transport).last())
        assertFalse(events.any { it is GatewayRuntimeEvent.RequestRejected && it.requestType == "history" })
        assertFalse(events.any { it is GatewayRuntimeEvent.RequestCancelled && it.requestType == "history" })
        transport.receive("""{"kind":"history","sessionId":"large","messages":[],"entryIds":[],"hasMore":true,"oldestEntryId":"mid-turn"}""")
        runCurrent()
        assertEquals("large", events.filterIsInstance<GatewayRuntimeEvent.Frame>().last().correlatedSessionId)
        runtime.requestHistory("large", "mid-turn", limit = 21)
        assertNull(historyPayloads(transport).last()["turnLimit"])
        assertEquals("mid-turn", historyPayloads(transport).last()["beforeEntryId"]?.jsonPrimitive?.content)
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.requestHistory("large", resetLeaf = true)
        assertNull(historyPayloads(transport).last()["turnLimit"])
        assertEquals("true", historyPayloads(transport).last()["resetLeaf"]?.jsonPrimitive?.content)
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.requestHistory("normal")
        assertEquals("10", historyPayloads(transport).last()["turnLimit"]?.jsonPrimitive?.content)
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
    }

    @Test
    fun oversizedHistoryCannotLoop_andOtherEntryFailureStillTerminates() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        transport.receive(historyTooLarge("large"));runCurrent()
        transport.receive(historyTooLarge("large"));runCurrent()
        assertEquals(2, historyPayloads(transport).size)
        assertEquals("history-turn-too-large", events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>()
            .single { it.requestType == "history" }.reason)
        assertTrue(runtime.requestHistory("large"))
        transport.receive("""{"kind":"error","code":"session-not-found","requestType":"history","sessionId":"large"}""")
        runCurrent()
        assertEquals(3, historyPayloads(transport).size)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.reason == "session-not-found" })
    }

    @Test
    fun wrongSessionStaleGenerationAndUnassociatedErrorsNeverTriggerHistoryFallback() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        val oldGeneration = transport.connectionSpecs.last().generation
        runtime.disconnect()
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        transport.receiveAt(oldGeneration, historyTooLarge("large"))
        transport.receive(historyTooLarge("wrong"));runCurrent()
        assertEquals(1, historyPayloads(transport).size)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.reason == "stale-frame" })
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any { it.reason == "session-mismatch" })
        transport.receive(emptyHistory("large"));runCurrent()
        transport.receive(historyTooLarge("large"));runCurrent()
        assertEquals(1, historyPayloads(transport).size)
        runtime.requestHistory("large")
        assertEquals("10", historyPayloads(transport).last()["turnLimit"]?.jsonPrimitive?.content)
    }

    @Test
    fun missingSessionGenericErrorAndLegacyHistoryDoNotTriggerFallback() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        transport.receive("""{"kind":"error","code":"history-turn-too-large","requestType":"history"}""")
        runCurrent()
        assertEquals(1, historyPayloads(transport).size)
        runtime.requestHistory("large")
        assertEquals("10", historyPayloads(transport).last()["turnLimit"]?.jsonPrimitive?.content)
        transport.receive("""{"kind":"error","code":"history-turn-too-large","sessionId":"large"}""")
        runCurrent()
        assertEquals(2, historyPayloads(transport).size)
        runtime.disconnect()
        connectHello(transport, runtime)
        runtime.requestHistory("large")
        transport.receive(historyTooLarge("large"));runCurrent()
        assertEquals(3, historyPayloads(transport).size)
        assertNull(historyPayloads(transport).last()["turnLimit"])
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "history" && it.reason == "history-turn-too-large"
        })
    }

    @Test
    fun sameSessionQueuedHistoryAlsoUsesEntryPaging_andDifferentSessionRunsImmediately() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        runtime.requestHistory("large", "older-turn", limit = 29)
        transport.receive(historyTooLarge("large"));runCurrent()
        assertEquals(2, historyPayloads(transport).size)
        transport.receive(emptyHistory("large"));runCurrent()
        assertEquals(3, historyPayloads(transport).size)
        assertNull(historyPayloads(transport).last()["turnLimit"])
        assertEquals("older-turn", historyPayloads(transport).last()["beforeEntryId"]?.jsonPrimitive?.content)
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.requestHistory("another-large")
        // 精确覆盖“同会话旧页排队后，用户又切到其他会话”的顺序。
        runtime.requestHistory("another-large", "superseded-cursor")
        runtime.requestHistory("selected-now")
        transport.receive(historyTooLarge("another-large"));runCurrent()
        assertEquals(5, historyPayloads(transport).size)
        assertEquals("selected-now", historyPayloads(transport).last()["sessionId"]?.jsonPrimitive?.content)
        assertEquals("10", historyPayloads(transport).last()["turnLimit"]?.jsonPrimitive?.content)
        assertFalse(historyPayloads(transport).any {
            it["beforeEntryId"]?.jsonPrimitive?.content == "superseded-cursor"
        })
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestCancelled>().any {
            it.targetSessionId == "another-large" && it.reason == "request-coalesced"
        })
        transport.receive(emptyHistory("selected-now"));runCurrent()
        runtime.requestHistory("another-large")
        assertNull(historyPayloads(transport).last()["turnLimit"])
    }

    @Test
    fun historyFallbackSurvivesReconnectAndAddressChange_butNewPairingResetsIt() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials().apply { token = "synthetic-gateway-token" }
        val runtime = newRuntime(transport, credentials = credentials)
        backgroundScope.launch { runtime.events.collect { } }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        transport.receive(historyTooLarge("large"));runCurrent()
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.disconnect()
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large", "mid-turn")
        assertNull(historyPayloads(transport).last()["turnLimit"])
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.connect(TAIL_ENDPOINT);runCurrent()
        transport.opened();transport.receive(helloWithHistoryTurns());runCurrent()
        runtime.requestHistory("large")
        assertNull(historyPayloads(transport).last()["turnLimit"])
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.pair(encodeBase64Url("""{"version":1,"publicUrl":"wss://new-gateway.example/ws/mobile","pairingCode":"synthetic","expiresAt":1786937455000}"""))
        runCurrent()
        transport.opened()
        transport.receive("""{"kind":"paired","token":"synthetic-new-token"}""")
        transport.receive(helloWithHistoryTurns());runCurrent()
        runtime.requestHistory("large")
        assertEquals("10", historyPayloads(transport).last()["turnLimit"]?.jsonPrimitive?.content)
        transport.receive(historyTooLarge("large"));runCurrent()
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.disconnect()
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        assertNull(historyPayloads(transport).last()["turnLimit"], "新配对后的首次重连也必须保留已降级模式")
    }

    @Test
    fun changedCredentialTokenResetsHistoryFallbackForSameSessionId() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials().apply { token = "synthetic-one" }
        val runtime = newRuntime(transport, credentials = credentials)
        backgroundScope.launch { runtime.events.collect { } }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        transport.receive(historyTooLarge("large"));runCurrent()
        transport.receive(emptyHistory("large"));runCurrent()
        runtime.disconnect()
        credentials.token = "synthetic-other"
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        assertEquals("10", historyPayloads(transport).last()["turnLimit"]?.jsonPrimitive?.content)
    }

    @Test
    fun historyFallbackSendFailureTerminates_andQueuedRefreshSurvivesReconnect() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        runtime.requestHistory("large")
        runtime.requestHistory("large", "later-cursor")
        transport.failNextSend = true
        transport.receive(historyTooLarge("large"));runCurrent()
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.requestType == "history" && it.targetSessionId == "large" && it.reason == "send-failed"
        })
        assertEquals(2, transport.connectionSpecs.size)
        transport.opened();transport.receive(helloWithHistoryTurns());runCurrent()
        assertEquals(2, historyPayloads(transport).size)
        assertNull(historyPayloads(transport).last()["turnLimit"])
        assertEquals("later-cursor", historyPayloads(transport).last()["beforeEntryId"]?.jsonPrimitive?.content)
        transport.receive(emptyHistory("large"));runCurrent()
        assertTrue(runtime.requestHistory("large"))
    }

    @Test
    fun historyFallbackTimeoutStillUsesExistingFailureRecovery() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 1_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithHistoryTurns())
        transport.receive("""{"kind":"sessions","items":[]}""");runCurrent()
        runtime.requestHistory("large")
        transport.receive(historyTooLarge("large"));runCurrent()
        advanceTimeBy(1_001);runCurrent()
        assertEquals(2, historyPayloads(transport).size)
        assertTrue(events.filterIsInstance<GatewayRuntimeEvent.RequestTimedOut>().any {
            it.requestType == "history" && it.targetSessionId == "large"
        })
        assertEquals(2, transport.connectionSpecs.size)
    }

    @Test
    fun orderedFailureInvalidatesGenerationBeforeLaterBufferedHello() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        val generation = transport.connectionSpecs.single().generation
        transport.failThenReceive(
            GatewayTransportState.Failed(generation, reason = "incoming-overflow", recoverable = true),
            generation,
            """{"kind":"hello","authenticated":true}"""
        )
        runCurrent()
        assertTrue(runtime.state.value.connection != GatewayConnectionState.CONNECTED)
        assertTrue(events.any { it is GatewayRuntimeEvent.RequestRejected && it.reason == "stale-frame" })
    }

    @Test
    fun backgroundLastTurnEndClosesImmediatelyAndStoredConnectKeepsWaitingDeadline() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(
            transport,
            network = network,
            recoveryWindowMilliseconds = 1_000
        )
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("active", emptyList(), "session-a", null))
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        val closeBeforeEnd = transport.closeCount
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":1,"time":1.0,"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
        assertTrue(transport.closeCount > closeBeforeEnd)

        runtime.applicationDidBecomeActive()
        runCurrent()
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("recover", emptyList(), "session-a", null))
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        assertEquals(GatewayConnectionState.WAITING_FOR_NETWORK, runtime.state.value.connection)
        val specCount = transport.connectionSpecs.size
        assertTrue(runtime.connectStoredIfPaired())
        assertEquals(specCount, transport.connectionSpecs.size)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("recovery-timeout", runtime.state.value.lastError)
    }

    @Test
    fun credentialFailureFromConnectedClosesOldTransportBeforeFailedState() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials()
        val runtime = newRuntime(transport, credentials = credentials)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        val closeBefore = transport.closeCount
        credentials.failDeviceRead = true
        runtime.connect("wss://other.example/ws/mobile")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("credential-access-failed", runtime.state.value.lastError)
        assertTrue(transport.closeCount > closeBefore)
    }

    @Test
    fun unreachableEndpointFailsOverToNextCandidateWithoutBackoff() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val runtime = newRuntime(transport, preferences = preferences)
        runCurrent()
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)

        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        assertEquals(listOf(LAN_ENDPOINT, TAIL_ENDPOINT), transport.connectionSpecs.map { it.endpoint })
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)
        assertEquals(0, runtime.state.value.reconnectAttempt)

        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(TAIL_ENDPOINT, runtime.state.value.endpoint)
        // 连上哪个都不重排：否则出门走 tailnet 后就再也不会重探局域网地址。
        assertEquals(listOf(LAN_ENDPOINT, TAIL_ENDPOINT), preferences.load().endpointCandidates)
    }

    @Test
    fun handshakeWatchdogDropsBlackHoledEndpointAndGivesFinalEndpointWiderWindow() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(
            transport,
            endpointProbeTimeoutMilliseconds = 2_500,
            finalHandshakeTimeoutMilliseconds = 10_000
        )
        runCurrent()
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)

        // 局域网地址在门外是静默黑洞：不会回失败帧，只能靠看门狗提前掐掉。
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        assertTrue(transport.closeCount >= 1)

        // 末位候选拿 10s 预算，不能把冷启动较慢的 tailnet 误判为不可达。
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)

        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun pairingPrioritizesPayloadEndpointAndPreservesOtherCandidates() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val runtime = newRuntime(transport, preferences = preferences)
        runCurrent()
        runtime.connect(LAN_ENDPOINT)
        runCurrent()

        runtime.pair(
            encodeBase64Url(
                """{"version":1,"publicUrl":"$TAIL_ENDPOINT",""" +
                    """"pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
            )
        )
        runCurrent()
        val spec = transport.connectionSpecs.last()
        assertEquals(TAIL_ENDPOINT, spec.endpoint)
        assertEquals(GatewayProtocolFixtures.PAIRING_CODE, spec.pairingCode)
        assertEquals(TAIL_ENDPOINT, preferences.load().endpoint)
        assertEquals(listOf(TAIL_ENDPOINT, LAN_ENDPOINT), preferences.load().endpointCandidates)
    }

    @Test
    fun pairingUnreachableNewAddressDoesNotProbeOldGatewayWithItsCode() = runTest {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        val runtime = newRuntime(transport, preferences = preferences)
        connectHello(transport, runtime)
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        val before = transport.connectionSpecs.size
        runtime.pair(encodeBase64Url(
            """{"version":1,"publicUrl":"$TAIL_ENDPOINT","pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
        ))
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(transport.connectionSpecs.drop(before).isNotEmpty())
        assertTrue(transport.connectionSpecs.drop(before).all { it.endpoint == TAIL_ENDPOINT })
        assertEquals(listOf(TAIL_ENDPOINT, LAN_ENDPOINT), preferences.load().endpointCandidates)
    }

    @Test
    fun pairingClearsOldSubscriptionAndCapabilitiesEvenWhileOffline() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithAttachment())
        runtime.subscribe("old-session")
        runCurrent()
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        runCurrent()
        val priorRevision = runtime.state.value.gatewayRevision
        runtime.pair(encodeBase64Url(
            """{"version":1,"publicUrl":"$TAIL_ENDPOINT","pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
        ))
        assertEquals(priorRevision + 1, runtime.state.value.gatewayRevision)
        assertTrue(runtime.state.value.capabilities.isEmpty())
        assertEquals(GatewayConnectionState.WAITING_FOR_NETWORK, runtime.state.value.connection)
        val sentBeforeNewGateway = transport.sentPayloads.size
        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runCurrent()
        transport.opened()
        transport.receive("""{"kind":"paired","token":"new-gateway-token"}""")
        transport.receive(helloWithAttachment())
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertFalse(transport.sentPayloads.drop(sentBeforeNewGateway).any { "old-session" in it })
        val newFrames = events.filterIsInstance<GatewayRuntimeEvent.Frame>().filter { it.frame.kind == "paired" }
        assertEquals(runtime.state.value.gatewayRevision, newFrames.single().gatewayRevision)
    }

    @Test
    fun queuedOldGatewayFramesRemainDistinguishableAfterRePairing() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime)
        transport.receive("""{"kind":"sessions","items":[{"id":"old","name":"old","running":false}]}""")
        runCurrent() // Intentionally leave runtime.events uncollected.
        runtime.pair(encodeBase64Url(
            """{"version":1,"publicUrl":"$TAIL_ENDPOINT","pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
        ))
        runCurrent()
        transport.opened()
        transport.receive("""{"kind":"paired","token":"new-gateway-token"}""")
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[{"id":"new","name":"new","running":false}]}""")
        runCurrent()
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        val sessions = events.filterIsInstance<GatewayRuntimeEvent.Frame>().filter { it.frame.kind == "sessions" }
        assertEquals(2, sessions.size)
        assertTrue(sessions.first().gatewayRevision < runtime.state.value.gatewayRevision)
        assertEquals(runtime.state.value.gatewayRevision, sessions.last().gatewayRevision)
        assertTrue(sessions.last().rawJson.contains("new"))
    }

    @Test
    fun tokenFromOneEndpointAuthenticatesAnotherWithoutRepairing() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials()
        val runtime = newRuntime(transport, credentials = credentials)
        runCurrent()
        // 全新安装（没有自配地址）时，配对落在 payload.publicUrl 上。
        runtime.pair(
            encodeBase64Url(
                """{"version":1,"publicUrl":"$TAIL_ENDPOINT",""" +
                    """"pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
            )
        )
        runCurrent()
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        transport.opened()
        transport.receive(GatewayProtocolFixtures.PAIRED)
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals("long-lived-secret", credentials.token)

        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        val retry = transport.connectionSpecs.last()
        assertEquals(LAN_ENDPOINT, retry.endpoint)
        assertEquals("long-lived-secret", retry.bearerToken)
        assertNull(retry.pairingCode)
    }

    @Test
    fun rejectedPairingCodeFallsBackToStoredTokenInsteadOfBlockingConnection() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials().apply { token = "stored-token" }
        val runtime = newRuntime(transport, credentials = credentials)
        runCurrent()
        runtime.pair(
            encodeBase64Url(
                """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile",""" +
                    """"pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
            )
        )
        runCurrent()
        assertEquals(GatewayProtocolFixtures.PAIRING_CODE, transport.connectionSpecs.last().pairingCode)

        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                httpStatus = 401,
                recoverable = false
            )
        )
        runCurrent()
        assertEquals("stored-token", credentials.token)
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)
        // 本地还有可用凭据 → 直接自愈改走 bearer，不该停在错误状态。
        assertNull(runtime.state.value.lastError)
        val retry = transport.connectionSpecs.last()
        assertEquals(2, transport.connectionSpecs.size)
        assertNull(retry.pairingCode)
        assertEquals("stored-token", retry.bearerToken)

        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
    }

    @Test
    fun rejectedPairingCodeWithoutStoredTokenReportsInvalidCodeAndStopsRetrying() = runTest {
        val transport = FakeTransport()
        val network = FakeNetworkMonitor()
        val runtime = newRuntime(transport, network = network)
        runCurrent()
        runtime.pair(
            encodeBase64Url(
                """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile",""" +
                    """"pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1786937455000}"""
            )
        )
        runCurrent()
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                httpStatus = 401,
                recoverable = false
            )
        )
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("pairing-code-invalid", runtime.state.value.lastError)
        network.mutableState.value = GatewayNetworkState.UNAVAILABLE
        network.mutableState.value = GatewayNetworkState.AVAILABLE
        runtime.applicationDidBecomeActive()
        runCurrent()
        assertEquals(1, transport.connectionSpecs.size)
    }

    @Test
    fun activeTurnSurvivesBackupEndpointFailoverUntilAgentSettled() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, recoveryWindowMilliseconds = 5_000)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        runCurrent()
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)

        // 出门场景：LAN 地址断连时 AI 回复仍在后台跑，切备用入口（Tailscale）属于
        // 同一轮连接恢复，不是新的连接意图：turn 必须保留，FGS 条件不能塌。
        runtime.applicationDidEnterBackground()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        assertTrue(runtime.state.value.shouldKeepAliveInBackground)

        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)

        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":9,"time":2.0,"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        // 最后一轮落定 + 后台闲置 → 按既有语义立即挂起。
        assertEquals(GatewayConnectionState.SUSPENDED, runtime.state.value.connection)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertEquals(emptySet<String>(), runtime.state.value.activeTurnSessionIds)
    }

    @Test
    fun recoveryWindowBoundsFailoverWalkWithActiveTurn() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, recoveryWindowMilliseconds = 5_000)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()

        // 首地址 recoverable failure 切备用时就要武装有界恢复窗口：
        // 备用入口握手黑洞（不给 hello）不能绕过窗口无限等。
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        val blackHoleGeneration = transport.connectionSpecs.last().generation
        val closesBeforeDeadline = transport.closeCount

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
        assertEquals("recovery-timeout", runtime.state.value.lastError)
        assertFalse(runtime.state.value.shouldKeepAliveInBackground)
        assertTrue(transport.closeCount > closesBeforeDeadline)

        // 迟到的 hello 不复活已被恢复窗口终止的连接。
        transport.receiveAt(blackHoleGeneration, """{"kind":"hello","authenticated":true}""")
        runCurrent()
        assertEquals(GatewayConnectionState.FAILED, runtime.state.value.connection)
    }

    @Test
    fun initialNoTurnProbingKeepsBackoffRecoveryAndNeverArmsRecoveryDeadline() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, recoveryWindowMilliseconds = 1_000)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        assertEquals(2, transport.connectionSpecs.size)
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        // 整轮走完：无活动回合时不武装恢复窗口，走退避重连（FakeClock 退避即时返回）。
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(LAN_ENDPOINT, transport.connectionSpecs.last().endpoint)
        assertEquals(GatewayConnectionState.CONNECTING, runtime.state.value.connection)

        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        // 无 turn 连接不受恢复窗口约束：过窗后仍连着，不触发 recovery-timeout。
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertNull(runtime.state.value.lastError)
    }

    @Test
    fun liveEventFrameTopLevelEntryIdFlowsThroughNormalizationAndDefaultsToNull() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val frames = mutableListOf<GatewayRuntimeEvent.Frame>()
        backgroundScope.launch { runtime.events.collect { if (it is GatewayRuntimeEvent.Frame) frames += it } }
        runCurrent()
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()

        // 带 entryId 的 message_end 帧：帧顶层身份（PROTOCOL §4.5）随归一透传到
        // SessionEvent.entryId，供下游按（sessionId,entryId,type,callId）身份对消。
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":7,"time":9.0,"entryId":"e-live-1",""" +
                """"event":{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"继续"}]}}}"""
        )
        runCurrent()
        val withEntry = frames.last().normalizedEvents
        assertEquals(1, withEntry.size)
        assertEquals("e-live-1", withEntry.single().entryId)

        // 不带 entryId 的 event 帧：归一事件身份未知（null），不猜值。
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":8,"time":10.0,""" +
                """"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        val withoutEntry = frames.last().normalizedEvents
        assertTrue(withoutEntry.isEmpty() || withoutEntry.all { it.entryId == null })
    }

    @Test
    fun newConnectionIntentStillClearsTurnsLeftFromFailover() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        backgroundScope.launch { runtime.events.collect { } }
        runCurrent()
        runtime.setEndpointCandidates(listOf(LAN_ENDPOINT, TAIL_ENDPOINT))
        runtime.connect(LAN_ENDPOINT)
        runCurrent()
        transport.opened()
        transport.receive(HELLO_WITHOUT_EXTRA)
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        runCurrent()
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)

        // 用户显式发起的新连接意图仍然清旧 turn（与恢复路径区分）。
        runtime.connect(TAIL_ENDPOINT)
        runCurrent()
        assertEquals(3, transport.connectionSpecs.size)
        assertEquals(TAIL_ENDPOINT, transport.connectionSpecs.last().endpoint)
        assertEquals(emptySet<String>(), runtime.state.value.activeTurnSessionIds)
        assertFalse(runtime.state.value.hasUnassociatedTurn)
    }

    private fun kotlinx.coroutines.test.TestScope.newRuntime(
        transport: FakeTransport,
        attachmentCache: FakeAttachmentCache = FakeAttachmentCache(),
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        credentials: FakeCredentials = FakeCredentials(),
        preferences: FakePreferences = FakePreferences(),
        requestTimeoutMilliseconds: Long = 30_000,
        recoveryWindowMilliseconds: Long = 60_000,
        endpointProbeTimeoutMilliseconds: Long = 2_500,
        finalHandshakeTimeoutMilliseconds: Long = 10_000,
        clock: GatewayClock = FakeClock(1_786_937_355_000)
    ): GatewayRuntime = GatewayRuntime(
        transport,
        preferences,
        credentials,
        attachmentCache,
        network,
        clock,
        backgroundScope,
        requestTimeoutMilliseconds,
        recoveryWindowMilliseconds,
        endpointProbeTimeoutMilliseconds,
        finalHandshakeTimeoutMilliseconds
    )

    // -- §4.22 attachment 按需取回 -------------------------------------------

    /** common 源集无 assertArrayEquals；字节数组比较统一走这里。 */
    private fun assertSameBytes(expected: ByteArray, actual: ByteArray?) {
        assertTrue(
            expected.contentEquals(actual),
            "expected=${expected.contentToString()} actual=${actual?.contentToString()}"
        )
    }

    private fun helloWithAttachment() =
        """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["ping","sessions","subscribe","message","abort","history","state","attachment"]}"""

    private fun attachmentPayload(sessionId: String, entryId: String, imageIndex: Int, base64: String, bytes: Int) =
        """{"kind":"attachment","sessionId":"$sessionId","entryId":"$entryId","imageIndex":$imageIndex,"mediaType":"image/png","bytes":$bytes,"dataBase64":"$base64"}"""

    /** 便捷起站：connect + opened + hello（含/不含 attachment capability）。 */
    private suspend fun kotlinx.coroutines.test.TestScope.connectHello(
        transport: FakeTransport,
        runtime: GatewayRuntime,
        hello: String = HELLO_WITHOUT_EXTRA
    ) {
        // 先让 runtime init 的 collectSafely 就绪：SharedFlow 无 replay，订阅前 emit
        // 的帧会丢失；且 network StateFlow 首值发射需在 connect 前处理完，否则
        // handleNetworkLocked 会在 desiredConnection 已置位后调度一次多余的立即重连。
        runCurrent()
        runtime.connect("wss://gateway.example/ws/mobile")
        runCurrent()
        transport.opened()
        transport.receive(hello)
        runCurrent()
    }

    @Test
    fun treeActionRepliesAndErrorsCannotConsumeAnotherEntryOrAnAbsentLane() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime,
            """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["entry-copy","label-put","tree-actions-v1"]}""")
        transport.receive("""{"kind":"entry-copy","sessionId":"s1","entryId":"e1","text":"未请求"}""")
        runCurrent()
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "entry-copy" })
        for ((kind, request) in listOf(
            "entry-copy" to GatewayRequests.entryCopy("s1", "e1"),
            "label-put" to GatewayRequests.labelPut("s1", "e1", "交付")
        )) {
            assertTrue(runtime.sendRequest(request))
            transport.receive("""{"kind":"$kind","sessionId":"s1","entryId":"wrong","text":"错误正文","label":"错误标签"}""")
            transport.receive("""{"kind":"error","requestType":"$kind","sessionId":"s1","entryId":"wrong","code":"entry-not-found"}""")
            runCurrent()
            assertFalse(runtime.sendRequest(request))
            transport.receive("""{"kind":"$kind","entryId":"e1","text":"缺会话"}""")
            runCurrent()
            assertFalse(runtime.sendRequest(request))
            transport.receive("""{"kind":"$kind","sessionId":"s1","entryId":"e1","text":"全文","label":"交付"}""")
            runCurrent()
            assertEquals(1, events.filterIsInstance<GatewayRuntimeEvent.Frame>().count { it.frame.kind == kind })
            assertTrue(runtime.sendRequest(request))
            transport.receive("""{"kind":"error","requestType":"$kind","sessionId":"s1","entryId":"e1","code":"entry-not-found"}""")
            runCurrent()
            assertTrue(runtime.sendRequest(request))
            runtime.disconnect()
            connectHello(transport, runtime,
                """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["entry-copy","label-put","tree-actions-v1"]}""")
        }
    }

    @Test
    fun compactFinishedIsValidatedAndDoesNotConsumeStateRequest() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime,
            """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["compact","state","prompt-disposition-v1"]}""")
        assertTrue(runtime.sendRequest(GatewayRequests.compact("s1")))
        transport.receive("""{"kind":"compact-started","sessionId":"s1","isStreaming":false,"isCompacting":true}""")
        runCurrent()
        assertTrue("s1" in runtime.state.value.activeTurnSessionIds)
        assertTrue(runtime.requestState("s-other"))
        transport.receive("""{"kind":"compact-finished","sessionId":"s1","isStreaming":false}""")
        runCurrent()
        assertTrue("s1" in runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"compact-finished","sessionId":"","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertTrue("s1" in runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"compact-finished","sessionId":"s1","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertFalse("s1" in runtime.state.value.activeTurnSessionIds)
        assertEquals(1, transport.sentTypes.count { it == "state" })
        // 另一个会话state仍在途；通知不把它当成回执释放。
        assertTrue(runtime.requestState("s-third"))
        assertEquals(1, transport.sentTypes.count { it == "state" })
        transport.receive("""{"kind":"state","sessionId":"s-other","isStreaming":false,"isCompacting":false}""")
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "state" })
    }

    /** CTX02：手动压缩的 wire 往返。compact 是触发帧（compact-started 受理），
     *  完成/失败靠 compaction_start/end 事件；REJECT_IF_BUSY 保证同会话不并发压缩。 */
    @Test
    fun manualCompactTriggersAndLifecycleEventsDriveActivity() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(
            transport, runtime,
            """{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["compact","state","message","prompt-disposition-v1"]}"""
        )
        assertTrue(runtime.sendRequest(GatewayRequests.compact("s1")))
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "compact" })
        // 受理回执（state 快照形状）。compact-started 仅带 isCompacting 的
        // 新网关形状同样要入活动集合；本字段缺省时不冒充活动，避免旧网关
        // 误常驻 Working。
        transport.receive("""{"kind":"compact-started","sessionId":"s1","isStreaming":false,"isCompacting":true,"disposition":"handled"}""")
        runCurrent()
        // 权威快照先入活动集合；重复/迟到事件不再重复计数。
        assertTrue("s1" in runtime.state.value.activeTurnSessionIds)
        // 压缩开始/结束事件翻转活动会话（GatewayRuntime 的 observedCompactingSessions）。
        transport.receive("""{"kind":"event","sessionId":"s1","seq":1,"time":1,"event":{"type":"compaction_start","reason":"manual"}}""")
        runCurrent()
        assertTrue("s1" in runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"event","sessionId":"s1","seq":2,"time":2,"event":{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false}}""")
        runCurrent()
        assertFalse("s1" in runtime.state.value.activeTurnSessionIds)
    }

    @Test
    fun fetchAttachmentServesCacheHitWithoutSendingRequest() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, attachmentCache = cache)
        connectHello(transport, runtime, helloWithAttachment())
        val pngBytes = byteArrayOf(1, 2, 3)
        cache.values[gatewayAttachmentCacheKey("s1", "img-1")] = pngBytes

        assertEquals(pngBytes, runtime.fetchAttachment("s1", "e1", 0, "img-1"))
        assertEquals(0, transport.sentTypes.count { it == "attachment" })
    }

    @Test
    fun fetchAttachmentNeedsCapabilityAndValidReference() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime)
        // hello 未宣告 attachment capability：不发请求。
        assertNull(runtime.fetchAttachment("s1", "e1", 0, "img-1"))
        assertEquals(0, transport.sentTypes.count { it == "attachment" })

        connectHello(transport, runtime, helloWithAttachment())
        runCurrent()
        // 缺 entryId（或负 index）不能发。
        assertNull(runtime.fetchAttachment("s1", "", 0, "img-1"))
        assertNull(runtime.fetchAttachment("s1", "e1", -1, "img-1"))
        assertEquals(0, transport.sentTypes.count { it == "attachment" })
    }

    @Test
    fun fetchAttachmentRoundTripWritesCacheAndKeepsFrameOutOfUiQueue() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, attachmentCache = cache)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithAttachment())

        var result: ByteArray? = null
        val job = launch { result = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "attachment" })
        val payload = wireJson.parseToJsonElement(transport.sentPayloads.last()).jsonObject
        assertEquals("s1", payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("e1", payload.getValue("entryId").jsonPrimitive.content)
        assertEquals(0, payload.getValue("imageIndex").jsonPrimitive.content.toInt())

        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        job.join()
        assertSameBytes(byteArrayOf(1, 2, 3), result)
        // 成功先写会话级缓存。
        assertSameBytes(byteArrayOf(1, 2, 3), cache.values[gatewayAttachmentCacheKey("s1", "img-1")])
        // attachment 帧本体（含大 base64）不进 UI 事件队列。
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.Frame>().any { it.frame.kind == "attachment" })

        // 同一 cacheId 第二次取：缓存命中，不再发请求。
        val before = transport.sentTypes.count { it == "attachment" }
        assertSameBytes(byteArrayOf(1, 2, 3), runtime.fetchAttachment("s1", "e1", 0, "img-1"))
        assertEquals(before, transport.sentTypes.count { it == "attachment" })
    }

    @Test
    fun fetchAttachmentFetchesNextImageAfterSuccess() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())

        var first: ByteArray? = null
        var second: ByteArray? = null
        val job = launch {
            first = runtime.fetchAttachment("s1", "e1", 0, "img-1")
            second = runtime.fetchAttachment("s1", "e1", 1, "img-2")
        }
        runCurrent()
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        transport.receive(attachmentPayload("s1", "e1", 1, "AQIDBA==", bytes = 4))
        runCurrent()
        job.join()
        assertSameBytes(byteArrayOf(1, 2, 3), first)
        assertSameBytes(byteArrayOf(1, 2, 3, 4), second)
    }

    @Test
    fun fetchAttachmentIgnoresMismatchedTriplesAndLateFrames() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithAttachment())

        var result: ByteArray? = "stale".encodeToByteArray()
        val job = launch { result = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        // 迟到/别的请求的成功帧（entryId、imageIndex 不匹配）：静默丢弃，当前等待不受影响。
        transport.receive(attachmentPayload("s1", "e0", 0, "AQID", bytes = 3))
        runCurrent()
        transport.receive(attachmentPayload("s1", "e1", 9, "AQID", bytes = 3))
        runCurrent()
        // 错配的错误帧同样不认领。
        transport.receive(
            """{"kind":"error","requestType":"attachment","sessionId":"s1","entryId":"eX","imageIndex":0,"code":"attachment-not-found","message":"no entry"}"""
        )
        runCurrent()
        assertTrue(job.isActive)

        // 三元匹配的成功帧才认领终态。
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        job.join()
        assertSameBytes(byteArrayOf(1, 2, 3), result)
        // 错配帧不产生 session-mismatch 噪声。
        assertFalse(events.filterIsInstance<GatewayRuntimeEvent.RequestRejected>().any {
            it.reason == "session-mismatch"
        })
    }

    @Test
    fun fetchAttachmentErrorFrameCompletesWithNullAndLaneIsReusable() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())

        var result: ByteArray? = byteArrayOf(0)
        val job = launch { result = runtime.fetchAttachment("s1", "e-missing", 0, "img-x") }
        runCurrent()
        transport.receive(
            """{"kind":"error","requestType":"attachment","sessionId":"s1","entryId":"e-missing","imageIndex":0,"code":"attachment-not-found","message":"no entry"}"""
        )
        runCurrent()
        job.join()
        assertNull(result)

        // 错误终态释放 lane：同一连接可再次取图并成功（错误后再次成功）。
        var retry: ByteArray? = null
        val retryJob = launch { retry = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        retryJob.join()
        assertSameBytes(byteArrayOf(1, 2, 3), retry)
    }

    @Test
    fun fetchAttachmentFailsOnInvalidPayloads() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())
        suspend fun receive(json: String) {
            var result: ByteArray? = byteArrayOf(0)
            val job = launch { result = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
            runCurrent()
            transport.receive(json)
            runCurrent()
            job.join()
            assertNull(result, json.take(60))
        }
        // mime 非有界 image/*。
        receive(
            """{"kind":"attachment","sessionId":"s1","entryId":"e1","imageIndex":0,"mediaType":"text/plain","bytes":3,"dataBase64":"AQID"}"""
        )
        // 声明 bytes 与解码结果不一致。
        receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 4))
        // 坏 base64。
        receive(attachmentPayload("s1", "e1", 0, "not-base64!!", bytes = 3))
        // base64 超过编码长度上限（>5_592_408 字符）。
        receive(attachmentPayload("s1", "e1", 0, "A".repeat(5_592_409), bytes = 4_194_307))
    }

    @Test
    fun fetchAttachmentTimeoutReleasesLane() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport, requestTimeoutMilliseconds = 100)
        val events = mutableListOf<GatewayRuntimeEvent>()
        backgroundScope.launch { runtime.events.collect(events::add) }
        connectHello(transport, runtime, helloWithAttachment())

        var result: ByteArray? = byteArrayOf(0)
        val job = launch { result = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "attachment" })

        // hello 后 replay 的 sessions 请求 timeout 同为 100ms：先收掉它的应答，
        // 让 attachment 超时成为本测试唯一到期的请求。
        transport.receive("""{"kind":"sessions","items":[]}""")
        runCurrent()

        // message/history 已在途（timeout 到期晚于 attachment）：attachment 超时不得连带取消它们。
        advanceTimeBy(50)
        runCurrent()
        assertTrue(runtime.sendMessage("work", emptyList(), "session-a", null))
        assertTrue(runtime.requestHistory("session-a"))
        runCurrent()

        advanceTimeBy(50)
        runCurrent()
        job.join()
        assertNull(result)
        assertTrue(events.last() is GatewayRuntimeEvent.RequestTimedOut)

        // 只结束 attachment 自己：连接保持 CONNECTED、generation 不变，message/history
        // lane 与 turn 保留（不再回收整条连接）。
        assertEquals(GatewayConnectionState.CONNECTED, runtime.state.value.connection)
        assertEquals(1, transport.connectionSpecs.size)
        assertEquals(0, transport.closeCount)
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        transport.receive("""{"kind":"sent","sessionId":"session-a"}""")
        transport.receive(
            """{"kind":"history","sessionId":"session-a","messages":[],"entryIds":[],"hasMore":false}"""
        )
        runCurrent()
        // sent 只是受理，turn 仍 active：attachment 超时未连带结束 message 的 turn。
        assertEquals(setOf("session-a"), runtime.state.value.activeTurnSessionIds)
        // 真实 agent_settled 才结束该 turn（同时证明 message lane 存活、事件通道正常）。
        transport.receive(
            """{"kind":"event","sessionId":"session-a","seq":8,"time":10.0,""" +
                """"event":{"type":"agent_settled"}}"""
        )
        runCurrent()
        assertEquals(emptySet<String>(), runtime.state.value.activeTurnSessionIds)

        // 下一取图无需重连/重新握手即可发出。
        var retry: ByteArray? = null
        val retryJob = launch { retry = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        retryJob.join()
        assertSameBytes(byteArrayOf(1, 2, 3), retry)
    }

    @Test
    fun fetchAttachmentDisconnectCompletesWithNull() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())

        var result: ByteArray? = byteArrayOf(0)
        val job = launch { result = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        transport.fail(
            GatewayTransportState.Failed(
                generation = transport.connectionSpecs.last().generation,
                reason = "websocket-failure",
                recoverable = true
            )
        )
        runCurrent()
        job.join()
        assertNull(result)
    }

    @Test
    fun fetchAttachmentCallerCancellationReleasesLaneForNextRequest() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())

        val job = launch { runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "attachment" })
        job.cancelAndJoin()

        // 取消及时退 lane：下一请求立即发出（若 slot/lane 未释放会被 REJECT_IF_BUSY 拒掉）。
        var retry: ByteArray? = null
        val retryJob = launch { retry = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        retryJob.join()
        assertSameBytes(byteArrayOf(1, 2, 3), retry)
    }

    @Test
    fun concurrentAttachmentFetchRejectsSecondWithoutDisruptingFirst() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, attachmentCache = cache)
        connectHello(transport, runtime, helloWithAttachment())

        var resultA: ByteArray? = byteArrayOf(0)
        val jobA = launch { resultA = runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "attachment" })

        // A 在途时并发 B：按 REJECT_IF_BUSY 拒绝，且不得作废 A 的等待。
        var resultB: ByteArray? = byteArrayOf(0)
        val jobB = launch { resultB = runtime.fetchAttachment("s1", "e2", 0, "img-2") }
        runCurrent()
        assertNull(resultB)

        // A 的回复照常完成并写缓存（B 的 busy 不得影响 A）。
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        runCurrent()
        jobA.join()
        assertSameBytes(byteArrayOf(1, 2, 3), resultA)
        assertSameBytes(byteArrayOf(1, 2, 3), cache.values[gatewayAttachmentCacheKey("s1", "img-1")])

        // A 完成后 C 立即成功（lane 已释放，不再挂到超时）。
        var resultC: ByteArray? = null
        val jobC = launch { resultC = runtime.fetchAttachment("s1", "e3", 0, "img-3") }
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentPayload("s1", "e3", 0, "AQID", bytes = 3))
        runCurrent()
        jobC.join()
        assertSameBytes(byteArrayOf(1, 2, 3), resultC)
    }

    @Test
    fun fetchAttachmentCancellationCleanupSurvivesBusySerializedLock() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())

        // A 在途正常（attachment lane + slot 均为 A）。
        val jobA = launch { runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "attachment" })

        // 另一请求在 transport.send 闸门内挂起，占住 serialization 锁。
        val sendGate = CompletableDeferred<Unit>()
        transport.sendGate = sendGate
        val blockerJob = launch { runtime.sendMessage("ping", emptyList(), "session-a", null) }
        runCurrent()

        // 调用方取消：清理需在不可取消区等锁并保证最终执行（不被取消吞掉）。
        jobA.cancel()
        runCurrent()

        transport.sendGate = null
        sendGate.complete(Unit)
        runCurrent()
        blockerJob.join()
        jobA.join()

        // A 的取消已释放 slot/lane：B 立即发出（旧实现 cleanup 被取消吞掉，B 被 busy 拒）。
        var result: ByteArray? = null
        val jobB = launch { result = runtime.fetchAttachment("s1", "e2", 0, "img-2") }
        runCurrent()
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
        transport.receive(attachmentPayload("s1", "e2", 0, "AQID", bytes = 3))
        runCurrent()
        jobB.join()
        assertSameBytes(byteArrayOf(1, 2, 3), result)
    }

    @Test
    fun fetchAttachmentCallerCancellationDoesNotKillNextRequest() = runTest {
        val transport = FakeTransport()
        val runtime = newRuntime(transport)
        connectHello(transport, runtime, helloWithAttachment())

        val jobA = launch { runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        assertEquals(1, transport.sentTypes.count { it == "attachment" })

        // 调度顺序（FIFO）：A 的回复处理 → 新请求 B 接管 lane/slot → A 的取消清理。
        // A 回复先释放 lane/slot，清理只能作用于仍属于自己的请求，不得误杀 B。
        transport.receive(attachmentPayload("s1", "e1", 0, "AQID", bytes = 3))
        var resultB: ByteArray? = null
        val jobB = launch { resultB = runtime.fetchAttachment("s1", "e2", 0, "img-2") }
        jobA.cancel()
        runCurrent()

        transport.receive(attachmentPayload("s1", "e2", 0, "AQID", bytes = 3))
        runCurrent()
        jobB.join()
        assertSameBytes(byteArrayOf(1, 2, 3), resultB)
        assertEquals(2, transport.sentTypes.count { it == "attachment" })
    }

    @Test
    fun fetchAttachmentCancellationDuringCacheReadDoesNotSendRequest() = runTest {
        val transport = FakeTransport()
        val cache = FakeAttachmentCache()
        val runtime = newRuntime(transport, attachmentCache = cache)
        connectHello(transport, runtime, helloWithAttachment())

        val readGate = CompletableDeferred<Unit>()
        cache.readGate = readGate
        val job = launch { runtime.fetchAttachment("s1", "e1", 0, "img-1") }
        runCurrent()
        job.cancel()
        readGate.complete(Unit)
        runCurrent()
        job.join()

        // 已取消的调用方不得继续发新请求（旧实现读取消被吞后继续 miss 发送）。
        assertEquals(0, transport.sentTypes.count { it == "attachment" })
    }

    private fun sentSessionIds(payload: String): String? =
        wireJson.parseToJsonElement(payload).jsonObject["sessionId"]?.jsonPrimitive?.content

    private class FakeTransport : GatewayTransport {
        private val mutableTransportState = MutableStateFlow<GatewayTransportState>(GatewayTransportState.Closed())
        private val mutableEvents = MutableSharedFlow<GatewayTransportEvent>(extraBufferCapacity = 64)
        val connectionSpecs = mutableListOf<GatewayConnectionSpec>()
        val sentPayloads = mutableListOf<String>()
        var failNextSend = false
        var closeCount = 0

        override val state: StateFlow<GatewayTransportState> = mutableTransportState.asStateFlow()
        override val events: Flow<GatewayTransportEvent> = mutableEvents.asSharedFlow()

        val sentTypes: List<String>
            get() = sentPayloads.map {
                wireJson.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content
            }

        override suspend fun open(spec: GatewayConnectionSpec) {
            connectionSpecs += spec
            emitState(GatewayTransportState.Opening(spec.generation))
        }

        override suspend fun send(text: String) {
            // 测试闸门：挂 send 以占住 runtime serialization 锁（默认 null 不挂起）。
            sendGate?.await()
            if (failNextSend) {
                failNextSend = false
                throw IllegalStateException("synthetic-send-failure")
            }
            sentPayloads += text
        }

        var sendGate: CompletableDeferred<Unit>? = null

        override suspend fun close() {
            closeCount += 1
            emitState(GatewayTransportState.Closed(connectionSpecs.lastOrNull()?.generation ?: 0))
        }

        fun opened() {
            emitState(GatewayTransportState.Open(connectionSpecs.last().generation))
        }

        fun receive(json: String) {
            receiveAt(connectionSpecs.last().generation, json)
        }

        fun receiveAt(generation: Long, json: String) {
            assertTrue(
                mutableEvents.tryEmit(
                    GatewayTransportEvent.Frame(
                        GatewayTransportFrame(generation, json, json.encodeToByteArray().size)
                    )
                )
            )
        }

        fun fail(failure: GatewayTransportState.Failed) {
            emitState(failure)
        }

        fun failThenReceive(failure: GatewayTransportState.Failed, generation: Long, json: String) {
            emitState(failure)
            receiveAt(generation, json)
        }

        private fun emitState(value: GatewayTransportState) {
            mutableTransportState.value = value
            assertTrue(mutableEvents.tryEmit(GatewayTransportEvent.State(value)))
        }
    }

    private class FakePreferences : GatewayPreferences {
        private val mutable = MutableStateFlow(GatewayPreferencesSnapshot())
        override val snapshots: Flow<GatewayPreferencesSnapshot> = mutable
        override suspend fun load(): GatewayPreferencesSnapshot = mutable.value
        override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
            mutable.value = snapshot
        }
    }

    private class FakeCredentials : GatewayCredentialStore {
        var token: String? = null
        var failTokenWrite = false
        override suspend fun loadOrCreateDeviceId(): String {
            if (failDeviceRead) error("synthetic credential failure")
            return "device-installation"
        }
        override suspend fun loadToken(): String? = token
        override suspend fun saveToken(token: String) {
            if (failTokenWrite) error("synthetic credential write failure")
            this.token = token
        }
        override suspend fun deleteToken() {
            token = null
        }
        var failDeviceRead = false
    }

    private class FakeAttachmentCache : GatewayAttachmentCache {
        val values = mutableMapOf<String, ByteArray>()
        /** 测试闸门：挂 read 以构造取消时序（默认 null 不挂起）。 */
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun read(attachmentId: String): ByteArray? {
            readGate?.await()
            return values[attachmentId]
        }
        override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean {
            values[attachmentId] = bytes
            return true
        }
        override suspend fun removeExpired() = Unit
    }

    private class FakeNetworkMonitor : GatewayNetworkMonitor {
        val mutableState = MutableStateFlow(GatewayNetworkState.AVAILABLE)
        override val state: StateFlow<GatewayNetworkState> = mutableState.asStateFlow()
    }

    private class FakeClock(private val now: Long) : GatewayClock {
        override fun nowEpochMilliseconds(): Long = now
        override suspend fun delay(milliseconds: Long) = Unit
    }

    companion object {
        /** 不带 port/clients/capabilities 的精简 hello（服务端实现允许缺省）。 */
        private const val HELLO_WITHOUT_EXTRA = """{"kind":"hello","protocol":1,"authenticated":true}"""

        /** 家里局域网入口与外出 tailnet 入口（RFC1918 私网段与 CGNAT 段；明文 ws 只允许这类地址）。 */
        private const val LAN_ENDPOINT = "ws://192.168.0.20:3081/ws/mobile"
        private const val TAIL_ENDPOINT = "ws://100.64.0.1:3081/ws/mobile"

        private const val BASE64_URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        private fun encodeBase64Url(value: String): String {
            val bytes = value.encodeToByteArray()
            val result = StringBuilder((bytes.size * 4 + 2) / 3)
            var index = 0
            while (index < bytes.size) {
                val first = bytes[index++].toInt() and 0xff
                val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                result.append(BASE64_URL_ALPHABET[first shr 2])
                result.append(BASE64_URL_ALPHABET[((first and 0x03) shl 4) or if (second >= 0) second shr 4 else 0])
                if (second >= 0) {
                    result.append(BASE64_URL_ALPHABET[((second and 0x0f) shl 2) or if (third >= 0) third shr 6 else 0])
                }
                if (third >= 0) result.append(BASE64_URL_ALPHABET[third and 0x3f])
            }
            return result.toString()
        }
    }
}
