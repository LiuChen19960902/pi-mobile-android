package com.finecat.pimobile.android.ui.finecat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.finecat.pimobile.android.ui.finecat.components.FcCard
import com.finecat.pimobile.android.ui.finecat.components.FcHeader
import com.finecat.pimobile.android.ui.finecat.components.FcHint
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FcPopover
import com.finecat.pimobile.android.ui.finecat.components.FcPopoverItem
import com.finecat.pimobile.android.ui.finecat.components.FcRow
import com.finecat.pimobile.android.ui.finecat.components.FcSectionLabel
import com.finecat.pimobile.android.ui.finecat.components.FcSheet
import com.finecat.pimobile.android.ui.finecat.components.FcStatusTag
import com.finecat.pimobile.android.ui.finecat.components.FcSwitch
import com.finecat.pimobile.android.ui.finecat.components.FcTagStyle
import com.finecat.pimobile.android.ui.finecat.components.FcTextButton
import com.finecat.pimobile.android.ui.finecat.components.FcViewSeg
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType

/**
 * P1 组件陈列（debug fixture）：V1 token、V2 行与触控、V3 弹层与无障碍的
 * 手动 + 设备测试入口。数据是固定假数据，只存在于此 debug 宿主。
 */
@Composable
fun FineCatGalleryScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    val colors = LocalFineCatColors.current
    val spacing = LocalFineCatSpacing.current
    val type = LocalFineCatType.current

    var selectedView by remember { mutableIntStateOf(0) }
    var directorySheet by remember { mutableStateOf(false) }
    var longSheet by remember { mutableStateOf(false) }
    var popoverExpanded by remember { mutableStateOf(false) }
    var popoverAnchor by remember { mutableStateOf(IntRect.Zero) }
    // 初值 1：点第 0 项后 UI 值可观察变化
    var popoverChoice by remember { mutableIntStateOf(1) }
    var toggleState by remember { mutableStateOf(false) }
    var showChatDemo by rememberSaveable { mutableStateOf(false) }
    var showMarkdownDemo by rememberSaveable { mutableStateOf(false) }
    val dialects = remember { listOf("anthropic", "openai-completions", "openai-responses") }

    if (showChatDemo) {
        FineCatChatDemoScreen(
            isDark = isDark,
            onToggleTheme = onToggleTheme,
            onBackToComponents = { showChatDemo = false },
        )
        return
    }
    if (showMarkdownDemo) {
        FineCatMarkdownDemoScreen(
            isDark = isDark,
            onToggleTheme = onToggleTheme,
            onBackToComponents = { showMarkdownDemo = false },
        )
        return
    }
    Box(modifier = Modifier.fillMaxSize().background(colors.bg).testTag("gallery.root")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding(),
        ) {
            FcHeader(
                title = "FineCat 组件陈列",
                showConnectionDot = true,
                directory = "/home/demo/work/pi-demo",
                onDirectoryClick = { directorySheet = true },
                leading = {
                    FcIconButton(
                        onClick = { longSheet = true },
                        icon = FineCatIcons.Menu,
                        contentDescription = "打开示例弹卡",
                        solid = true,
                        modifier = Modifier.testTag("gallery.sheetAnchor"),
                    )
                },
                trailing = {
                    FcViewSeg(
                        icons = listOf(FineCatIcons.Chat, FineCatIcons.Branch),
                        labels = listOf("对话", "tree"),
                        selected = selectedView,
                        onSelect = { selectedView = it },
                        modifier = Modifier.testTag("gallery.viewseg"),
                    )
                },
            )

            Column(modifier = Modifier.padding(horizontal = spacing.gutter)) {
                FcSectionLabel(text = "调试", topPadding = 8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FcTextButton(
                        text = if (isDark) "切到浅色" else "切到深色",
                        onClick = onToggleTheme,
                        modifier = Modifier.testTag("gallery.themeToggle"),
                    )
                    Spacer(modifier = Modifier.padding(start = spacing.s4))
                    FcStatusTag(text = if (isDark) "深色" else "浅色", style = FcTagStyle.Ok)
                    Spacer(modifier = Modifier.padding(start = spacing.s4))
                    Spacer(modifier = Modifier.padding(start = spacing.s2))
                    FcTextButton(
                        text = "对话骨架",
                        onClick = { showChatDemo = true },
                        modifier = Modifier.testTag("gallery.chatDemo"),
                    )
                    FcTextButton(
                        text = "Markdown",
                        onClick = { showMarkdownDemo = true },
                        modifier = Modifier.testTag("gallery.markdownDemo"),
                    )
                    // V1：主操作实心是墨色不是品牌蓝（发送按钮的形态）
                    Box(
                        modifier = Modifier
                            .testTag("gallery.inkSend")
                            .background(colors.ink, androidx.compose.foundation.shape.CircleShape)
                            .padding(spacing.s3),
                    ) {
                        Text("发送", style = type.sm, color = colors.onInk)
                    }
                }

                FcSectionLabel(text = "会话")
                FcCard {
                    FcRow(
                        label = "会话设置",
                        lead = FineCatIcons.Sliders,
                        value = "3 项",
                        showChevron = true,
                        onClick = { directorySheet = true },
                    )
                    FcRow(
                        label = "长内容弹卡",
                        lead = FineCatIcons.File,
                        value = "可滚动 · 完成",
                        showChevron = true,
                        dividerAbove = true,
                        onClick = { longSheet = true },
                        modifier = Modifier.testTag("gallery.longSheetRow"),
                    )
                    FcRow(
                        label = "后台运行",
                        sub = "开关整行可点，语义只有一份",
                        dividerAbove = true,
                        onClick = { toggleState = !toggleState },
                        clickRole = Role.Switch,
                        stateDescription = if (toggleState) "开" else "关",
                        trailing = { FcSwitch(checked = toggleState) },
                        modifier = Modifier.testTag("gallery.toggleRow"),
                    )
                }
                FcHint(text = "行高至少 48dp；行间发丝线从文案起点 56dp 处内缩。")

                FcSectionLabel(text = "状态标签")
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.s2)) {
                    FcStatusTag(text = "已连接", style = FcTagStyle.Ok)
                    FcStatusTag(text = "进行中", style = FcTagStyle.Wait)
                    FcStatusTag(text = "缺 key", style = FcTagStyle.Bad)
                }

                FcSectionLabel(text = "小浮层")
                FcCard {
                    Box(
                        modifier = Modifier
                            .testTag("gallery.popoverAnchor")
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { popoverExpanded = true }
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                popoverAnchor = IntRect(
                                    b.left.roundToInt(), b.top.roundToInt(),
                                    b.right.roundToInt(), b.bottom.roundToInt(),
                                )
                            },
                    ) {
                        FcRow(
                            label = "方言选择",
                            sub = "Popover 悬于 Sheet 之上",
                            value = dialects[popoverChoice],
                            showChevron = true,
                            valueDescription = "当前方言 ${dialects[popoverChoice]}",
                        )
                    }
                }

                FcSectionLabel(text = "长列表")
                repeat(60) { index ->
                    FcRow(
                        label = "列表行 ${index + 1}",
                        value = "#${index + 1}",
                        dividerAbove = index > 0,
                        modifier = if (index == 59) Modifier.testTag("gallery.longRow.60") else Modifier,
                    )
                }
                Spacer(modifier = Modifier.padding(bottom = spacing.s8))
            }
        }

        // 弹层宿主：Drawer < Sheet < Popover 层级在此叠加
        FcSheet(
            visible = directorySheet,
            onDismiss = { directorySheet = false },
            title = "工作目录",
            modifier = Modifier.testTag("gallery.directorySheet"),
        ) {
            Column {
                FcRow(label = "/home/demo/work/pi-demo", showChevron = true, onClick = { directorySheet = false }, dividerAbove = true)
                FcRow(label = "/home/demo/work/finecat", showChevron = true, onClick = { directorySheet = false }, dividerAbove = true)
                FcRow(label = "/mnt/c/dev/demo", showChevron = true, onClick = { directorySheet = false }, dividerAbove = true)
            }
        }
        FcSheet(
            visible = longSheet,
            onDismiss = { longSheet = false },
            title = "长内容弹卡",
            showDone = true,
            onDone = { longSheet = false },
            modifier = Modifier.testTag("gallery.longSheet"),
        ) {
            Column(
                modifier = Modifier
                    .testTag("gallery.longSheetContent")
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .testTag("gallery.popoverAnchorInSheet")
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { popoverExpanded = true }
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInWindow()
                            popoverAnchor = IntRect(
                                b.left.roundToInt(), b.top.roundToInt(),
                                b.right.roundToInt(), b.bottom.roundToInt(),
                            )
                        },
                ) {
                    FcRow(
                        label = "在弹卡里选方言",
                        sub = "Popover 悬于本 Sheet 之上",
                        value = dialects[popoverChoice],
                        showChevron = true,
                    )
                }
                repeat(30) { index ->
                    FcRow(
                        label = "弹卡内条目 ${index + 1}",
                        value = "第 ${index + 1} 项",
                        dividerAbove = true,
                        modifier = if (index == 29) Modifier.testTag("gallery.sheetLastItem") else Modifier,
                    )
                }
            }
        }
        FcPopover(
            expanded = popoverExpanded,
            onDismissRequest = { popoverExpanded = false },
            anchorBoundsInWindow = popoverAnchor,
        ) {
            dialects.forEachIndexed { index, name ->
                FcPopoverItem(
                    text = name,
                    selected = index == popoverChoice,
                    onClick = {
                        popoverChoice = index
                        popoverExpanded = false
                    },
                    modifier = if (index == 0) Modifier.testTag("gallery.popover.item0") else Modifier,
                )
            }
            FcPopoverItem(
                text = "恢复上层给的",
                secondary = true,
                onClick = {
                    popoverChoice = 0
                    popoverExpanded = false
                },
                modifier = Modifier.testTag("gallery.popover.secondary"),
            )
        }
    }
}
