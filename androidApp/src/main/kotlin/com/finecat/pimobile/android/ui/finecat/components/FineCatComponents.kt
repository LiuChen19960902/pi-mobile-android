package com.finecat.pimobile.android.ui.finecat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import com.finecat.pimobile.android.ui.finecat.conversation.projectBasename
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatMotion
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

/** CSS --sh-1/2/3 在 Compose 的近似：单层 elevation + 主题阴影色（tokens.md §5 的建议）。 */
object FineCatElevation {
    val Card = 2.dp
    val Raised = 6.dp
    val Floating = 12.dp
}

/** 38×38 圆形图标按钮（.icon-btn）；composer 内用 30×30（components.md §4 关键几何）。
 *  视觉尺寸按原型，可点击区域外扩到 48×48（01 §4），相邻触控区不重叠。 */
@Composable
fun FcIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    buttonSize: Dp = 38.dp,
    iconSize: Dp = 22.dp,
    floating: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalFineCatColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(buttonSize)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .scale(if (pressed) 0.94f else 1f)
                .then(
                    if (floating) {
                        Modifier.dropShadow(
                            shape = CircleShape,
                            shadow = Shadow(
                                radius = 8.dp,
                                spread = 0.dp,
                                color = colors.shadow.copy(alpha = if (colors.isDark) 0.45f else 0.16f),
                                offset = DpOffset(0.dp, 2.dp),
                            ),
                        )
                    } else if (solid) {
                        Modifier.shadow(
                            FineCatElevation.Card, CircleShape, clip = false,
                            ambientColor = colors.shadow, spotColor = colors.shadow,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .background(if (solid || floating) colors.surface else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.text.copy(alpha = if (enabled) 1f else 0.3f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** 7dp 状态圆点（.dot）；只在"这一条值得写字"时辅助（components.md §1）。 */
@Composable
fun FcDot(ok: Boolean, contentDescription: String?, modifier: Modifier = Modifier) {
    val colors = LocalFineCatColors.current
    Box(
        modifier = modifier
            .size(7.dp)
            .background(if (ok) colors.ok else colors.text3, CircleShape)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    )
}

/**
 * 骨架头部（.app-head）：min-height 52、padding 4/20/12、gap 8。
 * `title` 是会话标题；`showConnectionDot` 是网关连接点；`directory` 副行是
 * "这个会话跑在哪个目录"，不是副标题。生产里已有会话/轨迹/tree 的副行只读
 * （DEC-F09-CWD：不打开目录选择器，不改旧会话 cwd）；`onDirectoryClick` 只留给
 * debug 组件画廊演示可点形态，生产调用方不传。
 */
@Composable
fun FcHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    showConnectionDot: Boolean = false,
    dotDescription: String = "网关已连接",
    directory: String? = null,
    onDirectoryClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(start = LocalFineCatSpacing.current.gutter, end = LocalFineCatSpacing.current.gutter, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (leading != null) leading()
        // 空态首页只留菜单按钮：原型 `appHead({ menu: true, title: '' })` 没有标题、
        // 没有连接点、没有目录副行。此时中间退化成纯占位，不渲染空文本行，
        // 头部高度只由 min-height 决定。
        if (title.isEmpty() && !showConnectionDot && directory == null) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    // 标题改名入口（pi /name 的 UI 对等物，会话页专用；旧 UI 同口径：
                    // 会话存在 + 已连接 + 网关支持 name）。标题与副行整块是一个触控
                    // 目标；保留原型自然行高，最小触控由平台外扩保证。它与
                    // onDirectoryClick 互斥（后者仅 debug 画廊传入），同一头部不会出现
                    // 两个重叠触控区（01 §4）。点副行落在这块里只会触发改名，不是改目录。
                    if (onTitleClick != null && onDirectoryClick == null) {
                        Modifier
                            .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
                            .clickable(role = Role.Button, onClick = onTitleClick)
                            .testTag("finecat.headerTitle")
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = type.body.copy(letterSpacing = type.body.fontSize * -0.01f),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showConnectionDot) {
                    FcDot(ok = true, contentDescription = dotDescription)
                }
            }
            if (directory != null) {
                // `.sub-line` 保留自然行高；可点击时由平台外扩触控，不增加视觉槽位。
                Box(Modifier.testTag("finecat.headerDirectory")) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
                            .then(
                                if (onDirectoryClick != null) {
                                    Modifier.clickable(onClick = onDirectoryClick)
                                } else {
                                    // 只读副行不挂 disabled clickable，避免拦截父级标题改名。
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = FineCatIcons.Folder,
                                contentDescription = null,
                                tint = colors.text3,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                // 副行只放项目基名（原型 `data-repo` 存的就是
                                // `pi-mobile-android` 这类短名），全路径会撑爆 210dp
                                // 并挤掉标题。
                                text = projectBasename(directory),
                                style = type.xs,
                                color = colors.text3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 210.dp),
                            )
                        }
                    }
                }
            }
        }
        }
        if (trailing != null) trailing()
    }
}

/**
 * 三枚图标的视图分段（.seg-icons）：切换同一会话的三种看法，不是三个页面。
 * 视觉盒按原型 28×26dp、圆角 7/9dp；平台 clickable 自动外扩触控至至少 48×48dp。
 * 允许因 30dp 中心距产生 18dp 名义局部触控重叠，每个落点恰好投递一次无歧义。
 * Role.Tab + selected 语义供 TalkBack 读取。
 */
@Composable
fun FcViewSeg(
    icons: List<ImageVector>,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabledItems: List<Boolean>? = null,
    /** 逐项 testTag（抽屉分组切换等需要按项定位的调用方用）；缺省挂 finecat.viewSeg.*。 */
    itemTags: List<String>? = null,
) {
    require(icons.size == labels.size) { "FcViewSeg icons/labels size mismatch" }
    require(icons.isNotEmpty()) { "FcViewSeg needs at least one item" }
    require(selected in icons.indices) { "FcViewSeg selected index out of bounds" }
    require(enabledItems == null || enabledItems.size == icons.size) {
        "FcViewSeg enabledItems size must match icons"
    }
    require(itemTags == null || itemTags.size == icons.size) {
        "FcViewSeg itemTags size must match icons"
    }
    val colors = LocalFineCatColors.current
    val radius = LocalFineCatRadius.current
    val defaultTags = listOf("finecat.viewSeg.chat", "finecat.viewSeg.tree")
    val effectiveTags = itemTags ?: defaultTags.take(icons.size)
    Row(
        modifier = modifier
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        icons.forEachIndexed { index, icon ->
            val isSelected = index == selected
            val isEnabled = enabledItems?.getOrNull(index) ?: true
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 26.dp)
                    .then(effectiveTags.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier)
                    .clip(RoundedCornerShape(radius.segItem))
                    .background(if (isSelected) colors.text.copy(alpha = 0.07f) else Color.Transparent)
                    .clickable(enabled = isEnabled, role = Role.Tab) { onSelect(index) }
                    .semantics {
                        this.selected = isSelected
                        this.contentDescription = labels[index]
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) colors.text else colors.text3,
                    modifier = Modifier
                        .size(15.dp)
                        .then(if (isEnabled) Modifier else Modifier.alpha(0.4f)),
                )
            }
        }
    }
}

/**
 * 分组卡片（.card.rows）：16 圆角 + surface + 卡内行间从文案起算的内缩发丝线。
 * 行的分隔线由 [FcRow] 的 `dividerAbove` 画出（第一条不画线）。
 */
@Composable
fun FcCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFineCatColors.current
    val radius = LocalFineCatRadius.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                FineCatElevation.Card, RoundedCornerShape(radius.card), clip = true,
                ambientColor = colors.shadow, spotColor = colors.shadow,
            )
            .background(colors.surface, RoundedCornerShape(radius.card)),
        content = content,
    )
}

/**
 * 分组卡片行（.row）：lead 图标 + 文案(+sub) + 右对齐灰值 + 细箭头。
 * 单行高约 52，触控区至少 48dp（01 §4）；按压反馈统一跟随 FineCatTheme。
 * `dividerAbove` 画 56dp 内缩发丝线（第一条不画）。`onClick == null` 是静态行。
 * value 的 max-width 46% 按行宽相对计算（CSS .row .value）。
 */
@Composable
fun FcRow(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    lead: ImageVector? = null,
    sub: String? = null,
    value: String? = null,
    showChevron: Boolean = false,
    valueDescription: String? = null,
    dividerAbove: Boolean = false,
    clickRole: Role = Role.Button,
    stateDescription: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    val dividerColor = colors.line
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val valueMaxWidth = maxWidth * 0.46f
        val touchable = Modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = clickRole, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(
                if (stateDescription != null) {
                    Modifier.semantics { this.stateDescription = stateDescription }
                } else {
                    Modifier
                },
            )
        // 调用方 modifier（如 testTag）放在与 clickable/stateDescription 相同的节点上，
        // 保证测试/TalkBack 拿到的是同一个合并语义节点
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier)
                .then(touchable)
                .drawBehind {
                    // CSS 1px 按 01 §4 映射为 1dp
                    if (dividerAbove) {
                        val x = 56.dp.toPx()
                        drawLine(dividerColor, Offset(x, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                    }
                }
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = spacing.gutter, vertical = spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        ) {
            if (lead != null) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(imageVector = lead, contentDescription = null, tint = colors.text, modifier = Modifier.size(21.dp))
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
                if (sub != null) {
                    Text(
                        text = sub,
                        style = type.xs,
                        color = colors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    style = type.sm,
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = valueMaxWidth)
                        .then(
                            if (valueDescription != null) {
                                Modifier.semantics { contentDescription = valueDescription }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            if (trailing != null) trailing()
            if (showChevron) {
                Icon(
                    imageVector = FineCatIcons.Right,
                    contentDescription = null,
                    tint = colors.text3,
                    modifier = Modifier
                        .size(18.dp)
                        .alpha(0.7f),
                )
            }
        }
    }
}

/**
 * 组标题（.sec-label）。CSS 是 14sp/500/text-3、margin 24/12/4——
 * components.md 概要写成了 2xs，与 CSS 冲突，按 01 §4 以 CSS 为准。
 */
@Composable
fun FcSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 24.dp,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Text(
        text = text,
        style = type.sm.copy(fontWeight = FontWeight.Medium),
        color = colors.text3,
        modifier = modifier.padding(start = 4.dp, top = topPadding, bottom = 12.dp),
    )
}

/** 说明文字（.hint）：只在需要时出现，不做成长段。 */
@Composable
fun FcHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Text(
        text = text,
        style = type.xs.copy(lineHeight = type.xs.fontSize * 1.7f),
        color = colors.text3,
        modifier = modifier.padding(start = LocalFineCatSpacing.current.s1, top = LocalFineCatSpacing.current.s3, end = LocalFineCatSpacing.current.s1),
    )
}

/** 异常/状态标签（.tag）：ok=事实正常，bad=失败，wait=进行中（品牌蓝底）。 */
enum class FcTagStyle { Ok, Bad, Wait }

@Composable
fun FcStatusTag(
    text: String,
    style: FcTagStyle,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val radius = LocalFineCatRadius.current
    val type = LocalFineCatType.current
    val (fg, bg) = when (style) {
        FcTagStyle.Ok -> colors.ok to colors.okSoft
        FcTagStyle.Bad -> colors.err to colors.errSoft
        FcTagStyle.Wait -> colors.brand to colors.brandSoft
    }
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            // 标签盒自身的定位锚点（原型 .tag height:20px）：testTag 置于 padding 之前，
            // 节点 bounds 才是含内边距的整盒（挂在 padding 后只剩文本区，量不到盒）。
            .testTag("finecat.statusTag")
            .background(bg, RoundedCornerShape(radius.tag))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = type.xs2.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

/** 文本按钮（.tbtn）：保留原型自然布局高，触控由 clickable 外扩；mute 变灰。
 *  `enabled=false` 仍可被聚焦到但不响应（表单项未填齐时的占位动作）。 */
@Composable
fun FcTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(LocalFineCatRadius.current.chip))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.sm,
            color = if (!enabled || muted) colors.text3 else colors.brand,
        )
    }
}

/**
 * 开关（.tgl）：34×20 轨道，开=ink 轨 + on-ink 滑块，关=surface-3 轨。
 * 只做视觉，不做点击语义——整行可点的开关行把 Role.Switch 和 stateDescription
 * 放在行上（[FcRow] 的 clickRole/stateDescription），语义只有一份。
 */
@Composable
fun FcSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val trackColor = if (checked) colors.ink else colors.surface3
    val knobColor = if (checked) colors.onInk else colors.surface
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 20.dp)
            .background(trackColor, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .offset(x = if (checked) 14.dp else 0.dp)
                .size(16.dp)
                .background(knobColor, CircleShape)
                .then(
                    if (checked) {
                        Modifier
                    } else {
                        Modifier.shadow(FineCatElevation.Card, CircleShape, ambientColor = colors.shadow, spotColor = colors.shadow)
                    },
                ),
        )
    }
}
