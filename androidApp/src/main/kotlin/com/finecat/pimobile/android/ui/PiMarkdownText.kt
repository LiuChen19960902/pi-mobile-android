package com.finecat.pimobile.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.SpannedString
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.widget.AppCompatTextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import io.noties.markwon.utils.LeadingMarginUtils
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.ThematicBreak
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Markdown 渲染所需的全部外观参数。所有尺寸都是 px（由 Compose density 换算），
 * 参与 Spanned 渲染缓存的 key，深浅主题切换会生成新的 palette 与缓存条目。
 * 新增字段带默认值 0（透明/零尺寸），仅测试 palette 使用；生产入口 piMarkdownPalette
 * 总是显式赋值。
 */
internal data class PiMarkdownPalette(
    val textColor: Int,
    val linkColor: Int,
    val tableBorderColor: Int,
    val tableHeaderColor: Int,
    val tableOddRowColor: Int,
    val taskOutlineColor: Int,
    val tableBorderWidthPx: Int,
    val tableCellPaddingPx: Int,
    val inlineCodeBackgroundColor: Int,
    val inlineCodeCornerRadiusPx: Float,
    val inlineCodeHorizontalPaddingPx: Float,
    val inlineCodeVerticalPaddingPx: Float,
    val headingBreakColor: Int = 0,
    val ruleColor: Int = 0,
    val quoteStripeColor: Int = 0,
    val quoteStripeWidthPx: Int = 0,
    val listMarkerColor: Int = 0,
    val listFirstLevelMarginPx: Int = 0,
    val listNestedLevelMarginPx: Int = 0,
    val listMarkerSizePx: Int = 0,
    val listMarkerStrokeWidthPx: Int = 0,
    val codeBlockBackgroundColor: Int = 0,
    val codeBlockBorderColor: Int = 0,
    val codeBlockMarginPx: Int = 0,
    val codeBlockBorderWidthPx: Float = 0f,
    val codeBlockCornerRadiusPx: Float = 0f,
    val codeTextSizePx: Int = 0,
    val hairlineHeightPx: Int = 0
)

private data class MarkdownRenderTag(
    val source: String,
    val palette: PiMarkdownPalette
)

private data class MarkdownRenderCacheKey(
    val source: String,
    val palette: PiMarkdownPalette
)

/**
 * LazyColumn 会在长会话滚动时销毁并重新创建离屏 TextView。缓存不可变的 Spanned，避免每次
 * 用户气泡跨入可视区时，都在主线程重新解析紧随其后的完整 assistant Markdown。
 */
private object PiMarkdownRenderCache {
    private const val MAX_SOURCE_CHARACTERS = 512_000
    private val values = LinkedHashMap<MarkdownRenderCacheKey, Spanned>(16, 0.75f, true)
    private var sourceCharacters = 0

    @Synchronized
    fun get(source: String, palette: PiMarkdownPalette): Spanned? =
        values[MarkdownRenderCacheKey(source, palette)]

    @Synchronized
    fun put(source: String, palette: PiMarkdownPalette, rendered: Spanned): Spanned {
        val key = MarkdownRenderCacheKey(source, palette)
        values.remove(key)?.let { sourceCharacters -= key.source.length }
        val immutable = SpannedString(rendered)
        values[key] = immutable
        sourceCharacters += source.length
        val iterator = values.entries.iterator()
        while (sourceCharacters > MAX_SOURCE_CHARACTERS && iterator.hasNext()) {
            sourceCharacters -= iterator.next().key.source.length
            iterator.remove()
        }
        return immutable
    }
}

private fun renderedMarkdown(
    markwon: Markwon,
    markdown: String,
    palette: PiMarkdownPalette
): Spanned = PiMarkdownRenderCache.get(markdown, palette)
    ?: renderSafely(markwon, markdown, palette)

/** 远端文本不可信：深嵌套 HTML 可抛 StackOverflowError（Error 非 Exception，
 * runCatching 兜不住）。失败降级纯文本，不崩。 */
private fun renderSafely(markwon: Markwon, markdown: String, palette: PiMarkdownPalette): Spanned {
    val rendered = try {
        markwon.toMarkdown(markdown)
    } catch (overflow: StackOverflowError) {
        null
    } catch (_: Exception) {
        null
    } ?: return PiMarkdownRenderCache.put(markdown, palette, SpannedString(markdown))
    return PiMarkdownRenderCache.put(markdown, palette, rendered)
}

/** 代码围栏转义：内容含 ``` 行会提前闭合围栏，按内容最长反引号串加长围栏。 */
internal fun fencedCodeBlock(language: String, content: String): String {
    var longest = 0
    var run = 0
    for (char in content) {
        if (char == '`') {
            run += 1
            if (run > longest) longest = run
        } else {
            run = 0
        }
    }
    val fence = "`".repeat(maxOf(3, longest + 1))
    val tag = language.trim().takeIf(String::isNotEmpty)?.let { "$it\n" }.orEmpty()
    return "$fence$tag$content\n$fence"
}

/** Markwon 的 TablePlugin 带有可变 visitor，只在单独的后台线程串行解析。 */
private object PiAsyncMarkdownRenderer {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pi-markdown-renderer").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val markwons = mutableMapOf<PiMarkdownPalette, Markwon>()

    suspend fun render(
        context: Context,
        markdown: String,
        palette: PiMarkdownPalette
    ): Spanned = withContext(dispatcher) {
        PiMarkdownRenderCache.get(markdown, palette) ?: renderedMarkdown(
            markwon = markwons.getOrPut(palette) { buildPiMarkwon(context, palette) },
            markdown = markdown,
            palette = palette
        )
    }
}

internal class PiMarkdownPreloader internal constructor(
    private val context: Context,
    private val palette: PiMarkdownPalette
) {
    suspend fun preload(markdowns: List<String>) {
        markdowns.forEach { markdown ->
            // 取消后不再提交新解析：executor 队列里已入队的会跑完，
            // 但甩动期间 collectLatest 重启不再追加，从源头截断堆积。
            coroutineContext.ensureActive()
            PiAsyncMarkdownRenderer.render(context, markdown, palette)
        }
    }
}

@Composable
internal fun rememberPiMarkdownPreloader(): PiMarkdownPreloader {
    val context = LocalContext.current.applicationContext
    val palette = piMarkdownPalette(compact = false)
    return remember(context, palette) { PiMarkdownPreloader(context, palette) }
}

/** 主线程只共享实例执行轻量的 TextView 插件回调，不参与 LazyColumn 的 Markdown 解析。 */
private object PiMarkdownTextApplier {
    private val markwons = mutableMapOf<PiMarkdownPalette, Markwon>()

    @Synchronized
    fun get(context: Context, palette: PiMarkdownPalette): Markwon =
        markwons.getOrPut(palette) { buildPiMarkwon(context, palette) }
}

@Composable
private fun piMarkdownPalette(compact: Boolean): PiMarkdownPalette {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    return PiMarkdownPalette(
        textColor = colors.onSurface.copy(alpha = if (compact) 0.68f else 1f).toArgb(),
        linkColor = PiColors.Ocean.toArgb(),
        tableBorderColor = colors.onSurface.copy(alpha = 0.14f).toArgb(),
        tableHeaderColor = colors.onSurface.copy(alpha = 0.05f).toArgb(),
        tableOddRowColor = colors.onSurface.copy(alpha = 0.02f).toArgb(),
        taskOutlineColor = colors.onSurface.copy(alpha = 0.45f).toArgb(),
        tableBorderWidthPx = with(density) { 1.dp.roundToPx() },
        tableCellPaddingPx = with(density) { 6.dp.roundToPx() },
        inlineCodeBackgroundColor = colors.onSurface.copy(alpha = 0.075f).toArgb(),
        inlineCodeCornerRadiusPx = with(density) { 4.dp.toPx() },
        inlineCodeHorizontalPaddingPx = with(density) { 4.dp.toPx() },
        inlineCodeVerticalPaddingPx = with(density) { 1.dp.toPx() },
        headingBreakColor = colors.onSurface.copy(alpha = 0.10f).toArgb(),
        ruleColor = colors.onSurface.copy(alpha = 0.12f).toArgb(),
        quoteStripeColor = colors.onSurface.copy(alpha = 0.20f).toArgb(),
        quoteStripeWidthPx = with(density) { 2.dp.roundToPx() },
        listMarkerColor = colors.onSurface.copy(alpha = 0.45f).toArgb(),
        listFirstLevelMarginPx = with(density) { 16.dp.roundToPx() },
        listNestedLevelMarginPx = with(density) { 12.dp.roundToPx() },
        listMarkerSizePx = with(density) { 3.dp.roundToPx() },
        listMarkerStrokeWidthPx = with(density) { 1.dp.roundToPx() },
        codeBlockBackgroundColor = colors.onSurface.copy(alpha = 0.05f).toArgb(),
        codeBlockBorderColor = colors.onSurface.copy(alpha = 0.10f).toArgb(),
        codeBlockMarginPx = with(density) { 12.dp.roundToPx() },
        codeBlockBorderWidthPx = with(density) { 1.dp.toPx() },
        codeBlockCornerRadiusPx = with(density) { 6.dp.toPx() },
        codeTextSizePx = with(density) { 12.sp.roundToPx() },
        hairlineHeightPx = with(density) { 1.dp.roundToPx() }
    )
}

@Composable
internal fun PiMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val palette = piMarkdownPalette(compact)
    val markwon = remember(context.applicationContext, palette) {
        PiMarkdownTextApplier.get(context.applicationContext, palette)
    }
    val textSizeSp = 14f
    val lineSpacingExtra = with(density) { (if (compact) 2.dp else 4.dp).toPx() }
    PiRenderedMarkdownText(
        markdown = markdown,
        rendered = renderedMarkdown(markwon, markdown, palette),
        palette = palette,
        textSizeSp = textSizeSp,
        lineSpacingExtra = lineSpacingExtra,
        modifier = modifier
    )
}

private data class PresentedMarkdown(
    val source: String,
    val rendered: Spanned
)

/**
 * LazyColumn 专用 Markdown：首次出现时直接提供富文本，后续流式内容在后台解析期间继续
 * 保留上一份富文本。它只会发生“富文本 -> 更新后的富文本”，不会回退成 Markdown 源码。
 */
@Composable
internal fun PiLazyMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 首帧是否也不在主线程解析：详情页大文本用 true（先纯文本后升级）；
     * 时间线保持 false（首帧即富文本，流式不回退）。 */
    deferFirstParse: Boolean = false
) {
    val context = LocalContext.current.applicationContext
    val palette = piMarkdownPalette(compact = compact)
    val markwon = remember(context, palette) {
        PiMarkdownTextApplier.get(context, palette)
    }
    var presented by remember(palette) {
        mutableStateOf(
            PresentedMarkdown(
                // 延迟首解析时 source 置空，LaunchedEffect 必走升级分支。
                source = if (deferFirstParse) "" else markdown,
                rendered = if (deferFirstParse) SpannedString(markdown)
                else renderedMarkdown(markwon, markdown, palette)
            )
        )
    }
    LaunchedEffect(context, markdown, palette) {
        if (presented.source != markdown) {
            presented = PresentedMarkdown(
                source = markdown,
                rendered = PiAsyncMarkdownRenderer.render(context, markdown, palette)
            )
        }
    }

    val density = LocalDensity.current
    val textSizeSp = 14f
    PiRenderedMarkdownText(
        markdown = presented.source,
        rendered = presented.rendered,
        palette = palette,
        textSizeSp = textSizeSp,
        lineSpacingExtra = with(density) { (if (compact) 2.dp else 4.dp).toPx() },
        modifier = modifier
    )
}

@Composable
private fun PiRenderedMarkdownText(
    markdown: String,
    rendered: Spanned,
    palette: PiMarkdownPalette,
    textSizeSp: Float,
    lineSpacingExtra: Float,
    modifier: Modifier
) {
    val context = LocalContext.current.applicationContext
    val markwon = remember(context, palette) {
        PiMarkdownTextApplier.get(context, palette)
    }
    AndroidView(
        factory = { viewContext ->
            PiMarkdownTextView(viewContext).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setLineSpacing(lineSpacingExtra, 1f)
                linksClickable = true
                // 部分文字选择复制（AndroidView 里的 TextView 不受 Compose
                // SelectionContainer 管辖，用 Android 原生选择手柄）。选中后
                // 复制的是原文；单击链接仍是打开，长按进入选择模式。
                setTextIsSelectable(true)
            }
        },
        onReset = { textView ->
            // Opt in to AndroidView reuse inside LazyColumn. update 会在下一次测量前写入新内容。
            textView.tag = null
        },
        update = { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(lineSpacingExtra, 1f)
            textView.setTextColor(palette.textColor)
            textView.setLinkTextColor(palette.linkColor)
            val nextTag = MarkdownRenderTag(markdown, palette)
            if (textView.tag != nextTag) {
                markwon.setParsedMarkdown(textView, rendered)
                // 冻结表格行高 + 宽度变化时一次性修正：TableRowSpan 首次测量
                //（getSize）时 cell 布局为空，只返回单行高度；Markwon 依赖
                // TableRowsScheduler 的 invalidator 在 draw 后修正，但在我们的
                // LazyColumn + 吸底重滚组合里会形成自激循环。这里置空 invalidator
                // 切断循环，同时在视图宽度确定/变化时手动触发一次 setText（等价
                // 于 Markwon 的修正），让表格按真实宽度一次性撑到正确高度。
                if (rendered is Spanned) {
                    rendered.getSpans(0, rendered.length, TableRowSpan::class.java)
                        .forEach { it.invalidator(null) }
                    (textView as PiMarkdownTextView).requestTableRemeasureOnWidth()
                }
                textView.tag = nextTag
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 流式阶段不反复解析整篇 Markdown。最终 assistant/message 到达后 ID 会切换为
 * 持久化事件 ID，再一次性使用 Markwon 渲染完整正文。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun PiStreamingAwareMarkdownText(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    // 流式期间不含 TablePlugin：表格 cell 的 AsyncDrawableSpan 在流式 token
    // 反复解析中反复重建布局，触发 TableRowsScheduler 的 invalidate→setText→
    // 重测自激循环（“表格疯狂抽动”的根因）。流式阶段退化为纯文本渲染
    //（表格管道符原样显示，跟随增长），终态由 PiLazyMarkdownText 一次性
    // 完整 Markwon 渲染（含表格）。表格通常在流式末尾才完整出现，此时
    // 已切换终态路径，用户几乎看不到“纯文本表格”阶段。
    if (isStreaming) {
        PiMarkdownText(markdown, modifier)
    } else {
        PiLazyMarkdownText(markdown, modifier)
    }
}

internal fun buildPiMarkwon(
    context: Context,
    palette: PiMarkdownPalette
): Markwon {
    val tableTheme = TableTheme.emptyBuilder()
        .tableBorderColor(palette.tableBorderColor)
        .tableBorderWidth(palette.tableBorderWidthPx)
        .tableCellPadding(palette.tableCellPaddingPx)
        .tableHeaderRowBackgroundColor(palette.tableHeaderColor)
        .tableEvenRowBackgroundColor(Color.Transparent.toArgb())
        .tableOddRowBackgroundColor(palette.tableOddRowColor)
        .build()

    return Markwon.builder(context)
        .usePlugin(CorePlugin.create())
        .usePlugin(
            object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .blockMargin(palette.listFirstLevelMarginPx)
                        .blockQuoteWidth(palette.quoteStripeWidthPx)
                        .blockQuoteColor(palette.quoteStripeColor)
                        .listItemColor(palette.listMarkerColor)
                        .bulletWidth(palette.listMarkerSizePx)
                        .bulletListItemStrokeWidth(palette.listMarkerStrokeWidthPx)
                        .headingBreakColor(palette.headingBreakColor)
                        .headingBreakHeight(palette.hairlineHeightPx)
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeTextSize(palette.codeTextSizePx)
                        // 标题不用 HTML 默认倍率（2/1.5/1.17…），按 14sp 正文对应
                        // 样稿的 18/17/15/14sp；倍率相对 TextView 实际字号，随 fontScale 缩放
                        .headingTextSizeMultipliers(
                            floatArrayOf(
                                18f / 14f,
                                17f / 14f,
                                15f / 14f,
                                1f,
                                1f,
                                1f
                            )
                        )
                }

                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    // 与 CorePlugin 的 ListItem 访问器一致，但有序列表项也写入嵌套层级，
                    // 使嵌套有序/混合列表同样拿到“首层 16dp / 嵌套 12dp”的缩进。
                    builder.on(ListItem::class.java) { visitor, listItem ->
                        val start = visitor.length()
                        visitor.visitChildren(listItem)
                        val renderProps = visitor.renderProps()
                        val parent = listItem.parent
                        if (parent is OrderedList) {
                            CoreProps.LIST_ITEM_TYPE.set(
                                renderProps,
                                CoreProps.ListItemType.ORDERED
                            )
                            CoreProps.ORDERED_LIST_ITEM_NUMBER.set(
                                renderProps,
                                parent.startNumber
                            )
                            parent.startNumber = parent.startNumber + 1
                        } else {
                            CoreProps.LIST_ITEM_TYPE.set(
                                renderProps,
                                CoreProps.ListItemType.BULLET
                            )
                        }
                        CoreProps.BULLET_LIST_ITEM_LEVEL.set(renderProps, listItem.listDepth())
                        visitor.setSpansForNodeOptional(listItem, start)
                        if (visitor.hasNext(listItem)) {
                            visitor.ensureNewLine()
                        }
                    }
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.setFactory(Code::class.java) { configuration, _ ->
                        PiInlineCodeSpan(
                            theme = configuration.theme(),
                            backgroundColor = palette.inlineCodeBackgroundColor,
                            cornerRadiusPx = palette.inlineCodeCornerRadiusPx,
                            horizontalPaddingPx = palette.inlineCodeHorizontalPaddingPx,
                            verticalPaddingPx = palette.inlineCodeVerticalPaddingPx
                        )
                    }
                    builder.setFactory(ThematicBreak::class.java) { _, _ ->
                        MarkdownHairlineSpan(palette.ruleColor)
                    }
                    builder.setFactory(ListItem::class.java) { _, props ->
                        val level = CoreProps.BULLET_LIST_ITEM_LEVEL.get(props, 0)
                        if (CoreProps.LIST_ITEM_TYPE.require(props) == CoreProps.ListItemType.ORDERED) {
                            PiOrderedListItemSpan(
                                palette = palette,
                                level = level,
                                number = CoreProps.ORDERED_LIST_ITEM_NUMBER
                                    .require(props).toString() + ".\u00a0"
                            )
                        } else {
                            PiBulletListItemSpan(palette, level)
                        }
                    }
                    builder.setFactory(FencedCodeBlock::class.java) { _, _ ->
                        PiCodeBlockSpan(palette)
                    }
                    builder.setFactory(IndentedCodeBlock::class.java) { _, _ ->
                        PiCodeBlockSpan(palette)
                    }
                }

                override fun beforeSetText(textView: TextView, markdown: Spanned) {
                    // 有序编号宽度需用 TextView 真实画笔测量，多位编号才不会与正文重叠
                    PiOrderedListItemSpan.measure(textView, markdown)
                }
            }
        )
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(tableTheme))
        .usePlugin(
            TaskListPlugin.create(
                PiColors.Ocean.toArgb(),
                palette.taskOutlineColor,
                Color.White.toArgb()
            )
        )
        .usePlugin(HtmlPlugin.create())
        .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
        .build()
}

private fun Node.listDepth(): Int {
    var depth = 0
    var node = parent
    while (node != null) {
        if (node is ListItem) depth += 1
        node = node.parent
    }
    return depth
}

/**
 * GitHub 式无序列表项：缩进按层级区分（首层 16dp、嵌套 12dp），标记画在自身缩进带内
 * （首层实心圆、二层空心圆、更深层实心方块），不再复用 Markwon 的大尺寸大缩进默认值。
 * 嵌套时父项的 LeadingMarginSpan 与本项叠加，总缩进 = 16 + 12×(层数-1)。
 */
internal class PiBulletListItemSpan(
    private val palette: PiMarkdownPalette,
    private val level: Int
) : LeadingMarginSpan {
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerBounds = RectF()

    private fun marginPx(): Int =
        if (level <= 0) palette.listFirstLevelMarginPx else palette.listNestedLevelMarginPx

    override fun getLeadingMargin(first: Boolean): Int = marginPx()

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        if (!first || !LeadingMarginUtils.selfStart(start, text, this)) return
        val side = palette.listMarkerSizePx
        if (side <= 0) return

        markerPaint.set(paint)
        markerPaint.color = palette.listMarkerColor
        markerPaint.strokeWidth = palette.listMarkerStrokeWidthPx.toFloat()
        markerPaint.style = if (level == 1) Paint.Style.STROKE else Paint.Style.FILL

        val width = marginPx()
        val markerLeft = (width - side) / 2
        // 参考 Markwon BulletListItemSpan：Nougat 上嵌套 LeadingMarginSpan 的 x 不含外层
        // 缩进，手动补差值；其它版本 diff 为 0
        val diff = if (PI_LIST_SPAN_IS_NOUGAT) {
            val expected = if (level <= 0) 0
            else palette.listFirstLevelMarginPx + (level - 1) * palette.listNestedLevelMarginPx
            if (dir < 0) x - (layout.width - expected) else expected - x
        } else {
            0
        }
        val left = if (dir > 0) x + markerLeft + diff else x - width + markerLeft + diff
        markerBounds.set(
            left.toFloat(),
            (baseline + ((paint.descent() + paint.ascent()) / 2f + 0.5f).toInt() - side / 2).toFloat(),
            (left + side).toFloat(),
            (baseline + ((paint.descent() + paint.ascent()) / 2f + 0.5f).toInt() + (side + 1) / 2).toFloat()
        )
        when {
            level >= 2 -> canvas.drawRect(markerBounds, markerPaint)
            else -> canvas.drawOval(markerBounds, markerPaint)
        }
    }
}

/**
 * GitHub 式有序列表项：编号右对齐在缩进右缘，缩进取 max(编号宽, 层级基准)，
 * 多位编号自动变宽避免裁切/重叠。编号宽度在 beforeSetText 用 TextView 真实画笔测量，
 * 与 Markwon 内置 OrderedListItemSpan 机制一致。
 */
internal class PiOrderedListItemSpan(
    private val palette: PiMarkdownPalette,
    private val level: Int,
    internal val number: String
) : LeadingMarginSpan {
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var measuredNumberWidthPx: Int = 0

    private fun marginPx(): Int =
        if (level <= 0) palette.listFirstLevelMarginPx else palette.listNestedLevelMarginPx

    override fun getLeadingMargin(first: Boolean): Int =
        maxOf(measuredNumberWidthPx, marginPx())

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        if (!first || !LeadingMarginUtils.selfStart(start, text, this)) return

        numberPaint.set(paint)
        numberPaint.color = palette.listMarkerColor

        val numberWidth = (numberPaint.measureText(number) + 0.5f).toInt()
        if (numberWidth > measuredNumberWidthPx) {
            // 布局已按旧宽度完成，这里自愈，等待下一次布局生效（与 Markwon 内置实现一致）
            measuredNumberWidthPx = numberWidth
        }
        val width = maxOf(numberWidth, marginPx())
        val diff = if (PI_LIST_SPAN_IS_NOUGAT) {
            val expected = if (level <= 0) 0
            else palette.listFirstLevelMarginPx + (level - 1) * palette.listNestedLevelMarginPx
            if (dir < 0) x - (layout.width - expected) else expected - x
        } else {
            0
        }
        val left = (if (dir > 0) x + width - numberWidth else x - numberWidth) + diff
        canvas.drawText(number, left.toFloat(), baseline.toFloat(), numberPaint)
    }

    companion object {
        /** 在 setText 前用 TextView 真实画笔测量全部编号，布局阶段即可用正确缩进。 */
        fun measure(textView: TextView, text: CharSequence) {
            val spanned = text as? Spanned ?: return
            val paint = textView.paint
            spanned.getSpans(0, spanned.length, PiOrderedListItemSpan::class.java).forEach { span ->
                span.measuredNumberWidthPx = (paint.measureText(span.number) + 0.5f).toInt()
            }
        }
    }
}

/**
 * Nougat(API 24/25) 的 LeadingMarginSpan 嵌套绘制 x 坐标不含外层缩进（Markwon 4.2.1
 * 修复的同一 bug），这里用与内置 span 相同的差值校正。
 */
private val PI_LIST_SPAN_IS_NOUGAT =
    Build.VERSION.SDK_INT == Build.VERSION_CODES.N ||
        Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1

/**
 * GitHub 式代码块：文本用等宽字体（字号见 palette.codeTextSizePx），左侧留缩进带；
 * 背景/圆角/细边框不在这里逐行绘制（半透明色多次叠加会出现接缝），
 * 由 PiMarkdownTextView.onDraw 按整块的行范围一次性画出。
 */
internal class PiCodeBlockSpan(internal val palette: PiMarkdownPalette) :
    MetricAffectingSpan(), LeadingMarginSpan {

    override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)

    override fun updateDrawState(textPaint: TextPaint) = apply(textPaint)

    private fun apply(textPaint: TextPaint) {
        textPaint.typeface = Typeface.MONOSPACE
        if (palette.codeTextSizePx > 0) {
            textPaint.textSize = palette.codeTextSizePx.toFloat()
        }
    }

    override fun getLeadingMargin(first: Boolean): Int = palette.codeBlockMarginPx

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) = Unit
}

/**
 * Markwon 默认用 TextPaint.bgColor 绘制行内代码，只能得到直角矩形。这里保留默认
 * CodeSpan 的字体/字号/前景色设置，并由 TextView 按实际换行片段绘制圆角背景。
 */
internal class PiInlineCodeSpan(
    theme: MarkwonTheme,
    internal val backgroundColor: Int,
    internal val cornerRadiusPx: Float,
    internal val horizontalPaddingPx: Float,
    internal val verticalPaddingPx: Float
) : MetricAffectingSpan() {
    private val delegate = CodeSpan(theme)

    override fun updateMeasureState(textPaint: TextPaint) {
        delegate.updateMeasureState(textPaint)
    }

    override fun updateDrawState(textPaint: TextPaint) {
        delegate.updateDrawState(textPaint)
        textPaint.bgColor = android.graphics.Color.TRANSPARENT
    }
}

internal class PiMarkdownTextView(context: Context) : AppCompatTextView(context) {
    // 背景绘制对象由视图复用，避免滚动/流式重绘时反复创建路径与矩形。
    private val codeBlockFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val codeBlockBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val codeBlockRect = RectF()
    private val inlineCodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inlineCodeSelectionPath = android.graphics.Path()
    private val inlineCodeLineClip = android.graphics.Path()
    private val inlineCodeRect = RectF()
    // 表格行高修正：宽度确定后一次性重测，见 PiRenderedMarkdownText 的注释。
    private var tableRemeasureWidth = -1
    internal fun requestTableRemeasureOnWidth() {
        tableRemeasureWidth = -1
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != tableRemeasureWidth && text is Spanned &&
            (text as Spanned).getSpans(0, (text as Spanned).length, TableRowSpan::class.java).isNotEmpty()
        ) {
            tableRemeasureWidth = w
            @Suppress("USELESS_CAST")
            text = text
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawCodeBlockBackgrounds(canvas)
        drawInlineCodeBackgrounds(canvas)
        super.onDraw(canvas)
    }

    /** 代码块背景/圆角/细边框：按整块的行范围一次性绘制，避免逐行半透明叠加出现接缝。 */
    private fun drawCodeBlockBackgrounds(canvas: Canvas) {
        val spanned = text as? Spanned ?: return
        val textLayout = layout ?: return
        val spans = spanned.getSpans(0, spanned.length, PiCodeBlockSpan::class.java)
        if (spans.isEmpty()) return

        val saveCount = canvas.save()
        canvas.translate(
            (compoundPaddingLeft - scrollX).toFloat(),
            (extendedPaddingTop - scrollY).toFloat()
        )
        spans.forEach { span ->
            drawCodeBlockSpan(canvas, textLayout, spanned, span)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawCodeBlockSpan(
        canvas: Canvas,
        textLayout: Layout,
        spanned: Spanned,
        span: PiCodeBlockSpan
    ) {
        val spanStart = spanned.getSpanStart(span)
        val spanEnd = spanned.getSpanEnd(span)
        if (spanStart < 0 || spanEnd <= spanStart) return

        val firstLine = textLayout.getLineForOffset(spanStart)
        val lastLine = textLayout.getLineForOffset((spanEnd - 1).coerceAtLeast(spanStart))
        val top = textLayout.getLineTop(firstLine).toFloat()
        val bottom = textLayout.getLineBottom(lastLine).toFloat()
        val radius = span.palette.codeBlockCornerRadiusPx

        // 段落盒边界（Layout 公开信息，StaticLayout 已实现 margin 语义：
        // getParagraphLeft/Right 按该行覆盖的全部 LeadingMarginSpan 求和 inset）。
        // LTR：paragraphLeft = 外层引用/列表缩进 + 代码自身 leading margin，
        //      paragraphRight = 布局宽；背景左缘 = paragraphLeft - 自身 margin。
        // RTL：leading margin 画在右侧，paragraphRight = 布局宽 -（外层缩进 + 自身
        //      margin），paragraphLeft = 0；加回自身 margin 才落到外层真实缩进处，
        //      与 LTR 的减法对称。背景跟随外层真实缩进，不再盖住缩进带。
        val ownMargin = span.palette.codeBlockMarginPx.toFloat()
        val isRtl =
            textLayout.getParagraphDirection(firstLine) == Layout.DIR_RIGHT_TO_LEFT
        val left = if (isRtl) {
            textLayout.getParagraphLeft(firstLine).toFloat()
        } else {
            textLayout.getParagraphLeft(firstLine) - ownMargin
        }
        val right = if (isRtl) {
            textLayout.getParagraphRight(firstLine) + ownMargin
        } else {
            textLayout.getParagraphRight(firstLine).toFloat()
        }

        codeBlockFillPaint.color = span.palette.codeBlockBackgroundColor
        codeBlockRect.set(left, top, right, bottom)
        canvas.drawRoundRect(codeBlockRect, radius, radius, codeBlockFillPaint)

        val borderWidth = span.palette.codeBlockBorderWidthPx
        if (borderWidth > 0f) {
            val inset = borderWidth / 2f
            codeBlockBorderPaint.color = span.palette.codeBlockBorderColor
            codeBlockBorderPaint.strokeWidth = borderWidth
            codeBlockRect.set(left + inset, top + inset, right - inset, bottom - inset)
            canvas.drawRoundRect(codeBlockRect, radius, radius, codeBlockBorderPaint)
        }
    }

    private fun drawInlineCodeBackgrounds(canvas: Canvas) {
        val spanned = text as? Spanned ?: return
        val textLayout = layout ?: return
        val spans = spanned.getSpans(0, spanned.length, PiInlineCodeSpan::class.java)
        if (spans.isEmpty()) return

        val saveCount = canvas.save()
        canvas.translate(
            (compoundPaddingLeft - scrollX).toFloat(),
            (extendedPaddingTop - scrollY).toFloat()
        )
        spans.forEach { span ->
            drawInlineCodeSpan(canvas, textLayout, spanned, span)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawInlineCodeSpan(
        canvas: Canvas,
        textLayout: Layout,
        spanned: Spanned,
        span: PiInlineCodeSpan
    ) {
        val spanStart = spanned.getSpanStart(span)
        val spanEnd = spanned.getSpanEnd(span)
        if (spanStart < 0 || spanEnd <= spanStart) return

        val codePaint = TextPaint(paint).also(span::updateDrawState)
        val fontMetrics = codePaint.fontMetrics
        inlineCodeFillPaint.color = span.backgroundColor
        val firstLine = textLayout.getLineForOffset(spanStart)
        val lastLine = textLayout.getLineForOffset((spanEnd - 1).coerceAtLeast(spanStart))
        for (line in firstLine..lastLine) {
            val segmentStart = maxOf(spanStart, textLayout.getLineStart(line))
            var segmentEnd = minOf(spanEnd, textLayout.getLineEnd(line))
            while (segmentEnd > segmentStart && spanned[segmentEnd - 1] == '\n') {
                segmentEnd -= 1
            }
            if (segmentEnd <= segmentStart) continue

            // 行尾 offset 属于下一行，直接取 primaryHorizontal 会把底色画回行首。
            // 选区按当前行的方向片段测量；裁掉选区跨行时附加的行尾空白与下一行。
            inlineCodeSelectionPath.reset()
            textLayout.getSelectionPath(segmentStart, segmentEnd, inlineCodeSelectionPath)
            inlineCodeLineClip.reset()
            inlineCodeLineClip.addRect(textLayout.getLineLeft(line), textLayout.getLineTop(line).toFloat(),
                textLayout.getLineRight(line), textLayout.getLineBottom(line).toFloat(),
                android.graphics.Path.Direction.CW)
            inlineCodeSelectionPath.op(inlineCodeLineClip, android.graphics.Path.Op.INTERSECT)
            if (inlineCodeSelectionPath.isEmpty) continue
            inlineCodeSelectionPath.computeBounds(inlineCodeRect, true)
            val left = (inlineCodeRect.left - span.horizontalPaddingPx).coerceAtLeast(0f)
            val right = (inlineCodeRect.right + span.horizontalPaddingPx)
                .coerceAtMost(textLayout.width.toFloat())
            val baseline = textLayout.getLineBaseline(line).toFloat()
            val top = (baseline + fontMetrics.ascent - span.verticalPaddingPx)
                .coerceAtLeast(textLayout.getLineTop(line).toFloat())
            val bottom = (baseline + fontMetrics.descent + span.verticalPaddingPx)
                .coerceAtMost(textLayout.getLineBottom(line).toFloat())
            inlineCodeRect.set(left, top, right, bottom)
            canvas.drawRoundRect(
                inlineCodeRect,
                span.cornerRadiusPx,
                span.cornerRadiusPx,
                inlineCodeFillPaint
            )
        }
    }
}

/** Markwon 的内置矩形分割线最少会占两个物理像素；这里直接绘制单像素 hairline。 */
private class MarkdownHairlineSpan(
    private val color: Int
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = 0

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        val linePaint = Paint(paint).apply {
            color = this@MarkdownHairlineSpan.color
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = false
        }
        val left = if (dir > 0) x.toFloat() else (x - canvas.width).toFloat()
        val right = if (dir > 0) canvas.width.toFloat() else x.toFloat()
        val centerY = (top + bottom) / 2f
        canvas.drawLine(left, centerY, right, centerY, linePaint)
    }
}
