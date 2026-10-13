package com.finecat.pimobile.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.text.Spanned
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import io.noties.markwon.core.spans.BlockQuoteSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PiMarkdownTextDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun gfmTableIsRenderedAsTableSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = buildPiMarkwon(context, testPalette())
        val markdown = """
            | 优先级 | 主文案 | 感觉 |
            | :--- | :--- | :---: |
            | ⭐ 推荐 | **平安喜乐** | 温暖 |
            | 备选 | ~~旧文案~~ | 克制 |
        """.trimIndent()

        val rendered = markwon.toMarkdown(markdown)
        val tableRows = rendered.getSpans(
            0,
            rendered.length,
            TableRowSpan::class.java
        )

        assertTrue("GFM 表格应生成 TableRowSpan", tableRows.isNotEmpty())
        assertFalse("表格分隔符不应作为普通正文显示", rendered.toString().contains(":---"))
    }

    @Test
    fun longStreamingAndFinalMarkdownBothKeepTheDocumentTail() {
        val paragraphs = List(80) { index ->
            "第${index + 1}段：${"这是一段用于验证安卓长文本不会中断的内容。".repeat(8)}"
        }
        val code = "```kotlin\nval tailProbe = 80\n```"
        val document = paragraphs.joinToString("\n\n") + "\n\n" + code
        val markdown = mutableStateOf(paragraphs.take(40).joinToString("\n\n") + "\n\n" + code)
        val streaming = mutableStateOf(true)
        lateinit var hostView: View
        compose.setContent {
            hostView = LocalView.current
            PiTheme {
                // 长文档在可滚动容器中测量，不能要求整篇一次显示在视口里。
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PiStreamingAwareMarkdownText(markdown.value, isStreaming = streaming.value)
                }
            }
        }

        fun renderedView(): PiMarkdownTextView {
            fun collect(view: View): List<PiMarkdownTextView> = when (view) {
                is PiMarkdownTextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
            return collect(hostView).single()
        }

        fun assertDocumentTail(tail: String) {
            compose.runOnIdle {
                // AndroidView 的正文不在 Compose 文本语义树中；检查实际 TextView，
                // 同时验证末字符进入布局且未被 maxLines/ellipsis/高度裁掉。
                val view = renderedView()
                val text = view.text as Spanned
                assertTrue("应保留首段", text.toString().contains(paragraphs.first()))
                assertTrue("应保留末段 $tail", text.toString().contains(tail))
                assertTrue("代码内容应保留", text.toString().contains("val tailProbe = 80"))
                assertFalse("应检查渲染正文而非 Markdown 源码", text.toString().contains("```"))
                assertTrue("真实视图应包含代码块 span", text.getSpans(0, text.length, PiCodeBlockSpan::class.java).isNotEmpty())
                val layout = requireNotNull(view.layout)
                assertEquals("末字符应进入实际布局", text.length, layout.getLineEnd(layout.lineCount - 1))
                assertTrue("正文不应被省略", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                assertTrue("末行应在文档自身高度内", layout.getLineBottom(layout.lineCount - 1) <= view.height - view.totalPaddingTop - view.totalPaddingBottom)
            }
        }

        assertDocumentTail(paragraphs[39])
        compose.runOnIdle { markdown.value = document }
        assertDocumentTail(paragraphs.last())
        compose.runOnIdle { streaming.value = false }
        assertDocumentTail(paragraphs.last())

        // 终态路径后续更新走后台解析；等待真实视图收到新尾段，再检查其布局。
        val finalTail = "终态追加文末标记"
        compose.runOnIdle { markdown.value = "$document\n\n$finalTail" }
        compose.waitUntil(timeoutMillis = 5_000) {
            var updated = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                updated = renderedView().text.toString().contains(finalTail)
            }
            updated
        }
        assertDocumentTail(finalTail)
    }

    @Test
    fun inlineCodeUsesRoundedBackgroundAndStillWrapsAcrossLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val palette = testPalette().copy(
            inlineCodeCornerRadiusPx = 10f * density,
            inlineCodeHorizontalPaddingPx = 8f * density,
            inlineCodeVerticalPaddingPx = 2f * density
        )
        val markwon = buildPiMarkwon(context, palette)
        val rendered = markwon.toMarkdown("前缀 `inline-code` 后缀")
        val span = rendered.getSpans(
            0,
            rendered.length,
            PiInlineCodeSpan::class.java
        ).single()
        assertEquals(palette.inlineCodeCornerRadiusPx, span.cornerRadiusPx, 0f)

        val textView = PiMarkdownTextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            textSize = 20f
            text = rendered
        }
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        val bitmap = Bitmap.createBitmap(
            textView.measuredWidth,
            textView.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        textView.draw(Canvas(bitmap))

        val layout = requireNotNull(textView.layout)
        val spanStart = rendered.getSpanStart(span)
        val spanEnd = rendered.getSpanEnd(span)
        val codeLine = layout.getLineForOffset(spanStart)
        assertEquals(
            "短行内代码应完整落在同一行，避免跨行视口改变采样几何",
            codeLine,
            layout.getLineForOffset(spanEnd - 1)
        )
        val codePaint = TextPaint(textView.paint).also(span::updateDrawState)
        val baseline = layout.getLineBaseline(codeLine).toFloat()
        val top = (baseline + codePaint.fontMetrics.ascent - span.verticalPaddingPx)
            .coerceAtLeast(layout.getLineTop(codeLine).toFloat())
        val left = (layout.getPrimaryHorizontal(spanStart) - span.horizontalPaddingPx)
            .coerceAtLeast(0f)
        val right = (layout.getPrimaryHorizontal(spanEnd) + span.horizontalPaddingPx)
            .coerceAtMost(layout.width.toFloat())
        val cornerAlpha = Color.alpha(bitmap.getPixel(left.toInt(), top.toInt()))
        // 取圆角弧线内部且仍在字体墨迹上方，避免用抗锯齿边缘当背景判据。
        val interiorY = (top + (span.verticalPaddingPx - 1f).coerceAtLeast(1f))
            .toInt().coerceAtMost(layout.getLineBottom(codeLine) - 1)
        val topCenterAlpha = Color.alpha(
            bitmap.getPixel(((left + right) / 2f).toInt(), interiorY)
        )
        assertTrue("圆角外侧像素应保持透明", cornerAlpha < 128)
        assertTrue("圆角顶部内部应绘制背景", topCenterAlpha > 128)

        val longRendered = markwon.toMarkdown(
            "命令 `rm /tmp/pi-demo.txt` 结束"
        )
        val longSpan = longRendered.getSpans(
            0,
            longRendered.length,
            PiInlineCodeSpan::class.java
        ).single()
        textView.text = longRendered
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(dp(80), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        assertTrue(
            "长行内代码必须保持可换行",
            textView.layout.getLineForOffset(longRendered.getSpanStart(longSpan)) <
                textView.layout.getLineForOffset(longRendered.getSpanEnd(longSpan) - 1)
        )
    }

    private fun testPalette() = PiMarkdownPalette(
        textColor = 0xFF10131A.toInt(),
        linkColor = 0xFF1478F2.toInt(),
        tableBorderColor = 0x3310131A,
        tableHeaderColor = 0x1410131A,
        tableOddRowColor = 0x0910131A,
        taskOutlineColor = 0x7310131A,
        tableBorderWidthPx = 1,
        tableCellPaddingPx = 8,
        inlineCodeBackgroundColor = 0xFFCBD5E1.toInt(),
        inlineCodeCornerRadiusPx = 10f,
        inlineCodeHorizontalPaddingPx = 8f,
        inlineCodeVerticalPaddingPx = 2f
    )

    /** 带真实屏幕密度的紧凑排展 palette，验证列表/标题/代码块样式的实际渲染。 */
    private fun compactPalette(): PiMarkdownPalette {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        return testPalette().copy(
            headingBreakColor = 0x1A10131A,
            ruleColor = 0x1F10131A,
            quoteStripeColor = 0x3310131A,
            quoteStripeWidthPx = dp(2),
            listMarkerColor = 0x7310131A,
            listFirstLevelMarginPx = dp(16),
            listNestedLevelMarginPx = dp(12),
            listMarkerSizePx = dp(3),
            listMarkerStrokeWidthPx = dp(1),
            codeBlockBackgroundColor = 0x0D10131A,
            codeBlockBorderColor = 0x1A10131A,
            codeBlockMarginPx = dp(12),
            codeBlockBorderWidthPx = 1f * density,
            codeBlockCornerRadiusPx = 6f * density,
            codeTextSizePx = (12 * context.resources.displayMetrics.scaledDensity + 0.5f).toInt(),
            hairlineHeightPx = dp(1)
        )
    }

    @Test
    fun headingsUseCompactGfmSizesInsteadOfHtmlMultipliers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = buildPiMarkwon(context, compactPalette())
        val rendered = markwon.toMarkdown("# 一级\n\n## 二级\n\n### 三级\n\n#### 四级\n\n##### 五级")

        val headings = rendered.getSpans(0, rendered.length, HeadingSpan::class.java)
            .sortedBy { rendered.getSpanStart(it) }
        assertEquals(5, headings.size)

        val textView = PiMarkdownTextView(context)
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        val basePx = textView.paint.textSize
        val expectedSp = floatArrayOf(18f, 17f, 15f, 14f, 14f)
        headings.forEachIndexed { index, span ->
            val paint = TextPaint().apply { textSize = basePx }
            span.updateMeasureState(paint)
            assertEquals(
                "标题 h${index + 1} 应为 ${expectedSp[index]}sp",
                expectedSp[index] / 14f * basePx,
                paint.textSize,
                1.5f
            )
        }
    }

    @Test
    fun nestedListsUseCompactFirstAndNestedIndents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val markwon = buildPiMarkwon(context, compactPalette())
        val markdown = """- 一级项
  - 二级项
    - 三级项
""".trimIndent()
        val rendered = markwon.toMarkdown(markdown)

        val bullets = rendered.getSpans(0, rendered.length, PiBulletListItemSpan::class.java)
            .sortedBy { rendered.getSpanStart(it) }
        assertEquals(3, bullets.size)
        assertEquals("首层缩进应为 16dp", (16 * density + 0.5f).toInt(), bullets[0].getLeadingMargin(true))
        assertEquals("嵌套缩进应为 12dp", (12 * density + 0.5f).toInt(), bullets[1].getLeadingMargin(true))
        assertEquals((12 * density + 0.5f).toInt(), bullets[2].getLeadingMargin(true))

        // 真实布局：二级项行首文本位置 = 16dp + 12dp，三级项 = 16dp + 12dp + 12dp
        val textView = PiMarkdownTextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            markwon.setParsedMarkdown(this, rendered)
        }
        val width = (360 * density + 0.5f).toInt()
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        val layout = requireNotNull(textView.layout)

        fun lineStartX(mark: String): Float {
            val offset = rendered.indexOf(mark)
            assertTrue("应包含 $mark", offset >= 0)
            return layout.getPrimaryHorizontal(offset)
        }

        assertEquals("二级项行首应缩进 28dp", 28 * density, lineStartX("二级项"), 2f)
        assertEquals("三级项行首应缩进 40dp", 40 * density, lineStartX("三级项"), 2f)
    }

    @Test
    fun orderedMultiDigitNumbersKeepMeasuredMarginWithoutOverlap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = buildPiMarkwon(context, compactPalette())
        val markdown = (1..12).joinToString("\n") { "$it. 第${it}项" }
        val rendered = markwon.toMarkdown(markdown)

        val textView = PiMarkdownTextView(context).apply {
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            markwon.setParsedMarkdown(this, rendered)
        }
        val ordered = rendered.getSpans(0, rendered.length, PiOrderedListItemSpan::class.java)
            .sortedBy { rendered.getSpanStart(it) }
        assertEquals(12, ordered.size)
        assertEquals("1.\u00a0", ordered[0].number)
        assertEquals("10.\u00a0", ordered[9].number)

        val paint = textView.paint
        ordered.forEach { span ->
            assertTrue(
                "编号 ${span.number} 应完整落在缩进内，不与正文重叠",
                span.getLeadingMargin(true) >= paint.measureText(span.number)
            )
        }
        assertTrue(
            "两位数编号应比一位数占更多缩进",
            ordered[9].getLeadingMargin(true) > ordered[0].getLeadingMargin(true)
        )
    }

    @Test
    fun codeBlockDrawsRoundedBackgroundAndBorderAcrossWholeBlock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val palette = compactPalette()
        val markwon = buildPiMarkwon(context, palette)
        val rendered = markwon.toMarkdown("```kotlin\nfun a() {}\nfun b() {}\n```")
        val span = rendered.getSpans(0, rendered.length, PiCodeBlockSpan::class.java).single()

        val textView = PiMarkdownTextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            markwon.setParsedMarkdown(this, rendered)
        }
        val width = (360 * density + 0.5f).toInt()
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        val layout = requireNotNull(textView.layout)

        val bitmap = Bitmap.createBitmap(
            textView.measuredWidth,
            textView.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        textView.draw(Canvas(bitmap))

        val firstLine = layout.getLineForOffset(rendered.getSpanStart(span))
        val lastLine = layout.getLineForOffset((rendered.getSpanEnd(span) - 1).coerceAtLeast(rendered.getSpanStart(span)))
        val midY = (layout.getLineTop(firstLine) + layout.getLineBottom(lastLine)) / 2
        val radius = palette.codeBlockCornerRadiusPx

        // 块内背景：淡底已绘制
        val insideAlpha = Color.alpha(bitmap.getPixel(bitmap.width / 2, midY))
        assertTrue("代码块中部应有背景色", insideAlpha > 0)
        // 圆角：首行左上角外应保持透明（或至少弱于块内）
        val cornerAlpha = Color.alpha(bitmap.getPixel(2, layout.getLineTop(firstLine) + 2))
        assertTrue("圆角外应基本透明", cornerAlpha < insideAlpha)
        // 边框：左缘中部 alpha 应高于纯背景（背景+边框叠加）
        val borderAlpha = Color.alpha(bitmap.getPixel(1, midY))
        assertTrue("左缘应有边框叠加", borderAlpha > insideAlpha)

        // 代码文本应为等宽字体且字号 12sp
        val codePaint = TextPaint(textView.paint).also(span::updateDrawState)
        assertEquals(android.graphics.Typeface.MONOSPACE, codePaint.typeface)
        assertEquals(
            12 * context.resources.displayMetrics.scaledDensity,
            codePaint.textSize,
            2f
        )
    }

    @Test
    fun codeBlockBackgroundInsideBlockquoteStartsAtOuterIndent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val palette = compactPalette()
        val markwon = buildPiMarkwon(context, palette)
        val rendered = markwon.toMarkdown("> 引用中\n>\n> ```kotlin\n> fun a() {}\n> ```")
        val span = rendered.getSpans(0, rendered.length, PiCodeBlockSpan::class.java).single()

        val quoteSpans = rendered.getSpans(0, rendered.length, BlockQuoteSpan::class.java)
        assertTrue("引用块应生成 BlockQuoteSpan", quoteSpans.isNotEmpty())
        val quoteMargin = quoteSpans.maxOf { it.getLeadingMargin(true) }
        assertTrue("引用缩进应大于 0，否则断言退化", quoteMargin > 0)

        val textView = PiMarkdownTextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            markwon.setParsedMarkdown(this, rendered)
        }
        val width = (360 * density + 0.5f).toInt()
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        textView.layout(0, 0, textView.measuredWidth, textView.measuredHeight)
        val layout = requireNotNull(textView.layout)

        val bitmap = Bitmap.createBitmap(
            textView.measuredWidth,
            textView.measuredHeight,
            Bitmap.Config.ARGB_8888
        )
        textView.draw(Canvas(bitmap))

        // 段落盒（已含全部 LeadingMarginSpan）扣除代码自身 leading margin，
        // 背景左缘应落在外层真实缩进（引用缩进）处，而不是盖住整个缩进带。
        val firstLine = layout.getLineForOffset(rendered.getSpanStart(span))
        assertEquals(
            "背景左缘应等于外层引用缩进",
            quoteMargin.toFloat(),
            (layout.getParagraphLeft(firstLine) - palette.codeBlockMarginPx).toFloat(),
            1f
        )

        val midY = (layout.getLineTop(firstLine) + layout.getLineBottom(firstLine)) / 2
        val insideX = (quoteMargin + 4f * density).toInt().coerceAtMost(bitmap.width - 1)
        val outsideX = (quoteMargin - 4f * density).toInt().coerceIn(0, insideX - 1)
        // inside/outside 均避开引用竖条（宽度 = quoteStripeWidthPx < 4dp）与边框。
        val insideAlpha = Color.alpha(bitmap.getPixel(insideX, midY))
        val outsideAlpha = Color.alpha(bitmap.getPixel(outsideX, midY))
        assertTrue("缩进带外侧不应被代码块背景覆盖", outsideAlpha == 0)
        assertTrue("背景应从外层真实缩进处开始绘制", insideAlpha > 0)
    }
}
