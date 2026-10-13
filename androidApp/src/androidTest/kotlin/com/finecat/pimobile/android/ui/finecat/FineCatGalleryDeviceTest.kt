package com.finecat.pimobile.android.ui.finecat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1 验收 V1/V2/V3 的真实交互证据：中心点击、Back 逐层退出、长列表滚动、
 * 亮暗截图、fontScale 1.3。截图存到应用外部 files 目录，跑完 `adb pull` 取证。
 * 只跑在隔离模拟器（04 §8），不触碰真机。
 */
@RunWith(AndroidJUnit4::class)
class FineCatGalleryDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<FineCatGalleryActivity>()

    /** 系统级 Back：路由到当前焦点窗口。焦点在 focusable Popup 上时由弹层消费，
     *  否则由 Activity 的 BackHandler/默认行为处理——与真实按键一致。 */
    private fun pressBack() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("input keyevent KEYCODE_BACK").close()
        Thread.sleep(300)
    }

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "finecat-p1-evidence").apply { mkdirs() }
    }

    /** 截当前画面：compose 捕获宿主窗口（供像素采样），另用 screencap 存一张
     *  含 Popup 窗口的全屏图作弹层叠加证据。窗口 surface 可能瞬时不可用，重试几次。 */
    private fun capture(name: String): Pair<Int, Int> {
        var last: Exception? = null
        repeat(5) {
            try {
                val bitmap = rule.onNodeWithTag("gallery.root").captureToImage().asAndroidBitmap()
                File(evidenceDir(), "$name.png").outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val w = bitmap.width
                val h = bitmap.height
                val samples = bitmap.getPixel(w / 2, h / 10) to bitmap.getPixel(w / 2, h / 2)
                screencap("$name-screen.png")
                return samples
            } catch (e: IllegalArgumentException) {
                last = e
                Thread.sleep(500)
            } catch (e: AssertionError) {
                last = IllegalStateException(e)
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("capture $name failed after retries", last)
    }

    /** 全屏截图（含 Popup 独立窗口）到 /data/local/tmp（shell 可写，应用外部目录不可写），
     *  失败不影响测试。 */
    private fun screencap(fileName: String) {
        try {
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.executeShellCommand("screencap -p /data/local/tmp/$fileName").close()
            Thread.sleep(300)
        } catch (_: Exception) {
        }
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun v1_v2_tokensAndRows_lightDark_clicks() {
        waitForTag("gallery.themeToggle")
        val light = capture("01-components-light")

        // V2：视图分段第三枚（tree）真实点击切换，selected 语义随之翻转
        val segTree = rule.onNodeWithContentDescription("tree")
        segTree.assertIsNotSelected()
        segTree.performClick()
        segTree.assertIsSelected()

        // V2：开关行整行点击，Role.Switch 语义只有一份，stateDescription 翻转
        val toggleRow = rule.onNodeWithTag("gallery.toggleRow")
        toggleRow.performClick()
        toggleRow.assert(hasStateDescription("开"))
        toggleRow.performClick()
        toggleRow.assert(hasStateDescription("关"))

        // V1：切到深色，同名 token 应变值（像素采样随主题改变）
        rule.onNodeWithTag("gallery.themeToggle").performClick()
        rule.waitForIdle()
        val dark = capture("02-components-dark")
        assert(light != dark) { "dark screenshot pixels identical to light; theme toggle did not change colors" }

        // 切回浅色，保持设备状态可预期
        rule.onNodeWithTag("gallery.themeToggle").performClick()
        rule.waitForIdle()
    }

    @Test
    fun v3_sheet_scroll_popover_centerClick_backExitsOneLayer() {
        waitForTag("gallery.longSheetRow")

        // 打开长内容 sheet（断言只存在于打开态的内容节点）
        rule.onNodeWithTag("gallery.longSheetRow").performClick()
        waitForTag("gallery.longSheetContent")
        rule.waitForIdle()

        // sheet 内打开 popover（层级：Popover > Sheet）
        rule.onNodeWithTag("gallery.popoverAnchorInSheet").performClick()
        waitForTag("gallery.popover.item0")
        rule.waitForIdle()
        capture("04-popover-over-sheet")

        // Back 只退 popover：弹层关闭，sheet 仍在
        pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("gallery.popover.item0").assertDoesNotExist()
        rule.onNodeWithTag("gallery.longSheetContent").assertExists()

        // 再开 popover，item0 中心真实点击：选中值从第 1 项回到第 0 项
        rule.onNodeWithTag("gallery.popoverAnchorInSheet").performClick()
        waitForTag("gallery.popover.item0")
        rule.onNodeWithTag("gallery.popover.item0").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("gallery.popover.item0").assertDoesNotExist()
        rule.onNodeWithTag("gallery.popoverAnchor").assertTextEquals("方言选择", "Popover 悬于 Sheet 之上", "anthropic")
        capture("05-popover-dismissed")

        // sheet 内真实触摸滚动到底（sheet-body 滚动约束）
        rule.onNodeWithTag("gallery.longSheetContent").performTouchInput { swipeUp() }
        rule.waitForIdle()
        capture("03-sheet-scrolled")

        // Back 退掉 sheet（内容节点消失），且 Activity 未被关闭
        pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag("gallery.longSheetContent").assertDoesNotExist()
        rule.onNodeWithTag("gallery.root").assertExists()
    }

    @Test
    fun longList_scrollToEnd() {
        waitForTag("gallery.themeToggle")
        // 真实触摸滑动到底部（对应用户滚动）；verticalScroll 的节点始终在语义树里，
        // 必须用 assertIsDisplayed 判可见而不是判存在
        var displayed = false
        repeat(12) {
            try {
                rule.onNodeWithTag("gallery.longRow.60").assertIsDisplayed()
                displayed = true
                return@repeat
            } catch (_: AssertionError) {
                rule.onNodeWithTag("gallery.root").performTouchInput { swipeUp() }
                rule.waitForIdle()
            }
        }
        assert(displayed) { "longRow.60 not visible after scrolling" }
        capture("07-longlist-end")
    }

    /**
     * fontScale 证据：由外部先 `settings put system font_scale 1.3` 再单独运行本方法，
     * Activity 以 1.3 启动，避免测试中途配置重建卡住 compose 同步；跑完外部恢复 1.0。
     */
    @Test
    fun fontScale_1_3_componentsStillUsable() {
        waitForTag("gallery.themeToggle")
        capture("08-fontscale-1_3")
        // 开关行在 1.3 下仍整行可点，语义翻转可观察
        val toggleRow = rule.onNodeWithTag("gallery.toggleRow")
        toggleRow.performClick()
        toggleRow.assert(hasStateDescription("开"))
        toggleRow.performClick()
        toggleRow.assert(hasStateDescription("关"))
    }
}
