package com.finecat.pimobile.android

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.test.core.app.ActivityScenario
import com.finecat.pimobile.android.ui.PiProductApp
import com.finecat.pimobile.android.ui.PiTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.finecat.pimobile.android.BuildConfig

/**
 * 导航形态回归：用无存储 holder 验空白会话首页，会话列表住在左侧抽屉。
 * 窗口配置仍单独启动 MainActivity 核验，不用隔离宿主替代生产窗口断言。
 *
 * 旧版这里断言的是 workspace 首页（大标题 + 新建按钮 + 顶栏扫码）。那套界面已
 * 整体移进抽屉，所以断言换成新语义：品牌区/输入区/加号在首页，搜索/列表/设置
 * 在抽屉，配对与连接入口收进抽屉底部「更多」面板，首次未连接时也可达。
 */
@RunWith(AndroidJUnit4::class)
class AndroidUiParityDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var holder: AndroidSharedStateHolder? = null

    @After
    fun tearDown() {
        try {
            scenario?.close()
        } finally {
            holder?.close()
        }
    }

    private fun showHome() {
        val stateHolder = AndroidSharedStateHolder()
        holder = stateHolder
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { host ->
            host.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                activity.setContent {
                    PiTheme { PiProductApp(stateHolder = stateHolder, onPickImage = {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Suppress("DEPRECATION")
    fun activityUsesResizeInsteadOfPanningTheConversationAboveTheIme() {
        // 显式启动前置位；不读取遗留配对端点，也不改变生产默认连接行为。
        val previous = AndroidSharedStateHolder.skipStoredConnectForTest
        AndroidSharedStateHolder.skipStoredConnectForTest = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activityScenario ->
                activityScenario.onActivity { activity ->
                    val adjustMode = activity.window.attributes.softInputMode and
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                    assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjustMode)
                }
            }
        } finally {
            AndroidSharedStateHolder.skipStoredConnectForTest = previous
        }
    }

    @Test
    fun homeChromeExposesAccessibilitySemantics() {
        showHome()
        // 顶栏：左只有汉堡，右只有「+」；空白首页不出会话标题与三 tab。
        compose.onNode(hasTestTag("home-screen")).assertIsDisplayed()
        // 常驻背景在根宿主：首页/会话/设置同一份，不随发送或导航重建。
        compose.onNode(hasTestTag("pi-animated-background")).assertIsDisplayed()
        compose.onNode(hasContentDescription("会话列表")).assertIsDisplayed()
        compose.onNode(hasContentDescription("新会话")).assertIsDisplayed()
        compose.onNode(hasContentDescription("轨迹")).assertDoesNotExist()
        // 无存储 holder 给出确定的空白首页与默认品牌文案，不读取用户自定义 hero。
        compose.onNode(
            hasTestTag("home-brand-hit") and hasText("FineCat", substring = true)
        ).assertIsDisplayed().assertHasClickAction()
        compose.onNode(hasTestTag("home-brand-version") and
            hasText("v" + BuildConfig.VERSION_NAME, substring = true),
            useUnmergedTree = true
        ).assertIsDisplayed()
        // 输入区与它上方的工作区行。
        compose.onNode(hasTestTag("composer-input")).assertIsDisplayed()
        compose.onNode(hasTestTag("home-workspace-line")).assertIsDisplayed()
            .performClick()
        compose.onNode(hasTestTag("workspace-picker-sheet")).assertIsDisplayed()
        compose.onNode(hasText("选择其它目录")).assertIsDisplayed()
        compose.onNode(hasText("新建工作区")).assertIsDisplayed()
    }

    @Test
    fun drawerHoldsSessionListPairingAndSettings() {
        showHome()
        compose.onNode(hasContentDescription("会话列表")).performClick()
        compose.waitForIdle()
        compose.onNode(hasTestTag("drawer-session-list")).assertIsDisplayed()
        val drawerBounds = compose.onNode(hasTestTag("session-drawer")).fetchSemanticsNode().boundsInRoot
        val windowBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("抽屉右侧应留出主页面", drawerBounds.width < windowBounds.width * 0.90f)
        compose.onNode(hasTestTag("workspace-session-search")).assertIsDisplayed()
        // 首次未连接也不能堵死：配对与连接入口收在底部「更多」面板，先展开。
        compose.onNode(hasTestTag("drawer-more-menu")).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("扫码配对")).assertIsDisplayed()
        compose.onNode(hasText("手动配对")).assertIsDisplayed()
        compose.onNode(hasText("连接")).assertIsDisplayed()
        // 设置固定在抽屉底部，不随列表滚动。
        compose.onNode(hasTestTag("drawer-settings")).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Mobile Gateway")).assertIsDisplayed()
    }

    @Test
    fun systemBackClosesDrawerBeforeLeavingHome() {
        showHome()
        compose.onNode(hasContentDescription("会话列表")).performClick()
        compose.onNode(hasTestTag("drawer-settings")).assertIsDisplayed()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(hasTestTag("drawer-settings")).assertIsNotDisplayed()
        compose.onNode(hasTestTag("home-screen")).assertIsDisplayed()
    }

    @Test
    fun offlineNewSessionOpensComposerWithoutShowingAnInternalSubscribeError() {
        showHome()
        // 首页本身就是新会话页；点「+」也只是回空白页，不发 Pi 会话创建请求。
        compose.onNode(hasContentDescription("新会话")).performClick()
        compose.onNode(hasTestTag("composer-input")).assertIsDisplayed()
        compose.onNode(hasText("unsubscribe: not-connected")).assertDoesNotExist()
    }
}
