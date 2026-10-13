package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.android.ui.finecat.conversation.*
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import org.junit.Assert.*
import org.junit.Test

class FineCatStepSummaryTest {
    private fun tool(name: String?, command: String?, completed: Boolean = true, failed: Boolean = false) =
        FcStep("t", label = "执行命令", toolName = name, command = command,
            completed = completed, failed = failed)

    @Test fun fileToolsUseRealPathAndKeepFullArguments() {
        val original = """{"path":"src/中文.kt","content":"long content","offset":4}"""
        listOf("read" to "读取了", "write" to "写入了", "edit" to "修改了", "ls" to "列出了").forEach { (name, action) ->
            val step = tool(name, original)
            assertEquals(action to "src/中文.kt", fineCatStepSummary(step))
            assertEquals(original, step.command)
        }
    }

    @Test fun searchUsesPatternAndDirectoryWithoutDiscardingDetail() {
        val step = tool("grep", """{"pattern":"skill","path":"src/"}""")
            .copy(detail = "full match output")
        assertEquals("搜索了" to "skill · src/", fineCatStepSummary(step))
        assertEquals("full match output", step.detail)
        assertEquals("查找了" to "*.kt", fineCatStepSummary(tool("find", """{"pattern":"*.kt"}""")))
    }

    @Test fun shellSupportsStructuredAndLegacyPlainCommand() {
        assertEquals("执行了" to "git status", fineCatStepSummary(tool("bash", """{"command":"git status","timeout":10}""")))
        assertEquals("执行了" to "echo one echo two", fineCatStepSummary(tool("bash", "echo one\necho two")))
    }

    @Test fun failedAndPendingToolsNeverSayCompleted() {
        assertEquals("执行失败" to "npm view", fineCatStepSummary(tool("bash", "npm view", failed = true)))
        assertEquals("正在读取" to "file.txt", fineCatStepSummary(tool("read", """{"path":"file.txt"}""", completed = false)))
        assertEquals("正在执行 ", fineCatStepsSummaryPrefix(listOf(tool("bash", "pwd")), true))
        assertEquals("", fineCatStepsSummaryPrefix(listOf(tool("bash", "pwd", completed = false)), false))
        assertEquals("完成了 ", fineCatStepsSummaryPrefix(listOf(tool("bash", "pwd", failed = true)), false))
    }

    @Test fun partialMalformedAndMissingArgumentsDoNotInventTargetsOrCrash() {
        for (command in listOf(null, "", "{", "{\"path\":", "{broken}")) {
            assertEquals("读取了" to "", fineCatStepSummary(tool("read", command)))
        }
        assertEquals("执行了" to "", fineCatStepSummary(tool("bash", "{\"command\":")))
        assertEquals("读取了" to "", fineCatStepSummary(tool("read", """{"path":{"not":"a string"}}""")))
    }

    @Test fun unknownToolAndOrphanResultRetainHonestFallback() {
        assertEquals("使用了" to "custom_search · dir", fineCatStepSummary(tool("custom_search", """{"path":"dir"}""")))
        assertEquals("使用了" to "", fineCatStepSummary(tool(null, null).copy(detail = "orphan result")))
    }

    @Test fun thinkingAndContextRemainVisibleAndComplete() {
        val thought = FcStep("reason", label = "深度思考", isThought = true, detail = "a\nb")
        assertEquals("思考" to "a b", fineCatStepSummary(thought))
        assertEquals("a\nb", thought.detail)
        val context = FcStep("ctx", label = "上下文", isContext = true, arg = "compacted\nsummary")
        assertEquals("上下文" to "compacted summary", fineCatStepSummary(context))
        assertEquals("完成了 ", fineCatStepsSummaryPrefix(listOf(thought, context), false))
    }

    @Test fun longSummaryIsBoundedWhileFullCommandAndOutputRemainAvailable() {
        val command = "echo " + "参数".repeat(300)
        val output = "result\n".repeat(300)
        val step = tool("bash", command).copy(detail = output)
        assertTrue(fineCatStepSummary(step).second.length <= 160)
        assertEquals(command, step.command)
        assertEquals(output, step.detail)
    }

    @Test fun mergedTrailingBlankAssistantStillIdentifiesLiveGroup_andErrorsStopIt() {
        val user = ConversationItem("u", ConversationItemKind.USER, "", "request", epochSeconds = 0.0)
        val call = ConversationItem("c", ConversationItemKind.TOOL, "read", "", epochSeconds = 0.0)
        val blank = ConversationItem("a", ConversationItemKind.ASSISTANT, "", " \n", epochSeconds = 0.0)
        val entries = mergeFineCatProcessEntries(makeConversationDisplayEntries(listOf(user, call, blank)))
        assertNotNull(fineCatWorkingGroupId(entries, true))
        assertNull(fineCatWorkingGroupId(entries, false))
        val error = mergeFineCatProcessEntries(makeConversationDisplayEntries(listOf(user, call, blank.copy(isError = true))))
        assertNull(fineCatWorkingGroupId(error, true))
        val visible = mergeFineCatProcessEntries(makeConversationDisplayEntries(listOf(user, call, blank.copy(text = "visible"))))
        assertNull(fineCatWorkingGroupId(visible, true))
    }
}
