package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.domain.SessionSummary
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉分组的纯逻辑：今天/昨天/更早的**本地日界**、缺时间戳、未来时间、组顺序与组内保序；
 * 以及与 `relativeTime` 的日词口径一致性（两处各有一份 localDayIndex 公式，防止漂移）。
 *
 * 日界按本地日差而不是 24 小时滚动窗口——这是最容易写错的一处：`now - 24h` 在下午跑
 * 仍落在昨天，但在凌晨跑就落到前天。
 */
class FineCatDrawerGroupingTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 固定「现在」：2026-03-10 12:00 CST。 */
    private val now: Long = LocalDateTime.of(2026, 3, 10, 12, 0)
        .toInstant(ZoneOffset.ofHours(8)).toEpochMilli()

    private fun epochSeconds(millis: Long): Double = millis / 1_000.0

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()

    private fun session(id: String, epochMillis: Long, cwd: String? = "/p/a") = SessionSummary(
        id = id,
        title = id,
        lastActivityEpochSeconds = epochSeconds(epochMillis),
        isRunning = false,
        hasUnread = false,
        cwd = cwd,
    )

    @Test
    fun todayBucketForSameLocalDay() {
        assertEquals(FineCatDrawerDayBucket.Today, drawerDayBucketOf(epochSeconds(at(2026, 3, 10, 0, 1)), now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Today, drawerDayBucketOf(epochSeconds(now - 1), now, timeZone))
        // 恰好 24h 前但跨过本地午夜的是昨天（日界按本地日差，不是 24h 滚动窗口）
        assertEquals(FineCatDrawerDayBucket.Yesterday, drawerDayBucketOf(epochSeconds(now - 24 * 3_600_000L), now, timeZone))
    }

    @Test
    fun yesterdayAndEarlierBuckets() {
        assertEquals(FineCatDrawerDayBucket.Yesterday, drawerDayBucketOf(epochSeconds(at(2026, 3, 9, 20, 12)), now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Yesterday, drawerDayBucketOf(epochSeconds(at(2026, 3, 9, 0, 0)), now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Earlier, drawerDayBucketOf(epochSeconds(at(2026, 3, 8, 23, 59)), now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Earlier, drawerDayBucketOf(epochSeconds(at(2026, 2, 21, 8, 0)), now, timeZone))
    }

    @Test
    fun missingAndNegativeTimestampsGoToEarlier_notFabricatedAsToday() {
        assertEquals(FineCatDrawerDayBucket.Earlier, drawerDayBucketOf(0.0, now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Earlier, drawerDayBucketOf(-5.0, now, timeZone))
    }

    @Test
    fun futureTimestampClampsToToday_notNegativeDayDifference() {
        // 设备时钟回拨/网关时钟超前：未来时间夹到"现在"，归今天，不出现负日差
        assertEquals(FineCatDrawerDayBucket.Today, drawerDayBucketOf(epochSeconds(now + 60_000), now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Today, drawerDayBucketOf(epochSeconds(now + 30L * 86_400_000L), now, timeZone))
    }

    @Test
    fun dayGroups_orderAndMembership_andEmptyBucketsOmitted() {
        val sessions = listOf(
            session("t1", at(2026, 3, 10, 9, 0)),
            session("y1", at(2026, 3, 9, 9, 0)),
            session("e1", at(2026, 1, 1, 9, 0)),
            session("t2", at(2026, 3, 10, 1, 0)),
        )
        val groups = drawerDayGroups(sessions, now, timeZone)
        assertEquals(
            "组顺序应为今天→昨天→更早，且空档不产出",
            listOf("今天", "昨天", "更早"),
            groups.map { it.label },
        )
        assertEquals("今天组", listOf("t1", "t2"), groups[0].sessions.map { it.id })
        assertEquals("昨天组", listOf("y1"), groups[1].sessions.map { it.id })
        assertEquals("更早组", listOf("e1"), groups[2].sessions.map { it.id })
        // 时间组的计数 = 组内行数（原型 `n: g.length`）
        assertEquals(listOf(2, 1, 1), groups.map { it.count })
    }

    @Test
    fun dayGroups_allEmptyYieldsNoGroups() {
        assertTrue(drawerDayGroups(emptyList(), now, timeZone).isEmpty())
    }

    @Test
    fun pinnedGroupComesFirstWithoutDuplicatingDateGroups() {
        val sessions = listOf(
            session("t1", at(2026, 3, 10, 9, 0)),
            session("y1", at(2026, 3, 9, 9, 0)),
            session("t2", at(2026, 3, 10, 1, 0)),
            session("e1", at(2026, 2, 1, 9, 0)),
        )
        val groups = drawerDayGroups(sessions, now, timeZone, listOf("e1", "t1", "e1", "missing"))
        assertEquals(listOf("置顶", "今天", "昨天"), groups.map { it.label })
        // 置顶按会话列表顺序排列，不按点击顺序或重复的偏好键排列。
        assertEquals(listOf("t1", "e1"), groups.first().sessions.map { it.id })
        assertEquals(listOf("t2"), groups[1].sessions.map { it.id })
        assertEquals(listOf("y1"), groups[2].sessions.map { it.id })
        assertEquals(sessions.map { it.id }.toSet(), groups.flatMap { it.sessions }.map { it.id }.toSet())
        assertEquals(sessions.size, groups.sumOf { it.sessions.size })
    }

    @Test
    fun pinnedPreferencesDoNotResurrectFilteredOrHiddenSessions() {
        val visible = listOf(session("visible", at(2026, 3, 10, 9, 0)))
        val groups = drawerDayGroups(visible, now, timeZone, listOf("hidden", "other-directory"))
        assertEquals(listOf("今天"), groups.map { it.label })
        assertEquals(listOf("visible"), groups.flatMap { it.sessions }.map { it.id })
        assertTrue(drawerDayGroups(emptyList(), now, timeZone, listOf("hidden")).isEmpty())
    }

    @Test
    fun projectGroups_countUsesAllSessionsWhileMembershipUsesVisible() {
        val all = listOf(
            session("a1", at(2026, 3, 10, 9, 0), "/p/alpha"),
            session("a2", at(2026, 3, 9, 9, 0), "/p/alpha"),
            session("b1", at(2026, 3, 8, 9, 0), "/p/beta"),
        )
        // a2 被搜索/隐藏排除，但计数仍算它在项目里的总数（原型 `n: p.n`）
        val groups = drawerProjectGroups(all, listOf(all[0], all[2]))
        assertEquals(listOf("alpha", "beta"), groups.map { it.label })
        assertEquals("alpha 计数应为项目总数 2（不是可见的 1）", 2, groups[0].count)
        assertEquals(listOf("a1"), groups[0].sessions.map { it.id })
        assertEquals(1, groups[1].count)
        // cwd 必须是**原始路径**（不是加长后的标题）：抽屉靠它做目录级隐藏
        assertEquals("/p/alpha", groups[0].cwd)
        assertEquals("/p/beta", groups[1].cwd)
    }

    @Test
    fun searchFindsTitleAndFullDirectoryAcrossSameBasenamesWithoutChangingOrder() {
        val all = listOf(
            session("a", now, "/work/alpha/shared").copy(title = "模型选择"),
            session("b", now, "/work/beta/shared").copy(title = "构建记录"),
            session("c", now, "/other/project").copy(title = "Alpha讨论"),
        )
        assertEquals(listOf("a", "c"), drawerSearchSessions(all, " ALPHA ").map { it.id })
        assertEquals(listOf("a", "b"), drawerSearchSessions(all, "shared").map { it.id })
        assertEquals(listOf("b"), drawerSearchSessions(all, "/work/beta/").map { it.id })
        assertEquals(listOf("a"), drawerSearchSessions(all, "模型选择").map { it.id })
        val groups = drawerProjectGroups(all, drawerSearchSessions(all, "alpha"))
        assertEquals(listOf("/work/alpha/shared", "/other/project"), groups.map { it.cwd })
        assertTrue(drawerSearchSessions(all, "不存在").isEmpty())
        assertEquals(all, drawerSearchSessions(all, "  "))
    }

    @Test
    fun searchCanFindUnspecifiedDirectoryWithoutMatchingOtherSessions() {
        val all = listOf(session("none", now, null), session("blank", now, " "), session("a", now, "/p/a"))
        assertEquals(listOf("none", "blank"), drawerSearchSessions(all, "未指定目录").map { it.id })
    }

    @Test
    fun dayGroupsHaveNoCwd_soOnlyProjectGroupsCanBeHiddenByDirectory() {
        val sessions = listOf(
            session("t", at(2026, 3, 10, 9, 0), "/p/alpha"),
            session("y", at(2026, 3, 9, 9, 0), "/p/beta"),
        )
        // 时间组不携带 cwd → 组标题不可长按隐藏目录（否则会把不同目录的会话混着藏）
        assertTrue(drawerDayGroups(sessions, now, timeZone).all { it.cwd == null })
    }

    @Test
    fun projectGroups_sameBasenameCwdIsOriginalPathNotDisambiguatedLabel() {
        val all = listOf(
            session("x", at(2026, 3, 10, 9, 0), "/work/alpha/shared"),
            session("y", at(2026, 3, 9, 9, 0), "/work/beta/shared"),
        )
        val groups = drawerProjectGroups(all, all)
        assertEquals(setOf("alpha/shared", "beta/shared"), groups.map { it.label }.toSet())
        // 标题被加长成两段，但 cwd 仍是完整原路径（隐藏黑名单按完整 cwd 存）
        assertEquals(
            setOf("/work/alpha/shared", "/work/beta/shared"),
            groups.mapNotNull { it.cwd }.toSet(),
        )
    }

    @Test
    fun projectGroups_blankCwdGroupHasNullCwd() {
        val all = listOf(session("n1", at(2026, 3, 10, 9, 0), null))
        val groups = drawerProjectGroups(all, all)
        assertEquals(1, groups.size)
        assertEquals("未指定目录", groups[0].label)
        // 空白 cwd 组不可组级隐藏（hideDirectory 拒绝空键）
        assertEquals(null, groups[0].cwd)
    }

    @Test
    fun projectGroups_sameBasenameDisambiguatedByParentSegment() {
        val all = listOf(
            session("x", at(2026, 3, 10, 9, 0), "/work/alpha/shared"),
            session("y", at(2026, 3, 9, 9, 0), "/work/beta/shared"),
        )
        val labels = drawerProjectGroups(all, all).map { it.label }
        assertEquals(setOf("alpha/shared", "beta/shared"), labels.toSet())
    }

    @Test
    fun projectGroups_blankCwdGroupedUnderUnknownLabel() {
        val all = listOf(
            session("n1", at(2026, 3, 10, 9, 0), null),
            session("n2", at(2026, 3, 9, 9, 0), "   "),
        )
        val groups = drawerProjectGroups(all, all)
        assertEquals(1, groups.size)
        assertEquals("未指定目录", groups[0].label)
        assertEquals(2, groups[0].count)
    }

    @Test
    fun drawerDayBucketAgreesWithRelativeTimeDayWords() {
        // 两处日界公式必须一致：relativeTime 说「昨天」的输入，分组必须是 Yesterday 档；
        // 说「N 天前」的输入必须是 Earlier 档（注意「前天」是 2 天前的独立文案，
        // 它同样属 Earlier 档——所以这里用 >2 天的样本，才能拿到「N 天前」措辞）。
        val yesterdaySample = epochSeconds(at(2026, 3, 9, 23, 30))
        val earlierSample = epochSeconds(at(2026, 3, 5, 0, 1))
        val twoDaysAgoSample = epochSeconds(at(2026, 3, 8, 0, 1))
        val todaySample = epochSeconds(at(2026, 3, 10, 0, 0))
        assertEquals(
            "昨天",
            com.finecat.pimobile.android.ui.relativeTime(yesterdaySample, now, timeZone),
        )
        assertEquals(FineCatDrawerDayBucket.Yesterday, drawerDayBucketOf(yesterdaySample, now, timeZone))
        assertTrue(
            "更早样本在 relativeTime 里应是 N 天前（实测 " +
                com.finecat.pimobile.android.ui.relativeTime(earlierSample, now, timeZone) + "）",
            com.finecat.pimobile.android.ui.relativeTime(earlierSample, now, timeZone).endsWith("天前"),
        )
        assertEquals(FineCatDrawerDayBucket.Earlier, drawerDayBucketOf(earlierSample, now, timeZone))
        // 「前天」也是 Earlier 档（两处口径在"档"上一致，文案不必相同）
        assertEquals("前天", com.finecat.pimobile.android.ui.relativeTime(twoDaysAgoSample, now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Earlier, drawerDayBucketOf(twoDaysAgoSample, now, timeZone))
        assertEquals(FineCatDrawerDayBucket.Today, drawerDayBucketOf(todaySample, now, timeZone))
    }
}
