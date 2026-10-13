package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToLog
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
import com.finecat.pimobile.android.waitUntil
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * 按显示回合计算时间分隔的设备回归。
 *
 * 判据只认**回合下标集合严格相等**：一个回合 = 一条用户消息起、到下一条用户消息前
 * （会话开头没有用户消息时，开头那段自成一回合）。每个用例把实测写成
 * `CAPTURE-ASSERT: <名> actual=<值>`，同时直接断言期望集合，日志可用于独立核对。
 *
 * 实测怎么来：从列表顶逐行 `performScrollToIndex` 到底，收集带 `finecat.tsep.<回合键>`
 * tag 的分隔行；回合键回查真实 holder 的 conversation 条目，用条目正文里的 `T<n>` 标记
 * 得出回合下标（fixture 每回合正文唯一）。同时独立收集列表内所有"像时间"的文本，
 * 防止没打 tag 的分隔行漏网（修复前就是靠这一列留存差异证据）。
 *
 * 时间一律用 `LocalDateTime.atZone(systemDefault)` 构造，渲染侧同样按本机时区格式化，
 * 所以期望标签与模拟器时区无关。
 */
@RunWith(AndroidJUnit4::class)
class FineCatTimeSeparatorF10DeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("FineCatF10") }
            runCatching {
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                saveShotNamed(bitmap, "FAIL-${description.methodName}.png")
            }
        }
    }

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null
    private lateinit var transport: FakeTransport
    private var liveSeq = 100

    @After
    fun tearDownGraph() {
        runCatching { holder?.close() }
        runCatching { graph?.gatewayScope?.cancel() }
        runCatching { graph?.applicationScope?.cancel() }
        holder = null
        graph = null
    }

    // ------------------------------------------------------------------ 用例

    /** 批准 fixture（7 回合）：期望分隔回合下标严格为 [0,3,4]。 */
    @Test
    fun approvedFixture_separatorTurnsExactly_0_3_4() {
        connect()
        openSessionWithHistory(historyFrame(approvedTurns(), firstEntry = 0, hasMore = false))
        awaitText(userText(6))

        val scan = scanSeparators()
        emitAsserts("tsep.fixture", scan)
        assertEquals("批准 fixture 分隔回合下标", FIXTURE_INDICES, scan.indices())
        assertEquals("批准 fixture 分隔文案", FIXTURE_LABELS, scan.labels())
        assertEquals("列表内时间样文本（含未打 tag 的分隔行）", FIXTURE_CLOCKS, scan.clocks())
        assertEquals("每回合分隔行数上限", "1", scan.maxPerTurn().toString())
        saveTopShot("f10-tsep-fixture.png")
    }

    /** 派生边界：4:59/5:00、未知用户回落助手、跨日小间隔、用户优先、回合内首个已知时间。 */
    @Test
    fun boundaryFixture_thresholdCrossDayMixedAndFirstKnownTime() {
        connect()
        openSessionWithHistory(historyFrame(boundaryTurns(), firstEntry = 0, hasMore = false))
        awaitText(userText(11))

        val scan = scanSeparators()
        emitAsserts("tsep.boundary", scan)
        assertEquals("边界 fixture 分隔回合下标", BOUNDARY_INDICES, scan.indices())
        assertEquals("边界 fixture 分隔文案", BOUNDARY_LABELS, scan.labels())
        assertEquals("每回合分隔行数上限", "1", scan.maxPerTurn().toString())
        saveTopShot("f10-tsep-boundary.png")
    }

    /**
     * 历史 prepend：新页从回合 2 的助手消息开始（用户消息在旧页）。只有新页时回合 2 以
     * 助手 10:06 自成首个已知回合；补上旧页后回合 2 归入用户锚点、相对 10:03 不足 5 分钟，
     * 分隔必须移除，整体回到 [0,3,4]，不残留、不重复。
     */
    @Test
    fun historyPrepend_recomputesWithoutDuplicates() {
        connect()
        val turns = approvedTurns()
        val messages = flatten(turns)
        // 切点：旧页 = 回合 0、1 与回合 2 的用户消息（5 条），新页 = 其余
        val olderCount = 5
        openSessionWithHistory(
            historyJson(messages.drop(olderCount), firstEntry = olderCount, hasMore = true),
        )
        awaitText(userText(6))

        val before = scanSeparators()
        emitAsserts("tsep.prepend.before", before)
        assertEquals("prepend 前（仅新页）分隔回合下标", PREPEND_BEFORE_INDICES, before.indices())
        assertEquals("prepend 前分隔文案", PREPEND_BEFORE_LABELS, before.labels())

        // 翻旧页：扫描时滚到顶可能已自动触发；没触发再走产品入口 loadOlderHistory
        val holder = holder!!
        runBlocking {
            runCatching { waitUntil { transport.payloadsOfType("history").size >= 2 } }
        }
        if (transport.payloadsOfType("history").size < 2) {
            onMain { holder.loadOlderHistory() }
            runBlocking { waitUntil { transport.payloadsOfType("history").size >= 2 } }
        }
        val older = transport.payloadsOfType("history")[1]
        assertEquals(
            "翻页请求必须带最旧条目游标",
            "h-$olderCount",
            older["beforeEntryId"]?.jsonPrimitive?.contentOrNull,
        )
        transport.receive(historyJson(messages.take(olderCount), firstEntry = 0, hasMore = false))
        awaitText(userText(0))

        val after = scanSeparators()
        emitAsserts("tsep.prepend.after", after)
        assertEquals("prepend 后分隔回合下标", FIXTURE_INDICES, after.indices())
        assertEquals("prepend 后分隔文案", FIXTURE_LABELS, after.labels())
        assertEquals("prepend 后每回合分隔行数上限", "1", after.maxPerTurn().toString())
        saveTopShot("f10-tsep-prepend.png")
    }

    /**
     * 流式落定：回合 1 用户 10:03（已知，不足 5 分钟）→ 流式前后都不加分隔；回合 2 用户
     * 时间未知，流式片段 10:10 不计，落定助手 10:15 才计入 → 落定后回合 2 出现唯一分隔。
     */
    @Test
    fun streamingSettle_countsOnlySettledAssistantTime() {
        connect()
        val seed = listOf(
            Turn(0, userAt = fixtureAt(0, 10, 0), assistantAt = listOf(fixtureAt(0, 10, 0))),
        )
        openSessionWithHistory(historyFrame(seed, firstEntry = 0, hasMore = false))
        awaitText(userText(0))

        // 回合 1：用户 10:03 已知
        transport.receive(liveUser(1, fixtureAt(0, 10, 3)))
        transport.receive(liveEvent("""{"type":"agent_start"}""", fixtureAt(0, 10, 3)))
        transport.receive(liveEvent("""{"type":"message_start","message":{"role":"assistant"}}""", fixtureAt(0, 10, 4)))
        transport.receive(liveTextDelta("T1 助手流式片段", fixtureAt(0, 10, 4)))
        awaitStreaming(true)
        val t1Streaming = scanSeparators()
        emitAsserts("tsep.stream.t1Streaming", t1Streaming)
        transport.receive(liveAssistantEnd(assistantText(1), fixtureAt(0, 10, 5)))
        transport.receive(liveEvent("""{"type":"agent_settled"}""", fixtureAt(0, 10, 5)))
        awaitStreaming(false)
        val t1Settled = scanSeparators()
        emitAsserts("tsep.stream.t1Settled", t1Settled)

        // 回合 2：用户时间未知，流式 10:10 不计，落定 10:15 计入
        transport.receive(liveUser(2, null))
        transport.receive(liveEvent("""{"type":"agent_start"}""", fixtureAt(0, 10, 10)))
        transport.receive(liveEvent("""{"type":"message_start","message":{"role":"assistant"}}""", fixtureAt(0, 10, 10)))
        transport.receive(liveTextDelta("T2 助手流式片段", fixtureAt(0, 10, 10)))
        awaitStreaming(true)
        val t2Streaming = scanSeparators()
        emitAsserts("tsep.stream.t2Streaming", t2Streaming)
        transport.receive(liveAssistantEnd(assistantText(2), fixtureAt(0, 10, 15)))
        transport.receive(liveEvent("""{"type":"agent_settled"}""", fixtureAt(0, 10, 15)))
        awaitStreaming(false)
        val t2Settled = scanSeparators()
        emitAsserts("tsep.stream.t2Settled", t2Settled)

        assertEquals("回合1流式中", "[0]", t1Streaming.indices())
        assertEquals("回合1落定后", "[0]", t1Settled.indices())
        assertEquals("回合2流式中（流式时间不计）", "[0]", t2Streaming.indices())
        assertEquals("回合2落定后", "[0,2]", t2Settled.indices())
        assertEquals("回合2落定后文案", "0=10:00|2=10:15", t2Settled.labels())
        assertEquals("每回合分隔行数上限", "1", t2Settled.maxPerTurn().toString())
        saveTopShot("f10-tsep-stream.png")
    }

    /** 列表滚回顶部后整屏截图（通过路径的内容取证）。 */
    private fun saveTopShot(name: String) {
        compose.onNodeWithTag("finecat.list").performScrollToIndex(0)
        compose.waitForIdle()
        saveShotNamed(rootBitmap(), name)
    }

    // ------------------------------------------------------------------ 三环境亮暗矩阵

    @Test
    fun matrix_390dp_light_s4() = matrixShot("f10-390-light", dark = false, expectedViewportDp = 390f)

    @Test
    fun matrix_390dp_dark_s4() = matrixShot("f10-390-dark", dark = true, expectedViewportDp = 390f)

    @Test
    fun matrix_360dp_fs10_light_s4() =
        matrixShot("f10-360-fs10-light", dark = false, densityDpi = 520, expectedViewportDp = 360f)

    @Test
    fun matrix_360dp_fs13_light_s4() =
        matrixShot("f10-360-fs13-light", dark = false, densityDpi = 520, fontScale = "1.3", expectedViewportDp = 360f)

    @Test
    fun matrix_360dp_fs13_dark_s4() =
        matrixShot("f10-360-fs13-dark", dark = true, densityDpi = 520, fontScale = "1.3", expectedViewportDp = 360f)

    /**
     * 矩阵：批准 fixture 滚到列表顶，回合 0 的分隔行 `10:00` 与头部只读目录副行必须同屏
     * 可见且落在视口内，再截图。实际视口宽 = 标称（360dp 档 density=520，F07 同口径）。
     */
    private fun matrixShot(
        prefix: String,
        dark: Boolean,
        densityDpi: Int? = null,
        fontScale: String? = null,
        expectedViewportDp: Float,
    ) {
        try {
            if (densityDpi != null) applyDeviceConfig(densityDpi, fontScale)
            connect(dark = dark)
            openSessionWithHistory(historyFrame(approvedTurns(), firstEntry = 0, hasMore = false))
            awaitText(userText(6))
            compose.onNodeWithTag("finecat.list").performScrollToIndex(0)
            compose.waitForIdle()

            val viewportDp = tagBounds("finecat.root").width / density
            assertTrue(
                "$prefix 实际视口宽应=$expectedViewportDp dp ±2（实际 $viewportDp）",
                abs(viewportDp - expectedViewportDp) <= 2f,
            )
            val root = tagBounds("finecat.root")
            val top = compose.onAllNodesWithTag(SEP_PREFIX + "round:" + userItemId(0), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .firstOrNull { it.boundsInRoot.height > 0f }
            val topLabel = top?.let(::nodeText)
            val inViewport = top != null && top.boundsInRoot.top >= root.top && top.boundsInRoot.bottom <= root.bottom
            val sub = compose.onAllNodesWithTag("finecat.headerTitle", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() && hasTextNode(PROJECT_BASENAME)
            saveAssert("tsep.matrix.$prefix.top", if (inViewport) "visible:$topLabel" else "missing")
            saveAssert("tsep.matrix.$prefix.subLine", if (sub) PROJECT_BASENAME else "missing")
            assertEquals("$prefix 回合 0 分隔行应在视口内且为 10:00", "visible:10:00", if (inViewport) "visible:$topLabel" else "missing")
            assertTrue("$prefix 头部目录副行应显示 $PROJECT_BASENAME", sub)
            saveShotNamed(rootBitmap(), "$prefix-s4.png")
        } finally {
            if (densityDpi != null) restoreDeviceConfig()
        }
    }

    // ------------------------------------------------------------------ 扫描与取证

    /** 一次完整扫描的结果：tag→(文案, 回合下标)，单帧同 tag 最大出现次数，时间样文本序列。 */
    private class Scan(
        val tagged: Map<String, Pair<String, Int>>,
        val maxSameFrame: Int,
        val clockTexts: List<String>,
    ) {
        private val byTurn get() = tagged.values.groupBy { it.second }
        fun indices(): String = byTurn.keys.sorted().joinToString(",", "[", "]")
        fun labels(): String = byTurn.toSortedMap().entries
            .joinToString("|") { (turn, items) -> "$turn=" + items.joinToString("+") { it.first } }
        fun maxPerTurn(): Int = maxOf(maxSameFrame, byTurn.values.maxOfOrNull { it.size } ?: 0)
        fun clocks(): String = clockTexts.joinToString("|")
    }

    private fun scanSeparators(): Scan {
        compose.waitForIdle()
        val tagged = linkedMapOf<String, Pair<String, Int>>()
        val clocks = mutableListOf<String>()
        var maxSameFrame = 0
        val list = compose.onNodeWithTag("finecat.list")
        var index = 0
        while (index < MAX_SCAN_ROWS) {
            val scrolled = runCatching { list.performScrollToIndex(index) }.isSuccess
            if (!scrolled) break
            compose.waitForIdle()
            val frame = compose.onAllNodes(hasTagPrefix(SEP_PREFIX), useUnmergedTree = true).fetchSemanticsNodes()
            frame.groupBy { it.config.getOrNull(SemanticsProperties.TestTag) }
                .forEach { (_, nodes) -> maxSameFrame = maxOf(maxSameFrame, nodes.size) }
            frame.forEach { node ->
                val tag = node.config[SemanticsProperties.TestTag]
                if (tag !in tagged) tagged[tag] = nodeText(node) to turnOfTag(tag)
            }
            compose.onAllNodes(hasAnyAncestor(hasTestTag("finecat.list")) and isClockText(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map(::nodeText)
                .forEach { if (it !in clocks) clocks += it }
            if (index > 0 && listAtEnd()) break
            index++
        }
        saveNote("CAPTURE-NOTE: scan rows=$index tags=${tagged.keys} clocks=$clocks")
        return Scan(tagged, maxSameFrame, clocks)
    }

    private fun emitAsserts(name: String, scan: Scan) {
        saveAssert("$name.indices", scan.indices())
        saveAssert("$name.labels", scan.labels())
        saveAssert("$name.maxPerTurn", scan.maxPerTurn().toString())
        saveAssert("$name.clockTexts", scan.clocks())
    }

    private fun hasTagPrefix(prefix: String) = SemanticsMatcher("testTag startsWith $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun isClockText() = SemanticsMatcher("clock-like text") { node ->
        CLOCK_RE.matches(nodeText(node))
    }

    private fun nodeText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun hasTextNode(text: String): Boolean =
        compose.onAllNodes(SemanticsMatcher("text == $text") { nodeText(it) == text }, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun listAtEnd(): Boolean {
        val node = compose.onNodeWithTag("finecat.list").fetchSemanticsNode()
        val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return true
        return range.value() >= range.maxValue() - 0.5f
    }

    /** `finecat.tsep.<回合键>` → 回合下标：回合键即生产 displayRoundKey，回查真实条目正文的 `T<n>`。 */
    private fun turnOfTag(tag: String): Int {
        val key = tag.removePrefix(SEP_PREFIX)
        val id = key.removePrefix("round:")
        val item = holder!!.snapshot.conversation.firstOrNull { it.id == id }
            ?: throw AssertionError("分隔行 $tag 的锚点条目 $id 不在 conversation 中")
        val turn = TURN_RE.find(item.text)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("锚点条目 $id 正文缺回合标记：${item.text}")
        return turn
    }

    // ------------------------------------------------------------------ fixture

    private data class Turn(
        val turn: Int,
        val userAt: LocalDateTime?,
        val assistantAt: List<LocalDateTime?>,
    )

    private data class Msg(val role: String, val text: String, val at: LocalDateTime?)

    private fun fixtureAt(day: Long, h: Int, m: Int, s: Int = 0): LocalDateTime =
        LocalDateTime.of(FIXTURE_DAY.plusDays(day), java.time.LocalTime.of(h, m, s))

    private fun boundaryAt(day: Long, h: Int, m: Int, s: Int = 0): LocalDateTime =
        LocalDateTime.of(BOUNDARY_DAY.plusDays(day), java.time.LocalTime.of(h, m, s))

    /** 批准 fixture（OPEN-DECISIONS 事项 1 / 批准记录 DEC-F09-TIMESEP）。 */
    private fun approvedTurns(): List<Turn> = listOf(
        Turn(0, fixtureAt(0, 10, 0), listOf(fixtureAt(0, 10, 0))),
        Turn(1, fixtureAt(0, 10, 3), listOf(fixtureAt(0, 10, 3))),
        Turn(2, null, listOf(fixtureAt(0, 10, 6))),
        Turn(3, fixtureAt(0, 10, 12), listOf(null)),
        Turn(4, fixtureAt(1, 0, 1), listOf(fixtureAt(1, 0, 1))),
        Turn(5, null, listOf(null)),
        Turn(6, fixtureAt(1, 0, 4), listOf(null)),
    )

    /** 派生边界序列（期望值由批准规则逐回合推得，见 asserts.tsv 备注）。 */
    private fun boundaryTurns(): List<Turn> = listOf(
        Turn(0, boundaryAt(0, 9, 0, 0), listOf(boundaryAt(0, 9, 0, 30))),
        Turn(1, boundaryAt(0, 9, 4, 59), listOf(null)),
        Turn(2, boundaryAt(0, 9, 9, 59), listOf(null)),
        Turn(3, null, listOf(boundaryAt(0, 9, 15, 0))),
        Turn(4, boundaryAt(0, 9, 18, 0), listOf(null)),
        Turn(5, boundaryAt(0, 23, 58, 0), listOf(null)),
        Turn(6, boundaryAt(1, 0, 1, 0), listOf(null)),
        Turn(7, boundaryAt(1, 0, 3, 0), listOf(null)),
        Turn(8, boundaryAt(1, 10, 0, 0), listOf(boundaryAt(1, 10, 20, 0))),
        Turn(9, boundaryAt(1, 10, 22, 0), listOf(null)),
        Turn(10, null, listOf(boundaryAt(1, 10, 24, 0), boundaryAt(1, 10, 40, 0))),
        Turn(11, boundaryAt(1, 10, 42, 0), listOf(null)),
    )

    private fun userText(turn: Int) = "T$turn 用户提问"
    private fun assistantText(turn: Int, part: Int = 0) =
        "T$turn 助手回答" + if (part > 0) "（续$part）" else ""

    private fun flatten(turns: List<Turn>): List<Msg> = turns.flatMap { t ->
        listOf(Msg("user", userText(t.turn), t.userAt)) +
            t.assistantAt.mapIndexed { i, at -> Msg("assistant", assistantText(t.turn, i), at) }
    }

    private fun historyFrame(turns: List<Turn>, firstEntry: Int, hasMore: Boolean): String =
        historyJson(flatten(turns), firstEntry, hasMore)

    private fun historyJson(messages: List<Msg>, firstEntry: Int, hasMore: Boolean): String {
        val body = messages.joinToString(",") { m ->
            val ts = m.at?.let { ",\"timestamp\":${epochMs(it)}" }.orEmpty()
            val stop = if (m.role == "assistant") ",\"stopReason\":\"stop\"" else ""
            "{\"role\":\"${m.role}\"$ts$stop,\"content\":[{\"type\":\"text\",\"text\":${jstr(m.text)}}]}"
        }
        val ids = messages.indices.joinToString(",") { "\"h-${firstEntry + it}\"" }
        return "{\"kind\":\"history\",\"sessionId\":\"$SESSION\",\"messages\":[$body]," +
            "\"entryIds\":[$ids],\"hasMore\":$hasMore,\"oldestEntryId\":\"h-$firstEntry\",\"bytes\":${body.length}}"
    }

    private fun userItemId(turn: Int): String =
        holder!!.snapshot.conversation.first { it.text == userText(turn) }.id

    private fun epochMs(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun jstr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun liveEvent(json: String, at: LocalDateTime?): String {
        val time = at?.let(::epochMs) ?: 0
        return "{\"kind\":\"event\",\"sessionId\":\"$SESSION\",\"seq\":${liveSeq++},\"time\":$time,\"event\":$json}"
    }

    private fun liveUser(turn: Int, at: LocalDateTime?): String = liveEvent(
        "{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":${jstr(userText(turn))}}]}}",
        at,
    )

    private fun liveTextDelta(text: String, at: LocalDateTime): String = liveEvent(
        "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":${jstr(text)}}}",
        at,
    )

    private fun liveAssistantEnd(text: String, at: LocalDateTime): String = liveEvent(
        "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"stop\"," +
            "\"content\":[{\"type\":\"text\",\"text\":${jstr(text)}}]}}",
        at,
    )

    private fun awaitStreaming(streaming: Boolean) {
        runBlocking {
            waitUntil {
                holder!!.snapshot.conversation.any { it.id.startsWith("stream-") } == streaming
            }
        }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) {
        runBlocking { waitUntil { holder!!.snapshot.conversation.any { it.text == text } } }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ 装配

    private fun connect(dark: Boolean = false) {
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
        val stateHolder = graph!!.stateHolder
        holder = stateHolder
        compose.setContent { FineCatApp(stateHolder = stateHolder) }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        val mode = if (dark) "dark" else "light"
        onMain { stateHolder.updateAppearanceMode(mode) }
        runBlocking { waitUntil { stateHolder.appearanceMode == mode } }
    }

    private fun openSessionWithHistory(historyJson: String) {
        val stateHolder = holder!!
        transport.receive(
            "{\"kind\":\"sessions\",\"items\":[{\"id\":\"$SESSION\",\"cwd\":${jstr(PROJECT_CWD)}," +
                "\"name\":\"F10 时间分隔\",\"messageCount\":12,\"running\":false}]}",
        )
        runBlocking { waitUntil { stateHolder.snapshot.sessions.any { it.id == SESSION } } }
        onMain { stateHolder.selectSession(SESSION) }
        runBlocking {
            waitUntil { stateHolder.snapshot.selectedSessionId == SESSION }
            waitUntil { transport.payloadsOfType("history").isNotEmpty() }
        }
        transport.receive(historyJson)
    }

    // ------------------------------------------------------------------ 设备配置/截图/落盘

    private val density: Float get() = compose.density.density

    private fun tagBounds(tag: String): Rect =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }?.boundsInRoot
            ?: throw AssertionError("节点 $tag 未找到或 bounds 为零")

    private fun applyDeviceConfig(densityDpi: Int, fontScale: String?) {
        shell("wm density $densityDpi")
        if (fontScale != null) shell("settings put system font_scale $fontScale")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("wm density").contains("Override density: $densityDpi")) break
            Thread.sleep(200)
        }
        Thread.sleep(500)
        compose.waitForIdle()
    }

    private fun restoreDeviceConfig() {
        shell("wm density 480")
        shell("settings put system font_scale 1.0")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("wm density").contains("Override density: 480")) break
            Thread.sleep(200)
        }
        val densityOut = shell("wm density")
        val fontScale = shell("settings get system font_scale")
        assertTrue(
            "矩阵步骤结束必须恢复设备配置：density=[$densityOut] fontScale=[$fontScale]",
            densityOut.contains("Override density: 480") && fontScale.trim() == "1.0",
        )
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun rootBitmap(): Bitmap {
        var last: Throwable? = null
        repeat(3) {
            runCatching {
                return compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }.onFailure { last = it }
            Thread.sleep(200)
            compose.waitForIdle()
        }
        throw AssertionError("rootBitmap 三次采集均失败", last)
    }

    private fun saveShotNamed(bmp: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        File(dir, name).outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private var measureFileReady = false

    /** 取证行写进外部 files 根 capture-measure.txt（capture 按 mtime 拉取并附进用例日志）。 */
    private fun appendMeasureLine(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line.trimEnd() + "\n")
    }

    /** 严格等值取证：check 用 asserts.tsv 的登记期望逐字比对 actual。 */
    private fun saveAssert(name: String, actual: String) = appendMeasureLine("CAPTURE-ASSERT: $name actual=$actual")

    private fun saveNote(line: String) = appendMeasureLine(line)

    companion object {
        private const val SESSION = "s-f10-tsep"
        private const val PROJECT_CWD = "/home/user/f10-proj"
        private const val PROJECT_BASENAME = "f10-proj"
        private const val SEP_PREFIX = "finecat.tsep."
        private const val MAX_SCAN_ROWS = 80
        private val FIXTURE_DAY: LocalDate = LocalDate.of(2026, 9, 30)
        private val BOUNDARY_DAY: LocalDate = LocalDate.of(2026, 3, 14)
        private val CLOCK_RE = Regex("""(\d{2}-\d{2} )?\d{2}:\d{2}""")
        private val TURN_RE = Regex("""\bT(\d+) """)

        // 批准 fixture 期望（DEC-F09-TIMESEP：分隔回合下标严格为 [0,3,4]）
        const val FIXTURE_INDICES = "[0,3,4]"
        const val FIXTURE_LABELS = "0=10:00|3=10:12|4=10-01 00:01"
        const val FIXTURE_CLOCKS = "10:00|10:12|10-01 00:01"

        // 派生边界期望（由批准规则推得）
        const val BOUNDARY_INDICES = "[0,2,3,5,6,8,9,11]"
        const val BOUNDARY_LABELS =
            "0=09:00|2=09:09|3=09:15|5=23:58|6=03-15 00:01|8=10:00|9=10:22|11=10:42"

        // prepend 前（只有新页：回合 2 助手 10:06 自成首个已知回合）
        const val PREPEND_BEFORE_INDICES = "[2,3,4]"
        const val PREPEND_BEFORE_LABELS = "2=10:06|3=10:12|4=10-01 00:01"

        val HELLO =
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","abort","history","state","models","name"]}"""
    }
}
