package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FineCatApp/FcComposer 的「实际 Compose 点按」回归：走真实 FineCatApp 组合 + 真实
 * facade/projection 链路（FakeTransport/fake graph 组装，同 AndroidAppGraphFakeIntegration）。
 * 断言的是点击后的真实产物（holder.pendingDispatch / transport 帧），不是 sendButtonStops
 * 纯逻辑——后者由 JVM 的 FcSendButtonTest 覆盖。
 *
 * 覆盖：
 * - streaming 且有草稿：点发送=吸附待派发（capturePendingDispatch），不发 prompt/abort；
 * - streaming+compacting 且有草稿：停止优先（压缩中 pi 会拒 prompt），点发送位发 abort；
 * - streaming 且草稿为空：点发送位=停止，发 abort，不产生吸附。
 *
 * 未验证（本环境不可靠驱动，明确保留）：带图消息的可见附件请求/队列经 FineCatApp
 * 可见性上报的端到端；切会话/上滑历史后流式不抢滚动位（需真实手势与列表布局时序）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatConversationRegressionDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var transport: FakeTransport
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var graph: AndroidAppGraph

    /** graph 无 close API：测试后取消两个 scope 并关闭 holder，阻断跨测试的协程/连接残留。 */
    @After
    fun tearDownGraph() {
        if (this::graph.isInitialized) {
            graph.stateHolder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    /**
     * fake graph + 已连接 + fixture 会话上屏，并让会话进入 streaming（pi agent_start
     * 经 PiEventNormalizer 归一为 turn/start）。[compacting] 为真时叠加 compaction_start。
     */
    private fun setUpStreamingSession(draft: String, compacting: Boolean) {
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
        holder = graph.stateHolder
        runBlocking { holder.loadFixtureAndAwaitForTest() }
        runBlocking { waitUntil { transport.specs.isNotEmpty() } }
        transport.open()
        transport.receive(
            "{\"kind\":\"hello\",\"authenticated\":true,\"protocol\":3,\"capabilities\":[\"sessions\",\"subscribe\",\"message\",\"abort\",\"history\",\"state\",\"models\",\"name\"]}"
        )
        runBlocking { waitUntil { holder.gatewayState.connection.name == "CONNECTED" } }

        rule.setContent { FineCatApp(stateHolder = holder) }
        rule.waitUntil(5_000) { holder.snapshot.selectedSessionId == "android-demo" }

        // 必须是真实线上帧：kind/event 骨架 + pi 原生 AgentSessionEvent 类型。
        // PiEventNormalizer 只认 pi 原生名（agent_start → turn/start，
        // compaction_start → compact/start），直接灌内部分归一事件会被整帧忽略。
        transport.receive(
            """{"kind":"event","sessionId":"android-demo","seq":11,"time":11,"event":{"type":"agent_start"}}"""
        )
        if (compacting) {
            transport.receive(
                """{"kind":"event","sessionId":"android-demo","seq":12,"time":12,"event":{"type":"compaction_start","reason":"auto"}}"""
            )
        }
        rule.waitUntil(5_000) {
            val sessionState = holder.snapshot.sessionStates["android-demo"]
            sessionState?.isStreaming == true && (!compacting || sessionState.isCompacting)
        }

        if (draft.isNotEmpty()) {
            rule.onNodeWithTag("fc.composer.input").performTextInput(draft)
            rule.waitUntil(5_000) { holder.messageDraft == draft }
        }
        rule.waitForIdle()
    }

    @Test
    fun streamingWithDraft_sendTap_capturesPendingDispatchAndSendsNoPrompt() {
        setUpStreamingSession(draft = "排队草稿", compacting = false)

        // streaming 且有草稿时发送位仍是「发送」：点击=吸附待派发，不是停止
        rule.onNodeWithContentDescription("发送").performClick()

        rule.waitUntil(5_000) { holder.pendingDispatch != null }
        assertEquals("排队草稿", holder.pendingDispatch?.draft)
        assertEquals("", holder.messageDraft)
        rule.onNodeWithTag("finecat.pendingBar").assertExists()
        // 吸附路径不发 prompt、不误触 abort
        assertTrue(transport.payloadsOfType("message").isEmpty())
        assertTrue(transport.sentTypes.none { it == "abort" })
    }

    @Test
    fun streamingAndCompacting_withDraft_stopTakesPriority() {
        setUpStreamingSession(draft = "压缩中草稿", compacting = true)

        // 压缩中 pi 会拒 prompt：即使有草稿也不得吸附，发送位保持「停止生成」
        rule.onNodeWithContentDescription("停止生成").performClick()

        rule.waitUntil(5_000) { transport.sentTypes.contains("abort") }
        assertNull(holder.pendingDispatch)
        assertEquals("压缩中草稿", holder.messageDraft)
        assertTrue(transport.payloadsOfType("message").isEmpty())
    }

    @Test
    fun streamingWithEmptyDraft_stopTap_sendsAbort() {
        setUpStreamingSession(draft = "", compacting = false)

        // 空草稿：点击发送位=停止，发 abort，不产生吸附
        rule.onNodeWithContentDescription("停止生成").performClick()

        rule.waitUntil(5_000) { transport.sentTypes.contains("abort") }
        assertNull(holder.pendingDispatch)
        assertTrue(transport.payloadsOfType("message").isEmpty())
    }
}