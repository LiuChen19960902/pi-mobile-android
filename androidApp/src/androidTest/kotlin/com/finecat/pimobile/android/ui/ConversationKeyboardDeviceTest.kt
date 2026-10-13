package com.finecat.pimobile.android.ui

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import com.finecat.pimobile.android.AndroidSharedStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ConversationKeyboardDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var holderToClose: AndroidSharedStateHolder? = null

    @Before
    fun configureWindow() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { host ->
            host.onActivity { activity ->
                // 先完成生产窗口配置，再挂载 Compose，避免 IME insets 在首帧测量中途到达。
                activity.enableEdgeToEdge()
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            holderToClose?.close()
        }
    }

    @Test
    fun inputFocusMovesLatestMessageAboveImeAndTimelineTapHidesIme() {
        val fixture = showConversation()
        val latestMessage = compose.onNode(
            hasTestTag("user-text-bubble") and hasText("末尾标记", substring = true)
        )
        latestMessage.assertIsDisplayed()
        val messageTopBeforeIme = latestMessage.fetchSemanticsNode().boundsInRoot.top

        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        waitForIme(fixture.view, visible = true)
        compose.waitUntil(timeoutMillis = 5_000) {
            latestMessage.fetchSemanticsNode().boundsInRoot.top < messageTopBeforeIme - 100f &&
                latestMessage.getUnclippedBoundsInRoot().bottom <=
                compose.onNodeWithTag("composer-input").getUnclippedBoundsInRoot().top
        }
        latestMessage.assertIsDisplayed()
        val messageBottom = latestMessage.getUnclippedBoundsInRoot().bottom
        val composerTop = compose.onNodeWithTag("composer-input").getUnclippedBoundsInRoot().top
        assertTrue("末条完整正文应在输入栏上方：bottom=$messageBottom composerTop=$composerTop",
            messageBottom <= composerTop)
        println("IME-BOUNDS messageBottom=$messageBottom composerTop=$composerTop")

        compose.onNodeWithTag("conversation-timeline").performTouchInput {
            click(Offset(center.x, 40f))
        }

        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        waitForIme(fixture.view, visible = false)
    }

    @Test
    fun successfulSendClearsFocusAndHidesIme() {
        val fixture = showConversation()
        compose.runOnIdle { fixture.holder.messageDraft = "你好" }
        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        waitForIme(fixture.view, visible = true)
        val submission = compose.runOnIdle {
            fixture.holder.captureMessageSubmissionForTest()
        }

        compose.runOnIdle {
            fixture.holder.applyMessageSendResultForTest(submission, sent = true)
        }

        compose.onNodeWithTag("composer-input").assertIsNotFocused()
        waitForIme(fixture.view, visible = false)
    }

    /**
     * 发送绑定前后“同一页继续”的设备回归：生产里首条 message 由 sent 帧自动
     * 绑定会话，这里注入相同的 sent 帧（不新起导航）。断言绑定后
     * 三 tab 与只读工作区行轻量出现、composer 位置稳定、未发送草稿保留——
     * 都是真实组合产物，不是 helper 自证。
     */
    @Test
    fun sessionBindingKeepsComposerBoundsWorkspaceLineAndDraft() {
        val holder = AndroidSharedStateHolder()
        holderToClose = holder
        scenario!!.onActivity { activity ->
            activity.setContent {
                PiTheme {
                    PiProductApp(stateHolder = holder, onPickImage = {})
                }
            }
        }
        compose.waitForIdle()
        // 空白首页：工作区行 + composer。
        compose.onNodeWithTag("home-workspace-line").assertIsDisplayed()
        compose.onNodeWithTag("pi-animated-background").assertIsDisplayed()
        compose.onNodeWithTag("home-background-decoration", useUnmergedTree = true).assertIsDisplayed()
        val composerBefore = compose.onNodeWithTag("composer-input")
            .fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { holder.messageDraft = "未发送草稿" }
        compose.waitForIdle()

        compose.runOnIdle {
            holder.wirePayload =
                """{"kind":"sessions","items":[{"id":"bound-session","modified":"2026-09-20T12:00:00.000Z","running":false,"cwd":"/tmp/bound","messageCount":1,"firstMessage":"测试会话"}]}"""
            holder.submitWirePayload()
            holder.wirePayload = """{"kind":"sent","sessionId":"bound-session"}"""
            holder.submitWirePayload()
            assertTrue("sent 应完成会话绑定", holder.snapshot.selectedSessionId == "bound-session")
            assertTrue("模拟帧应符合协议", holder.platformError == null)
        }
        compose.waitForIdle()

        // 同一壳层切到会话态：三 tab 轻量出现，工作区行仍在（只读 cwd）。
        compose.onNodeWithTag("conversation-screen").assertIsDisplayed()
        compose.onNodeWithTag("pi-animated-background").assertIsDisplayed()
        compose.onNodeWithTag("home-background-decoration", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("home-brand-title", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasTestTag("conversation-title") and hasText("测试会话")).assertIsDisplayed()
        compose.onNode(hasContentDescription("轨迹")).assertIsDisplayed()
        compose.onNodeWithTag("home-workspace-line").assertIsDisplayed()
        compose.onNode(hasContentDescription("会话列表")).assertIsDisplayed()

        // composer 底部（输入区在屏幕上的位置）不因绑定跳动。
        val composerAfter = compose.onNodeWithTag("composer-input")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "composer bottom should stay stable, before=${composerBefore.bottom} after=${composerAfter.bottom}",
            abs(composerAfter.bottom - composerBefore.bottom) < 8f
        )
        // 草稿保留：绑定/切换会话不清输入框。
        compose.onNode(hasText("未发送草稿")).assertIsDisplayed()
    }

    private fun showConversation(): Fixture {
        val holder = AndroidSharedStateHolder()
        holderToClose = holder
        lateinit var view: View
        scenario!!.onActivity { activity ->
            activity.setContent {
                view = LocalView.current
                PiTheme {
                    ConversationScreen(
                        stateHolder = holder,
                        onPickImage = {},
                        // 会话页顶栏入口已改成抽屉与「+」，测试不驱动这两条。
                        onOpenDrawer = {},
                        onNewSession = {}
                    )
                }
            }
        }
        compose.waitForIdle()

        // 先挂载空白首页，再选会话并让空时间线完成布局，模拟生产中先收到 sessions、
        // 随后收到 history 的异步顺序；避免把完整历史塞进 Compose 首次测量。
        compose.runOnIdle {
            holder.wirePayload =
                """{"kind":"sessions","items":[{"id":"keyboard-session","modified":"2026-09-20T12:00:00.000Z","running":false,"cwd":"/tmp/keyboard","messageCount":14,"firstMessage":"键盘测试"}]}"""
            holder.submitWirePayload()
            holder.selectSession("keyboard-session")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("会话选择应先渲染空时间线", "keyboard-session", holder.snapshot.selectedSessionId)
            assertTrue("history 到达前时间线应为空", holder.snapshot.conversation.isEmpty())
            val messages = (0 until 14).joinToString(",") { index ->
                val text = if (index == 13) {
                    "末尾标记 ${"最后一条内容".repeat(8)}"
                } else {
                    "第${index + 1}条消息 ${"用于填充会话列表".repeat(8)}"
                }
                """{"role":"user","content":[{"type":"text","text":"$text"}]}"""
            }
            val entryIds = (0 until 14).joinToString(",") { "\"keyboard-$it\"" }
            holder.wirePayload =
                """{"kind":"history","sessionId":"keyboard-session","messages":[$messages],"entryIds":[$entryIds],"hasMore":false,"oldestEntryId":"keyboard-0"}"""
            holder.submitWirePayload()
            assertEquals("夹具必须实际投影 14 条消息", 14, holder.snapshot.conversation.size)
            assertTrue("夹具末条必须存在", holder.snapshot.conversation.last().text.contains("末尾标记"))
            assertTrue("历史应加载完成且无错误", !holder.snapshot.selectedHistoryIsLoading &&
                holder.snapshot.selectedHistoryError == null && holder.platformError == null)
        }
        compose.waitForIdle()
        return Fixture(holder, view)
    }

    private fun waitForIme(view: View, visible: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
        }
    }

    private data class Fixture(
        val holder: AndroidSharedStateHolder,
        val view: View
    )
}
