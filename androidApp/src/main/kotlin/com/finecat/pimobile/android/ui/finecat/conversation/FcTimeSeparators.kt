package com.finecat.pimobile.android.ui.finecat.conversation

import com.finecat.pimobile.android.ui.ConversationDisplayEntry
import com.finecat.pimobile.android.ui.displayRoundKey
import com.finecat.pimobile.shared.projection.ConversationItemKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 时间分隔按"显示回合"计算，不按每条助手消息：
 *
 * - 回合：一条用户消息起、到下一条用户消息前；会话开头没有用户消息时，开头那段自成一回合
 *   （例如历史分页只拉到了某回合的后半段）。
 * - 回合时间：用户消息的正时间戳优先；用户时间未知时取回合内第一条**已落定**助手消息的
 *   时间。流式中的助手片段（`stream-` 前缀）不计——它的时间随每个 delta 刷新，不是落定值。
 * - 两者都未知：该回合不显示分隔，也不更新"上一已知时间"。
 * - 首个已知时间回合显示；之后与上一已知回合相隔 ≥ 5 分钟，或本地日期变化时显示。
 * - 文案：同日 `HH:mm`，跨日 `MM-dd HH:mm`。"跨日"相对上一已知回合判断；首个已知回合
 *   没有比较对象，按同日写 `HH:mm`（批准原文未单列，这是按字面推出的口径）。
 */

/** 5 分钟阈值（批准值；≥ 即显示）。 */
internal const val TIME_SEPARATOR_GAP_SECONDS: Double = 5 * 60.0

private val SAME_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val CROSS_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 一个显示回合的时间候选（秒级 epoch；≤0 表示未知）。
 * @param anchorKey 分隔行挂在哪个显示回合上：回合首个显示回合的 [displayRoundKey]。
 */
internal data class TurnTime(
    val anchorKey: String,
    val userEpochSeconds: Double,
    val settledAssistantEpochSeconds: Double,
)

/** 按批准规则给每个回合算分隔文案；返回与 [turns] 等长的列表，null = 不显示。 */
internal fun timeSeparatorLabels(
    turns: List<TurnTime>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<String?> {
    var previousEpoch: Double? = null
    var previousDate: java.time.LocalDate? = null
    return turns.map { turn ->
        val epoch = turn.userEpochSeconds.takeIf { it > 0.0 }
            ?: turn.settledAssistantEpochSeconds.takeIf { it > 0.0 }
            ?: return@map null
        val time = Instant.ofEpochMilli((epoch * 1000).toLong()).atZone(zone)
        val date = time.toLocalDate()
        val label = when {
            previousEpoch == null -> time.format(SAME_DAY)
            date != previousDate -> time.format(CROSS_DAY)
            epoch - previousEpoch!! >= TIME_SEPARATOR_GAP_SECONDS -> time.format(SAME_DAY)
            else -> null
        }
        previousEpoch = epoch
        previousDate = date
        label
    }
}

/**
 * 把 [groupAssistantRounds][com.finecat.pimobile.android.ui.groupAssistantRounds] 的显示回合
 * 合成"用户消息起"的回合，并取各自的时间候选。
 */
internal fun turnTimes(rounds: List<List<ConversationDisplayEntry>>): List<TurnTime> {
    val turns = mutableListOf<TurnTime>()
    rounds.forEach { round ->
        val messages = round.mapNotNull { (it as? ConversationDisplayEntry.Message)?.item }
        val user = messages.firstOrNull { it.kind == ConversationItemKind.USER }
        if (user != null || turns.isEmpty()) {
            turns += TurnTime(
                anchorKey = displayRoundKey(round),
                userEpochSeconds = user?.epochSeconds?.takeIf { it > 0.0 } ?: 0.0,
                settledAssistantEpochSeconds = 0.0,
            )
        }
        if (turns.last().settledAssistantEpochSeconds <= 0.0) {
            messages.firstOrNull {
                it.kind == ConversationItemKind.ASSISTANT &&
                    !it.id.startsWith(STREAMING_ID_PREFIX) &&
                    it.epochSeconds > 0.0
            }?.let { settled ->
                turns[turns.lastIndex] = turns.last().copy(settledAssistantEpochSeconds = settled.epochSeconds)
            }
        }
    }
    return turns
}

/** 显示回合键 → 分隔文案（只含需要显示的回合）。生产与设备测试共用同一回合键。 */
internal fun timeSeparatorsByRoundKey(
    rounds: List<List<ConversationDisplayEntry>>,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<String, String> {
    val turns = turnTimes(rounds)
    return turns.zip(timeSeparatorLabels(turns, zone))
        .mapNotNull { (turn, label) -> label?.let { turn.anchorKey to it } }
        .toMap()
}

/** 共享投影里流式中条目的 id 前缀（`stream-text-…` 等；落定后被正式 id 替换）。 */
private const val STREAMING_ID_PREFIX = "stream-"
