package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDisplayGroupsTest {
    @Test
    fun `provisional stream ids are detected independently from rendering`() {
        assertTrue(isStreamingConversationItem(item("stream-text-1-1", ConversationItemKind.ASSISTANT)))
        assertTrue(isStreamingConversationItem(item("stream-reason-1-1", ConversationItemKind.REASONING)))
        assertFalse(isStreamingConversationItem(item("session-a-42", ConversationItemKind.ASSISTANT)))
    }

    @Test
    fun `streaming assistant is markdown virtualized without a final copy footer`() {
        val text = List(5) { index ->
            "第${index + 1}段：${"流式 Markdown".repeat(40)}"
        }.joinToString("\n\n")

        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(
                listOf(item("stream-text-7-9", ConversationItemKind.ASSISTANT, text = text))
            )
        )

        assertTrue(timeline.first() is ConversationTimelineEntry.AssistantHeader)
        assertTrue(timeline.any { it is ConversationTimelineEntry.AssistantMarkdown })
        // 复制入口并入消息头：流式期间也没有尾部复制行。
        assertTrue(timeline.last() is ConversationTimelineEntry.AssistantMarkdown)
        assertEquals(
            text,
            timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
                .joinToString("\n\n", transform = { it.markdown })
        )
    }

    @Test
    fun noticesJoinStepsButErrorsAndMessagesRemainBoundaries() {
        val entries = makeConversationDisplayEntries(listOf(
            item("user", ConversationItemKind.USER),
            item("call", ConversationItemKind.TOOL, toolCallId = "t"),
            item("notice", ConversationItemKind.NOTICE, text = "background result"),
            item("result", ConversationItemKind.TOOL_RESULT, toolCallId = "t"),
            item("error", ConversationItemKind.SYSTEM).copy(isError = true),
            item("second-notice", ConversationItemKind.NOTICE),
            item("answer", ConversationItemKind.ASSISTANT),
        ))
        assertEquals(5, entries.size)
        val group = (entries[1] as ConversationDisplayEntry.Process).group
        assertEquals(listOf("notice"), group.notices.map { it.id })
        assertEquals(listOf("call", "notice"), group.orderedSegments().map { it.id })
        val tool = (group.orderedSegments().first() as ConversationProcessSegment.ToolRun).tools.single()
        assertEquals("result", tool.result?.id)
        assertTrue((entries[2] as ConversationDisplayEntry.Message).item.isError)
    }

    @Test
    fun resultAcrossNoticePrefersExactIdOverAnEarlierUnknownCall() {
        val group = ConversationProcessGroup("g", listOf(
            item("unknown", ConversationItemKind.TOOL),
            item("notice1", ConversationItemKind.NOTICE),
            item("known", ConversationItemKind.TOOL, toolCallId = "known"),
            item("notice2", ConversationItemKind.NOTICE),
            item("result", ConversationItemKind.TOOL_RESULT, toolCallId = "known"),
        ))
        val tools = group.orderedSegments().filterIsInstance<ConversationProcessSegment.ToolRun>().flatMap { it.tools }
        assertNull(tools.first().result)
        assertEquals("result", tools.last().result?.id)
    }

    @Test
    fun lateNotificationIsNotMovedAheadOfTheDispatchReply() {
        val rounds = groupAssistantRounds(makeConversationDisplayEntries(listOf(
            item("dispatch", ConversationItemKind.ASSISTANT, text = "已派发"),
            item("notice", ConversationItemKind.NOTICE, text = "后台任务完成"),
            item("answer", ConversationItemKind.ASSISTANT, text = "收到结果"),
        )))
        assertEquals(2, rounds.size)
        assertEquals("dispatch", rounds.first().single().id)
        assertEquals(listOf("process:dispatch:0", "answer"), rounds.last().map { it.id })
    }

    @Test
    fun consecutiveProcessEventsBecomeOneNestedGroup() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("context", ConversationItemKind.CONTEXT, seconds = 2.0),
                item("reason", ConversationItemKind.REASONING, text = "先分析", seconds = 3.0),
                item("call", ConversationItemKind.TOOL, title = "ask_user_question", seconds = 4.0),
                item("result", ConversationItemKind.TOOL_RESULT, seconds = 7.0),
                item("assistant", ConversationItemKind.ASSISTANT, seconds = 8.0)
            )
        )

        // 思考与工具同组（授权后的紧凑方案）：context+reasoning+tools 一个外层折叠组。
        assertEquals(3, entries.size)
        assertTrue(entries[0] is ConversationDisplayEntry.Message)
        val process = (entries[1] as ConversationDisplayEntry.Process).group
        assertEquals("耗时 5 秒 · 1 项上下文 · 1 段思考 · 1 次工具调用", process.title)
        assertEquals(listOf("reason"), process.reasonings.map(ConversationItem::id))
        assertEquals("call", process.tools.single().call?.id)
        assertEquals("result", process.tools.single().result?.id)
        assertTrue(entries[2] is ConversationDisplayEntry.Message)
    }

    @Test
    fun processGroupIdStaysPutAcrossStreamFinalize() {
        // 流式期间：组内只有 stream 成员，id 形如 `stream-reason-<turn>-<step>`。
        val streamingGroup = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("stream-reason-1-1", ConversationItemKind.REASONING, text = "x")
            )
        ).last() as ConversationDisplayEntry.Process

        // finalize：投影先摘流式行再 append 最终事件行，组内成员全换血，
        // 且与 stream id 无共享子串（旧实现锚首成员，此处必换键）。
        val finalizedGroup = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("session-a-9-reason", ConversationItemKind.REASONING, text = "x", seconds = 3.0),
                item("session-a-10", ConversationItemKind.TOOL, title = "bash", seconds = 4.0),
                item("session-a-11", ConversationItemKind.TOOL_RESULT, seconds = 5.0)
            )
        ).last() as ConversationDisplayEntry.Process

        // 组 id 锚定前驱消息+序数，与成员 id 无关：手动收起跨 finalize 不弹开。
        assertEquals("process:user:0", streamingGroup.id)
        assertEquals(streamingGroup.id, finalizedGroup.id)
    }

    @Test
    fun siblingProcessGroupsUnderOneMessageGetDistinctKeys() {
        // 邻组边界：同一前驱消息下的多个组按序数区分，互不共享收起状态。
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("reason-a", ConversationItemKind.REASONING),
                item("assistant-a", ConversationItemKind.ASSISTANT, seconds = 2.0),
                item("reason-b", ConversationItemKind.REASONING),
                item("assistant-b", ConversationItemKind.ASSISTANT, seconds = 3.0)
            )
        )
        assertEquals(
            listOf("user", "process:user:0", "assistant-a", "process:assistant-a:0", "assistant-b"),
            entries.map(ConversationDisplayEntry::id)
        )
    }

    @Test
    fun historyPrependingChangesOldestGroupKeyWhenMessageArrivesAhead() {
        // 已知限制：会话最老的过程组前驱为空（`process::0`），加载更早历史且更早
        // 历史以消息收尾时，该组前驱变为新消息，组键随之更新（旧收起选择不迁移）。
        // 锚成员的旧实现在此同样换键，两组代价相同；换键仅影响最老组，换 finalize
        // 稳定（高频）值得。
        val before = makeConversationDisplayEntries(
            listOf(
                item("reason-a", ConversationItemKind.REASONING),
                item("assistant", ConversationItemKind.ASSISTANT, seconds = 2.0)
            )
        )
        val afterPrepend = makeConversationDisplayEntries(
            listOf(
                item("m0", ConversationItemKind.USER, seconds = 1.0),
                item("reason-a", ConversationItemKind.REASONING),
                item("assistant", ConversationItemKind.ASSISTANT, seconds = 2.0)
            )
        )
        assertEquals("process::0", (before.first() as ConversationDisplayEntry.Process).id)
        assertEquals(
            "process:m0:0",
            (afterPrepend.first { it is ConversationDisplayEntry.Process } as ConversationDisplayEntry.Process).id
        )
    }

    @Test
    fun orderedSegmentsKeepInterleavedThinkToolTimeline() {
        // think→tool→think→tool 交错（纯工具轮 assistant/message 无正文不 flush）:
        // 按原事件顺序逐段渲染，不得按类别重排（先所有思考再所有工具）。
        val group = (makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("r1", ConversationItemKind.REASONING, seconds = 2.0),
                item("t1", ConversationItemKind.TOOL, title = "bash", toolCallId = "c1", seconds = 3.0),
                item("res1", ConversationItemKind.TOOL_RESULT, toolCallId = "c1", seconds = 4.0),
                item("r2", ConversationItemKind.REASONING, seconds = 5.0),
                item("t2", ConversationItemKind.TOOL, title = "read", toolCallId = "c2", seconds = 6.0),
                item("res2", ConversationItemKind.TOOL_RESULT, toolCallId = "c2", seconds = 7.0)
            )
        )[1] as ConversationDisplayEntry.Process).group

        val segments = group.orderedSegments()
        assertEquals(listOf("r1", "t1", "r2", "t2"), segments.map(ConversationProcessSegment::id))
        // 段内仍按 callId 精确配对。
        val firstRun = segments[1] as ConversationProcessSegment.ToolRun
        val secondRun = segments[3] as ConversationProcessSegment.ToolRun
        assertEquals("res1", firstRun.tools.single().result?.id)
        assertEquals("res2", secondRun.tools.single().result?.id)
    }

    @Test
    fun orderedSegmentsPairParallelResultsInsideOneRun() {
        // 连续工具 run 内：并行乱序完成的 result 按 toolCallId 精确挂接。
        val group = (makeConversationDisplayEntries(
            listOf(
                item("a-call", ConversationItemKind.TOOL, title = "bash", toolCallId = "c-a", seconds = 1.0),
                item("b-call", ConversationItemKind.TOOL, title = "read", toolCallId = "c-b", seconds = 2.0),
                item("b-result", ConversationItemKind.TOOL_RESULT, toolCallId = "c-b", seconds = 3.0),
                item("a-result", ConversationItemKind.TOOL_RESULT, toolCallId = "c-a", seconds = 9.0)
            )
        ).single() as ConversationDisplayEntry.Process).group

        val run = group.orderedSegments().single() as ConversationProcessSegment.ToolRun
        assertEquals("a-result", run.tools.first { it.call?.id == "a-call" }.result?.id)
        assertEquals("b-result", run.tools.first { it.call?.id == "b-call" }.result?.id)
    }

    @Test
    fun orderedSegmentsIsolateOrphanResultAfterFilledRun() {
        // 孤儿 result（无未配对 call）：不前挂已完成的工具，单段保底可查。
        val group = (makeConversationDisplayEntries(
            listOf(
                item("t1", ConversationItemKind.TOOL, title = "bash", toolCallId = "c1", seconds = 1.0),
                item("res1", ConversationItemKind.TOOL_RESULT, toolCallId = "c1", seconds = 2.0),
                item("ghost", ConversationItemKind.TOOL_RESULT, seconds = 3.0)
            )
        ).single() as ConversationDisplayEntry.Process).group

        val segments = group.orderedSegments()
        assertEquals(listOf("t1", "ghost"), segments.map(ConversationProcessSegment::id))
        val ghost = (segments[1] as ConversationProcessSegment.ToolRun).tools.single()
        assertNull(ghost.call)
        assertEquals("ghost", ghost.result?.id)
    }

    @Test
    fun `unknown timestamps are excluded from the duration`() {
        // 回归：历史项缺 timestamp 时为 0，与实时事件（epoch 约 1.79e9）混进同一组后，
        // 旧的 first/last 差值会渲染成"耗时 29821798 分钟 3 秒"。
        val now = 1_789_307_880.0
        val entries = makeConversationDisplayEntries(
            listOf(
                item("context", ConversationItemKind.CONTEXT, seconds = 0.0),
                item("call", ConversationItemKind.TOOL, title = "bash", seconds = now),
                item("result", ConversationItemKind.TOOL_RESULT, seconds = now + 4)
            )
        )
        val group = (entries.first { it is ConversationDisplayEntry.Process }
            as ConversationDisplayEntry.Process).group
        assertEquals(4.0, group.durationSeconds, 0.001)
        assertEquals("耗时 4 秒 · 1 项上下文 · 1 次工具调用", group.title)
    }

    @Test
    fun `a single known timestamp yields no duration at all`() {
        val now = 1_789_307_880.0
        val entries = makeConversationDisplayEntries(
            listOf(
                item("context", ConversationItemKind.CONTEXT, seconds = 0.0),
                item("reason", ConversationItemKind.REASONING, text = "先分析", seconds = 0.0),
                item("call", ConversationItemKind.TOOL, title = "bash", seconds = 0.0),
                item("result", ConversationItemKind.TOOL_RESULT, seconds = now)
            )
        )
        val group = (entries.first { it is ConversationDisplayEntry.Process }
            as ConversationDisplayEntry.Process).group
        assertEquals(0.0, group.durationSeconds, 0.001)
        // 思考并入组后：context+reasoning+tool 全在一组，标题含全部计数。
        assertEquals("思考过程 · 1 项上下文 · 1 段思考 · 1 次工具调用", group.title)
    }

    @Test
    fun conversationalAndStatusRowsSplitProcessGroups() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("context-a", ConversationItemKind.CONTEXT),
                item("status", ConversationItemKind.STATUS),
                item("reason-b", ConversationItemKind.REASONING),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf("process::0", "status", "process:status:0", "system"),
            entries.map(ConversationDisplayEntry::id)
        )
    }

    @Test
    fun `lazy list content types keep incompatible message layouts separate`() {
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER),
                item("assistant", ConversationItemKind.ASSISTANT),
                item("reason", ConversationItemKind.REASONING),
                item("status", ConversationItemKind.STATUS),
                item("system", ConversationItemKind.SYSTEM)
            )
        )

        assertEquals(
            listOf(
                ConversationDisplayContentType.USER,
                ConversationDisplayContentType.ASSISTANT,
                ConversationDisplayContentType.PROCESS,
                ConversationDisplayContentType.STATUS,
                ConversationDisplayContentType.SYSTEM
            ),
            entries.map(ConversationDisplayEntry::contentType)
        )
    }

    @Test
    fun `final assistant markdown is virtualized into stable timeline rows`() {
        val text = List(6) { index ->
            "第${index + 1}段：${"长回复内容".repeat(45)}"
        }.joinToString("\n\n")
        val timeline = makeConversationTimelineEntries(
            makeConversationDisplayEntries(
                listOf(item("assistant-42", ConversationItemKind.ASSISTANT, text = text))
            )
        )

        assertTrue(timeline.first() is ConversationTimelineEntry.AssistantHeader)
        // 复制入口已并入消息头：最后一个分片之后不再有 footer 行。
        assertTrue(timeline.last() is ConversationTimelineEntry.AssistantMarkdown)
        val markdownRows = timeline.filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
        assertTrue(markdownRows.size > 1)
        assertEquals(
            markdownRows.indices.map { "assistant:assistant-42:markdown:$it" },
            markdownRows.map(ConversationTimelineEntry::id)
        )
        assertEquals(text, markdownRows.joinToString("\n\n", transform = { it.markdown }))
    }

    @Test
    fun `markdown virtualization never splits a fenced code block`() {
        val markdown = """
            开始

            ```kotlin
            val first = 1

            val second = 2
            ```

            结束段落
        """.trimIndent()

        val blocks = splitMarkdownForLazyLayout(markdown, targetCharacters = 20)

        assertTrue(blocks.size > 1)
        val codeBlock = blocks.single { it.contains("```kotlin") }
        assertTrue(codeBlock.contains("val first = 1\n\nval second = 2"))
        assertTrue(codeBlock.endsWith("```"))
    }

    @Test
    fun unmatchedToolResultRemainsInspectable() {
        val process = (makeConversationDisplayEntries(
            listOf(item("orphan-result", ConversationItemKind.TOOL_RESULT, seconds = 3.0))
        ).single() as ConversationDisplayEntry.Process).group

        assertEquals(1, process.tools.size)
        assertNull(process.tools.single().call)
        assertEquals("orphan-result", process.tools.single().result?.id)
    }

    @Test
    fun agentCallsJoinProcessGroupLikeOrdinaryTools() {
        // Agent 完成后与普通工具同折叠（结果入流）；运行中的动效走常驻条。
        val entries = makeConversationDisplayEntries(
            listOf(
                item("user", ConversationItemKind.USER, seconds = 1.0),
                item("agent-call", ConversationItemKind.TOOL, title = "Agent", seconds = 2.0, toolCallId = "c-agent"),
                item("agent-result", ConversationItemKind.TOOL_RESULT, seconds = 10.0, toolCallId = "c-agent"),
                item("assistant", ConversationItemKind.ASSISTANT, seconds = 11.0)
            )
        )

        assertEquals(3, entries.size)
        assertTrue(entries[0] is ConversationDisplayEntry.Message)
        val process = (entries[1] as ConversationDisplayEntry.Process).group
        assertEquals("Agent", process.tools.single().call?.title)
        assertEquals("agent-result", process.tools.single().result?.id)
        assertTrue(entries[2] is ConversationDisplayEntry.Message)
    }


    @Test
    fun processGroupPairsResultsByToolCallIdBeforeFifo() {
        // 折叠区：并行工具乱序完成时按 toolCallId 精确配对（B 先 A 后），
        // 防止 A 的参数挂 B 的结果。
        val process = (
            makeConversationDisplayEntries(
                listOf(
                    item("a-call", ConversationItemKind.TOOL, title = "Agent", toolCallId = "c-a", seconds = 1.0),
                    item("b-call", ConversationItemKind.TOOL, title = "read", toolCallId = "c-b", seconds = 2.0),
                    item("b-result", ConversationItemKind.TOOL_RESULT, toolCallId = "c-b", seconds = 3.0),
                    item("a-result", ConversationItemKind.TOOL_RESULT, toolCallId = "c-a", seconds = 9.0)
                )
            ).first { it is ConversationDisplayEntry.Process } as ConversationDisplayEntry.Process
            ).group

        assertEquals("a-result", process.tools.first { it.call?.id == "a-call" }.result?.id)
        assertEquals("b-result", process.tools.first { it.call?.id == "b-call" }.result?.id)
    }



    // ---- F02：助手回合分组（步骤组提到正文前合成回合；回合间 24dp 由渲染层落） ----

    @Test
    fun groupAssistantRounds_processLiftedBeforeItsAssistant_oneRound() {
        val rounds = groupAssistantRounds(
            makeConversationDisplayEntries(
                listOf(
                    item("u1", ConversationItemKind.USER),
                    item("a1", ConversationItemKind.ASSISTANT),
                    item("tool-1", ConversationItemKind.TOOL),
                    item("tool-2", ConversationItemKind.TOOL),
                    item("u2", ConversationItemKind.USER),
                )
            )
        )
        // 投影序 [u1, a1, tools] → 回合 [u1] / [tools(提上), a1] / [u2]
        assertEquals(3, rounds.size)
        assertEquals(1, rounds[0].size)
        // 回合内顺序：Process（工具步骤组）在前、正文在后；Process.id 是组 id
        assertTrue(rounds[1][0] is ConversationDisplayEntry.Process)
        assertEquals("a1", (rounds[1][1] as ConversationDisplayEntry.Message).item.id)
        assertEquals(1, rounds[2].size)
    }

    @Test
    fun groupAssistantRounds_assistantWithoutTools_ownRound() {
        val rounds = groupAssistantRounds(
            makeConversationDisplayEntries(
                listOf(item("a1", ConversationItemKind.ASSISTANT))
            )
        )
        assertEquals(1, rounds.size)
        assertEquals(1, rounds[0].size)
    }

    @Test
    fun groupAssistantRounds_orphanProcessWithoutAssistant_ownGroup() {
        val rounds = groupAssistantRounds(
            makeConversationDisplayEntries(
                listOf(item("tool-1", ConversationItemKind.TOOL))
            )
        )
        assertEquals(1, rounds.size)
        assertEquals(1, rounds[0].size)
    }

    @Test
    fun groupAssistantRounds_backToBackAssistantRounds_split() {
        val rounds = groupAssistantRounds(
            makeConversationDisplayEntries(
                listOf(
                    item("a1", ConversationItemKind.ASSISTANT),
                    item("tool-1", ConversationItemKind.TOOL),
                    item("a2", ConversationItemKind.ASSISTANT),
                    item("tool-2", ConversationItemKind.TOOL),
                )
            )
        )
        // 回合1=[tool-1(提上), a1]，回合2=[tool-2(提上), a2]
        assertEquals(2, rounds.size)
        assertTrue(rounds[0][0] is ConversationDisplayEntry.Process)
        assertEquals("a1", (rounds[0][1] as ConversationDisplayEntry.Message).item.id)
        assertTrue(rounds[1][0] is ConversationDisplayEntry.Process)
        assertEquals("a2", (rounds[1][1] as ConversationDisplayEntry.Message).item.id)
    }

    @Test
    fun groupAssistantRounds_userMessage_splitsRounds() {
        val rounds = groupAssistantRounds(
            makeConversationDisplayEntries(
                listOf(
                    item("u1", ConversationItemKind.USER),
                    item("a1", ConversationItemKind.ASSISTANT),
                    item("tool-1", ConversationItemKind.TOOL),
                )
            )
        )
        // [u1] / [a1+tool（组内步骤在前）]
        assertEquals(2, rounds.size)
        assertTrue(rounds[1][0] is ConversationDisplayEntry.Process)
        assertEquals("a1", (rounds[1][1] as ConversationDisplayEntry.Message).item.id)
    }

    @Test
    fun `displayRoundKey prefers assistant id and stays stable across tool merging`() {
        val assistant = ConversationDisplayEntry.Message(item("ai-1", ConversationItemKind.ASSISTANT))
        val process = ConversationDisplayEntry.Process(
            ConversationProcessGroup("process:ai-1:0", listOf(item("call", ConversationItemKind.TOOL, toolCallId = "call")))
        )
        // 步骤组+正文回合：key=正文消息 id（流式中工具项并入、组内重排都不变）
        assertEquals("ai-1", displayRoundKey(listOf(process, assistant)))
        // 单条 USER 回合：round: 前缀兜底
        val user = ConversationDisplayEntry.Message(item("u-1", ConversationItemKind.USER))
        assertEquals("round:u-1", displayRoundKey(listOf(user)))
        // 纯步骤组回合（正文未到）：round: + 步骤组 id
        assertEquals("round:process:ai-1:0", displayRoundKey(listOf(process)))
    }

    private fun item(
        id: String,
        kind: ConversationItemKind,
        title: String = kind.name,
        text: String = id,
        seconds: Double = 0.0,
        toolCallId: String? = null
    ) = ConversationItem(
        id = id,
        kind = kind,
        title = title,
        text = text,
        epochSeconds = seconds,
        toolCallId = toolCallId
    )
}
