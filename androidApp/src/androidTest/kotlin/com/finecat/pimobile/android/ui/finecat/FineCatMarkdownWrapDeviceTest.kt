package com.finecat.pimobile.android.ui.finecat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.ClipboardManager
import android.content.Context
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.ui.PiInlineCodeSpan
import com.finecat.pimobile.android.ui.PiMarkdownTextView
import com.finecat.pimobile.android.ui.finecat.conversation.FcMarkdownText
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** 合成正文走 FineCat 生产入口；仅编译不代表设备执行通过。 */
@RunWith(AndroidJUnit4::class)
class FineCatMarkdownWrapDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()
    private fun markdownViews(view: View): List<PiMarkdownTextView> = when (view) {
        is PiMarkdownTextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { markdownViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun paragraph(): PiMarkdownTextView = markdownViews(compose.activity.window.decorView).single()

    @Test fun paragraphSupportsNativePartialCopyWithoutMarkdownDelimiters() {
        compose.setContent { FineCatTheme { FcMarkdownText("前文 **可复制部分** 与 `models.json` 后文") } }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = paragraph()
            assertTrue("正文必须可长按选取", view.isTextSelectable && view.isLongClickable)
            val selected = "可复制部分 与 models.json"
            val start = view.text.toString().indexOf(selected)
            assertTrue(start >= 0)
            view.requestFocus()
            Selection.setSelection(view.text as Spannable, start, start + selected.length)
            assertTrue(view.onTextContextMenuItem(android.R.id.copy))
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(selected, clipboard.primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString())
            assertTrue("选取底色必须可见", Color.alpha(view.highlightColor) > 0)
        }
    }
    private fun assertAllCharactersLaidOut(view: PiMarkdownTextView, expected: String) {
        assertEquals(expected, view.text.toString().trimEnd('\n'))
        val layout = view.layout
        assertEquals(0, layout.getLineStart(0))
        assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 1 until layout.lineCount) {
            assertEquals(layout.getLineEnd(line - 1), layout.getLineStart(line))
        }
        assertTrue("最后一行不能被高度裁掉", view.height >= layout.getLineBottom(layout.lineCount - 1))
    }
    private fun assertWrappedCodeBackground(view: PiMarkdownTextView) {
        val text = view.text as Spanned
        val span = text.getSpans(0, text.length, PiInlineCodeSpan::class.java).single()
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        val layout = view.layout
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(end - 1)
        assertTrue("代码必须实际跨行", last > first)
        val codePaint = TextPaint(view.paint).also(span::updateDrawState)
        // 普通正文墨迹隐藏，不改变字宽；代码前景由span设置，不受此改动影响。
        view.setTextColor(Color.TRANSPARENT)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            var rightNegativeSamples = 0
            for (line in first..last) {
                val a = maxOf(start, layout.getLineStart(line))
                val b = minOf(end, layout.getLineEnd(line))
                assertTrue("合成URL每行至少含两个ASCII字符", b-a >= 2)
                // 从行内字符的插入点加字宽推导边缘，避开行尾offset及双向边界。
                val left = layout.getPrimaryHorizontal(a+1) - codePaint.measureText(text,a,a+1)
                val right = layout.getPrimaryHorizontal(b-1) + codePaint.measureText(text,b-1,b)
                val y = (layout.getLineBaseline(line) +
                    (codePaint.fontMetrics.ascent+codePaint.fontMetrics.descent)/2).toInt()
                for (x in listOf(left-span.horizontalPaddingPx/2, right+span.horizontalPaddingPx/2)) {
                    val pixel = x.toInt().coerceIn(0, view.width-1)
                    assertEquals("第${line+1}行两缘应保留底色", Color.alpha(span.backgroundColor),
                        Color.alpha(bitmap.getPixel(pixel,y)))
                }
                val outsideLeft = left-span.horizontalPaddingPx*2
                if (outsideLeft >= 0) assertEquals("底色不能染到前文或缩进带", 0,
                    Color.alpha(bitmap.getPixel(outsideLeft.toInt(),y)))
                val outsideRight = right+span.horizontalPaddingPx*2
                if (outsideRight < view.width) {
                    assertEquals("底色不能染到右侧普通文字", 0,
                        Color.alpha(bitmap.getPixel(outsideRight.toInt(),y)))
                    rightNegativeSamples++
                }
            }
            assertTrue("尾部普通文字必须提供可见的右侧负样本", rightNegativeSamples > 0)
        } finally { bitmap.recycle() }
    }
    @Test fun narrowInlineCodeWrapsWithoutLosingCharacters() {
        val code = "provider_" + "abcdefghij".repeat(18)
        val plain = "中文前文🙂 $code 中文后文完整可见"
        compose.setContent { FineCatTheme { Box(Modifier.width(180.dp)) {
            FcMarkdownText("中文前文🙂 `$code` 中文后文完整可见")
        } } }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = paragraph()
            assertAllCharactersLaidOut(view, plain)
            assertTrue(kotlin.math.abs(180 * view.resources.displayMetrics.density - view.width) <= 1)
            val text = view.text as Spanned
            val span = text.getSpans(0, text.length, PiInlineCodeSpan::class.java).single()
            assertTrue("长行内代码必须实际跨行", view.layout.getLineForOffset(text.getSpanEnd(span) - 1) >
                view.layout.getLineForOffset(text.getSpanStart(span)))
        }
    }
    @Test fun streamingAppendRelayoutsAndRetainsTheLastLine() {
        val markdown = mutableStateOf("开头 `models.json` 正文")
        compose.setContent { FineCatTheme { Box(Modifier.width(220.dp)) { FcMarkdownText(markdown.value) } } }
        compose.waitForIdle()
        var beforeLines = 0
        compose.runOnIdle { beforeLines = paragraph().lineCount }
        val tail = " 后续内容🙂".repeat(30)
        compose.runOnIdle { markdown.value += tail }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = paragraph()
            assertAllCharactersLaidOut(view, "开头 models.json 正文$tail")
            assertTrue(view.lineCount > beforeLines)
        }
    }
    @Test fun wrappedUrlKeepsCodeBackgroundOnProtocolAndDomain() {
        val prefix = "前文 "
        val url = "https://xxx.xx.xxx"
        compose.setContent { FineCatTheme { Box(Modifier.width(180.dp)) {
            FcMarkdownText("$prefix`$url` .")
        } } }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = paragraph()
            val text = view.text as Spanned
            val span = text.getSpans(0, text.length, PiInlineCodeSpan::class.java).single()
            val codePaint = TextPaint(view.paint).also(span::updateDrawState)
            // 让首行只容下协议前缀，确实复现下一行从域名开始的断点。
            val width = ceil(view.paint.measureText(prefix) + codePaint.measureText("https://") + 1).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertAllCharactersLaidOut(view, prefix + url + " .")
            val layout = view.layout
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val first = layout.getLineForOffset(start)
            val last = layout.getLineForOffset(end - 1)
            assertTrue("URL必须实际跨行", last > first)
            assertEquals("首行确实只显示协议", "https://", text.subSequence(start, layout.getLineEnd(first)).toString())
            assertWrappedCodeBackground(view)
        }
    }
    @Test fun quotedWrappedCodeRetainsBackgroundWithoutPaintingTheIndent() {
        compose.setContent { FineCatTheme { Box(Modifier.width(140.dp)) {
            FcMarkdownText("> 前文 `https://xxx.xx.xxx` 后")
        } } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertAllCharactersLaidOut(paragraph(), "前文 https://xxx.xx.xxx 后")
            assertWrappedCodeBackground(paragraph())
        }
    }
    @Test fun wrappedUrlInRtlParagraphKeepsBackgroundOnItsOwnCharacters() {
        compose.setContent { FineCatTheme { Box(Modifier.width(140.dp)) {
            FcMarkdownText("עברית `https://xxx.xx.xxx` אחר")
        } } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertAllCharactersLaidOut(paragraph(), "עברית https://xxx.xx.xxx אחר")
            assertEquals(android.text.Layout.DIR_RIGHT_TO_LEFT, paragraph().layout.getParagraphDirection(0))
            assertWrappedCodeBackground(paragraph())
        }
    }
}
