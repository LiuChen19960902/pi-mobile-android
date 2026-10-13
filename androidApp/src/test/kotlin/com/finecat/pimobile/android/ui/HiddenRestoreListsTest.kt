package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.domain.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隐藏恢复条目的真源是持久化黑名单；catalog 只补标题，不决定条目存在性。 */
class HiddenRestoreListsTest {

    private fun summary(
        id: String,
        title: String,
        cwd: String? = "/repo/demo"
    ): SessionSummary = SessionSummary(
        id = id,
        title = title,
        lastActivityEpochSeconds = 0.0,
        isRunning = false,
        hasUnread = false,
        cwd = cwd
    )

    @Test
    fun `重启刚加载隐藏记录而 catalog 为空时保留完整ID与占位标题`() {
        val result = buildHiddenRestoreLists(
            hiddenSessionIds = listOf("abc12345-def6-7890-abcd-ef0123456789"),
            sessions = emptyList(),
            hiddenDirectoryPaths = listOf("/repo/demo")
        )
        assertEquals(
            listOf(
                HiddenSessionEntry(
                    id = "abc12345-def6-7890-abcd-ef0123456789",
                    displayTitle = HiddenSessionEntry.FALLBACK_TITLE
                )
            ),
            result.sessionEntries
        )
        assertEquals(listOf("/repo/demo"), result.directoryPaths)
    }

    @Test
    fun `catalog 只含部分隐藏会话时缺失的用占位标题且数量不缩水`() {
        val result = buildHiddenRestoreLists(
            hiddenSessionIds = listOf("id-1", "id-2", "id-3"),
            sessions = listOf(summary("id-2", "真标题")),
            hiddenDirectoryPaths = emptyList()
        )
        assertEquals(
            listOf(
                HiddenSessionEntry("id-1", HiddenSessionEntry.FALLBACK_TITLE),
                HiddenSessionEntry("id-2", "真标题"),
                HiddenSessionEntry("id-3", HiddenSessionEntry.FALLBACK_TITLE)
            ),
            result.sessionEntries
        )
    }

    @Test
    fun `catalog 后来补齐标题时条目原位更新不重复`() {
        val before = buildHiddenRestoreLists(
            hiddenSessionIds = listOf("id-1", "id-2"),
            sessions = emptyList(),
            hiddenDirectoryPaths = emptyList()
        )
        val after = buildHiddenRestoreLists(
            hiddenSessionIds = listOf("id-1", "id-2"),
            sessions = listOf(summary("id-2", "补齐的标题")),
            hiddenDirectoryPaths = emptyList()
        )
        assertEquals(
            listOf(
                HiddenSessionEntry("id-1", HiddenSessionEntry.FALLBACK_TITLE),
                HiddenSessionEntry("id-2", HiddenSessionEntry.FALLBACK_TITLE)
            ),
            before.sessionEntries
        )
        assertEquals(
            listOf(
                HiddenSessionEntry("id-1", HiddenSessionEntry.FALLBACK_TITLE),
                HiddenSessionEntry("id-2", "补齐的标题")
            ),
            after.sessionEntries
        )
    }

    @Test
    fun `清空隐藏记录后所有条目归零`() {
        // restoreAll 清空的是两份黑名单本身；catalog 里仍有记录也不影响。
        val result = buildHiddenRestoreLists(
            hiddenSessionIds = emptyList(),
            sessions = listOf(summary("id-1", "标题")),
            hiddenDirectoryPaths = emptyList()
        )
        assertTrue(result.sessionEntries.isEmpty())
        assertTrue(result.directoryPaths.isEmpty())
    }

    @Test
    fun `隐藏目录不依赖 catalog 透传目录清单`() {
        val result = buildHiddenRestoreLists(
            hiddenSessionIds = emptyList(),
            sessions = emptyList(),
            hiddenDirectoryPaths = listOf("/repo/demo", "/repo/other")
        )
        assertEquals(listOf("/repo/demo", "/repo/other"), result.directoryPaths)
    }

    @Test
    fun `空白ID与目录不产生条目`() {
        val result = buildHiddenRestoreLists(
            hiddenSessionIds = listOf("", "id-1"),
            sessions = emptyList(),
            hiddenDirectoryPaths = listOf(" ", "/repo/demo")
        )
        assertEquals(listOf(HiddenSessionEntry("id-1", HiddenSessionEntry.FALLBACK_TITLE)), result.sessionEntries)
        assertEquals(listOf("/repo/demo"), result.directoryPaths)
    }
}
