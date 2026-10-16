package com.finecat.pimobile.android.ui.finecat

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finecat.pimobile.android.*
import com.finecat.pimobile.android.ui.finecat.theme.lightFineCatColors
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实根入口 + 合成wire帧；不接真实网关/模型或用户会话。 */
@RunWith(AndroidJUnit4::class)
class FineCatTraceTreeRepairDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var graph: AndroidAppGraph? = null

    @After fun close() {
        graph?.stateHolder?.close()
        graph?.gatewayScope?.cancel()
        graph?.applicationScope?.cancel()
    }

    private fun setup(actions: Boolean = true): Pair<AndroidSharedStateHolder, FakeTransport> {
        val transport = FakeTransport()
        val app = AndroidAppGraph(
            application = ApplicationProvider.getApplicationContext<Application>(),
            transportOverride = transport, preferencesOverride = FakePreferences(), credentialStoreOverride = FakeCredentials,
            attachmentCacheOverride = FakeCache(), networkMonitorOverride = FakeNetwork, clockOverride = FakeClock,
        )
        graph = app
        val holder = app.stateHolder
        compose.setContent { FineCatApp(stateHolder = holder) }
        // actions=false 模拟旧网关：不宣告 tree-actions-v1，复制/标签入口必须隐藏。
        val capabilities = if (actions) {
            """["sessions","subscribe","history","state","tree","switch-branch","tree-view-v1","tree-actions-v1","entry-copy","label-put"]"""
        } else {
            """["sessions","subscribe","history","state","tree","switch-branch","tree-view-v1"]"""
        }
        runBlocking {
            waitUntil { transport.specs.isNotEmpty() }
            transport.open()
            transport.receive("""{"kind":"hello","authenticated":true,"protocol":3,"capabilities":$capabilities}""")
            waitUntil { holder.gatewayState.connection.name == "CONNECTED" }
            transport.receive("""{"kind":"sessions","items":[{"id":"s","cwd":"/tmp/synthetic","messageCount":2,"firstMessage":"测试","running":false}]}""")
            waitUntil { holder.snapshot.sessions.any { it.id == "s" } }
            onMain { holder.selectSession("s") }
            waitUntil { transport.sentTypes.contains("history") }
            transport.receive("""{"kind":"history","sessionId":"s","messages":[
                {"role":"assistant","content":[{"type":"text","text":"历史助手回答"}]},
                {"role":"toolResult","toolName":"read","toolCallId":"c1","content":[{"type":"text","text":"历史工具输出"}]}
                ],"entryIds":["a1","t1"],"hasMore":false,"oldestEntryId":"a1"}""")
            waitUntil { holder.snapshot.conversation.isNotEmpty() }
        }
        return holder to transport
    }

    @Test fun treeUsesWirePrefixesLabelsAndLiteralPiText() {
        val (_, transport) = setup()
        compose.onNodeWithContentDescription("tree").performClick()
        runBlocking {
            waitUntil { transport.sentTypes.contains("tree") }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"a","nodes":[
                {"id":"r","type":"message","role":"user","prefix":"","onActivePath":true,"preview":"root","displayText":"user: root","foldable":true},
                {"id":"a","parentId":"r","type":"message","role":"assistant","prefix":"├─ ","onActivePath":true,"preview":"old","displayText":"assistant: **literal**","label":"bookmark"},
                {"id":"b","parentId":"r","type":"message","role":"user","prefix":"└─ ","preview":"branch","displayText":"user: branch"}
            ]}""")
        }
        compose.onNodeWithTag("finecat.treePrefix.a", useUnmergedTree = true).assertTextEquals("├─ • ")
        compose.onNodeWithTag("finecat.treePrefix.b", useUnmergedTree = true).assertTextEquals("└─ ")
        val currentPrefix = compose.onNodeWithTag("finecat.treePrefix.a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val siblingPrefix = compose.onNodeWithTag("finecat.treePrefix.b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("当前标记不推歪连接线", siblingPrefix.left, currentPrefix.left, 1f)
        compose.onNodeWithText("[bookmark]", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeText.a", useUnmergedTree = true).assertTextEquals("assistant: **literal**")
        compose.onNodeWithTag("finecat.treePrefix.r", useUnmergedTree = true).assertTextContains("⊟")
        compose.onNodeWithTag("finecat.treeFold.r").performClick()
        runBlocking {
            waitUntil { transport.sentTypes.count { it == "tree" } == 2 }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"r","nodes":[
                {"id":"r","type":"message","role":"user","prefix":"⊞ ","onActivePath":true,"preview":"root","displayText":"user: root","foldable":true,"folded":true}
            ]}""")
            waitUntil { graph?.stateHolder?.treeRequestInFlight == false }
        }
        compose.onNodeWithTag("finecat.treePrefix.r", useUnmergedTree = true).assertTextContains("⊞")
        compose.onNodeWithTag("finecat.treeSearch").performTextInput("root")
        compose.onNodeWithTag("finecat.treeSearch").performImeAction()
        runBlocking { waitUntil { transport.sentTypes.count { it == "tree" } == 3 } }
        val view = transport.payloadsOfType("tree").last().getValue("view").jsonObject
        assertEquals("root", view.getValue("search").jsonPrimitive.content)
        assertEquals("[]", view.getValue("foldedIds").toString())
    }

    @Test fun flatTreeShowsTurnConnectorsFromThePrototype() {
        val (holder, transport) = setup()
        onMain { holder.updateAppearanceMode("light") }
        compose.onNodeWithContentDescription("tree").performClick()
        runBlocking {
            waitUntil { transport.sentTypes.contains("tree") }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a2","nodes":[
                {"id":"u1","type":"message","role":"user","preview":"first"},
                {"id":"t1","parentId":"u1","type":"message","role":"toolResult","preview":"tool"},
                {"id":"a1","parentId":"t1","type":"message","role":"assistant","preview":"reply"},
                {"id":"u2","parentId":"a1","type":"message","role":"user","preview":"second"},
                {"id":"a2","parentId":"u2","type":"message","role":"assistant","preview":"done"}
            ]}""")
        }
        compose.onNodeWithTag("finecat.treePrefix.t1", useUnmergedTree = true).assertTextEquals("│  ├─ ")
        compose.onNodeWithTag("finecat.treePrefix.a1", useUnmergedTree = true).assertTextEquals("│  └─ ")
        compose.onNodeWithTag("finecat.treePrefix.a2", useUnmergedTree = true).assertTextEquals("   └─ ")
        val content = compose.onNodeWithTag("finecat.treeContent").fetchSemanticsNode().boundsInRoot
        val prefix = compose.onNodeWithTag("finecat.treePrefix.t1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("正文线从12dp起，不保留空折叠列", 12f * compose.activity.resources.displayMetrics.density, prefix.left - content.left, 1f)
        for (id in listOf("u1", "a1", "a2")) {
            val text = compose.onNodeWithTag("finecat.treeText.$id", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.Text].single()
            val role = text.spanStyles.first { it.start == 0 && it.item.fontWeight != null }
            assertEquals(FontWeight.SemiBold, role.item.fontWeight)
            assertEquals(if (id == "a2") lightFineCatColors().onInk else lightFineCatColors().text, role.item.color)
        }
    }

    @Test fun branchDraftUpdatesInTheComposerAndDoesNotLeakAcrossSessions() {
        val (holder, transport) = setup()
        val nodes = """[
            {"id":"u1","type":"message","role":"user","preview":"第一轮用户完整内容"},
            {"id":"a1","parentId":"u1","type":"message","role":"assistant","preview":"第一轮回复"},
            {"id":"u2","parentId":"a1","type":"message","role":"user","preview":"第二轮用户完整内容"},
            {"id":"a2","parentId":"u2","type":"message","role":"assistant","preview":"第二轮回复"}]
        """.trimIndent()
        fun receiveTree(leaf: String) = runBlocking {
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"$leaf","nodes":$nodes}""")
        }
        fun openTree(leaf: String) {
            val count = transport.sentTypes.count { it == "tree" }
            compose.onNodeWithContentDescription("tree").performClick()
            runBlocking { waitUntil { transport.sentTypes.count { it == "tree" } > count } }
            receiveTree(leaf)
        }
        fun jump(id: String) {
            compose.onNodeWithTag("finecat.treeNode.$id", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("finecat.treeActionJump").performClick()
        }
        fun receiveParentHistory() = runBlocking {
            transport.receive("""{"kind":"history","sessionId":"s","entryIds":["u1","a1"],"messages":[
                {"role":"user","content":"第一轮用户完整内容"},
                {"role":"assistant","content":"第一轮回复"}],"hasMore":false}""")
        }
        openTree("a2")
        val historyBeforeFirst = transport.sentTypes.count { it == "history" }
        val treeBeforeFirst = transport.sentTypes.count { it == "tree" }
        jump("u2")
        runBlocking {
            waitUntil { transport.sentTypes.contains("switch-branch") }
            transport.receive("""{"kind":"branch-switched","sessionId":"s","entryId":"u2","leafId":"a1","editorText":"第二轮用户完整内容","alreadyAt":false,"queued":[]}""")
            waitUntil { transport.sentTypes.count { it == "history" } > historyBeforeFirst }
            waitUntil { transport.sentTypes.count { it == "tree" } > treeBeforeFirst }
        }
        receiveParentHistory(); receiveTree("a1")
        compose.onNodeWithTag("fc.composer.input").assertTextEquals("第二轮用户完整内容")
        openTree("a1")
        val historyBeforeSecond = transport.sentTypes.count { it == "history" }
        val treeBeforeSecond = transport.sentTypes.count { it == "tree" }
        jump("a1")
        runBlocking {
            waitUntil { transport.sentTypes.count { it == "switch-branch" } == 2 }
            transport.receive("""{"kind":"branch-switched","sessionId":"s","entryId":"a1","leafId":"a1","alreadyAt":true,"queued":[]}""")
            waitUntil { transport.sentTypes.count { it == "history" } > historyBeforeSecond }
            waitUntil { transport.sentTypes.count { it == "tree" } > treeBeforeSecond }
        }
        receiveParentHistory(); receiveTree("a1")
        compose.onNodeWithContentDescription("对话").performClick()
        compose.onNodeWithTag("fc.composer.input").assertTextEquals("第二轮用户完整内容")
        onMain { holder.selectSession("other") }
        compose.waitUntil { holder.snapshot.selectedSessionId == "other" }
        assertEquals("", holder.messageDraft)
        compose.onAllNodesWithText("第二轮用户完整内容", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun treeSearchSendsLastQueryAfterInFlightRequestAndClearingRestoresDefault() {
        val (holder, transport) = setup()
        compose.onNodeWithContentDescription("tree").performClick()
        runBlocking { waitUntil { holder.treeRequestInFlight } }
        compose.onNodeWithTag("finecat.treeSearch").performTextReplacement("first")
        compose.onNodeWithTag("finecat.treeSearch").performTextReplacement("last")
        assertEquals(1, transport.payloadsOfType("tree").size)
        compose.onNodeWithText("搜索", substring = false).assertDoesNotExist()
        // TREE03 曾要求“只留搜索框”；TREE05 按负责人新原型重新引入五枚筛选药丸，
        // 因此这里改为断言药丸存在且仍是默认选中（旧断言已作废）。
        compose.onNodeWithTag("finecat.treeFilter.default").assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeFilter.labeled-only").assertExists()
        runBlocking {
            transport.receive("""{"kind":"tree","sessionId":"s","nodes":[]}""")
            waitUntil { transport.payloadsOfType("tree").last().getValue("view").jsonObject["search"]?.jsonPrimitive?.content == "last" }
        }
        assertEquals(2, transport.payloadsOfType("tree").size)
        compose.onNodeWithTag("finecat.treeSearch").performTextReplacement("")
        runBlocking {
            transport.receive("""{"kind":"tree","sessionId":"s","nodes":[]}""")
            waitUntil { transport.payloadsOfType("tree").last().getValue("view").jsonObject["search"]?.jsonPrimitive?.content == "" }
        }
        assertEquals(3, transport.payloadsOfType("tree").size)
        val view = transport.payloadsOfType("tree").last().getValue("view").jsonObject
        assertEquals("default", view.getValue("filter").jsonPrimitive.content)
        assertEquals("[]", view.getValue("foldedIds").toString())
    }

    @Test fun legacyTreeRequestDoesNotInheritPreviousSearch() {
        val (holder, transport) = setup()
        runBlocking {
            onMain { holder.loadSessionTree(com.finecat.pimobile.shared.protocol.GatewayTreeView(search = "old search")) }
            waitUntil { transport.sentTypes.contains("tree") }
            transport.receive("""{"kind":"tree","sessionId":"s","nodes":[]}""")
            waitUntil { !holder.treeRequestInFlight }
            onMain { holder.loadSessionTree() }
            waitUntil { transport.sentTypes.count { it == "tree" } == 2 }
        }
        assertFalse(transport.payloadsOfType("tree").last().containsKey("view"))
    }

    // ---------------------------------------------------------------------------
    // TREE05：浏览选中 vs 跳转、五枚筛选、全文复制、标签写、旧网关降级
    // ---------------------------------------------------------------------------

    private fun openTree(transport: FakeTransport, nodesJson: String = THREE_NODES) {
        compose.onNodeWithContentDescription("tree").performClick()
        runBlocking {
            waitUntil { transport.sentTypes.contains("tree") }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"a","nodes":$nodesJson}""")
            waitUntil { !graph!!.stateHolder.treeRequestInFlight }
        }
        compose.waitForIdle()
    }

    /** 点行只开动作卡；跳转必须再点卡内「跳到此处」。 */
    @Test fun rowTapSelectsAndOpensActionSheetWithoutSwitchingBranch() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionSheet").assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeActionJump").assertIsDisplayed()
        assertEquals("点行不得发 switch-branch", 0, transport.payloadsOfType("switch-branch").size)
        // 取消只关卡：不跳转、不改变叶子，浏览选中保留。
        compose.onNodeWithTag("finecat.treeActionClose").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionSheet").assertDoesNotExist()
        assertEquals("取消不得发 switch-branch", 0, transport.payloadsOfType("switch-branch").size)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).assertIsSelected()
    }

    @Test fun jumpActionUsesExistingSwitchBranchPathWithRealEntryId() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionJump").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("switch-branch").isNotEmpty() } }
        assertEquals("b", transport.payloadsOfType("switch-branch").last().getValue("entryId").jsonPrimitive.content)
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionSheet").assertDoesNotExist()
    }

    @Test fun manualDraftConfirmationCanCancelOrKeepWithoutLosingText() {
        val (holder, transport) = setup()
        openTree(transport)
        onMain { holder.messageDraft = "合成未发送草稿" }
        fun requestJump() {
            compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("finecat.treeActionJump").performClick()
            compose.waitForIdle()
        }
        requestJump()
        compose.onNodeWithTag("finecat.branchDraftConfirmation").assertIsDisplayed()
        assertEquals(0, transport.payloadsOfType("switch-branch").size)
        onMain { holder.dismissBranchDraftConfirmation() }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.branchDraftConfirmation").assertDoesNotExist()
        assertEquals(0, transport.payloadsOfType("switch-branch").size)
        requestJump()
        compose.onNodeWithTag("finecat.branchDraftKeep").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("switch-branch").isNotEmpty() } }
        runBlocking {
            transport.receive("""{"kind":"branch-switched","sessionId":"s","entryId":"b","leafId":"a","editorText":"其它节点"}""")
        }
        compose.waitForIdle()
        assertEquals("合成未发送草稿", holder.messageDraft)
    }

    @Test fun assistantJumpSkipsConfirmationAndKeepsComposerTextAndAttachments() {
        val (holder, transport) = setup()
        val nodes = THREE_NODES.trim().dropLast(1) +
            """,{"id":"a2","parentId":"b","type":"message","role":"assistant","preview":"第二回复"}]"""
        openTree(transport, nodes)
        val image = com.finecat.pimobile.android.platform.AndroidPreparedImage(
            com.finecat.pimobile.shared.gateway.GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)
        onMain {
            holder.messageDraft = "合成待发送手稿"
            holder.setPreparedImagesForTest(listOf(image), listOf("content://synthetic/image"))
        }
        val historyBefore = transport.sentTypes.count { it == "history" }
        val treeBefore = transport.sentTypes.count { it == "tree" }
        compose.onNodeWithTag("finecat.treeNode.a2", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("finecat.treeActionJump").performClick()
        compose.onNodeWithTag("finecat.branchDraftConfirmation").assertDoesNotExist()
        runBlocking {
            waitUntil { transport.payloadsOfType("switch-branch").isNotEmpty() }
            assertEquals("a2", transport.payloadsOfType("switch-branch").last().getValue("entryId").jsonPrimitive.content)
            transport.receive("""{"kind":"branch-switched","sessionId":"s","entryId":"a2","leafId":"a2","alreadyAt":false,"queued":[]}""")
            waitUntil { transport.sentTypes.count { it == "history" } > historyBefore }
            waitUntil { transport.sentTypes.count { it == "tree" } > treeBefore }
            transport.receive("""{"kind":"history","sessionId":"s","entryIds":["b","a2"],"messages":[
                {"role":"user","content":"不应回填这个历史问题"},
                {"role":"assistant","content":"第二回复"}],"hasMore":false}""")
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a2","nodes":$nodes}""")
            waitUntil { holder.sessionTree?.second == "a2" }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.branchDraftConfirmation").assertDoesNotExist()
        compose.onNodeWithTag("fc.composer.input").assertTextEquals("合成待发送手稿")
        assertEquals(listOf(image), holder.preparedImages)
        assertEquals(listOf("content://synthetic/image"), holder.preparedUris)
    }

    @Test fun filterPillsSendPiFiltersAndSecondTapReturnsToDefault() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeFilter.user-only").performClick()
        runBlocking {
            waitUntil { transport.payloadsOfType("tree").size == 2 }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"a","nodes":[]}""")
            waitUntil { !graph!!.stateHolder.treeRequestInFlight }
        }
        assertEquals("user-only", transport.payloadsOfType("tree").last().getValue("view").jsonObject
            .getValue("filter").jsonPrimitive.content)
        // Pi 的 toggle 语义：T/U/L/A 再点一次回 default。
        compose.onNodeWithTag("finecat.treeFilter.user-only").performClick()
        runBlocking {
            waitUntil { transport.payloadsOfType("tree").size == 3 }
            transport.receive("""{"kind":"tree","sessionId":"s","leafId":"a","selectedId":"a","nodes":[]}""")
            waitUntil { !graph!!.stateHolder.treeRequestInFlight }
        }
        assertEquals("default", transport.payloadsOfType("tree").last().getValue("view").jsonObject
            .getValue("filter").jsonPrimitive.content)
        // 复审A修复的护栏：筛选请求失败时回滚乐观 view——药丸高亮与实际渲染数据
        // 保持一致，默认药丸重新选中，失败原因可见。
        compose.onNodeWithTag("finecat.treeFilter.labeled-only").performClick()
        runBlocking {
            waitUntil { transport.payloadsOfType("tree").size == 4 }
            transport.receive("""{"kind":"error","code":"bad-request","message":"invalid tree view","requestType":"tree","sessionId":"s"}""")
            waitUntil { graph!!.stateHolder.treeError != null }
        }
        compose.waitForIdle()
        compose.onNodeWithText("请求参数无效", substring = true).assertExists()
        compose.onNodeWithTag("finecat.treeFilter.default").assertIsSelected()
        compose.onNodeWithTag("finecat.treeFilter.labeled-only").assertIsNotSelected()
    }

    @Test fun copyActionRequestsEntryFullTextAndReportsTheRealResult() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.a", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionCopy").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("entry-copy").isNotEmpty() } }
        // 在途标志是快照状态（复审B major 修复）：请求已发出即 pending 已置位，
        // waitForIdle 后按钮必须已重组为“复制中…”并禁用。
        compose.onNodeWithContentDescription("复制中…").assertIsDisplayed()
        assertEquals("a", transport.payloadsOfType("entry-copy").last().getValue("entryId").jsonPrimitive.content)
        // 全文应答：写剪贴板并如实报字数（不是 preview 字数）。回执有 2.4s 自动清除，
        // autoAdvance 下 waitForIdle 会把虚拟时钟快进到清除任务——冻结时钟再断言
        // （复审D：否则出现→清除压进同一轮，断言 flaky）。
        compose.mainClock.autoAdvance = false
        try {
            transport.receive("""{"kind":"entry-copy","sessionId":"s","entryId":"a","bytes":11,"text":"hi there 全文"}""")
            compose.waitUntil(5_000) { hasText("已复制全文 11 字") }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        // 超限帧不带 text：必须明确报“未复制”，不能静默截断充全文。
        compose.onNodeWithTag("finecat.treeActionClose").performClick()
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionCopy").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("entry-copy").size == 2 } }
        compose.mainClock.autoAdvance = false
        try {
            transport.receive("""{"kind":"entry-copy","sessionId":"s","entryId":"b","bytes":600000,"oversized":true}""")
            compose.waitUntil(5_000) { hasText("未复制") }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun labelActionAppendsLabelAndReloadsTreeFromGateway() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionLabel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeLabelInput").performTextInput("交付")
        compose.onNodeWithTag("finecat.treeLabelSave").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("label-put").isNotEmpty() } }
        // 同 copy：在途期间保存按钮重组为“保存中…”。
        compose.onNodeWithContentDescription("保存中…").assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeLabelInput").assertIsNotEnabled()
        val payload = transport.payloadsOfType("label-put").last()
        assertEquals("b", payload.getValue("entryId").jsonPrimitive.content)
        assertEquals("交付", payload.getValue("label").jsonPrimitive.content)
        val beforeReload = transport.payloadsOfType("tree").size
        // 应答带推进后的叶子：客户端重拉树，不自己算当前行。成功回执已移到树页级
        // （复审D blocker 修复）：卡片同帧收起后反馈仍可见，断言不再依赖卡内文本。
        compose.mainClock.autoAdvance = false
        try {
            transport.receive("""{"kind":"label-put","sessionId":"s","entryId":"b","label":"交付","labelEntryId":"lab-1","leafId":"lab-1"}""")
            runBlocking { waitUntil { transport.payloadsOfType("tree").size > beforeReload } }
            compose.waitUntil(5_000) { hasText("已保存标签「交付」") }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("finecat.treeActionSheet").assertDoesNotExist()
        // take(24) 截断契约（复审D）：输入框按原型限 24 字符，超出部分不发；
        // 首尾空白不预 trim（Pi LabelInput 语义由网关执行）。
        compose.onNodeWithTag("finecat.treeNode.r", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionLabel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeLabelInput").performTextInput("  " + "标".repeat(30))
        compose.onNodeWithTag("finecat.treeLabelSave").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("label-put").size == 2 } }
        val clipped = transport.payloadsOfType("label-put").last().getValue("label").jsonPrimitive.content
        // UI先限制原始输入24字符，holder再按Pi语义trim；两空格+22字实际发送22字。
        assertEquals("标".repeat(22), clipped)
    }

    @Test fun lateLabelSuccessDoesNotCloseAnotherRowsDraft() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("finecat.treeActionLabel").performClick()
        compose.onNodeWithTag("finecat.treeLabelInput").performTextInput("第一条")
        compose.onNodeWithTag("finecat.treeLabelSave").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("label-put").isNotEmpty() } }
        compose.onNodeWithTag("finecat.treeNode.r", useUnmergedTree = true).performClick()
        // 在途期间标签入口禁用，先只保持新动作卡，旧保存成功不得关闭它。
        transport.receive("""{"kind":"label-put","sessionId":"s","entryId":"b","label":"第一条","labelEntryId":"lab-1","leafId":"lab-1"}""")
        runBlocking { waitUntil { !graph!!.stateHolder.treeLabelInFlight } }
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionSheet").assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeActionTitle").assertTextEquals("user: root")
    }

    @Test fun labelFailureKeepsTheSheetAndShowsStableText() {
        val (_, transport) = setup()
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionLabel").performClick()
        compose.onNodeWithTag("finecat.treeLabelInput").performTextInput("交付")
        compose.onNodeWithTag("finecat.treeLabelSave").performClick()
        runBlocking { waitUntil { transport.payloadsOfType("label-put").isNotEmpty() } }
        transport.receive("""{"kind":"error","code":"entry-not-found","message":"entry not found: b","requestType":"label-put","sessionId":"s","entryId":"b"}""")
        compose.waitUntil(5_000) { hasText("条目不存在，请刷新会话树") }
        // 失败保留输入态，不把错误当已保存。
        compose.onNodeWithTag("finecat.treeLabelInput").assertIsDisplayed()
    }

    @Test fun legacyGatewayWithoutTreeActionsHidesCopyAndLabelInsteadOfFakingThem() {
        val (_, transport) = setup(actions = false)
        openTree(transport)
        compose.onNodeWithTag("finecat.treeNode.b", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finecat.treeActionSheet").assertIsDisplayed()
        compose.onNodeWithTag("finecat.treeActionCopy").assertDoesNotExist()
        compose.onNodeWithTag("finecat.treeActionLabel").assertDoesNotExist()
        compose.onNodeWithTag("finecat.treeActionUpgradeHint").assertIsDisplayed()
        // 跳转仍可用（switch-branch 是旧能力）。
        compose.onNodeWithTag("finecat.treeActionJump").assertIsDisplayed()
        // 两条 0 计数单独看恒真（按钮已不存在）：由 copyAction/labelAction 用例的
        // 正向计数（isNotEmpty）兑住同一类型串，这里只兑住“隐藏后确实没发过”。
        assertEquals(0, transport.payloadsOfType("entry-copy").size)
        assertEquals(0, transport.payloadsOfType("label-put").size)
    }

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** 合成三节点树：不含任何真实会话/目录/正文。 */
        const val THREE_NODES = """[
                {"id":"r","type":"message","role":"user","prefix":"","onActivePath":true,"preview":"root","displayText":"user: root","foldable":true},
                {"id":"a","parentId":"r","type":"message","role":"assistant","prefix":"├─ ","onActivePath":true,"preview":"old","displayText":"assistant: **literal**","label":"bookmark"},
                {"id":"b","parentId":"r","type":"message","role":"user","prefix":"└─ ","preview":"branch","displayText":"user: branch"}
            ]"""
    }
}
