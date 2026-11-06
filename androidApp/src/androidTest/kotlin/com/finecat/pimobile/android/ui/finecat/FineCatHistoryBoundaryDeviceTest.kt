package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
import com.finecat.pimobile.android.onMain
import com.finecat.pimobile.android.waitUntil
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 历史分页边界手感回归。三条可观察行为：
 * 1. 用户朝更早方向滑动、进入离顶不足两屏的预取区就预取下一页，惯性不撞已加载内容的尽头
 *    （治「硬卡」）；按像素距离而非回合数，程序化滚动不触发。
 * 2. 预取在离顶数屏处发起时，加载浮层不显示，不遮住正在读的正文。
 * 3. 到顶等待时的盲文点阵是列表外的浮层（finecat.loadingOlder），出现/收起不改变首回合高度
 *    （治「主体提高」），一旦出现至少驻留 1 秒，切会话时不残留。
 */
@RunWith(AndroidJUnit4::class)
class FineCatHistoryBoundaryDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var graph: AndroidAppGraph? = null
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var transport: FakeTransport

    @After
    fun tearDown() {
        runCatching { holder.close() }
        runCatching { graph?.gatewayScope?.cancel() }
        runCatching { graph?.applicationScope?.cancel() }
        graph = null
    }

    private fun historyFrame(rounds: Int, hasMore: Boolean, tallFrom: Int = Int.MAX_VALUE): String {
        val tallText = (1..90).joinToString("\\n\\n") { "第 $it 段较长的回答内容，用来把整个回合撑得比一个视口还高" }
        val msgs = StringBuilder()
        val ids = StringBuilder()
        for (i in 0 until rounds) {
            if (i > 0) { msgs.append(','); ids.append(',') }
            msgs.append("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"问题 $i\"}]},")
            msgs.append("{\"role\":\"assistant\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"${if (i >= tallFrom) tallText else "回答 $i"}\"}]}")
            ids.append("\"e$i-u\",\"e$i-a\"")
        }
        return "{\"kind\":\"history\",\"sessionId\":\"$SID\",\"messages\":[$msgs],\"entryIds\":[$ids]," +
            "\"hasMore\":$hasMore,\"oldestEntryId\":\"e0-u\",\"bytes\":1024}"
    }

    private fun connect(rounds: Int = 12, tallFrom: Int = Int.MAX_VALUE, firstPage: String? = null) {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            requestTimeoutOverride = 120_000L,
        )
        holder = graph!!.stateHolder
        val sh = holder
        compose.setContent { FineCatApp(stateHolder = sh) }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { sh.gatewayState.connection.name == "CONNECTED" }
        }
        onMain { sh.updateAppearanceMode("light") }
        transport.receive("{\"kind\":\"sessions\",\"items\":[{\"id\":\"$SID\",\"cwd\":\"/home/user/proj\",\"name\":\"分页会话\",\"messageCount\":${rounds * 2},\"running\":false},{\"id\":\"$OTHER_SID\",\"cwd\":\"/home/user/other\",\"name\":\"另一会话\",\"messageCount\":0,\"running\":false}]}")
        runBlocking { waitUntil { sh.snapshot.sessions.any { it.id == SID } } }
        onMain { sh.selectSession(SID) }
        runBlocking {
            waitUntil { sh.snapshot.selectedSessionId == SID }
            waitUntil { transport.payloadsOfType("history").isNotEmpty() }
        }
        transport.receive(firstPage ?: historyFrame(rounds, hasMore = true, tallFrom = tallFrom))
        if (firstPage == null) runBlocking { waitUntil { sh.snapshot.conversation.size >= rounds * 2 } }
        else runBlocking { waitUntil { sh.snapshot.conversation.isNotEmpty() } }
        compose.waitForIdle()
    }

    private fun firstRoundHeight(): Float {
        val list = compose.onNodeWithTag("finecat.list", useUnmergedTree = true).fetchSemanticsNode()
        val first = list.children.first()
        val tag = first.config.getOrNull(SemanticsProperties.TestTag)
        assertTrue("列表首个子节点应是带 finecat.round.* tag 的回合 item，实际 $tag", tag?.startsWith("finecat.round.") == true)
        return first.boundsInRoot.height
    }

    private fun swipeTowardOlder() {
        compose.onNodeWithTag("finecat.list").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.4f),
                end = Offset(centerX, height * 0.5f),
                durationMillis = 300,
            )
        }
    }

    private fun swipeTowardNewer() {
        compose.onNodeWithTag("finecat.list").performTouchInput {
            swipe(
                start = Offset(centerX, height * 0.5f),
                end = Offset(centerX, height * 0.4f),
                durationMillis = 300,
            )
        }
    }

    private fun historyRequests(): Int = transport.payloadsOfType("history").size

    private fun listAndFirstChildHeights(): Pair<Float, Float> {
        val node = compose.onNodeWithTag("finecat.list", useUnmergedTree = true).fetchSemanticsNode()
        return node.size.height.toFloat() to node.children.first().size.height.toFloat()
    }

    @Test
    fun prefetch_userScrollInsidePrefetchZoneRequestsOlderPage() {
        connect(rounds = 150)
        val initial = historyRequests()
        val list = compose.onNodeWithTag("finecat.list")
        // 远离顶部（约百回合、数万像素）：用户滚动也不应过早拉取。
        list.performScrollToIndex(100)
        swipeTowardOlder()
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        assertEquals("离顶还很远（远超两屏）时不应预取", initial, historyRequests())
        // 取离顶约 1.2 个视口的回合：已超过 5 个回合的旧阈值，但像素上很近。下标按本机
        // 视口与回合高度动态算，避免设备几何不同时前提失效。程序化跳转本身不是用户手势，
        // 不应触发加载。
        list.performScrollToIndex(0)
        compose.waitForIdle()
        val (listHeight, roundHeight) = listAndFirstChildHeights()
        val inZoneIndex = (listHeight * 1.2f / roundHeight).toInt()
        assertTrue(
            "前置：预取区内下标应大于旧阈值 5 且在两个视口内，实际 index=$inZoneIndex list=$listHeight round=$roundHeight",
            inZoneIndex > 5 && inZoneIndex * roundHeight <= listHeight * 2f,
        )
        list.performScrollToIndex(inZoneIndex)
        compose.waitForIdle()
        Thread.sleep(800)
        compose.waitForIdle()
        assertEquals("程序化滚动不应静默触发加载", initial, historyRequests())
        // 用户朝更早方向滑动，进入预取区就应拉取，此时远未到顶，加载点阵不显示。
        swipeTowardOlder()
        runBlocking { waitUntil { historyRequests() > initial } }
        assertTrue(
            "用户在离顶不足两屏处朝更早方向滑动时就应预取，即便回合数超过旧阈值",
            historyRequests() > initial,
        )
        compose.onNodeWithTag("finecat.loadingOlder").assertDoesNotExist()
    }

    @Test
    fun prefetch_tallVisibleRoundDoesNotInflateDistanceEstimate() {
        connect(rounds = 10, tallFrom = 1)
        val initial = historyRequests()
        val list = compose.onNodeWithTag("finecat.list")
        // 用户小幅上滑先取消贴底，否则尾部跟随会把随后的程序化跳转拉回底部。
        swipeTowardOlder()
        compose.waitForIdle()
        list.performScrollToIndex(3)
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        val (listHeight, firstHeight) = listAndFirstChildHeights()
        assertTrue(
            "前置：视口顶的回合应比一个视口还高，实际 round=$firstHeight list=$listHeight",
            firstHeight >= listHeight,
        )
        // 先往新方向挪进超高回合内部，视口里只剩这一个超高回合；真实离顶只有三个短条目。
        swipeTowardNewer()
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("朝较新方向滑动不应预取", initial, historyRequests())
        swipeTowardOlder()
        runBlocking { waitUntil { historyRequests() > initial } }
        assertTrue(
            "视口只含一个超高回合时，按可见项平均高度估算不能把离顶距离放大到错过预取",
            historyRequests() > initial,
        )
    }

    private fun toolResultJson(callId: String, text: String) =
        "{\"role\":\"toolResult\",\"toolCallId\":\"$callId\",\"toolName\":\"read\",\"content\":[{\"type\":\"text\",\"text\":\"$text\"}],\"isError\":false}"

    private fun toolCallAssistantJson(callId: String) =
        "{\"role\":\"assistant\",\"stopReason\":\"toolUse\",\"content\":[{\"type\":\"toolCall\",\"toolCallId\":\"$callId\",\"toolName\":\"read\",\"input\":{\"path\":\"$callId.txt\"}}]}"

    private fun finalAnswerJson(text: String) =
        "{\"role\":\"assistant\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"$text\"}]}"

    private fun userJson(text: String) =
        "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"$text\"}]}"

    private fun entryPage(messages: List<String>, ids: List<String>, hasMore: Boolean, oldest: String) =
        "{\"kind\":\"history\",\"sessionId\":\"$SID\",\"messages\":[${messages.joinToString(",")}]," +
            "\"entryIds\":[${ids.joinToString(",") { "\"$it\"" }}],\"hasMore\":$hasMore," +
            "\"oldestEntryId\":\"$oldest\",\"bytes\":2048}"

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag startsWith $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun stepsGroupNodes() = compose.onAllNodes(tagStartsWith("finecat.steps."))

    private fun partialGroupFirstPage() = entryPage(
        messages = listOf(
            toolResultJson("c1", "一号结果"),
            toolCallAssistantJson("c2"),
            toolResultJson("c2", "二号结果"),
            finalAnswerJson("最终回答"),
        ),
        ids = listOf("p1-r1", "p1-a2", "p1-r2", "p1-fin"),
        hasMore = true,
        oldest = "p1-r1",
    )

    @Test
    fun hiddenPartialGroup_isRevealedWhenCompletionStalls() {
        connect(firstPage = partialGroupFirstPage())
        compose.mainClock.autoAdvance = false
        // 自动续拉已发出，但更旧页一直不回：请求丢失、断线等都会落到这里。
        runBlocking { waitUntil { historyRequests() >= 2 } }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(
            "补全仍在进行时继续隐藏缺头的组",
            stepsGroupNodes().fetchSemanticsNodes().isEmpty(),
        )
        compose.mainClock.advanceTimeBy(20_000)
        assertTrue(
            "补全停滞超时后放弃隐藏，照常显示已加载的部分",
            stepsGroupNodes().fetchSemanticsNodes().isNotEmpty(),
        )
        // 慢但没丢的页之后落地，组头仍没到齐：兜底只管当时那一次停滞，补全应继续、重新隐藏。
        transport.receive(
            entryPage(
                messages = listOf(toolResultJson("c0", "零号结果"), toolCallAssistantJson("c1")),
                ids = listOf("p0-r0", "p0-a1"),
                hasMore = true,
                oldest = "p0-r0",
            ),
        )
        runBlocking { waitUntil { holder.snapshot.conversation.any { it.id.contains("p0-a1") || it.text.contains("零号结果") } } }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(
            "更旧页落地后组头仍缺：重新隐藏缺头的组",
            stepsGroupNodes().fetchSemanticsNodes().isEmpty(),
        )
        runBlocking { waitUntil { historyRequests() >= 3 } }
        assertEquals("p0-r0", transport.payloadsOfType("history")[2]["beforeEntryId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun stallTimerDoesNotRunWhileTheTreeIsShown() {
        connect(firstPage = partialGroupFirstPage())
        compose.mainClock.autoAdvance = false
        runBlocking { waitUntil { historyRequests() >= 2 } }
        // 切到树页停留远超停滞时限；这段时间不在对话页，不应消耗补全预算。
        compose.onNodeWithTag("finecat.viewSeg").performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("finecat.tree", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(30_000)
        compose.onNodeWithTag("finecat.viewSeg").performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(
            "在树页停留期间不应放弃补全，切回对话页后缺头的组仍应隐藏等待",
            stepsGroupNodes().fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun hiddenPartialGroup_staysHiddenAcrossSessionRoundTrip() {
        connect(firstPage = partialGroupFirstPage())
        compose.mainClock.autoAdvance = false
        runBlocking { waitUntil { historyRequests() >= 2 } }
        compose.mainClock.advanceTimeBy(100)
        onMain { holder.selectSession(OTHER_SID) }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == OTHER_SID } }
        onMain { holder.selectSession(SID) }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == SID } }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("切会话往返后仍应继续隐藏等待补全", stepsGroupNodes().fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun partialOperationGroupAtTheTop_isHeldBackAndCompletedBeforeItAppears() {
        // 页顶是被条目分页截断的操作组：c1 的结果在本页，c1 的调用与更早的 c0 在更旧页。
        connect(firstPage = partialGroupFirstPage())
        runBlocking { waitUntil { holder.snapshot.conversation.any { it.text == "最终回答" } } }
        compose.waitForIdle()
        assertTrue(
            "页顶操作组缺少开头时不应先显示残缺的「N 个操作」",
            stepsGroupNodes().fetchSemanticsNodes().isEmpty(),
        )
        // 没有任何手势，也应自动续拉，并用更大的页尽快补全。
        runBlocking { waitUntil { historyRequests() >= 2 } }
        val request = transport.payloadsOfType("history")[1]
        assertEquals("p1-r1", request["beforeEntryId"]?.jsonPrimitive?.contentOrNull)
        assertTrue("补全操作组应使用更大的页", (request["limit"]?.jsonPrimitive?.intOrNull ?: 0) > 20)
        transport.receive(
            entryPage(
                messages = listOf(
                    userJson("请求"),
                    toolCallAssistantJson("c0"),
                    toolResultJson("c0", "零号结果"),
                    toolCallAssistantJson("c1"),
                ),
                ids = listOf("p0-u", "p0-a0", "p0-r0", "p0-a1"),
                hasMore = false,
                oldest = "p0-u",
            ),
        )
        compose.waitUntil(5_000) { stepsGroupNodes().fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            "更旧页的用户消息应随操作组一起出现",
            compose.onAllNodes(tagStartsWith("finecat.userText.")).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNode(hasText("3 个操作", substring = true)).assertExists()
    }

    private val emptyOlderPage: String
        get() = "{\"kind\":\"history\",\"sessionId\":\"$SID\",\"messages\":[],\"entryIds\":[],\"hasMore\":false,\"oldestEntryId\":null,\"bytes\":0}"

    private fun overlayGone(): Boolean =
        compose.onAllNodesWithTag("finecat.loadingOlder").fetchSemanticsNodes().isEmpty()

    private fun startBoundaryLoad() {
        compose.onNodeWithTag("finecat.list").performScrollToIndex(0)
        compose.waitForIdle()
        onMain { holder.loadOlderHistory() }
        runBlocking { waitUntil { holder.snapshot.selectedHistoryIsLoadingOlder } }
        compose.waitForIdle()
    }

    @Test
    fun loadingIndicator_isOverlayAndDoesNotChangeFirstRoundHeight() {
        connect(rounds = 12)
        startBoundaryLoad()
        // 到顶且加载中：浮层存在（基线嵌入式无此 tag）。
        compose.onNodeWithTag("finecat.loadingOlder").assertExists()
        val during = firstRoundHeight()
        // 喂一个空旧页清除加载态、不 prepend 任何内容，首回合本身不变。
        transport.receive(emptyOlderPage)
        runBlocking { waitUntil { !holder.snapshot.selectedHistoryIsLoadingOlder } }
        compose.waitUntil(timeoutMillis = 5_000) { overlayGone() }
        val after = firstRoundHeight()
        assertEquals(
            "加载浮层在列表外，出现/收起不应改变首回合高度（否则主体位置跳变）",
            during, after, 1.0f,
        )
    }

    @Test
    fun boundaryIndicator_dwellsAtLeastOneSecondEvenWhenReplyIsInstant() {
        connect(rounds = 12)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("finecat.list").performScrollToIndex(0)
        compose.mainClock.advanceTimeByFrame()
        onMain { holder.loadOlderHistory() }
        runBlocking { waitUntil { holder.snapshot.selectedHistoryIsLoadingOlder } }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("finecat.loadingOlder").assertExists()
        transport.receive(emptyOlderPage)
        runBlocking { waitUntil { !holder.snapshot.selectedHistoryIsLoadingOlder } }
        // 点阵出现于 48ms 窗口内某一帧，此刻起算已驻留不足 1 秒。
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("finecat.loadingOlder").assertExists()
        compose.mainClock.advanceTimeBy(150)
        assertTrue("驻留满 1 秒后点阵应消失", overlayGone())
    }

    @Test
    fun boundaryIndicator_doesNotLingerIntoAnotherSession() {
        connect(rounds = 12)
        startBoundaryLoad()
        compose.onNodeWithTag("finecat.loadingOlder").assertExists()
        compose.mainClock.autoAdvance = false
        onMain { holder.selectSession(OTHER_SID) }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == OTHER_SID } }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("切到另一会话后，上一会话的加载点阵不应残留", overlayGone())
    }

    companion object {
        private const val SID = "s-paged"
        private const val OTHER_SID = "s-other"
        private const val HELLO = "{\"kind\":\"hello\",\"authenticated\":true,\"protocol\":3,\"capabilities\":[\"sessions\",\"subscribe\",\"message\",\"abort\",\"history\",\"state\",\"models\",\"name\",\"directories\"]}"
    }
}
