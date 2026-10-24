package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.ConversationProcessGroup
import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatSteps
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatWorkingGroupId
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatStepSummary
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatStepsSummaryPrefix
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import org.junit.Assert.*
import org.junit.Test

class FineCatProcessStepsTest {
    private fun item(id: String, kind: ConversationItemKind, text: String, time: Double = 1.0,
                     error: Boolean = false, toolId: String? = null) =
        ConversationItem(id, kind, "bash", text, isError = error, epochSeconds = time, toolCallId = toolId)

    @Test fun longCommandAndOutputRemainCompleteInDetail() {
        val command = "echo " + "参数".repeat(120)
        val output = "输出\n".repeat(150)
        val step = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("call", ConversationItemKind.TOOL, command, toolId = "t"),
            item("result", ConversationItemKind.TOOL_RESULT, output, 2.0, true, "t"),
        ))).single()
        assertTrue(step.arg.length < command.length)
        assertEquals(command, step.command)
        assertEquals(output, step.detail)
        assertTrue(step.failed)
        assertTrue(step.completed)
        assertEquals("1.0s", step.durationText)
        assertEquals("bash", step.toolName)
    }

    @Test fun notificationIsACompletedInspectableStepWithFullText() {
        val text = "完整通知\n".repeat(100)
        val notice = item("n", ConversationItemKind.NOTICE, text).copy(title = "通知")
        val steps = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("call", ConversationItemKind.TOOL, "pwd", toolId = "t"),
            notice,
            item("result", ConversationItemKind.TOOL_RESULT, "/tmp", toolId = "t"),
        )))
        assertEquals(listOf("call", "n"), steps.map { it.id })
        assertTrue(steps.all { it.completed })
        val step = steps.last()
        assertTrue(step.isNotice)
        assertNull(step.command)
        assertEquals(text, step.detail)
        assertEquals("通知", fineCatStepSummary(step).first)
        assertTrue(fineCatStepSummary(step).second.length <= 120)
        assertEquals("完成了 ", fineCatStepsSummaryPrefix(steps, false))
    }

    @Test fun lateResultUpdatesSameStepAndDistinguishesEmptyOutput() {
        val call = item("call", ConversationItemKind.TOOL, "pwd", toolId = "t")
        val pending = fineCatSteps(ConversationProcessGroup("g", listOf(call))).single()
        val done = fineCatSteps(ConversationProcessGroup("g", listOf(call,
            item("result", ConversationItemKind.TOOL_RESULT, "", 2.0, toolId = "t")))).single()
        assertEquals(pending.id, done.id)
        assertFalse(pending.completed)
        assertNull(pending.detail)
        assertTrue(done.completed)
        assertEquals("", done.detail)
        assertEquals("pwd", done.command)
    }

    @Test fun orphanResultDoesNotBecomeCommand() {
        val step = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("orphan", ConversationItemKind.TOOL_RESULT, "history result", toolId = "missing"),
        ))).single()
        assertNull(step.command)
        assertEquals("history result", step.detail)
        assertTrue(step.completed)
        assertNull(step.durationText)
        assertNull("结果标题不是工具名", step.toolName)
    }

    @Test fun reasoningFinalizationKeepsDetailIdentityAndFullText() {
        val streaming = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("stream-reason-1-1", ConversationItemKind.REASONING, "正在分析"),
        ))).single()
        val final = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("event-final-reason", ConversationItemKind.REASONING, "正在分析\n完整结论"),
        ))).single()
        assertEquals(streaming.id, final.id)
        assertEquals("深度思考", final.label)
        assertEquals("正在分析\n完整结论", final.detail)
        assertTrue(final.isThought)
        assertFalse(final.completed)
    }

    @Test fun contextAndMultipleThoughtsKeepOrderAndDistinctKeys() {
        val steps = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("ctx", ConversationItemKind.CONTEXT, "context ".repeat(100)),
            item("r1", ConversationItemKind.REASONING, "first"),
            item("call", ConversationItemKind.TOOL, "pwd"),
            item("r2", ConversationItemKind.REASONING, "second"),
        )))
        assertEquals(4, steps.size)
        assertEquals(4, steps.map { it.id }.toSet().size)
        assertTrue(steps[0].isContext)
        assertEquals("context ".repeat(100), steps[0].detail)
        assertEquals(listOf("first", "second"), steps.filter { it.isThought }.map { it.detail })
    }

    @Test fun unknownTimestampsDoNotInventDuration() {
        val step = fineCatSteps(ConversationProcessGroup("g", listOf(
            item("call", ConversationItemKind.TOOL, "pwd", 0.0, toolId = "t"),
            item("result", ConversationItemKind.TOOL_RESULT, "/tmp", 1000.0, toolId = "t"),
        ))).single()
        assertNull(step.durationText)
        assertFalse(step.failed)
    }

    @Test fun workingOnlyAppliesToCurrentProcess_notOlderGroupsOrFinalText() {
        val history = listOf(
            item("user-old", ConversationItemKind.USER, "old"),
            item("reason-old", ConversationItemKind.REASONING, "old thought"),
            item("answer-old", ConversationItemKind.ASSISTANT, "old answer"),
        )
        val currentUser = item("user-now", ConversationItemKind.USER, "new")
        val currentCall = item("call-now", ConversationItemKind.TOOL, "pwd")
        assertNull(fineCatWorkingGroupId(makeConversationDisplayEntries(history + currentUser), true))
        val running = makeConversationDisplayEntries(history + currentUser + currentCall)
        val groups = running.filterIsInstance<ConversationDisplayEntry.Process>()
        assertEquals(2, groups.size)
        assertEquals(groups.last().id, fineCatWorkingGroupId(running, true))
        assertNotEquals(groups.first().id, fineCatWorkingGroupId(running, true))
        assertNull("turn结束或终止后收口计数", fineCatWorkingGroupId(running, false))
        assertNull("正文开始后不把前面的步骤继续报成运行", fineCatWorkingGroupId(
            makeConversationDisplayEntries(history + currentUser + currentCall +
                item("answer-now", ConversationItemKind.ASSISTANT, "final text")), true))
    }
}
