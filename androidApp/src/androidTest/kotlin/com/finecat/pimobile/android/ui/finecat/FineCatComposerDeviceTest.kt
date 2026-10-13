package com.finecat.pimobile.android.ui.finecat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidGatewayViewModel
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.MainActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4b：composer 与真实门面绑定——草稿写入 holder、模型 sheet 空态。
 * 真实发送/停止/吸附派发经 P11 隔离网关验收（本测试环境无网关连接）。
 */
@RunWith(AndroidJUnit4::class)
class FineCatComposerDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun p4b_composer_bindsRealDraft_andModelSheetEmptyState() {
        lateinit var holder: AndroidSharedStateHolder
        rule.activityRule.scenario.onActivity { activity ->
            holder = androidx.lifecycle.ViewModelProvider(activity)[AndroidGatewayViewModel::class.java].stateHolder
            holder.debugUseFineCatUi = true
        }
        runBlocking { holder.loadFixtureAndAwaitForTest() }
        rule.waitForIdle()

        // 输入经组件回调写入 holder.messageDraft（单一真源）
        try {
            rule.onNodeWithTag("fc.composer.input").performTextInput("草稿内容")
        } catch (e: AssertionError) {
            val tree = rule.onRoot().fetchSemanticsNode()
            fun dump(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int, sb: StringBuilder) {
                val t = runCatching {
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("|")
                }.getOrNull()
                val st = runCatching { node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }.getOrNull()
                sb.append("  ".repeat(depth)).append("tag=").append(st).append(" text=").append(t).append('\n')
                node.children.forEach { dump(it, depth + 1, sb) }
            }
            val sb = StringBuilder()
            dump(tree, 0, sb)
            val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "p4b-tree.txt")
            out.writeText(sb.toString())
            throw e
        }
        rule.waitForIdle()
        org.junit.Assert.assertEquals("草稿内容", holder.messageDraft)

        // 模型 sheet：fixture 无目录 → 真实空态
        rule.onNodeWithTag("fc.composer.input").performClick()
        rule.onNodeWithTag("finecat.modelChip").performClick()
        rule.waitUntil(timeoutMillis = 3_000) {
            rule.onAllNodesWithTag("finecat.modelSheet").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("暂无可用模型", substring = true).assertExists()
    }

    @Test
    fun p4d_slashCommandConsumedBeforeSend() {
        lateinit var holder: AndroidSharedStateHolder
        rule.activityRule.scenario.onActivity { activity ->
            holder = androidx.lifecycle.ViewModelProvider(activity)[AndroidGatewayViewModel::class.java].stateHolder
            holder.debugUseFineCatUi = true
        }
        runBlocking { holder.loadFixtureAndAwaitForTest() }
        rule.waitForIdle()

        // /name 是门面消费的内置命令：CONSUMED → 不发送、清草稿、出提示
        rule.onNodeWithTag("fc.composer.input").performTextInput("/name 新名字")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("发送").performClick()
        rule.waitUntil(timeoutMillis = 3_000) { holder.messageDraft.isEmpty() }
        rule.onNodeWithText("点顶部标题或长按会话列表即可改名", substring = true).assertExists()
    }
}
