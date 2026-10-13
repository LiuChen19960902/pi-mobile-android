package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.AttachmentLoadState
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.base64TinyPng
import com.finecat.pimobile.android.onMain
import com.finecat.pimobile.android.waitUntil
import com.finecat.pimobile.shared.gateway.gatewayAttachmentCacheKey
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher

/**
 * FineCatApp 时间线两个真实生产 Compose 回归（合成网关，无真实网络/密钥/prefs/设备图）。
 *
 * 组装与 [FineCatProviderRegressionDeviceTest] / [com.finecat.pimobile.android.AndroidAppGraphFakeIntegrationDeviceTest]
 * 同口径：AndroidAppGraph 全套 override（FakeTransport/FakePreferences/FakeCredentials/
 * FakeCache/FakeNetwork/FakeClock）+ `createComposeRule` + 真实 `FineCatApp`。只走生产组合与
 * 生产可见性上报，不调 holder.updateVisibleAttachments / 不反射 private / 不加生产测试接口。
 *
 * 覆盖：
 * 1. 选中会话时对话为空（初始空会话）→ 真实 history 应答 + 真实 pi message_end(user) 事件帧带图到达 →
 *    顶部存在「加载更早消息…」行（older 分页在途）时，屏内可见必须发出 attachment 请求，
 *    且三元寻址 entryId/imageIndex 命中图所在条目（key 映射不被 loading 行错位），随后缓存键
 *    携带真实 attachmentId。
 * 2. 30 条合成长对话：首次贴底 → 用户实际 swipeDown 阅读历史（离开底部，「跳到最新」出现）→
 *    追加尾部 pi message_end(user) 事件不抢视口（屏内可见条目集合逐字保持）→ 点「跳到最新」恢复贴底。
 *
 * 明确未覆盖（按本轮约定保留）：真实图片选择器/真实媒体文件与 IME 输入；真实网关/真机手势节流
 * 差异；分页失败/重试与 branch 切换下的贴底。
 *
 * 每个用例独立 graph/holder，@After 关 holder + 取消两个 scope；失败瞬间保存合成截图。
 */
@RunWith(AndroidJUnit4::class)
class FineCatTimelineRegressionDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    /** 失败证据：完整语义树入 logcat + 失败瞬间截图（只落合成状态）。 */
    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("FineCatTimeline") }
            runCatching {
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                val dir = File(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "finecat-timeline-failures",
                ).apply { mkdirs() }
                File(dir, "${description.methodName}.png").outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
    }

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null

    @After
    fun tearDownGraph() {
        runCatching { holder?.close() }
        graph?.gatewayScope?.cancel()
        graph?.applicationScope?.cancel()
        holder = null
        graph = null
    }

    private fun newGraph(transport: FakeTransport, cache: FakeCache): AndroidAppGraph = AndroidAppGraph(
        application = ApplicationProvider.getApplicationContext<Application>(),
        transportOverride = transport,
        preferencesOverride = FakePreferences(),
        credentialStoreOverride = FakeCredentials,
        attachmentCacheOverride = cache,
        networkMonitorOverride = FakeNetwork,
        clockOverride = FakeClock,
    )

    /** 建图、连上 hello、注入单个合成会话（协议 sessions 应答字段为 items）。 */
    private fun connectWithOneSession(transport: FakeTransport, cache: FakeCache): AndroidSharedStateHolder {
        graph = newGraph(transport, cache)
        val stateHolder = graph!!.stateHolder
        holder = stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
            transport.receive(SESSIONS_ONE)
            waitUntil { stateHolder.snapshot.sessions.any { it.id == SESSION } }
        }
        return stateHolder
    }

    // ---- 用例 1：loading 行在场时的屏内带图附件请求 ----

    @Test
    fun visibleImage_withHistoryLoadingRow_sendsAttachmentRequestForOwningEntry() {
        val transport = FakeTransport()
        val cache = FakeCache()
        val stateHolder = connectWithOneSession(transport, cache)

        compose.setContent { FineCatApp(stateHolder = stateHolder) }

        runBlocking {
            onMain { stateHolder.selectSession(SESSION) }
            waitUntil { stateHolder.snapshot.selectedSessionId == SESSION }
            // 初始空会话：选中后尚未有任何对话条目
            assertTrue(stateHolder.snapshot.conversation.isEmpty())

            waitUntil { transport.sentTypes.count { it == "history" } >= 1 }
            transport.receive(initialHistoryOneText())
            waitUntil { stateHolder.snapshot.conversation.any { it.text == "旧文本消息" } }
            waitUntil { !stateHolder.snapshot.selectedHistoryIsLoading }

            // 触发更早分页且不回应 → 列表顶部出现「加载更早消息…」行
            onMain { stateHolder.loadOlderHistory() }
            waitUntil { stateHolder.snapshot.selectedHistoryIsLoadingOlder }
            waitUntil { transport.sentTypes.count { it == "history" } >= 2 }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("加载更早消息…").fetchSemanticsNodes().isNotEmpty()
        }

        // 带图消息经真实事件帧到达：帧顶层 entryId 即 §4.22 取图寻址身份
        transport.receive(imageEvent())
        compose.waitUntil(5_000) {
            stateHolder.snapshot.conversation.any { item ->
                item.images.any { it.attachmentId == IMAGE_ID }
            }
        }
        // 取图由布局后的可见集合 effect 驱动，等待时也必须推进 Compose 重组/布局。
        compose.waitUntil(5_000) { transport.payloadsOfType("attachment").isNotEmpty() }

        val request = transport.payloadsOfType("attachment").last()
        assertEquals(SESSION, request.getValue("sessionId").jsonPrimitive.content)
        // loading 行（列表 index 0）在场也不能把取图寻址错映射到别条
        assertEquals(ENTRY_ID, request.getValue("entryId").jsonPrimitive.content)
        assertEquals(0, request.getValue("imageIndex").jsonPrimitive.int)

        runBlocking {
            transport.receive(attachmentResponse())
            waitUntil { stateHolder.attachmentStates[IMAGE_ID] == AttachmentLoadState.LOADED }
        }
        // 请求帧本身按 entryId+序号寻址，attachmentId 由缓存键最终确证
        assertTrue(gatewayAttachmentCacheKey(SESSION, IMAGE_ID) in cache.values)
    }

    // ---- 用例 2：阅读历史后追加尾部不抢视口，跳最新恢复贴底 ----

    @Test
    fun readHistory_tailAppendKeepsViewport_thenJumpRestoresBottom() {
        val transport = FakeTransport()
        val stateHolder = connectWithOneSession(transport, FakeCache())

        compose.setContent { FineCatApp(stateHolder = stateHolder) }

        runBlocking {
            onMain { stateHolder.selectSession(SESSION) }
            waitUntil { stateHolder.snapshot.selectedSessionId == SESSION }
            waitUntil { transport.sentTypes.count { it == "history" } >= 1 }
            transport.receive(longHistory(MESSAGE_COUNT))
            waitUntil { stateHolder.snapshot.conversation.size >= MESSAGE_COUNT }
        }

        // 首次贴底：末条上屏且无「跳到最新」
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(messageText(MESSAGE_COUNT - 1)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(messageText(MESSAGE_COUNT - 1)).assertIsDisplayed()

        // 用户实际 swipeDown 阅读历史：半屏步长上滑，直到末条离开视口（离开底部）
        var swipes = 0
        while (messageText(MESSAGE_COUNT - 1) in visibleMessageTexts() && swipes < 3) {
            compose.onNodeWithTag("finecat.list").performTouchInput {
                swipe(
                    start = Offset(centerX, top + height * 0.25f),
                    end = Offset(centerX, top + height * 0.75f),
                    durationMillis = 200,
                )
            }
            compose.waitForIdle()
            swipes++
        }
        // 离开底部 → 「跳到最新」出现
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isNotEmpty()
        }
        val visibleBefore = visibleMessageTexts()
        assertTrue(messageText(MESSAGE_COUNT - 1) !in visibleBefore)

        // 追加尾部消息：不得抢视口，原屏内可见条目集合逐字保持
        runBlocking {
            transport.receive(tailEvent())
            waitUntil { stateHolder.snapshot.conversation.any { it.text == TAIL_TEXT } }
        }
        compose.waitForIdle()
        assertEquals(visibleBefore, visibleMessageTexts())
        assertTrue(messageText(MESSAGE_COUNT - 1) !in visibleMessageTexts())

        // 点「跳到最新」：恢复贴底（按钮消失）且尾条上屏
        compose.onNodeWithTag("finecat.jump").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("finecat.jump").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(TAIL_TEXT).assertIsDisplayed()
    }

    /**
     * 屏内可见的合成用户消息文本：按 list 语义 bounds 与每条消息 bounds 求交，
     * 记录的是实际落在视口内的可见条目（不反射 private LazyListState）。
     */
    private fun visibleMessageTexts(): Set<String> {
        val viewport = compose.onNodeWithTag("finecat.list").fetchSemanticsNode().boundsInRoot
        return (0 until MESSAGE_COUNT).mapNotNull { index ->
            val text = messageText(index)
            val node = compose.onAllNodesWithText(text).fetchSemanticsNodes().firstOrNull()
                ?: return@mapNotNull null
            val bounds = node.boundsInRoot
            text.takeIf { bounds.bottom > viewport.top && bounds.top < viewport.bottom }
        }.toSet()
    }

    private companion object {
        const val SESSION = "android-demo"
        const val MESSAGE_COUNT = 30
        const val ENTRY_ID = "e-img"
        const val IMAGE_ID = "reg-visible-1"
        const val TAIL_TEXT = "尾部追加消息"

        const val HELLO =
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","history","state","attachment"]}"""

        const val SESSIONS_ONE =
            """{"kind":"sessions","items":[{"id":"android-demo","cwd":"/tmp/synth","messageCount":1,"firstMessage":"合成时间线","running":false}]}"""

        fun messageText(index: Int): String = "reg-msg-$index"

        /** 真实 history 应答：一条纯文本条目 + 更早页游标（供 older 分页触发 loading 行）。 */
        fun initialHistoryOneText(): String =
            """{"kind":"history","sessionId":"$SESSION","messages":[{"role":"user","content":[{"type":"text","text":"旧文本消息"}]}],"entryIds":["e-old"],"hasMore":true,"oldestEntryId":"e-old","bytes":48}"""

        /** 真实事件帧：pi message_end(user) 带 image 块，帧顶层 entryId 是取图寻址身份。 */
        fun imageEvent(): String =
            """{"kind":"event","sessionId":"$SESSION","seq":9001,"time":9001,"entryId":"$ENTRY_ID","event":{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"带图消息"},{"type":"image","attachmentId":"$IMAGE_ID","mimeType":"image/png","dataBytes":68}]}}}"""

        fun attachmentResponse(): String {
            val data = base64TinyPng()
            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT).size
            return """{"kind":"attachment","sessionId":"$SESSION","entryId":"$ENTRY_ID","imageIndex":0,"mediaType":"image/png","bytes":$bytes,"dataBase64":"$data"}"""
        }

        /** 真实 history 应答：30 条平行 messages/entryIds 的长对话首页。 */
        fun longHistory(count: Int): String {
            val messages = (0 until count).joinToString(",") { index ->
                """{"role":"user","content":[{"type":"text","text":"${messageText(index)}"}]}"""
            }
            val entries = (0 until count).joinToString(",") { index -> "\"h-$index\"" }
            return """{"kind":"history","sessionId":"$SESSION","messages":[$messages],"entryIds":[$entries],"hasMore":false,"oldestEntryId":"h-0","bytes":$count}"""
        }

        /** 尾部追加：真实 pi message_end(user) 事件，seq 大于 history 合成序。 */
        fun tailEvent(): String =
            """{"kind":"event","sessionId":"$SESSION","seq":9999,"time":9999,"event":{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"$TAIL_TEXT"}]}}}"""
    }
}
