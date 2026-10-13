package com.finecat.pimobile.android.ui.finecat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2 验收（S4 静态/交互部分）：整组步骤折叠不串、多工具行同时展开、复制内容
 * 正确、Working 只由运行状态控制、composer 三态、跳最新。亮暗截图存证据目录。
 */
@RunWith(AndroidJUnit4::class)
class FcChatDemoDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<FineCatGalleryActivity>()

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "finecat-p2-evidence").apply { mkdirs() }
    }

    private fun capture(name: String, tag: String) {
        repeat(5) {
            try {
                val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                File(evidenceDir(), "$name.png").outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                return
            } catch (e: Exception) {
                Thread.sleep(400)
            }
        }
        throw IllegalStateException("capture $name failed")
    }

    /** 点击改变组合后，语义树更新与查询存在竞态：轮询直到条件成立。 */
    private fun waitText(text: String, present: Boolean) {
        try {
            rule.waitUntil(timeoutMillis = 3_000) {
                val found = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
                found == present
            }
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
            android.util.Log.e("ChatDump3", "waitText($text, $present) timeout:\n" + sb)
            throw e
        }
    }

    private fun gotoChatDemo() {
        rule.onNodeWithTag("gallery.chatDemo").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("chat.root").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun s4_chat_static_lightDark_copy() {
        gotoChatDemo()
        rule.waitForIdle()
        capture("11-chat-light", "chat.root")

        // 复制内容正确：m2 的复制按钮在文档序最前，剪贴板应等于该条正文（不含工具输出/思考/UI 词）
        val firstCopy = rule.onAllNodesWithContentDescription("复制全文")[0]
        firstCopy.performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        org.junit.Assert.assertTrue("clipboard missing m2 text: $copied", copied != null && copied.contains("定位到：表格 span"))
        org.junit.Assert.assertTrue("copy must not include tool output", !copied!!.contains("AssertionError"))
        org.junit.Assert.assertTrue("copy must not include thought", !copied.contains("先复现"))

        // 深色
        rule.onNodeWithTag("chat.themeToggle").performClick()
        rule.waitForIdle()
        capture("12-chat-dark", "chat.root")
        rule.onNodeWithTag("chat.themeToggle").performClick()
        rule.waitForIdle()
    }

    @Test
    fun s4_stepGroups_collapseIndependently_andRowsExpand() {
        gotoChatDemo()
        rule.waitForIdle()

        // Lazy 只组合视口附近的项：定位到 m2（index 1）
        rule.onNodeWithTag("chat.list").performScrollToIndex(1)
        rule.waitForIdle()

        // 展开 m2 的两行输出：同屏同时展开（多工具行同时展开）
        rule.onNodeWithText("读取").assertIsDisplayed()
        rule.onNodeWithText("读取").performClick()
        waitText("tableSpan.draw", present = true)
        rule.onNodeWithText("运行测试").assertIsDisplayed()
        rule.onNodeWithText("运行测试").performClick()
        waitText("AssertionError", present = true)

        // 收起 m2 组：组内步骤行不再被组合
        rule.onNodeWithText("深度思考 · 2 次工具调用").assertIsDisplayed()
        rule.onNodeWithText("深度思考 · 2 次工具调用").performClick()
        waitText("PiMarkdownText.kt", present = false)
        waitText("AssertionError", present = false)

        // m7（index 6）不受影响：组仍展开，行与输出在
        rule.onNodeWithTag("chat.list").performScrollToIndex(6)
        rule.waitForIdle()
        rule.onNodeWithText("编辑").assertIsDisplayed()
        rule.onNodeWithText("TableSpan.kt +12 -3").assertExists()
        rule.onNodeWithText("编辑").performClick()
        waitText("themeColor()", present = true)

        // 回到 m2 重新展开组
        rule.onNodeWithTag("chat.list").performScrollToIndex(1)
        rule.waitForIdle()
        rule.onNodeWithText("深度思考 · 2 次工具调用").assertIsDisplayed()
        rule.onNodeWithText("深度思考 · 2 次工具调用").performClick()
        waitText("PiMarkdownText.kt", present = true)
    }

    @Test
    fun s4_composer_threeStates_shotsToggles_jump() {
        gotoChatDemo()
        rule.waitForIdle()

        // idle：胶囊（模型 chip 不可见）
        rule.onNodeWithText("glm-5.3-flash").assertDoesNotExist()

        // 输入文本 → 展开卡片（模型 chip 出现）
        rule.onNodeWithTag("fc.composer.input").performTextInput("帮我把首帧检查加进 CI")
        rule.waitForIdle()
        rule.onNodeWithText("glm-5.3-flash").assertExists()
        capture("13-composer-expanded", "chat.root")

        // 开选图条 → 卡片 + 选图条（11 格），点第 1 格打勾
        rule.onNodeWithContentDescription("添加图片").performClick()
        rule.waitUntil(timeoutMillis = 3_000) {
            rule.onAllNodesWithContentDescription("最近照片 1").fetchSemanticsNodes().isNotEmpty()
        }
        val shot1 = rule.onNodeWithContentDescription("最近照片 1")
        shot1.performClick()
        shot1.assertIsSelected()
        rule.onNodeWithContentDescription("打开相册").assertExists()
        capture("14-composer-shots", "chat.root")

        // 跳最新：向上滚（起点避开底部中央的跳最新按钮）后出现，点击后回到底部并消失
        rule.onNodeWithTag("chat.list").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, centerY + height * 0.25f))
            moveTo(androidx.compose.ui.geometry.Offset(centerX, centerY))
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("chat.jump").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chat.jump").assertDoesNotExist()
    }

    @Test
    fun s4_workingDrivenOnlyByRunState_andStopSemantics() {
        gotoChatDemo()
        rule.waitForIdle()

        // Working 行挂在列表末尾，LazyColumn 只组合可见项：滚到最后一项
        rule.onNodeWithTag("chat.list").performScrollToIndex(6)
        rule.waitForIdle()

        // 冻结 compose 时钟，避免无限呼吸动画阻塞 idle 等待
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("chat.runToggle").performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.waitUntil(timeoutMillis = 3_000) {
            rule.onAllNodesWithTag("chat.working").fetchSemanticsNodes().isNotEmpty()
        }
        // 运行中发送位语义 = 停止生成
        rule.onNodeWithContentDescription("停止生成").assertExists()

        // 点击停止 → 回到发送语义，Working 消失
        rule.onNodeWithContentDescription("停止生成").performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.waitUntil(timeoutMillis = 3_000) {
            rule.onAllNodesWithTag("chat.working").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithContentDescription("发送").assertExists()

        rule.mainClock.autoAdvance = true
    }
}
