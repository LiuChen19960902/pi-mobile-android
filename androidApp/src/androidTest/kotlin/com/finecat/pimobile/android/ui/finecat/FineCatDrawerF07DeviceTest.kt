package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finecat.pimobile.android.AndroidAppGraph
import com.finecat.pimobile.android.AndroidSharedStateHolder
import com.finecat.pimobile.android.FakeCache
import com.finecat.pimobile.android.FakeClock
import com.finecat.pimobile.android.FakeCredentials
import com.finecat.pimobile.android.FakeNetwork
import com.finecat.pimobile.android.FakePreferences
import com.finecat.pimobile.android.FakeTransport
import com.finecat.pimobile.android.onMain
import com.finecat.pimobile.android.waitUntil
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** F07 合成 wire fixture 上的 r47 抽屉回归：完整cwd筛选、日期折叠、本地置顶与隐藏恢复。
 * 旧底栏/分组模式已经删除，检查随新原型迁移；真机或隔离设备执行结果须另行登记。
 */
@RunWith(AndroidJUnit4::class)
class FineCatDrawerF07DeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** 失败证据：语义树入 logcat + 失败瞬间截图（runCatching，不掩盖原失败）。 */
    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("FineCatF07") }
            runCatching {
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                saveShotNamed(bitmap, "FAIL-${description.methodName}.png")
            }
        }
    }

    private var graph: AndroidAppGraph? = null
    private var holder: AndroidSharedStateHolder? = null
    /** 跨重建保留的偏好（验证“重启后按持久化恢复”）。 */
    private var preferences = FakePreferences()

    /**
     * 用**同一份 preferences** 重建 graph（模拟重启）。
     *
     * 不能在这里再 `setContent`：`createAndroidComposeRule` 的 Activity 只允许设置一次
     * 内容（实测 `IllegalStateException: has already set content`）。所以重建后只断言
     * **状态回读**（holder 的黑名单来自持久化，不是旧实例内存），列表过滤由 UI 用例
     * 在重建前已验；这是“重启后黑名单仍在”的直接证据。
     */
    private fun rebuildWithSamePreferences(
        expectHidden: List<String> = emptyList(),
        expectHiddenDirectory: List<String> = emptyList(),
        requireHiddenSession: String? = null,
        requireHiddenDirectory: String? = null,
    ): AndroidSharedStateHolder {
        runCatching { holder?.close() }
        runCatching { graph?.gatewayScope?.cancel() }
        runCatching { graph?.applicationScope?.cancel() }
        holder = null
        graph = null
        val transport = FakeTransport()
        val newGraph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = preferences,
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            requestTimeoutOverride = 120_000L,
        )
        graph = newGraph
        val newHolder = newGraph.stateHolder
        holder = newHolder
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { newHolder.gatewayState.connection.name == "CONNECTED" }
        }
        transport.receive(sessionsFrame())
        runBlocking {
            waitUntil { newHolder.snapshot.sessions.size >= FIXTURE_TOTAL }
            // 黑名单回读是异步的（preferences.snapshots 流 → hiddenSessionsLoaded 护栏）
            requireHiddenSession?.let { id -> waitUntil { newHolder.hiddenSessionIds.contains(id) } }
            requireHiddenDirectory?.let { p ->
                waitUntil { newHolder.hiddenDirectoryPaths.contains(p) }
            }
        }
        expectHidden.forEach { id ->
            assertTrue("重建后会话 $id 应在黑名单里", newHolder.hiddenSessionIds.contains(id))
        }
        expectHiddenDirectory.forEach { p ->
            assertTrue("重建后目录 $p 应在黑名单里", newHolder.hiddenDirectoryPaths.contains(p))
        }
        return newHolder
    }

    @After
    fun tearDownGraph() {
        runCatching { holder?.close() }
        runCatching { graph?.gatewayScope?.cancel() }
        runCatching { graph?.applicationScope?.cancel() }
        holder = null
        graph = null
    }

    // r47：保留 F07 的 wire fixture，断言当前目录标签、置顶和列表内恢复。

    @Test
    fun directoryFilterKeepsDateGroupsAndUsesFullPaths() {
        setupSession()
        openDrawer()
        val originalCwd = holder!!.newSessionDirectoryPath
        directory(ANDROID_CWD)
        assertEquals(setOf("d-today-run", "d-today-unread"), allSessionIds())
        assertEquals(setOf("d-today-run", "d-today-unread"), sessionIdsInGroup("今天"))
        assertTrue(descendantTexts("d-today-run").contains("pi-mobile-android"))
        assertEquals("筛选不能改变新会话工作目录", originalCwd, holder!!.newSessionDirectoryPath)
        directory("/work/alpha/shared")
        assertEquals(setOf("d-same-a"), allSessionIds())
        directory("/work/beta/shared")
        assertEquals(setOf("d-same-b"), allSessionIds())
        compose.onNodeWithTag("finecat.drawerDirectory.all").performScrollTo().performClick()
        listTo("finecat.drawerSession.d-today-run")
        compose.onNodeWithTag("finecat.drawerSession.d-today-run").assertIsDisplayed()
    }

    @Test
    fun searchAndDirectoryAndFoldSurviveDrawerReopen() {
        setupSession()
        openDrawer()
        directory(ANDROID_CWD)
        search("未读会话")
        assertEquals(setOf("d-today-unread"), allSessionIds())
        compose.onNodeWithTag("finecat.drawerGroupHeader.day-Today").performClick()
        assertFalse(hasTag("finecat.drawerSession.d-today-unread"))
        compose.onNodeWithContentDescription("关闭抽屉").performClick()
        openDrawer()
        assertEquals("未读会话", searchText())
        assertFalse(hasTag("finecat.drawerSession.d-today-unread"))
        compose.onNodeWithTag("finecat.drawerGroupHeader.day-Today").performClick()
        assertEquals(setOf("d-today-unread"), allSessionIds())
        search("不存在的会话")
        assertTrue(hasTag("finecat.drawerEmpty"))
        listTo("finecat.drawerHiddenToggle")
        assertTrue("搜索空结果不影响隐藏入口", hasTag("finecat.drawerHiddenToggle"))
    }

    @Test
    fun searchFindsDirectoryTabsAndSessionsOutsidePreviousDirectory() {
        setupSession()
        assertTrue("对话标题不再显示网关绿点", compose.onAllNodesWithContentDescription("网关已连接", true).fetchSemanticsNodes().isEmpty())
        openDrawer()
        directory(ANDROID_CWD)
        search("/work/beta/")
        assertEquals(setOf("d-same-b"), allSessionIds())
        compose.onNodeWithTag("finecat.drawerDirectory./work/beta/shared").assertIsDisplayed()
        assertFalse(hasTag("finecat.drawerDirectory.$ANDROID_CWD"))
        assertTrue("新查询解除旧目录限制", compose.onNodeWithTag("finecat.drawerDirectory.all", true).fetchSemanticsNode().config[SemanticsProperties.Selected])
        directory("/work/beta/shared")
        assertEquals(setOf("d-same-b"), allSessionIds())
        search("同名甲")
        assertEquals(setOf("d-same-a"), allSessionIds())
        compose.onNodeWithTag("finecat.drawerDirectory./work/alpha/shared").assertIsDisplayed()
        search("")
        assertTrue(hasTag("finecat.drawerDirectory.$ANDROID_CWD"))
        assertTrue(hasTag("finecat.drawerDirectory./work/beta/shared"))
    }

    @Test
    fun pinMenuDoesNotNavigateAndPersistsAcrossGraphRebuild() {
        val transport = setupSession()
        openDrawer()
        directory(ANDROID_CWD)
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerPin.d-today-unread").performClick()
        compose.waitUntil(5_000) { "d-today-unread" in holder!!.pinnedSessionIds }
        runBlocking { withTimeout(5_000) { preferences.snapshots.first { "d-today-unread" in it.pinnedSessionIds } } }
        compose.waitForIdle()
        assertEquals("d-today-run", holder!!.snapshot.selectedSessionId)
        assertEquals(setOf("d-today-unread"), sessionIdsInGroup("置顶"))
        assertEquals(setOf("d-today-run"), sessionIdsInGroup("今天"))
        assertEquals(1, compose.onAllNodesWithTag("finecat.drawerSession.d-today-unread", true).fetchSemanticsNodes().size)
        assertEquals(0, transport.payloadsOfType("message").size)
        val rebuilt = rebuildWithSamePreferences()
        runBlocking { waitUntil { "d-today-unread" in rebuilt.pinnedSessionIds } }
        assertEquals(listOf("d-today-unread"), rebuilt.pinnedSessionIds)
    }

    @Test
    fun unpinReturnsToDateAndHiddenPinnedDoesNotLeakIntoFilter() {
        setupSession()
        openDrawer()
        directory(ANDROID_CWD)
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerPin.d-today-unread").performClick()
        compose.waitUntil(5_000) { "d-today-unread" in holder!!.pinnedSessionIds }
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerPin.d-today-unread").performClick()
        compose.waitUntil(5_000) { "d-today-unread" !in holder!!.pinnedSessionIds }
        compose.waitForIdle()
        assertFalse(hasTag("finecat.drawerGroupHeader.pinned"))
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerPin.d-today-unread").performClick()
        compose.waitUntil(5_000) { "d-today-unread" in holder!!.pinnedSessionIds }
        compose.waitForIdle()
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerHide.d-today-unread").performClick()
        compose.waitUntil(5_000) { "d-today-unread" in holder!!.hiddenSessionIds }
        compose.waitForIdle()
        assertFalse(hasTag("finecat.drawerSession.d-today-unread"))
        listTo("finecat.drawerHiddenToggle")
        compose.onNodeWithTag("finecat.drawerHiddenToggle").performClick()
        listTo("finecat.drawerSession.d-today-unread")
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerHide.d-today-unread").performClick()
        compose.waitUntil(5_000) { "d-today-unread" !in holder!!.hiddenSessionIds }
        assertTrue("恢复不丢置顶", "d-today-unread" in holder!!.pinnedSessionIds)
    }

    @Test
    fun hiddenEntrySurvivesDirectorySwitchAndRestoresOrphanAndDirectory() {
        setupSession()
        openDrawer()
        directory(GATEWAY_CWD)
        menu("d-yesterday")
        compose.onNodeWithTag("finecat.drawerHide.d-yesterday").performClick()
        compose.waitUntil(5_000) { "d-yesterday" in holder!!.hiddenSessionIds }
        onMain {
            holder!!.hideSession("orphan-id")
            holder!!.hideDirectory(GATEWAY_CWD)
        }
        compose.waitUntil(5_000) { GATEWAY_CWD in holder!!.hiddenDirectoryPaths }
        directory(GATEWAY_CWD)
        assertTrue(hasTag("finecat.drawerEmpty"))
        listTo("finecat.drawerHiddenToggle")
        compose.onNodeWithTag("finecat.drawerHiddenToggle").performClick()
        listTo("finecat.drawerHiddenSession.orphan-id.action")
        compose.onNodeWithTag("finecat.drawerHiddenSession.orphan-id.action").performClick()
        compose.waitUntil(5_000) { "orphan-id" !in holder!!.hiddenSessionIds }
        listTo("finecat.drawerHiddenDir.$GATEWAY_CWD.action")
        compose.onNodeWithTag("finecat.drawerHiddenDir.$GATEWAY_CWD.action").performClick()
        compose.waitUntil(5_000) { GATEWAY_CWD !in holder!!.hiddenDirectoryPaths }
        assertTrue("恢复目录不能清掉同目录下独立隐藏的会话", "d-yesterday" in holder!!.hiddenSessionIds)
        assertTrue("恢复目录不会清掉独立会话黑名单", "d-old-hidden" in holder!!.hiddenSessionIds)
        directory(ANDROID_CWD)
        listTo("finecat.drawerHiddenToggle")
        compose.onNodeWithTag("finecat.drawerHiddenToggle").assertIsDisplayed()
    }

    @Test
    fun directoryHideAndRestoreAllPersistWithoutDeletingSessions() {
        val transport = setupSession()
        onMain { holder!!.hideDirectory(GATEWAY_CWD) }
        runBlocking { withTimeout(5_000) { preferences.snapshots.first { GATEWAY_CWD in it.hiddenDirectoryPaths } } }
        openDrawer()
        directory(GATEWAY_CWD)
        assertEquals(emptySet<String>(), allSessionIds())
        listTo("finecat.drawerHiddenToggle")
        compose.onNodeWithTag("finecat.drawerHiddenToggle").performClick()
        listTo("finecat.drawerRestoreAll.action")
        compose.onNodeWithTag("finecat.drawerRestoreAll.action").performClick()
        compose.waitUntil(5_000) { holder!!.hiddenDirectoryPaths.isEmpty() && holder!!.hiddenSessionIds.isEmpty() }
        runBlocking { withTimeout(5_000) { preferences.snapshots.first { it.hiddenDirectoryPaths.isEmpty() && it.hiddenSessionIds.isEmpty() } } }
        assertEquals(FIXTURE_IDS.toSet(), holder!!.snapshot.sessions.map { it.id }.toSet())
        assertEquals(0, transport.payloadsOfType("message").size)
        val rebuilt = rebuildWithSamePreferences()
        assertTrue(rebuilt.hiddenDirectoryPaths.isEmpty())
        assertTrue(rebuilt.hiddenSessionIds.isEmpty())
    }

    @Test
    fun hiddenEntryAbsentWithoutHiddenItems() {
        setupSession(presetHidden = false)
        openDrawer()
        assertFalse(hasTag("finecat.drawerHiddenToggle"))
        onMain { FIXTURE_IDS.forEach { holder!!.hideSession(it) } }
        compose.waitUntil(5_000) { holder!!.hiddenSessionIds.size == FIXTURE_TOTAL }
        assertEquals(emptySet<String>(), allSessionIds())
        assertTrue(hasTag("finecat.drawerEmpty"))
        listTo("finecat.drawerHiddenToggle")
        compose.onNodeWithTag("finecat.drawerHiddenToggle").assertIsDisplayed()
    }

    @Test
    fun failedAssistantUsesMonochromeMarkAndReadDoesNotClearFailure() {
        val transport = setupSession()
        val now = System.currentTimeMillis() / 1000.0
        transport.receive("""{"kind":"event","sessionId":"d-today-unread","seq":9002,"time":$now,"event":{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"synthetic"}}}""")
        compose.waitUntil(5_000) { holder!!.snapshot.sessions.first { it.id == "d-today-unread" }.hasFailure }
        openDrawer()
        directory(ANDROID_CWD)
        assertTrue(descendantTexts("d-today-unread").contains("失败"))
        compose.onNodeWithTag("finecat.drawerMark.d-today-unread", true).assertIsDisplayed()
        val mark = tagBounds("finecat.drawerMark.d-today-unread")
        // 取圆内部，背景不能冒充白色叉号；同时验证深色圆与反色叉均实际绘制。
        val inner = Rect(
            mark.center.x - px(3f), mark.center.y - px(3f),
            mark.center.x + px(3f), mark.center.y + px(3f),
        )
        val bitmap = rootBitmap()
        assertTrue("失败圆内部应有单色墨色像素", countPixelsNear(bitmap, inner, lightText, 3) > 0)
        assertTrue("失败圆内部应有反色叉号像素", countPixelsNear(bitmap, inner, 0xFFFFFFFFL, 3) > 0)
        compose.onNodeWithTag("finecat.drawerSession.d-today-unread").performClick()
        assertTrue(holder!!.snapshot.sessions.first { it.id == "d-today-unread" }.hasFailure)
        assertFalse(holder!!.snapshot.sessions.first { it.id == "d-today-unread" }.hasUnread)
    }

    @Test
    fun topEntriesAndTitleActionsAreReachableWithNoFooter() {
        setupSession()
        openDrawer()
        assertFalse(hasTag("finecat.drawerBar"))
        val new = tagBounds("finecat.drawerNew")
        val panel = tagBounds("finecat.drawerPanel")
        assertDpEquals("新建宽度", new.width / density, panel.width / density - 24f)
        assertTrue(new.height / density >= 48f)
        directory(ANDROID_CWD)
        val title = requireDescendant("finecat.drawerSession.d-today-run", TITLE_RUN)
        val row = tagBounds("finecat.drawerSession.d-today-run")
        val mark = tagBounds("finecat.drawerMark.d-today-run")
        assertTrue("状态标记应在标题右侧", mark.left >= title.right)
        assertTrue("标记应相对双行会话行居中", abs(mark.center.y - row.center.y) <= px(2f))
        val action = tagBounds("finecat.drawerAction.d-today-run")
        assertTrue(action.width / density >= 48f && action.height / density >= 48f)
        assertTrue("三点按钮应相对双行会话行居中", abs(action.center.y - row.center.y) <= px(2f))
        val more = tagBounds("finecat.drawerMore.d-today-run")
        assertTrue("状态点与三点图标应组成6dp间隔的紧凑组", abs(more.left - mark.right - px(6f)) <= px(1f))
        assertTrue("状态点与三点图标应纵向齐平", abs(mark.center.y - more.center.y) <= px(1f))
        val status = requireDescendant("finecat.drawerSession.d-today-run", "进行中")
        val time = tagBounds("finecat.drawerTime.d-today-run")
        assertTrue("时间应紧跟状态文字", abs(time.left - status.right) <= px(2f))
        val bmp = rootBitmap()
        assertTextPaintedWith(bmp, title, lightText, "当前标题")
        assertTextPaintedWith(bmp, requireDescendant("finecat.drawerSession.d-today-unread", "未读会话"), lightText, "非当前标题")
        compose.onNodeWithTag("finecat.drawerTheme").performClick()
        compose.waitUntil(5_000) { holder!!.appearanceMode == "dark" }
        assertTrue(hasTag("finecat.drawerPanel"))
        compose.onNodeWithTag("finecat.drawerSettings").performClick()
        compose.onNodeWithTag("finecat.settings", true).assertIsDisplayed()
        assertFalse(hasTag("finecat.drawerPanel"))
    }

    @Test
    fun menuRenameAndOutsideAndBackDoNotSelectSession() {
        setupSession()
        openDrawer()
        directory(ANDROID_CWD)
        menu("d-today-unread")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(hasTag("finecat.drawerMenu.d-today-unread"))
        assertTrue(hasTag("finecat.drawerPanel"))
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerCopySessionId.d-today-unread").performClick()
        compose.waitForIdle()
        var copied: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            copied = activity.getSystemService(ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.text?.toString()
        }
        assertEquals("d-today-unread", copied)
        assertFalse(hasTag("finecat.drawerMenu.d-today-unread"))
        assertTrue(hasTag("finecat.drawerPanel"))
        assertEquals("复制不能切换会话", "d-today-run", holder!!.snapshot.selectedSessionId)
        menu("d-today-unread")
        compose.onNodeWithTag("finecat.drawerRename.d-today-unread").performClick()
        compose.waitUntil(5_000) { hasText("会话改名") }
        assertFalse(hasTag("finecat.drawerPanel"))
        assertEquals("d-today-run", holder!!.snapshot.selectedSessionId)
        compose.onNodeWithText("取消").performClick()
        openDrawer()
        val panel = tagBounds("finecat.drawerPanel")
        compose.onNodeWithTag("finecat.root").performTouchInput { click(Offset(panel.width + 5f, panel.height / 2f)) }
        assertFalse(hasTag("finecat.drawerPanel"))
        assertEquals("d-today-run", holder!!.snapshot.selectedSessionId)
    }

    @Test
    fun s9_newSessionEntryReturnsHomeKeepingWorkspace() {
        val transport = setupSession()
        compose.onNodeWithTag("finecat.viewSeg.tree").performClick()
        openDrawer()
        val cwd = holder!!.snapshot.sessions.first { it.id == "d-today-run" }.cwd
        compose.onNodeWithTag("finecat.drawerNew").performClick()
        compose.waitUntil(5_000) { holder!!.snapshot.selectedSessionId == null }
        assertTrue(hasTag("finecat.home"))
        assertEquals(cwd, holder!!.newSessionDirectoryPath)
        assertFalse(hasTag("finecat.drawerPanel"))
        assertEquals(0, transport.payloadsOfType("message").size)
    }

    @Test
    fun s9_newSessionWithoutDirectoryReturnsHomeWithoutPromptOrWireCreate() {
        val transport = setupSession()
        onMain { holder!!.selectFineCatDirectory(null); holder!!.prepareNewSession() }
        compose.waitUntil(5_000) { holder!!.snapshot.selectedSessionId == null }
        openDrawer()
        compose.onNodeWithTag("finecat.drawerNew").performClick()
        assertTrue(hasTag("finecat.home"))
        assertFalse(hasTag("finecat.drawerPanel"))
        assertTrue(hasText("待选择目录"))
        assertFalse(holder!!.transientNotice.orEmpty().contains("选择工作目录"))
        assertEquals(null, holder!!.fineCatNewSessionDirectoryPath)
        assertEquals(0, transport.payloadsOfType("message").size)
    }

    @Test
    fun s9_directoryPillOverridesCurrentSessionAndFirstMessageUsesItsCwd() {
        val transport = setupSession()
        onMain { holder!!.messageDraft = "目录继承诊断" }
        openDrawer()
        directory(GATEWAY_CWD)
        // 筛选本身不改变已有会话；点新建才把完整路径交给新会话。
        assertEquals("d-today-run", holder!!.snapshot.selectedSessionId)
        assertEquals(ANDROID_CWD, holder!!.snapshot.sessions.first { it.id == "d-today-run" }.cwd)
        compose.onNodeWithTag("finecat.drawerNew").performClick()
        compose.waitUntil(5_000) { holder!!.snapshot.selectedSessionId == null }
        assertTrue(hasTag("finecat.home"))
        assertFalse(hasTag("finecat.drawerPanel"))
        assertEquals(GATEWAY_CWD, holder!!.fineCatNewSessionDirectoryPath)
        assertEquals("目录继承诊断", holder!!.messageDraft)
        assertEquals(0, transport.payloadsOfType("message").size)

        compose.onNodeWithContentDescription("发送").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("message").isNotEmpty() } }
        val frame = transport.payloadsOfType("message").single()
        assertEquals(GATEWAY_CWD, frame["cwd"]?.jsonPrimitive?.contentOrNull)
        assertEquals(null, frame["sessionId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("目录继承诊断", frame["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(ANDROID_CWD, holder!!.snapshot.sessions.first { it.id == "d-today-run" }.cwd)
    }

    @Test
    fun s9_directoryPillOverridesHomeChoiceAndAllKeepsNewChoice() {
        val transport = setupSession()
        onMain {
            holder!!.prepareNewSession(ANDROID_CWD)
            holder!!.selectFineCatDirectory("/other/project")
            holder!!.messageDraft = "保留草稿"
        }
        compose.waitUntil(5_000) { holder!!.snapshot.selectedSessionId == null }
        // 相同末级标签必须按完整路径分别继承，第二次选择不能沿用第一次。
        listOf("/work/alpha/shared", "/work/beta/shared").forEach { cwd ->
            openDrawer()
            directory(cwd)
            compose.onNodeWithTag("finecat.drawerNew").performClick()
            compose.waitUntil(5_000) { !hasTag("finecat.drawerPanel") }
            assertEquals(cwd, holder!!.fineCatNewSessionDirectoryPath)
        }
        openDrawer()
        compose.onNodeWithTag("finecat.drawerDirectory.all").performScrollTo().performClick()
        compose.onNodeWithTag("finecat.drawerNew").performClick()
        compose.waitUntil(5_000) { !hasTag("finecat.drawerPanel") }
        assertEquals("/work/beta/shared", holder!!.fineCatNewSessionDirectoryPath)
        assertEquals("保留草稿", holder!!.messageDraft)
        assertEquals(0, transport.payloadsOfType("message").size)
    }

    @Test
    fun s9_unspecifiedDirectoryGroupKeepsCurrentSessionWorkspace() {
        val transport = setupSession()
        val frame = sessionsFrame().replace(
            "\"items\":[",
            "\"items\":[{\"id\":\"no-cwd\",\"name\":\"未指定目录\",\"messageCount\":1,\"running\":false},",
        )
        transport.receive(frame)
        runBlocking { waitUntil { holder!!.snapshot.sessions.any { it.id == "no-cwd" } } }
        openDrawer()
        directory("")
        compose.onNodeWithTag("finecat.drawerNew").performClick()
        compose.waitUntil(5_000) { holder!!.snapshot.selectedSessionId == null }
        assertEquals(ANDROID_CWD, holder!!.fineCatNewSessionDirectoryPath)
        assertEquals(0, transport.payloadsOfType("message").size)
    }

    private fun directory(cwd: String) {
        compose.onNodeWithTag("finecat.drawerDirectory.$cwd").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun listTo(tag: String) {
        compose.onNodeWithTag("finecat.drawerList").performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun menu(id: String) {
        listTo("finecat.drawerSession.$id")
        compose.onNodeWithTag("finecat.drawerAction.$id", true).performTouchInput { click() }
        compose.onNodeWithTag("finecat.drawerMenu.$id", true).assertIsDisplayed()
    }

    @Test
    fun matrix_390dp_dark_s9() {
        matrixDrawerShot(prefix = "f07-390-dark", dark = true, expectedViewportDp = 390f)
    }

    @Test
    fun matrix_360dp_fs10_light_s9() {
        matrixDrawerShot(
            prefix = "f07-360-fs10-light", dark = false,
            densityDpi = 520, expectedViewportDp = 360f,
        )
    }

    @Test
    fun matrix_360dp_fs10_dark_s9() {
        matrixDrawerShot(
            prefix = "f07-360-fs10-dark", dark = true,
            densityDpi = 520, expectedViewportDp = 360f,
        )
    }

    @Test
    fun matrix_360dp_fs13_light_s9() {
        matrixDrawerShot(
            prefix = "f07-360-fs13-light", dark = false,
            densityDpi = 520, fontScale = "1.3", expectedViewportDp = 360f,
        )
    }

    @Test
    fun matrix_360dp_fs13_dark_s9() {
        matrixDrawerShot(
            prefix = "f07-360-fs13-dark", dark = true,
            densityDpi = 520, fontScale = "1.3", expectedViewportDp = 360f,
        )
    }

    /**
     * 三环境亮暗矩阵：注入系统配置 → 打开抽屉 → 断言面板/遮罩真显示 → 截图 → 恢复。
     * 暗色走产品真入口 `holder.updateAppearanceMode("dark")`（外层套 `FineCatTheme` 无效，
     * 因为 FineCatApp 内层主题会覆盖外层主题）。
     *
     * **标称视口必须等于实际视口**：设备是 1170px 宽，360dp 需 density=520
     * （1170/(520/160)=360），而不是 440（那是设备的 physical density，得 425dp）。
     * 抄错密度会让 "360dp 窄屏"这一档实际没跑，而面板收窄分支只在屏宽 <367.4dp 时才生效。
     * 所以这里显式断言一次实测视口宽（用根节点 bounds ÷ density）。
     */
    private fun matrixDrawerShot(
        prefix: String,
        dark: Boolean,
        densityDpi: Int? = null,
        fontScale: String? = null,
        expectedViewportDp: Float? = null,
    ) {
        try {
            if (densityDpi != null) applyDeviceConfig(densityDpi, fontScale)
            setupSession(dark = dark)
            openDrawer()
            compose.onNodeWithTag("finecat.drawerPanel", useUnmergedTree = true).assertExists()
            // 标称视口 = 实际视口（否则这一档矩阵名不副实）
            if (expectedViewportDp != null) {
                val viewportDp = tagBounds("finecat.root").width / density
                assertDpEquals("$prefix 实际视口宽", viewportDp, expectedViewportDp)
                // 面板宽：原型 width:316px; max-width:86%。窄屏（<367.4dp）时按 86% 收窄，
                // 否则取 316dp。86% 分支只在 360dp 档真正执行，这里把**结果**也钉住，
                // 防止公式写错（如 0.88 / 忘了收窄）时只靠人看截图（审查 N6）。
                val expectedPanelDp = minOf(expectedViewportDp * 0.86f, 316f)
                assertDpEquals(
                    "$prefix 面板宽应为 min(视口×86%, 316dp)",
                    tagBounds("finecat.drawerPanel").width / density,
                    expectedPanelDp,
                )
            }
            // 三环境都要能看见分组标题与会话行（不是空抽屉）
            assertTrue("矩阵应渲染分组标题", hasText("今天"))
            assertTrue("矩阵应渲染会话行", hasTag("finecat.drawerSession.d-today-run"))
            saveShotNamed(rootBitmap(), "$prefix-s9.png")
        } finally {
            if (densityDpi != null) restoreDeviceConfig()
        }
    }

    /** 注入系统配置并轮询读回生效（F06 同口径：固定 sleep 在密度切换上有竞态）。 */
    private fun applyDeviceConfig(densityDpi: Int, fontScale: String?) {
        shell("wm density $densityDpi")
        if (fontScale != null) shell("settings put system font_scale $fontScale")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("wm density").contains("Override density: $densityDpi")) break
            Thread.sleep(200)
        }
        Thread.sleep(500)
        compose.waitForIdle()
    }

    private fun restoreDeviceConfig() {
        shell("wm density 480")
        shell("settings put system font_scale 1.0")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("wm density").contains("Override density: 480")) break
            Thread.sleep(200)
        }
        val densityOut = shell("wm density")
        val fontScale = shell("settings get system font_scale")
        assertTrue(
            "矩阵步骤结束必须恢复设备配置：density=[$densityOut] fontScale=[$fontScale]",
            densityOut.contains("Override density: 480") && fontScale.trim() == "1.0",
        )
    }

    // ------------------------------------------------------------------ 读取 helper

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tagBoundsOrNull(tag: String): Rect? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }?.boundsInRoot

    private fun tagBounds(tag: String): Rect =
        tagBoundsOrNull(tag) ?: throw AssertionError("节点 $tag 未找到或 bounds 为零")

    /** 节点子树内第一个含指定文本的后代 bounds；找不到即断言失败。 */
    private fun requireDescendant(tag: String, text: String): Rect =
        descendantBounds(tag, text) ?: throw AssertionError("节点 $tag 内未找到文本「$text」")

    private fun descendantBounds(tag: String, text: String): Rect? {
        val root = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .firstOrNull() ?: throw AssertionError("节点 $tag 未找到")
        var found: Rect? = null
        fun walk(n: SemanticsNode) {
            if (found == null &&
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
            ) {
                found = n.boundsInRoot
            }
            n.children.forEach { walk(it) }
        }
        walk(root)
        return found
    }

    /** 当前上屏的会话行 ID 集合（从 testTag 前缀取）。 */
    private fun allSessionIds(): Set<String> {
        val nodes = compose.onAllNodesWithTag("finecat.drawerList", useUnmergedTree = true)
            .fetchSemanticsNodes()
        val ids = mutableSetOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { tag ->
                if (tag.startsWith("finecat.drawerSession.")) ids += tag.removePrefix("finecat.drawerSession.")
            }
            n.children.forEach { walk(it) }
        }
        nodes.forEach { walk(it) }
        return ids
    }

    /** 某分组下的会话 ID 集合。组标题是 Row 容器、文本在子节点，所以用子树文本判定。 */
    private fun sessionIdsInGroup(label: String): Set<String> {
        val list = compose.onAllNodesWithTag("finecat.drawerList", useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull() ?: return emptySet()
        val ids = mutableSetOf<String>()
        var inTarget = false
        fun walk(n: SemanticsNode) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            if (tag != null && tag.startsWith("finecat.drawerGroupHeader.")) {
                inTarget = subtreeTexts(n).firstOrNull() == label
            } else if (tag != null && tag.startsWith("finecat.drawerSession.")) {
                if (inTarget) ids += tag.removePrefix("finecat.drawerSession.")
            }
            n.children.forEach { walk(it) }
        }
        walk(list)
        return ids
    }

    /** 某个会话行内的全部文本（用于“不得出现某词”这类反向断言）。 */
    private fun descendantTexts(sessionId: String): List<String> {
        val node = compose.onAllNodesWithTag("finecat.drawerSession.$sessionId", useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()
            ?: throw AssertionError("节点 finecat.drawerSession.$sessionId 未找到")
        return subtreeTexts(node)
    }

    /** 节点子树内全部文本（按遍历顺序）。 */    private fun subtreeTexts(n: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        fun collect(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { collect(it) }
        }
        collect(n)
        return out
    }

    /** 搜索框当前文本（语义树 EditableText）。 */
    private fun searchText(): String =
        compose.onNodeWithTag("finecat.drawerSearch", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun search(query: String) {
        compose.onNodeWithTag("finecat.drawerSearch").performTextClearance()
        compose.onNodeWithTag("finecat.drawerSearch").performTextInput(query)
        compose.waitForIdle()
    }

    private fun openDrawer() {
        compose.onNodeWithContentDescription("会话列表").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { hasTag("finecat.drawerSearch") }
    }

    // ------------------------------------------------------------------ 像素/几何 helper

    private val density: Float get() = compose.density.density

    private fun px(dp: Float): Int = (dp * density).roundToInt()

    private fun assertDpEquals(what: String, actualDp: Float, expectedDp: Float) {
        assertTrue("$what 应=$expectedDp dp ±2（实际 $actualDp）", abs(actualDp - expectedDp) <= 2f)
    }

    private fun rootBitmap(): Bitmap {
        var last: Throwable? = null
        repeat(3) {
            runCatching {
                return compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            }.onFailure { last = it }
            Thread.sleep(200)
            compose.waitForIdle()
        }
        throw AssertionError("rootBitmap 三次采集均失败", last)
    }

    /**
     * 文本区域内必须存在期望色像素（通道差 ≤3）。
     *
     * 比单点采样可靠：文字有字形缝隙与抗锯齿边，单点很容易落在背景上（实测踩过）。
     * 判别力仍然保留：颜色写错时该区域不可能出现期望色。
     */
    private fun assertTextPaintedWith(bmp: Bitmap, bounds: Rect, expected: Long, what: String) {
        val hit = countPixelsNear(bmp, bounds, expected, tol = 3)
        assertTrue(
            "$what：文本区域 $bounds 内未找到期望色 #${expected.toString(16)} 的像素（命中 $hit 个）",
            hit > 0,
        )
    }

    /** 文本区域内不得存在指定色像素（用于排除两行取到同一色的假阳性）。 */
    private fun assertTextNotPaintedWith(bmp: Bitmap, bounds: Rect, notExpected: Long, what: String) {
        val hit = countPixelsNear(bmp, bounds, notExpected, tol = 3)
        assertTrue("$what：文本区域 $bounds 内出现了不该有的色 #${notExpected.toString(16)}（$hit 个）", hit == 0)
    }

    private fun countPixelsNear(bmp: Bitmap, bounds: Rect, expected: Long, tol: Int): Int {
        val left = bounds.left.roundToInt().coerceIn(0, bmp.width - 1)
        val right = bounds.right.roundToInt().coerceIn(0, bmp.width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, bmp.height - 1)
        val bottom = bounds.bottom.roundToInt().coerceIn(0, bmp.height - 1)
        var hit = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val c = bmp.getPixel(x, y)
                if (abs(AndroidColor.red(c) - AndroidColor.red(expected.toInt())) <= tol &&
                    abs(AndroidColor.green(c) - AndroidColor.green(expected.toInt())) <= tol &&
                    abs(AndroidColor.blue(c) - AndroidColor.blue(expected.toInt())) <= tol
                ) {
                    hit++
                }
            }
        }
        return hit
    }


    private fun saveShotNamed(bmp: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        File(dir, name).outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private var measureFileReady = false

    /** CAPTURE-MEASURE 取证行落盘（外部 files 根；verify-stage capture 按 mtime 拉取）。 */
    private fun saveMeasure(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line.trimEnd() + "\n")
    }

    /**
     * 非门控的取证备注行（写进同一份 capture-measure.txt，随用例 log 归档）。
     * 用于登记"超出容差但有登记例外"的实测偏差：这些值不能被写成 CAPTURE-MEASURE
     * 的 maxDeltaDp（那是容差门，会直接 FAIL），但也不能不记——否则偏差会被埋起来。
     */
    private fun saveNote(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line.trimEnd() + "\n")
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    // ------------------------------------------------------------------ fixture 装配

    private fun setupSession(dark: Boolean = false, presetHidden: Boolean = true): FakeTransport {
        val transport = FakeTransport()
        preferences = FakePreferences()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = preferences,
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            // 注入应答帧的 fixture 需要窗口 >> 模拟器抖动（F06 同口径）
            requestTimeoutOverride = 120_000L,
        )
        val stateHolder = graph!!.stateHolder
        holder = stateHolder
        compose.setContent { FineCatApp(stateHolder = stateHolder) }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        // 初始隐藏黑名单：一条更早的会话（用于恢复路径）。presetHidden=false 的变体
        // 用于验证“无隐藏项时入口必须缺席”。
        if (presetHidden) {
            onMain { stateHolder.hideSession("d-old-hidden") }
            runBlocking { waitUntil { stateHolder.hiddenSessionIds.contains("d-old-hidden") } }
        }
        transport.receive(sessionsFrame())
        runBlocking { waitUntil { stateHolder.snapshot.sessions.size >= FIXTURE_TOTAL } }
        // 未读是真事件驱动的（`hasUnread = state.selectedSessionId != record.sessionId`）：
        // 给 d-today-unread 发一条 session_info_changed（→ session/title），把它标成未读。
        // 两个坑：①不能用 agent_start，它会把 isRunning 也置 true，状态词会变成「进行中」；
        // ②事件 `time` 不能用 0.0——SessionListReducer 会把 lastActivityEpochSeconds 写成
        // 归一化后的 0，该会话直接掉进「更早」组（实测踩过）。用当前 epoch 秒。
        val nowEpochSeconds = System.currentTimeMillis() / 1000.0
        transport.receive(
            "{\"kind\":\"event\",\"sessionId\":\"d-today-unread\",\"seq\":9001," +
                "\"time\":$nowEpochSeconds," +
                "\"event\":{\"type\":\"session_info_changed\",\"sessionInfo\":{\"name\":\"未读会话\"}}}",
        )
        runBlocking {
            waitUntil { stateHolder.snapshot.sessions.firstOrNull { it.id == "d-today-unread" }?.hasUnread == true }
        }
        val appearance = if (dark) "dark" else "light"
        onMain { stateHolder.updateAppearanceMode(appearance) }
        runBlocking { waitUntil { stateHolder.appearanceMode == appearance } }
        onMain { stateHolder.selectSession("d-today-run") }
        runBlocking { waitUntil { stateHolder.snapshot.selectedSessionId == "d-today-run" } }
        compose.waitForIdle()
        return transport
    }

    /**
     * sessions 帧：6 条 fixture 会话 + 1 条隐藏会话。`modified` 用相对"现在"的 ISO 串
     * 决定时间档（今天/昨天/更早），不写死绝对日期（否则换天跑就全错档）。
     */
    private fun sessionsFrame(): String {
        val now = LocalDateTime.now()
        fun iso(daysAgo: Long, hour: Int, minute: Int): String {
            val day = LocalDate.now().minusDays(daysAgo)
            return LocalDateTime.of(day, LocalTime.of(hour, minute))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toString()
        }
        fun item(id: String, cwd: String, title: String, modified: String, running: Boolean = false): String =
            "{\"id\":" + jsonString(id) + ",\"cwd\":" + jsonString(cwd) +
                ",\"name\":" + jsonString(title) + ",\"messageCount\":3,\"running\":$running" +
                ",\"modified\":" + jsonString(modified) + "}"

        val items = listOf(
            item("d-today-run", ANDROID_CWD, TITLE_RUN, iso(0, 10, 0), running = true),
            item("d-today-unread", ANDROID_CWD, "未读会话", iso(0, 9, 0)),
            item("d-same-a", "/work/alpha/shared", "同名甲", iso(0, 8, 0)),
            item("d-yesterday", GATEWAY_CWD, LONG_TITLE, iso(1, 20, 0)),
            item("d-old", GATEWAY_CWD, "更早会话", iso(9, 12, 0)),
            item("d-same-b", "/work/beta/shared", "同名乙", iso(11, 12, 0)),
            item("d-old-hidden", "/work/hidden", "最初就隐藏的会话", iso(20, 12, 0)),
        )
        // 未读由事件驱动：`hasUnread = state.selectedSessionId != record.sessionId`。
        // fixture 里给 d-today-unread 发一条 session_info_changed 把它标成未读（真实链路，
        // 不是直接改 holder 字段）。选它而不是 agent_start：后者会把 isRunning 也置 true。
        return "{\"kind\":\"sessions\",\"items\":[" + items.joinToString(",") + "]}"
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val ANDROID_CWD = "/home/dev/code/pi-mobile-android"
        private const val GATEWAY_CWD = "/home/dev/code/pi-mobile-gateway"
        private const val TITLE_RUN = "运行中的会话"
        /**
         * 超长标题（审查 m2：F07 验收要求覆盖「长标题/路径」，此前 fixture 最长只有 9 个汉字）。
         * 用于验证会话行标题不挤掉行尾动作、以及组标题不与右端计数重叠。
         */
        const val LONG_TITLE =
            "这是一个用于验证长标题在抽屉会话行与分组标题里都不会挤掉右侧计数与行尾动作的超长会话标题"
        private const val FIXTURE_TOTAL = 7
        private val FIXTURE_IDS = listOf(
            "d-today-run", "d-today-unread", "d-same-a", "d-yesterday", "d-old", "d-same-b", "d-old-hidden",
        )

        val HELLO =
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","abort","history","state","models","name"]}"""

        // 亮色 token（FineCatTheme lightFineCatColors）
        const val lightText = 0xFF1A1A1CL
        const val lightText2 = 0xFF63636AL
        const val lightBrand = 0xFF2F5FE0L
        const val lightOk = 0xFF1A9A6CL
    }
}
