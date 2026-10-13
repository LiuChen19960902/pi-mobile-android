package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayTreeNode
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 树行显示层纯函数（TreeUi）：Pi 前缀透传、活动标记、深单链与截断父节点、
 * 行内 md span（code/bold、重叠、未闭合字面）。
 */
class TreeUiTest {

    private fun node(
        id: String,
        parentId: String? = null,
        role: String = "assistant",
        preview: String = "正文 $id",
    ): GatewayTreeNode = GatewayTreeNode(
        id = id,
        parentId = parentId,
        type = "message",
        role = role,
        preview = preview,
    )

    @Test
    fun singleChainDoesNotAccumulateParentIndentation() {
        val chain = (1..400).map { i ->
            node("d$i", parentId = if (i == 1) null else "d${i - 1}")
        }
        assertTrue(chain.all { treeRowPrefix(it) == "" })
    }

    @Test
    fun branchPrefixesAndActivePathArePreservedEvenWhenParentIsOutsideWindow() {
        val prefixes = listOf("├─ ", "│  ├─ ", "│  │  ", "│  └─ ", "└─ ", "   ")
        prefixes.forEach { prefix ->
            val row = node("entry", "outside-window").copy(prefix = prefix)
            assertEquals(prefix, treeRowPrefix(row))
            assertEquals(prefix + "• ", treeRowPrefix(row.copy(onActivePath = true)))
        }
    }

    @Test
    fun inlineSpansMatchPrototypeMd_codeBoldOverlapAndLiteral() {
        assertEquals(emptyList<TreeInlineSpan>(), treeInlineSpans("没有任何标记的正文"))
        val both = treeInlineSpans("前缀 `code` 与 **粗体** 尾")
        assertEquals(
            listOf(
                TreeInlineSpan(3, 9, code = true, bold = false),
                TreeInlineSpan(12, 18, code = false, bold = true),
            ),
            both,
        )
        // bold 包住 code：两段范围重叠，样式叠加（原型先 code 后 bold 的结果）
        val nested = treeInlineSpans("**`x`**")
        assertEquals(2, nested.size)
        assertTrue(nested.any { it.code && it.start == 2 && it.endExclusive == 5 })
        assertTrue(nested.any { it.bold && it.start == 0 && it.endExclusive == 7 })
        // 未闭合标记保持字面，不产生 span
        assertTrue(treeInlineSpans("未闭合 `code 与 **bold").isEmpty())
    }

    @Test
    fun inlineSpanInnerRangeStripsDelimiters_likePrototypeMd() {
        // 原型 md() 替换为 <code>$1</code>/<strong>$1</strong>：定界符不进文本内容。
        // code 定界 1 字符、bold 2 字符——渲染层按 inner 范围 append（审查 m8）。
        assertEquals(1, treeInlineSpans("`x`").single().innerStart)
        assertEquals(2, treeInlineSpans("`x`").single().innerEndExclusive)
        assertEquals(2, treeInlineSpans("**x**").single().innerStart)
        assertEquals(3, treeInlineSpans("**x**").single().innerEndExclusive)
        val nested = treeInlineSpans("**`x`**")
        val code = nested.first { it.code }
        val bold = nested.first { it.bold }
        assertEquals(3, code.innerStart)
        assertEquals(4, code.innerEndExclusive)
        assertEquals(2, bold.innerStart)
        assertEquals(5, bold.innerEndExclusive)
    }

    @Test
    fun inlineSegmentsStripDelimiters_andConcatToCleanText() {
        assertEquals(
            listOf(TreeInlineSegment("没有任何标记的正文", code = false, bold = false)),
            treeInlineSegments("没有任何标记的正文"),
        )
        val segs = treeInlineSegments("前缀 `code` 与 **粗体** 尾")
        assertEquals("前缀 code 与 粗体 尾", segs.joinToString("") { it.text })
        assertEquals(
            listOf(
                TreeInlineSegment("前缀 ", code = false, bold = false),
                TreeInlineSegment("code", code = true, bold = false),
                TreeInlineSegment(" 与 ", code = false, bold = false),
                TreeInlineSegment("粗体", code = false, bold = true),
                TreeInlineSegment(" 尾", code = false, bold = false),
            ),
            segs,
        )
        // bold 包 code：内容单段双标记，定界符全剥（渲染 <strong><code>x</code></strong>）
        assertEquals(listOf(TreeInlineSegment("x", code = true, bold = true)),
            treeInlineSegments("**`x`**"))
    }

    @Test
    fun treeClockLabelFormatsLocalTimeAndNeverEchoesGarbage() {
        // 真实网关形状（ISO/UTC）：本机时区 HH:mm，与对话页 formatClockLabel 同口径
        val ts = "2026-09-30T09:01:00.000Z"
        val expected = com.finecat.pimobile.android.ui.finecat.conversation.formatClockLabel(
            Instant.parse(ts).epochSecond.toDouble(),
        )
        assertEquals(expected, treeClockLabel(ts))
        assertTrue(Regex("^\\d{2}:\\d{2}$").containsMatchIn(treeClockLabel(ts)))
        // 非 ISO 串（旧网关/缺 timestamp）：空串不画，绝不退回原串（B1：截取会得 ":00.0"）
        assertEquals("", treeClockLabel("09:01"))
        assertEquals("", treeClockLabel("2026-09-30 09:01:00"))
        assertEquals("", treeClockLabel(""))
    }
}
