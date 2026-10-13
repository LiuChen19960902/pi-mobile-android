package com.finecat.pimobile.android

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.shared.gateway.gatewayAttachmentCacheKey
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.platform.GatewayAttachmentCache
import com.finecat.pimobile.shared.platform.GatewayClock
import com.finecat.pimobile.shared.platform.GatewayConnectionSpec
import com.finecat.pimobile.shared.platform.GatewayCredentialStore
import com.finecat.pimobile.shared.platform.GatewayNetworkMonitor
import com.finecat.pimobile.shared.platform.GatewayNetworkState
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import com.finecat.pimobile.shared.platform.GatewayTransport
import com.finecat.pimobile.shared.platform.GatewayTransportEvent
import com.finecat.pimobile.shared.platform.GatewayTransportFrame
import com.finecat.pimobile.shared.platform.GatewayTransportState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAppGraphFakeIntegrationDeviceTest {
    @Test
    fun injectedProductGraphRunsRuntimeHolderProjectionHistoryAndVisibleAttachment() = runBlocking {
        val transport = FakeTransport()
        val cache = FakeCache()
        val decoderThreads = mutableListOf<Thread>()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = cache,
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            frameDecoderOverride = { raw ->
                synchronized(decoderThreads) { decoderThreads += Thread.currentThread() }
                GatewayWireDecoder.decode(raw)
            }
        )
        val holder = graph.stateHolder
        waitUntil { transport.specs.isNotEmpty() }
        transport.open()
        transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
        waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        transport.receive("""{"kind":"pong","message":"${"x".repeat(1_000_000)}"}""")
        waitUntil { synchronized(decoderThreads) { decoderThreads.size >= 2 } }
        assertTrue(synchronized(decoderThreads) { decoderThreads.all { it !== Looper.getMainLooper().thread } })

        onMain { holder.loadFixture() }
        val historyBefore = transport.sentTypes.count { it == "history" }
        onMain { holder.selectSession("android-demo") }
        waitUntil { transport.sentTypes.count { it == "history" } > historyBefore }
        val initialHistoryRequest = transport.payloadsOfType("history").last()
        assertEquals(60, initialHistoryRequest.getValue("limit").jsonPrimitive.int)
        transport.receive(
            """{"kind":"history","sessionId":"android-demo","messages":[{"role":"user","content":[{"type":"text","text":"product-history"}]}],"entryIds":["e1"],"hasMore":true,"oldestEntryId":"e1","bytes":64}"""
        )
        waitUntil { holder.snapshot.conversation.any { it.text == "product-history" } }
        delay(100)
        assertEquals(historyBefore + 1, transport.sentTypes.count { it == "history" })

        onMain { holder.loadOlderHistory() }
        waitUntil { transport.sentTypes.count { it == "history" } == historyBefore + 2 }
        val olderHistoryRequest = transport.payloadsOfType("history").last()
        assertEquals("e1", olderHistoryRequest.getValue("beforeEntryId").jsonPrimitive.content)
        transport.receive(
            """{"kind":"history","sessionId":"android-demo","messages":[],"entryIds":[],"hasMore":false,"bytes":0}"""
        )
        waitUntil { !holder.snapshot.selectedHistoryIsLoading }

        assertTrue(graph.gatewayRuntime.requestHistory("android-demo"))
        waitUntil { transport.sentTypes.count { it == "history" } >= historyBefore + 2 }
        transport.receive(
            """{"kind":"error","requestType":"history","sessionId":"android-demo","code":"synthetic"}"""
        )
        waitUntil { holder.platformError?.startsWith("history:") == true }
        val beforeRetry = transport.sentTypes.count { it == "history" }
        assertTrue(graph.gatewayRuntime.requestHistory("android-demo"))
        waitUntil { transport.sentTypes.count { it == "history" } > beforeRetry }

        val chunks = List(1_200) { "长" }
        chunks.forEachIndexed { index, text ->
            transport.receive(
                """{"sessionId":"android-demo","seq":${index + 2},"time":${index + 2},"event":{"type":"assistant/chunk","turn":2,"step":1,"chunkType":"text-delta","text":"$text"}}"""
            )
        }
        val finalText = chunks.joinToString("")
        transport.receive(
            """{"sessionId":"android-demo","seq":1202,"time":1202,"event":{"type":"assistant/message","turn":2,"step":1,"text":"$finalText"}}"""
        )
        waitUntil {
            holder.snapshot.conversation.any { it.text == finalText } &&
                holder.snapshot.conversation.none { it.id.startsWith("stream-") }
        }
        assertTrue(holder.snapshot.conversation.none { it.id.startsWith("stream-") })

        val png = tinyPng()
        transport.receive(
            """{"sessionId":"android-demo","seq":1300,"time":1300,"event":{"type":"assistant/message","turn":2,"step":1,"text":"image","images":[{"attachmentId":"same-id","mediaType":"image/png","bytes":${png.size},"width":2,"height":2}]}}"""
        )
        waitUntil { holder.snapshot.conversation.any { item -> item.images.any { it.attachmentId == "same-id" } } }
        onMain { holder.updateVisibleAttachments(setOf("same-id")) }
        waitUntil { transport.sentTypes.lastOrNull() == "attachment" }
        transport.receive(
            """{"kind":"attachment","sessionId":"android-demo","attachment":{"attachmentId":"same-id","mediaType":"image/png","bytes":${png.size},"width":2,"height":2},"data":"${Base64.encodeToString(png, Base64.NO_WRAP)}"}"""
        )
        waitUntil { holder.attachmentStates["same-id"] == AttachmentLoadState.LOADED }
        assertTrue("same-id" in holder.attachmentThumbnails)
        assertTrue(gatewayAttachmentCacheKey("android-demo", "same-id") in cache.values)
    }

}
