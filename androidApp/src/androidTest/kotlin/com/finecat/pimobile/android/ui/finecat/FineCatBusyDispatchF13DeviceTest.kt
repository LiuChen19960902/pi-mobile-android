package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F13 · R4「忙时 steer/followUp/撤回」的真实 Compose 回归。
 *
 * 补充 `FineCatApp` 中 `PendingDispatchBar`（取回 / 排队下轮 / 注入本轮）与
 * `finecat.queued` 服务端队列行的真实触控和协议验证；
 * `FineCatConversationRegressionDeviceTest#streamingWithDraft_sendTap_capturesPendingDispatchAndSendsNoPrompt`
 * 只对该 bar `assertExists`，没有核对 wire 的 `mode`，也没验「取回」把草稿放回编辑器且不发 prompt。
 * 本类补这两条，属回归收口，不是新功能。
 *
 * 组装与 FineCatConversationRegressionDeviceTest 同口径：AndroidAppGraph 全套 override
 * （FakeTransport/FakePreferences/FakeCredentials/FakeCache/FakeNetwork/FakeClock）
 * + createComposeRule + 真实 FineCatApp；只走生产组合与生产可见性上报，
 * 不反射 private、不调测试专用生产接口、不用 fake 兜底生产。
 *
 * 判别力（断言登记进 asserts.tsv，逐字等值）：
 * - `wire.*` 取**线上原始 JSON** 的 `mode`/`text`，不是界面文案；
 * - 「取回」的判据是 `editor.draftBack=true` **且** `wire.messages=0`——
 *   只看编辑器有字不能证明没有顺手发出一条 prompt；
 * - 每条用例独立 graph/holder，@After 关 holder + 取消两个 scope。
 *
 * 未覆盖（本环境不假装）：真实 pi/厂商网关下的 steer 与 followUp 行为差异、
 * 带图待派发条的真实相册选择、压缩期派发（由 R3 的停���优先用例覆盖）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatBusyDispatchF13DeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    /** F5 观察窗：草稿恢复后再等这么久才判「线上无 message 帧」。 */
    private companion object {
        const val OBSERVE_WINDOW_MS = 1_500L
    }

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
     * 建 fake graph → hello 连接 → 真实 FineCatApp 上屏 → fixture 会话进入 streaming
     * （agent_start 经 PiEventNormalizer 归一为 turn/start）→ 输入 [draft] 并吸附。
     *
     * 返回 (transport, holder)，供用例逐字核对 wire。
     */
    private fun setUpBusySessionAndCapture(draft: String): Pair<FakeTransport, AndroidSharedStateHolder> {
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
        runBlocking { h.loadFixtureAndAwaitForTest() }
        runBlocking { waitUntil { t.specs.isNotEmpty() } }
        t.open()
        t.receive(
            "{\"kind\":\"hello\",\"authenticated\":true,\"protocol\":3,\"capabilities\":[\"sessions\",\"subscribe\",\"message\",\"abort\",\"history\",\"state\",\"models\",\"name\"]}"
        )
        runBlocking { waitUntil { h.gatewayState.connection.name == "CONNECTED" } }

        rule.setContent { FineCatApp(stateHolder = h) }
        rule.waitUntil(5_000) { h.snapshot.selectedSessionId == "android-demo" }

        t.receive(
            """{"kind":"event","sessionId":"android-demo","seq":21,"time":21,"event":{"type":"agent_start"}}"""
        )
        rule.waitUntil(5_000) { h.snapshot.sessionStates["android-demo"]?.isStreaming == true }

        rule.onNodeWithTag("fc.composer.input").performTextInput(draft)
        rule.waitUntil(5_000) { h.messageDraft == draft }
        rule.waitForIdle()

        // busy + 有草稿：点发送位 = 吸附待派发（不是停止）
        // 发送键无 testTag，按既有回归同口径用 contentDescription 定位
        rule.onNodeWithContentDescription("发送").performClick()
        rule.waitUntil(5_000) { h.pendingDispatch != null }
        rule.waitForIdle()
        return t to h
    }

    /** 证据行写入外部 files（capture 按用例拉取）；不写则该用例无取证。 */
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
     * F13-R4-STEER：忙时吸附后「注入本轮」发出 `mode=steer`，正文逐字等于吸附的草稿，
     * 派发成功后吸附条收起。
     *
     * 独立成用例而非与 followUp 拼在一段：`createComposeRule` 每用例只允许一次
     * `setContent`（首轮实测第二次抛 `ComponentActivity has already set content`），
     * 换 graph 就必须换用例。
     */
    @Test
    fun busyDraft_steerButton_sendsSteerModeAndClosesBar() {
        val (t, h) = setUpBusySessionAndCapture("忙时草稿A")
        rule.onNodeWithTag("finecat.pendingBar").assertExists()

        rule.onNodeWithText("注入本轮").performClick()

        rule.waitUntil(5_000) { t.payloadsOfType("message").isNotEmpty() }
        rule.waitForIdle()
        assertEquals(1, t.payloadsOfType("message").size)
        assertEquals("steer", t.payloadsOfType("message").first().getValue("mode").jsonPrimitive.content)
        assertEquals("忙时草稿A", t.payloadsOfType("message").first().getValue("text").jsonPrimitive.content)
        // 派发成功后吸附条收起（settlePendingDispatch 只在 sent 时清 pending）
        rule.waitUntil(5_000) { h.pendingDispatch == null }
        rule.onNodeWithTag("finecat.pendingBar").assertDoesNotExist()

        saveShot("f13-r4-steer.png")
        listOf(
            "wire.steerMode" to "steer",
            "wire.steerText" to "忙时草稿A",
            "wire.steerMessageCount" to "1",
            "wire.barGoneAfterSteer" to "true",
        ).forEach { (name, value) -> emitAssert(name, value) }
    }

    /**
     * F13-R4-FOLLOWUP：忙时吸附后「排队下轮」发出 `mode=followUp`（不是 steer）。
     */
    @Test
    fun busyDraft_followUpButton_sendsFollowUpMode() {
        val (t, _) = setUpBusySessionAndCapture("忙时草稿B")

        rule.onNodeWithText("排队下轮").performClick()

        rule.waitUntil(5_000) { t.payloadsOfType("message").isNotEmpty() }
        rule.waitForIdle()
        assertEquals(1, t.payloadsOfType("message").size)
        assertEquals("followUp", t.payloadsOfType("message").first().getValue("mode").jsonPrimitive.content)
        assertEquals("忙时草稿B", t.payloadsOfType("message").first().getValue("text").jsonPrimitive.content)

        saveShot("f13-r4-followup.png")
        listOf(
            "wire.followUpMode" to "followUp",
            "wire.followUpText" to "忙时草稿B",
            "wire.followUpMessageCount" to "1",
        ).forEach { (name, value) -> emitAssert(name, value) }
    }

    /**
     * F13-R4-TAKEBACK：「取回」把草稿放回编辑器且**不发任何 prompt**。
     * 判据同时看编辑器与线上 message 帧数——只看编辑器有字不能证明没顺手发出一条。
     */
    @Test
    fun busyDraft_takeBack_restoresEditorAndSendsNoPrompt() {
        val (t, h) = setUpBusySessionAndCapture("取回草稿C")

        // 吸附后编辑器已清空、条在屏上
        rule.waitUntil(5_000) { h.messageDraft == "" }
        rule.onNodeWithTag("finecat.pendingBar").assertExists()

        rule.onNodeWithText("取回").performClick()

        rule.waitUntil(5_000) { h.messageDraft == "取回草稿C" }
        rule.waitForIdle()

        // 观察窗：草稿恢复后再静置一拍才判空（审查 F5）。原来紧跟恢复就取快照，
        // 漏检「先恢复草稿、稍后才异步发出一条 prompt」的形态。
        // 用挂钟睡眠而不是 rule.waitUntil —— 后者的条件恒 false 时会抛
        // ComposeTimeoutException（首轮实测），根本到不了断言。
        Thread.sleep(OBSERVE_WINDOW_MS)
        rule.waitForIdle()

        // 关键反证：观察窗结束时线上仍没有任何 message 帧（取回不是发送）
        assertTrue(t.payloadsOfType("message").isEmpty())
        assertNull(h.pendingDispatch)
        rule.onNodeWithTag("finecat.pendingBar").assertDoesNotExist()
        // 草稿回到输入框且屏上可见（不是只改了内存态）
        rule.onNodeWithTag("fc.composer.input").assertIsDisplayed()

        saveShot("f13-r4-takeback.png")
        listOf(
            "editor.draftBack" to "true",
            "editor.inputStillDisplayed" to "true",
            "wire.messages" to "0",
            "pendingBar.afterTakeback" to "absent",
        ).forEach { (name, value) -> emitAssert(name, value) }
    }
}
