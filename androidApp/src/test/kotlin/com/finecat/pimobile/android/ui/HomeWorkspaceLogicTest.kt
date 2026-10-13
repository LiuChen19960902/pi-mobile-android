package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.domain.SessionSummary
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首页品牌/工作区选择的纯逻辑：不组 Compose，只锁判定口径。
 * 手势与真实渲染留给设备回归（SessionDrawerScrollUiDeviceTest、真机试用）。
 */
class HomeWorkspaceLogicTest {
    private fun session(id: String, cwd: String?, activitySeconds: Double) = SessionSummary(
        id = id,
        title = "会话 $id",
        lastActivityEpochSeconds = activitySeconds,
        isRunning = false,
        hasUnread = false,
        cwd = cwd
    )

    @Test
    fun drawerLeavesMainContentVisibleOnNarrowWindows() {
        assertEquals(196.8f, piDrawerWidth(240.dp).value, 0.01f)
        assertEquals(295.2f, piDrawerWidth(360.dp).value, 0.01f)
        assertEquals(360f, piDrawerWidth(800.dp).value, 0.01f)
    }

    @Test
    fun homeBrandDoesNotOverlapHistoryOrPendingContent() {
        assertEquals(true, shouldShowHomeBrand(false, false, false, false, false))
        // 即使尚未绑定 session，也不能在加载或失败状态下面留下首页品牌。
        assertEquals(false, shouldShowHomeBrand(false, false, false, true, false))
        assertEquals(false, shouldShowHomeBrand(false, false, false, false, true))
        // 已选历史会话、已有消息或可见发送预览都属于内容页。
        assertEquals(false, shouldShowHomeBrand(true, false, false, false, false))
        assertEquals(false, shouldShowHomeBrand(false, true, false, false, false))
        assertEquals(false, shouldShowHomeBrand(false, false, true, false, false))
    }

    @Test
    fun brandCollapsesForKeyboardAndShortViewports() {
        // 键盘弹出：品牌一律收起，把高度让给输入区。
        assertEquals(
            HomeBrandLayout.Hidden,
            homeBrandLayout(viewportHeightDp = 800f, composerHeightDp = 160f, imeVisible = true)
        )
        // 正常竖屏剩余空间充足 → 完整品牌。
        assertEquals(
            HomeBrandLayout.Full,
            homeBrandLayout(viewportHeightDp = 800f, composerHeightDp = 160f, imeVisible = false)
        )
        // 横屏/分屏：剩余空间只够缩小档。
        assertEquals(
            HomeBrandLayout.Compact,
            homeBrandLayout(viewportHeightDp = 360f, composerHeightDp = 160f, imeVisible = false)
        )
        // 空间不够（含输入区变高：图片托盘、吸附条）→ 直接收起，不挤压列表。
        assertEquals(
            HomeBrandLayout.Hidden,
            homeBrandLayout(viewportHeightDp = 360f, composerHeightDp = 260f, imeVisible = false)
        )
    }

    @Test
    fun workspaceLabelUsesLastPathSegment() {
        assertEquals("pi-mobile-android", workspaceDisplayName("/workspace/pi-mobile-android"))
        assertEquals("pi-mobile-android", workspaceDisplayName("/workspace/pi-mobile-android/"))
        assertEquals("选择工作区", workspaceDisplayName(null))
        assertEquals("/", workspaceDisplayName("/"))
    }

    @Test
    fun recentWorkspacesFollowSessionRecencyAndHideHiddenDirs() {
        val sessions = listOf(
            session("s-1", "/repo/alpha", 300.0),
            session("s-2", "/repo/beta", 500.0),
            session("s-3", "/repo/alpha", 400.0),
            session("s-4", null, 900.0),
            session("s-5", "/repo/secret", 800.0)
        )
        val options = recentWorkspaceOptions(sessions, hiddenDirectoryPaths = listOf("/repo/secret"))
        // 组间按最新活动倒序（beta 500 > alpha 400），同目录只出一条；无 cwd 不成选项；
        // 本地隐藏的整组目录不从「最近工作区」漏回界面。
        assertEquals(listOf("/repo/beta", "/repo/alpha"), options.map { it.path })
        assertEquals(listOf("beta", "alpha"), options.map { it.label })

        // 本地隐藏的目录不会成为选项（连 limit 也不凑数）。
        assertEquals(
            emptyList<String>(),
            recentWorkspaceOptions(
                sessions,
                hiddenDirectoryPaths = listOf("/repo/alpha", "/repo/beta", "/repo/secret")
            ).map { it.path }
        )
        // limit 只为防面板超高；超额部分整条掉（secret 最新所以路在 beta 前）。
        assertEquals(
            listOf("/repo/secret", "/repo/beta"),
            recentWorkspaceOptions(sessions, hiddenDirectoryPaths = emptyList(), limit = 2)
                .map { it.path }
        )
    }

    @Test
    fun brandCopyDistinguishesUnconfiguredFromExplicitlyDeleted() {
        assertEquals(
            "FineCat" to "探索未至之境",
            homeBrandCopy(heroTitle = null, heroTagline = null)
        )
        assertEquals(
            "" to "",
            homeBrandCopy(heroTitle = "   ", heroTagline = "")
        )
        assertEquals("" to "自定义标语", homeBrandCopy("", "自定义标语"))
        assertEquals("我的名字" to "", homeBrandCopy("我的名字", ""))
        assertEquals(
            "我的名字" to "自定义标语",
            homeBrandCopy(heroTitle = "我的名字", heroTagline = "自定义标语")
        )
    }
}
