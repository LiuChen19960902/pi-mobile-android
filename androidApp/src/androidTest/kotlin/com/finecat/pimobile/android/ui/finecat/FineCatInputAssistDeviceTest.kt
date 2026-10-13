package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.finecat.pimobile.android.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 临时资源应答与假 transport，绝不读取真实宿主资源或发送消息。 */
class FineCatInputAssistDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()
    private lateinit var graph: AndroidAppGraph
    private lateinit var transport: FakeTransport
    private val holder get() = graph.stateHolder
    @After fun close() { if (this::graph.isInitialized) {
        holder.close(); graph.gatewayScope.cancel(); graph.applicationScope.cancel()
    } }

    private fun showHome() {
        transport = FakeTransport()
        graph = AndroidAppGraph(ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(), credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(), networkMonitorOverride = FakeNetwork, clockOverride = FakeClock)
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","protocol":1,"authenticated":true,"capabilities":["sessions","input-resources","input-completions"]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
        compose.setContent { FineCatApp(holder) }
        compose.runOnIdle { holder.prepareNewSession("/fixture") }
        runBlocking {
            waitUntil { transport.payloadsOfType("input-resources").isNotEmpty() }
            val id = transport.payloadsOfType("input-resources").last()["queryId"]!!.jsonPrimitive.content
            val commands = (0..7).joinToString(",") { """{"name":"skill:audit$it","source":"skill","description":"Synthetic"}""" } +
                """,{"name":"evaluate","source":"prompt","description":"Synthetic"}"""
            transport.receive("""{"kind":"input-resources","queryId":"$id","cwd":"/fixture","mode":"preview","complete":false,"skillCommandsEnabled":true,"commands":[$commands]}""")
            waitUntil { holder.inputAssist.commands.size == 9 }
        }
    }

    @Test fun skillAndPromptShareRowsAndAcceptanceOnlyEditsDraft() {
        showHome()
        compose.onNodeWithTag("fc.composer.input").performClick().performTextInput("/au")
        compose.onNodeWithTag("finecat.inputAssist").assertExists()
        compose.onNodeWithTag("finecat.composerDirectory").assertDoesNotExist()
        val row0 = compose.onNodeWithTag("finecat.inputAssist.row.0").fetchSemanticsNode().boundsInRoot
        val row1 = compose.onNodeWithTag("finecat.inputAssist.row.1").fetchSemanticsNode().boundsInRoot
        assertEquals(38f, row0.height / compose.density.density, 0.5f)
        assertEquals(row0.bottom, row1.top, 0.5f)
        compose.onNodeWithText("/skill:audit0").performClick()
        compose.runOnIdle { assertEquals("/skill:audit0 ", holder.messageDraft) }
        compose.onNodeWithTag("finecat.inputAssist").assertDoesNotExist()
        compose.onNodeWithTag("fc.composer.input").performTextReplacement("/ev")
        compose.onNodeWithText("/evaluate").performClick()
        compose.runOnIdle { assertEquals("/evaluate ", holder.messageDraft); assertFalse("message" in transport.sentTypes) }
    }

    @Test fun outsideTapDropsOnlyTriggerAndRestoresDirectorySlot() {
        showHome()
        compose.onNodeWithTag("fc.composer.input").performClick().performTextInput("保留 /au")
        compose.onNodeWithTag("finecat.inputAssist").assertExists()
        // 本应用根左侧的留白，位于浮层外；不点击正文或会话导航。
        compose.onNodeWithTag("finecat.root").performTouchInput { click(Offset(2f, height / 3f)) }
        compose.runOnIdle { assertEquals("保留 ", holder.messageDraft); assertFalse("message" in transport.sentTypes) }
        compose.onNodeWithTag("finecat.inputAssist").assertDoesNotExist()
        compose.onNodeWithTag("finecat.composerDirectory").assertExists()
    }
}
