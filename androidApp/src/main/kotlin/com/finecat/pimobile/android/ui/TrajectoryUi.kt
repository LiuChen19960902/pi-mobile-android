package com.finecat.pimobile.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finecat.pimobile.android.R
import com.finecat.pimobile.shared.projection.TrajectoryNode
import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay

internal data class TrajectoryDisplayRow(
    val node: TrajectoryNode,
    val request: TrajectoryNode?,
    val turn: Int?,
    val startsTurn: Boolean,
    /** 该轮已完成的步数（原型 `t.spans.length`，显示为「N 步」）。 */
    val stepCount: Int = 0,
    /** 本轮是否出现过工具/上游错误（原型 `st === 'fail'`，元信息转红）。 */
    val hasFailed: Boolean = false,
    /** 本轮最后一条 span 是否仍在进行中（原型 `st === 'run'`）。 */
    val isRunning: Boolean = false,
)

internal data class TrajectoryOverviewEntry(
    val node: TrajectoryNode,
    val startFraction: Float,
    val endFraction: Float
)

internal data class TrajectoryOverviewSummary(
    val durationSeconds: Double,
    val turnCount: Int,
    val toolCount: Int,
    /**
     * 累计 token（输入含缓存 + 输出）。与原型 `turns.reduce(turnTok)` 同口径：取各轮
     * 末次请求的 cumulative 快照求和，而不是把每步增量累加——后者会重复计算。
     */
    val tokenTotal: Int,
    /**
     * 上下文占用率（0f..1f）。取最新一轮的 cumulative 输入 ÷ 上下文窗口；窗口未知
     * （协议里 `contextWindow` 可空）时为 null，调用方据此整格隐藏而不是造 0%。
     */
    val contextFraction: Float?,
    val visibleNodes: List<TrajectoryNode>,
    val entries: List<TrajectoryOverviewEntry>
)

internal object TrajectoryUiProjection {
    fun overview(
        nodes: List<TrajectoryNode>,
        contextWindow: Long? = null,
    ): TrajectoryOverviewSummary {
        val visible = nodes.filter { it.kind != TrajectoryNodeKind.REQUEST }
        val wall = wallClock(visible)
        return TrajectoryOverviewSummary(
            durationSeconds = wall.duration,
            turnCount = nodes.flatMap(TrajectoryNode::records).mapNotNull { it.event.turn }.toSet().size,
            toolCount = nodes.count {
                it.kind == TrajectoryNodeKind.TOOL || it.kind == TrajectoryNodeKind.SUBTOOL
            },
            tokenTotal = tokenTotal(nodes),
            contextFraction = contextFraction(nodes, contextWindow),
            visibleNodes = visible,
            entries = layoutEntries(visible, wall)
        )
    }

    /**
     * 累计 token：按 turn 取最后一个请求节点的 `cumulativeUsage` 快照（输入含缓存 +
     * 输出），再跨轮求和。同一轮内多个请求的 cumulative 已包含前序请求，直接求和会
     * 把同一条 token 算多次。
     */
    private fun tokenTotal(nodes: List<TrajectoryNode>): Int =
        nodes.filter { it.kind == TrajectoryNodeKind.REQUEST }
            .filter { it.request != null }
            .groupBy { it.request!!.turn }
            .values
            .sumOf { group ->
                val last = group.maxByOrNull { it.request!!.step ?: 0 } ?: return@sumOf 0
                val usage = last.request?.cumulativeUsage ?: return@sumOf 0
                usage.totalInput + usage.output
            }

    /** 上下文占用率：最新一轮末次请求的 cumulative 输入 ÷ 窗口。缺任一项则不显示。 */
    private fun contextFraction(nodes: List<TrajectoryNode>, contextWindow: Long?): Float? {
        val window = contextWindow?.takeIf { it > 0 } ?: return null
        val latest = nodes.filter { it.kind == TrajectoryNodeKind.REQUEST && it.request != null }
            .maxByOrNull { (it.request!!.turn ?: 0) * 1000 + (it.request!!.step ?: 0) }
            ?: return null
        val input = latest.request?.cumulativeUsage?.totalInput ?: return null
        if (input <= 0) return null
        return (input.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    }

    fun displayRows(nodes: List<TrajectoryNode>): List<TrajectoryDisplayRow> {
        val visible = nodes.filter { it.kind != TrajectoryNodeKind.REQUEST }
        val requestsByStep = nodes.mapNotNull { node ->
            val request = node.request ?: return@mapNotNull null
            if (node.kind != TrajectoryNodeKind.REQUEST || request.turn == null || request.step == null) {
                return@mapNotNull null
            }
            "${request.turn}-${request.step}" to node
        }.toMap()
        var previousTurn: Int? = null
        val rows = visible.mapIndexed { index, node ->
            val directTurn = node.records.firstNotNullOfOrNull { it.event.turn }
            // 历史消息使用负序号且没有轮次标识。订阅正在运行的会话时可能没有
            // 新 user 边界，不能因此向后借用实时轮次，把历史记录藏入折叠轮。
            val historyOnly = node.records.isNotEmpty() && node.records.all { it.seq < 0 }
            val turn = directTurn ?: if (historyOnly) null else when (node.kind) {
                // pi 原生 tool_execution_start 不带 turn（extensions/types.d.ts:675：只有
                // toolCallId/toolName/args）。工具执行属于触发它的 assistant 轮：向前归，
                // 否则向后 fallback 会把工具行提升为下一轮锚点——工具详情失去入口、
                // 轮次聚合（步数/时长）整体错位（F04 实测）。
                TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL ->
                    visible.subList(0, index + 1).asSequence().flatMap { it.records.asSequence() }
                        .mapNotNull { it.event.turn }.lastOrNull()
                else -> {
                    // 向后 fallback 只扫到下一个用户输入节点之前：用户消息（message_start
                    // assistant 递增的 turn 属于其后 assistant）是轮边界，历史页行（归一
                    // 事件无 turn）不得借用到第一条 live 轮——否则轮 1 锚点/「你说」/span
                    // 列表全部错引历史消息（F04 审查 M1）。按索引扫描不复制尾部列表
                    // （drop 每行复制一次，流式期间重投影 O(n²)，复审 N6）。
                    var boundary = visible.size
                    for (j in index + 1 until visible.size) {
                        if (visible[j].kind == TrajectoryNodeKind.INPUT) {
                            boundary = j
                            break
                        }
                    }
                    visible.subList(index, boundary)
                        .asSequence().flatMap { it.records.asSequence() }
                        .mapNotNull { it.event.turn }.firstOrNull()
                }
            }
            val startsTurn = turn != null && turn != previousTurn
            if (turn != null) previousTurn = turn
            val step = node.records.firstNotNullOfOrNull { it.event.step }
            TrajectoryDisplayRow(
                node = node,
                request = if (node.kind == TrajectoryNodeKind.ASSISTANT && turn != null && step != null) {
                    requestsByStep["$turn-$step"]
                } else {
                    null
                },
                turn = turn,
                startsTurn = startsTurn
            )
        }
        return withTurnStatus(rows)
    }

    /**
     * 补轮次级聚合：步数、失败态、进行中。原型按轮聚合 span，所以元信息
     * 「N 步 · 时长 · 失败/进行中」必须由整轮算，不能只看锚点那一行。
     */
    private fun withTurnStatus(rows: List<TrajectoryDisplayRow>): List<TrajectoryDisplayRow> {
        val byTurn = rows.filter { it.turn != null }.groupBy { it.turn }
        // 每轮 span 数一次遍历预计算（displayRows 在流式期间逐 delta 重算，逐锚点调
        // spansOfTurn 是 O(轮数×总行数)；二轮复审 N3）。过滤条件与 spansOfTurn 逐条
        // 等价：锚点自身 startsTurn 已排除，用户锚点不算 span。
        val spanCountByTurn = rows.asSequence()
            .filter { it.turn != null && !it.startsTurn && it.node.kind != TrajectoryNodeKind.INPUT }
            .groupingBy { it.turn!! }.eachCount()
        return rows.map { row ->
            val group = row.turn?.let { byTurn[it] }
            if (group == null || !row.startsTurn) {
                row
            } else {
                row.copy(
                    // 原型 t.spans.length 不含用户锚点（said 不算 span），与详情卡
                    // 「第 i/n 步」的 spansOfTurn 完全同口径——锚点非 INPUT 的轮（重连
                    // 中途等）旧 count 会把锚点自己多算 1（复审 N3）
                    stepCount = spanCountByTurn[row.turn] ?: 0,
                    // 结束时间为 0 表示这一轮还有 span 没落定；最后一轮才可能是"进行中"
                    hasFailed = group.any { it.node.records.any { rec -> rec.event.isError == true } },
                    isRunning = group.last().node.endEpochSeconds <= 0.0,
                )
            }
        }
    }

    /**
     * 该轮的 span 行（原型 `t.spans`）：排除轮首锚点行与用户输入行。用户锚点
     * （INPUT）任何情况下不算 span（F04：第 i/n 步的分母不能把用户锚点冒充 span），
     * 轮次行自己已显示用户那句话；CONTEXT/ASSISTANT/TOOL/SUBTOOL 是真实执行步。
     */
    fun spansOfTurn(
        rows: List<TrajectoryDisplayRow>,
        anchor: TrajectoryDisplayRow,
    ): List<TrajectoryDisplayRow> {
        val turn = anchor.turn ?: return emptyList()
        return rows.filter {
            it.turn == turn && !it.startsTurn &&
                it.node.kind != TrajectoryNodeKind.INPUT && it.node.id != anchor.node.id
        }
    }

    /** 该轮用户原话（原型 `t.said`）：该轮 INPUT 节点的正文；纯上下文注入轮没有 INPUT → null。 */
    fun turnUserSaid(rows: List<TrajectoryDisplayRow>, turn: Int?): String? {
        turn ?: return null
        return rows.firstOrNull { it.turn == turn && it.node.kind == TrajectoryNodeKind.INPUT }
            ?.node?.subtitle?.takeIf(String::isNotBlank)
    }

    /**
     * span 是否仍在运行：tool/call 节点构造时 end=自身时间（非 0），`end <= 0` 对工具
     * 恒 false；真实信号是结果事件缺席（start 后无 toolResult/tool/code-dispatch）。
     * 其余类型维持“结束时间未知 = 运行中”。
     */
    fun isSpanRunning(row: TrajectoryDisplayRow): Boolean = when (row.node.kind) {
        TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL ->
            row.node.records.none {
                it.event.type == "tool/result" || it.event.type == "tool/code-dispatch"
            }
        // ASSISTANT 与工具对称：records 无落定 assistant/message 即流式未定（end 随
        // chunk 更新非 0、历史缺 timestamp 归 0，end<=0 都不可靠——审查 Minor 2）
        TrajectoryNodeKind.ASSISTANT ->
            row.node.records.none { it.event.type == "assistant/message" }
        else -> row.node.endEpochSeconds <= 0.0
    }

    /** span 是否失败：本轮任一 record 带错误标记（tool/result isError、上游错误等）。 */
    fun isSpanFailed(row: TrajectoryDisplayRow): Boolean =
        row.node.records.any { it.event.isError == true }

    fun nearestEntry(
        entries: List<TrajectoryOverviewEntry>,
        fraction: Float,
        lane: TrajectoryNodeKind
    ): TrajectoryNode? {
        val laneEntries = entries.filter { entry ->
            when (lane) {
                TrajectoryNodeKind.ASSISTANT -> entry.node.kind in setOf(
                    TrajectoryNodeKind.CONTEXT,
                    TrajectoryNodeKind.ASSISTANT
                )
                else -> entry.node.kind == lane
            }
        }
        return (laneEntries.ifEmpty { entries }).minByOrNull { entry ->
            when {
                fraction < entry.startFraction -> entry.startFraction - fraction
                fraction > entry.endFraction -> fraction - entry.endFraction
                else -> 0f
            }
        }?.node
    }

    /** 墙钟窗口：横轴按真实时刻铺，嵌套区间不再重复累加。
     * Duration 显示跨度（assistant 10s 含 3×2s 工具调用 → 10s 而非 16s）。 */
    private data class WallClock(val origin: Double, val span: Double) {
        val duration: Double get() = span
    }

    private fun wallClock(nodes: List<TrajectoryNode>): WallClock {
        val starts = nodes.mapNotNull { it.startEpochSeconds.takeIf { s -> s > 0 } }
        val ends = nodes.mapNotNull { it.endEpochSeconds.takeIf { e -> e > 0 } }
        if (starts.isEmpty() || ends.isEmpty()) return WallClock(0.0, 0.0)
        val origin = starts.min()
        // 进行中节点（end 未知）延伸到窗口右缘：右缘取最晚已知结束与最晚开始的较大者。
        val end = maxOf(ends.max(), starts.max())
        return WallClock(origin, (end - origin).coerceAtLeast(0.0))
    }

    private fun layoutEntries(nodes: List<TrajectoryNode>, wall: WallClock): List<TrajectoryOverviewEntry> {
        if (nodes.isEmpty()) return emptyList()
        if (wall.span <= 0) {
            // 全无有效时间戳：退化为均分布，避免除零。
            return nodes.mapIndexed { index, node ->
                TrajectoryOverviewEntry(node, index.toFloat() / nodes.size, (index + 1).toFloat() / nodes.size)
            }
        }
        return nodes.map { node ->
            val start = node.startEpochSeconds.takeIf { it > 0 }?.let { (it - wall.origin) / wall.span } ?: 0.0
            val end = node.endEpochSeconds.takeIf { it > 0 }?.let { (it - wall.origin) / wall.span } ?: 1.0
            TrajectoryOverviewEntry(
                node,
                start.coerceIn(0.0, 1.0).toFloat(),
                end.coerceIn(0.0, 1.0).toFloat().coerceAtLeast(start.coerceIn(0.0, 1.0).toFloat())
            )
        }
    }

}

@Composable
internal fun TrajectoryPage(
    nodes: List<TrajectoryNode>,
    /** 会话是否真在跑（isStreaming）：空列表+运行中=转圈，空列表+空闲=空态提示。
     * 之前误用“当前页”当运行信号，新会话右滑即永久转圈。 */
    isRunning: Boolean
) {
    val summary = remember(nodes) { TrajectoryUiProjection.overview(nodes) }
    val rows = remember(nodes) { TrajectoryUiProjection.displayRows(nodes) }
    val listState = rememberLazyListState()
    var selectedNode by remember { mutableStateOf<TrajectoryNode?>(null) }
    var highlightedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(highlightedId) {
        val id = highlightedId ?: return@LaunchedEffect
        delay(1_200)
        if (highlightedId == id) highlightedId = null
    }

    Column(Modifier.fillMaxSize().testTag("trajectory-page")) {
        TrajectoryOverview(summary) { node ->
            val index = rows.indexOfFirst { it.node.id == node.id }
            if (index >= 0) {
                highlightedId = node.id
            }
        }
        LaunchedEffect(highlightedId, rows) {
            val id = highlightedId ?: return@LaunchedEffect
            val index = rows.indexOfFirst { it.node.id == id }
            if (index >= 0) listState.animateScrollToItem(index)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)
                .testTag("trajectory-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 90.dp)
        ) {
            if (nodes.isEmpty()) {
                item("trajectory-empty") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isRunning) {
                            PiSpinner(color = PiColors.Ocean, fontSize = 12.sp)
                            Text(
                                "正在生成轨迹…",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                "暂无轨迹：发送第一条消息后，这里会显示执行时间线",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> "trajectory:${row.node.id}" }) { _, row ->
                if (row.startsTurn && row.turn != null) TrajectoryTurnHeader(row.turn)
                TrajectoryRow(
                    row = row,
                    highlighted = highlightedId == row.node.id,
                    onSelect = { selectedNode = row.node },
                    onRequest = { selectedNode = it }
                )
            }
        }
    }

    selectedNode?.let { node ->
        TrajectoryEventDetailSheet(node = node, onDismiss = { selectedNode = null })
    }
}

@Composable
private fun TrajectoryOverview(
    summary: TrajectoryOverviewSummary,
    onSelect: (TrajectoryNode) -> Unit
) {
    val isDark = LocalPiDark.current
    val duration = String.format(Locale.US, "%.2f s", summary.durationSeconds)
    Box(
        modifier = Modifier.fillMaxWidth()
            // 玻璃材质：半透明底透出下层背景，不再用实色头部条。
            .background(piGlassTint())
            .testTag("trajectory-overview")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                TrajectoryClockIcon()
                Spacer(Modifier.width(8.dp))
                Text("Duration", fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text("${summary.turnCount} Turns", fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text("${summary.toolCount} Calls", fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    duration,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier.width(34.dp).height(52.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("Input", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 11.sp)
                    Text("Model", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 11.sp)
                    Text("Tools", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 11.sp)
                }
                TimelineOverviewCanvas(
                    entries = summary.entries,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).height(52.dp)
                )
            }
            Row {
                Text(
                    "0s",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                Text(
                    duration,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.7.dp).background(
                if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
            )
        )
    }
}

@Composable
private fun TrajectoryClockIcon() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color, style = Stroke(stroke))
        drawLine(
            color,
            start = center,
            end = Offset(center.x, center.y - 4.dp.toPx()),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color,
            start = center,
            end = Offset(center.x - 3.dp.toPx(), center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun TimelineOverviewCanvas(
    entries: List<TrajectoryOverviewEntry>,
    onSelect: (TrajectoryNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalPiDark.current
    val guideColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    Canvas(
        modifier.pointerInput(entries) {
            detectTapGestures { position ->
                if (entries.isEmpty() || size.width == 0) return@detectTapGestures
                val fraction = (position.x / size.width).coerceIn(0f, 0.999999f)
                val lane = when {
                    position.y < 15.dp.toPx() -> TrajectoryNodeKind.INPUT
                    position.y < 35.dp.toPx() -> TrajectoryNodeKind.ASSISTANT
                    else -> TrajectoryNodeKind.TOOL
                }
                TrajectoryUiProjection.nearestEntry(entries, fraction, lane)?.let(onSelect)
            }
        }.semantics { contentDescription = "轨迹耗时概览" }
    ) {
        val lanePositions = listOf(6.dp.toPx(), 25.dp.toPx(), 44.dp.toPx())
        lanePositions.forEach { y ->
            drawLine(guideColor, Offset(0f, y), Offset(size.width, y), 0.7.dp.toPx())
        }
        entries.forEach { entry ->
            val height = if (entry.node.kind == TrajectoryNodeKind.ASSISTANT) 9.dp.toPx() else 7.dp.toPx()
            val startX = entry.startFraction * size.width
            val naturalWidth = (entry.endFraction - entry.startFraction) * size.width
            val minimumWidth = if (entry.node.kind == TrajectoryNodeKind.INPUT) 2.dp.toPx() else 1.5.dp.toPx()
            val width = maxOf(minimumWidth, naturalWidth)
            val x = startX.coerceIn(0f, (size.width - width).coerceAtLeast(0f))
            val y = trajectoryLaneY(entry.node.kind).dp.toPx() - height / 2
            drawRoundRect(
                color = trajectoryColor(entry.node.kind, isDark),
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
        }
    }
}

@Composable
private fun TrajectoryTurnHeader(turn: Int) {
    val isDark = LocalPiDark.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Turn $turn",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Box(
            Modifier.weight(1f).height(1.dp).background(
                if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)
            )
        )
    }
}

@Composable
private fun TrajectoryRow(
    row: TrajectoryDisplayRow,
    highlighted: Boolean,
    onSelect: () -> Unit,
    onRequest: (TrajectoryNode) -> Unit
) {
    val node = row.node
    val isDark = LocalPiDark.current
    val color = trajectoryColor(node.kind, isDark)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) PiColors.Ocean.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(width = 10.dp, height = 20.dp)
                    .clickable(
                        enabled = row.request != null,
                        role = Role.Button
                    ) { row.request?.let(onRequest) }
                    .semantics {
                        contentDescription = row.request?.request?.number?.let { "打开 Request #$it" }
                            ?: "${trajectoryLabel(node.kind)} 轨迹节点"
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(if (row.request != null) 10.dp else 7.dp)
                        .background(
                            if (row.request != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else color,
                            CircleShape
                        )
                )
            }
            Box(
                Modifier.width(if (isDark) 1.5.dp else 1.dp).height(22.dp)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.14f)
                    )
            )
        }
        Row(
            modifier = Modifier.weight(1f).heightIn(min = 20.dp)
                .clickable(role = Role.Button, onClick = onSelect),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                trajectoryLabel(node.kind),
                modifier = Modifier.background(
                    color.copy(alpha = if (isDark) 0.24f else 0.10f),
                    RoundedCornerShape(6.dp)
                ).border(
                    0.7.dp,
                    color.copy(alpha = if (isDark) 0.38f else 0.14f),
                    RoundedCornerShape(6.dp)
                ).padding(horizontal = 6.dp, vertical = 3.dp),
                color = color,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
            if (node.kind != TrajectoryNodeKind.INPUT && node.kind != TrajectoryNodeKind.ASSISTANT) {
                Text(
                    node.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (node.subtitle.isNotEmpty()) {
                Text(
                    node.subtitle.replace('\n', ' '),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                "#${formatSequence(node.startSequence)}",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
            )
        }
    }
}

internal fun trajectoryColor(kind: TrajectoryNodeKind, isDark: Boolean): Color = when (kind) {
    TrajectoryNodeKind.INPUT -> PiColors.Ocean
    TrajectoryNodeKind.CONTEXT -> if (isDark) Color(0xFF30D158) else Color(0xFF34C759)
    TrajectoryNodeKind.REQUEST -> Color(0xFF8E8E93)
    TrajectoryNodeKind.ASSISTANT -> PiColors.Purple
    TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL -> PiColors.Orange
}

internal fun trajectoryLabel(kind: TrajectoryNodeKind): String = when (kind) {
    TrajectoryNodeKind.INPUT -> "USER"
    TrajectoryNodeKind.CONTEXT -> "CONTEXT"
    TrajectoryNodeKind.REQUEST -> "REQUEST"
    TrajectoryNodeKind.ASSISTANT -> "ASSISTANT"
    TrajectoryNodeKind.TOOL -> "TOOL"
    TrajectoryNodeKind.SUBTOOL -> "SUBTOOL"
}

private fun trajectoryLaneY(kind: TrajectoryNodeKind): Int = when (kind) {
    TrajectoryNodeKind.INPUT -> 6
    TrajectoryNodeKind.CONTEXT, TrajectoryNodeKind.REQUEST, TrajectoryNodeKind.ASSISTANT -> 25
    TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL -> 44
}

private fun formatSequence(sequence: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(sequence)
