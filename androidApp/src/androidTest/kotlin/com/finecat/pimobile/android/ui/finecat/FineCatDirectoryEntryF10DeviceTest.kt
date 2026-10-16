package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeUp
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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

/**
 * 目录入口的设备回归，验证新会话工作目录与失败恢复。
 *
 * 目录选择只决定**新会话**的 cwd；已有会话/轨迹/tree 的目录副行只读（不开选择器、
 * 不给"改本会话目录"）；目录读取失败、创建失败都保留原状态；新浏览器关闭保留最后成功位置，回根清空选择；不得只改显示串伪造 cwd。
 *
 * 判据：真实 wire 帧（`directories`/`message` 的 path/cwd/sessionId）+ holder 回读
 * （selectedSessionId、newSessionDirectoryPath、旧会话 cwd、草稿）。实测写成
 * `CAPTURE-ASSERT`，期望登记在 `tools/verify/asserts.tsv`，check 侧逐项严格比对。
 *
 * fixture：旧会话 `s-old`（cwd=/home/user/old-proj）；目录树 /home/user → {old-proj, new-proj}。
 */
@RunWith(AndroidJUnit4::class)
class FineCatDirectoryEntryF10DeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val failureEvidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("FineCatF10Cwd") }
            runCatching {
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                saveShotNamed(bitmap, "FAIL-${description.methodName}.png")
            }
        }
    }

    private var graph: AndroidAppGraph? = null
    private lateinit var holder: AndroidSharedStateHolder
    private lateinit var transport: FakeTransport

    @After
    fun tearDownGraph() {
        runCatching { holder.close() }
        runCatching { graph?.gatewayScope?.cancel() }
        runCatching { graph?.applicationScope?.cancel() }
        graph = null
    }

    // ------------------------------------------------------------------ 用例

    /** 目录统一放到composer；三视图标题不再重复，已有会话目录保持只读。 */
    @Test
    fun existingSession_directoryOnlyInComposer_notRepeatedInThreeHeaders() {
        connectWithOldSession()
        assertTrue(hasTag("finecat.composerDirectory"))
        compose.onNodeWithTag("finecat.composerDirectory").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(0, transport.payloadsOfType("directories").size)
        val views = listOf("chat" to null, "tree" to "tree")
        views.forEach { (name, segment) ->
            segment?.let { compose.onNodeWithContentDescription(it).performClick() }
            compose.waitForIdle()
            assertTrue("$name 不重复显示头部目录", subLineCandidates().isEmpty())
            assertFalse(hasTag("finecat.dirSheet"))
            assertEquals(OLD_CWD, oldSessionCwd())
            assertEquals(OLD, holder.snapshot.selectedSessionId)
        }
        assertEquals(0, transport.payloadsOfType("directories").size)
    }

    /** 首页 → 输入框上方目录入口 → 选 new-proj → 发首条：message 帧带新 cwd、无 sessionId；新会话归属以网关帧为准。 */
    @Test
    fun newSession_pickDirectory_firstMessageCreatesSessionWithChosenCwd() {
        connectWithOldSession()
        goHomeWithDraft()
        pickNewProjInSheet(screenshot = "f10-cwd-dirsheet-light.png")

        saveAssert("cwd.pick.afterSelect", stateLine())
        assertEquals("选择后只影响新会话落点", "selected=null;newDir=$NEW_CWD;draft=$DRAFT;oldCwd=$OLD_CWD;messages=0", stateLine())

        compose.onNodeWithContentDescription("发送").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("message").isNotEmpty() } }
        val frame = transport.payloadsOfType("message").single()
        val frameLine = "cwd=${frame["cwd"]?.jsonPrimitive?.contentOrNull};" +
            "sessionId=${frame["sessionId"]?.jsonPrimitive?.contentOrNull};" +
            "text=${frame["text"]?.jsonPrimitive?.contentOrNull}"
        saveAssert("cwd.pick.messageFrame", frameLine)
        assertEquals("首条 message 帧", "cwd=$NEW_CWD;sessionId=null;text=$DRAFT", frameLine)
        // 只有 message 帧带 cwd；没有任何改旧会话 cwd 的帧
        val cwdFrameTypes = transport.sentTypes.distinct().filter { type ->
            transport.payloadsOfType(type).any { it.containsKey("cwd") }
        }
        saveAssert("cwd.pick.cwdFrameTypes", cwdFrameTypes.joinToString(","))

        // 网关受理：sent 帧给出新会话 id → 自动选中；cwd 只在 sessions 帧到达后显示
        transport.receive("""{"kind":"sent","sessionId":"$NEW","mode":"followUp"}""")
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == NEW } }
        compose.waitForIdle()
        val headerBeforeSessions = subLineTextOrNone()
        transport.receive(sessionsFrame(includeNew = true))
        runBlocking { waitUntil { holder.snapshot.sessions.firstOrNull { it.id == NEW }?.cwd == NEW_CWD } }
        compose.waitForIdle()
        val nav = "selected=${holder.snapshot.selectedSessionId};newCwd=${sessionCwd(NEW)};" +
            "oldCwd=${oldSessionCwd()};headerBeforeSessions=$headerBeforeSessions;header=${subLineTextOrNone()}"
        saveAssert("cwd.pick.navigation", nav)
        assertEquals(
            "新会话导航与 cwd 回读",
            "selected=$NEW;newCwd=$NEW_CWD;oldCwd=$OLD_CWD;headerBeforeSessions=none;header=none",
            nav,
        )
        saveShotNamed(rootBitmap(), "f10-cwd-new-session.png")
    }

    /** 进 new-proj 后点「完成」只关闭：保留最后成功位置，草稿与旧会话不变，不发 message。 */
    @Test
    fun newSession_closeDirectorySheet_keepsBrowsedChoiceAndDraft() {
        connectWithOldSession()
        goHomeWithDraft()
        val before = stateLine()
        browseToHomeParent()
        compose.onNodeWithTag("finecat.dirItem.new-proj").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size >= 3 } }
        answerDirectories(NEW_CWD, emptyList())
        compose.onAllNodesWithText("完成").fetchSemanticsNodes().let { assertTrue("缺完成键", it.isNotEmpty()) }
        compose.onAllNodes(hasText("完成")).let { it[it.fetchSemanticsNodes().lastIndex].performClick() }
        compose.waitForIdle()
        val after = stateLine() + ";dirSheet=${hasTag("finecat.dirSheet")}"
        saveAssert("cwd.cancel.before", before)
        saveAssert("cwd.cancel.after", after)
        assertEquals("关闭保留浏览后的选择", "selected=null;newDir=$NEW_CWD;draft=$DRAFT;oldCwd=$OLD_CWD;messages=0;dirSheet=false", after)
        assertEquals("草稿未改", DRAFT, holder.messageDraft)
        saveShotNamed(rootBitmap(), "f10-cwd-cancel-home.png")
    }

    @Test
    fun firstDirectoryFailure_globalRetryUsesOriginalRequestedPath() {
        connectWithOldSession()
        goHomeWithDraft()
        openDirectorySheet()
        val expected = transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull
        assertEquals(OLD_CWD, expected)
        transport.receive("""{"kind":"error","code":"bad-request","requestType":"directories"}""")
        runBlocking { waitUntil { holder.directoryError != null } }
        compose.waitForIdle()
        val before = transport.payloadsOfType("directories").size
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("重试")).performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > before } }
        assertEquals(expected, transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull)
        assertEquals(DRAFT, holder.messageDraft)
    }

    @Test
    fun childDirectoryFailure_globalRetryUsesFailedChildNotLastSuccessfulParent() {
        connectWithOldSession()
        goHomeWithDraft()
        browseToHomeParent()
        val beforeChild = transport.payloadsOfType("directories").size
        compose.onNodeWithTag("finecat.dirItem.new-proj").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > beforeChild } }
        transport.receive("""{"kind":"error","code":"bad-request","requestType":"directories"}""")
        runBlocking { waitUntil { holder.directoryError != null } }
        compose.waitForIdle()
        assertEquals(HOME, holder.directoryPath)
        val beforeRetry = transport.payloadsOfType("directories").size
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("重试")).performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > beforeRetry } }
        assertEquals(NEW_CWD, transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull)
        assertEquals(DRAFT, holder.messageDraft)
    }

    @Test
    fun directoryInFlightWebSocketFailure_keepsListingAndRetriesRequestedChild() {
        connectWithOldSession()
        goHomeWithDraft()
        browseToHomeParent()
        val entries = holder.directoryEntries
        val beforeChild = transport.payloadsOfType("directories").size
        compose.onNodeWithTag("finecat.dirItem.new-proj").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > beforeChild } }
        assertTrue(holder.directoryIsLoading)
        transport.fail("websocket-failure")
        runBlocking { waitUntil { holder.directoryError == "连接已断开，请重试" } }
        compose.waitForIdle()
        assertFalse(holder.directoryIsLoading)
        assertEquals(HOME, holder.directoryPath)
        assertEquals(entries, holder.directoryEntries)
        assertEquals(DRAFT, holder.messageDraft)
        compose.onNodeWithText("连接已断开，请重试").assertExists()
        compose.onNodeWithText("重试").assertExists()

        runBlocking {
            graph!!.gatewayRuntime.connectStoredIfPaired()
            transport.open()
            transport.receive(HELLO)
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
        }
        val beforeRetry = transport.payloadsOfType("directories").size
        compose.onNodeWithText("重试").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > beforeRetry } }
        assertEquals(NEW_CWD, transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull)
        answerDirectories(NEW_CWD, emptyList())
        assertEquals(null, holder.directoryError)
        assertEquals(DRAFT, holder.messageDraft)
    }

    /** 目录读取失败：面板内错误行+重试；状态与草稿不变；重试发出新的 directories 请求。 */
    @Test
    fun newSession_directoryReadFailure_keepsStateAndDraft_retryRequests() {
        connectWithOldSession()
        goHomeWithDraft()
        val before = stateLine()
        openDirectorySheet()
        saveNote("step=sheetOpen directories=${transport.payloadsOfType("directories").size}")
        transport.receive("""{"kind":"error","code":"bad-request","message":"目录不可读","requestType":"directories"}""")
        runBlocking { waitUntil { holder.directoryError != null } }
        compose.waitForIdle()
        saveNote("step=errorShown platformError=${holder.platformError} dirError=${holder.directoryError}")
        // 全局弹窗先收起（「好」只关弹窗，不重试）
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("好")).performClick()
        compose.waitForIdle()
        // 面板内显示稳定码映射后的短中文，不回显上游 message。
        val panelError = compose.onAllNodes(hasText("目录路径无效") and hasAnyAncestor(hasTestTag("finecat.dirSheet"))).fetchSemanticsNodes().isNotEmpty()
        val requestsBeforeRetry = transport.payloadsOfType("directories").size
        compose.onNodeWithTag("finecat.dirSheet", useUnmergedTree = true).assertExists()
        val retryNodes = compose.onAllNodes(hasText("重试")).fetchSemanticsNodes()
        saveNote("step=beforeRetry panelError=$panelError retryNodes=${retryNodes.size} platformError=${holder.platformError}")
        compose.onAllNodes(hasText("重试")).let { it[retryNodes.lastIndex].performClick() }
        compose.waitForIdle()
        saveNote("step=afterRetryClick directories=${transport.payloadsOfType("directories").size} loading=${holder.directoryIsLoading} platformError=${holder.platformError}")
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > requestsBeforeRetry } }
        val failure = "panelError=$panelError;retrySent=${transport.payloadsOfType("directories").size - requestsBeforeRetry};" +
            stateLine()
        saveAssert("cwd.readFail.state", failure)
        assertEquals("读失败后", "panelError=true;retrySent=1;$before", failure)
        saveShotNamed(rootBitmap(), "f10-cwd-readfail-sheet.png")
    }

    /**
     * 创建失败（首条 message 被网关拒绝，如 cwd 已被删）：不伪造新会话，旧会话与新会话落点
     * 不变；本地发送写出后与网关拒绝后，输入框都保留原稿。
     */
    @Test
    fun newSession_firstMessageRejected_noFabricatedSession() {
        connectWithOldSession()
        goHomeWithDraft()
        pickNewProjInSheet(screenshot = null)
        compose.onNodeWithContentDescription("发送").performClick()
        runBlocking {
            waitUntil { transport.payloadsOfType("message").isNotEmpty() }
        }
        transport.receive("""{"kind":"error","code":"bad-request","message":"cwd 不存在","requestType":"message"}""")
        runBlocking { waitUntil { holder.messageDraft.isNotEmpty() } }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        val state = "selected=${holder.snapshot.selectedSessionId};sessions=${holder.snapshot.sessions.map { it.id }.sorted()};" +
            "newDir=${holder.fineCatNewSessionDirectoryPath};oldCwd=${oldSessionCwd()};header=${subLineTextOrNone()}"
        saveAssert("cwd.createFail.state", state)
        saveAssert("cwd.createFail.draft", holder.messageDraft.ifEmpty { "<empty>" })
        assertEquals(
            "首发被拒后不伪造会话",
            "selected=null;sessions=[$OLD];newDir=$NEW_CWD;oldCwd=$OLD_CWD;header=none",
            state,
        )
        assertEquals("首发被拒后草稿回填", DRAFT, holder.messageDraft)
        // 拒绝同时弹出既有全局错误弹窗（第二个 root）：先「好」收起再截首页，收起不应清草稿
        saveNote("step=rejectDialog platformError=${holder.platformError}")
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("好")).performClick()
        compose.waitForIdle()
        assertEquals("收起错误弹窗后草稿仍在", DRAFT, holder.messageDraft)
        saveShotNamed(rootBitmap(), "f10-cwd-createfail-home.png")
    }

    @Test
    fun newSession_returnToRoot_clearsChoiceAndPreservesDraftWithoutSending() {
        connectWithOldSession()
        goHomeWithDraft()
        browseToHomeParent()
        listOf("/home", "/").forEach { path ->
            val before = transport.payloadsOfType("directories").size
            compose.onNodeWithTag("finecat.dirUp").performClick()
            runBlocking { waitUntil { transport.payloadsOfType("directories").size > before } }
            assertEquals(path, transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull)
            answerDirectories(path, emptyList())
        }
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        compose.waitForIdle()
        assertEquals(null, holder.fineCatNewSessionDirectoryPath)
        assertEquals(DRAFT, holder.messageDraft)
        compose.onNodeWithText("待选择目录").assertExists()
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals("先在输入框选择工作目录，草稿已保留", holder.transientNotice)
        assertEquals(0, transport.payloadsOfType("message").size)
        assertEquals(DRAFT, holder.messageDraft)
        assertEquals(OLD_CWD, oldSessionCwd())
        openDirectorySheet()
    }

    // ------------------------------------------------------------------ 流程片段

    private fun goHomeWithDraft() {
        compose.onNodeWithContentDescription("会话列表").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.drawerNew").performClick()
        runBlocking { waitUntil { holder.snapshot.selectedSessionId == null } }
        compose.waitForIdle()
        compose.onNodeWithTag("fc.composer.input").performClick()
        compose.onNodeWithTag("fc.composer.input").performTextInput(DRAFT)
        runBlocking { waitUntil { holder.messageDraft == DRAFT } }
        compose.waitForIdle()
        assertEquals("回首页沿用旧会话目录", OLD_CWD, holder.fineCatNewSessionDirectoryPath)
    }

    private fun openDirectorySheet() {
        val before = transport.payloadsOfType("directories").size
        val expectedPath = holder.fineCatNewSessionDirectoryPath ?: "/"
        compose.onNodeWithTag("finecat.composerDirectory").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > before } }
        assertEquals("从已选目录或根重新打开", expectedPath,
            transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull)
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.dirSheet", useUnmergedTree = true).assertExists()
    }

    private fun browseToHomeParent() {
        openDirectorySheet()
        answerDirectories(OLD_CWD, emptyList())
        val before = transport.payloadsOfType("directories").size
        compose.onNodeWithTag("finecat.dirUp").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > before } }
        assertEquals(HOME, transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull)
        answerDirectories(HOME, listOf("new-proj", "old-proj"))
    }

    private fun pickNewProjInSheet(screenshot: String?) {
        browseToHomeParent()
        val before = transport.payloadsOfType("directories").size
        compose.onNodeWithTag("finecat.dirItem.new-proj").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("directories").size > before } }
        val browse = transport.payloadsOfType("directories").last()["path"]?.jsonPrimitive?.contentOrNull
        assertEquals("进入子目录的 directories 请求路径", NEW_CWD, browse)
        answerDirectories(NEW_CWD, emptyList())
        screenshot?.let { saveShotNamed(rootBitmap(), it) }
        compose.onNodeWithTag("finecat.sheetDone").performClick()
        compose.waitForIdle()
        assertEquals("完成不重置选择", NEW_CWD, holder.fineCatNewSessionDirectoryPath)
    }

    private fun answerDirectories(path: String, children: List<String>) {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val paths = listOf("/") + parts.runningReduce { a, b -> "$a/$b" }.map { "/$it" }
        val crumbs = paths.joinToString(",") { "{\"name\":\"${it.substringAfterLast('/').ifEmpty { "/" }}\",\"path\":\"$it\",\"hidden\":false}" }
        val entries = children.joinToString(",") { "{\"name\":\"$it\",\"path\":\"${path.trimEnd('/')}/$it\",\"hidden\":false}" }
        transport.receive(
            "{\"kind\":\"directories\",\"path\":\"$path\",\"home\":\"$HOME\",\"crumbs\":[$crumbs],\"entries\":[$entries]}",
        )
        runBlocking { waitUntil { holder.directoryPath == path && !holder.directoryIsLoading } }
        compose.waitForIdle()
        compose.waitUntil(5_000) { holder.fineCatNewSessionDirectoryPath == path.takeUnless { it == "/" } }
    }

    // ------------------------------------------------------------------ 回读

    private fun stateLine(): String =
        "selected=${holder.snapshot.selectedSessionId};newDir=${holder.fineCatNewSessionDirectoryPath};" +
            "draft=${holder.messageDraft};oldCwd=${oldSessionCwd()};messages=${transport.payloadsOfType("message").size}"

    private fun sessionCwd(id: String): String? = holder.snapshot.sessions.firstOrNull { it.id == id }?.cwd

    private fun oldSessionCwd(): String? = sessionCwd(OLD)

    private fun subLineCandidates(): List<SemanticsNode> =
        compose.onAllNodesWithTag("finecat.headerDirectory", useUnmergedTree = true).fetchSemanticsNodes()

    private fun subLineTextOrNone(): String = subLineCandidates().firstOrNull()?.let(::nodeText) ?: "none"

    /** 在某点上带 OnClick 的语义节点（取 testTag，无 tag 记 role/文本），用于证明副行本身不可点。 */
    private fun clickableTagsAt(point: Offset): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.contains(point) }
            .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "untagged:${nodeText(it)}" }
            .sorted()

    private fun nodeText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun visible(tag: String): Boolean {
        val rootH = compose.onRoot().fetchSemanticsNode().size.height
        return compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.boundsInRoot.height > 0 && it.boundsInRoot.top < rootH }
    }

    // ------------------------------------------------------------------ 装配

    private fun connectWithOldSession() {
        transport = FakeTransport()
        graph = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport,
            preferencesOverride = FakePreferences(),
            credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(),
            networkMonitorOverride = FakeNetwork,
            clockOverride = FakeClock,
            requestTimeoutOverride = 120_000L,
        )
        holder = graph!!.stateHolder
        val stateHolder = holder
        compose.setContent { FineCatApp(stateHolder = stateHolder) }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive(HELLO)
            waitUntil { stateHolder.gatewayState.connection.name == "CONNECTED" }
        }
        onMain { stateHolder.updateAppearanceMode("light") }
        transport.receive(sessionsFrame(includeNew = false))
        runBlocking { waitUntil { stateHolder.snapshot.sessions.any { it.id == OLD } } }
        onMain { stateHolder.selectSession(OLD) }
        runBlocking {
            waitUntil { stateHolder.snapshot.selectedSessionId == OLD }
            waitUntil { transport.payloadsOfType("history").isNotEmpty() }
        }
        transport.receive(
            "{\"kind\":\"history\",\"sessionId\":\"$OLD\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"旧会话提问\"}]}," +
                "{\"role\":\"assistant\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"text\",\"text\":\"旧会话回答\"}]}" +
                "],\"entryIds\":[\"o-0\",\"o-1\"],\"hasMore\":false,\"oldestEntryId\":\"o-0\",\"bytes\":64}",
        )
        runBlocking { waitUntil { stateHolder.snapshot.conversation.size >= 2 } }
        compose.waitForIdle()
    }

    private fun sessionsFrame(includeNew: Boolean): String {
        val old = "{\"id\":\"$OLD\",\"cwd\":\"$OLD_CWD\",\"name\":\"旧会话\",\"messageCount\":2,\"running\":false}"
        val new = "{\"id\":\"$NEW\",\"cwd\":\"$NEW_CWD\",\"name\":\"新会话\",\"messageCount\":1,\"running\":false}"
        return "{\"kind\":\"sessions\",\"items\":[" + (if (includeNew) "$new,$old" else old) + "]}"
    }

    // ------------------------------------------------------------------ 截图/落盘

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

    private fun saveShotNamed(bmp: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        File(dir, name).outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    private var measureFileReady = false

    private fun saveAssert(name: String, actual: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText("CAPTURE-ASSERT: $name actual=$actual\n")
    }

    /** 非门控取证备注（同一文件，随用例日志归档）。 */
    private fun saveNote(line: String) = saveAssertRaw("CAPTURE-NOTE: $line")

    private fun saveAssertRaw(line: String) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()
        val f = File(dir, "capture-measure.txt")
        if (!measureFileReady) {
            f.delete()
            measureFileReady = true
        }
        f.appendText(line + "\n")
    }

    companion object {
        private const val OLD = "s-old"
        private const val NEW = "s-new"
        private const val HOME = "/home/user"
        private const val OLD_CWD = "/home/user/old-proj"
        private const val NEW_CWD = "/home/user/new-proj"
        private const val DRAFT = "在新目录开工"

        val HELLO =
            """{"kind":"hello","authenticated":true,"protocol":3,"capabilities":["sessions","subscribe","message","abort","history","state","models","name","directories"]}"""
    }
}
