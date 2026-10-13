package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.onMain
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F13 · M7「长会话」的成链回归。
 *
 * 既有 `FineCatTimelineRegressionDeviceTest#readHistory_tailAppendKeepsViewport_thenJumpRestoresBottom`
 * 验证视口保持（30 条合成对话、追加不抢视口、跳到最新恢复贴底）。
 * 本类补充「进入 / 连续流式 / 滚动」的成链观测：
 * 80 条合成对话 + 一次连续 12 chunk 流式 + 真实手势滚动。
 *
 * 记录数据量和耗时，不据此推断帧率：
 * - 只记录**条数**与**各段墙钟耗时**（进入、流式批次、滚动），
 *   **不**记录也不声称帧率、fps 或「无卡顿」这类本环境无法证伪的指标；
 * - 耗时只作观测留档，**不做断言阈值**（模拟器负载差异大，冻结耗时即过拟合本机）。
 *   判据是**关系**：条数正确、流式后仍贴底、能滚到早期条目、草稿与焦点不丢。
 *
 * 编排严格照 `FineCatTimelineRegressionDeviceTest` 的真实顺序（踩过的坑写在这里）：
 * 1. **不调** `loadFixtureAndAwaitForTest()`：它用 fixture 快照覆盖投影，随后到达的
 *    sessions/history 帧会被丢弃（本类首轮实测「会话列表出现」20s 超时）；
 * 2. 建图 → hello → sessions → **setContent** → `selectSession`（生产公开接口，主线程）
 *    → 等客户端发出 history → 回 history。选中会话必须显式调 `selectSession`，
 *    首页默认停在新会话态，不会自动选中；
 * 3. 流式用真实 pi 序列 `agent_start` → `message_start(assistant)` →
 *    `message_update(assistantMessageEvent.text_delta)` ×N → `message_end(assistant, stopReason)`。
 *    **没有 agent_end**，落定由 message_end 归一出的 turn/end 驱动。
 *
 * 断言走生产可见性与线上 wire，不反射 LazyListState、不调测试专用生产接口。
 */
@RunWith(AndroidJUnit4::class)
class FineCatLongSessionF13DeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    private var measureFileReady = false
    private var transport: FakeTransport? = null
    private var holder: AndroidSharedStateHolder? = null
    private var graph: AndroidAppGraph? = null

    @After
    fun tearDownGraph() {
        holder?.let { h -> runCatching { h.close() } }
        graph?.gatewayScope?.cancel()
        graph?.applicationScope?.cancel()
        holder = null
        graph = null
        transport = null
    }

    /**
     * 夹具自带的 `waitUntil` 是 `withTimeout(5_000)` 固定版，超时只抛
     * `TimeoutCancellationException` 且不带「在等什么」，80 条历史这种量级会得到
     * 无法定位的失败。这里用带标签与可读消息的等待：判据不变，只是放宽上限并让失败可诊断。
     */
    private suspend fun awaitOrFail(what: String, condition: () -> Boolean) {
        try {
            withTimeout(AWAIT_MS) { while (!condition()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("等待「$what」超时（$AWAIT_MS ms）", e)
        }
    }

    /** 真实事件帧：seq/time 显式给出，不依赖时钟桩；time=0 故不渲染时间分隔行。 */
    private fun ev(json: String, seq: Int): String =
        """{"kind":"event","sessionId":"$SESSION","seq":$seq,"time":0.0,"event":$json}"""

    // ---------------------------------------------------------------- 取证

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /**
     * 严格等值取证行：check 逐字比对 asserts.tsv 登记的 expected（缺行/不等即 FAIL）。
     * 与 CAPTURE-MEASURE 写同一个文件（capture 用 stat mtime 区分用例后 pull），
     * 但行前缀不同、互不覆盖；每条用例首次写时先删旧文件，避免上轮残留。
     */
    private fun emitAssert(name: String, actual: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val file = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            file.delete()
            measureFileReady = true
        }
        file.appendText("CAPTURE-ASSERT: $name actual=$actual\n")
        android.util.Log.i("F13ASSERT", "$name=$actual")
    }

    /**
     * 取证截图：必须落到外部 files 目录（verify-stage.sh capture 从这里 adb pull）。
     * 直接复制真实 Compose 窗口 Surface；本类取证无 IME 独立窗口，避免无头系统截屏黑屏。
     */
    private fun saveShot(name: String) = rule.saveEvidenceScreenshot(name)


    /**
     * F13-M7-LONG：80 条合成对话进入 → 连续 12 chunk 流式 → 真实手势滚回第 0 条。
     *
     * 判据（关系型）：
     * 1. 进入：80 条全部投影，末条上屏且贴底（无「跳到最新」）；
     * 2. 流式：12 个 chunk 合并进**同一条** assistant 消息（条数只 +1，不是每 chunk 一条），
     *    完整正文落在那一条上；流式结束仍贴底；
     * 3. 滚动：真实 swipe 能把第 0 条滚进视口，且离开底部后「跳到最新」出现；
     * 4. 焦点不丢：滚动与流式后 composer 仍能收草稿。
     */
    @Test
    fun longSession_enterStreamAndScroll_noAnrNoFocusLoss() {
        val t = FakeTransport()
        transport = t
        val g = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = t,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        graph = g
        val h = g.stateHolder
        holder = h

        // —— 1a. 连接并注入单个合成会话（协议 sessions 应答字段为 items）——
        runBlocking {
            awaitOrFail("transport 收到请求") { t.specs.isNotEmpty() }
            t.open()
            t.receive(HELLO)
            awaitOrFail("连接变为 CONNECTED") { h.gatewayState.connection.name == "CONNECTED" }
            t.receive(SESSIONS_ONE)
            awaitOrFail("会话列表出现") { h.snapshot.sessions.any { it.id == SESSION } }
        }

        rule.setContent { FineCatApp(stateHolder = h) }

        // —— 1b. 选中会话并接入 80 条长历史 ——
        val enterMs = run {
            var elapsed = 0L
            runBlocking {
                onMain { h.selectSession(SESSION) }
                awaitOrFail("会话被选中") { h.snapshot.selectedSessionId == SESSION }
                awaitOrFail("客户端发出 history 请求") { t.sentTypes.count { it == "history" } >= 1 }
                elapsed = measureTimeMillis {
                    t.receive(longHistory(MESSAGE_COUNT))
                    awaitOrFail("$MESSAGE_COUNT 条历史投影完成") { h.snapshot.conversation.size >= MESSAGE_COUNT }
                }
            }
            elapsed
        }

        rule.waitUntil(SEMANTIC_WAIT_MS) {
            rule.onAllNodesWithText(messageText(MESSAGE_COUNT - 1)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        // 贴底：末条可见且「跳到最新」不出现
        rule.waitUntil(SEMANTIC_WAIT_MS) {
            rule.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText(messageText(MESSAGE_COUNT - 1)).assertIsDisplayed()
        val projectedAfterEnter = h.snapshot.conversation.size

        // —— 2. 连续流式：12 个 chunk 合并进同一条 assistant 消息 ——
        val streamMs = run {
            var elapsed = 0L
            runBlocking {
                elapsed = measureTimeMillis {
                    t.receive(ev("""{"type":"agent_start"}""", 5001))
                    awaitOrFail("轮次进入 streaming") { h.snapshot.sessionStates[SESSION]?.isStreaming == true }
                    t.receive(ev("""{"type":"message_start","message":{"role":"assistant"}}""", 5002))
                    repeat(CHUNKS) { index ->
                        t.receive(
                            ev(
                                """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"片段$index"}}""",
                                5100 + index,
                            )
                        )
                    }
                    t.receive(
                        ev(
                            """{"type":"message_end","message":{"role":"assistant","stopReason":"stop","content":[{"type":"text","text":${jstr(streamedText())}}]}}""",
                            5200,
                        )
                    )
                    // 落定判据是 pi 原生 agent_settled 归一出的 turn/end
                    // （FineCatConversationF02DeviceTest#injectConversation 同口径）：
                    // 只发 message_end 时轮次仍在 streaming，不会自行落定。
                    t.receive(ev("""{"type":"agent_settled"}""", 5300))
                    awaitOrFail("轮次落定（agent_settled 之后）") { h.snapshot.sessionStates[SESSION]?.isStreaming == false }
                }
            }
            elapsed
        }

        rule.waitForIdle()
        // 同一条逐步更新：条数不因 chunk 增长（相对进入时只 +1 条 assistant）
        val projectedAfterStream = h.snapshot.conversation.size
        assertEquals(projectedAfterEnter + 1, projectedAfterStream)
        // 完整正文落在那一条上（不是散成多条）
        assertTrue(h.snapshot.conversation.any { it.text.contains("片段0") && it.text.contains("片段${CHUNKS - 1}") })
        // 流式结束后仍贴底
        rule.waitUntil(SEMANTIC_WAIT_MS) {
            rule.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isEmpty()
        }

        // —— 3. 真实手势滚动：能回到早期条目 ——
        // 手势方向与步长沿用 FineCatTimelineRegressionDeviceTest（同为「上滑看更早」：
        // start 0.25h → end 0.75h）。只放宽次数上限——80 条比它的 30 条远得多，
        // 半屏步长 3 次够用、12 次不够（首轮实测停在中间，末条第 0 条未进视口）。
        var swipes = 0
        val swipeStart = System.currentTimeMillis()
        // LazyColumn 会预组合屏外节点；存在不等于进入视口，必须以真实显示为停止条件。
        while (swipes < MAX_SWIPES && runCatching {
            rule.onNodeWithText(messageText(0)).assertIsDisplayed()
        }.isFailure) {
            rule.onNodeWithTag("finecat.list").performTouchInput {
                swipe(
                    start = Offset(centerX, top + height * 0.15f),
                    end = Offset(centerX, top + height * 0.85f),
                    durationMillis = 150,
                )
            }
            rule.waitForIdle()
            swipes++
        }
        val scrollMs = System.currentTimeMillis() - swipeStart
        rule.onNodeWithText(messageText(0)).assertIsDisplayed()
        // 离开底部 → 「跳到最新」出现（阅读历史不被拉回）
        rule.waitUntil(SEMANTIC_WAIT_MS) {
            rule.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isNotEmpty()
        }

        // —— 4. 焦点不丢：滚动与流式后 composer 仍能收草稿 ——
        rule.onNodeWithTag("fc.composer.input").performTextInput("长会话后草稿")
        rule.waitUntil(SEMANTIC_WAIT_MS) { h.messageDraft == "长会话后草稿" }

        saveShot("f13-m7-long-session.png")
        listOf(
            "session.messages" to MESSAGE_COUNT.toString(),
            "session.projectedAfterEnter" to projectedAfterEnter.toString(),
            "session.projectedAfterStream" to projectedAfterStream.toString(),
            "stream.chunks" to CHUNKS.toString(),
            "stream.singleEntryMerged" to "true",
            "scroll.reachedFirstMessage" to "true",
            "editor.draftKept" to "true",
        ).forEach { (name, value) -> emitAssert(name, value) }
        // 观测留档（不登记为断言、不同模拟器负载差异大，冻结即过拟合本机）：
        // 进入 80 条 ${enterMs}ms、流式 12 chunk ${streamMs}ms、滚动到首条 ${scrollMs}ms（${swipes} 次手势）。
        android.util.Log.i("F13OBSERVE", "enterMs=$enterMs streamMs=$streamMs scrollMs=$scrollMs swipes=$swipes")
    }

    private companion object {
        const val SESSION = "android-demo"
        const val MESSAGE_COUNT = 80
        const val CHUNKS = 12
        const val MAX_SWIPES = 40
        const val AWAIT_MS = 20_000L
        const val SEMANTIC_WAIT_MS = 15_000L

        const val HELLO =
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","history","state","attachment"]}"""

        const val SESSIONS_ONE =
            """{"kind":"sessions","items":[{"id":"android-demo","cwd":"/tmp/synth","messageCount":80,"firstMessage":"长会话","running":false}]}"""

        fun messageText(index: Int): String = "long-msg-$index"

        fun streamedText(): String = (0 until CHUNKS).joinToString("") { "片段$it" }

        /** 真实 history 应答：N 条平行 messages/entryIds 的长对话首页。 */
        fun longHistory(count: Int): String {
            val messages = (0 until count).joinToString(",") { index ->
                """{"role":"user","content":[{"type":"text","text":"${messageText(index)}"}]}"""
            }
            val entries = (0 until count).joinToString(",") { index -> "\"h-$index\"" }
            return """{"kind":"history","sessionId":"$SESSION","messages":[$messages],"entryIds":[$entries],"hasMore":false,"oldestEntryId":"h-0","bytes":$count}"""
        }

        fun jstr(value: String): String = buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
    }
}
