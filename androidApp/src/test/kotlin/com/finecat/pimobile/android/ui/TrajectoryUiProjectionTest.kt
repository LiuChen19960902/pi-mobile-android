package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.projection.TrajectoryNode
import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import com.finecat.pimobile.shared.projection.TrajectoryRequest
import com.finecat.pimobile.shared.projection.RequestTokenUsage
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryUiProjectionTest {
    @Test
    fun overviewMatchesDurationTurnAndToolRules() {
        val input = node("input", TrajectoryNodeKind.INPUT, 1, 1.0, 2.0)
        val request = requestNode("request", turn = 1, step = 1, start = 2.0, end = 6.0)
        val assistant = node("assistant", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 4.0, turn = 1, step = 1)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 3, 4.0, 7.0, turn = 1, step = 1)
        val nextTurn = node("next", TrajectoryNodeKind.INPUT, 4, 8.0, 9.0, turn = 2)

        val summary = TrajectoryUiProjection.overview(listOf(input, request, assistant, tool, nextTurn))

        // 墙钟跨度：1.0→9.0 计 8s（嵌套区间不累加）。
        assertEquals(8.0, summary.durationSeconds, 0.0001)
        assertEquals(2, summary.turnCount)
        assertEquals(1, summary.toolCount)
        assertEquals(listOf(input, assistant, tool, nextTurn), summary.visibleNodes)
        assertEquals(0f, summary.entries.first().startFraction)
        assertEquals(1f, summary.entries.last().endFraction)
        // 各条按真实时刻定位：assistant 2→4 落 1/8→3/8，tool 4→7 落 3/8→6/8。
        val byId = summary.entries.associateBy { it.node.id }
        assertEquals(0.125f, byId.getValue("assistant").startFraction, 0.001f)
        assertEquals(0.375f, byId.getValue("assistant").endFraction, 0.001f)
        assertEquals(0.375f, byId.getValue("tool").startFraction, 0.001f)
        assertEquals(0.75f, byId.getValue("tool").endFraction, 0.001f)
    }

    @Test
    fun hiddenRequestMapsToAssistantAndTurnHeadersFollowFutureTurn() {
        val inputWithoutTurn = node("input", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0)
        val request = requestNode("request", turn = 3, step = 2, start = 2.0, end = 3.0)
        val assistant = node("assistant", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 3.0, turn = 3, step = 2)
        val rows = TrajectoryUiProjection.displayRows(listOf(inputWithoutTurn, request, assistant))

        assertEquals(2, rows.size)
        assertEquals(3, rows.first().turn)
        assertTrue(rows.first().startsTurn)
        assertSame(request, rows.last().request)
        assertEquals(3, rows.last().turn)
    }

    @Test
    fun liveNodeReplacementImmediatelyChangesOverviewAndRowContent() {
        val streaming = node("assistant-stream", TrajectoryNodeKind.ASSISTANT, 1, 10.0, 10.2, turn = 1, step = 1)
            .copy(subtitle = "正在生成")
        val first = TrajectoryUiProjection.overview(listOf(streaming))
        val completed = streaming.copy(subtitle = "生成完成", endEpochSeconds = 12.5)
        val second = TrajectoryUiProjection.overview(listOf(completed))

        assertEquals("正在生成", first.visibleNodes.single().subtitle)
        assertEquals("生成完成", second.visibleNodes.single().subtitle)
        assertTrue(second.durationSeconds > first.durationSeconds)
        assertEquals(completed, TrajectoryUiProjection.displayRows(listOf(completed)).single().node)
    }

    @Test
    fun overviewTapUsesRequestedLaneBeforeNearestFallback() {
        val input = node("input", TrajectoryNodeKind.INPUT, 1, 1.0, 2.0)
        val assistant = node("assistant", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 3.0)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 3, 3.0, 4.0)
        val entries = TrajectoryUiProjection.overview(listOf(input, assistant, tool)).entries

        assertSame(tool, TrajectoryUiProjection.nearestEntry(entries, 0.05f, TrajectoryNodeKind.TOOL))
        assertSame(assistant, TrajectoryUiProjection.nearestEntry(entries, 0.95f, TrajectoryNodeKind.ASSISTANT))
    }

    private fun requestNode(
        id: String,
        turn: Int,
        step: Int,
        start: Double,
        end: Double
    ): TrajectoryNode = node(id, TrajectoryNodeKind.REQUEST, 99, start, end, turn, step).copy(
        request = TrajectoryRequest(
            number = 1,
            turn = turn,
            step = step,
            provider = "deepseek",
            model = "model",
            options = null,
            usage = RequestTokenUsage(),
            cumulativeUsage = RequestTokenUsage(),
            toolCalls = 0,
            subtoolCalls = 0
        )
    )

    private fun node(
        id: String,
        kind: TrajectoryNodeKind,
        sequence: Int,
        start: Double,
        end: Double,
        turn: Int? = null,
        step: Int? = null
    ): TrajectoryNode {
        val record = SessionEvent(
            sessionId = "session",
            seq = sequence,
            time = end,
            event = GatewayEvent("fixture", turn = turn, step = step)
        )
        return TrajectoryNode(
            id = id,
            kind = kind,
            title = kind.name,
            subtitle = id,
            startSequence = sequence,
            endSequence = sequence,
            startEpochSeconds = start,
            endEpochSeconds = end,
            records = listOf(record)
        )
    }

    // ---- S5 统计卡新增的 token / 上下文两格 ----

    @Test
    fun tokenTotalTakesLastCumulativeSnapshotPerTurn_notSumOfEveryRequest() {
        // 同一轮两个请求，cumulative 已含前序：12000+3000 与 12000+5000。
        // 正确口径是取该轮最后一次快照（17000），直接把两次都加会算成 32000。
        val first = requestNode("r1", 1, 1, 1.0, 2.0).withCumulative(12_000, 3_000)
        val second = requestNode("r2", 1, 2, 2.0, 3.0).withCumulative(12_000, 5_000)
        val summary = TrajectoryUiProjection.overview(listOf(first, second))
        assertEquals(17_000, summary.tokenTotal)
    }

    @Test
    fun contextFractionUsesLatestCumulativeInputOverWindow() {
        val first = requestNode("r1", 1, 1, 1.0, 2.0).withCumulative(50_000, 1_000)
        val second = requestNode("r2", 2, 1, 3.0, 4.0).withCumulative(25_000, 1_000)
        val summary = TrajectoryUiProjection.overview(listOf(first, second), contextWindow = 100_000)
        // 取最新一轮的 cumulative 输入 25000 / 100000
        assertEquals(0.25f, summary.contextFraction!!, 0.0001f)
    }

    @Test
    fun contextFractionAbsentWhenWindowUnknown_orNoInput() {
        val request = requestNode("r1", 1, 1, 1.0, 2.0).withCumulative(1_000, 100)
        // 协议里 contextWindow 可空：窗口未知就不显示该格，不猜默认窗口
        assertEquals(null, TrajectoryUiProjection.overview(listOf(request)).contextFraction)
        // 窗口为 0 同样视为未知
        assertEquals(
            null,
            TrajectoryUiProjection.overview(listOf(request), contextWindow = 0).contextFraction,
        )
        // 有窗口但没有累计输入：同样不显示 0%
        val noInput = requestNode("r2", 1, 1, 1.0, 2.0)
        assertEquals(
            null,
            TrajectoryUiProjection.overview(listOf(noInput), contextWindow = 100_000).contextFraction,
        )
    }

    // ---- S5 轮次元信息：步数 / 失败 / 进行中 ----

    @Test
    fun turnRowAggregatesStepCountAndFailure() {
        val input = node("in", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0, turn = 1)
        val assistant = node("as", TrajectoryNodeKind.ASSISTANT, 2, 1.0, 2.0, turn = 1)
        val failedTool = node("tool", TrajectoryNodeKind.TOOL, 3, 2.0, 3.0, turn = 1)
            .copy(records = listOf(
                SessionEvent("s", 3, 3.0, GatewayEvent("tool/result", turn = 1, isError = true))
            ))
        val rows = TrajectoryUiProjection.displayRows(listOf(input, assistant, failedTool))
        val header = rows.first { it.startsTurn }
        // 元信息「N 步」= 整轮 span 数（assistant+tool）；用户锚点不算 span（审查
        // Minor 1：与详情卡「第 i/n 步」的 spansOfTurn 同口径，原型 t.spans.length 同）
        assertEquals(2, header.stepCount)
        assertTrue(header.hasFailed)
        assertTrue(!header.isRunning)
    }

    @Test
    fun turnRowIsRunningWhenLastSpanHasNoEndTime() {
        val input = node("in", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0, turn = 1)
        val running = node("as", TrajectoryNodeKind.ASSISTANT, 2, 1.0, 0.0, turn = 1)
        val rows = TrajectoryUiProjection.displayRows(listOf(input, running))
        val header = rows.first { it.startsTurn }
        assertTrue(header.isRunning)
    }

    @Test
    fun turnStepCountMatchesSpansOfTurn_evenWhenAnchorIsNotInput() {
        // 重连中途的轮没有用户锚点，轮首行是 ASSISTANT（作为锚点）。「N 步」必须与
        // 详情卡 spansOfTurn 完全同口径——锚点自己不算 span（复审 N3：旧 count 按
        // kind != INPUT 统计会把锚点多算 1）。
        val assistant = node("as", TrajectoryNodeKind.ASSISTANT, 1, 1.0, 2.0, turn = 1)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 2, 2.0, 3.0, turn = 1)
        val rows = TrajectoryUiProjection.displayRows(listOf(assistant, tool))
        val header = rows.first { it.startsTurn }
        assertEquals(1, header.stepCount)
        assertEquals(
            TrajectoryUiProjection.spansOfTurn(rows, header).size,
            header.stepCount,
        )
    }

    // ---- F04：详情卡的 span 列表 / 你说 / 运行中判定 ----

    @Test
    fun spansOfTurnExcludesAnchorAndUserInput_countsRealSpans() {
        val input = node("in", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0, turn = 1)
        val assistant = node("as", TrajectoryNodeKind.ASSISTANT, 2, 1.0, 2.0, turn = 1)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 3, 2.0, 3.0, turn = 1)
        val context = node("ctx", TrajectoryNodeKind.CONTEXT, 4, 3.0, 3.0, turn = 1)
        val nextInput = node("in2", TrajectoryNodeKind.INPUT, 5, 4.0, 4.0, turn = 2)
        val rows = TrajectoryUiProjection.displayRows(listOf(input, assistant, tool, context, nextInput))
        val anchor = rows.first { it.startsTurn }
        val spans = TrajectoryUiProjection.spansOfTurn(rows, anchor)
        // 用户锚点不算 span（F04：第 i/n 步分母不得包含用户锚点）；轮首锚点行本身也不在列表里
        assertEquals(listOf("as", "tool", "ctx"), spans.map { it.node.id })
    }

    @Test
    fun spansOfTurnExcludesLiftedInputWhenAnchorIsNotInput() {
        // 锚点被提升为非 INPUT 行（turn=assistant 流序号）时，同轮的 INPUT 行仍不得算 span
        val tool = node("tool", TrajectoryNodeKind.TOOL, 1, 1.0, 2.0, turn = 1)
        val input = node("in", TrajectoryNodeKind.INPUT, 2, 1.5, 1.5, turn = 1)
        val assistant = node("as", TrajectoryNodeKind.ASSISTANT, 3, 2.5, 3.0, turn = 1)
        val rows = TrajectoryUiProjection.displayRows(listOf(tool, input, assistant))
        val anchor = rows.first { it.startsTurn }
        val spans = TrajectoryUiProjection.spansOfTurn(rows, anchor)
        assertEquals(listOf("as"), spans.map { it.node.id })
    }

    @Test
    fun turnUserSaidIsTurnInputText_orNullWithoutInput() {
        val said = node("in", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0, turn = 1).copy(subtitle = "用户原话甲")
        val assistant = node("as", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 3.0, turn = 1)
        val rows = TrajectoryUiProjection.displayRows(listOf(said, assistant))
        assertEquals("用户原话甲", TrajectoryUiProjection.turnUserSaid(rows, 1))
        // 纯上下文注入轮没有 INPUT：不造“你说”
        val noInputRows = TrajectoryUiProjection.displayRows(
            listOf(node("ctx", TrajectoryNodeKind.CONTEXT, 3, 4.0, 4.0, turn = 2)),
        )
        assertEquals(null, TrajectoryUiProjection.turnUserSaid(noInputRows, 2))
        assertEquals(null, TrajectoryUiProjection.turnUserSaid(rows, null))
    }

    @Test
    fun spanRunningDetectsToolWithoutResultEvent() {
        // tool/call 节点构造时 end=自身时间（非 0），运行中的真实信号是结果事件缺席
        val runningTool = node("tool-run", TrajectoryNodeKind.TOOL, 1, 1.0, 1.0, turn = 1)
        val doneTool = node("tool-done", TrajectoryNodeKind.TOOL, 2, 2.0, 3.0, turn = 1).copy(
            records = listOf(
                SessionEvent("s", 1, 2.0, GatewayEvent("tool/call", callId = "c1")),
                SessionEvent("s", 2, 3.0, GatewayEvent("tool/result", callId = "c1", preview = "输出")),
            ),
        )
        val rows = TrajectoryUiProjection.displayRows(listOf(runningTool, doneTool))
        assertTrue(TrajectoryUiProjection.isSpanRunning(rows[0]))
        assertTrue(!TrajectoryUiProjection.isSpanRunning(rows[1]))
        // 非工具类型维持“结束时间未知 = 运行中”
        val openAssistant = node("as", TrajectoryNodeKind.ASSISTANT, 3, 4.0, 0.0, turn = 2)
        assertTrue(TrajectoryUiProjection.isSpanRunning(TrajectoryUiProjection.displayRows(listOf(openAssistant)).single()))
    }

    @Test
    fun spanRunningDetectsStreamingAssistantWithoutMessageRecord_evenWithNonZeroEnd() {
        // N7（F05 转办）：ASSISTANT 分支的判别力——流式助手节点 end 随 chunk 更新恒非 0，
        // 旧「end<=0」规则判不出流式运行；真实信号是 records 无落定 assistant/message。
        val streaming = node("as-stream", TrajectoryNodeKind.ASSISTANT, 1, 4.0, 4.0, turn = 1)
        assertTrue(
            "流式助手（end 非 0、无落定 message）应判运行中",
            TrajectoryUiProjection.isSpanRunning(
                TrajectoryUiProjection.displayRows(listOf(streaming)).single(),
            ),
        )
        // 对照：带落定 assistant/message 的节点 → 均不判运行（end 口径与 record 口径一致为 false）
        val settled = streaming.copy(
            records = listOf(
                SessionEvent("s", 1, 4.0, GatewayEvent("assistant/message", turn = 1, text = "落定正文")),
            ),
        )
        assertTrue(
            !TrajectoryUiProjection.isSpanRunning(
                TrajectoryUiProjection.displayRows(listOf(settled)).single(),
            ),
        )
    }

    @Test
    fun historyRowsDoNotBorrowFirstLiveTurnForAnchorSaidAndSpans() {
        // 审查 M1：历史页行（归一事件无 turn）向后 fallback 不得跨过 live 用户输入
        // 边界——否则轮 1 锚点/「你说」/span 列表全部错引历史消息。
        val histInput = node("hist-in", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0).copy(subtitle = "历史原话")
        val histAssistant = node("hist-as", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 2.5).copy(subtitle = "历史回答")
        val liveInput = node("live-in", TrajectoryNodeKind.INPUT, 3, 3.0, 3.0, turn = 1).copy(subtitle = "live 原话")
        val liveAssistant = node("live-as", TrajectoryNodeKind.ASSISTANT, 4, 4.0, 5.0, turn = 1)
        val rows = TrajectoryUiProjection.displayRows(listOf(histInput, histAssistant, liveInput, liveAssistant))
        val anchor = rows.first { it.startsTurn && it.node.kind == TrajectoryNodeKind.INPUT && it.node.id == "live-in" }
        // 轮 1 锚点 = live 用户输入；历史行 turn=null 不混入轮 1
        assertEquals(null, rows.first { it.node.id == "hist-in" }.turn)
        assertEquals(null, rows.first { it.node.id == "hist-as" }.turn)
        assertEquals("live 原话", TrajectoryUiProjection.turnUserSaid(rows, 1))
        assertEquals(listOf("live-as"), TrajectoryUiProjection.spansOfTurn(rows, anchor).map { it.node.id })
    }

    @Test
    fun spanFailedFollowsRecordErrorFlag() {
        val okTool = node("tool-ok", TrajectoryNodeKind.TOOL, 1, 1.0, 2.0, turn = 1)
        val badTool = node("tool-bad", TrajectoryNodeKind.TOOL, 2, 2.0, 3.0, turn = 1).copy(
            records = listOf(SessionEvent("s", 2, 3.0, GatewayEvent("tool/result", isError = true))),
        )
        val rows = TrajectoryUiProjection.displayRows(listOf(okTool, badTool))
        assertTrue(!TrajectoryUiProjection.isSpanFailed(rows[0]))
        assertTrue(TrajectoryUiProjection.isSpanFailed(rows[1]))
    }

    @Test
    fun toolNodeTurnsForwardToItsTriggeringAssistant_notLiftedToNextTurnAnchor() {
        // 审查 Minor 7：pi 原生 tool_execution_start 无 turn——向前归到触发它的
        // assistant 轮，不得被提升为下一轮锚点。
        val assistant1 = node("as1", TrajectoryNodeKind.ASSISTANT, 1, 1.0, 2.0, turn = 1)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 2, 2.5, 3.0).copy(
            records = listOf(SessionEvent("s", 2, 2.5, GatewayEvent("tool/call", callId = "c1"))),
        )
        val input2 = node("in2", TrajectoryNodeKind.INPUT, 3, 3.5, 3.5, turn = 2)
        val assistant2 = node("as2", TrajectoryNodeKind.ASSISTANT, 4, 4.0, 5.0, turn = 2)
        val rows = TrajectoryUiProjection.displayRows(listOf(assistant1, tool, input2, assistant2))
        val toolRow = rows.first { it.node.id == "tool" }
        assertEquals(1, toolRow.turn)
        assertTrue(!toolRow.startsTurn)
        // 轮 2 的锚点 = live 用户输入，工具不是任何轮的锚点
        val anchor2 = rows.first { it.startsTurn && it.turn == 2 }
        assertEquals("in2", anchor2.node.id)
    }

    private fun TrajectoryNode.withCumulative(input: Int, output: Int): TrajectoryNode = copy(
        request = request?.copy(
            cumulativeUsage = RequestTokenUsage(
                uncachedInput = input,
                cachedInput = 0,
                output = output,
                reasoning = 0,
            ),
        ),
    )
}
