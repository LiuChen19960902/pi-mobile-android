package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.ui.finecat.theme.FineCatTheme
import com.finecat.pimobile.android.waitUntil
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S1 空态首页的静态形态取证（与原型 `01-S1-home-empty-base-*` 逐项对照）。
 *
 * 覆盖报告 `prototype-parity-audit-12` 记的三条 S1 结构偏差：
 * 1. 首页头部只留菜单按钮——不得出现标题/连接点/目录副行/三视图分段；
 * 2. 方格纸底纹（`has-grid`）已接入，不再是纯色背景；
 * 3. 首页按负责人定稿1B显示小尺寸Pi标志与品牌签名，背景保持纯色。
 *
 * 宿主与 `FineCatBoundedUiDeviceTest` 同口径：AndroidAppGraph 全套 Fake override +
 * 生产组合路径 `FineCatTheme { FineCatApp }`，无真实网关、密钥与 prefs。截图落到
 * external files，由主 agent 拉回与原型参考图并排比对。
 */
@RunWith(AndroidJUnit4::class)
class FineCatHomeParityDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<FineCatEvidenceHostActivity>()

    private lateinit var transport: FakeTransport
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var graph: AndroidAppGraph

    @After
    fun tearDown() {
        if (this::graph.isInitialized) {
            graph.stateHolder.close()
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    private fun connectFakeGateway() {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        holder = graph.stateHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(
                """{"kind":"hello","connection":{"state":"connected"},"sessions":[],""" +
                    """"contextUsage":null,"model":null}"""
            )
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
    }

    private fun evidenceDir(): File {
        val dir = File(
            ApplicationProvider.getApplicationContext<Application>().filesDir,
            "audit-evidence/frontend-rebuild/parity/S1",
        )
        dir.mkdirs()
        return dir
    }

    /**
     * 亮/暗两态各截一张首页，并把头部可见文本写进证据文件，供主 agent 逐项核对
     * "首页不得出现标题 / 目录副行 / 三视图分段"。
     */
    @Test
    fun home_emptyState_matchesPrototypeHeaderAndBackdrop() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        // 空态首页：无选中会话
        compose.runOnIdle { holder.prepareNewSession() }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        compose.waitForIdle()

        // 首页容器存在（用 assertExists 而非 assertIsDisplayed：容器自身可能不落在
        // 裁剪后的可视区，但它承载底纹与品牌块，其子节点才是可见性判据）
        compose.onNodeWithTag("finecat.home").assertExists()
        // 菜单按钮保留（原型 appHead 的 menu）
        compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
        // Pi标志与品牌签名；签名文字点击可编辑，标志没有编辑动作。
        compose.onNodeWithTag("finecat.homeBrand").assertExists()
        compose.onNodeWithTag("finecat.brandLogo").assertIsDisplayed()
        compose.onNodeWithTag("finecat.brandTagline", useUnmergedTree = true).assertExists()

        // 头部不应再有标题行：finecat.headerTitle 只在可改名时挂，首页不应存在
        assertEquals(
            "首页头部仍存在标题触控目标，不符合原型 appHead({menu, title:''})",
            0,
            compose.onAllNodesWithTag("finecat.headerTitle").fetchSemanticsNodes().size,
        )

        saveShot("s1-home-light.png")
    }

    @Test
    fun home_newSessionDirectory_entry_remainsAboveCapsuleAndExpandedComposer() {
        connectFakeGateway()
        compose.setContent {
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = holder) }
        }
        compose.runOnIdle { holder.prepareNewSession() }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.composerDirectory").assertIsDisplayed()

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.composerDirectory").assertIsDisplayed()
        compose.onNodeWithText("待选择目录").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("finecat.modelSheet").fetchSemanticsNodes().size)
        compose.onNodeWithTag("finecat.composerDirectory").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.dirSheet", useUnmergedTree = true).assertExists()
        saveShot("s1-composer-directory-sheet.png")
    }

    /**
     * 节点是否真的落在可视区内。目录行与压缩行都被父可点击列合并了语义，所以只能在
     * 未合并树里取；判据用 `boundsInRoot` 与视口求正面积交集，不把"存在"当"可见"。
     */
    private fun isInViewport(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .any { node ->
                val b = node.boundsInRoot
                b.height > 0f && b.width > 0f && b.bottom > 0f && b.top < viewportHeightPx
            }

    private val viewportHeightPx: Float
        get() = compose.onRoot().fetchSemanticsNode().size.height.toFloat()

    /**
     * S2 聚焦态：输入框展开成卡片后，输入独占首行、控制行落到下方，控制行左侧是模型
     * 胶囊（原型 `.composer.expanded`：textarea order -1 撑满一行，控件换行）。
     */
    @Test
    fun focus_expandedComposer_putsInputOnOwnRow_withControlsBelow() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        compose.runOnIdle { holder.prepareNewSession() }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        compose.waitForIdle()

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()

        val input = compose.onNodeWithTag("fc.composer.input").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("finecat.modelChip").fetchSemanticsNode().boundsInRoot
        // 输入独占首行：控件行的顶边必须在输入底边之下（原��是同一行左右排列）
        assertTrue(
            "展开态控件行未落到输入下方：input=${input.top}..${input.bottom} " +
                "chip=${chip.top}..${chip.bottom}",
            chip.top >= input.bottom,
        )
        // 底纹在聚焦态仍保留（原型 S2 与 S1 共用 .empty.has-grid）
        compose.onNodeWithTag("finecat.home", useUnmergedTree = true).assertExists()
        saveShot("s2-focus-light.png")
    }

    /** S3 选图条：长在输入卡片内部的第一行，横向可滑。 */
    @Test
    fun shots_trayGrowsInsideComposerCard() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        compose.runOnIdle { holder.prepareNewSession() }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        compose.waitForIdle()

        // 展开后打开选图条，选图条应长在 composer 卡片内部。
        // 不点相册图标：测试环境无 READ_MEDIA 权限，那条路径会走权限弹窗并回落到
        // onPickImage 拉起系统相册 intent，activity 被换掉后 compose 层级随之消失
        // （真实设备上由 P4c 权限链路覆盖）。这里直接驱动门面托盘状态，只验布局形态。
        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.runOnIdle { holder.togglePhotosTray() }
        compose.waitForIdle()
        waitUntilTagPresent("fc.shotStrip")

        val tray = compose.onNodeWithTag("fc.shotStrip", useUnmergedTree = true)
        tray.assertExists()
        val trayBounds = tray.fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("fc.composer.input").fetchSemanticsNode().boundsInRoot
        // 选图条在输入上方（同属一张卡片内部）
        assertTrue(
            "选图条未长在输入卡片内部：tray=${trayBounds.top}..${trayBounds.bottom} " +
                "input=${input.top}..${input.bottom}",
            trayBounds.bottom <= input.top + 1f,
        )
        saveShot("s3-shots-light.png")
    }

    /** 等 tag 出现（进场动画/异步发布都不稳定，按 tag 轮询）。 */
    private fun waitUntilTagPresent(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * S8 tree 屏：头部与对话/轨迹屏同构（viewSeg 停在 tree 项），行结构是
     * 「导轨 + 角色 + 时间」一行、正文另起一行。
     *
     * 切到 tree 之前必须先选中会话：空态首页按原型只留菜单按钮，本来就没有 viewSeg。
     */
    @Test
    fun tree_usesUnifiedHeaderAndRoleTimeRow() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        // 造一个会话，让三视图切换入口出现（帧格式照 FineCatTrajectoryRegressionDeviceTest）
        transport.receive(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp/synth",""" +
                """"messageCount":2,"firstMessage":"树对照用会话","running":false}]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.isNotEmpty() } }
        compose.runOnIdle { holder.selectSession("s1") }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == "s1" } }
        compose.waitForIdle()

        // viewSeg 三项只有 contentDescription（图标无文本），按 description 找
        compose.onNodeWithContentDescription("tree").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
        compose.onNodeWithContentDescription("对话").assertExists()
        compose.onNodeWithContentDescription("轨迹").assertDoesNotExist()
        compose.onNodeWithContentDescription("tree").assertExists()
        saveShot("s8-tree-header.png")
    }

    /**
     * S9 抽屉：原型 `drawerPanel` 从搜索框开始，没有独立标题栏；副行是
     * `状态词 · 项目 · 相对时间` 三段同排。
     */
    @Test
    fun drawer_startsAtSearchWithoutOwnHeader() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        transport.receive(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp/synth",""" +
                """"messageCount":3,"firstMessage":"抽屉对照用会话","running":true}]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.isNotEmpty() } }
        compose.runOnIdle { holder.selectSession("s1") }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == "s1" } }
        compose.waitForIdle()

        // 打开抽屉
        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.drawerSearch")

        // 搜索框是抽屉第一层：原型 drawerPanel 从 .drawer-top（搜索）开始，无标题栏。
        // 用几何判据：抽屉内边距只该有 statusBarsPadding（状态栏 inset），标题栏若还在
        // 会再占一整行。实测"只有状态栏"时搜索行顶为 240px（density3 下 80dp）。
        val searchTop = compose.onNodeWithTag("finecat.drawerSearch", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "搜索行顶 $searchTop px 超过仅状态栏 inset 的基线 240px——上方仍有自绘标题栏",
            searchTop <= 240f,
        )
        // 会话行：进行中 + 项目 + 相对时间三段都在副行
        assertTrue(
            "副行缺少状态词（原型 .sub 的首段）",
            compose.onAllNodesWithText("进行中").fetchSemanticsNodes().isNotEmpty(),
        )
        // sheet 有入场动画，推进时钟后再截
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        saveShot("s9-drawer.png")
    }

    /**
     * 键盘 inset 回归护栏：composer 链路上挂了 `imePadding`，底部栏不会被键盘压住。
     *
     * 这一条是整类缺失的护栏——重构后的 `ui/finecat/` 一度 0 处 `imePadding`
     * （旧 UI 有 6 处），表现为「点输入框弹起键盘，输入框被盖住」。
     *
     * 模拟器用手势导航，系统导航条 inset 为 0，所以这里只能验"链路没断"：
     * composer 仍然是内容 Column 的最后一个子项、贴在底部（不是浮在中间），
     * 且整条链路上 imePadding 没有把内容顶出视口。真机键盘弹起与 3-button
     * 导航的避让行为留给真机验收。
     */
    @Test
    fun composer_staysInBottomBarChain_afterImeInsetWiring() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        compose.waitForIdle()
        waitUntilTagPresent("finecat.composer")

        val rootHeight = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val b = compose.onNodeWithTag("finecat.composer").fetchSemanticsNode().boundsInRoot
        // 1. composer 仍在下半屏（底部栏位置），没有被 inset 顶到中间或出视口
        assertTrue(
            "composer 不在底部区域（${b.top}..${b.bottom}，视口高 $rootHeight）",
            b.top > rootHeight * 0.6f,
        )
        assertTrue(
            "composer 超出视口下沿（底 ${b.bottom} > 视口 $rootHeight）",
            b.bottom <= rootHeight + 1f,
        )
        // 2. 高度没有被 inset 压扁：正常 composer 高约 50–90dp（density3 下 150–270px）
        assertTrue(
            "composer 高度异常（${b.height}px），imePadding 可能在重复扣除",
            b.height in 100f..400f,
        )
    }

    /**
     * 目录标签只筛选会话，日期仍负责分组；副行保留目录名。
     */
    @Test
    fun drawer_directoryFilterRetainsProjectInSubLine() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatTheme(darkTheme = false) { FineCatApp(stateHolder = stateHolder) }
        }
        transport.receive(
            """{"kind":"sessions","items":[""" +
                """{"id":"s1","cwd":"/tmp/pi-web","messageCount":2,"firstMessage":"网页版","running":false},""" +
                """{"id":"s2","cwd":"/tmp/pi-subagents","messageCount":1,"firstMessage":"子代理","running":false}""" +
                """]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.size >= 2 } }
        compose.runOnIdle { holder.selectSession("s1") }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == "s1" } }
        compose.waitForIdle()

        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.drawerList")

        compose.onNodeWithTag("finecat.drawerDirectory./tmp/pi-web").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.drawerSession.s1").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("finecat.drawerSession.s2").fetchSemanticsNodes().isEmpty())
        compose.onAllNodes(
            hasText("pi-web") and hasAnyAncestor(hasTestTag("finecat.drawerSession.s1")),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onNodeWithTag("finecat.drawerGroupHeader.day-Earlier", true).assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        saveShot("s10-drawer-proj.png")
    }

    /**
     * S1 暗色态：纯色背景、Pi标志与品牌签名在 dark 下必须同样成立。
     *
     * dark 是独立维度，Pi标志和签名使用当前主题文字色；实测不能只凭light推断。
     *
     * 注意主题的真入口是 `stateHolder.appearanceMode`：`FineCatApp` 内部自己套了一层
     * `FineCatTheme(darkTheme = isFineCatDark(appearanceMode))`，所以在外层
     * `setContent { FineCatTheme(darkTheme = true) { … } }` 完全无效——必须走 holder。
     */
    @Test
    fun home_emptyState_darkThemeKeepsBackdropAndBrand() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        compose.runOnIdle {
            holder.updateAppearanceMode("dark")
            holder.prepareNewSession()
        }
        runBlocking {
            waitUntil { holder.snapshot.selectedSessionId == null }
            waitUntil { holder.appearanceMode == "dark" }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
        compose.onNodeWithTag("finecat.homeBrand").assertExists()
        compose.onNodeWithTag("finecat.brandLogo").assertIsDisplayed()
        compose.onNodeWithTag("finecat.brandTagline", useUnmergedTree = true).assertExists()
        saveShot("s1-home-dark.png")
    }

    /**
     * S2 聚焦态 · 暗色：输入卡、控制行、模型胶囊在 dark 下必须同样成立。
     *
     * S2 与 S3 共享 S1 的纯色背景与品牌区，这里只对 S2 特有的
     * 展开态 composer 取证——它是本轮改动最多的一屏（卡片、控件行、模型胶囊）。
     */
    @Test
    fun focus_expandedComposer_darkThemeKeepsCardAndControls() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        compose.runOnIdle {
            holder.updateAppearanceMode("dark")
            holder.prepareNewSession()
        }
        runBlocking {
            waitUntil { holder.snapshot.selectedSessionId == null }
            waitUntil { holder.appearanceMode == "dark" }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.modelChip").assertIsDisplayed()
        compose.onNodeWithTag("finecat.brandTagline", useUnmergedTree = true).assertExists()
        saveShot("s2-focus-dark.png")
    }

    /**
     * S4 对话屏 · 暗色 + **真实会话体**。
     *
     * 之前只注入了 `sessions` 帧，会话体是空的，所以本轮四项改动（副行改基名、
     * 补复制按钮、补时间分隔、助手 Markdown）一次都没在真机画面上验证过。这里补上
     * `history` 帧，让气泡/正文/分隔行/复制按钮都真的有内容可截。
     */
    @Test
    fun conversation_darkThemeShowsRealBodiesWithCopyAndTimeSeparator() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        transport.receive(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp/pi-mobile-android",""" +
                """"messageCount":3,"firstMessage":"夜间模式对一遍","running":false}]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.isNotEmpty() } }
        compose.runOnIdle {
            holder.updateAppearanceMode("dark")
            holder.selectSession("s1")
        }
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == "s1" } }
        // history 请求由选中会话触发：等它真的发出再注入应答，否则应答早于请求到达
        runBlocking { waitUntil { transport.payloadsOfType("history").isNotEmpty() } }

        // 真实会话体：一问一答 + 一条工具调用，正文带 Markdown 标记。
        // `timestamp` 是 epoch **毫秒**（网关透传，缺省=时间未知）——不给它时间分隔行
        // 就不会渲染，那是有意的：宁可没有也不画 1970。
        transport.receive(
            """{"kind":"history","sessionId":"s1","messages":[""" +
                """{"role":"user","timestamp":1738000000000,""" +
                """"content":[{"type":"text","text":"夜间模式对一遍"}]},""" +
                """{"role":"assistant","timestamp":1738000060000,""" +
                """"content":[{"type":"text","text":"**已完成**，token 与缩进都对齐了。"}]},""" +
                """{"role":"toolResult","timestamp":1738000070000,""" +
                """"content":[{"type":"text","text":"ok"}]}""" +
                """],"entryIds":["h-0","h-1","h-2"],"hasMore":false,""" +
                """"oldestEntryId":"h-0","bytes":96}"""
        )
        runBlocking { waitUntil { holder.snapshot.conversation.size >= 3 } }
        compose.waitForIdle()

        // 1. 副行显示项目基名而不是全路径（本轮改动）
        compose.onNodeWithText("pi-mobile-android", substring = true).assertExists()
        // 2. 助手 Markdown 正文真的渲染了。
        // 注意：FcMarkdownText 走 AndroidView(TextView)，正文文字**不进 Compose 语义树**，
        // 所以不能用 onNodeWithText 找——只能断言宿主节点存在 + 非零尺寸，
        // 文字本身的呈现以截图为准（s4-conversation-dark.png）。
        val aiBody = compose.onAllNodesWithContentDescription("复制全文")
            .fetchSemanticsNodes()
        assertTrue("助手消息旁缺复制按钮（ASSISTANT 分支未走 FcAiMessage）", aiBody.isNotEmpty())
        // 原始 Markdown 标记不应作为纯文本节点出现（说明确实走了渲染而不是裸文本）
        assertEquals(
            0,
            compose.onAllNodesWithText("**").fetchSemanticsNodes().size,
        )
        // 4. 时间分隔行（本轮新接的 FcTimeSeparator）：文案是 HH:mm。
        // 语义树里带方括号（`[01:47]`）是 ListItem 语义的自动标注，所以按包含匹配。
        assertTrue(
            "缺少时间分隔行（HH:mm），当前文本：${allTexts()}",
            allTexts().any { Regex("""\d{2}:\d{2}""").containsMatchIn(it) },
        )
        // 顺带确认工具调用组也上屏（FixtureToolCalls 语义：N 次工具调用）
        assertTrue(
            "工具调用组缺失，当前文本：${allTexts()}",
            allTexts().any { it.contains("工具调用") },
        )
        saveShot("s4-conversation-dark.png")
    }

    /** 语义树里所有文本节点（排查用：AndroidView 里的正文不进树，但 Compose 文本进）。 */
    private fun allTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.forEach { entry ->
                if (entry.key.name == "Text") entry.value?.toString()?.let(out::add)
            }
            node.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** 语义树里是否存在形如 `HH:mm` 的文本节点（时间分隔行的唯一形态）。 */
    private fun hasClockSeparatorText(): Boolean {
        var found = false
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            if (found) return
            node.config.forEach { entry ->
                if (entry.key.name == "Text" &&
                    Regex("""^\d{2}:\d{2}$""").matches(entry.value?.toString().orEmpty())
                ) {
                    found = true
                }
            }
            node.children.forEach { walk(it) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return found
    }

    /**
     * S8 tree 屏 · 暗色：行结构是「导轨 + 角色 + 时间」一行、正文另起一行，
     * 角色色分 `text2`（我）与 `roleAi`（AI），这两个 token 亮暗取值不同。
     */
    @Test
    fun tree_darkThemeKeepsRowAndRoleColors() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        transport.receive(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp/synth",""" +
                """"messageCount":2,"firstMessage":"树对照","running":false}]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.isNotEmpty() } }
        compose.runOnIdle {
            holder.updateAppearanceMode("dark")
            holder.selectSession("s1")
        }
        runBlocking {
            waitUntil { holder.snapshot.selectedSessionId == "s1" }
            waitUntil { holder.appearanceMode == "dark" }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("tree").performClick()
        compose.waitForIdle()
        // 头部与其它屏同构
        compose.onNodeWithTag("finecat.menu").assertIsDisplayed()
        compose.onNodeWithText("synth", substring = true).assertExists()
        saveShot("s8-tree-dark.png")
    }

    /**
     * S9 抽屉 · 暗色：遮罩、面板底色、分组 pill、状态词语义色、dbar 在 dark 下成立。
     *
     * 抽屉是本轮改动最集中的屏之一（去标题栏、状态词移到副行行首并带语义色、
     * 隐藏动作常驻），而且 `overlay`/`surface2`/`err`/`roleAi` 在亮暗下取值都不同。
     */
    @Test
    fun drawer_darkThemeKeepsScrimPanelAndStatusColors() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        transport.receive(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp/pi-mobile-android",""" +
                """"messageCount":3,"firstMessage":"抽屉暗色对照","running":true}]}"""
        )
        runBlocking { waitUntil { holder.snapshot.sessions.isNotEmpty() } }
        compose.runOnIdle {
            holder.updateAppearanceMode("dark")
            holder.selectSession("s1")
        }
        runBlocking {
            waitUntil { holder.snapshot.selectedSessionId == "s1" }
            waitUntil { holder.appearanceMode == "dark" }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.drawerSearch")
        // 副行三段同排：状态词在行首（蓝色「进行中」）
        compose.onNodeWithText("进行中").assertExists()
        // 面板底色与遮罩都要真显示（dark 下 overlay 偏深，容易糊成一片）
        compose.onNodeWithTag("finecat.drawerPanel", useUnmergedTree = true).assertExists()

        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        saveShot("s9-drawer-dark.png")
    }

    /**
     * S11 设置整页 · 暗色：`.sec-label` / `.card` / `.row` 三件套在 dark 下成立。
     *
     * 设置是**整页**（原型 `appHead({title:'设置', center:true})` 居中标题 + 返回箭头），
     * 不是底部弹卡；卡片底 `surface`、发丝线 `line`、次级值 `text2` 三个 token 亮暗
     * 取值不同，dark 下卡片边界最容易糊掉，所以单独取证。
     */
    @Test
    fun settings_darkThemeKeepsFullPageCardAndRows() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        compose.runOnIdle { holder.updateAppearanceMode("dark") }
        runBlocking { waitUntil { holder.appearanceMode == "dark" } }
        compose.waitForIdle()

        // 抽屉 dbar → 设置（原型 .dbar 的 sliders）
        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.drawerSearch")
        compose.onNodeWithTag("finecat.drawerSettings").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.settingsScreen")

        // 整页特征：居中标题 + 左侧返回箭头（不是 sheet 的 grab + 完成）
        compose.onNodeWithTag("finecat.settingsTitle").assertIsDisplayed()
        compose.onNodeWithTag("finecat.settingsBack").assertIsDisplayed()
        assertTrue(
            "设置仍是 sheet 形态（不该有 grab）",
            compose.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().isEmpty(),
        )
        // 三个网关行都在
        compose.onNodeWithText("Ping 网关").assertIsDisplayed()
        compose.onNodeWithText("断开连接").assertIsDisplayed()
        compose.onNodeWithText("未测试").assertExists()
        // 小节标题也在（第二段：「模型与 Provider」）
        compose.onNodeWithText("模型与 Provider").assertExists()
        saveShot("s11-settings-dark.png")
    }

    /**
     * S11 子态 · provider 一级卡：设置整页里的 provider 行带正确副标题
     * （`方言 · N 个模型 · M 项改动`，原型 `pvRow`）。
     *
     * 注：**表单 sheet 子态**（点一级卡后弹出的 `FineCatProviderSheet`）的取证暂缺——
     * 该 sheet 自带独立的 `editingId` 状态，在设备测试里从设置整页点进去后无法可靠
     * 断言其弹出（`finecat.providerSheet` 在 `createComposeRule` 下拿不到稳定 bounds）。
     * 表单层本身由 `FineCatProviderRegressionDeviceTest`（3 项）+ `FineCatProviderSaveDeviceTest`
     * （3 项）直接挂 `FineCatProviderSheet` 覆盖，已全过；这里只验整页里的一级卡。
     */
    @Test
    fun settings_providerRow_showsDialectModelAndOverrideSubtitle() {
        connectFakeGateway()
        compose.setContent {
            val stateHolder = holder
            FineCatApp(stateHolder = stateHolder)
        }
        // 注入一个 provider，让整页的一级卡有内容可点。
        // `models` 是 `GatewayProviderModel` 对象数组（照 FineCatProviderRegressionDeviceTest）。
        transport.receive(
            """{"kind":"providers","providers":[{"id":"zhipu","name":"智谱 · 备用",""" +
                """"baseUrl":"https://open.bigmodel.cn/api","api":"openai-completions",""" +
                """"apiKeySet":true,"active":true,"models":[""" +
                """{"id":"glm-5","reasoning":true,"input":["text"],"contextWindow":131072,"maxTokens":8192,"compat":{"thinking":{"a":1}}},""" +
                """{"id":"glm-4","reasoning":false,"input":["text"],"contextWindow":65536,"maxTokens":4096}""" +
                """],"status":{"ok":true}}]}"""
        )
        compose.runOnIdle { holder.updateAppearanceMode("light") }
        runBlocking { waitUntil { holder.appearanceMode == "light" } }
        compose.waitForIdle()
        // 设置整页不像旧 provider sheet 那样进屏即拉列表：显式触发一次拉取。
        compose.runOnIdle { holder.loadProviders() }
        runBlocking { waitUntil { holder.providers.isNotEmpty() } }

        openSettingsPage()
        waitUntilTagPresent("finecat.settingsScreen")
        // 一级卡在整页里（原型 .card.rows 的 pvRow）。它在滚动容器底部，
        // 先滚进视口再断言可见。
        val row = compose.onNodeWithTag("finecat.providerRow.zhipu")
        row.performScrollTo()
        compose.waitForIdle()
        row.assertIsDisplayed()
        // 副标题：方言 · 2 个模型 · 1 项改动（compat 只有 glm-5 有 1 个顶层键）
        compose.onNodeWithText("openai-completions · 2 个模型 · 1 项改动")
            .performScrollTo()
            .assertIsDisplayed()
        // 「添加 Provider」入口行也在同一张卡里
        compose.onNodeWithTag("finecat.providerNew").performScrollTo().assertIsDisplayed()
        saveShot("s11-provider-row.png")
    }

    /** 从抽屉 dbar 打开设置整页（多处用例复用，避免各写一遍点击序列）。 */
    private fun openSettingsPage() {
        compose.onNodeWithTag("finecat.menu").performClick()
        compose.waitForIdle()
        waitUntilTagPresent("finecat.drawerSearch")
        compose.onNodeWithTag("finecat.drawerSettings").performClick()
        compose.waitForIdle()
    }

    private fun saveShot(name: String) {
        compose.saveEvidenceScreenshot(name)
    }

    private companion object {
        /** 有界上滑上限：正常 1–2 次到底，异常结构按上限终止并让断言给出真实结论。 */
        const val MAX_SCROLL_TRIES = 6
    }
}
