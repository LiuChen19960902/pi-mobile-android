package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.conversation.FcMdBlock
import com.finecat.pimobile.android.ui.finecat.conversation.splitMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3 路线 A 的块级拆分纯函数测试：围栏/表格/段落/流式未闭合。 */
class FcMarkdownSplitTest {

    @Test
    fun `fenced code with language and closing fence`() {
        val blocks = splitMarkdownBlocks("先看代码：\n```kotlin\nfun a() = 1\n```\n后续说明")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is FcMdBlock.Para)
        val code = blocks[1] as FcMdBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals("fun a() = 1", code.content)
        assertTrue(blocks[2] is FcMdBlock.Para)
    }

    @Test
    fun `unclosed fence keeps content as code block`() {
        val blocks = splitMarkdownBlocks("```python\nprint(1)")
        assertEquals(1, blocks.size)
        val code = blocks[0] as FcMdBlock.Code
        assertEquals("python", code.lang)
        assertEquals("print(1)", code.content)
    }

    @Test
    fun `gfm table with separator and body`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val blocks = splitMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val table = blocks[0] as FcMdBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("3", "4"), table.rows[1])
    }

    @Test
    fun `single pipe line without separator stays paragraph`() {
        // 流式中表头刚到、分隔行未到：不误判为表格，闭合后自然变表
        val blocks = splitMarkdownBlocks("| a | b |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is FcMdBlock.Para)
    }

    @Test
    fun `streaming append turns paragraph into table`() {
        val before = splitMarkdownBlocks("| a | b |")
        assertTrue(before[0] is FcMdBlock.Para)
        val after = splitMarkdownBlocks("| a | b |\n|---|---|\n| 1 | 2 |")
        assertTrue(after[0] is FcMdBlock.Table)
    }

    @Test
    fun `consecutive text lines group into one paragraph`() {
        val blocks = splitMarkdownBlocks("第一行\n第二行\n\n第三行")
        assertEquals(2, blocks.size)
        val p1 = blocks[0] as FcMdBlock.Para
        assertTrue(p1.markdown.contains("第一行") && p1.markdown.contains("第二行"))
    }

    @Test
    fun `mixed document block order is stable`() {
        val md = "文字\n\n| h |\n|---|\n| v |\n\n```\ncode\n```"
        val blocks = splitMarkdownBlocks(md)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is FcMdBlock.Para)
        assertTrue(blocks[1] is FcMdBlock.Table)
        assertTrue(blocks[2] is FcMdBlock.Code)
    }
}
