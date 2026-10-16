package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.GatewayTreeNode
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun displayConnectorsMatchPrototypeForTwoFlatTurns() {
        val rows = listOf(
            node("u1", role = "user"), node("t1", "u1", role = "toolResult"), node("a1", "t1"),
            node("u2", "a1", role = "user"), node("t2", "u2", role = "toolResult"), node("a2", "t2")
        )
        assertEquals(listOf("", "│  ├─ ", "│  └─ ", "", "   ├─ ", "   └─ "), treeDisplayPrefixes(rows))
        assertTrue("视觉线不改写网关节点", rows.all { it.prefix.isEmpty() })
    }

    @Test
    fun displayConnectorsPreserveActualBranchesAndActivePathMarkers() {
        val rows = listOf(
            node("u", role = "user"),
            node("a", "u").copy(prefix = "├─ ", onActivePath = true),
            node("b", "a").copy(prefix = "│  "),
            node("c", "u").copy(prefix = "└⊞ "),
        )
        assertEquals(listOf("", "├─ • ", "│  └─ ", "└⊞ "), treeDisplayPrefixes(rows))
        val flatActive = listOf(node("u", role = "user"), node("a", "u").copy(onActivePath = true))
        assertEquals(listOf("", "   └─ • "), treeDisplayPrefixes(flatActive))
    }

    @Test
    fun displayConnectorsContinueInsideARealBranchWithoutAccumulatingIndent() {
        val rows = listOf(node("u", role = "user").copy(prefix = "├─ ")) + (1..200).map { i ->
            node("n$i", if (i == 1) "u" else "n${i - 1}").copy(prefix = "│  ")
        }
        val prefixes = treeDisplayPrefixes(rows)
        assertEquals("├─ ", prefixes.first())
        assertEquals("│  ├─ ", prefixes[1])
        assertTrue(prefixes.drop(1).all { it.length == 6 })
        assertEquals("│  └─ ", prefixes.last())
    }

    @Test
    fun displayConnectorsDoNotGuessMissingOrFilteredUserAncestors() {
        assertEquals(listOf("", ""), treeDisplayPrefixes(listOf(node("a", "outside"), node("b", "a"))))
        val broken = listOf(node("u", role = "user"), node("a", "outside"), node("b", "a"))
        assertEquals(listOf("", "", ""), treeDisplayPrefixes(broken))
        val userOnly = listOf(node("u1", role = "user"), node("u2", "u1", role = "user"))
        assertEquals(listOf("", ""), treeDisplayPrefixes(userOnly))
    }

    @Test
    fun displayConnectorsKeepLongSingleChainAtOneVisualLevel() {
        val rows = (1..400).map { i ->
            node("n$i", if (i == 1) null else "n${i - 1}", role = if (i == 1) "user" else "assistant")
        }
        val prefixes = treeDisplayPrefixes(rows)
        assertEquals("", prefixes.first())
        assertTrue(prefixes.drop(1).all { it.length == 6 })
        assertEquals("   └─ ", prefixes.last())
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

    // -- TREE05：原型行层次（目录弱化/尾部保留、角色标记、搜索强调）------------

    @Test
    fun pathPartsDimOnlyTheLastPathLikeTokenAndNeverChangeTheText() {
        // 工具行：目录弱化、尾部（文件名:行号）保留。
        val read = treeRowPathParts("[read: ~/proj/docs/guide.md:2-4]")
        assertEquals("[read: ", read.before)
        assertEquals("~/proj/docs/", read.dimDirectory)
        assertEquals("guide.md:2-4]", read.tail)
        // 命令行：只弱化路径那一段，`cd ` 与 ` && ls -la` 保持正常色
        // （原型直接取全文最后一个 `/`，会把 `cd ` 也弱化、尾部只剩一个词）。
        val bash = treeRowPathParts("[bash: cd ~/proj/src && ls -la]")
        assertEquals("[bash: cd ", bash.before)
        assertEquals("~/proj/", bash.dimDirectory)
        assertEquals("src && ls -la]", bash.tail)
        // grep：模式不被弱化，只弱化最后一个路径 token 的目录部分。
        val grep = treeRowPathParts("[grep: /pat/ in ~/proj/lib]")
        assertEquals("[grep: /pat/ in ", grep.before)
        assertEquals("~/proj/", grep.dimDirectory)
        assertEquals("lib]", grep.tail)
        // 没有路径：原样返回，不造弱化段。
        val plain = treeRowPathParts("[compaction: 42k tokens]")
        assertEquals("", plain.dimDirectory)
        assertEquals("[compaction: 42k tokens]", plain.joined)
        assertFalse(plain.hasDimDirectory)
        // 拼接恒等原文（包含空串、纯路径、尾部斜线、中文路径）。
        for (text in listOf("", "/", "~/a/", "~/目录/文件.md", "no slash at all", "a/b c/d")) {
            assertEquals(text, treeRowPathParts(text).joined)
        }
        assertEquals("", treeRowPathParts("/README.md").before)
        assertEquals("/", treeRowPathParts("/README.md").dimDirectory)
        assertEquals("README.md", treeRowPathParts("/README.md").tail)
    }

    @Test
    fun roleLabelOnlySplitsWhatTheGatewayAlreadyPrefixed() {
        // 网关 displayText 已含角色前缀：客户端只拆不染色不追加，避免双重前缀。
        assertEquals("user: ", treeRowRoleLabel("user: 阅读项目", "user"))
        assertEquals("assistant: ", treeRowRoleLabel("assistant: 已读完", "assistant"))
        // 工具/元数据行、旧网关缺前缀、其他角色：不拆，整行当正文。
        assertEquals(null, treeRowRoleLabel("[bash: ls]", "toolResult"))
        assertEquals(null, treeRowRoleLabel("阅读项目", "user"))
        assertEquals(null, treeRowRoleLabel("user: x", null))
        assertEquals(null, treeRowRoleLabel("[compaction: 1k tokens]", null))
    }

    @Test
    fun treeRowTextPrefersGatewayDisplayTextAndFallsBackToRolePrefixedPreview() {
        val display = node("a").copy(displayText = "assistant: **literal**", preview = "截断预览")
        assertEquals("assistant: **literal**", treeRowText(display))
        // 旧网关（无 displayText）：沿用改动前的回落口径，不丢角色信息。
        assertEquals("user: 正文 a", treeRowText(node("a", role = "user")))
        assertEquals("assistant: 正文 a", treeRowText(node("a", role = "assistant")))
        assertEquals("正文 a", treeRowText(node("a", role = "toolResult")))
    }

    @Test
    fun searchEmphasisUsesGatewayTokenAndOverVisibleTextOnly() {
        val labeled = node("a", role = "user", preview = "ignored").copy(
            displayText = "user: 阅读项目 readme",
            label = "交付",
        )
        // 空白分词 AND、不区分大小写（与网关同口径）。
        assertTrue(treeRowMatchesSearch(labeled, "README 阅读"))
        assertTrue(treeRowMatchesSearch(labeled, "交付"))
        assertFalse(treeRowMatchesSearch(labeled, "readme 不存在词"))
        // 空查询不强调（否则整屏都是高亮）。
        assertFalse(treeRowMatchesSearch(labeled, "   "))
        // 只看行上可见文本：preview 里有、displayText 里没有的词不算命中
        // （网关可能因 200 字窗口或角色词把它筛进来，客户端不反过来伪造强调）。
        val windowed = node("b", role = "assistant", preview = "preview-only").copy(displayText = "assistant: 正文")
        assertFalse(treeRowMatchesSearch(windowed, "preview-only"))
        assertTrue(treeRowMatchesSearch(windowed, "正文"))
        // 旧网关回落：没有 displayText 时用 preview 判定。
        assertTrue(treeRowMatchesSearch(node("c", role = "user", preview = "旧网关正文"), "旧网关"))
    }
}
