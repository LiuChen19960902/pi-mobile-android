package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatSteps
import com.finecat.pimobile.android.ui.finecat.conversation.mergeFineCatProcessEntries
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 对话复制反馈、消息分组间距与结束态的设备回归。
 *
 * 数据全部经**真实线上事件帧**注入（pi 原生形状，PiEventNormalizer 归一）：
 * agent_start / message_start(assistant) / message_update(text_delta|toolcall_*) /
 * tool_execution_start / message_end(role=assistant|toolResult) / agent_settled /
 * compaction_*——与 FineCatConversationRegressionDeviceTest 同口径。
 *
 * 判定口径（任务书）：
 * - 剪贴板字符串与目标助手全文严格相等（ClipboardManager 读回比对；全文取自
 *   holder 的投影 item.text，即 UI 复制语义的源文本）；
 * - 复制反馈用 compose rule 的**受控帧钟**验证 1000ms 在 / 1300ms 复位
 *   （mainClock.autoAdvance=false + advanceTimeBy，不用 sleep 猜）；
 * - 步骤→正文及连续组均4dp 用组件 bounds 几何测量（冻结容差 ±2dp）；
 * - 步骤展开状态与 fixture 期望集合严格相等（detail 文本可见性），串扰 0 例；
 * - 末行文案由 fixture 驱动的真实运行状态判定（isStreaming/isCompacting）。
 *
 * 矩阵（360dp 暗色 / 360dp+fs1.3）拆独立方法：配置注入在 setContent 之前
 * （wm density 520 → 1170px/(520/160) = 360dp），finally 恢复并读回断言。
 * 已知实现注意（实测）：对话 LazyColumn 离屏项无语义，查询/点击前必须滚动到位；
 * 步骤行合并节点对语义 performClick 不触发展开，用真实触摸坐标点击。
 */
@RunWith(AndroidJUnit4::class)
class FineCatConversationF02DeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var transport: FakeTransport
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var graph: AndroidAppGraph

    private val SESSION = "android-demo"

    /** 同文两条助手消息的正文：多段落 + 围栏代码 + 表格（任务书覆盖面）。 */
    private val assistantMarkdown: String =
        "第一段说明文字，验证多段落渲染。\n\n" +
            "```bash\necho \"f02-step\"\n```\n\n" +
            "| 列A | 列B |\n| --- | --- |\n| 甲 | 乙 |\n\n" +
            "第二段结论文字收尾。"

    private val ARGS_READ = """{"path":"/tmp/a.md"}"""
    private val ARGS_LS = """{"cmd":"ls"}"""

    /** 工具 detail 文本（toolResult content），键与注入的 toolCallId 一致。 */
    // detail 文本全唯一：按文本可见性严格判定展开集合，同文本无法区分不同步骤
    private val detailsById = mapOf(
        "t0-1" to "文件内容输出 A",
        "t0-2" to "目录列表输出 A",
        "t1-1" to "文件内容输出 B",
        "t1-2" to "目录列表输出 B",
        "t2-1" to "目录列表输出 C",
    )

    private var seq = 100

    @After
    fun tearDown() {
        if (this::graph.isInitialized) {
            holder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    @Test
    fun userCopyUsesExactText_andProcessMessagesHaveNoCopyButton() {
        setUpConnectedSession()
        val userText = "请保留这行原文\n第二行 `models.json`"
        transport.receive(event("""{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":${jstr(userText)}}]}}"""))
        transport.receive(event("""{"type":"agent_start"}"""))
        transport.receive(event("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"正在分析"},{"type":"text","text":"   "},{"type":"toolCall","toolCallId":"copy-tool","toolName":"read","input":$ARGS_READ}]}}"""))
        transport.receive(toolExecutionStart("copy-tool", "read"))
        transport.receive(toolResultEnd("copy-tool", "read", "合成工具结果"))
        transport.receive(assistantEnd("这才是助手正文。"))
        transport.receive(event("""{"type":"agent_settled"}"""))
        runBlocking { waitUntil { holder.snapshot.conversation.any { it.text == "这才是助手正文。" } &&
            holder.snapshot.sessionStates[SESSION]?.isStreaming == false } }
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        compose.waitForIdle()
        val user = holder.snapshot.conversation.single { it.text == userText }
        scrollListTo("finecat.copy.${user.id}")
        compose.onNodeWithTag("finecat.copy.${user.id}").performClick()
        assertEquals(userText, clipboardManager().currentText())
        assertEquals(listOf("已复制"), compose.onNodeWithTag("finecat.copy.${user.id}")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        // 遍历真实投影，不以正文文本或列表位置猜按钮归属。
        holder.snapshot.conversation.filter { it.text.isBlank() ||
            it.kind !in setOf(com.finecat.pimobile.shared.projection.ConversationItemKind.USER,
                com.finecat.pimobile.shared.projection.ConversationItemKind.ASSISTANT) }.forEach {
            compose.onNodeWithTag("finecat.copy.${it.id}").assertDoesNotExist()
        }
        val assistant = holder.snapshot.conversation.single { it.text == "这才是助手正文。" }
        scrollListTo("finecat.copy.${assistant.id}")
        compose.onNodeWithTag("finecat.copy.${assistant.id}").performClick()
        assertEquals(assistant.text, clipboardManager().currentText())
    }

    @Test
    fun streamingFollowKeepsWorkingAtBottom_andDoesNotPullHistoryReader() {
        setUpConnectedSession()
        transport.receive(event("""{"type":"agent_start"}"""))
        transport.receive(messageStartAssistant())
        transport.receive(textDelta("足够长的流式正文，验证尾部滚动。".repeat(100)))
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        waitUntilWorkingRow()
        compose.waitForIdle()
        val bottom = compose.onNodeWithTag("finecat.working").fetchSemanticsNode().boundsInRoot.bottom
        repeat(8) {
            val beforeLength = holder.snapshot.conversation.last().text.length
            transport.receive(textDelta("接下来继续追加内容，跨过新的换行。".repeat(3)))
            runBlocking { waitUntil { holder.snapshot.conversation.last().text.length > beforeLength } }
            compose.waitForIdle()
            val current = compose.onNodeWithTag("finecat.working").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("贴底时 Working 不应跳到列表顶部：$bottom → $current",
                kotlin.math.abs(current - bottom) <= compose.density.density * 2)
        }
        compose.onNodeWithTag("finecat.list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val id = holder.snapshot.conversation.last { it.kind ==
            com.finecat.pimobile.shared.projection.ConversationItemKind.ASSISTANT }.id
        val before = compose.onNodeWithTag("finecat.ai.$id").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("finecat.working").assertDoesNotExist()
        val beforeLength = holder.snapshot.conversation.last().text.length
        transport.receive(textDelta("阅读历史期间追加的内容。".repeat(5)))
        runBlocking { waitUntil { holder.snapshot.conversation.last().text.length > beforeLength } }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("finecat.ai.$id").fetchSemanticsNode().boundsInRoot.top
        assertTrue("上滑阅读时不能被流式增量拉回底部", kotlin.math.abs(after - before) <= compose.density.density * 2)
        compose.onNodeWithTag("finecat.working").assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // 条件 1：复制全文严格相等 + 应用内勾选反馈（受控时钟 1000/1300ms）+
    // 同文不同消息不串对象。
    // ------------------------------------------------------------------
    @Test
    fun copy_putsExactTextOnClipboard_showsInAppFeedback_for1200ms_noCrossTalk() {
        injectConversation(sameTextTwice = true)
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        compose.waitForIdle()
        waitUntilAssistantShown(count = 2)

        val clipboard = clipboardManager()

        // 先滚动到位（autoAdvance=false 时 waitForIdle 不返回——帧钟停转）
        scrollListTo("finecat.copy.${assistantMessageIdAt(0)}")
        val expectedText0 = assistantTextAt(0)
        assertTrue("全文应含代码块", expectedText0.contains("echo"))
        assertTrue("全文应含表格", expectedText0.contains("列A"))
        compose.mainClock.autoAdvance = false
        copyButton(0).performClick()
        compose.mainClock.advanceTimeBy(16)
        assertEquals(expectedText0, clipboard.currentText())

        // 受控时钟 1000ms：第一条「已复制」在，第二条（同文）仍是「复制全文」
        compose.mainClock.advanceTimeBy(1_000)
        assertCopyState(0, expectedCopied = true)
        assertCopyState(1, expectedCopied = false)

        // 1300ms：反馈复位
        compose.mainClock.advanceTimeBy(300)
        assertCopyState(0, expectedCopied = false)

        // 复制第二条：剪贴板同为全文（同文），反馈只在第二条，第一条不串
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        scrollListTo("finecat.copy.${assistantMessageIdAt(1)}")
        compose.mainClock.autoAdvance = false
        copyButton(1).performClick()
        compose.mainClock.advanceTimeBy(16)
        assertEquals(assistantTextAt(1), clipboard.currentText())
        assertCopyState(1, expectedCopied = true)
        assertCopyState(0, expectedCopied = false)
        compose.mainClock.advanceTimeBy(1_300)
        assertCopyState(1, expectedCopied = false)
        saveShot("f02-copy-feedback.png")
    }

    // ------------------------------------------------------------------
    // 条件 2：步骤组 → 正文、正文 → 步骤组均4dp，收展不拉开；
    // bounds 几何测量，容差 ≤2dp（DEC-TOLERANCE）。
    // ------------------------------------------------------------------
    @Test
    fun fourBatchesWithBlankAssistantShareOneInlineSummary() {
        setUpConnectedSession()
        repeat(4) { index ->
            val id = "compact-$index"
            transport.receive(event("""{"type":"agent_start"}"""))
            transport.receive(event("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":${jstr(" \n")}},{"type":"toolCall","toolCallId":"$id","toolName":"read","input":$ARGS_READ}]}}"""))
            transport.receive(toolExecutionStart(id, "read"))
            transport.receive(toolResultEnd(id, "read", "合成结果$index"))
            transport.receive(event("""{"type":"agent_settled"}"""))
        }
        transport.receive(assistantEnd("最后的可见正文。"))
        runBlocking { waitUntil { holder.snapshot.conversation.any { it.text == "最后的可见正文。" } } }
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        compose.waitForIdle()
        val groups = processGroupIds()
        assertEquals("四批之间只有空助手消息，应只有一个入口", 1, groups.size)
        val first = compose.onNodeWithTag("finecat.steps.${groups[0]}").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        compose.onNodeWithTag("finecat.stepsHeader.${groups[0]}").assertTextContains("4 个操作")
        val finalId = holder.snapshot.conversation.single { it.text == "最后的可见正文。" }.id
        val body = compose.onNodeWithTag("finecat.ai.$finalId").fetchSemanticsNode().boundsInRoot
        assertTrue("合并入口到正文应为4dp", (body.top - first.bottom) / density in 3f..5f)
        val header = compose.onNodeWithTag("finecat.stepsHeader.${groups[0]}").fetchSemanticsNode().boundsInRoot
        assertTrue("参考摘要允许大字撑高", header.height / density >= 31f)
        if (compose.density.fontScale == 1f) assertTrue("13sp摘要+上下7dp", header.height / density in 31.5f..33f)
        clickGroupById(groups[0])
        val expanded = compose.onNodeWithTag("finecat.steps.${groups[0]}").fetchSemanticsNode().boundsInRoot
        val after = compose.onNodeWithTag("finecat.ai.$finalId").fetchSemanticsNode().boundsInRoot
        assertEquals("内联展开保持入口顶部", first.top, expanded.top, 1f)
        assertTrue("内联列表为正文让出真实高度", after.top > body.top)
        compose.onNodeWithTag("finecat.stepsList.${groups[0]}").assertExists()
        repeat(4) { index ->
            clickStepRow("read", index)
            compose.onNodeWithTag("finecat.stepDetailOutput").performScrollTo()
            compose.onNodeWithTag("finecat.stepDetailOutput").assertTextContains("合成结果$index")
            compose.onNodeWithTag("finecat.stepDetailCommand").assertExists()
            assertEquals("工具详情不再打开上弹面板", 0,
                compose.onAllNodesWithTag("finecat.sheetCard").fetchSemanticsNodes().size)
        }
        clickGroupById(groups[0])
        clickGroupById(groups[0])
        compose.onNodeWithTag("finecat.stepsList.${groups[0]}").assertExists()
        clickGroupById(groups[0])
    }

    @Test
    fun spacing_stepsToTextAndAcrossRounds_4dp_measuredByBounds() {
        injectConversation(sameTextTwice = false, twoRounds = true)
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        compose.waitForIdle()
        waitUntilAssistantShown(count = 2)

        // 组 id 由真实 displayEntries 计算（投影 item id 形状不写死）
        val groupIds = processGroupIds()
        assertTrue("fixture 应有两个步骤组", groupIds.size >= 2)

        val density = compose.density.density

        // 第一回合：组0（步骤组）→ 正文0
        scrollListToTag("finecat.steps.${groupIds[0]}")
        val group0 = compose.onNodeWithTag("finecat.steps.${groupIds[0]}")
            .fetchSemanticsNode().boundsInRoot
        val text0 = compose.onNodeWithTag("finecat.ai.${assistantMessageIdAt(0)}")
            .fetchSemanticsNode().boundsInRoot
        val innerGap = (text0.top - group0.bottom) / density
        assertTrue("步骤组→同组正文应4dp±1（实测 ${innerGap}dp）", innerGap in 3f..5f)
        saveShot("f02-spacing.png")

        // 正文0（底）→ 组1（顶）、组1 → 正文1均4dp
        scrollListToTag("finecat.steps.${groupIds[1]}")
        val text0b = compose.onNodeWithTag("finecat.ai.${assistantMessageIdAt(0)}")
            .fetchSemanticsNode().boundsInRoot
        val group1 = compose.onNodeWithTag("finecat.steps.${groupIds[1]}")
            .fetchSemanticsNode().boundsInRoot
        val groupGap = (group1.top - text0b.bottom) / density
        assertTrue(
            "正文→下一回合步骤组应4dp±1（实测 ${groupGap}dp）",
            groupGap in 3f..5f,
        )
        val text1 = compose.onNodeWithTag("finecat.ai.${assistantMessageIdAt(1)}")
            .fetchSemanticsNode().boundsInRoot
        val innerGap2 = (text1.top - group1.bottom) / density
        assertTrue("第二回合组内也应4dp±1（实测 ${innerGap2}dp）", innerGap2 in 3f..5f)
    }

    // ------------------------------------------------------------------
    // 条件 3a：多步骤独立展开/收起，不串消息（期望集合严格相等，串扰 0 例）。
    // ------------------------------------------------------------------
    @Test
    fun stepsExpandInline_andGroupCloseKeepsRowsIsolated() {
        injectConversation(sameTextTwice = false, twoRounds = true)
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        compose.waitForIdle()
        waitUntilAssistantShown(count = 2)
        val groups = processGroupIds()
        clickGroupById(groups[0])
        clickStepRow("read", 0)
        assertExpandedSetStrictly(setOf("t1-1"))
        compose.onNodeWithTag(stepTag("read", 0)).performScrollTo().performClick()
        assertExpandedSetStrictly(emptySet())
        clickStepRow("bash", 0)
        assertExpandedSetStrictly(setOf("t1-2"))
        clickGroupById(groups[0])
        compose.onNodeWithTag("finecat.stepsList.${groups[0]}").assertDoesNotExist()
        clickGroupById(groups[1])
        compose.onNodeWithTag(stepTag("read", 0)).assertDoesNotExist()
        clickStepRow("bash", 1)
        assertExpandedSetStrictly(setOf("t2-1"))
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(0)
        clickGroupById(groups[1])
    }

    // ------------------------------------------------------------------
    // 条件 3b + 4：流式到落定不丢消息身份/展开状态；末行由真实运行状态驱动
    // （Working → 压缩中仍 Working（不误报空闲）→ 空闲移除末行，C10）。
    // ------------------------------------------------------------------
    @Test
    fun streamingToSettled_keepsIdentityAndExpansion_footerFollowsRealState() {
        beginConversationStreaming()
        compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
        compose.waitForIdle()
        waitUntilWorkingRow()

        // 流式中正文只有步骤入口，Working仍由真实运行状态驱动。
        assertFooterText("Working")

        // 运行中的组也从列表入口打开详情。
        val streamGroupId = processGroupIds()[0]
        compose.onNodeWithTag("finecat.stepsHeader.$streamGroupId").assertExists()
        compose.onNodeWithTag(stepTag("read", 0)).assertDoesNotExist()
        clickGroupById(streamGroupId)
        clickStepRow("read", 0)
        compose.waitForIdle()
        assertExpandedSetStrictly(expected = setOf("t0-1"))
        clickStepRow("read", 0)
        compose.onNodeWithTag("finecat.stepsList.$streamGroupId").assertExists()
        assertExpandedSetStrictly(expected = emptySet())
        // 再点摘要收起内联列表。
        clickGroupById(streamGroupId)
        compose.waitForIdle()
        assertExpandedSetStrictly(expected = emptySet())

        // 压缩开始：isCompacting=true，末行仍 Working（不误报空闲）
        transport.receive(event("""{"type":"compaction_start","reason":"auto"}"""))
        compose.waitUntil(10_000) {
            holder.snapshot.sessionStates[SESSION]?.isCompacting == true
        }
        assertFooterText("Working")

        // 落定：assistant 消息落定 + 压缩结束 + agent_settled；落定前先注入排队
        transport.receive(assistantEndFrame())
        transport.receive(event("""{"type":"compaction_end"}"""))
        transport.receive(queueUpdate(steering = emptyList(), followUp = listOf("排队中的追问")))
        transport.receive(event("""{"type":"agent_settled"}"""))
        compose.waitUntil(10_000) {
            holder.snapshot.sessionStates[SESSION]?.let { !it.isStreaming && !it.isCompacting } == true
        }
        // 排队未投递完不是空闲：队列非空时仍 Working（不误报空闲）
        assertFooterText("Working")
        // 队列清空后移除空闲提示；运行中的状态提示仍按真实状态保留。
        transport.receive(queueUpdate(steering = emptyList(), followUp = emptyList()))
        compose.waitUntil(10_000) {
            holder.snapshot.sessionStates[SESSION]?.queuedMessages?.isEmpty != false
        }
        compose.onNodeWithTag("finecat.list").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 1_000_000f)
        }
        compose.waitForIdle()
        val tailRange = compose.onNodeWithTag("finecat.list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("确认已到列表尾部，不能用离屏未组合的节点代替删除", tailRange.value() >= tailRange.maxValue())
        compose.onNodeWithTag("finecat.working").assertDoesNotExist()

        // finalize 保持组ID；恢复计数头且默认折叠，显式展开后详情仍可用。
        assertEquals("流式组ID不能漂移", streamGroupId, processGroupIds()[0])
        scrollListToTag("finecat.stepsHeader.$streamGroupId")
        compose.onNodeWithTag(stepTag("read", 0)).assertDoesNotExist()
        clickGroupById(streamGroupId)
        compose.onNodeWithTag(stepTag("read", 0)).assertExists()
        assertExpandedSetStrictly(expected = emptySet())
        clickStepRow("read", 0)
        assertExpandedSetStrictly(expected = setOf("t0-1"))
        clickGroupById(streamGroupId)
        compose.waitForIdle()
        saveShot("f02-settled.png")
    }

    // ------------------------------------------------------------------
    // 条件 6（矩阵抽样）：360dp 暗色复制反馈可达且底色为暗；360dp+fs1.3
    // 大字号下组与正文仍紧凑且不裁切。
    // ------------------------------------------------------------------
    @Test
    fun matrix_360dp_dark_copyFeedback_usable() {
        shell("wm density 520")
        Thread.sleep(1_000)
        try {
            injectConversation(sameTextTwice = true)
            holder.updateAppearanceMode("dark")
            compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
            compose.waitForIdle()
            waitUntilAssistantShown(count = 2)
            scrollListTo("finecat.copy.${assistantMessageIdAt(0)}")
            copyButton(0).performClick()
            assertEquals(assistantTextAt(0), clipboardManager().currentText())
            assertCopyState(0, expectedCopied = true)
            assertCanvasPixel(darkExpected = true)
            saveShot("f02-matrix-360-dark-copy.png")
        } finally {
            restoreDeviceConfig()
        }
    }

    @Test
    fun matrix_360dp_fs13_spacing_holds() {
        shell("wm density 520")
        shell("settings put system font_scale 1.3")
        Thread.sleep(1_000)
        try {
            injectConversation(sameTextTwice = false, twoRounds = true)
            compose.setContent { FineCatTheme { FineCatApp(stateHolder = holder) } }
            compose.waitForIdle()
            waitUntilAssistantShown(count = 2)
            val density = compose.density.density
            scrollListToTag("finecat.steps.${processGroupIds()[0]}")
            val group0 = compose.onNodeWithTag("finecat.steps.${processGroupIds()[0]}")
                .fetchSemanticsNode().boundsInRoot
            val text0 = compose.onNodeWithTag("finecat.ai.${assistantMessageIdAt(0)}")
                .fetchSemanticsNode().boundsInRoot
            val innerGap = (text0.top - group0.bottom) / density
            assertTrue("fs1.3 组内间距仍应4dp±1（实测 ${innerGap}dp）", innerGap in 3f..5f)
            saveShot("f02-matrix-360fs13-spacing.png")
        } finally {
            restoreDeviceConfig()
        }
    }

    // ==================================================================
    // 公共步骤
    // ==================================================================

    /** 复制反馈断言：按 contentDescription 判「复制全文/已复制」。 */
    private fun assertCopyState(index: Int, expectedCopied: Boolean) {
        val expected = if (expectedCopied) "已复制" else "复制全文"
        val nodes = compose.onAllNodesWithTag(
            "finecat.copy.${assistantMessageIdAt(index)}",
        ).fetchSemanticsNodes()
        assertTrue("复制按钮（第 ${index + 1} 条助手消息）应存在", nodes.isNotEmpty())
        val actual = nodes[0].config
            .getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals("复制反馈状态不符（期望 $expected）", expected, actual)
    }

    /** 画布底色像素：暗色 bg #0C0C0E、亮色 #FFFFFF（避开文字，取左缘）。 */
    private fun assertCanvasPixel(darkExpected: Boolean) {
        var last: Exception? = null
        repeat(4) {
            try {
                val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
                val c = bmp.getPixel(8, bmp.height / 2)
                val lum = (android.graphics.Color.red(c) +
                    android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3f
                if (darkExpected) assertTrue("应暗色底（实测亮度=$lum）", lum < 80f)
                else assertTrue("应亮色底（实测亮度=$lum）", lum > 180f)
                return
            } catch (e: IllegalArgumentException) {
                last = e
                Thread.sleep(300)
            }
        }
        throw IllegalStateException("采样画布底色失败", last)
    }

    private fun clipboardManager(): ClipboardManager =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun ClipboardManager.currentText(): String =
        primaryClip?.getItemAt(0)?.coerceToText(
            InstrumentationRegistry.getInstrumentation().targetContext
        )?.toString() ?: ""

    /** 展开状态与 fixture 期望集合严格相等：以各步骤 detail 文本可见性判定。 */
    private fun assertExpandedSetStrictly(expected: Set<String>) {
        detailsById.forEach { (id, text) ->
            val visible = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            assertEquals("步骤 $id 展开状态应=${id in expected}（detail「$text」可见性=$visible）", id in expected, visible)
        }
    }

    /**
     * 注入对话事件流（pi 原生形状）。
     * sameTextTwice=true：两条**同文**助手消息（多段落+代码+表格，复制不串对象用）。
     * twoRounds=true：回合1 只有步骤组（t1-1/t1-2），回合2 纯正文。
     */
    private fun injectConversation(sameTextTwice: Boolean, twoRounds: Boolean = false) {
        setUpConnectedSession()
        if (twoRounds) {
            // 回合1：步骤组（t1-1/t1-2）+ 正文；回合2：步骤组（t2-1）+ 正文
            transport.receive(event("""{"type":"agent_start"}"""))
            transport.receive(textDelta("第一回合的正文。"))
            transport.receive(
                assistantEnd(
                    text = "第一回合的正文。",
                    toolCalls = listOf(
                        Triple("t1-1", "read", ARGS_READ),
                        Triple("t1-2", "bash", ARGS_LS),
                    ),
                )
            )
            transport.receive(toolExecutionStart("t1-1", "read"))
            transport.receive(toolResultEnd("t1-1", "read", detailsById.getValue("t1-1")))
            transport.receive(toolExecutionStart("t1-2", "bash"))
            transport.receive(toolResultEnd("t1-2", "bash", detailsById.getValue("t1-2")))
            transport.receive(event("""{"type":"agent_settled"}"""))
            transport.receive(event("""{"type":"agent_start"}"""))
            transport.receive(textDelta("第二回合的正文。"))
            transport.receive(
                assistantEnd(
                    text = "第二回合的正文。",
                    toolCalls = listOf(Triple("t2-1", "bash", ARGS_LS)),
                )
            )
            transport.receive(toolExecutionStart("t2-1", "bash"))
            transport.receive(toolResultEnd("t2-1", "bash", detailsById.getValue("t2-1")))
            transport.receive(event("""{"type":"agent_settled"}"""))
            return
        }
        val rounds = if (sameTextTwice) 2 else 1
        repeat(rounds) { round ->
            val p1 = "t$round-1"
            val p2 = "t$round-2"
            transport.receive(event("""{"type":"agent_start"}"""))
            transport.receive(messageStartAssistant())
            transport.receive(textDelta("流式正文开头。"))
            transport.receive(
                assistantEnd(
                    text = assistantMarkdown,
                    toolCalls = listOf(
                        Triple(p1, "read", ARGS_READ),
                        Triple(p2, "bash", ARGS_LS),
                    ),
                )
            )
            transport.receive(toolExecutionStart(p1, "read"))
            transport.receive(toolResultEnd(p1, "read", detailsById.getValue(p1)))
            transport.receive(toolExecutionStart(p2, "bash"))
            transport.receive(toolResultEnd(p2, "bash", detailsById.getValue(p2)))
            transport.receive(event("""{"type":"agent_settled"}"""))
        }
    }

    /** 只开到 streaming（不 settled）：流式→落定用例自行驱动后续事件。 */
    private fun beginConversationStreaming() {
        setUpConnectedSession()
        transport.receive(event("""{"type":"agent_start"}"""))
        transport.receive(messageStartAssistant())
        transport.receive(textDelta("流式正文开头。"))
        transport.receive(
            assistantEnd(
                text = "流式正文开头。",
                toolCalls = listOf(Triple("t0-1", "read", ARGS_READ)),
            )
        )
        transport.receive(toolExecutionStart("t0-1", "read"))
        transport.receive(toolResultEnd("t0-1", "read", detailsById.getValue("t0-1")))
        runBlocking {
            waitUntil { holder.snapshot.sessionStates[SESSION]?.isStreaming == true }
        }
    }

    private fun setUpConnectedSession() {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        holder = graph.stateHolder
        runBlocking { holder.loadFixtureAndAwaitForTest() }
        runCatching { runBlocking { waitUntil { transport.specs.isNotEmpty() } } }
        transport.open()
        transport.receive(
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","abort","history","state","models","name"]}"""
        )
        runBlocking { waitUntil { holder.gatewayState.connection.name == "CONNECTED" } }
    }

    private fun waitUntilAssistantShown(count: Int) {
        runCatching {
            compose.waitUntil(10_000) {
                holder.snapshot.conversation.isNotEmpty() &&
                    nodesWithTagPrefix("finecat.ai.").fetchSemanticsNodes().size >= count
            }
        }.onFailure { e ->
            val items = holder.snapshot.conversation
            throw AssertionError(
                "助手消息未到位（期望 $count 条）：conversation=${items.size} 条 " +
                    "kinds=${items.take(6).map { it.kind }} " +
                    "sessionState=${holder.snapshot.sessionStates[SESSION]?.let { "streaming=${it.isStreaming}" }} " +
                    "gateway=${holder.gatewayState.connection.name}",
                e,
            )
        }
    }

    private fun waitUntilWorkingRow() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("finecat.working").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun event(json: String): String {
        seq += 1
        // time 固定 0：epochSeconds=0 时不渲染时间分隔行（FcTimeSeparator 条件
        // epochSeconds>0），间距/复制断言只针对 F02 目标行为，不混入 tsep 占位。
        return """{"kind":"event","sessionId":"$SESSION","seq":$seq,"time":0.0,"event":$json}"""
    }

    private fun messageStartAssistant(): String =
        event("""{"type":"message_start","message":{"role":"assistant"}}""")

    private fun textDelta(text: String): String =
        event(
            """{"type":"message_update","assistantMessageEvent":""" +
                """{"type":"text_delta","delta":${jstr(text)}}}""",
        )

    private fun toolExecutionStart(id: String, name: String): String =
        event(
            """{"type":"tool_execution_start","toolCallId":${jstr(id)},"toolName":${jstr(name)}}""",
        )

    private fun toolResultEnd(id: String, name: String, text: String): String =
        event(
            """{"type":"message_end","message":{"role":"toolResult","toolCallId":${jstr(id)},""" +
                """"toolName":${jstr(name)},"content":[{"type":"text","text":${jstr(text)}}],"isError":false}}""",
        )

    /** pi 原生 queue_update（归一器入参用下划线）：steering/followUp 排队快照。 */
    private fun queueUpdate(steering: List<String>, followUp: List<String>): String =
        event(
            """{"type":"queue_update","steering":[${steering.joinToString(",") { jstr(it) }}],""" +
                """"followUp":[${followUp.joinToString(",") { jstr(it) }}]}""",
        )

    private fun assistantEndFrame(): String = assistantEnd(assistantMarkdown)

    /** 助手消息落定帧：text 可空；toolCalls 为 (id, name, argsJson)（pi 原生 toolCall 块）。 */
    private fun assistantEnd(text: String?, toolCalls: List<Triple<String, String, String>> = emptyList()): String {
        val content = buildList {
            if (!text.isNullOrBlank()) add("""{"type":"text","text":${jstr(text)}}""")
            toolCalls.forEach { (id, name, args) ->
                add("""{"type":"toolCall","toolCallId":${jstr(id)},"toolName":${jstr(name)},"input":$args}""")
            }
        }
        return event(
            """{"type":"message_end","message":{"role":"assistant","stopReason":"stop",""" +
                """"content":[${content.joinToString(",")}]}}""",
        )
    }

    private fun assistantEnd(text: String): String = assistantEnd(text = text, toolCalls = emptyList())

    private fun jstr(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    // ---- 查询与交互 ----

    /** testTag 前缀匹配的节点集合（组合顺序=列表顺序；投影 item id 形状不进测试）。 */
    private fun nodesWithTagPrefix(prefix: String) =
        compose.onAllNodes(
            SemanticsMatcher("testTag 以 $prefix 开头") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
            },
        )

    /** 第 index 条助手消息的复制按钮（index 按对话顺序）。 */
    private fun copyButton(index: Int): SemanticsNodeInteraction =
        compose.onNodeWithTag("finecat.copy.${assistantMessageIdAt(index)}")

    /** 第 index 条助手消息的真实投影 id（holder 状态读取，不写死）。 */
    private fun assistantMessageIdAt(index: Int): String =
        holder.snapshot.conversation
            .filter { it.kind == com.finecat.pimobile.shared.projection.ConversationItemKind.ASSISTANT }
            .getOrNull(index)?.id
            ?: error("第 $index 条助手消息不存在")

    /** 第 index 条助手消息的全文（UI 复制语义的源文本）。 */
    private fun assistantTextAt(index: Int): String =
        holder.snapshot.conversation
            .filter { it.kind == com.finecat.pimobile.shared.projection.ConversationItemKind.ASSISTANT }
            .getOrNull(index)?.text
            ?: error("第 $index 条助手消息不存在")

    /** 步骤组真实组 id 列表（从 displayEntries 计算）。 */
    private fun processGroupIds(): List<String> =
        mergeFineCatProcessEntries(com.finecat.pimobile.android.ui.makeConversationDisplayEntries(holder.snapshot.conversation))
            .filterIsInstance<com.finecat.pimobile.android.ui.ConversationDisplayEntry.Process>()
            .map { it.id }

    /** 末行文案断言（失败带运行状态诊断）。 */
    private fun assertFooterText(expected: String) {
        runCatching {
            compose.onNodeWithTag("finecat.workingText").assertTextContains(expected)
        }.onFailure { e ->
            val st = holder.snapshot.sessionStates[SESSION]
            throw AssertionError(
                "末行文案不符（期望 $expected）：isStreaming=${st?.isStreaming} " +
                    "isCompacting=${st?.isCompacting}", e,
            )
        }
    }

    /** 对话列表滚动到精确 testTag 的节点（LazyColumn 离屏项无语义）。 */
    private fun scrollListToTag(tag: String) {
        compose.onNodeWithTag("finecat.list")
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun scrollListTo(tag: String) {
        compose.onNodeWithTag("finecat.list")
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun stepTag(label: String, index: Int): String {
        val step = mergeFineCatProcessEntries(com.finecat.pimobile.android.ui.makeConversationDisplayEntries(holder.snapshot.conversation))
            .filterIsInstance<com.finecat.pimobile.android.ui.ConversationDisplayEntry.Process>()
            .flatMap { fineCatSteps(it.group) }.filter { it.arg.startsWith(label) }[index]
        return "finecat.step.${step.id}"
    }

    /** 按真实投影的稳定步骤id定位，避免统一的“执行命令”标签串到另一条。 */
    private fun clickStepRow(label: String, index: Int) {
        val tag = stepTag(label, index)
        compose.onNodeWithTag("finecat.list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun clickGroupById(groupId: String) {
        val tag = "finecat.stepsHeader.$groupId"
        scrollListToTag(tag)
        compose.onNodeWithTag(tag).performTouchInput { click() }
        compose.waitForIdle()
    }

    /** 测试进程内注入/读取系统配置（矩阵用例专用，finally 恢复）。 */
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
            .bufferedReader().use { it.readText() }
    }

    private fun restoreDeviceConfig() {
        shell("wm density 480")
        shell("settings put system font_scale 1.0")
        Thread.sleep(1_000)
        val density = shell("wm density")
        val fontScale = shell("settings get system font_scale")
        assertTrue(
            "矩阵步骤结束必须恢复设备配置：density=[$density] fontScale=[$fontScale]",
            density.contains("Override density: 480") && fontScale.trim() == "1.0",
        )
    }

    /** 整屏截图存 external filesDir（capture 拉取归领），文件名即证据名。 */
    private fun saveShot(name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { out ->
            shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }


    private companion object
}
