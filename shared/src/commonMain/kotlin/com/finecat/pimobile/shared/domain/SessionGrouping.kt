package com.finecat.pimobile.shared.domain

/**
 * 首页"最近会话"的目录分组（纯展示分组，不修改会话状态）。
 *
 * 分组口径：
 * - 按 pi 会话的项目目录（cwd）聚合；cwd 缺失（本地刚建、远端尚未确认）归一组。
 * - 组间按组内最新活动时间倒序；组内保持传入顺序（调用方已按时间倒序）。
 * - 标题默认取目录名；仅对重名项逐段补父目录（`code/pi-mobile-android`），
 *   不重名的项保持短名；加到自身全部路径段为止。
 */
data class SessionGroup(
    /** 分组键：pi 会话的 cwd；null = 尚无目录信息。 */
    val cwd: String?,
    /** 组标题（目录名，必要时补足父目录段）。 */
    val label: String,
    val sessions: List<SessionSummary>
)

object SessionGrouping {
    /** cwd 缺失（或为空白）时的组标题。 */
    const val UNKNOWN_CWD_LABEL = "未指定目录"

    fun group(sessions: List<SessionSummary>): List<SessionGroup> {
        if (sessions.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String?, MutableList<SessionSummary>>()
        sessions.forEach { session ->
            // 空白 cwd 视同缺失：pi 只发绝对路径，空串属异常数据，不该造出空标题组。
            val key = session.cwd?.takeIf(String::isNotBlank)
            buckets.getOrPut(key) { mutableListOf() }.add(session)
        }
        val labels = disambiguatedLabels(buckets.keys.filterNotNull())
        return buckets
            .map { (cwd, items) ->
                SessionGroup(cwd = cwd, label = labels[cwd] ?: UNKNOWN_CWD_LABEL, sessions = items)
            }
            .sortedByDescending { group -> group.sessions.maxOf(SessionSummary::lastActivityEpochSeconds) }
    }

    /**
     * 只加长当前仍重名的标签，避免一组冲突带着其他目录一起变长。
     * 路径段已用完时不再加长；仅斜杠写法不同的同一目录不会让循环无法结束。
     */
    private fun disambiguatedLabels(cwds: List<String>): Map<String, String> {
        if (cwds.isEmpty()) return emptyMap()
        val depths = cwds.associateWith { 1 }.toMutableMap()
        val labels = cwds.associateWith { tailSegments(it, 1) }.toMutableMap()
        while (true) {
            val duplicates = labels.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            val toExpand = cwds.filter {
                labels[it] in duplicates && depths.getValue(it) < segmentCount(it)
            }
            if (toExpand.isEmpty()) return labels
            toExpand.forEach { cwd ->
                val depth = depths.getValue(cwd) + 1
                depths[cwd] = depth
                labels[cwd] = tailSegments(cwd, depth)
            }
        }
    }

    private fun segmentCount(path: String): Int =
        path.trimEnd('/').split('/').count(String::isNotEmpty)

    /** 取路径末 [depth] 段；不足时返回去掉尾斜杠的原路径。 */
    private fun tailSegments(path: String, depth: Int): String {
        val parts = path.trimEnd('/').split('/').filter(String::isNotEmpty)
        if (parts.isEmpty()) return path
        return parts.takeLast(depth).joinToString("/")
    }
}
