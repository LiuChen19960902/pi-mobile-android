package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.conversation.timeSeparatorLabels
import com.finecat.pimobile.android.ui.finecat.conversation.timeSeparatorsByRoundKey
import com.finecat.pimobile.android.ui.finecat.conversation.turnTimes
import com.finecat.pimobile.android.ui.groupAssistantRounds
import com.finecat.pimobile.android.ui.makeConversationDisplayEntries
import com.finecat.pimobile.shared.projection.ConversationItem
import com.finecat.pimobile.shared.projection.ConversationItemKind
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DEC-F09-TIMESEP 纯函数口径（批准 fixture 期望分隔回合下标严格为 [0,3,4]）。
 * 走真实 makeConversationDisplayEntries → groupAssistantRounds 链，时区固定，结果与宿主无关。
 */
class FcTimeSeparatorsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private data class T(val user: LocalDateTime?, val assistants: List<LocalDateTime?>, val streaming: LocalDateTime? = null)

    private fun at(day: Int, h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 9, 30, h, m, s).plusDays(day.toLong())

    private fun epoch(t: LocalDateTime?) = t?.atZone(zone)?.toEpochSecond()?.toDouble() ?: 0.0

    private fun items(turns: List<T>, firstTurn: Int = 0, skipFirstUser: Boolean = false): List<ConversationItem> =
        turns.flatMapIndexed { offset, t ->
            val n = firstTurn + offset
            buildList {
                if (!(skipFirstUser && offset == 0)) {
                    add(ConversationItem("u$n", ConversationItemKind.USER, "你", "T$n 用户", epochSeconds = epoch(t.user)))
                }
                t.assistants.forEachIndexed { i, a ->
                    add(ConversationItem("a$n-$i", ConversationItemKind.ASSISTANT, "Pi", "T$n 助手$i", epochSeconds = epoch(a)))
                }
                t.streaming?.let {
                    add(ConversationItem("stream-text-$n", ConversationItemKind.ASSISTANT, "Pi", "T$n 流式", epochSeconds = epoch(it)))
                }
            }
        }

    /** 回合下标 → 文案（只列显示的回合），下标按 turnTimes 的回合序。 */
    private fun shown(items: List<ConversationItem>): Map<Int, String> {
        val rounds = groupAssistantRounds(makeConversationDisplayEntries(items))
        return timeSeparatorLabels(turnTimes(rounds), zone)
            .mapIndexedNotNull { i, label -> label?.let { i to it } }
            .toMap()
    }

    private val approved = listOf(
        T(at(0, 10, 0), listOf(at(0, 10, 0))),
        T(at(0, 10, 3), listOf(at(0, 10, 3))),
        T(null, listOf(at(0, 10, 6))),
        T(at(0, 10, 12), listOf(null)),
        T(at(1, 0, 1), listOf(at(1, 0, 1))),
        T(null, listOf(null)),
        T(at(1, 0, 4), listOf(null)),
    )

    @Test
    fun approvedFixture_separatorsExactly_0_3_4() {
        assertEquals(mapOf(0 to "10:00", 3 to "10:12", 4 to "10-01 00:01"), shown(items(approved)))
    }

    @Test
    fun threshold_isInclusiveAtFiveMinutes() {
        val turns = listOf(
            T(at(0, 9, 0, 0), listOf(null)),
            T(at(0, 9, 4, 59), listOf(null)), // 299s：不显示
            T(at(0, 9, 9, 59), listOf(null)), // 300s：显示
        )
        assertEquals(mapOf(0 to "09:00", 2 to "09:09"), shown(items(turns)))
    }

    @Test
    fun crossDay_showsEvenWithinFiveMinutes_withDateLabel() {
        val turns = listOf(T(at(0, 23, 58), listOf(null)), T(at(1, 0, 1), listOf(null)), T(at(1, 0, 3), listOf(null)))
        assertEquals(mapOf(0 to "23:58", 1 to "10-01 00:01"), shown(items(turns)))
    }

    @Test
    fun unknownTurn_neitherShowsNorMovesBaseline() {
        // 回合 1 全未知：若误把它当基线（或误清空基线），回合 2 会被判成"首个已知"而显示
        val turns = listOf(T(at(0, 10, 0), listOf(null)), T(null, listOf(null)), T(at(0, 10, 3), listOf(null)))
        assertEquals(mapOf(0 to "10:00"), shown(items(turns)))
    }

    @Test
    fun userTimeWins_overAssistantTime() {
        // 回合 0 用户 10:00、助手 10:20；回合 1 用户 10:22：按用户优先间隔 22 分钟→显示
        val turns = listOf(T(at(0, 10, 0), listOf(at(0, 10, 20))), T(at(0, 10, 22), listOf(null)))
        assertEquals(mapOf(0 to "10:00", 1 to "10:22"), shown(items(turns)))
    }

    @Test
    fun unknownUser_fallsBackToFirstSettledAssistant() {
        // 回合 1 用户未知，两条已落定助手 10:24/10:40：取第一条；回合 2 10:42 相对 10:24 显示
        val turns = listOf(
            T(at(0, 10, 22), listOf(null)),
            T(null, listOf(at(0, 10, 24), at(0, 10, 40))),
            T(at(0, 10, 42), listOf(null)),
        )
        assertEquals(mapOf(0 to "10:22", 2 to "10:42"), shown(items(turns)))
    }

    @Test
    fun streamingAssistant_notCountedUntilSettled() {
        val base = T(at(0, 10, 0), listOf(null))
        val streaming = listOf(base, T(null, emptyList(), streaming = at(0, 10, 10)))
        assertEquals(mapOf(0 to "10:00"), shown(items(streaming)))
        val settled = listOf(base, T(null, listOf(at(0, 10, 15))))
        assertEquals(mapOf(0 to "10:00", 1 to "10:15"), shown(items(settled)))
    }

    @Test
    fun prepend_recomputesAndDropsStaleLeadingSeparator() {
        // 新页从回合 2 的助手开始（用户消息在旧页）：开头那段自成首个已知回合
        val newer = items(approved.drop(2), firstTurn = 2, skipFirstUser = true)
        val rounds = groupAssistantRounds(makeConversationDisplayEntries(newer))
        assertEquals(
            mapOf("a2-0" to "10:06", "round:u3" to "10:12", "round:u4" to "10-01 00:01"),
            timeSeparatorsByRoundKey(rounds, zone),
        )
        // 补上旧页后：回合 2 归入用户锚点、相对 10:03 不足 5 分钟，助手锚点的分隔必须消失
        val full = groupAssistantRounds(makeConversationDisplayEntries(items(approved)))
        assertEquals(
            mapOf("round:u0" to "10:00", "round:u3" to "10:12", "round:u4" to "10-01 00:01"),
            timeSeparatorsByRoundKey(full, zone),
        )
    }

    @Test
    fun separatorsAnchorOnlyOnTurnFirstRound_neverOnAssistantRounds() {
        val rounds = groupAssistantRounds(makeConversationDisplayEntries(items(approved)))
        val keys = timeSeparatorsByRoundKey(rounds, zone).keys
        // 每回合至多一个键，且都挂在用户回合（不按助手消息强插）
        assertEquals(setOf("round:u0", "round:u3", "round:u4"), keys)
    }
}
