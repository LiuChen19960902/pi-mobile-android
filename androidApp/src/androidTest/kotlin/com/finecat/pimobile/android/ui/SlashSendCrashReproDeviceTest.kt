package com.finecat.pimobile.android.ui

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.waitUntil
import com.finecat.pimobile.android.onMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 回归守护：发送 "/" 开头消息的完整 UI 链路不崩。
 * 链路：commands 帧填充联想 → draft="/example-cmd " → 点发送 → message 帧 → sent 帧 →
 * applyMessageSendResult（清草稿）→ pill/联想退场重组。pi 原生语义：TUI-only
 * 扩展命令在 RPC/headless 下本地 no-op、无 turn 事件（notify 丢弃），客户端
 * 不得因此崩溃或卡死。真机 BUG-105（FGS connectedDevice 权限缺失崩进程）
 * 由 Manifest 权限修复，此测试守护 UI 层同类回归。
 */
class SlashSendCrashReproDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun slashCommandSendSurvivesSentFrameAndUiRecomposition() = runBlocking {
        val transport = FakeTransport()
        val graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock
        )
        val holder = graph.stateHolder
        waitUntil { transport.specs.isNotEmpty() }
        transport.open()
        transport.receive("""{"kind":"hello","authenticated":true,"protocol":3}""")
        waitUntil { holder.gatewayState.connection.name == "CONNECTED" }

        onMain { holder.loadFixture() }
        onMain { holder.selectSession("android-demo") }
        waitUntil { transport.sentTypes.count { it == "history" } >= 1 }
        // history 应答（真机时序：会话内容先渲染，再拉 commands，再输入发送）
        transport.receive(
            """{"kind":"history","sessionId":"android-demo","messages":[{"role":"user","content":[{"type":"text","text":"之前的一条消息"}]}],"entryIds":["e1"],"hasMore":false,"oldestEntryId":"e1","bytes":64}"""
        )
        compose.setContent {
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
        compose.waitForIdle()

        // commands 帧填充联想清单（真机同款形状：extension + skill 源）
        transport.receive(
            """{"kind":"commands","sessionId":"android-demo","commands":[
                {"name":"example-cmd","description":"示例命令","source":"extension"},
                {"name":"context","description":"Show context token usage breakdown","source":"extension"}
            ]}"""
        )
        waitUntil { holder.slashCommands?.second?.isNotEmpty() == true }

        // 完整复刻真机操作：输入 /example-cmd（联想出现）→ 点发送
        compose.runOnIdle { holder.messageDraft = "/example-cmd " }
        compose.waitForIdle()
        val beforeSend = transport.sentTypes.count { it == "message" }
        compose.onNode(hasContentDescription("发送")).performClick()
        waitUntil { transport.sentTypes.count { it == "message" } > beforeSend }
        val messagePayload = transport.payloadsOfType("message").last()
        assertTrue(
            "draft 文本应随 message 帧发出",
            messagePayload.toString().contains("/example-cmd")
        )

        // 网关回 sent 帧（真机日志：sent 后同秒崩溃）
        transport.receive("""{"kind":"sent","sessionId":"android-demo","mode":"followUp"}""")
        withTimeout(10_000) {
            compose.waitUntil(timeoutMillis = 10_000) { holder.messageDraft.isEmpty() }
        }
        // 撑过 pill/candidates 退场动画窗口（真机在 sent 同秒崩溃）
        delay(3_000)
        assertTrue("UI 应保持可交互且草稿已清空", holder.messageDraft.isEmpty())
        holder.close()
    }
}
