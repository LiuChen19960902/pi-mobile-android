package com.finecat.pimobile.android.ui.finecat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.finecat.pimobile.android.ui.finecat.composer.*
import com.finecat.pimobile.shared.protocol.GatewayInputCompletion
import org.junit.Test
import org.junit.Assert.*

class InputAssistEditorTest {
    private fun value(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))

    @Test fun triggerFollowsCaretAndDoesNotTreatUrlsOrPathsAsSlashCommands() {
        assertNull(inputAssistToken(value("https://example.com/a")))
        assertNull(inputAssistToken(value("src/main.kt")))
        assertEquals("/au", inputAssistToken(value("参考 @docs/a /au 后面的文字", 14))?.text)
        assertEquals("@src/", inputAssistToken(value("请看@src/"))?.text)
        assertNull(inputAssistToken(value("/audit 参数")))
        assertNull(inputAssistToken(TextFieldValue("/audit", TextRange(0, 4))))
        assertNull(inputAssistToken(TextFieldValue("@文", TextRange(2), TextRange(1, 2))))
        assertNull(inputAssistToken(value("@\"my docs\n")))
        assertEquals("@src/", inputAssistToken(value("@\"my docs\n@src/"))?.text)
    }

    @Test fun acceptCommandPreservesSurroundingTextAndUsesRealSkillInvocation() {
        val original = value("请 /au 后续", 5)
        val token = inputAssistToken(original)!!
        val accepted = replaceInputAssistToken(original, token, "/skill:audit ")
        assertEquals("请 /skill:audit  后续", accepted.text)
        assertEquals(TextRange(15), accepted.selection)
        val movedCaret = original.copy(selection = TextRange(0))
        assertEquals(movedCaret, replaceInputAssistToken(movedCaret, token, "/skill:audit "))
    }

    @Test fun quotedPathsConsumeOnlyExistingClosingQuoteAndKeepDirectoryCaretInsideQuote() {
        val original = value("检查 @\"my docs/gu\" 之后", 15)
        val token = inputAssistToken(original)!!
        val file = GatewayInputCompletion("guide.md", "my docs/guide.md", "file", "@\"my docs/guide.md\" ", 20, true)
        val accepted = acceptInputCompletion(original, token, file)
        assertEquals("检查 @\"my docs/guide.md\"  之后", accepted.text)
        assertEquals(TextRange(23), accepted.selection)
        val dir = GatewayInputCompletion("my docs/", "my docs/", "directory", "@\"my docs/\"", 10)
        val next = acceptInputCompletion(value("@my"), inputAssistToken(value("@my"))!!, dir)
        assertEquals("@\"my docs/", inputAssistToken(next)?.text)
    }

    @Test fun dismissDropsOnlyActiveFragmentAndItsClosingQuote() {
        assertEquals("前面  之后", dropInputAssistToken(value("前面 /au 之后", 6)).text)
        assertEquals("前面  之后", dropInputAssistToken(value("前面 @\"my docs\" 之后", 12)).text)
        assertEquals("完成 @docs/a ", dropInputAssistToken(value("完成 @docs/a ")).text)
    }

    @Test fun highlightIsColorOnlyWithExactOffsetsAndRecognizesQuotedFiles() {
        val text = "https://host /audit @\"my docs/a\" /unregistered"
        val ranges = inputReferenceRanges(text, setOf("audit"))
        assertEquals(listOf("/audit", "@\"my docs/a\""), ranges.map { text.substring(it.start, it.end) })
    }

    @Test fun breadcrumbSeparatesFilterAndSupportsQuotedParents() {
        assertEquals(InputAssistPath("my docs/", "gu"), inputAssistPath("@\"my docs/gu"))
        assertEquals("@src/", parentInputAssistToken("@src/ui/co"))
        assertEquals("@\"my docs/\"", parentInputAssistToken("@\"my docs/ui/co"))
        assertEquals("@/", parentInputAssistToken("@/root/co"))
        assertEquals("@/", parentInputAssistToken("@/co"))
    }
}
