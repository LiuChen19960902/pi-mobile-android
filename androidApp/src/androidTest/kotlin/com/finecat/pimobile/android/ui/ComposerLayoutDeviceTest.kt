package com.finecat.pimobile.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `ComposerLayout` 的键盘压缩布局回归（不接网关、不唤键盘；固定高度容器模拟扣除 IME 后的视口）。
 *
 * 线上 bug：键盘压掉可视高度后，composer 原来那个 Column 里附件 72dp + 输入最多 120dp +
 * 照片 64dp 会把 42dp 的底部按钮行挤扁/挤出视口，发送键点不到。修法是工具栏不带 weight
 * 因此**先**测得完整 42dp，正文 `weight(1f, fill = false)` + `verticalScroll` 吸收全部挤压。
 *
 * 断言的是真实 layout 几何与可见性：工具栏/按钮的实测高度、是否落在容器内、被裁剪的内容
 * 是否 `assertIsNotDisplayed` 且 `performScrollTo` 后可见可点。dp→px 用被测组合自己的
 * LocalDensity；`boundsInRoot` 是裁剪后边界，所以只对未裁剪节点取几何，裁剪外的子节点
 * 一律走可见性/滚动可达判定，不依赖其坐标。可达性用 `performScrollTo` 驱动真实滚动语义，
 * 本轮不额外做手指级拖拽实测。
 */
class ComposerLayoutDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    /** 被测组合的 px/dp，在 setContent 里从 LocalDensity 捕获。 */
    private var pxPerDp = 0f

    private var sendClicks = 0
    private var photoClicks = 0
    private var attachClicks = 0

    /** 20 行草稿：让输入框自然高远高于 120dp，从而稳定被夹到上限。 */
    private val multiLineDraft =
        (1..20).joinToString("\n") { "第 $it 行草稿内容，用来把输入框撑到最大高度上限" }

    private companion object {
        const val HOST = "composer-host"
        const val BODY = "composer-body"
        const val TOOLBAR = "composer-toolbar"
        const val ATTACH_ROW = "test-attach-row"
        const val ATTACH_FIRST = "test-attach-first"
        const val INPUT = "test-input"
        const val PHOTO = "test-photo"
        const val SEND = "test-send"

        val ATTACH_H = 72.dp
        val INPUT_MAX_H = 120.dp
        val PHOTO_H = 64.dp
        val TOOLBAR_H = 42.dp

        /** 与 ComposerLayout 内部一致：spacedBy(10dp)、上 14dp、下 12dp。 */
        val SPACING = 10.dp
        val PAD_TOP = 14.dp
        val PAD_BOTTOM = 12.dp

        /** 正文不滚动时需要的完整高度。 */
        val BODY_FULL_H: Dp = ATTACH_H + SPACING + INPUT_MAX_H + SPACING + PHOTO_H
    }

    /** 把 ComposerLayout 夹进 [viewportDp] 高的容器；[richBody] 决定是否装满正文。 */
    private fun showComposer(viewportDp: Int, richBody: Boolean) {
        sendClicks = 0
        photoClicks = 0
        attachClicks = 0
        compose.setContent {
            PiTheme {
                pxPerDp = with(LocalDensity.current) { 1.dp.toPx() }
                Box(
                    modifier = Modifier.fillMaxWidth().height(viewportDp.dp).testTag(HOST),
                    contentAlignment = Alignment.TopStart
                ) {
                    ComposerLayout(
                        modifier = Modifier.fillMaxWidth(),
                        body = {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.testTag(ATTACH_ROW)
                            ) {
                                items(3) { index ->
                                    Box(
                                        modifier = Modifier
                                            .size(width = 82.dp, height = ATTACH_H)
                                            .background(Color.LightGray)
                                            .clickable { attachClicks++ }
                                            .testTag(if (index == 0) ATTACH_FIRST else "att$index"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(Modifier.size(10.dp).background(Color.DarkGray))
                                    }
                                }
                            }
                            if (richBody) {
                                BasicTextField(
                                    value = multiLineDraft,
                                    onValueChange = { },
                                    textStyle = TextStyle(fontSize = 14.sp),
                                    modifier = Modifier.fillMaxWidth()
                                        .heightIn(min = 38.dp, max = INPUT_MAX_H)
                                        .testTag(INPUT)
                                )
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(PHOTO_H)
                                        .background(Color(0xFFDDDDDD)).clickable { photoClicks++ }
                                        .testTag(PHOTO)
                                )
                            }
                        },
                        toolbar = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                // 占位比工具栏矮：42dp 高度必须由发送按钮决定
                                Box(Modifier.height(24.dp).weight(1f).background(Color(0xFFEEEEEE)))
                                Box(
                                    modifier = Modifier.size(TOOLBAR_H).background(Color(0xFF1177EE))
                                        .clickable { sendClicks++ }.testTag(SEND)
                                )
                            }
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Dp.toPx(): Float = value * pxPerDp
    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { click(center) }
        compose.waitForIdle()
    }

    /** 重度压缩（160dp）：工具栏与按钮高度不缩水、完整落在容器内且可点，挤压全由正文承担。 */
    @Test
    fun toolbarKeepsFullHeightAndStaysClickableWhenViewportIsCompressed() {
        showComposer(viewportDp = 160, richBody = true)

        val host = bounds(HOST)
        val body = bounds(BODY)
        val toolbar = bounds(TOOLBAR)
        val send = bounds(SEND)

        assertEquals("工具栏必须保持 42dp，不能被正文挤扁", TOOLBAR_H.toPx(), toolbar.height, 2f)
        assertEquals("发送按钮高度必须保持 42dp", TOOLBAR_H.toPx(), send.height, 2f)
        assertEquals("发送按钮宽度必须保持 42dp", TOOLBAR_H.toPx(), send.width, 2f)

        // 160 - 上 14 - 下 12 - 间距 10 - 工具栏 42 = 82dp 全给正文
        val expectedBody = (160.dp - PAD_TOP - PAD_BOTTOM - SPACING - TOOLBAR_H).toPx()
        assertEquals("正文应吸收全部挤压并夹到剩余高度", expectedBody, body.height, 4f)
        assertTrue("正文被裁剪（小于自然高）", body.height < BODY_FULL_H.toPx())
        assertTrue(
            "工具栏必须完整在容器内：toolbar=[${toolbar.top},${toolbar.bottom}] host=[${host.top},${host.bottom}]",
            toolbar.top >= host.top - 1f && toolbar.bottom <= host.bottom + 1f
        )

        compose.onNodeWithTag(PHOTO).assertIsNotDisplayed()
        tap(SEND)
        assertEquals("压缩视口下发送键仍必须可点", 1, sendClicks)
    }

    /** 一般压缩（260dp）：正文滚动可达首末端，工具栏高度与位置全程不变。 */
    @Test
    fun bodyScrollsToBothEndsWhileToolbarStaysPut() {
        showComposer(viewportDp = 260, richBody = true)
        val toolbarBefore = bounds(TOOLBAR)
        assertEquals("滚动前工具栏保持 42dp", TOOLBAR_H.toPx(), toolbarBefore.height, 2f)
        compose.onNodeWithTag(PHOTO).assertIsNotDisplayed()

        compose.onNodeWithTag(PHOTO).performScrollTo().assertIsDisplayed()
        assertEquals("照片栏高度保持 64dp", PHOTO_H.toPx(), bounds(PHOTO).height, 2f)
        assertEquals("滚动不得移动工具栏", toolbarBefore.top, bounds(TOOLBAR).top, 0.5f)
        assertEquals("滚动不得压缩工具栏", toolbarBefore.height, bounds(TOOLBAR).height, 0.5f)
        tap(PHOTO)
        assertEquals("滚到末端后照片在其可见位置可点", 1, photoClicks)

        // 滚动外层正文，避免由横向 LazyRow 处理这次纵向回到顶部。
        compose.onNodeWithTag(ATTACH_ROW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ATTACH_FIRST).assertIsDisplayed()
        assertEquals("附件行高度保持 72dp", ATTACH_H.toPx(), bounds(ATTACH_FIRST).height, 2f)
        assertEquals("滚回首端不得移动工具栏", toolbarBefore.top, bounds(TOOLBAR).top, 0.5f)
        assertEquals("滚回首端不得压缩工具栏", toolbarBefore.height, bounds(TOOLBAR).height, 0.5f)
        tap(ATTACH_FIRST)
        assertEquals("滚回首端后第一个附件格子可点", 1, attachClicks)
    }

    /** 短内容：`weight(1f, fill=false)` 让正文只占内容高，工具栏紧跟其后而非撑满视口。 */
    @Test
    fun shortBodyWrapsInsteadOfFillingViewport() {
        showComposer(viewportDp = 260, richBody = false)
        val host = bounds(HOST)
        val body = bounds(BODY)
        val toolbar = bounds(TOOLBAR)

        assertEquals("短内容时正文不得拉伸填满剩余空间", ATTACH_H.toPx(), body.height, 2f)
        assertEquals("正文顶部对齐到上 padding", PAD_TOP.toPx(), body.top - host.top, 1f)
        assertEquals("正文与工具栏固定 10dp 间距", SPACING.toPx(), toolbar.top - body.bottom, 1f)
        assertEquals(
            "工具栏底边停在内容末尾", (PAD_TOP + ATTACH_H + SPACING + TOOLBAR_H).toPx(),
            toolbar.bottom - host.top, 3f
        )
        assertTrue("视口底部应留白（wrap-content）", host.bottom - toolbar.bottom >= 40.dp.toPx())
    }

    /** 宽裕视口（400dp）：正文取自然高度，末端照片无需滚动即可见、可点。 */
    @Test
    fun roomyViewportShowsWholeBodyWithoutScrolling() {
        showComposer(viewportDp = 400, richBody = true)
        val host = bounds(HOST)
        val body = bounds(BODY)
        val toolbar = bounds(TOOLBAR)

        assertEquals("视口够高时正文取自然高度", BODY_FULL_H.toPx(), body.height, 3f)
        assertEquals("输入框被夹到 120dp 上限", INPUT_MAX_H.toPx(), bounds(INPUT).height, 3f)
        assertEquals("工具栏仍是 42dp", TOOLBAR_H.toPx(), toolbar.height, 2f)
        assertEquals("工具栏紧跟正文", SPACING.toPx(), toolbar.top - body.bottom, 1f)
        // 底部余量含 ComposerLayout 的 12dp 下 padding，只断言「没有撑满视口」
        assertTrue("不撑满视口", host.bottom - toolbar.bottom >= 40.dp.toPx())
        compose.onNodeWithTag(PHOTO).assertIsDisplayed()
        assertEquals("照片栏高度保持 64dp", PHOTO_H.toPx(), bounds(PHOTO).height, 2f)
        tap(PHOTO)
        assertEquals("宽裕视口下照片可点", 1, photoClicks)
    }
}
