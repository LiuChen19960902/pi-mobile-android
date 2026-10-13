package com.finecat.pimobile.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 空白首页品牌区的点击范围回归（不接网关，纯布局命中测试）。
 *
 * 品牌区曾经是 `fillMaxWidth().clickable` 的整行：输入框上方的左右空白都会误进
 * 自定义文案弹窗。现在外容器只排版（两侧 16dp 不可点留宽 + 调用方传入的 bottom
 * padding），中央内容块 widthIn(max=280dp) 包内容宽并承担 clickable。
 * 这里锁三件事：中央可点、左右空白不可点、长文案下热区仍被 280dp 上限挡住不回到通栏。
 *
 * dp→px 用被测组合自己的 LocalDensity，点击坐标全部从实际 `home-brand-hit` 的
 * boundsInRoot 推导，不假设设备/仿真器屏幕宽度（也不要求 ≥360dp）；长文案用例锁
 * 的是绝对上限 280dp，分屏窄窗同样成立。fontScale 变大只让文本更早省略，热区上限
 * 与 16dp 留宽是布局层约束，不随字号变化——这里锁的就是这个不变量。
 */
class HomeBrandTapRegionDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    /** 品牌点击回调的触发次数（每个用例在 showBrand 里清零）。 */
    private var brandClicks = 0

    /** 被测组合的 px/dp，在 setContent 里从 LocalDensity 捕获。 */
    private var pxPerDp = 0f

    private val longTitle = "这是一段非常长的自定义首页标题文案用来验证省略与热区上限"
    private val longTagline = "标语也会被拉到省略号这一档用来验证它不再把点击区顶到屏幕两侧"

    /** 空白首页的调用方形态：居中排布，bottom padding 用于避让实测的输入区高度。 */
    private fun showBrand(
        title: String,
        tagline: String,
        layout: HomeBrandLayout = HomeBrandLayout.Full,
        bottomPaddingDp: Int = 200
    ) {
        brandClicks = 0
        compose.setContent {
            PiTheme {
                pxPerDp = with(LocalDensity.current) { 1.dp.toPx() }
                Box(Modifier.fillMaxSize().height(720.dp)) {
                    HomeBrand(
                        title = title,
                        tagline = tagline,
                        versionName = "0.0.0-test",
                        layout = layout,
                        onClick = { brandClicks++ },
                        modifier = Modifier.align(Alignment.Center)
                            .padding(bottom = bottomPaddingDp.dp)
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Dp.toPx(): Float = value * pxPerDp

    private fun hitBounds(): Rect =
        compose.onNodeWithTag("home-brand-hit").fetchSemanticsNode().boundsInRoot

    private fun rootBounds(): Rect =
        compose.onRoot().fetchSemanticsNode().boundsInRoot

    /** root 坐标系里的单指轻点：用于命中热区之外的空白。 */
    private fun tapAt(x: Float, y: Float) {
        compose.onRoot().performTouchInput { click(Offset(x, y)) }
        compose.waitForIdle()
    }

    /** 点热区外侧 insetDp 的空白；留白不够就判失败（说明热区又铺满整行）。 */
    private fun tapOutside(bounds: Rect, onLeft: Boolean, inset: Float) {
        if (onLeft) {
            assertTrue("热区左侧应留出不可点空白，实际 ${bounds.left}", bounds.left >= inset)
            tapAt(bounds.left - inset, bounds.center.y)
        } else {
            val gap = rootBounds().right - bounds.right
            assertTrue("热区右侧应留出不可点空白，实际 $gap", gap >= inset)
            tapAt(bounds.right + inset, bounds.center.y)
        }
    }

    @Test
    fun centerOfBrandContentIsClickable() {
        showBrand("FineCat", "探索未至之境")
        val bounds = hitBounds()
        tapAt(bounds.center.x, bounds.center.y)
        assertTrue("点击 logo/标题中央应进自定义文案，实际 $brandClicks", brandClicks == 1)
    }

    @Test
    fun leftAndRightGuttersOfBrandRowAreNotClickable() {
        showBrand("FineCat", "探索未至之境")
        val bounds = hitBounds()
        val inset = 8.dp.toPx()
        tapOutside(bounds, onLeft = true, inset = inset)
        tapOutside(bounds, onLeft = false, inset = inset)
        assertTrue("左右空白不该触发品牌点击，实际 $brandClicks", brandClicks == 0)

        // 同一 fixture 下中央仍可点：排除「整个组件没装上」造成的假绿。
        compose.onNodeWithTag("home-brand-hit").performClick()
        compose.waitForIdle()
        assertTrue("中央内容仍应可点，实际 $brandClicks", brandClicks == 1)
    }

    @Test
    fun longTitleAndTaglineStayWithinHitCap() {
        showBrand(longTitle, longTagline)
        val bounds = hitBounds()
        val cap = HomeBrandHitMaxWidth.toPx()
        assertTrue("长文案下热区不该回到通栏：${bounds.width} > $cap", bounds.width <= cap + 1f)

        val inset = 6.dp.toPx()
        tapOutside(bounds, onLeft = true, inset = inset)
        tapOutside(bounds, onLeft = false, inset = inset)
        assertTrue("长文案时两侧留白仍不可点，实际 $brandClicks", brandClicks == 0)

        tapAt(bounds.center.x, bounds.center.y)
        assertTrue("长文案时中央仍应可点，实际 $brandClicks", brandClicks == 1)
    }

    @Test
    fun outerBottomPaddingStillMovesBrandUp() {
        showBrand("FineCat", "探索未至之境", bottomPaddingDp = 200)
        val bounds = hitBounds()
        val root = rootBounds()
        assertTrue(
            "外部 bottom padding 应把品牌上推：${bounds.center.y} vs ${root.center.y}",
            bounds.center.y < root.center.y
        )
    }

    @Test
    fun compactLayoutKeepsCenterClickableOnly() {
        showBrand(longTitle, longTagline, layout = HomeBrandLayout.Compact)
        val bounds = hitBounds()
        tapOutside(bounds, onLeft = true, inset = 6.dp.toPx())
        assertTrue("缩小档的留白也不该可点，实际 $brandClicks", brandClicks == 0)
        tapAt(bounds.center.x, bounds.center.y)
        assertTrue("缩小档中央应可点，实际 $brandClicks", brandClicks == 1)
    }
}
