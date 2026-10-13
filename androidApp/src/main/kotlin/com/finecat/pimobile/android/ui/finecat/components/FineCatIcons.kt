package com.finecat.pimobile.android.ui.finecat.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 细线图标集，从 `FineCat/prototype/icons.js` 的 24×24 路径表逐条迁移：
 * 24px 网格、stroke 1.5、圆头线帽、currentColor；`fill="none"` 由
 * pathFill = null 表达。`Icon(FineCatIcons.X, ...)` 的 tint 负责着色，
 * 路径内的 Black 只是无 tint 时的兜底。
 *
 * 仅两个特例：`Contrast` 的半圆按原型填充、`More` 的描边加粗到 2.4。
 * 步骤/工具图标只有 `Terminal` 和 `Brain` 两种（components.md §10）。
 */
object FineCatIcons {

    private fun icon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.stroke(pathData: String, width: Float = 1.5f) {
        addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun ImageVector.Builder.fillPath(pathData: String) {
        addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        )
    }

    val Menu: ImageVector = icon("Menu") {
        stroke("M4 7h16M4 12h16M4 17h11")
    }

    val Plus: ImageVector = icon("Plus") {
        stroke("M12 5v14M5 12h14")
    }

    val Back: ImageVector = icon("Back") {
        stroke("M15 5l-7 7 7 7")
    }

    val Search: ImageVector = icon("Search") {
        stroke("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14Z")
        stroke("M16.5 16.5 21 21")
    }

    val Right: ImageVector = icon("Right") {
        stroke("M9 6l6 6-6 6")
    }

    val Down: ImageVector = icon("Down") {
        stroke("M6 9l6 6 6-6")
    }

    val Up: ImageVector = icon("Up") {
        stroke("M6 15l6-6 6 6")
    }

    val Folder: ImageVector = icon("Folder") {
        stroke("M3.5 7.5A2 2 0 0 1 5.5 5.5h3.7l2 2.5h7.3a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    }

    val Clock: ImageVector = icon("Clock") {
        stroke("M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17Z")
        stroke("M12 7.5V12l3.2 2")
    }

    val Trash: ImageVector = icon("Trash") {
        stroke("M4 7h16M9.5 7V4.5h5V7M6.5 7l.9 12.6A1.6 1.6 0 0 0 9 21h6a1.6 1.6 0 0 0 1.6-1.4L17.5 7")
    }

    val Globe: ImageVector = icon("Globe") {
        stroke("M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17Z")
        stroke("M3.6 12h16.8M12 3.5c2.5 2.5 2.5 14 0 17M12 3.5c-2.5 2.5-2.5 14 0 17")
    }

    val Sun: ImageVector = icon("Sun") {
        stroke("M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z")
        stroke("M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.2 5.2l1.6 1.6M17.2 17.2l1.6 1.6M18.8 5.2l-1.6 1.6M6.8 17.2l-1.6 1.6")
    }

    val Moon: ImageVector = icon("Moon") {
        stroke("M20.2 14.6A8.6 8.6 0 0 1 9.4 3.8a8.6 8.6 0 1 0 10.8 10.8z")
    }

    /** 半填充圆：小尺寸下比太阳/月亮好认，且不依赖当前主题表意。 */
    val Contrast: ImageVector = icon("Contrast") {
        stroke("M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17Z")
        fillPath("M12 3.5a8.5 8.5 0 0 1 0 17z")
    }

    val Mic: ImageVector = icon("Mic") {
        stroke("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0V6Z")
        stroke("M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18.2V21")
    }

    val Image: ImageVector = icon("Image") {
        stroke("M3 7.5a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-9Z")
        stroke("M8.5 8.3a1.7 1.7 0 1 0 0 3.4 1.7 1.7 0 0 0 0-3.4Z")
        stroke("M4 17l5-4.4 3.6 3.2L16 12l4 3.6")
    }

    val Bell: ImageVector = icon("Bell") {
        stroke("M6.2 9.6a5.8 5.8 0 0 1 11.6 0c0 3.9 1.6 5.4 1.6 5.4H4.6s1.6-1.5 1.6-5.4zM9.9 19.4a2.3 2.3 0 0 0 4.2 0")
    }

    val Mail: ImageVector = icon("Mail") {
        stroke("M3 8a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8Z")
        stroke("M4.2 7.4l7.8 5.6 7.8-5.6")
    }

    val Shield: ImageVector = icon("Shield") {
        stroke("M12 3.2l7 2.8v5.6c0 4.4-2.9 7.4-7 8.8-4.1-1.4-7-4.4-7-8.8V6z")
    }

    val Wrench: ImageVector = icon("Wrench") {
        stroke("M15.4 3.6a5.4 5.4 0 0 0-4.9 7.9l-7 7 2 2 7-7a5.4 5.4 0 0 0 7.9-4.9l-3 3-2.4-.5-.5-2.4z")
    }

    val Terminal: ImageVector = icon("Terminal") {
        stroke("M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9A2.5 2.5 0 0 1 18 19H6a2.5 2.5 0 0 1-2.5-2.5v-9A2.5 2.5 0 0 1 6 5Z")
        stroke("M7.5 10.2l2.3 1.8-2.3 1.8M12.4 14.4h4")
    }

    val Book: ImageVector = icon("Book") {
        stroke("M4 19.4A2.6 2.6 0 0 1 6.6 16.8H20")
        stroke("M6.6 3.2H20v17.2H6.6A2.6 2.6 0 0 1 4 17.8V5.8a2.6 2.6 0 0 1 2.6-2.6z")
    }

    val File: ImageVector = icon("File") {
        stroke("M6.5 3.6h6.6l4.4 4.4v12.4H6.5zM13.1 3.6V8h4.4")
    }

    val Edit: ImageVector = icon("Edit") {
        stroke("M4 20h4.2L19.4 8.8 15.2 4.6 4 15.8zM13.9 5.9l4.2 4.2")
    }

    val Check: ImageVector = icon("Check") {
        stroke("M5 12.6l4.6 4.4L19 7.4")
    }

    val X: ImageVector = icon("X") {
        stroke("M6.2 6.2l11.6 11.6M17.8 6.2 6.2 17.8")
    }

    val ArrowUp: ImageVector = icon("ArrowUp") {
        stroke("M12 20V4.4M6 10.4L12 4.4l6 6")
    }

    val SendUp: ImageVector = icon("SendUp") {
        stroke("M12 19.5V5M5.8 11.2L12 5l6.2 6.2")
    }

    val Copy: ImageVector = icon("Copy") {
        stroke("M12 9h5a3 3 0 0 1 3 3v5a3 3 0 0 1-3 3h-5a3 3 0 0 1-3-3v-5a3 3 0 0 1 3-3Z")
        stroke("M15.2 6.6A2.6 2.6 0 0 0 12.6 4H6.6A2.6 2.6 0 0 0 4 6.6v6a2.6 2.6 0 0 0 2.6 2.6")
    }

    val Cloud: ImageVector = icon("Cloud") {
        stroke("M7 18a4 4 0 0 1-.5-8 5.6 5.6 0 0 1 10.8 1.3A3.4 3.4 0 0 1 16.8 18z")
    }

    val Sparkle: ImageVector = icon("Sparkle") {
        stroke("M11.6 3.4l1.6 4.3 4.3 1.6-4.3 1.6-1.6 4.3-1.6-4.3-4.3-1.6 4.3-1.6zM18 15.4l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z")
    }

    val Calendar: ImageVector = icon("Calendar") {
        stroke("M6 5.5h12a2.5 2.5 0 0 1 2.5 2.5v10a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 18V8A2.5 2.5 0 0 1 6 5.5Z")
        stroke("M3.5 10h17M8 3.8v3.4M16 3.8v3.4")
    }

    val Layers: ImageVector = icon("Layers") {
        stroke("M12 3.4l7.8 4.3-7.8 4.3-7.8-4.3zM4.2 12.6L12 16.9l7.8-4.3")
    }

    val More: ImageVector = icon("More") {
        stroke("M6 12h.01M12 12h.01M18 12h.01", width = 2.4f)
    }

    val Gauge: ImageVector = icon("Gauge") {
        stroke("M4.2 17.5a8.4 8.4 0 1 1 15.6 0M12 17.5l4.2-5.4")
    }

    val Branch: ImageVector = icon("Branch") {
        stroke("M7 3.7a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6Z")
        stroke("M7 15.7a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6Z")
        stroke("M17 7.2a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6Z")
        stroke("M7 8.3v7.4M9.3 6.2H13a2 2 0 0 1 2 2v.6")
    }

    val Qr: ImageVector = icon("Qr") {
        stroke("M4 5.5A1.5 1.5 0 0 1 5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3Z")
        stroke("M14 5.5A1.5 1.5 0 0 1 15.5 4h3A1.5 1.5 0 0 1 20 5.5v3A1.5 1.5 0 0 1 18.5 10h-3A1.5 1.5 0 0 1 14 8.5v-3Z")
        stroke("M4 15.5A1.5 1.5 0 0 1 5.5 14h3A1.5 1.5 0 0 1 10 15.5v3A1.5 1.5 0 0 1 8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3Z")
        stroke("M14 14h2.5v2.5M20 20v-3.5M17 20h3")
    }

    val Gear: ImageVector = icon("Gear") {
        stroke("M8.9 12a3.1 3.1 0 1 0 6.2 0 3.1 3.1 0 0 0-6.2 0Z")
        stroke("M12 3.4v2.2M12 18.4v2.2M3.4 12h2.2M18.4 12h2.2M5.9 5.9l1.6 1.6M16.5 16.5l1.6 1.6M18.1 5.9l-1.6 1.6M7.5 16.5l-1.6 1.6")
    }

    /** 16px 下 gear 会被读成太阳，设置入口改用推子。 */
    val Sliders: ImageVector = icon("Sliders") {
        stroke("M12.6 8.5a2.4 2.4 0 1 0 4.8 0 2.4 2.4 0 0 0-4.8 0Z")
        stroke("M8.1 15.5a2.4 2.4 0 1 0 4.8 0 2.4 2.4 0 0 0-4.8 0Z")
        stroke("M4 8.5h8.5M17.5 8.5H20M4 15.5h4M13 15.5h7")
    }

    val Logout: ImageVector = icon("Logout") {
        stroke("M14.5 4H6.5v16h8M10.5 12h9M16.6 8l3 4-3 4")
    }

    val Refresh: ImageVector = icon("Refresh") {
        stroke("M4.2 12a7.8 7.8 0 0 1 13.3-5.5M19.8 12a7.8 7.8 0 0 1-13.3 5.5M17.4 3.4v3.4H14M6.6 20.6v-3.4H10")
    }

    val Grip: ImageVector = icon("Grip") {
        stroke("M8 9.5h8M8 14.5h8")
    }

    val Activity: ImageVector = icon("Activity") {
        stroke("M3.5 12.5h3.6l2.6 6 4-13 2.6 7h4.1")
    }

    val List: ImageVector = icon("List") {
        stroke("M4.5 7h2M4.5 12h2M4.5 17h2M9.5 7h10M9.5 12h10M9.5 17h10")
    }

    val Link: ImageVector = icon("Link") {
        stroke("M10 13.5a3.5 3.5 0 0 0 5 0l2.5-2.5a3.5 3.5 0 0 0-5-5L11.2 7.3M14 10.5a3.5 3.5 0 0 0-5 0L6.5 13a3.5 3.5 0 0 0 5 5l1.3-1.3")
    }

    val Brain: ImageVector = icon("Brain") {
        stroke("M9 4.5a3 3 0 0 0-3 3 3 3 0 0 0-1.4 5.6A3 3 0 0 0 6.6 19a3 3 0 0 0 5.4-1.3V7.5a3 3 0 0 0-3-3zM15 4.5a3 3 0 0 1 3 3 3 3 0 0 1 1.4 5.6A3 3 0 0 1 17.4 19a3 3 0 0 1-5.4-1.3")
    }

    val Monitor: ImageVector = icon("Monitor") {
        stroke("M5.5 4.5h13a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 14V7a2.5 2.5 0 0 1 2.5-2.5Z")
        stroke("M9 20h6M12 16.5V20")
    }

    val Chat: ImageVector = icon("Chat") {
        stroke("M20 12.5a7.5 7.5 0 0 1-7.5 7.5H8l-4 2.5V12.5A7.5 7.5 0 0 1 11.5 5h1A7.5 7.5 0 0 1 20 12.5z")
    }

    val ArrowDown: ImageVector = icon("ArrowDown") {
        stroke("M12 4.5V19M6 13.2l6 6 6-6")
    }

    val Up2: ImageVector = icon("Up2") {
        stroke("M7 21V10.5M11 3.5L7.6 10.5h9.9a2 2 0 0 1 1.9 2.6l-1.8 6.2a2 2 0 0 1-1.9 1.4H7.6")
    }

    val Down2: ImageVector = icon("Down2") {
        stroke("M7 3v10.5M11 20.5l3.4-7H4.5a2 2 0 0 1-1.9-2.6l1.8-6.2A2 2 0 0 1 6.3 3.5H16.4")
    }
}
