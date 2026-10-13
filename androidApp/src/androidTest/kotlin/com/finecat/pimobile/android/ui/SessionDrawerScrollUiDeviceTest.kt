package com.finecat.pimobile.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.finecat.pimobile.android.AndroidSharedStateHolder

/**
 * 会话抽屉的滚动与固定区回归（取代旧首页吸顶测试）。
 *
 * 抽屉对齐参考稿后结构固定：顶部大圆角搜索（不滚），中部会话列表
 * （唯一可滚段，weight 吃掉剩余高度），底部固定「更多 / 设置」——连接区
 * 收进「更多」展开面板，不再常驻。低高度（横屏/分屏/键盘）下只是列表变矮，
 * 操作区不会被巨型固定头挤出屏幕。
 * 这里锁的就是这个结构：长列表滚动、矮视口、搜索过滤时固定区仍在，列表仍到底。
 *
 * 界面使用无 graph 的 holder 与人工 sessions 帧；测试进程仍会初始化应用的
 * Application，运行时需使用隔离测试环境。未执行前不得写成真机手势已通过。
 */
class SessionDrawerScrollUiDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showDrawer(heightDp: Int = 640, count: Int = 30) {
        val holder = AndroidSharedStateHolder()
        val items = (0 until count).joinToString(",") {
            // modified 帧与 SharedMobileStore 演示帧同形；标题带唯一 token 便于搜索定位。
            """{"id":"sess-$it","cwd":"/repo/alpha","modified":"2026-09-01T07:36:16.694Z",""" +
                """"messageCount":2,"firstMessage":"Alpha 任务 $it token-$it","running":false}"""
        }
        holder.wirePayload = """{"kind":"sessions","items":[$items]}"""
        holder.submitWirePayload()
        compose.setContent {
            PiTheme {
                Box(Modifier.size(width = 360.dp, height = heightDp.dp)) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SessionDrawerContent(
                            stateHolder = holder,
                            onOpenSession = {},
                            onSettings = {},
                            onScanPairing = {},
                            onManualPairing = {},
                            onRename = {},
                            onShowHiddenSessions = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun isDisplayed(tag: String) =
        runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    /** 一直上滑直到目标行可见；滑到上限仍不可见即失败（不猜具体视口高度）。 */
    private fun scrollUntilVisible(tag: String, maxSwipes: Int = 20) {
        repeat(maxSwipes) {
            if (isDisplayed(tag)) return
            compose.onNodeWithTag("drawer-session-list").performTouchInput {
                swipeUp(durationMillis = 600)
            }
            compose.waitForIdle()
        }
        assertTrue("滑到上限仍看不到 $tag", isDisplayed(tag))
    }

    @Test
    fun longListScrollKeepsSearchAndBottomSettingsVisible() {
        showDrawer()
        compose.onNodeWithTag("workspace-session-search").assertIsDisplayed()
        compose.onNodeWithTag("drawer-settings").assertIsDisplayed()
        compose.onNodeWithTag("workspace-session-sess-0").assertIsDisplayed()

        scrollUntilVisible("workspace-session-sess-29")

        // 列表滚到底：固定区不被带走，末尾会话可达。
        compose.onNodeWithTag("drawer-settings").assertIsDisplayed()
        compose.onNodeWithTag("workspace-session-search").assertIsDisplayed()
    }

    @Test
    fun shortViewportKeepsControlsAndListStillReachesEnd() {
        // 旧首页 350dp 固定头在这种高度会把列表挤没；抽屉只压缩中间段。
        showDrawer(heightDp = 300, count = 8)
        compose.onNodeWithTag("drawer-settings").assertIsDisplayed()
        compose.onNodeWithTag("workspace-session-search").assertIsDisplayed()
        assertTrue(
            "默认面板收起时列表应留有可见高度",
            compose.onNodeWithTag("drawer-session-list").fetchSemanticsNode().boundsInRoot.height > 0f
        )

        // 连接/配对入口收进底部「更多」面板：先展开再断言，然后收起还原。
        compose.onNodeWithTag("drawer-more-menu").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("扫码配对")).assertIsDisplayed()
        compose.onNodeWithTag("drawer-more-menu").performClick()
        compose.waitForIdle()

        scrollUntilVisible("workspace-session-sess-7", maxSwipes = 80)
        compose.onNodeWithTag("drawer-settings").assertIsDisplayed()
    }

    @Test
    fun searchFiltersListAndClearingRestoresIt() {
        showDrawer()
        compose.onNodeWithTag("workspace-session-search-input").performTextInput("token-7")
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-session-sess-7").assertIsDisplayed()
        compose.onNodeWithTag("workspace-session-sess-0").assertDoesNotExist()
        compose.onNodeWithTag("drawer-settings").assertIsDisplayed()

        compose.onNodeWithTag("workspace-session-search-input").performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-session-sess-0").assertIsDisplayed()
    }

    @Test
    fun tappingSessionRowReportsItsId() {
        val opened = mutableListOf<String>()
        val holder = AndroidSharedStateHolder()
        holder.wirePayload =
            """{"kind":"sessions","items":[{"id":"sess-a","cwd":"/repo/a",""" +
                """"modified":"2026-09-01T07:36:16.694Z","messageCount":2,"firstMessage":"任务 A","running":false}]}"""
        holder.submitWirePayload()
        compose.setContent {
            PiTheme {
                Surface {
                    SessionDrawerContent(
                        stateHolder = holder,
                        onOpenSession = { opened += it },
                        onSettings = {},
                        onScanPairing = {},
                        onManualPairing = {},
                        onRename = {},
                        onShowHiddenSessions = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-session-sess-a").performClick()
        compose.waitForIdle()
        // 「选择会话后关抽屉并打开会话」的关抽屉动作在宿主层；这里锁住回调口径。
        assertTrue("点击会话行应回报该会话 id", opened == listOf("sess-a"))
    }

    @Test
    fun groupCollapseHidesRowsAndExpandsAgain() {
        showDrawer(count = 3)
        val headerTag = "workspace-session-group-/repo/alpha"
        compose.onNodeWithTag(headerTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(headerTag).assertIsDisplayed()
        compose.onNodeWithTag("workspace-session-sess-0").assertDoesNotExist()

        compose.onNodeWithTag(headerTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-session-sess-0").assertIsDisplayed()
    }
}
