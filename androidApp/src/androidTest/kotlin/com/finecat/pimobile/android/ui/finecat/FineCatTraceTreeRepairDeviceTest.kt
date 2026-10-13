package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实根入口 + 合成wire帧；不接真实网关/模型或用户会话。 */
@RunWith(AndroidJUnit4::class)
class FineCatTraceTreeRepairDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var graph: AndroidAppGraph? = null

    @After fun close() {
        graph?.stateHolder?.close()
        graph?.gatewayScope?.cancel()
        graph?.applicationScope?.cancel()
    }

    private fun setup(): Pair<AndroidSharedStateHolder, FakeTransport> {
        val transport = FakeTransport()
        val app = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(), credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(), networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
        )
        graph = app
        val holder = app.stateHolder
        compose.setContent { FineCatApp(stateHolder = holder) }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","history","state","tree","tree-view-v1"]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
            transport.receive("""{"kind":"sessions","items":[{"id":"s","cwd":"/tmp/synthetic","messageCount":2,"firstMessage":"测试","running":false}]}""")
            waitUntil { holder.snapshot.sessions.any { it.id == "s" } }
            onMain { holder.selectSession("s") }
            waitUntil { transport.sentTypes.contains("history") }
            transport.receive("""{"kind":"history","sessionId":"s","messages":[
                {"role":"assistant","content":[{"type":"text","text":"历史助手回答"}]},
                {"role":"toolResult","toolName":"read","toolCallId":"c1","content":[{"type":"text","text":"历史工具输出"}]}
                ],"entryIds":["a1","t1"],"hasMore":false,"oldestEntryId":"a1"}""")
            waitUntil { holder.snapshot.conversation.isNotEmpty() }
        }
        return holder to transport
    }

    @Test fun treeUsesWirePrefixesLabelsAndLiteralPiText() {
        val (_, transport) = setup()
        compose.onNodeWithContentDescription("tree").performClick()
        runBlocking {
            waitUntil { transport.sentTypes.contains("tree") }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"a","nodes":[
                {"id":"r","type":"message","role":"user","prefix":"","onActivePath":true,"preview":"root","displayText":"user: root","foldable":true},
                {"id":"a","parentId":"r","type":"message","role":"assistant","prefix":"├─ ","onActivePath":true,"preview":"old","displayText":"assistant: **literal**","label":"bookmark"},
                {"id":"b","parentId":"r","type":"message","role":"user","prefix":"└─ ","preview":"branch","displayText":"user: branch"}
            ]}""")
        }
        compose.onNodeWithTag("finecat.treePrefix.a", useUnmergedTree = true).assertTextEquals("├─ • ")
        compose.onNodeWithTag("finecat.treePrefix.b", useUnmergedTree = true).assertTextEquals("└─ ")
        compose.onNodeWithText("[bookmark]", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeText.a", useUnmergedTree = true).assertTextEquals("assistant: **literal**")
        compose.onNodeWithTag("finecat.treeFold.r").performClick()
        runBlocking {
            waitUntil { transport.sentTypes.count { it == "tree" } == 2 }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"r","nodes":[
                {"id":"r","type":"message","role":"user","prefix":"⊞ ","onActivePath":true,"preview":"root","displayText":"user: root","foldable":true,"folded":true}
            ]}""")
            waitUntil { graph?.stateHolder?.treeRequestInFlight == false }
        }
        compose.onNodeWithTag("finecat.treeSearch").performTextInput("root")
        compose.onNodeWithTag("finecat.treeSearch").performImeAction()
        runBlocking { waitUntil { transport.sentTypes.count { it == "tree" } == 3 } }
        val view = transport.payloadsOfType("tree").last().getValue("view").jsonObject
        assertEquals("root", view.getValue("search").jsonPrimitive.content)
        assertEquals("[]", view.getValue("foldedIds").toString())
    }

    @Test fun treeSearchSendsLastQueryAfterInFlightRequestAndClearingRestoresDefault() {
        val (holder, transport) = setup()
        compose.onNodeWithContentDescription("tree").performClick()
        runBlocking { waitUntil { holder.treeRequestInFlight } }
        compose.onNodeWithTag("finecat.treeSearch").performTextReplacement("first")
        compose.onNodeWithTag("finecat.treeSearch").performTextReplacement("last")
        assertEquals(1, transport.payloadsOfType("tree").size)
        compose.onNodeWithText("搜索", substring = false).assertDoesNotExist()
        compose.onNodeWithText("默认", substring = false).assertDoesNotExist()
        runBlocking {
            transport.receive("""{"kind":"tree","sessionId":"s","nodes":[]}""")
            waitUntil { transport.payloadsOfType("tree").last().getValue("view").jsonObject["search"]?.jsonPrimitive?.content == "last" }
        }
        assertEquals(2, transport.payloadsOfType("tree").size)
        compose.onNodeWithTag("finecat.treeSearch").performTextReplacement("")
        runBlocking {
            transport.receive("""{"kind":"tree","sessionId":"s","nodes":[]}""")
            waitUntil { transport.payloadsOfType("tree").last().getValue("view").jsonObject["search"]?.jsonPrimitive?.content == "" }
        }
        assertEquals(3, transport.payloadsOfType("tree").size)
        val view = transport.payloadsOfType("tree").last().getValue("view").jsonObject
        assertEquals("default", view.getValue("filter").jsonPrimitive.content)
        assertEquals("[]", view.getValue("foldedIds").toString())
    }

    @Test fun legacyTreeRequestDoesNotInheritPreviousSearch() {
        val (holder, transport) = setup()
        runBlocking {
            onMain { holder.loadSessionTree(com.finecat.pimobile.shared.protocol.GatewayTreeView(search = "old search")) }
            waitUntil { transport.sentTypes.contains("tree") }
            transport.receive("""{"kind":"tree","sessionId":"s","nodes":[]}""")
            waitUntil { !holder.treeRequestInFlight }
            onMain { holder.loadSessionTree() }
            waitUntil { transport.sentTypes.count { it == "tree" } == 2 }
        }
        assertFalse(transport.payloadsOfType("tree").last().containsKey("view"))
    }
}
