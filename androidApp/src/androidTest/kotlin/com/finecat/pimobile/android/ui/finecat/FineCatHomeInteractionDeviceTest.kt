package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.waitUntil
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 隔离网关与偏好；触控走真实 FineCatApp 首页/历史会话，不发送或读取私人图片。 */
@RunWith(AndroidJUnit4::class)
class FineCatHomeInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()
    private lateinit var graph: AndroidAppGraph
    private lateinit var preferences: FakePreferences
    private lateinit var transport: FakeTransport
    private val holder: AndroidSharedStateHolder get() = graph.stateHolder

    @After fun tearDown() {
        if (this::graph.isInitialized) {
            holder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    private fun showHome() {
        transport = FakeTransport()
        preferences = FakePreferences()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = preferences,
            credentialStoreOverride = FakeCredentials, attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
        )
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","connection":{"state":"connected"},"sessions":[],"contextUsage":null,"model":null}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
        compose.setContent { FineCatApp(stateHolder = holder) }
        compose.runOnIdle { holder.prepareNewSession() }
    }

    private fun showHistory() {
        showHome()
        runBlocking {
            transport.receive("""{"kind":"sessions","items":[{"id":"history-ime","modified":"2026-10-07T00:00:00Z","running":false,"cwd":"/tmp/finecat-test","messageCount":12,"firstMessage":"历史消息 0"}]}""")
            holder.selectSession("history-ime")
            waitUntil { holder.snapshot.selectedSessionId == "history-ime" }
            val messages = (0 until 12).joinToString(",") {
                """{"role":"user","content":[{"type":"text","text":"历史消息 $it"}],"timestamp":${1_791_328_000_000L + it}}"""
            }
            val ids = (0 until 12).joinToString(",") { "\"history-ime-$it\"" }
            transport.receive("""{"kind":"history","sessionId":"history-ime","messages":[$messages],"entryIds":[$ids],"hasMore":false,"oldestEntryId":"history-ime-0"}""")
            waitUntil { holder.snapshot.conversation.size == 12 }
        }
        compose.waitForIdle()
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun focusInput() {
        compose.onNodeWithTag("fc.composer.input").performTouchInput { click() }
        compose.waitUntil(5_000) { imeVisible() }
        compose.onNodeWithTag("fc.composer.input").assertIsFocused()
    }

    private fun outsideTap() {
        // 侧边留白，首页/历史页都避开顶栏与正文交互控件。
        compose.onRoot().performTouchInput { click(Offset(1f, height * 0.25f)) }
        compose.waitForIdle()
    }

    @Test fun historyViewportStaysVisibleWhenImeOpensClosesAndReopens() {
        showHistory()
        fun assertHistoryVisible(imeExpected: Boolean = false) {
            var previousImeBottom = -1
            var imeStableSince = 0L
            compose.waitUntil(5_000) {
                var keyboardTop = Float.POSITIVE_INFINITY
                var imeBottom = 0
                compose.runOnIdle {
                    val decor = compose.activity.window.decorView
                    val origin = IntArray(2)
                    decor.getLocationInWindow(origin)
                    imeBottom = ViewCompat.getRootWindowInsets(decor)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    keyboardTop = (origin[1] + decor.height - imeBottom).toFloat()
                }
                val now = System.nanoTime()
                if (imeBottom != previousImeBottom) {
                    previousImeBottom = imeBottom
                    imeStableSince = now
                }
                val list = compose.onNodeWithTag("finecat.list").fetchSemanticsNode().boundsInWindow
                val composer = compose.onNodeWithTag("finecat.composer").fetchSemanticsNode().boundsInWindow
                // 同一窗口坐标，轮询跨过IME动画；5px只容忍同帧相接处的舍入。
                // 打开时等待高度稳定200ms，避开动画起始几像素造成的提前通过。
                (!imeExpected || (imeBottom > 5 && now - imeStableSince >= 200_000_000L)) &&
                    list.height > 0f && abs(composer.top - list.bottom) <= 5f &&
                    composer.bottom <= keyboardTop + 5f
            }
            compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
            compose.onNodeWithText("历史消息 11").assertIsDisplayed()
            compose.onNodeWithTag("finecat.working").assertDoesNotExist()
        }
        assertHistoryVisible()
        focusInput()
        assertHistoryVisible(imeExpected = true)
        outsideTap()
        compose.waitUntil(5_000) { !imeVisible() }
        assertHistoryVisible()
        focusInput()
        compose.onNodeWithTag("fc.composer.input").performTextInput("测试草稿第一行\n第二行")
        assertHistoryVisible(imeExpected = true)
        compose.runOnIdle {
            assertEquals(12, holder.snapshot.conversation.size)
            assertEquals("测试草稿第一行\n第二行", holder.messageDraft)
        }
    }

    @Test fun emptyOutsideTapHidesImeAndCollapsesWithoutReplacingEditor() {
        showHome()
        val editorId = compose.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id
        focusInput()
        outsideTap()
        compose.waitUntil(5_000) { !imeVisible() }
        compose.onNodeWithTag("fc.composer.input").assertIsNotFocused()
        compose.onNodeWithTag("finecat.modelChip").assertDoesNotExist()
        assertEquals(editorId, compose.onNodeWithTag("fc.composer.input").fetchSemanticsNode().id)
        focusInput()
    }

    @Test fun outsideTapClosesEmptyTrayButKeepsTextAndExpandedCard() {
        showHome()
        compose.runOnIdle { holder.togglePhotosTray() }
        compose.onNodeWithTag("fc.shotStrip").assertExists()
        outsideTap()
        compose.runOnIdle { assertFalse(holder.photosTrayOpen) }
        compose.onNodeWithTag("finecat.modelChip").assertDoesNotExist()
        focusInput()
        compose.onNodeWithTag("fc.composer.input").performTextInput("临时草稿")
        compose.onNodeWithTag("fc.composer.input").performTouchInput { click() }
        compose.onNodeWithTag("fc.composer.input").assertIsFocused()
        outsideTap()
        compose.waitUntil(5_000) { !imeVisible() }
        compose.onNodeWithTag("fc.composer.input").assertIsNotFocused()
        compose.onNodeWithTag("fc.composer.input").assertTextEquals("临时草稿")
        compose.onNodeWithTag("finecat.modelChip").assertExists()
        compose.runOnIdle { assertEquals("临时草稿", holder.messageDraft) }
    }

    @Test fun outsideTapDoesNotCancelPendingImageOrCloseItsTray() {
        showHome()
        val uri = Uri.parse("content://finecat-test/pending-image")
        compose.runOnIdle {
            assertTrue(holder.beginImagePreparation(uri.toString()) != null)
            holder.togglePhotosTray()
        }
        outsideTap()
        compose.runOnIdle {
            assertEquals(setOf(uri.toString()), holder.preparingPhotoUris)
            assertTrue(holder.photosTrayOpen)
        }
        compose.onNodeWithTag("finecat.modelChip").assertExists()
    }

    @Test fun scrollingLongPressMultitouchAndDraggingOutsideDoNotDismissInput() {
        showHome()
        focusInput()
        compose.onRoot().performTouchInput {
            swipeUp(startY = height * 0.4f, endY = height * 0.2f)
        }
        compose.onNodeWithTag("fc.composer.input").assertIsFocused()
        compose.onRoot().performTouchInput { longClick(Offset(width * 0.9f, height * 0.15f)) }
        compose.onNodeWithTag("fc.composer.input").assertIsFocused()
        assertTrue(imeVisible())
        compose.onRoot().performTouchInput {
            down(0, Offset(width * 0.8f, height * 0.15f))
            down(1, Offset(width * 0.9f, height * 0.15f))
            up(1)
            up(0)
        }
        compose.onNodeWithTag("fc.composer.input").assertIsFocused()
        assertTrue(imeVisible())
        val editorBounds = compose.onNodeWithTag("fc.composer.input").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(0, editorBounds.center)
            moveTo(0, Offset(width * 0.9f, height * 0.15f))
            up(0)
        }
        compose.onNodeWithTag("fc.composer.input").assertIsFocused()
        assertTrue(imeVisible())
    }

    @Test fun onlyBrandTextClickOpensEditorAndLogoRemainsInert() {
        showHome()
        compose.onNodeWithTag("finecat.brandLogo").performTouchInput {
            click()
            longClick()
        }
        compose.onNodeWithText("自定义首页品牌").assertDoesNotExist()
        compose.onNodeWithTag("finecat.brandTitle").performTouchInput { longClick() }
        compose.onNodeWithText("自定义首页品牌").assertDoesNotExist()
        compose.onNodeWithTag("finecat.brandTitle").performTouchInput { click(Offset(center.x, -2f)) }
        compose.onNodeWithText("自定义首页品牌").assertDoesNotExist()
        compose.onNodeWithTag("finecat.brandTitle").performTouchInput { click() }
        compose.onNodeWithText("自定义首页品牌").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("finecat.brandTagline").performTouchInput { click() }
        compose.onNodeWithText("自定义首页品牌").assertExists()
    }

    @Test fun deletingBothBrandLinesPersistsAndKeepsAnEditingEntry() {
        showHome()
        compose.onNodeWithTag("finecat.brandTitle").performClick()
        compose.onNodeWithTag("finecat.heroTitleField").performTextReplacement("   ")
        compose.onNodeWithTag("finecat.heroTaglineField").performTextReplacement("")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { holder.heroTitle == "" && holder.heroTagline == "" }
        val saved = runBlocking { preferences.load() }
        assertEquals("", saved.heroTitle)
        assertEquals("", saved.heroTagline)
        compose.onNodeWithTag("finecat.brandTitle").assertDoesNotExist()
        compose.onNodeWithTag("finecat.brandTagline").assertDoesNotExist()
        compose.onNodeWithTag("finecat.brandLogo").performTouchInput { click(); longClick() }
        compose.onNodeWithText("自定义首页品牌").assertDoesNotExist()
        compose.onNodeWithTag("finecat.brandEdit").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("finecat.heroTitleField").assertTextEquals("")
        compose.onNodeWithTag("finecat.heroTaglineField").assertTextEquals("")
        compose.onNodeWithTag("finecat.heroTitleField").performTextReplacement("重新添加")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { holder.heroTitle == "重新添加" }
        compose.onNodeWithTag("finecat.brandTitle").assertTextEquals("重新添加")
        compose.onNodeWithTag("finecat.brandEdit").assertDoesNotExist()
    }

    @Test fun drawerThemeIconTogglesDirectlyWithoutSheet() {
        showHome()
        compose.runOnIdle { holder.updateAppearanceMode("light") }
        compose.waitUntil(5_000) { holder.appearanceMode == "light" }
        compose.onNodeWithTag("finecat.menu").performClick()
        compose.onNodeWithTag("finecat.drawerTheme").performTouchInput { click() }
        compose.waitUntil(5_000) { holder.appearanceMode == "dark" }
        compose.waitUntil(5_000) { runBlocking { preferences.load().appearanceMode == "dark" } }
        compose.onNodeWithTag("finecat.themeSheet").assertDoesNotExist()
        compose.onNodeWithTag("finecat.drawerSearch").assertIsDisplayed()
        compose.onNodeWithTag("finecat.drawerTheme").performTouchInput { click() }
        compose.waitUntil(5_000) { holder.appearanceMode == "light" }
        compose.waitUntil(5_000) { runBlocking { preferences.load().appearanceMode == "light" } }
        compose.onNodeWithTag("finecat.themeSheet").assertDoesNotExist()
        compose.onNodeWithTag("finecat.drawerSearch").assertIsDisplayed()
    }
}
