package com.finecat.pimobile.android.ui

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部栏弹层（AnchoredPopupMenu）的定位回归。
 *
 * 需求：
 * 1. 菜单可见底边距锚点上沿正好 gap（4dp），卡片自身就是边界，阴影不额外占位；
 * 2. 上下边界只能取宿主窗口的真实安全区，不能用 Popup 回调里被系统栏再次缩短的 windowSize 推导；
 * 3. 优先放在上方，上方放不下才放下方；窄屏、系统栏与 IME 造成的安全区收缩不得越界；
 * 4. 列表较长时可用高度取上下两侧中较大的一侧。
 *
 * 窗口、锚点和菜单尺寸近似截图；安全区 (21,130,900,1924) 是测试输入，未实测设备 Insets。
 * 窗口 921x2000、锚点 (627,1824,754,1951)、菜单 688x576，4dp 按近似密度取 11px。
 */
class AnchoredMenuPositionTest {

    private val hostWindow = IntSize(921, 2000)
    private val safeBounds = IntRect(21, 130, 900, 1924)
    private val bottomAnchor = IntRect(627, 1824, 754, 1951)
    private val menuSize = IntSize(688, 576)
    private val gap = 11

    private fun position(
        anchor: IntRect = bottomAnchor,
        windowSize: IntSize = hostWindow,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        contentSize: IntSize = menuSize,
        safe: IntRect = safeBounds,
        horizontalOffset: Int = 0
    ) = AnchoredMenuPositionProvider(safe, horizontalOffset, gap)
        .calculatePosition(anchor, windowSize, layoutDirection, contentSize)

    @Test
    fun visibleBottomStaysExactlyFourDpAboveAnchorTop() {
        val pos = position()

        // 期望底边 1813 = 锚点上沿 1824 - 11px(4dp)；不得因阴影 padding 再上移。
        assertEquals(1237, pos.y)
        assertEquals(1813, pos.y + menuSize.height)
        assertEquals(gap, bottomAnchor.top - (pos.y + menuSize.height))
        assertTrue("底边不得进入锚点区", pos.y + menuSize.height <= bottomAnchor.top)
    }

    @Test
    fun boundariesUseHostSafeAreaNotTheShrunkWindowSize() {
        // 模拟 visible display 尺寸比宿主窗口短；1841 是回归输入，不是真机采集值。
        // 已给定宿主安全区时，位置仍必须落在 1813，不能被额外上推。
        assertEquals(1813, position(windowSize = IntSize(921, 1841)).y + menuSize.height)
        assertEquals(position(windowSize = hostWindow), position(windowSize = IntSize(921, 1841)))
        // 极端：回调尺寸完全不可信时也只按安全区算，结果与正常窗口一致。
        assertEquals(position(windowSize = hostWindow), position(windowSize = IntSize(921, 400)))
    }

    @Test
    fun prefersAboveWhenBothSidesHaveRoom() {
        val anchor = IntRect(627, 900, 754, 1027)

        assertEquals(313, position(anchor = anchor).y)
        assertEquals(anchor.top - gap, position(anchor = anchor).y + menuSize.height)
    }

    @Test
    fun dropsBelowWhenAboveCannotFit() {
        val anchor = IntRect(627, 200, 754, 327)
        val pos = position(anchor = anchor)

        // 上方只剩 70px 放不下 576px 菜单，改挂到锚点下方并留同样 gap。
        assertEquals(338, pos.y)
        assertEquals(anchor.bottom + gap, pos.y)
        assertTrue(pos.y >= safeBounds.top)
    }

    @Test
    fun keepsMenuAboveImeEvenWhenFullWindowWouldAllowOverflow() {
        val imeSafe = IntRect(21, 130, 900, 1200)
        val pos = position(
            anchor = IntRect(627, 200, 754, 300),
            contentSize = IntSize(688, 1000),
            safe = imeSafe
        )

        // 按 windowSize(2000) 推导会得到底边 1311，压在 IME 上；按安全区则贴到 1200 为止。
        assertEquals(1200, pos.y + 1000)
        assertTrue(pos.y >= imeSafe.top)
    }

    @Test
    fun alignsLogicalEndAndMirrorsHorizontalOffset() {
        val smallMenu = IntSize(200, 300)

        // 逻辑末端对齐：LTR 贴锚点右沿，RTL 贴锚点左沿。
        assertEquals(754, position(contentSize = smallMenu).x + smallMenu.width)
        assertEquals(627, position(contentSize = smallMenu, layoutDirection = LayoutDirection.Rtl).x)

        // 偏移随方向镜像：LTR 向右推，RTL 向左推。
        assertEquals(784, position(contentSize = smallMenu, horizontalOffset = 30).x + smallMenu.width)
        assertEquals(
            597,
            position(contentSize = smallMenu, layoutDirection = LayoutDirection.Rtl, horizontalOffset = 30).x
        )
    }

    @Test
    fun clampsHorizontallyOnNarrowScreens() {
        // 菜单向锚点末端对齐会越过左界时，移回安全区。
        val wide = position(contentSize = IntSize(800, 300))
        assertEquals(safeBounds.left, wide.x)
        assertTrue(wide.x + 800 <= safeBounds.right)

        // 锚点本身超出安全区右沿时，菜单整体收回安全区内。
        val pastRight = position(anchor = IntRect(850, 1824, 990, 1951), contentSize = IntSize(300, 300))
        assertEquals(safeBounds.right, pastRight.x + 300)

        // 菜单比整个安全区还宽：至少保留左对齐，不做居中漂移。
        assertEquals(safeBounds.left, position(contentSize = IntSize(1000, 300)).x)
    }

    @Test
    fun availableHeightTakesTheLargerSide() {
        // 贴底锚点：下方被锚点自己占满，只有上方 1813-130=1683。
        assertEquals(1683, anchoredMenuAvailableHeight(bottomAnchor, safeBounds, gap))
        // 贴顶锚点：上方只剩 59，下方 1924-338=1586。
        assertEquals(1586, anchoredMenuAvailableHeight(IntRect(627, 200, 754, 327), safeBounds, gap))
        // 锚点已落到安全区下方（如 IME 抬起后仍在原窗口坐标）：不得返回负值。
        assertEquals(1794, anchoredMenuAvailableHeight(IntRect(627, 1951, 754, 2078), safeBounds, gap))
    }

    @Test
    fun maxHeightMenuKeepsGapAndStaysInsideSafeArea() {
        var checked = 0
        for (top in 150..1950 step 25) {
            val anchor = IntRect(627, top, 754, top + 127)
            val height = anchoredMenuAvailableHeight(anchor, safeBounds, gap)
            if (height == 0) continue
            val pos = position(anchor = anchor, contentSize = IntSize(688, height))

            // 用满可用高度的菜单，要么挂在锚点上方且留足 gap，要么挂在下方且留足 gap。
            val above = pos.y + height <= anchor.top - gap
            val below = pos.y >= anchor.bottom + gap
            assertTrue("anchor top=$top y=${pos.y} bottom=${pos.y + height}", above || below)
            assertTrue(pos.y >= safeBounds.top)
            assertTrue(pos.y + height <= safeBounds.bottom)
            checked++
        }
        assertTrue("锚点扫描应覆盖多数位置", checked > 50)
    }
}
