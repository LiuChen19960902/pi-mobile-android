package com.finecat.pimobile.android.ui.finecat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

/**
 * 设置页公共视觉件（原型 `.sec-label` / `.card` / `.row` 三件套）。
 *
 * 原型的设置是一张整页，内容按「小节标题 → 圆角卡片 → 卡片内的行」组织；行是
 * `lead 图标 · label · value · 箭头` 四段。这些结构在网关 sheet、Provider sheet 与
 * 设置整页里都要用，抽在这里而不是各屏各画一份。
 */

// 小节标题（`.sec-label`）用 FineCatComponents.FcSectionLabel（原型 margin 24/12/4，
// 首个小节 top=12）——本文件不再维护重复定义（二轮 N-3）。

/** 圆角卡片容器（`.card`）：surface 底 + 卡半径 + 轻投影；相邻卡片自带 12dp 间距。 */
@Composable
fun FcSettingCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFineCatColors.current
    val radius = LocalFineCatRadius.current
    val shape = RoundedCornerShape(radius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .shadow(FineCatElevation.Card, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(colors.surface, shape),
        content = content,
    )
}

/**
 * 设置行（`.row`）：`lead · label(+副标题) · value · chevron`。
 *
 * 原型里 `.row + .row` 有 1px 上边框（Qoder 风发丝线，从 label 起点开始而不是图标起点），
 * 这里的分隔线同样由调用方在行之间插入（用 [FcRowDivider]），因为首行不应有分隔线。
 *
 * @param lead 左侧 24×24 图标槽；传 null 则留空（入口列表那类行用序号占位）。
 * @param leadDot 左侧 7px 圆点（原型连接状态行的绿点），与 lead 二选一：leadDot 非空时
 *   优先用它——原型「已连接」行是 `<span class="dot ok">` 而不是图标。
 * @param value 右侧次级值（如 `Port 3081`、`未测试`）；传 null 则不占位。
 *   原型 `.row .value`：fs-sm、text-3；[valueMono] 对应原型 value 上的 `.mono`（Port 值）。
 * @param tag 尾部状态 tag（原型 `<span class="tag bad">缺 key</span>`）；传 null 不占位，
 *   排在 value 之后、chevron 之前。
 * @param tagStyle tag 配色（缺 key 用 Bad 红）。
 * @param showChevron 右侧箭头；行不可点时传 false。
 */
@Composable
fun FcSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    lead: ImageVector? = null,
    leadDot: Color? = null,
    leadTint: Color? = null,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    valueMono: Boolean = false,
    tag: String? = null,
    tagStyle: FcTagStyle = FcTagStyle.Bad,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val radius = LocalFineCatRadius.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // lead：24×24 图标槽（原型 .row .lead）；leadDot 非空时改成 7px 圆点
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (leadDot != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(leadDot),
                )
            } else {
                lead?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = leadTint ?: colors.text,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = type.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题（Provider 行的「方言 · N 个模型 · M 项改动」）
            subtitle?.let {
                Text(
                    text = it,
                    style = type.xs2,
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Text(
                text = it,
                style = type.sm.copy(
                    fontFamily = if (valueMono) type.mono else FontFamily.Default,
                ),
                color = valueColor ?: colors.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
        tag?.let { FcStatusTag(text = it, style = tagStyle) }
        if (showChevron) {
            Icon(
                imageVector = FineCatIcons.Right,
                contentDescription = null,
                tint = colors.text3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 行间发丝线（原型 `.row + .row` 的 1px 上边框，从 lead 之后开始）。
 *
 * [startInset] = 56dp 是 `.rows .row + .row::before` 的 Qoder 内缩口径（默认）；
 * 传 0.dp 得到通栏线——网关入口卡的 `.gw`/添加行是元素自身 `border-top`，原型就是通栏。
 */
@Composable
fun FcRowDivider(modifier: Modifier = Modifier, startInset: Dp = 56.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(1.dp)
            .background(LocalFineCatColors.current.line),
    )
}
