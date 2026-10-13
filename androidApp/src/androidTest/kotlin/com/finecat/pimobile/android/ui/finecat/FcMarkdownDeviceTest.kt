package com.finecat.pimobile.android.ui.finecat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3 路线 A 实验的 M1–M6 关键证据（F4 fixture）：
 * 宽表首帧、真实横拖像素变化、两表独立偏移、流式未闭合→闭合、主题切换、块类型齐备。
 * 截图与像素采样落证据目录；像素对比不是仅 offset 断言（01 §6/M8）。
 */
@RunWith(AndroidJUnit4::class)
class FcMarkdownDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<FineCatGalleryActivity>()

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "finecat-p3-evidence").apply { mkdirs() }
    }

    private fun capture(name: String, tag: String): Pair<Int, Int> {
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(evidenceDir(), "$name.png").outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        return bitmap.getPixel(bitmap.width / 4, bitmap.height / 3) to
            bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 2 / 3)
    }

    /** 多步横向拖拽：单次瞬移不触发滚动轴锁。 */
    private fun dragLeft(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, centerY))
            var x = centerX
            while (x > centerX - width * 0.55f) {
                x -= width * 0.1f
                moveTo(androidx.compose.ui.geometry.Offset(x, centerY))
            }
            up()
        }
        rule.waitForIdle()
    }

    private fun gotoMarkdownDemo() {
        rule.onNodeWithTag("gallery.markdownDemo").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("md.root").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun m1_m2_wideTable_firstFrame_horizontalDrag_pixelChange() {
        gotoMarkdownDemo()
        // M1：冷启动第一帧，宽表首行立即可见（不依赖二次刷新）
        val table = rule.onNodeWithTag("md.table.0")
        table.assertIsDisplayed()
        capture("21-md-light", "md.root")

        // M2：宽表真实横拖——直接捕获表格节点位图，内容区域像素必须变化
        fun captureTable(name: String): android.graphics.Bitmap {
            val bitmap = table.captureToImage().asAndroidBitmap()
            File(evidenceDir(), name).outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            return bitmap
        }
        val before = captureTable("md-table-before.png")
        dragLeft("md.table.0")
        val afterDrag = captureTable("md-table-dragged.png")
        org.junit.Assert.assertFalse(
            "table pixels identical after horizontal drag",
            before.sameAs(afterDrag),
        )

        // 纵向列表仍可滚（拖表后外层滚动不失效）：系统级纵滑前后整屏像素不同
        val beforeScroll = rule.onNodeWithTag("md.root").captureToImage().asAndroidBitmap()
        systemSwipeUp()
        rule.waitForIdle()
        val afterScroll = rule.onNodeWithTag("md.root").captureToImage().asAndroidBitmap()
        org.junit.Assert.assertFalse("vertical scroll did not change pixels", beforeScroll.sameAs(afterScroll))
    }

    /** 系统级上滑（绕开 compose 语义滚动的稳定性问题）。 */
    private fun systemSwipeUp() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("input swipe 585 1700 585 900 220").close()
        Thread.sleep(400)
    }

    @Test
    fun m3_twoTables_independentOffsets() {
        gotoMarkdownDemo()
        rule.waitForIdle()
        // 两张同文表：拖第一张后，第二张的内容像素不变
        val tableA = rule.onNodeWithTag("md.table.1")
        val tableB = rule.onNodeWithTag("md.table.2")
        tableA.assertIsDisplayed()
        tableB.assertIsDisplayed()
        val bBefore = tableB.captureToImage().asAndroidBitmap().getPixel(100, 40)
        dragLeft("md.table.1")
        rule.waitForIdle()
        val bAfter = tableB.captureToImage().asAndroidBitmap().getPixel(100, 40)
        org.junit.Assert.assertEquals("table B must not move when table A scrolls", bBefore, bAfter)
    }

    @Test
    fun m4_streaming_unclosedToClosed() {
        gotoMarkdownDemo()
        rule.waitForIdle()
        // 未闭合态（fixture 初值即未闭合表头）：无崩溃、表头行已渲染
        rule.onNodeWithText("列一", substring = true).assertIsDisplayed()
        // 模拟流式补齐 → 闭合后行内容出现
        rule.onNodeWithTag("md.streamToggle").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("行2-A", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // 行2-A 是闭合后新增的首列末行：在视口内可显示
        rule.onNodeWithText("行2-A", substring = true).assertIsDisplayed()
        capture("23-md-streamed", "md.root")
    }

    @Test
    fun m5_themeSwitch_updatesColors() {
        gotoMarkdownDemo()
        rule.waitForIdle()
        val light = capture("24-md-light2", "md.root")
        rule.onNodeWithTag("md.themeToggle").performClick()
        rule.waitForIdle()
        val dark = capture("25-md-dark", "md.root")
        org.junit.Assert.assertNotEquals(light, dark)
        rule.onNodeWithTag("md.themeToggle").performClick()
        rule.waitForIdle()
    }

    @Test
    fun m6_blockVarieties_render() {
        gotoMarkdownDemo()
        rule.waitForIdle()
        // Markwon 段落文本在 AndroidView(TextView) 内，不在 Compose 语义树：
        // 滚到段落区截图（列表/引用/链接/行内样式靠截图目验），Compose 侧文本（代码块）用断言
        systemSwipeUp()
        rule.waitForIdle()
        rule.onNodeWithText("println(", substring = true).assertExists()
        capture("26-md-blocks", "md.root")
    }
}
