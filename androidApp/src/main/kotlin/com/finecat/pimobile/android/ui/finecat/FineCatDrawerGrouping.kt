package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.domain.SessionGrouping
import com.finecat.pimobile.shared.domain.SessionSummary
import java.util.Calendar
import java.util.TimeZone

/**
 * 抽屉日期分组的日界档，与相对时间使用同一本地日期口径。
 *
 * 只有三档是有意的：原型 `.sess .sub` 的相对时间是自由文本（`2 小时前`/`前天`/
 * `17 天前`），组标题却是固定的三段。两处口径只在**日界**上必须一致，文案不必相同。
 */
enum class FineCatDrawerDayBucket(val label: String) {
    Today("今天"),
    Yesterday("昨天"),
    Earlier("更早"),
}

/** 折叠组或目录标签的数据。count保留内部统计，不在当前抽屉展示。
 * key用日期档或完整cwd，同名目录不撞key；组内保持调用方传入的会话顺序。
 */
data class FineCatDrawerGroup(
    val key: String,
    val label: String,
    val count: Int,
    val sessions: List<SessionSummary>,
    /** 分组的目录（项目组为完整 cwd；时间组与「未指定目录」组为 null）。
     *  目录级隐藏需要完整路径（黑名单按 cwd 存），标题可能是加长后的末段，不能反推。 */
    val cwd: String? = null,
)

/**
 * 本地日序数（与 [com.finecat.pimobile.android.ui.relativeTime] 内部的 `localDayIndex`
 * 同一公式）。**两处必须同步改**：这里算分组桶、那里算副行文案，日界一旦漂移，
 * 同一条会话会出现「副行写昨天、却分在更早组」的自相矛盾。
 */
internal fun drawerLocalDayIndex(epochMillis: Long, timeZone: TimeZone): Long {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
    val previousYear = calendar.get(Calendar.YEAR).toLong() - 1
    return previousYear * 365 +
        previousYear / 4 -
        previousYear / 100 +
        previousYear / 400 +
        calendar.get(Calendar.DAY_OF_YEAR)
}

/**
 * 单条会话的时间档。
 *
 * - 缺时间戳（网关未给 modified/created，还原为 0.0）与负值一律归「更早」：未知不等于
 *   今天，也不该造出一个假的「刚刚」（`relativeTime` 对同一输入返回空串，两者一致地
 *   不声称"新"）。
 * - 未来时间（设备时钟回拨）按 `coerceAtMost` 夹到"现在"，归今天，不出现负的日差。
 * - 日界按**本地日差**，不是 24 小时滚动窗口：恰好 24 小时前但跨过本地午夜的是昨天。
 */
internal fun drawerDayBucketOf(
    epochSeconds: Double,
    nowMillis: Long,
    timeZone: TimeZone,
): FineCatDrawerDayBucket {
    if (epochSeconds <= 0.0) return FineCatDrawerDayBucket.Earlier
    val eventMillis = (epochSeconds * 1_000).toDouble().toLong().coerceAtMost(nowMillis)
    val dayDifference = drawerLocalDayIndex(nowMillis, timeZone) - drawerLocalDayIndex(eventMillis, timeZone)
    return when {
        dayDifference <= 0L -> FineCatDrawerDayBucket.Today
        dayDifference == 1L -> FineCatDrawerDayBucket.Yesterday
        else -> FineCatDrawerDayBucket.Earlier
    }
}

/** 过滤后的可见会话先分置顶，其余归日期档，空档不产出。 */
internal fun drawerDayGroups(
    sessions: List<SessionSummary>,
    nowMillis: Long,
    timeZone: TimeZone,
    pinnedSessionIds: List<String> = emptyList(),
): List<FineCatDrawerGroup> =
    (sessions.filter { it.id in pinnedSessionIds }.takeIf { it.isNotEmpty() }?.let {
        listOf(FineCatDrawerGroup(key = "pinned", label = "置顶", count = it.size, sessions = it))
    }.orEmpty() + FineCatDrawerDayBucket.entries
        .map { bucket ->
            val rows = sessions.filter {
                it.id !in pinnedSessionIds && drawerDayBucketOf(it.lastActivityEpochSeconds, nowMillis, timeZone) == bucket
            }
            FineCatDrawerGroup(key = "day-${bucket.name}", label = bucket.label, count = rows.size, sessions = rows)
        }
        .filter { it.sessions.isNotEmpty() })

/** 同一搜索同时匹配会话标题和完整目录；空目录按标签“未指定目录”查找。 */
internal fun drawerSearchSessions(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
    val term = query.trim()
    if (term.isEmpty()) return sessions
    return sessions.filter { session ->
        session.title.contains(term, ignoreCase = true) ||
            (session.cwd?.takeIf(String::isNotBlank) ?: SessionGrouping.UNKNOWN_CWD_LABEL).contains(term, ignoreCase = true)
    }
}

/** 聚合完整cwd并消歧目录名，用作横滑目录标签；标签不负责会话的日期分组。
 * count仍统计全部会话，当前界面不展示；调用方传入全部会话保留隐藏目录的恢复路径。
 */
internal fun drawerProjectGroups(
    allSessions: List<SessionSummary>,
    visibleSessions: List<SessionSummary>,
): List<FineCatDrawerGroup> {
    if (visibleSessions.isEmpty()) return emptyList()
    val totalByCwd = allSessions.groupingBy { it.cwd?.takeIf(String::isNotBlank) }.eachCount()
    return SessionGrouping.group(visibleSessions).map { group ->
        val key = group.cwd?.takeIf(String::isNotBlank)
        FineCatDrawerGroup(
            key = "proj-${key ?: "none"}",
            label = group.label,
            count = totalByCwd[key] ?: group.sessions.size,
            sessions = group.sessions,
            cwd = key,
        )
    }
}
