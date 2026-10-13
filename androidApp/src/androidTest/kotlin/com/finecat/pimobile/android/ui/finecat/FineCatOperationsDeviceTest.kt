package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.finecat.pimobile.android.ui.finecat.conversation.*
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 合成展示数据；设备用例只编译时不能记为真实运行或验收。 */
class FineCatOperationsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()
    private val steps = listOf(
        FcStep("reason", label = "深度思考", isThought = true, detail = "完整合成思考"),
        FcStep("read", label = "执行命令", toolName = "read", command = """{"path":"README.md"}""",
            completed = true, detail = "完整合成结果"),
    )

    @Composable
    private fun Content(groups: Map<String, List<FcStep>>, running: Boolean = false,
        surroundingBody: Boolean = false) {
        var expanded by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
        Column(Modifier.fillMaxSize().background(LocalFineCatColors.current.bg)
            .verticalScroll(rememberScrollState()).testTag("test.scroll")) {
            if (surroundingBody) repeat(12) { i ->
                Text("合成前文 $i\n正文滚动范围\n测试外部起手", Modifier.testTag("test.before.$i"))
            }
            groups.forEach { (groupId, list) ->
                FcStepsBlock(groupId, list, onOpenGroup = { id -> expanded = if (expanded.containsKey(id))
                    expanded - id else expanded + (id to null) }, expanded = expanded.containsKey(groupId),
                    expandedStepId = expanded[groupId],
                    onOpenStep = { id -> expanded = expanded + (groupId to if (expanded[groupId] == id) null else id) },
                    isRunning = running)
            }
            FcAiMessage(FcChatMessage("answer", false, "正文仍是阅读重点"), {})
            if (running) FcWorking("Working")
            if (surroundingBody) repeat(12) { i ->
                Text("合成后文 $i\n正文滚动范围\n测试外部起手", Modifier.testTag("test.after.$i"))
            }
        }
    }

    @Test fun summariesDefaultCollapsed_andGroupsToggleIndependentlyWithoutSheets() {
        val second = steps.map { it.copy(id = "two-${it.id}") }
        compose.setContent { FineCatTheme { Content(mapOf("one" to steps, "two" to second)) } }
        compose.onNodeWithTag("finecat.stepsList.one").assertDoesNotExist()
        compose.onNodeWithTag("finecat.stepsHeader.one").assertTextContains("完成了 2 个操作")
        compose.onNodeWithTag("finecat.stepsHeader.one").performClick()
        compose.onNodeWithTag("finecat.step.reason").assertExists()
        compose.onNodeWithTag("finecat.step.two-reason").assertDoesNotExist()
        compose.onNodeWithTag("finecat.stepsHeader.two").performClick()
        compose.onNodeWithTag("finecat.step.two-reason").assertExists()
        compose.onNodeWithTag("finecat.step.reason").assertExists()
        compose.onNodeWithTag("finecat.stepsHeader.one").performClick()
        compose.onNodeWithTag("finecat.step.reason").assertDoesNotExist()
        compose.onNodeWithTag("finecat.step.two-reason").assertExists()
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(0)
    }

    @Test fun inlineRowsRemainCompact_andDetailsToggleInPlace() {
        compose.setContent { FineCatTheme { Content(mapOf("one" to steps)) } }
        compose.onNodeWithTag("finecat.stepsHeader.one").performClick()
        val list = compose.onNodeWithTag("finecat.stepsList.one").fetchSemanticsNode().boundsInRoot
        val thought = compose.onNodeWithTag("finecat.step.reason").fetchSemanticsNode().boundsInRoot
        val read = compose.onNodeWithTag("finecat.step.read").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals("列表整体左缩进18dp", 18f, (thought.left-list.left)/density, 1f)
        assertEquals("相邻行不额外加间距", 0f, (read.top-thought.bottom)/density, 1f)
        if (compose.density.fontScale == 1f) assertTrue(thought.height/density in 28f..30f)
        compose.onNodeWithTag("finecat.step.reason").performClick()
        compose.onNodeWithText("完整合成思考").assertExists()
        compose.onNodeWithTag("finecat.step.read").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.stepDetailOutput").performScrollTo().assertTextContains("完整合成结果")
        compose.onNodeWithText("完整合成思考").assertDoesNotExist()
        compose.onNodeWithTag("finecat.step.read").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.stepDetailBody").assertDoesNotExist()
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(0)
    }

    @Test fun longThoughtAndToolDetailsScrollInternally_andSwitchStartsAtTop() {
        val longText = (1..120).joinToString("\n") { "合成详情第 $it 行，保留完整内容" }
        val large = listOf(steps[0].copy(detail = longText),
            steps[1].copy(command = longText, detail = "$longText\n工具结果末行"))
        compose.setContent { FineCatTheme { Content(mapOf("one" to large)) } }
        compose.onNodeWithTag("finecat.stepsHeader.one").performClick()
        compose.onNodeWithTag("finecat.step.reason").performClick()

        fun checkInnerScroll() {
            val detail = compose.onNodeWithTag("finecat.stepDetailBody")
            val range = detail.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals("新详情从顶部开始", 0f, range.value(), 1f)
            assertTrue("长内容有可滚动范围", range.maxValue() > 0f)
            assertTrue("详情最大高度240dp", detail.fetchSemanticsNode().boundsInRoot.height / compose.density.density <= 241f)
            val outerBefore = compose.onNodeWithTag("test.scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            val headerBefore = compose.onNodeWithTag("finecat.stepsHeader.one").fetchSemanticsNode().boundsInRoot.top
            detail.performTouchInput { swipeUp(durationMillis = 350) }
            compose.waitForIdle()
            assertTrue("滑动消费在详情内部", detail.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertEquals("内部滑动不带走对话", outerBefore,
                compose.onNodeWithTag("test.scroll").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            assertEquals("步骤摘要位置保持", headerBefore,
                compose.onNodeWithTag("finecat.stepsHeader.one").fetchSemanticsNode().boundsInRoot.top, 1f)
        }

        checkInnerScroll()
        compose.onNodeWithTag("finecat.stepDetailText").assertTextContains(longText)
        compose.onNodeWithTag("finecat.step.read").performScrollTo().performClick()
        checkInnerScroll()
        compose.onNodeWithTag("finecat.stepDetailCommand").assertTextContains(longText)
        compose.onNodeWithTag("finecat.stepDetailOutput").assertTextContains("工具结果末行", substring = true)
    }

    @Test fun detailEdgesKeepDragAndFlingLocal_andOutsideGestureStillScrollsConversation() {
        val longText = (1..120).joinToString("\n") { "合成详情第 $it 行" }
        val cases = listOf(steps[0].copy(detail = longText),
            steps[1].copy(command = longText, detail = longText),
            steps[0].copy(id = "short", detail = "短思考"))
        compose.setContent { FineCatTheme { Content(mapOf("one" to cases), surroundingBody = true) } }
        compose.onNodeWithTag("finecat.stepsHeader.one").performScrollTo().performClick()

        fun outerOffset() = compose.onNodeWithTag("test.scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("外层必须确实可滚动", compose.onNodeWithTag("test.scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)

        cases.forEach { step ->
            compose.onNodeWithTag("finecat.step.${step.id}").performScrollTo().performClick()
            val detail = compose.onNodeWithTag("finecat.stepDetailBody").performScrollTo()
            compose.waitForIdle()
            val range = detail.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (step.id == "short") assertEquals(0f, range.maxValue(), 1f)
            else assertTrue(range.maxValue() > 0f)

            for (atBottom in listOf(true, false)) {
                detail.performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, if (atBottom) range.maxValue() else -range.maxValue())
                }
                compose.waitForIdle()
                val outerBefore = outerOffset()
                val innerBefore = range.value()
                val headerBefore = compose.onNodeWithTag("finecat.stepsHeader.one")
                    .fetchSemanticsNode().boundsInRoot.top
                // 两次独立的内侧快速手势也不能把边缘余量续接到外层。
                repeat(2) {
                    detail.performTouchInput {
                        if (atBottom) swipeUp(durationMillis = 100) else swipeDown(durationMillis = 100)
                    }
                    compose.waitForIdle()
                    assertEquals("${step.id} 的边缘不转交外层", outerBefore, outerOffset(), 1f)
                    assertEquals("边缘处内部停住", innerBefore, range.value(), 1f)
                    assertEquals("步骤摘要位置不变", headerBefore,
                        compose.onNodeWithTag("finecat.stepsHeader.one").fetchSemanticsNode().boundsInRoot.top, 1f)
                }
            }
        }
        val outside = compose.onNodeWithTag("test.after.0").performScrollTo()
        val beforeOutside = outerOffset()
        outside.performTouchInput { swipeUp(durationMillis = 100) }
        compose.waitForIdle()
        assertTrue("从详情外重新起手仍能滚动会话", outerOffset() > beforeOutside)
    }

    @Test fun appendingAndSettlingKeepOpenedDetail_andReportFailuresSeparately() {
        val current = mutableStateOf(steps.take(1))
        val running = mutableStateOf(true)
        compose.setContent { FineCatTheme { Content(mapOf("live" to current.value), running.value) } }
        compose.onNodeWithTag("finecat.stepsHeader.live").assertTextContains("正在执行 1 个操作")
        compose.onNodeWithTag("finecat.stepsHeader.live").performClick()
        compose.onNodeWithTag("finecat.step.reason").performClick()
        compose.runOnIdle { current.value = steps + steps[1].copy(id = "fail", failed = true) }
        compose.onNodeWithText("完整合成思考").assertExists()
        compose.onNodeWithTag("finecat.stepsHeader.live").assertTextContains("3 个操作 · 1 个失败")
        compose.onNodeWithTag("finecat.step.fail").assertTextContains("读取失败 README.md")
        assertEquals(listOf("失败"), compose.onNodeWithTag("finecat.step.fail")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        compose.runOnIdle { running.value = false }
        compose.onNodeWithTag("finecat.stepsHeader.live").assertTextContains("完成了 3 个操作")
        compose.onNodeWithText("Working").assertDoesNotExist()
    }

    @Test fun allEightyStepsAndFullLongOutputRemainReachableWithoutHeightCap() {
        val many = List(80) { steps[1].copy(id = "read-$it", command = """{"path":"file-$it"}""",
            detail = List(100) { line -> "result-$it-$line" }.joinToString("\n")) }
        compose.setContent { FineCatTheme { Content(mapOf("many" to many)) } }
        compose.onNodeWithTag("finecat.stepsHeader.many").performClick()
        compose.onNodeWithTag("finecat.step.read-79").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.stepDetailOutput").assertTextContains("result-79-99")
        compose.onNodeWithTag("finecat.stepsList.many").assertExists()
        compose.onAllNodesWithTag("finecat.sheetCard").assertCountEquals(0)
    }

    @Test fun summaryClickActuallyAnimatesListHeight_andCollapseReturnsToHeader() {
        compose.mainClock.autoAdvance = false
        compose.setContent { FineCatTheme { Content(mapOf("animated" to steps)) } }
        compose.mainClock.advanceTimeBy(500)
        val closed = compose.onNodeWithTag("finecat.steps.animated").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("finecat.stepsHeader.animated").performClick()
        compose.mainClock.advanceTimeBy(160)
        val middle = compose.onNodeWithTag("finecat.steps.animated").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(400)
        val open = compose.onNodeWithTag("finecat.steps.animated").fetchSemanticsNode().boundsInRoot.height
        assertTrue("列表必须经历展开高度过渡", middle > closed && middle < open)
        compose.onNodeWithTag("finecat.stepsHeader.animated").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(closed, compose.onNodeWithTag("finecat.steps.animated").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("finecat.stepsList.animated").assertDoesNotExist()
    }

    @Test fun blackUserBubblesKeepWhiteTextAndCopyInBothThemes() {
        val dark = mutableStateOf(false)
        var copied = ""
        compose.setContent { FineCatTheme(darkTheme = dark.value) {
            FcUserBubble(FcChatMessage("u", true, "用户消息可复制"), onCopy = { copied = it })
        } }
        repeat(2) { theme ->
            compose.runOnIdle { dark.value = theme == 1 }
            val image = compose.onNodeWithTag("finecat.userBubble.u").captureToImage().asAndroidBitmap()
            val colors = IntArray(image.width * image.height)
            image.getPixels(colors, 0, image.width, 0, 0, image.width, image.height)
            assertTrue("必须存在#111黑色底", colors.count { it and 0xffffff == 0x111111 } > colors.size/4)
            assertTrue("白色文字/图标可见", colors.any { it and 0xffffff == 0xffffff })
            compose.onNodeWithTag("finecat.copy.u").performClick()
            assertEquals("用户消息可复制", copied)
        }
    }
}
