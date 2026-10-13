package com.finecat.pimobile.android.ui.finecat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.finecat.pimobile.android.waitUntil
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs

/**
 * /name 与新建/主题/恢复入口的「真实回调」设备测试：不用陈列宿主，走
 * FakeTransport + 真实 facade/projection 链路（同 AndroidAppGraphFakeIntegration
 * 的 fake graph 组装），断言的是状态与网关帧，不是标签存在性：
 * - 顶栏标题点击/抽屉长按 → 改名对话框；确认后 transport 收到 name 帧，标题只随
 *   session-named 应答刷新（离线/无应答不假成功）；取消不发帧、标题不变。
 * - 抽屉「新建」→ selectedSessionId 清空且工作区沿用（prepareNewSession(cwd)）。
 * - 抽屉主题入口 → appearanceMode 真实写回。
 * - 「全部恢复」行可点并清空两个黑名单。
 * - 首页品牌长按 → HeroCopyEditorDialog → heroTitle/heroTagline 真实持久值。
 */
@RunWith(AndroidJUnit4::class)
class FineCatEntryPointsDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 失败证据：完整语义树入 logcat + 失败瞬间截图到 external files。
     * 均 runCatching，不掩盖原失败；截图只落 synthetic fixture 状态。
     */
    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching {
                rule.onRoot(useUnmergedTree = true).printToLog("FineCatEntryPoints")
            }
            runCatching {
                val bitmap = rule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                val dir = File(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "finecat-entry-failures",
                ).apply { mkdirs() }
                File(dir, "${description.methodName}.png").outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
    }

    private lateinit var transport: FakeTransport
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var graph: AndroidAppGraph

    /** 释放 holder 内部资源后取消 scope，阻断跨测试的连接/协程残留。 */
    @After
    fun tearDownGraph() {
        if (this::graph.isInitialized) {
            if (this::holder.isInitialized) runCatching { holder.close() }
            graph.gatewayScope.cancel()
            graph.applicationScope.cancel()
        }
    }

    /** Dialog 窗口限定：composer/抽屉搜索框同样有 SetTextAction，必须按祖先排除。 */
    private fun inDialog(matcher: SemanticsMatcher): SemanticsMatcher =
        matcher and hasAnyAncestor(isDialog())

    /** 明确等待对话框完成布局，再保留 assertIsDisplayed（不降 assertExists/不 sleep）。 */
    private fun awaitDialogNode(matcher: SemanticsMatcher) {
        rule.waitUntil(5_000) {
            rule.onAllNodes(inDialog(matcher)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(inDialog(matcher)).assertIsDisplayed()
    }

    /** fake graph + 已连接 + fixture 会话上屏；每测试独立组装。 */
    private fun setUpFakeConnectedApp() {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
        )
        holder = graph.stateHolder
        runBlocking {
            holder.loadFixtureAndAwaitForTest()
            waitUntil { transport.specs.isNotEmpty() }
        }
        transport.open()
        transport.receive(
            "{\"kind\":\"hello\",\"authenticated\":true,\"protocol\":3,\"capabilities\":[\"sessions\",\"subscribe\",\"message\",\"abort\",\"history\",\"state\",\"models\",\"name\"]}"
        )
        runBlocking { waitUntil { holder.gatewayState.connection.name == "CONNECTED" } }
        rule.setContent { FineCatApp(stateHolder = holder) }
        rule.waitUntil(5_000) { holder.snapshot.sessions.isNotEmpty() }
    }

    private fun fixtureSessionId(): String = holder.snapshot.sessions.first().id

    private fun openDrawerViaDescription() {
        rule.onNodeWithContentDescription("会话列表").performClick()
        rule.waitForIdle()
    }

    @Test
    fun headerTitleClick_opensRenameDialog_confirmSendsNameFrameAndUpdatesTitle() {
        setUpFakeConnectedApp()
        val sessionId = fixtureSessionId()
        rule.onNodeWithTag("finecat.headerTitle").performClick()
        awaitDialogNode(hasText("会话改名"))

        // 输入框已有原标题（initial=title）：先清空再输入，断言的是纯新名；
        // matcher 限 Dialog 祖先——composer/搜索框同有 SetTextAction
        rule.onNode(inDialog(hasSetTextAction())).performTextClearance()
        rule.onNode(inDialog(hasSetTextAction())).performTextInput("重构计划")
        rule.onNode(inDialog(hasText("保存"))).performClick()

        // 真实回调：name 请求帧发出（REJECT_IF_BUSY lane，单条）
        rule.waitUntil(5_000) { transport.payloadsOfType("name").isNotEmpty() }
        val frame: JsonObject = transport.payloadsOfType("name").first()
        assertEquals(sessionId, frame.getValue("sessionId").jsonPrimitive.content)
        assertEquals("重构计划", frame.getValue("name").jsonPrimitive.content)
        // 单一数据流：应答未到前标题不假成功
        rule.onNodeWithTag("finecat.headerTitle").assertTextContains(fixtureSessionTitle())

        transport.receive(
            """{"kind":"session-named","sessionId":"$sessionId","name":"重构计划","isStreaming":false,"isCompacting":false}"""
        )
        rule.waitUntil(5_000) { fixtureSessionTitle() == "重构计划" }
        rule.onNodeWithTag("finecat.headerTitle").assertTextContains("重构计划")
        saveEvidenceShot("entry-rename-confirmed.png")
    }

    @Test
    fun renameCancel_sendsNoNameFrame_andKeepsTitle() {
        setUpFakeConnectedApp()
        rule.onNodeWithTag("finecat.headerTitle").performClick()
        awaitDialogNode(hasText("会话改名"))
        rule.onNode(inDialog(hasSetTextAction())).performTextInput("不该出现的名字")
        rule.onNode(inDialog(hasText("取消"))).performClick()

        rule.waitUntil(5_000) {
            rule.onAllNodes(inDialog(hasText("会话改名"))).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(transport.payloadsOfType("name").isEmpty())
        rule.onNodeWithTag("finecat.headerTitle").assertTextContains(fixtureSessionTitle())
        saveEvidenceShot("entry-rename-cancelled.png")
    }

    @Test
    fun drawerRowLongPress_opensRenameDialog() {
        setUpFakeConnectedApp()
        val sessionId = fixtureSessionId()
        openDrawerViaDescription()
        rule.onNodeWithTag("finecat.drawerSession.$sessionId")
            .performTouchInput { longClick() }
        awaitDialogNode(hasText("会话改名"))
        // 从抽屉进入的改名同样走真实回调：清空原标题后保存，发出 name 帧
        rule.onNode(inDialog(hasSetTextAction())).performTextClearance()
        rule.onNode(inDialog(hasSetTextAction())).performTextInput("改名自抽屉")
        rule.onNode(inDialog(hasText("保存"))).performClick()
        rule.waitUntil(5_000) { transport.payloadsOfType("name").isNotEmpty() }
        assertEquals(
            "改名自抽屉",
            transport.payloadsOfType("name").first().getValue("name").jsonPrimitive.content,
        )
    }

    @Test
    fun drawerNew_returnsHome_keepingWorkspace() {
        setUpFakeConnectedApp()
        val cwd = holder.snapshot.sessions.first().cwd
        openDrawerViaDescription()
        rule.onNodeWithTag("finecat.drawerNew").performClick()

        // 回首页且不丢工作区：prepareNewSession(cwd) 的沿用语义
        rule.waitUntil(5_000) { holder.snapshot.selectedSessionId == null }
        assertEquals(cwd, holder.newSessionDirectoryPath)
        rule.onNodeWithTag("finecat.home").assertIsDisplayed()
        saveEvidenceShot("entry-drawer-new-home.png")
    }

    /** 通过路径的内容取证截图（external files 根；verify-stage capture 按 mtime 归领）。 */
    private fun saveEvidenceShot(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        File(dir, name).outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun drawerTheme_updatesAppearanceMode() {
        setUpFakeConnectedApp()
        openDrawerViaDescription()
        rule.onNodeWithTag("finecat.drawerTheme").performClick()
        rule.onNodeWithTag("finecat.themeOption.dark").performClick()
        assertEquals("dark", holder.appearanceMode)
        rule.onNodeWithTag("finecat.themeOption.light").performClick()
        assertEquals("light", holder.appearanceMode)
        // system/light/dark 之外的写入路径由 holder 归一，这里只验三选真实可达
        rule.onNodeWithTag("finecat.themeOption.system").performClick()
        assertEquals("system", holder.appearanceMode)
    }

    @Test
    fun restoreAllRow_clearsHiddenSessionsAndDirectories() {
        setUpFakeConnectedApp()
        val sessionId = fixtureSessionId()
        holder.hideSession(sessionId)
        holder.hideSession("ghost-session")
        holder.hideDirectory("/gone/dir")
        rule.waitUntil(5_000) { holder.hiddenSessionIds.size == 2 }

        openDrawerViaDescription()
        rule.onNodeWithTag("finecat.drawerHiddenToggle").performClick()
        // 「全部恢复」是行标题，不是可点动作；实际动作是同一行右侧的
        // FcTextButton「恢复」。按行中心 y 匹配同一节点后点击它，不改生产凑过。
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("全部恢复").fetchSemanticsNodes().isNotEmpty()
        }
        val titleNode = rule.onNodeWithText("全部恢复").fetchSemanticsNode()
        val titleCenterY = titleNode.positionInRoot.y + titleNode.size.height / 2f
        val candidates = rule.onAllNodesWithText("恢复")
        val nodes = candidates.fetchSemanticsNodes()
        val rowIndex = nodes.indices.firstOrNull { idx ->
            val center = nodes[idx].positionInRoot.y + nodes[idx].size.height / 2f
            abs(center - titleCenterY) <=
                (titleNode.size.height + nodes[idx].size.height) / 2f
        } ?: error(
            "「全部恢复」同行未找到「恢复」动作节点；候选 ${nodes.size} 个，见 failureEvidence 截图/语义树",
        )
        candidates[rowIndex].performClick()

        rule.waitUntil(5_000) { holder.hiddenSessionIds.isEmpty() }
        assertTrue(holder.hiddenDirectoryPaths.isEmpty())
    }

    @Test
    fun homeBrandLongPress_opensEditor_savesHeroPreferences() {
        setUpFakeConnectedApp()
        // 回到空白首页（不依赖抽屉路径）
        holder.prepareNewSession()
        rule.waitUntil(5_000) { holder.snapshot.selectedSessionId == null }

        rule.onNodeWithTag("finecat.homeBrand").performTouchInput { longClick() }
        awaitDialogNode(hasText("自定义首页品牌"))

        // 编辑器初始值是内置默认文案：逐个清空再输入；只取 Dialog 祖先内的字段，
        // composer 的输入框被祖先过滤，不再靠全局下标
        val fields = rule.onAllNodes(inDialog(hasSetTextAction()))
        fields[0].performTextClearance()
        fields[0].performTextInput("我的 FineCat")
        fields[1].performTextClearance()
        fields[1].performTextInput("新的标语")
        rule.onNode(inDialog(hasText("保存"))).performClick()

        assertEquals("我的 FineCat", holder.heroTitle)
        assertEquals("新的标语", holder.heroTagline)
    }

    private fun fixtureSessionTitle(): String = holder.snapshot.sessions.first { it.id == fixtureSessionId() }.title
}
