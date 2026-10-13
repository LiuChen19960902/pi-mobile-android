package com.finecat.pimobile.android.ui.finecat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatRadius
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatSpacing
import com.finecat.pimobile.android.ui.finecat.theme.LocalFineCatType
import com.finecat.pimobile.android.ui.PiInlineCodeSpan
import com.finecat.pimobile.android.ui.PiMarkdownTextView
import com.finecat.pimobile.android.ui.finecat.components.FcIconButton
import com.finecat.pimobile.android.ui.finecat.components.FineCatIcons
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Code
import kotlin.math.max

/**
 * P3 实验（路线 A）：块级拆分 Markdown——围栏代码与 GFM 表格拆成独立
 * Compose 容器，其余块交给 Markwon 渲染富文本。表格不走 TextView span，
 * 从机制上排除旧表格"重绘不生效"的路径；无新增依赖（Markwon 已在依赖表）。
 *
 * 拆分是纯函数（[splitMarkdownBlocks]），对 append-only 的流式文本
 * 幂等且稳定：未闭合围栏按已收内容成块，闭合后自然衔接。
 * 复用现有 Markwon 依赖；块级拆分单独处理流式更新和表格滚动。
 */

sealed class FcMdBlock {
    /** 其余 Markdown（含标题/列表/引用/行内样式），整段交给 Markwon。 */
    data class Para(val markdown: String) : FcMdBlock()

    /** 围栏代码块；流式中未闭合时 content 为已收内容。 */
    data class Code(val lang: String, val content: String) : FcMdBlock()

    /** GFM 表格；header 与每行单元格数以表头为准对齐。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : FcMdBlock()
}

private val TABLE_SEPARATOR = Regex("^\\|?[\\s:|-]*-[\\s:|-]*\\|?$")

/** 行是否以竖线开头（允许前导空格）。 */
private fun isTableRow(line: String): Boolean = line.trimStart().startsWith("|")

/** 拆分一行表格为单元格：去掉首尾空竖线后按 | 切分并 trim。 */
private fun parseTableRow(line: String): List<String> {
    val t = line.trim()
    val body = if (t.startsWith("|")) t.removePrefix("|") else t
    val trimmedEnd = if (body.endsWith("|")) body.removeSuffix("|") else body
    return trimmedEnd.split("|").map { it.trim() }
}

/**
 * 块级拆分：围栏代码（``` 或 ~~~，支持未闭合）、GFM 表格（分隔行判定）、
 * 其余连续非空行为一个 Para。行数 O(n)；流式 append-only 时前面的块保持稳定。
 */
fun splitMarkdownBlocks(source: String): List<FcMdBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<FcMdBlock>()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks.add(FcMdBlock.Para(para.toString().trimEnd()))
            para.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        // 围栏代码：``` 或 ~~~ 打开；未闭合时吃到底（流式友好）
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushPara()
            val fence = trimmed.take(3)
            val lang = trimmed.removePrefix(fence).trim()
            val body = StringBuilder()
            i++
            var closed = false
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith(fence)) {
                    closed = true
                    i++
                    break
                }
                body.appendLine(lines[i])
                i++
            }
            blocks.add(FcMdBlock.Code(lang, body.toString().trimEnd('\n')))
            continue
        }
        // GFM 表格：当前行是竖线行且下一行是分隔行
        if (isTableRow(line) && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1].trim())) {
            flushPara()
            val header = parseTableRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableRow(lines[i])) {
                val cells = parseTableRow(lines[i])
                rows.add(List(max(header.size, cells.size)) { c -> cells.getOrElse(c) { "" } })
                i++
            }
            blocks.add(FcMdBlock.Table(header, rows))
            continue
        }
        // 空行结束段落
        if (trimmed.isEmpty()) {
            flushPara()
            i++
            continue
        }
        para.appendLine(line)
        i++
    }
    flushPara()
    return blocks
}

/**
 * Markdown 渲染（路线 A 实验）：段落由 Markwon 提供完整行内样式
 * （粗体/斜体/行内代码/链接），代码与表格是 Compose 容器。
 * 每次正文变化整块重拆（append-only 时前面块索引稳定，配合 key 保持复用）。
 */
@Composable
fun FcMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    tableTagPrefix: String? = null,
) {
    val blocks = remember(markdown) { splitMarkdownBlocks(markdown) }
    var tableIndex = 0
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is FcMdBlock.Para -> FcMdParagraph(
                    markdown = block.markdown,
                    modifier = Modifier.fillMaxWidth(),
                )
                is FcMdBlock.Code -> FcMdCodeBlock(
                    block = block,
                    modifier = Modifier.fillMaxWidth(),
                )
                is FcMdBlock.Table -> {
                    val tag = tableTagPrefix?.let { "$it.$tableIndex" }
                    tableIndex++
                    FcMdTable(
                        block = block,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
                    )
                }
            }
        }
    }
}

/** 段落：Markwon 渲染为 Spanned 落进 TextView（无 TablePlugin——表格不经过这里）。 */
@Composable
private fun FcMdParagraph(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val prose = type.prose.copy(fontSize = 16.sp, lineHeight = 26.sp)
    val markwon = remember(context, colors, density) {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(colors.brand.toArgb())
                        .blockMargin(with(density) { 16.dp.roundToPx() })
                        .listItemColor(colors.text3.toArgb())
                        .bulletWidth(with(density) { 4.dp.roundToPx() })
                        .bulletListItemStrokeWidth(with(density) { 1.dp.roundToPx() })
                        .blockQuoteColor(colors.line2.toArgb())
                        .blockQuoteWidth(with(density) { 2.dp.roundToPx() })
                        .codeTypeface(android.graphics.Typeface.MONOSPACE)
                        .codeTextSize(with(density) { 14.sp.roundToPx() })
                        .codeTextColor(colors.text.toArgb())
                        // 16sp正文上的19/18/17/16sp标题，手机上以字重和小幅字号差区分层级。
                        .headingTextSizeMultipliers(floatArrayOf(1.1875f, 1.125f, 1.0625f, 1f, 1f, 1f))
                        .headingBreakHeight(0)
                        .thematicBreakColor(colors.line.toArgb())
                        .thematicBreakHeight(with(density) { 1.dp.roundToPx() })
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.setFactory(Code::class.java) { configuration, _ ->
                        // 复用生产 TextView 的逐行圆角绘制，长 code 仍能自然换行。
                        PiInlineCodeSpan(
                            theme = configuration.theme(),
                            backgroundColor = colors.text.copy(alpha = 0.055f).toArgb(),
                            cornerRadiusPx = with(density) { 4.dp.toPx() },
                            horizontalPaddingPx = with(density) { 3.dp.toPx() },
                            verticalPaddingPx = with(density) { 1.dp.toPx() },
                        )
                    }
                }
            })
            .build()
    }
    val rendered = remember(markwon, markdown) {
        runCatching { markwon.render(markwon.parse(markdown)) }.getOrNull()
    }
    if (rendered != null) {
        AndroidView(
            factory = { ctx ->
                PiMarkdownTextView(ctx).apply {
                    includeFontPadding = false
                    // 逐行排满，避免流式段落整体优化把前面的行提前折回。
                    breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    // 平台仅铺满自动换行的行；末行、短段落和显式换行保持自然对齐。
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_CHARACTER
                    } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                        justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
                    }
                    setPadding(0, 0, 0, 0)
                    linksClickable = true
                    setTextIsSelectable(true)
                }
            },
            update = { view ->
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, prose.fontSize.value)
                androidx.core.widget.TextViewCompat.setLineHeight(view, with(density) { prose.lineHeight.roundToPx() })
                view.setTextColor(colors.text.toArgb())
                view.setLinkTextColor(colors.brand.toArgb())
                view.highlightColor = colors.brand.copy(alpha = 0.22f).toArgb()
                if (view.tag !== rendered) {
                    markwon.setParsedMarkdown(view, rendered)
                    view.tag = rendered
                }
            },
            modifier = modifier,
        )
    } else {
        // 解析失败兜底：原文展示，不吞内容
        SelectionContainer {
            Text(
                text = markdown,
                style = prose,
                color = colors.text,
                modifier = modifier,
            )
        }
    }
}

/** 正文围栏代码：语言栏、独立复制、长行横滑；工具详情仍使用原 FcCodeBlock。 */
@Composable
private fun FcMdCodeBlock(
    block: FcMdBlock.Code,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.content) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2.copy(alpha = 0.6f))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.lang.ifBlank { "代码" },
                style = type.xs.copy(fontWeight = FontWeight.Medium),
                color = colors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FcIconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(block.content))
                    copied = true
                },
                icon = if (copied) FineCatIcons.Check else FineCatIcons.Copy,
                contentDescription = if (copied) "代码已复制" else "复制代码",
                buttonSize = 48.dp,
                iconSize = 16.dp,
            )
        }
        HorizontalDivider(color = colors.line)
        SelectionContainer {
            Text(
                text = block.content,
                style = type.sm.copy(fontFamily = type.mono, fontSize = 13.sp, lineHeight = 22.sp),
                color = colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp),
            )
        }
    }
}

/** GFM 表格：每列固定 120dp、整表横向可滑（每个实例独立 scrollState）、发丝行线。 */
@Composable
fun FcMdTable(
    block: FcMdBlock.Table,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFineCatColors.current
    val type = LocalFineCatType.current
    val spacing = LocalFineCatSpacing.current
    val radius = LocalFineCatRadius.current
    val scroll = rememberScrollState()
    val cellWidth = 120.dp
    val lineColor = colors.line
    SelectionContainer {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(radius.chip))
                .horizontalScroll(scroll),
        ) {
            Row {
                block.header.forEach { cell ->
                    Text(
                        text = cell,
                        style = type.sm.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(cellWidth)
                            .padding(horizontal = spacing.s2, vertical = spacing.s2),
                    )
                }
            }
            block.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .background(colors.surface2.copy(alpha = 0.5f)),
                ) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = type.sm,
                            color = colors.text2,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .width(cellWidth)
                                .padding(horizontal = spacing.s2, vertical = spacing.s2),
                        )
                    }
                }
            }
        }
    }
}
