package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.ui.finecat.theme.darkFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.lightFineCatColors
import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.android.waitUntil
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 合成帧经过生产 Runtime/门面到真实 FineCatApp；不访问模型、设备配置或输入法。 */
@RunWith(AndroidJUnit4::class)
class FineCatExtensionStatusDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var graph: AndroidAppGraph
    private lateinit var transport: FakeTransport
    private val holder get() = graph.stateHolder

    @After
    fun close() {
        if (this::graph.isInitialized) {
            holder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    private fun showSession(dark: Boolean = false, capability: Boolean = true) {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        runBlocking {
            holder.loadFixtureAndAwaitForTest()
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            val statusCapability = if (capability) ",\"extension-status\"" else ""
            transport.receive("""{"kind":"hello","protocol":3,"authenticated":true,"capabilities":["sessions","subscribe","history","state","models"$statusCapability]}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
        compose.runOnIdle { holder.updateAppearanceMode(if (dark) "dark" else "light") }
        compose.setContent { FineCatApp(holder) }
        compose.waitUntil(5_000) { holder.snapshot.selectedSessionId == "android-demo" }
        compose.onNodeWithTag("extension-status").assertDoesNotExist()
    }

    private fun status(sessionId: String, text: String?) {
        val entries = text?.let { """[{"key":"arbitrary-extension","text":${Json.encodeToString(it)}}]""" } ?: "[]"
        transport.receive("""{"kind":"extension-status","sessionId":"$sessionId","statuses":$entries}""")
        compose.waitUntil(5_000) {
            holder.snapshot.extensionStatuses[sessionId]?.singleOrNull()?.text == text
        }
        compose.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("extension-status").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        return layouts.single()
    }

    private fun checkScopedSnapshots(dark: Boolean) {
        showSession(dark)
        compose.runOnIdle { holder.messageDraft = "保留草稿" }
        status("android-demo", "2 agents running · 1 queued")
        compose.onNodeWithTag("extension-status").assertTextEquals("2 agents running · 1 queued")
        compose.onNodeWithTag("extension-status-spinner").assertExists()
        assertEquals(if (dark) darkFineCatColors().text3 else lightFineCatColors().text3, layout().layoutInput.style.color)
        assertEquals(10.sp, layout().layoutInput.style.fontSize)
        assertEquals(12.5.sp, layout().layoutInput.style.lineHeight)
        val row = compose.onNodeWithTag("extension-status").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("fc.composer.input").fetchSemanticsNode().boundsInRoot
        assertTrue("状态行应在输入区上方", row.bottom <= input.top)
        compose.runOnIdle {
            assertEquals("保留草稿", holder.messageDraft)
            assertFalse(holder.snapshot.sessionStates["android-demo"]?.isStreaming == true)
        }

        // 后台会话的快照不能替换当前文字；切换只看对应会话，回来仍有原快照。
        status("other-session", "Waiting for review")
        compose.onNodeWithTag("extension-status").assertTextEquals("2 agents running · 1 queued")
        compose.runOnIdle { holder.selectSession("other-session") }
        compose.waitUntil(5_000) { holder.snapshot.selectedSessionId == "other-session" }
        compose.onNodeWithTag("extension-status").assertTextEquals("Waiting for review")
        status("other-session", null)
        compose.onNodeWithTag("extension-status").assertDoesNotExist()
        compose.runOnIdle { holder.selectSession("android-demo") }
        compose.waitUntil(5_000) { holder.snapshot.selectedSessionId == "android-demo" }
        compose.onNodeWithTag("extension-status").assertTextEquals("2 agents running · 1 queued")
        status("android-demo", null)
        compose.onNodeWithTag("extension-status").assertDoesNotExist()
        compose.onNodeWithTag("extension-status-spinner").assertDoesNotExist()
        compose.runOnIdle { assertEquals("保留草稿", holder.messageDraft) }
    }

    @Test fun lightThemeUsesScopedSnapshotsAndClearsWithoutLosingDraft() = checkScopedSnapshots(false)

    @Test fun darkThemeUsesScopedSnapshotsAndClearsWithoutLosingDraft() = checkScopedSnapshots(true)

    @Test fun oldGatewayHasNoStatusPlaceholderEvenIfAFrameArrives() {
        showSession(capability = false)
        status("android-demo", "1 agent running")
        compose.onNodeWithTag("extension-status").assertDoesNotExist()
    }

    @Test fun longStatusIsEllipsizedWithinTwoLines() {
        showSession()
        status("android-demo", "很长的扩展状态文本 ".repeat(20))
        val result = layout()
        assertEquals(2, result.lineCount)
        assertTrue(result.isLineEllipsized(1))
    }

    @Test fun visibleNotificationIsCollapsedAndCanRevealItsFullTextFromTheAppRoot() {
        showSession()
        val text = "合成后台任务完成，完整结果保留。".repeat(30)
        transport.receive("""{"kind":"event","sessionId":"android-demo","seq":900,"time":1792425600,"entryId":"notice-synthetic","event":{"type":"message_end","message":{"role":"custom","customType":"arbitrary-notice","display":true,"content":${Json.encodeToString(text)}}}}""")
        compose.waitUntil(5_000) { holder.snapshot.conversation.any { it.text == text } }
        val notice = holder.snapshot.conversation.single { it.text == text }
        assertEquals(ConversationItemKind.NOTICE, notice.kind)
        val group = makeConversationDisplayEntries(holder.snapshot.conversation)
            .filterIsInstance<ConversationDisplayEntry.Process>().single { notice in it.group.items }
        compose.onNodeWithTag("finecat.step.${notice.id}").assertDoesNotExist()
        compose.onNodeWithTag("finecat.stepsHeader.${group.id}").performScrollTo().assertTextContains("完成了")
            .performClick()
        compose.onNodeWithTag("finecat.step.${notice.id}").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.stepDetailText").assertTextEquals(text)
        compose.onNodeWithTag("finecat.stepDetailCommand").assertDoesNotExist()
    }
}
