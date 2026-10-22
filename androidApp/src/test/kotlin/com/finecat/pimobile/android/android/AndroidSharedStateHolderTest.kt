package com.finecat.pimobile.android

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import com.finecat.pimobile.shared.gateway.GatewayRuntimeEvent
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayImageAttachment
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.protocol.SessionEvent
import com.finecat.pimobile.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSharedStateHolderTest {
    @Test
    fun bridgeQueueRecallRestoresTextOnceAndKeepsCurrentRunAndNewDraft() {
        val holder = newPreviewHolder()
        try {
            holder.selectSession("s-bridge-queue")
            holder.wirePayload = """{"kind":"state","sessionId":"s-bridge-queue","isStreaming":true,"isCompacting":false,"pendingMessages":true,"queuedMessages":{"steering":[],"followUp":[]}}"""
            holder.submitWirePayload()
            assertTrue(holder.snapshot.sessionStates.getValue("s-bridge-queue").hasQueuedMessages)
            holder.messageDraft = "new draft"
            assertTrue(holder.beginQueueRecall("s-bridge-queue", "q-bridge"))
            holder.wirePayload = """{"kind":"dequeued","sessionId":"s-bridge-queue","queueRequestId":"q-bridge","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["queued draft"]}"""
            holder.submitWirePayload()
            assertEquals("queued draft\n\nnew draft", holder.messageDraft)
            val after = holder.snapshot.sessionStates.getValue("s-bridge-queue")
            assertFalse(after.hasQueuedMessages)
            assertTrue(after.isStreaming)
            holder.submitWirePayload()
            assertEquals("queued draft\n\nnew draft", holder.messageDraft)
            assertEquals(0L, holder.successfulMessageSendCount)
        } finally { holder.close() }
    }

    @Test
    fun treeActionsCannotPublishIntoAnotherSessionOrSurviveReset() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("a")
            holder.beginTreeCopyRequest("a", "e1")
            holder.beginTreeLabelRequest("a", "e1")
            assertTrue(holder.treeCopyInFlight)
            assertTrue(holder.treeLabelInFlight)
            holder.selectSession("b")
            assertFalse(holder.treeCopyInFlight)
            assertFalse(holder.treeLabelInFlight)
            holder.wirePayload = """{"kind":"entry-copy","sessionId":"a","entryId":"e1","text":"旧会话内容"}"""
            holder.submitWirePayload()
            holder.wirePayload = """{"kind":"label-put","sessionId":"a","entryId":"e1","label":"旧标签"}"""
            holder.submitWirePayload()
            assertNull(holder.treeCopyOutcome)
            assertNull(holder.treeLabelOutcome)
            holder.beginTreeCopyRequest("b", "e2")
            holder.beginTreeLabelRequest("b", "e2")
            holder.reset()
            assertFalse(holder.treeCopyInFlight)
            assertFalse(holder.treeLabelInFlight)
            assertNull(holder.treeCopyOutcome)
            assertNull(holder.treeLabelOutcome)
        } finally { holder.close() }
    }

    @Test
    fun treeActionRepliesRequireExactSessionAndEntryAndFailuresStayScoped() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("a")
            holder.beginTreeCopyRequest("a", "e1")
            for (payload in listOf(
                """{"kind":"entry-copy","sessionId":"a","entryId":"wrong","text":"错条目"}""",
                """{"kind":"entry-copy","entryId":"e1","text":"缺会话"}""",
                """{"kind":"entry-copy","sessionId":"a","text":"缺条目"}"""
            )) {
                holder.wirePayload = payload; holder.submitWirePayload()
                assertTrue(holder.treeCopyInFlight)
                assertNull(holder.treeCopyOutcome)
            }
            holder.failTreeAction("entry-copy", "b", "错误会话失败")
            assertTrue(holder.treeCopyInFlight)
            holder.wirePayload = """{"kind":"entry-copy","sessionId":"a","entryId":"e1","text":"完整正文"}"""
            holder.submitWirePayload()
            assertFalse(holder.treeCopyInFlight)
            assertEquals("完整正文", (holder.treeCopyOutcome as AndroidSharedStateHolder.TreeCopyOutcome.Copied).text)
            holder.selectSession("b")
            assertNull(holder.treeCopyOutcome)
            holder.beginTreeLabelRequest("b", "e2")
            holder.failTreeAction("label-put", "a", "旧会话超时")
            assertTrue(holder.treeLabelInFlight)
            holder.failTreeAction("label-put", "b", "请求超时，请重试")
            assertFalse(holder.treeLabelInFlight)
            assertEquals("请求超时，请重试", (holder.treeLabelOutcome as AndroidSharedStateHolder.TreeLabelOutcome.Failed).message)
        } finally { holder.close() }
    }

    @Test
    fun workspaceCancellationDistinguishesConnectionFailureFromReplacementAndOtherRequests() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.handleWorkspaceRequestCancelled(GatewayRuntimeEvent.RequestCancelled("directories", null, "connection-replaced"))
            assertNull(holder.directoryError)
            holder.handleWorkspaceRequestCancelled(GatewayRuntimeEvent.RequestCancelled("providers", null, "websocket-failure"))
            assertNull(holder.directoryError)
            for (reason in listOf("network-lost", "connection-closed", "recovery-timeout", "websocket-failure")) {
                holder.wirePayload = """{"kind":"directories","path":"/workspace","entries":[]}"""
                holder.submitWirePayload()
                assertNull(holder.directoryError)
                holder.handleWorkspaceRequestCancelled(GatewayRuntimeEvent.RequestCancelled("directories", null, reason))
                assertEquals("连接已断开，请重试", holder.directoryError)
            }
            holder.handleWorkspaceRequestCancelled(GatewayRuntimeEvent.RequestCancelled("directory-create", null, "websocket-failure"))
            assertEquals("创建未确认，请重新打开核对", holder.directoryError)
        } finally {
            holder.close()
        }
    }

    @Test
    fun storedConnectTestSwitchDefaultsToFalse() {
        // 防"默认翻转"回归：skipStoredConnectForTest 只允许测试在 @Before 置位，
        // 默认必须保持 false（生产 MainActivity 冷启动自动连接语义不被无声关掉，
        // F13 第三轮复审 MINOR-5）。
        org.junit.Assert.assertFalse(AndroidSharedStateHolder.skipStoredConnectForTest)
    }

    @Test
    fun hostModeFrameIsAuthoritativeBeforeInputRefresh() {
        val holder = AndroidSharedStateHolder()
        holder.wirePayload = """{"kind":"host-mode","mode":"bridge","changed":false}"""
        holder.submitWirePayload()
        assertEquals("bridge", holder.gatewayState.hostMode)
        holder.wirePayload = """{"kind":"host-mode","mode":"sdk","changed":false}"""
        holder.submitWirePayload()
        assertEquals("sdk", holder.gatewayState.hostMode)
    }

    @Test
    fun commandsFrameCachesUnderSelectedSessionId() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-c")
        holder.wirePayload = "{\"kind\":\"commands\",\"sessionId\":\"session-c\",\"commands\":[" +
            "{\"name\":\"eval\",\"description\":\"E\",\"source\":\"prompt\",\"scope\":\"project\",\"argumentHint\":\"[t]\"}," +
            "{\"name\":\"skill:grilling\",\"description\":\"G\",\"source\":\"skill\",\"disableModelInvocation\":true}]}"
        holder.submitWirePayload()
        val cached = holder.slashCommands
        assertNotNull(cached)
        assertEquals("session-c", cached!!.first)
        assertEquals(2, cached.second.size)
        assertEquals("skill:grilling", cached.second[1].name)
        assertTrue(cached.second[1].isSkill)
    }

    @Test
    fun commandsFrameFromOtherSessionIsDropped() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-c")
        holder.wirePayload = "{\"kind\":\"commands\",\"sessionId\":\"session-other\",\"commands\":[]}"
        holder.submitWirePayload()
        // 归属不符丢弃，不污染缓存（联想入口保持隐藏）。
        assertEquals(null, holder.slashCommands)
    }
    @Test
    fun treeFrameCachesUnderSessionIdNotLeafId() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-x")
        holder.wirePayload = """
            {"kind":"tree","sessionId":"session-x","leafId":"leaf-1","nodes":[]}
        """.trimIndent()
        holder.submitWirePayload()
        // 归属键必须是 sessionId；曾误存 leafId 导致与 selectedSessionId 比较永不相等，
        // 树页永久转圈且每次进 tab 重拉（b372405 引入，1.5.0 用户可得版本全中招）。
        val tree = holder.sessionTree
        assertNotNull(tree)
        assertEquals("session-x", tree!!.first)
        assertEquals("leaf-1", tree.second)
    }

    /** 树页动作的前置守卫（TREE05）：没网关/没会话/没选中条目时明确失败，
     *  不静默无反应、也不用 preview 冒充全文。 */
    @Test
    fun treeCopyGuardsFailExplicitlyWithoutGatewayOrTarget() {
        val holder = AndroidSharedStateHolder()
        // 未选会话：先报会话，不报“未连接”（用户实际缺的是会话）。
        holder.copyTreeEntry("e1")
        assertEquals(
            "当前没有选中的会话",
            (holder.treeCopyOutcome as AndroidSharedStateHolder.TreeCopyOutcome.Failed).message,
        )
        holder.consumeTreeCopyOutcome()
        assertEquals(null, holder.treeCopyOutcome)

        holder.selectSession("session-x")
        // 未连接（本用例无 graph）：不得把失败当成“已复制”。
        holder.copyTreeEntry("e1")
        assertEquals(
            "请先连接 Pi 网关",
            (holder.treeCopyOutcome as AndroidSharedStateHolder.TreeCopyOutcome.Failed).message,
        )
        assertEquals(false, holder.treeCopyInFlight)
        holder.consumeTreeCopyOutcome()
        // 注意：本用例无 graph（capabilities 恒空），`treeActionsSupported == false`
        // 在此恒真、不证明能力门控。门控的正反两态由设备用例真实覆盖：
        // `FineCatTraceTreeRepairDeviceTest.legacyGatewayWithoutTreeActionsHidesCopyAndLabelInsteadOfFakingThem`
        //（hello 不宣告 tree-actions-v1 → 按钮不存在 + 0 请求）与
        // `copyActionRequestsEntryFullTextAndReportsTheRealResult`（宣告 → 发 entry-copy）。
    }

    @Test
    fun treeLabelGuardsFailExplicitlyAndKeepTargetEntryId() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-x")
        holder.setTreeEntryLabel("e9", null)
        val failed = holder.treeLabelOutcome as AndroidSharedStateHolder.TreeLabelOutcome.Failed
        assertEquals("e9", failed.entryId)
        assertEquals("请先连接 Pi 网关", failed.message)
        assertEquals(false, holder.treeLabelInFlight)
        holder.consumeTreeLabelOutcome()
        assertEquals(null, holder.treeLabelOutcome)

        // 未选会话：同样明确失败，不默默丢请求。
        val fresh = AndroidSharedStateHolder()
        fresh.setTreeEntryLabel("e9", "交付")
        assertEquals(
            "当前没有选中的会话",
            (fresh.treeLabelOutcome as AndroidSharedStateHolder.TreeLabelOutcome.Failed).message,
        )
    }

    @Test
    fun liveUserMessageWithImageLandsInConversationItems() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-img")
        holder.wirePayload = """
            {"kind":"event","sessionId":"session-img","seq":1,"time":1.0,
             "event":{"type":"message_end","message":{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","mimeType":"image/png","attachmentId":"img-1",
                 "dataOmitted":true,"dataBytes":2048}]}}}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        val user = holder.snapshot.conversation.filter { it.kind == com.finecat.pimobile.shared.projection.ConversationItemKind.USER }
        assertEquals(1, user.size)
        val images = user.single().images
        assertEquals(1, images.size)
        assertEquals("img-1", images.single().attachmentId)
    }

    @Test
    fun newConversationModelChoiceIsParkedUntilSessionExists() {
        val holder = AndroidSharedStateHolder()
        // 新建对话尚无会话：选择先记为预选，随首个 message 帧携带（网关先 setModel 再 prompt）。
        holder.selectModel("deepseek", "deepseek-flash", "high")
        assertEquals(GatewayModelSelection("deepseek", "deepseek-flash", "high"), holder.pendingModelSelection)

        // 预选只属于“下一个新会话”：切换档位覆盖、进入已有会话或重开新建页即作废。
        holder.selectModel("deepseek", "deepseek-flash", "max")
        assertEquals(GatewayModelSelection("deepseek", "deepseek-flash", "max"), holder.pendingModelSelection)
        holder.selectSession("session-a")
        assertEquals(null, holder.pendingModelSelection)

        holder.selectModel("openai", "gpt-5", null)
        holder.prepareNewSession()
        assertEquals(null, holder.pendingModelSelection)
    }

    @Test
    fun directoryFramesDriveAndroidBrowserStateAndNewSessionCwd() {
        val holder = AndroidSharedStateHolder()
        holder.wirePayload =
            """{"kind":"directories","path":"/Users/mobile","home":"/Users/mobile","crumbs":[{"name":"mobile","path":"/Users/mobile","hidden":false}],"entries":[{"name":"project","path":"/Users/mobile/project","hidden":false}]}"""
        holder.submitWirePayload()

        assertEquals("/Users/mobile", holder.directoryPath)
        assertEquals("project", holder.directoryEntries.single().name)
        assertEquals(false, holder.directoryIsLoading)

        holder.wirePayload =
            """{"kind":"directory-create","path":"/Users/mobile/project","created":true}"""
        holder.submitWirePayload()

        assertEquals("/Users/mobile/project", holder.createdDirectoryPathToReveal)

        // pi 语义：目录选择代替旧 workspace；选中的目录作为新会话 cwd。
        holder.selectDirectory("/Users/mobile/project")
        assertEquals("/Users/mobile/project", holder.selectedDirectoryPath)
        holder.clearSelectedDirectory()
        assertEquals(null, holder.selectedDirectoryPath)
    }

    @Test
    fun fixtureAndWireInputAreReducedBySharedStore() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        assertEquals(2, holder.snapshot.conversation.size)

        holder.submitWirePayload()
        assertEquals("event", holder.snapshot.lastFrameKind)
        assertEquals("最终消息会替换流式临时消息。", holder.snapshot.conversation.last().text)
        assertNotNull(holder.snapshot.selectedSessionId)
    }

    @Test
    fun busySendKeepsDraftAndImagesUntilRequestIsAccepted() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "keep me"
        holder.setPreparedImagesForTest(
            listOf(
                AndroidPreparedImage(
                    GatewayOutgoingImage("image/png", "AQID"),
                    width = 1,
                    height = 1,
                    byteCount = 3
                )
            )
        )
        holder.applyMessageSendResult(false)
        assertEquals("keep me", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
        assertEquals(0L, holder.successfulMessageSendCount)

        holder.applyMessageSendResult(true)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
        assertEquals(1L, holder.successfulMessageSendCount)
    }

    @Test
    fun acceptedSubmissionDoesNotClearOrResendInputEditedAfterClick() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        val originalImage = AndroidPreparedImage(
            GatewayOutgoingImage("image/png", "AQID"),
            width = 1,
            height = 1,
            byteCount = 3
        )
        holder.messageDraft = "original draft"
        holder.setPreparedImagesForTest(listOf(originalImage))
        val submission = holder.captureMessageSubmissionForTest()
        assertEquals("android-demo", submission.sessionId)
        assertEquals("original draft", submission.draft)
        assertEquals(listOf(originalImage), submission.images)

        val newImage = originalImage.copy(
            outgoing = GatewayOutgoingImage("image/png", "BAUG")
        )
        holder.messageDraft = "new draft"
        holder.setPreparedImagesForTest(listOf(newImage))
        holder.selectSession("another-session")
        holder.applyMessageSendResultForTest(submission, sent = true)

        assertEquals("", holder.messageDraft)
        assertTrue(holder.preparedImages.isEmpty())
        holder.selectSession("android-demo")
        assertEquals("new draft", holder.messageDraft)
        assertEquals(listOf(newImage), holder.preparedImages)
        assertEquals(0L, holder.successfulMessageSendCount)
        assertEquals("original draft", submission.draft)
        assertEquals(listOf(originalImage), submission.images)
    }

    @Test
    fun photosTrayTogglesAndClosesAfterSend() {
        val holder = AndroidSharedStateHolder()
        // 无 provider（单测无 graph）也允许开合：UI 只依赖 open 状态渲染托盘。
        holder.togglePhotosTray()
        assertEquals(true, holder.photosTrayOpen)
        assertEquals(emptyList<Uri>(), holder.recentPhotos)
        holder.togglePhotosTray()
        assertEquals(false, holder.photosTrayOpen)

        // 发送成功自动收起托盘：连发多图时重新点按钮即可，不留常开占位。
        holder.messageDraft = "带图消息"
        holder.togglePhotosTray()
        assertEquals(true, holder.photosTrayOpen)
        val submission = holder.captureMessageSubmissionForTest()
        holder.applyMessageSendResultForTest(submission, sent = true)
        assertEquals(false, holder.photosTrayOpen)
    }

    @Test
    fun photosTrayToggleWithoutProviderStaysClosedConsistently() {
        val holder = AndroidSharedStateHolder()
        holder.closePhotosTray()
        assertEquals(false, holder.photosTrayOpen)
        assertEquals(emptyMap<String, ImageBitmap>(), holder.recentPhotoThumbnails)
    }

    @Test
    fun visibleAttachmentRequestCapturesSection422ReferenceFromArrivingLiveFrame() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        // live user message_end（帧顶层 entryId）→ 归一图引用：entryId+imageIndex
        // 随 GatewayImageAttachment 落到会话项，入队时从当前快照捕获。
        holder.wirePayload =
            """{"kind":"event","sessionId":"android-demo","seq":4,"time":1786937355,"entryId":"entry-9",
               "event":{"type":"message_end","message":{"role":"user","content":[
               {"type":"text","text":"看图"},
               {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}}}"""
        holder.submitWirePayload()
        val images = holder.snapshot.conversation.flatMap { it.images }
        assertEquals(listOf("hist-entry-9-0"), images.map { it.attachmentId })

        holder.updateVisibleAttachments(setOf("hist-entry-9-0"))
        val queued = holder.queuedAttachmentRequestsForTest().single()
        assertEquals("hist-entry-9-0", queued.attachmentId)
        assertEquals("android-demo", queued.sessionId)
        // 三元引用在 Main 入队时捕获：后续 fetch 按 (sessionId, entryId, imageIndex) 寻址。
        assertEquals("entry-9", queued.entryId)
        assertEquals(0, queued.imageIndex)
    }

    @Test
    fun queuedAttachmentRequestsDoNotSurviveSessionSwitch() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        holder.wirePayload =
            """{"kind":"event","sessionId":"android-demo","seq":4,"time":1786937355,"entryId":"entry-9",
               "event":{"type":"message_end","message":{"role":"user","content":[
               {"type":"text","text":"看图"},
               {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}}}"""
        holder.submitWirePayload()
        holder.updateVisibleAttachments(setOf("hist-entry-9-0"))
        assertEquals(1, holder.queuedAttachmentRequestsForTest().size)

        // 切会话：旧会话的取图请求不得留在队列（结果可缓存但不能发布到新页面）。
        holder.selectSession("session-other")
        assertTrue(holder.queuedAttachmentRequestsForTest().isEmpty())
        assertTrue(holder.queuedAttachmentIdsForTest().isEmpty())
    }

    @Test
    fun visibleAttachmentQueueKeepsVisibleOrderAndRejectsForeignIds() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        holder.wirePayload =
            """{"kind":"event","sessionId":"android-demo","seq":4,"time":1786937355,"entryId":"entry-9",
               "event":{"type":"message_end","message":{"role":"user","content":[
               {"type":"text","text":"两张图"},
               {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64},
               {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}}}"""
        holder.submitWirePayload()

        // 可见集合顺序决定队列顺序（串行消费），非本会话/不可见 id 不入队。
        holder.updateVisibleAttachments(setOf("hist-entry-9-0", "hist-entry-9-1", "foreign-id"))
        assertEquals(
            listOf("hist-entry-9-0", "hist-entry-9-1"),
            holder.queuedAttachmentRequestsForTest().map { it.attachmentId }
        )
        // 同一请求不重复入队。
        holder.updateVisibleAttachments(setOf("hist-entry-9-0", "hist-entry-9-1"))
        assertEquals(2, holder.queuedAttachmentRequestsForTest().size)
    }

    /** live 帧已携带 image 引用（entryId+序号），负缓存/手动重试按真实路径驱动。 */
    @Test
    fun visibleAttachmentFailureIsNegativeCachedUntilExplicitRetry() {
        val holder = AndroidSharedStateHolder()
        holder.loadFixture()
        holder.wirePayload =
            """{"kind":"event","sessionId":"android-demo","seq":4,"time":1786937355,"entryId":"entry-9",
               "event":{"type":"message_end","message":{"role":"user","content":[
               {"type":"text","text":"看图"},
               {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}}}"""
        holder.submitWirePayload()
        holder.updateVisibleAttachments(setOf("hist-entry-9-0"))
        assertEquals(AttachmentLoadState.LOADING, holder.attachmentStates["hist-entry-9-0"])
        assertEquals(listOf("hist-entry-9-0"), holder.queuedAttachmentIdsForTest())

        holder.failAttachmentForTest("hist-entry-9-0")
        holder.updateVisibleAttachments(setOf("hist-entry-9-0"))
        assertEquals(AttachmentLoadState.FAILED, holder.attachmentStates["hist-entry-9-0"])
        assertTrue(holder.queuedAttachmentIdsForTest().isEmpty())

        holder.retryAttachment("hist-entry-9-0")
        assertEquals(AttachmentLoadState.LOADING, holder.attachmentStates["hist-entry-9-0"])
        assertEquals(listOf("hist-entry-9-0"), holder.queuedAttachmentIdsForTest())
    }

    // ---- 手动压缩上下文（CTX02）：真实接线的闸门、归属与统计收尾 ----

    @Test
    fun compactFailedAckWithoutActivityFlagsReleasesPendingWithFailureNotice() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-compact")
        holder.compactGateForceOpenForTest = true
        holder.startManualCompact()
        holder.wirePayload = """{"kind":"compact-started","sessionId":"session-compact","status":"failed"}"""
        holder.submitWirePayload()
        assertTrue(holder.canStartManualCompact())
        assertEquals("上下文压缩失败，请重试", holder.transientNotice)
    }

    @Test
    fun compactRequestGuardBlocksRepeatsWhilePendingOrCompacting() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-compact")
        // 无会话/未连接时按钮不可用（引导与按钮共用闸门）。
        assertEquals(false, holder.canStartManualCompact())
        // 测试缝注入连接：闸门开启（M5：启用路径不再是恒 false 死断言）。
        holder.compactGateForceOpenForTest = true
        assertEquals(true, holder.canStartManualCompact())
        // 压缩中关闸。
        holder.wirePayload = """{"kind":"state","sessionId":"session-compact","isStreaming":false,"isCompacting":true}"""
        holder.submitWirePayload()
        assertEquals(false, holder.canStartManualCompact())
        // 压缩中重复触发被拦：不产生新的发送意图。
        holder.startManualCompact()
        assertTrue(holder.compactRequestsForTest.isEmpty())
        assertEquals(false, holder.canStartManualCompact())
    }

    @Test
    fun compactRequestLifecycleKeepsGateUntilStartThenRecovers() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-compact")
        holder.compactGateForceOpenForTest = true
        // 发出后闸门关（在途）；未收到受理前不可重复触发（M1：闸门不提前释放）。
        holder.startManualCompact()
        assertEquals(listOf("session-compact"), holder.compactRequestsForTest)
        assertEquals(false, holder.canStartManualCompact())
        // compact-started 受理（isCompacting=true 快照）：闸门由压缩中状态接管。
        holder.wirePayload = """{"kind":"compact-started","sessionId":"session-compact","isStreaming":false,"isCompacting":true}"""
        holder.submitWirePayload()
        assertEquals(false, holder.canStartManualCompact())
        assertTrue(holder.transientNotice!!.contains("已请求压缩"))
        // 收尾（compaction_end 归一为 compact/end）后状态可恢复。
        holder.wirePayload = """{"kind":"event","sessionId":"session-compact","seq":1,"time":1.0,"event":{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false}}"""
        holder.submitWirePayload()
        assertEquals(false, holder.selectedSessionCompacting())
        assertEquals(true, holder.canStartManualCompact())
    }

    @Test
    fun compactPendingGateOnlyBlocksItsOwnSession() {
        // R5：A 会话在途不阻塞 B 会话的压缩闸门；提示/状态仍按归属处理。
        val holder = AndroidSharedStateHolder()
        holder.compactGateForceOpenForTest = true
        holder.selectSession("session-a")
        holder.startManualCompact()
        assertEquals(false, holder.canStartManualCompact())
        holder.selectSession("session-b")
        assertEquals(true, holder.canStartManualCompact())
        // 回到 A：在途闸门仍然拦截。
        holder.selectSession("session-a")
        assertEquals(false, holder.canStartManualCompact())
    }

    @Test
    fun concurrentCompactPendingTracksBothSessionsIndependently() {
        // R5-3：按会话独立记账——B 的压缩不覆写 A 的在途闸门；A/B 的记账互不干扰。
        val holder = AndroidSharedStateHolder()
        holder.compactGateForceOpenForTest = true
        holder.selectSession("session-a")
        holder.startManualCompact()
        holder.selectSession("session-b")
        holder.startManualCompact()
        assertEquals(listOf("session-a", "session-b"), holder.compactRequestsForTest)
        // 切回 A：A 的在途闸门仍在（未被 B 覆写），B 的也独立保留。
        holder.selectSession("session-a")
        assertEquals(false, holder.canStartManualCompact())
        holder.selectSession("session-b")
        assertEquals(false, holder.canStartManualCompact())
    }

    @Test
    fun compactWatchJobsTrackSessionsAndRearmCancelsOnlyOwn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holder = AndroidSharedStateHolder(compactWatchScopeForTest = backgroundScope)
        try {
            holder.compactGateForceOpenForTest = true
            holder.selectSession("session-a")
            holder.startManualCompact()
            val firstA = holder.compactWatchJobForTest("session-a")!!
            holder.selectSession("session-b")
            holder.startManualCompact()
            val jobB = holder.compactWatchJobForTest("session-b")!!
            holder.rearmCompactWatchForTest("session-a")
            val secondA = holder.compactWatchJobForTest("session-a")!!
            assertTrue(firstA.isCancelled)
            assertTrue(secondA !== firstA)
            assertTrue(jobB.isActive)
            runCurrent()
            advanceTimeBy(9_000)
            runCurrent()
            assertFalse(holder.canStartManualCompact())
            holder.selectSession("session-a")
            assertFalse(holder.canStartManualCompact())
            holder.reset()
            holder.rearmCompactWatchForTest("session-a")
            assertNull(holder.compactWatchJobForTest("session-a"))
        } finally {
            holder.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun acknowledgedCompactStillWaitingAfterEightSecondsCannotBeRepeated() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val holder = AndroidSharedStateHolder(compactWatchScopeForTest = backgroundScope)
        try {
            holder.compactGateForceOpenForTest = true
            holder.selectSession("session-compact")
            holder.messageDraft = "保留草稿"
            holder.startManualCompact()
            holder.wirePayload = """{"kind":"compact-started","sessionId":"session-compact","isStreaming":true,"isCompacting":false}"""
            holder.submitWirePayload()
            runCurrent()
            advanceTimeBy(9_000)
            runCurrent()
            assertFalse(holder.canStartManualCompact())
            assertEquals("压缩仍在启动，请稍候", holder.transientNotice)
            holder.startManualCompact()
            assertEquals(listOf("session-compact"), holder.compactRequestsForTest)
            assertEquals("保留草稿", holder.messageDraft)
            // 即使错过start，权威结束事件也释放本地等待。
            holder.wirePayload = """{"kind":"event","sessionId":"session-compact","seq":1,"time":1.0,"event":{"type":"compaction_end","reason":"manual","aborted":true,"willRetry":false}}"""
            holder.submitWirePayload()
            assertTrue(holder.canStartManualCompact())
        } finally {
            holder.reset()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun compactRequestTerminalDoesNotShowAnotherSessionsNotice() {
        val holder = AndroidSharedStateHolder()
        holder.compactGateForceOpenForTest = true
        holder.selectSession("session-a")
        holder.startManualCompact()
        holder.selectSession("session-b")
        holder.finishManualCompactRequest("compact", "session-a", "不应显示在B")
        assertNull(holder.transientNotice)
        holder.selectSession("session-a")
        assertTrue(holder.canStartManualCompact())
        holder.startManualCompact()
        holder.finishManualCompactRequest("compact", "session-a", "当前压缩失败")
        assertEquals("当前压缩失败", holder.transientNotice)
        assertTrue(holder.canStartManualCompact())
    }

    @Test
    fun compactAckWithoutCompactingKeepsGateUntilStartOrTimeout() {
        // M1：受理快照 isCompacting=false（网关 liveState 在 fire 后立即回帧）时，
        // 闸门保持到 compact/start 事件到达，不在 ACK 即释放。
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-compact")
        holder.compactGateForceOpenForTest = true
        holder.startManualCompact()
        holder.wirePayload = """{"kind":"compact-started","sessionId":"session-compact","isStreaming":false,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals(false, holder.canStartManualCompact())
        // 压缩真正开始才释放闸门（后续由 isCompacting 接管）。
        holder.wirePayload = """{"kind":"event","sessionId":"session-compact","seq":1,"time":1.0,"event":{"type":"compaction_start","reason":"manual"}}"""
        holder.submitWirePayload()
        assertEquals(false, holder.canStartManualCompact())
        assertTrue(holder.selectedSessionCompacting())
    }

    @Test
    fun secondCompactionRoundStillRefreshesState() {
        // M3/R1：去重键带 seq，同一会话第二轮压缩的 compact/end 不被首轮残留键
        // 吞掉；用 stateRequestsAfterCompactionForTest 断言两轮各补拉一次（R2：
        // 用例必须有判别力，不能只验状态不异常）。
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-compact")
        holder.wirePayload = """{"kind":"event","sessionId":"session-compact","seq":1,"time":1.0,"event":{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false}}"""
        holder.submitWirePayload()
        assertEquals(listOf("session-compact"), holder.stateRequestsAfterCompactionForTest)
        holder.wirePayload = """{"kind":"event","sessionId":"session-compact","seq":2,"time":2.0,"event":{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false}}"""
        holder.submitWirePayload()
        assertEquals(listOf("session-compact", "session-compact"), holder.stateRequestsAfterCompactionForTest)
        assertEquals(false, holder.selectedSessionCompacting())
    }

    @Test
    fun compactStateRefreshSkipsUnselectedSessions() {
        // M4/R1：后台会话的压缩收尾不补拉（不挤当前会话排队中的 state 请求），
        // 切回时由 subscribed 帧重拉。
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-a")
        holder.selectSession("session-b")
        holder.wirePayload = """{"kind":"event","sessionId":"session-a","seq":1,"time":1.0,"event":{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false}}"""
        holder.submitWirePayload()
        assertTrue(holder.stateRequestsAfterCompactionForTest.isEmpty())
    }

    @Test
    fun slashCompactExplainsReasonByBranchNotGenericRingHint() {
        // M2：未连接时不再统一指引去点圆环（那条路同样禁用）。
        val holder = AndroidSharedStateHolder()
        assertEquals(AndroidSharedStateHolder.SlashCommandOutcome.CONSUMED, holder.consumeSlashCommand("/compact"))
        assertTrue(holder.transientNotice!!.contains("会话"))
        holder.selectSession("session-compact")
        holder.consumeSlashCommand("/compact")
        assertTrue(holder.transientNotice!!.contains("尚未连接"))
    }

    @Test
    fun slashCompactReachesRealActionWhenPossibleOtherwiseExplains() {
        val holder = AndroidSharedStateHolder()
        // 无会话：引导不假装完成，给出下一步。
        assertEquals(AndroidSharedStateHolder.SlashCommandOutcome.CONSUMED, holder.consumeSlashCommand("/compact"))
        assertTrue(holder.transientNotice!!.contains("会话"))
        // 有会话但未连接/闸门关：解释原因，不静默。
        holder.selectSession("session-compact")
        assertEquals(AndroidSharedStateHolder.SlashCommandOutcome.CONSUMED, holder.consumeSlashCommand("/compact"))
        assertNotNull(holder.transientNotice)
        assertTrue(holder.messageDraft.isEmpty())
        holder.compactGateForceOpenForTest = true
        holder.consumeSlashCommand("/compact")
        assertEquals(listOf("session-compact"), holder.compactRequestsForTest)
        assertFalse(holder.canStartManualCompact())
    }

    @Test
    fun postCompactionStatsRemainUnknownInsteadOfRecycledPercent() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-compact")
        holder.wirePayload = """{"kind":"state","sessionId":"session-compact","isStreaming":false,"isCompacting":false,"contextUsage":{"percent":74.0,"tokens":148000,"contextWindow":200000}}"""
        holder.submitWirePayload()
        assertEquals(74.0, holder.snapshot.sessionStates["session-compact"]!!.contextUsage!!.percent!!, 0.01)
        // 压缩后 SDK 统计合法 unknown：不沿用旧 percent 伪造改善。
        holder.wirePayload = """{"kind":"state","sessionId":"session-compact","isStreaming":false,"isCompacting":false,"contextUsage":{"percent":null,"tokens":null,"contextWindow":200000}}"""
        holder.submitWirePayload()
        val usage = holder.snapshot.sessionStates["session-compact"]!!.contextUsage!!
        assertNull(usage.percent)
        assertNull(usage.tokens)
    }

    @Test
    fun compactionStateBlocksSendingBeforeReachingTransport() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-c")
        holder.wirePayload = """
            {"kind":"state","sessionId":"session-c","isStreaming":false,"isCompacting":true}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        // pi 原生：压缩进行中 prompt 直接拒绝（agent-session 守卫）。移动端在
        // 客户端拦截（不发帧→不产生网关错误弹窗），以该谓词为闸门。
        assertTrue(holder.selectedSessionCompacting())

        holder.wirePayload = """
            {"kind":"state","sessionId":"session-c","isStreaming":false,"isCompacting":false}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        assertTrue(!holder.selectedSessionCompacting())
    }

    @Test
    fun `historyFrameLatestEntryIdDrivesBehindLatestPredicate`() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-jump")
        // 首页（entryIds 尾 == latestEntryId）= 活跃链尾即最新 → 不落后。
        holder.wirePayload = """
            {"kind":"history","sessionId":"session-jump","entryIds":["e1","e2"],
             "latestEntryId":"e2","hasMore":false,"oldestEntryId":"e1",
             "messages":[{"role":"user","content":[{"type":"text","text":"a"}]},
                         {"role":"assistant","content":[{"type":"text","text":"b"}]}]}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        assertTrue("链尾==latest 不应提示落后", !holder.isBehindLatest("session-jump"))
        assertEquals("e2", holder.latestEntryId("session-jump"))

        // 主线又前进了（重拉的 latestEntryId 超过链尾）→ 落后成立。
        holder.wirePayload = """
            {"kind":"state","sessionId":"session-jump","isStreaming":false,"isCompacting":false}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        holder.wirePayload = """
            {"kind":"history","sessionId":"session-jump","entryIds":["e1","e2"],
             "latestEntryId":"e9","hasMore":false,"oldestEntryId":"e1",
             "messages":[{"role":"user","content":[{"type":"text","text":"a"}]},
                         {"role":"assistant","content":[{"type":"text","text":"b"}]}]}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        assertTrue("latestEntryId 超前链尾应提示落后", holder.isBehindLatest("session-jump"))
        assertEquals("e9", holder.latestEntryId("session-jump"))
    }

    @Test
    fun stopIsIgnoredWithinCooldownAfterSend() {
        // 同位按钮防误触（发送/停止同一位、随状态换形态）：发送后极短窗口内的
        // "停止"是连点的第二下。实测发图后 87ms 被 abort，图已进 pi 但用户收不到
        // 任何回复，也不会报错（模型还没被调用）。
        val holder = AndroidSharedStateHolder()
        var now = 1_000L
        holder.elapsedRealtimeMs = { now }
        // 从未发送过：不在窗口内（初值 0 与 elapsed 相距很远）。
        assertEquals(false, holder.stopInCooldown())
        holder.markPromptSent()
        assertEquals(true, holder.stopInCooldown())
        now += 499
        assertEquals(true, holder.stopInCooldown())
        now += 1
        assertEquals(false, holder.stopInCooldown())
    }

    @Test
    fun providerUnconfirmedResultTextOnlyMarksConfigWritesUnconfirmed() {
        // P3 文案面回归：取消/超时的提示只有配置写操作（保存/删除）才说
        // “未确认”（请求可能已达网关，落盘状态不明）；providers 列表刷新、
        // 连接测试、拉模型是读操作，失败不得冒充保存/删除结论。
        // 端到端路径（init 块三处终态 + showProviderResult 的 4s 清除）依赖
        // AndroidAppGraph（构造需 Application），纯 JVM 单测不可达，见交付说明。
        for (outcome in listOf("已取消", "超时")) {
            assertTrue(
                AndroidSharedStateHolder.providerUnconfirmedResultText("provider-put", outcome)
                    .contains("未确认")
            )
            assertTrue(
                AndroidSharedStateHolder.providerUnconfirmedResultText("provider-delete", outcome)
                    .contains("未确认")
            )
        }
        for (requestType in listOf("providers", "provider-test", "provider-models")) {
            for (outcome in listOf("已取消", "超时")) {
                val text = AndroidSharedStateHolder.providerUnconfirmedResultText(requestType, outcome)
                assertFalse("读操作文案不应出现「未确认」：$text", text.contains("未确认"))
                // 终态仍可辨识：说明是 Provider 请求失败并给出取消/超时方向。
                assertTrue("读操作文案应保留请求失败方向：$text", text.contains("Provider 请求$outcome"))
            }
        }
    }

    // ---- 忙时吸附派发的可靠性（内容在本地 Runtime 接受前不允许丢失）----

    private fun singleTestImage(): AndroidPreparedImage = AndroidPreparedImage(
        GatewayOutgoingImage("image/png", "AQID"),
        width = 1,
        height = 1,
        byteCount = 3
    )

    @Test
    fun rejectedDispatchKeepsPendingContentRetryableUntilAccepted() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "待派发"
        holder.setPreparedImagesForTest(listOf(singleTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        // 忙时点发送：暂存吸附 + 清空输入区（原“暂存→steer/followUp”语义不变）。
        holder.capturePendingDispatchForTest(submission)
        assertEquals(submission, holder.pendingDispatch)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)

        // Runtime 接受前条目仍可见；失败无需重建或回填草稿。
        holder.dispatchPending("steer")
        assertEquals(submission, holder.pendingDispatch)
        assertEquals(submission, holder.pendingDispatchInFlightForTest)

        // 本地 Runtime 拒绝（未连接/发送失败）：内容放回吸附条，可原样重试，
        // 不回填编辑框（不把吸附草稿泼回当前会话输入）。
        holder.settlePendingDispatchForTest(submission, sent = false)
        assertEquals(submission, holder.pendingDispatch)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
        assertEquals(null, holder.pendingDispatchInFlightForTest)

        // 重试 + 接受：吸附才收走。
        holder.dispatchPending("followUp")
        holder.settlePendingDispatchForTest(submission, sent = true)
        assertEquals(null, holder.pendingDispatch)
        assertEquals(null, holder.pendingDispatchInFlightForTest)
    }

    @Test
    fun repeatedDispatchTapsDuringFlightTrackASingleAttempt() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "只发一次"
        val submission = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(submission)
        holder.dispatchPending("steer")
        val inFlight = holder.pendingDispatchInFlightForTest
        assertEquals(submission, inFlight)
        // 在途中的第二次点击：不产生新的派发轨（同一份内容只跟踪一次）。
        holder.dispatchPending("steer")
        assertEquals(inFlight, holder.pendingDispatchInFlightForTest)
        holder.settlePendingDispatchForTest(submission, sent = true)
        assertEquals(null, holder.pendingDispatchInFlightForTest)
    }

    @Test
    fun inFlightDispatchPreservesNewInputAndIgnoresLateCallbacks() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "第一份"
        val first = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(first)
        holder.dispatchPending("steer")

        // 缓存图片/等待 Runtime 期间编辑器可以继续输入，但不替换在途条目。
        holder.messageDraft = "新输入"
        val second = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(second)
        assertEquals(first, holder.pendingDispatch)
        assertEquals("新输入", holder.messageDraft)
        holder.cancelPendingDispatch()
        assertEquals(first, holder.pendingDispatch)
        assertEquals("新输入", holder.messageDraft)

        holder.settlePendingDispatchForTest(first, sent = false)
        assertEquals(first, holder.pendingDispatch)
        assertEquals("新输入", holder.messageDraft)
        holder.dispatchPending("steer")
        holder.settlePendingDispatchForTest(first, sent = true)
        assertEquals(null, holder.pendingDispatch)
        assertEquals("新输入", holder.messageDraft)

        holder.capturePendingDispatchForTest(second)
        holder.dispatchPending("followUp")
        holder.settlePendingDispatchForTest(first, sent = false)
        assertEquals(second, holder.pendingDispatch)
        assertEquals(second, holder.pendingDispatchInFlightForTest)
        holder.settlePendingDispatchForTest(second, sent = true)
    }

    @Test
    fun cancelPendingDispatchNeverSpillsForeignSessionDraftIntoEditor() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-old")
        holder.messageDraft = "旧会话草稿"
        val stale = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(stale)
        holder.selectSession("session-current")
        // X 撤销只恢复归属会话的输入框：绝不能把别处会话的草稿灌进当前输入，
        // 随下一条消息发往错误上下文（吸附条保留待用户切回去处理）。
        holder.cancelPendingDispatch()
        assertTrue(holder.messageDraft != "旧会话草稿")
        assertEquals(stale, holder.pendingDispatch)
        holder.selectSession("session-old")
        holder.cancelPendingDispatch()
        assertEquals("旧会话草稿", holder.messageDraft)
        assertEquals(null, holder.pendingDispatch)
    }

    @Test
    fun capturingInAnotherSessionKeepsBothDraftsInTheirOriginalPlaces() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-a")
        holder.messageDraft = "A 的草稿"
        val first = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(first)

        holder.selectSession("session-b")
        holder.messageDraft = "B 的新输入"
        val images = listOf(singleTestImage())
        holder.setPreparedImagesForTest(images)
        holder.capturePendingDispatchForTest(holder.captureMessageSubmissionForTest())

        assertEquals(first, holder.pendingDispatch)
        assertEquals("B 的新输入", holder.messageDraft)
        assertEquals(images, holder.preparedImages)
    }

    @Test
    fun replacingPendingDispatchKeepsUriImageMappingIntact() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("session-a")
        holder.messageDraft = "第一份"
        val firstImage = singleTestImage()
        holder.setPreparedImagesForTest(listOf(firstImage), uris = listOf("content://first"))
        holder.capturePendingDispatchForTest(holder.captureMessageSubmissionForTest())
        // 首次吸附：输入区（文本/图片/uri）一起搬进吸附条。
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
        assertEquals(0, holder.preparedUris.size)

        // 旧条目仍在时又编了新稿（B 文本 + B 图）：新内容顶替，旧内容回编辑器。
        holder.messageDraft = "第二份"
        val secondImage = preparedTestImage()
        holder.setPreparedImagesForTest(listOf(secondImage), uris = listOf("content://second"))
        holder.capturePendingDispatchForTest(holder.captureMessageSubmissionForTest())
        assertEquals("第二份", holder.pendingDispatch?.draft)
        assertEquals("第一份", holder.messageDraft)
        // 旧图片与旧 uri 必须同序成对回填，托盘勾选态不能指向 B 的图片。
        assertEquals(listOf(firstImage), holder.preparedImages)
        assertEquals(listOf("content://first"), holder.preparedUris)

        // 取回第二份时第一份仍在编辑器：两份交换，不能覆盖丢失任何一份。
        holder.cancelPendingDispatch()
        assertEquals("第一份", holder.pendingDispatch?.draft)
        assertEquals(listOf(firstImage), holder.pendingDispatch?.images)
        assertEquals(listOf("content://first"), holder.pendingDispatch?.uris)
        assertEquals("第二份", holder.messageDraft)
        assertEquals(listOf(secondImage), holder.preparedImages)
        assertEquals(listOf("content://second"), holder.preparedUris)
    }

    @Test
    fun takingBackPendingPreservesNewDraftAndAttachmentsBySwapping() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "待发 A"
        val pendingImage = singleTestImage()
        holder.setPreparedImagesForTest(listOf(pendingImage), listOf("content://a"))
        holder.capturePendingDispatchForTest(holder.captureMessageSubmissionForTest())
        holder.messageDraft = "后来编辑的 B"
        val newImage = preparedTestImage()
        holder.setPreparedImagesForTest(listOf(newImage), listOf("content://b"))
        holder.cancelPendingDispatch()
        assertEquals("待发 A", holder.messageDraft)
        assertEquals(listOf(pendingImage), holder.preparedImages)
        assertEquals(listOf("content://a"), holder.preparedUris)
        assertEquals("后来编辑的 B", holder.pendingDispatch?.draft)
        assertEquals(listOf(newImage), holder.pendingDispatch?.images)
        assertEquals(listOf("content://b"), holder.pendingDispatch?.uris)
        assertTrue(holder.transientNotice!!.contains("新草稿保留"))
    }

    @Test
    fun disconnectedComposerSendKeepsDraftAndExplainsWhy() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "不能悄悄丢弃"
        val image = singleTestImage()
        holder.setPreparedImagesForTest(listOf(image), listOf("content://a"))
        holder.sendComposerDraft()
        assertEquals("不能悄悄丢弃", holder.messageDraft)
        assertEquals(listOf(image), holder.preparedImages)
        assertEquals(listOf("content://a"), holder.preparedUris)
        assertTrue(holder.transientNotice!!.contains("尚未连接网关"))
    }

    @Test
    fun concurrentSelectionOfSamePhotoProducesOneAttachment() {
        val holder = AndroidSharedStateHolder()
        val token = holder.beginImagePreparation("content://a")!!
        assertNull(holder.beginImagePreparation("content://a"))
        assertEquals(setOf("content://a"), holder.preparingPhotoUris)
        assertTrue(holder.completeImagePreparation("content://a", token, singleTestImage()))
        assertFalse(holder.completeImagePreparation("content://a", token, preparedTestImage()))
        assertNull(holder.beginImagePreparation("content://a"))
        assertEquals(1, holder.preparedImages.size)
        assertEquals(listOf("content://a"), holder.preparedUris)
    }

    @Test
    fun cancellingPhotoRejectsLateResultAndDoesNotCancelReselection() {
        val holder = AndroidSharedStateHolder()
        val old = holder.beginImagePreparation("content://a")!!
        holder.cancelImagePreparation("content://a")
        assertTrue(holder.preparingPhotoUris.isEmpty())
        val current = holder.beginImagePreparation("content://a")!!
        assertFalse(holder.completeImagePreparation("content://a", old, singleTestImage()))
        assertEquals(setOf("content://a"), holder.preparingPhotoUris)
        val selected = preparedTestImage()
        assertTrue(holder.completeImagePreparation("content://a", current, selected))
        assertEquals(listOf(selected), holder.preparedImages)
        assertEquals(listOf("content://a"), holder.preparedUris)
    }

    @Test
    fun removedSystemPickerAttachmentKeepsRemainingSourceMapping() {
        val holder = AndroidSharedStateHolder()
        val first = singleTestImage()
        val second = preparedTestImage()
        holder.setPreparedImagesForTest(listOf(first, second), listOf("content://picker/a", "content://picker/b"))
        holder.removePreparedImage(0)
        assertEquals(listOf(second), holder.preparedImages)
        assertEquals(listOf("content://picker/b"), holder.preparedUris)
        holder.removePreparedImage(0)
        assertTrue(holder.preparedImages.isEmpty())
        assertTrue(holder.preparedUris.isEmpty())
    }

    @Test
    fun takeBackWaitsForInFlightPhotoInsteadOfMixingDraftAttachments() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "A"
        holder.capturePendingDispatchForTest(holder.captureMessageSubmissionForTest())
        holder.messageDraft = "B"
        val pending = holder.pendingDispatch
        holder.beginImagePreparation("content://b")
        holder.cancelPendingDispatch()
        assertEquals("B", holder.messageDraft)
        assertEquals(pending, holder.pendingDispatch)
        assertTrue(holder.transientNotice!!.contains("稍候再取回"))
    }

    @Test
    fun sendClickFreezesCwdAndModelAgainstMidFlightChanges() {
        val holder = AndroidSharedStateHolder()
        holder.prepareNewSession()
        holder.selectDirectory("/proj/a")
        holder.selectModel("prov", "model-a", "high")
        val submission = holder.captureMessageSubmissionForTest()
        // 点击瞬间的 cwd / 模型预选必须定格进快照，供延迟执行的发送协程使用。
        assertEquals("/proj/a", submission.cwd)
        assertEquals(GatewayModelSelection("prov", "model-a", "high"), submission.model)

        // gatewayScope 排队/图片缓存窗口内用户换目录、换模型。
        holder.selectDirectory("/proj/b")
        holder.selectModel("prov", "model-b", null)

        // 发送成功回调只消费本次实际携带的预选；用户其后新选的 model 必须保留。
        holder.applyMessageSendResultForTest(submission, sent = true)
        assertEquals(GatewayModelSelection("prov", "model-b", null), holder.pendingModelSelection)
        // 快照本身不可变：上下文按点击时那套发出。
        assertEquals("/proj/a", submission.cwd)
        assertEquals(GatewayModelSelection("prov", "model-a", "high"), submission.model)
    }

    // ---- 第1项A：带图发送本地预览（发送边界注入 + 真实产品方法驱动）----

    private var previewIdSequence = 0
    private var testNowMs = 1_000L

    private fun preparedTestImage(): AndroidPreparedImage = AndroidPreparedImage(
        GatewayOutgoingImage("image/png", "AQID", attachmentId = "img-draft-$previewIdSequence"),
        width = 64,
        height = 32,
        byteCount = 3
    )

    private fun newPreviewHolder(): AndroidSharedStateHolder {
        val holder = AndroidSharedStateHolder()
        holder.nextOutgoingAttachmentId = { "sent-${++previewIdSequence}" }
        // JVM 单测无 SystemClock：注入可控单调时钟（sent/未确认计时用）。
        holder.elapsedRealtimeMs = { testNowMs }
        return holder
    }

    private fun userEcho(sessionId: String, vararg attachmentIds: String): SessionEvent =
        SessionEvent(
            sessionId,
            1,
            1.0,
            GatewayEvent(
                type = "user/message",
                text = "看图",
                images = attachmentIds.map { GatewayImageAttachment(it, "image/png", 3) }
            )
        )

    /** 普通发送实发（生产 sendMessage 空闲路径的同段同步）；[sent] 为本地结局。 */
    private fun driveSend(
        holder: AndroidSharedStateHolder,
        clearInputOnSuccess: Boolean = true
    ): Pair<AndroidSharedStateHolder.MessageSubmission, AndroidSharedStateHolder.PreparedOutgoingSend> {
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = clearInputOnSuccess)
        return submission to outgoing
    }

    @Test
    fun imageOnlyAndTextWithImagePreviewExistBeforeAnyServerFrame() {
        val holder = newPreviewHolder()
        // 纯图：无任何服务端帧时预览已存在，渲染数据带内嵌 base64/尺寸。
        val image = preparedTestImage()
        holder.setPreparedImagesForTest(listOf(image))
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        assertEquals(1, holder.outgoingPreviews.size)
        val preview = holder.outgoingPreviews.single()
        assertEquals(OutgoingImagePreviewState.SENDING, preview.state)
        assertEquals(listOf("sent-1"), preview.attachmentIds)
        assertEquals("sent-1", preview.images.single().attachmentId)
        assertEquals("AQID", preview.images.single().dataBase64)
        assertEquals(64, preview.images.single().width)
        assertEquals(32, preview.images.single().height)
        assertEquals("", preview.draftText)
        assertEquals(listOf(image), preview.restoreImages)

        // 图文：草稿文本随预览保留（恢复草稿用）。
        val holder2 = newPreviewHolder()
        holder2.messageDraft = "看图"
        holder2.setPreparedImagesForTest(listOf(preparedTestImage()))
        holder2.beginOutgoingSend(holder2.captureMessageSubmissionForTest(), createPreview = true)
        assertEquals("看图", holder2.outgoingPreviews.single().draftText)
        assertNotNull(outgoing.sendToken)
    }

    @Test
    fun textOnlySendDoesNotCreatePreview() {
        val holder = newPreviewHolder()
        holder.messageDraft = "纯文本"
        val outgoing = holder.beginOutgoingSend(holder.captureMessageSubmissionForTest(), createPreview = true)
        // 纯文本无预览，但仍有请求身份 token（进等待队列防串绑）。
        assertTrue(outgoing.sendToken.isNotBlank())
        assertEquals(0, holder.outgoingPreviews.size)
    }

    @Test
    fun busyQueuedDispatchKeepsBarSemanticsWithoutPreviewBubble() {
        // 忙时点发送走吸附语义：不建预览气泡，不伪装成已执行。
        val holder = newPreviewHolder()
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        holder.messageDraft = "排队中"
        val submission = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(submission)
        assertEquals(0, holder.outgoingPreviews.size)
        assertEquals(submission, holder.pendingDispatch)
        // 吸附派发实发同样不建预览（createPreview=false），但仍有请求身份。
        val outgoing = holder.beginOutgoingSend(submission, createPreview = false)
        assertTrue(outgoing.sendToken.isNotBlank())
        assertEquals(0, holder.outgoingPreviews.size)
    }

    @Test
    fun pendingTextSendLaneDoesNotBindLaterImagePreview() {
        // 纯文本 T 实发（无预览但占 message lane），T 未 ACK 期间发图 B：
        // T 的 sent 帧不得消费 B 的 token 或绑定 B 的预览。
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "纯文本先发"
        val textSubmission = holder.captureMessageSubmissionForTest()
        val textOutgoing = holder.beginOutgoingSend(textSubmission, createPreview = true)
        holder.settleOutgoingSendLocally(textSubmission, textOutgoing, sent = true, clearInputOnSuccess = true)

        // 图片 B 实发（有预览）。
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val imageSubmission = holder.captureMessageSubmissionForTest()
        val imageOutgoing = holder.beginOutgoingSend(imageSubmission, createPreview = true)
        assertEquals(1, holder.outgoingPreviews.size)

        // T 的 sent 帧先到（B 还在缓存 I/O）：只消费 T 的 token，B 预览不动。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals(1, holder.outgoingPreviews.size)
        assertEquals(OutgoingImagePreviewState.SENDING, holder.outgoingPreviews.single().state)

        // B 随后 request-busy 失败（本地）：只收敛 B 自己的 token。
        holder.settleOutgoingSendLocally(imageSubmission, imageOutgoing, sent = false, clearInputOnSuccess = true)
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)

        // B 重发受理：sent 帧此时消费 B 的 token，确认绑定。
        holder.messageDraft = ""
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val retry = holder.captureMessageSubmissionForTest()
        val retryOutgoing = holder.beginOutgoingSend(retry, createPreview = true)
        holder.settleOutgoingSendLocally(retry, retryOutgoing, sent = true, clearInputOnSuccess = true)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        // B 失败预览保留可手动恢复；retry 已确认（各自按 token 归属；纯文本 T
        // 不消耗 attachmentId，B 即 sent-1、retry 即 sent-2）。
        val afterRetry = holder.outgoingPreviews.associate { it.attachmentIds.single() to it.state }
        assertEquals(2, afterRetry.size)
        assertEquals(OutgoingImagePreviewState.FAILED, afterRetry["sent-1"])
        assertEquals(OutgoingImagePreviewState.CONFIRMED, afterRetry["sent-2"])
    }

    @Test
    fun sentConfirmsThenEchoRemovesLeavingSingleFormalMessage() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        assertEquals("看图", holder.messageDraft)
        assertEquals(1, holder.outgoingPreviews.size)

        // sent 帧（preflight 受理）：确认不移除，预览仍唯一显示。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals(OutgoingImagePreviewState.CONFIRMED, holder.outgoingPreviews.single().state)
        assertEquals("", holder.messageDraft)

        // 正式回显（message_end user 带同组 id）：预览移除；投影里只剩正式一条。
        holder.wirePayload = """
            {"kind":"event","sessionId":"s-1","seq":1,"time":1.0,
             "event":{"type":"message_end","message":{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","mimeType":"image/png","attachmentId":"sent-1","dataBytes":3}]}}}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        holder.handleOutgoingEcho("s-1", listOf(userEcho("s-1", "sent-1")))
        assertEquals(0, holder.outgoingPreviews.size)
        val userItems = holder.snapshot.conversation.filter {
            it.kind == ConversationItemKind.USER
        }
        assertEquals(1, userItems.size)
        assertEquals("sent-1", userItems.single().images.single().attachmentId)
    }

    @Test
    fun historyRestoreCollapsesPreviewLeavingSingleFormalMessage() {
        // history 恢复正式 user 图片：归一事件恒空，回显只能从 frame.messages
        // 原始 content 数组的整组 attachmentId 认领；预览残留即双气泡。
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        val sentId = outgoing.images.single().outgoing.attachmentId
        // sent 已受理，但断连漏掉实时回显；观察超时后仍可由 history 收敛。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals(OutgoingImagePreviewState.CONFIRMED, holder.outgoingPreviews.single().state)
        holder.markStaleOutgoingPreviews(testNowMs + 45_001L)
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)

        // 经实际帧注入路径收敛，不直接调用 handleOutgoingEcho。
        holder.wirePayload = """
            {"kind":"history","sessionId":"s-1","entryIds":["e1"],"latestEntryId":"e1",
             "hasMore":false,"oldestEntryId":"e1",
             "messages":[{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","mimeType":"image/png","attachmentId":"$sentId","dataBytes":3}]}]}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        assertEquals(0, holder.outgoingPreviews.size)
        val userItems = holder.snapshot.conversation.filter {
            it.kind == ConversationItemKind.USER
        }
        assertEquals(1, userItems.size)
        assertEquals(sentId, userItems.single().images.single().attachmentId)
    }

    @Test
    fun eventEchoFrameCollapsesPreviewThroughInjectionPath() {
        // event 完整帧经 submitWirePayload 注入路径自动收敛（共用 postFrame）。
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        holder.wirePayload = """
            {"kind":"event","sessionId":"s-1","seq":1,"time":1.0,
             "event":{"type":"message_end","message":{"role":"user","content":[
                {"type":"text","text":"看图"},
                {"type":"image","mimeType":"image/png","attachmentId":"sent-1","dataBytes":3}]}}}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        assertEquals(0, holder.outgoingPreviews.size)
        assertEquals(1, holder.snapshot.conversation.filter { it.kind == ConversationItemKind.USER }.size)
    }

    @Test
    fun historyEchoClaimsOnlyOwnSessionOwnGroupUserImages() {
        // 他会话 / 错 ids / assistant 图都不能认领预览。
        fun historyPayload(sessionId: String, attachmentId: String, role: String) = """
            {"kind":"history","sessionId":"$sessionId","entryIds":["e1"],"latestEntryId":"e1",
             "hasMore":false,"oldestEntryId":"e1",
             "messages":[{"role":"$role","content":[
                {"type":"image","mimeType":"image/png","attachmentId":"$attachmentId","dataBytes":3}]}]}
        """.trimIndent().replace("\n", "")
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        assertEquals(1, holder.outgoingPreviews.size)

        // 错会话：同 id 也不认领。
        holder.wirePayload = historyPayload("s-other", "sent-1", "user")
        holder.submitWirePayload()
        assertEquals(1, holder.outgoingPreviews.size)
        // 错 ids：同会话也不认领。
        holder.wirePayload = historyPayload("s-1", "sent-9", "user")
        holder.submitWirePayload()
        assertEquals(1, holder.outgoingPreviews.size)
        // assistant 图（同会话同 id）：不认领。
        holder.wirePayload = historyPayload("s-1", "sent-1", "assistant")
        holder.submitWirePayload()
        assertEquals(1, holder.outgoingPreviews.size)
        // 同会话同 id 的 user 图片：恰好认领一次。
        holder.wirePayload = historyPayload("s-1", "sent-1", "user")
        holder.submitWirePayload()
        assertEquals(0, holder.outgoingPreviews.size)
    }

    @Test
    fun historyEchoDoesNotClearDraftEditedAfterAutoRestore() {
        // 本地失败自动回填后用户重新编辑：history 迟到收敛收走预览，但不清新稿。
        //（服务端拒绝已按 F10 收窄为不自动回填，回填搭建走本地失败路径。）
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        // 发送途中用户清空输入区（文本+托盘）→ 本地失败时输入区全空，触发自动回填。
        holder.setPreparedImagesForTest(emptyList())
        holder.messageDraft = ""
        holder.settleOutgoingSendLocally(submission, outgoing, sent = false, clearInputOnSuccess = true)
        assertEquals("看图", holder.messageDraft)
        // 用户改稿（代次变）。
        holder.messageDraft = "改过的新稿"
        holder.wirePayload = """
            {"kind":"history","sessionId":"s-1","entryIds":["e1"],"latestEntryId":"e1",
             "hasMore":false,"oldestEntryId":"e1",
             "messages":[{"role":"user","content":[
                {"type":"image","mimeType":"image/png","attachmentId":"sent-1","dataBytes":3}]}]}
        """.trimIndent().replace("\n", "")
        holder.submitWirePayload()
        assertEquals(0, holder.outgoingPreviews.size)
        assertEquals("改过的新稿", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
    }

    @Test
    fun echoArrivingFirstStillAcceptsLaterSentFrame() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        // echo 先到：预览移除（正式消息已到）。
        holder.handleOutgoingEcho("s-1", listOf(userEcho("s-1", "sent-1")))
        assertEquals(0, holder.outgoingPreviews.size)
        // 迟到的旧 sent 帧：无 SENDING 可确认，幂等不报错。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals(0, holder.outgoingPreviews.size)
    }

    @Test
    fun sentFrameAutoSelectionStillClearsNewSessionDraftViaPreviewBinding() {
        val holder = newPreviewHolder()
        holder.prepareNewSession()
        holder.messageDraft = "新会话首图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        assertEquals(null, submission.sessionId)
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        // sent 帧先到（真实 wire 路径）：SharedMobileStore 自动选中新会话，预览绑定真实 id。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-new","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals("s-new", holder.snapshot.selectedSessionId)
        assertEquals("s-new", holder.outgoingPreviews.single().sessionId)
        // 本地 sent 结果后到：同次发送的自动绑定（预览归属区分用户换会话）→ 照常清草稿。
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
        assertEquals(1L, holder.successfulMessageSendCount)
    }

    @Test
    fun userSwitchingSessionAfterNewSessionSendKeepsDraftWithItsOwner() {
        val holder = newPreviewHolder()
        holder.prepareNewSession()
        holder.messageDraft = "新会话首图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        // 切换后不把未确认稿带入其它会话；回到原新建页可恢复。
        holder.selectSession("other-session")
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("", holder.messageDraft)
        assertEquals(0, holder.preparedImages.size)
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    @Test
    fun lateSentAfterEchoRemovalConsumesOwnTokenOnly() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (first, out1) = driveSend(holder)
        assertEquals(first.sessionId, "s-1")
        // echo 先到：预览1移除。
        holder.handleOutgoingEcho("s-1", listOf(userEcho("s-1", "sent-1")))
        assertEquals(0, holder.outgoingPreviews.size)

        // 新请求 B（同会话）：真实网络中会被 request-busy 拒绝（单 message lane）。
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val second = holder.captureMessageSubmissionForTest()
        val out2 = holder.beginOutgoingSend(second, createPreview = true)
        holder.settleOutgoingSendLocally(second, out2, sent = false, clearInputOnSuccess = true)
        assertEquals(1, holder.outgoingPreviews.size)
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
        // 旧 A 的 sent 帧迟到：只消费 A 自己的 token，不把 B 绑成已受理。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)

        // 用户重发（手动）：受理后 sent 帧正常确认；B 失败预览保留（不自动清）。
        val retry = holder.captureMessageSubmissionForTest()
        assertEquals(second.draft, retry.draft)
        val out3 = holder.beginOutgoingSend(retry, createPreview = true)
        holder.settleOutgoingSendLocally(retry, out3, sent = true, clearInputOnSuccess = true)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        val afterRetry = holder.outgoingPreviews.associate { it.attachmentIds.single() to it.state }
        assertEquals(OutgoingImagePreviewState.CONFIRMED, afterRetry["sent-3"])
        assertEquals(OutgoingImagePreviewState.FAILED, afterRetry["sent-2"])
    }

    @Test
    fun duplicateImageSendsGetIndependentIdsAndMatchOnlyOwnEcho() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        // 第二次实发前输入区回填同样的图（模拟重复发送同图）。
        holder.messageDraft = ""
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        assertEquals(2, holder.outgoingPreviews.size)
        val ids = holder.outgoingPreviews.map { it.attachmentIds.single() }
        assertTrue(ids[0] != ids[1])
        // 第一条的 echo 只移除第一条。
        holder.handleOutgoingEcho("s-1", listOf(userEcho("s-1", ids[0])))
        assertEquals(1, holder.outgoingPreviews.size)
        assertEquals(ids[1], holder.outgoingPreviews.single().attachmentIds.single())
    }

    @Test
    fun requestBusyRejectionOnlyFailsItsOwnToken() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, out1) = driveSend(holder)
        // A 受理在途（sent 未到），B 被 request-busy 拒绝：只有 B 失败。
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val second = holder.captureMessageSubmissionForTest()
        val out2 = holder.beginOutgoingSend(second, createPreview = true)
        holder.settleOutgoingSendLocally(second, out2, sent = false, clearInputOnSuccess = true)
        val states = holder.outgoingPreviews.associate { it.attachmentIds.single() to it.state }
        assertEquals(OutgoingImagePreviewState.SENDING, states["sent-1"])
        assertEquals(OutgoingImagePreviewState.FAILED, states["sent-2"])
        // A 的 sent 帧到：只确认 A，不因 B 的失败误伤。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        val confirmed = holder.outgoingPreviews.associate { it.attachmentIds.single() to it.state }
        assertEquals(OutgoingImagePreviewState.CONFIRMED, confirmed["sent-1"])
        assertEquals(OutgoingImagePreviewState.FAILED, confirmed["sent-2"])
    }

    @Test
    fun timeoutMeansUnconfirmedNotFailedAndLateEchoStillCollapses() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        // 请求超时未确认：保留原输入，不计受理、不自动重发。
        holder.handleOutgoingSendUnconfirmed()
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)
        assertEquals(0L, holder.successfulMessageSendCount)
        // 迟到的正式回显：UNCONFIRMED 预览照样收走（避免双气泡）。
        holder.handleOutgoingEcho("s-1", listOf(userEcho("s-1", outgoing.images.single().outgoing.attachmentId)))
        assertEquals(0, holder.outgoingPreviews.size)
    }

    /** Finding 3（跨会话回归）：A 在 s-1 超时出队后用户切到 s-2 再发一条带图消息，
     *  A 的迟到 sent 不得消费 B 的 token，也不得把 B 的预览绑到 s-1。sent 帧只有
     *  kind/sessionId/mode（请求不带 correlationId），会话就是唯一可用的关联。 */
    @Test
    fun lateSentFromOtherSessionKeepsNextSessionPreviewPending() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图一"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        // 请求超时：token 出队，预览只转未确认。
        holder.handleOutgoingSendUnconfirmed()
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.first().state)

        holder.selectSession("s-2")
        holder.messageDraft = "看图二"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        assertEquals("s-2", holder.outgoingPreviews.last().sessionId)

        // A 的迟到 sent：队头是 B（s-2），会话不符 → 丢弃，B 不动。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals(OutgoingImagePreviewState.SENDING, holder.outgoingPreviews.last().state)
        assertEquals("s-2", holder.outgoingPreviews.last().sessionId)

        // B 自己的 sent 照常确认，绑定仍是 s-2。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-2","mode":"followUp"}"""
        holder.submitWirePayload()
        val confirmed = holder.outgoingPreviews.last()
        assertEquals(OutgoingImagePreviewState.CONFIRMED, confirmed.state)
        assertEquals("s-2", confirmed.sessionId)
    }

    @Test
    fun serverRejectConvergesFailedWithoutAutoRestoringDraft() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        // 服务端明确拒绝：FAILED收敛，原编辑内容始终保留；不会自动重发
        //（F10 收窄：带图自动回填仅保留在本地失败路径），FAILED 预览保留手动恢复。
        holder.wirePayload = """{"kind":"error","requestType":"message","code":"prompt-rejected","message":"无模型"}"""
        holder.submitWirePayload()
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
        assertEquals("看图", holder.messageDraft)
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    /**
     * 新会话首条纯文本的实发同步段（与生产 appGraphSend 同序：beginOutgoingSend →
     * rememberNewSessionTextForReject；sendInFlight 由 sendMessage 置位）。本地回写
     * [settle] 为 false 时留给调用方决定何时 settle（模拟拒绝先于回写到达）。
     */
    private fun driveNewSessionTextSend(
        holder: AndroidSharedStateHolder,
        settle: Boolean = true,
    ): Pair<AndroidSharedStateHolder.MessageSubmission, AndroidSharedStateHolder.PreparedOutgoingSend> {
        holder.setSendInFlightForTest(true)
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        holder.rememberNewSessionTextForReject(submission, outgoing, createPreview = true, clearInputOnSuccess = true)
        if (settle) holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        return submission to outgoing
    }

    private fun rejectMessage(holder: AndroidSharedStateHolder) {
        holder.wirePayload = """{"kind":"error","requestType":"message","code":"bad-request","message":"cwd 不存在"}"""
        holder.submitWirePayload()
    }

    @Test
    fun recalledQueueMatchesOperationAndPreservesNewDraftAndAttachments() {
        val holder = newPreviewHolder()
        holder.selectSession("s-recall")
        holder.messageDraft = "本地尚未派发稿"
        val localPending = holder.captureMessageSubmissionForTest()
        holder.capturePendingDispatchForTest(localPending)
        holder.dispatchPending("steer")
        assertFalse(holder.beginQueueRecall("s-recall", "before-dispatch"))
        holder.settlePendingDispatchForTest(localPending, sent = false)
        holder.setSendInFlightForTest(true)
        assertFalse(holder.beginQueueRecall("s-recall", "before-send"))
        holder.setSendInFlightForTest(false)
        holder.messageDraft = "新稿"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()), uris = listOf("content://new"))
        assertTrue(holder.beginQueueRecall("s-recall", "q-1"))
        assertFalse(holder.beginStoppingSession("s-recall"))
        assertTrue(holder.beginStoppingSession("other"))
        holder.finishStoppingSession("other")
        val ack = """{"kind":"dequeued","sessionId":"s-recall","queueRequestId":"q-1","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["s","f","f"]}"""
        holder.wirePayload = ack.replace("q-1", "wrong")
        holder.submitWirePayload()
        assertEquals("新稿", holder.messageDraft)
        assertFalse(holder.beginQueueRecall("s-recall", "q-2"))
        holder.wirePayload = ack
        holder.submitWirePayload()
        assertEquals("s\n\nf\n\nf\n\n新稿", holder.messageDraft)
        assertEquals(listOf("content://new"), holder.preparedUris)
        assertEquals(1, holder.preparedImages.size)
        assertEquals(localPending, holder.pendingDispatch)
        assertTrue(holder.beginQueueRecall("s-recall", "q-2"))
        holder.submitWirePayload() // 同会话旧回执不消费下一次撤回。
        assertFalse(holder.beginQueueRecall("s-recall", "q-3"))
        holder.wirePayload = ack.replace("q-1", "q-2").replace("[\"s\",\"f\",\"f\"]", "[]")
        holder.submitWirePayload()
        assertEquals("s\n\nf\n\nf\n\n新稿", holder.messageDraft)
        assertEquals(0L, holder.successfulMessageSendCount)
        assertTrue(holder.beginStoppingSession("s-recall"))
    }

    @Test
    fun recalledQueueStaysWithItsSession_andFailureReleasesWaitWithoutInventingDraft() {
        val holder = newPreviewHolder()
        holder.selectSession("s-recall")
        assertTrue(holder.beginQueueRecall("s-recall", "q-1"))
        holder.selectSession("other")
        holder.messageDraft = "新稿"
        holder.wirePayload = """{"kind":"dequeued","sessionId":"s-recall","queueRequestId":"q-1","queued":["取回稿"]}"""
        holder.submitWirePayload()
        assertEquals("新稿", holder.messageDraft)
        holder.selectSession("s-recall")
        assertEquals("取回稿", holder.messageDraft)
        assertTrue(holder.beginQueueRecall("s-recall", "q-2"))
        holder.finishQueueRecall("s-recall", "old")
        assertFalse(holder.beginQueueRecall("s-recall", "q-3"))
        holder.finishQueueRecall("s-recall", "q-2")
        assertTrue(holder.beginQueueRecall("s-recall", "q-3"))
        assertEquals("取回稿", holder.messageDraft)
    }

    @Test
    fun stoppedRemoteQueueRestoresOnceAheadOfNewDraftAndKeepsNewAttachments() {
        val holder = newPreviewHolder()
        holder.selectSession("s-stop")
        holder.messageDraft = "新草稿"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()), uris = listOf("content://new"))
        assertTrue(holder.beginStoppingSession("s-stop"))
        assertFalse(holder.beginStoppingSession("s-stop"))
        holder.wirePayload = """{"kind":"aborted","sessionId":"s-stop","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["steer","follow-up","follow-up"]}"""
        holder.submitWirePayload()
        assertEquals("steer\n\nfollow-up\n\nfollow-up\n\n新草稿", holder.messageDraft)
        assertEquals(listOf("content://new"), holder.preparedUris)
        assertEquals(1, holder.preparedImages.size)
        holder.submitWirePayload()
        assertEquals("steer\n\nfollow-up\n\nfollow-up\n\n新草稿", holder.messageDraft)
        assertEquals(0L, holder.successfulMessageSendCount)
        assertTrue(holder.beginStoppingSession("s-stop"))
        holder.wirePayload = """{"kind":"aborted","sessionId":"s-stop","queued":[],"isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]}}"""
        holder.submitWirePayload()
        assertEquals("steer\n\nfollow-up\n\nfollow-up\n\n新草稿", holder.messageDraft)
    }

    @Test
    fun stoppedQueueRemainsWithOriginalSessionAfterSwitch_andLegacyAckDoesNotGuessQueueText() {
        val holder = newPreviewHolder()
        holder.selectSession("s-stop")
        assertTrue(holder.beginStoppingSession("s-stop"))
        holder.selectSession("other")
        holder.messageDraft = "另一页的新稿"
        holder.wirePayload = """{"kind":"aborted","sessionId":"s-stop","queued":["取回稿"],"isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]}}"""
        holder.submitWirePayload()
        assertEquals("另一页的新稿", holder.messageDraft)
        holder.selectSession("s-stop")
        assertEquals("取回稿", holder.messageDraft)
        assertTrue(holder.beginStoppingSession("s-stop"))
        holder.wirePayload = """{"kind":"aborted","sessionId":"s-stop"}"""
        holder.submitWirePayload()
        assertEquals("取回稿", holder.messageDraft)
        assertTrue(holder.beginStoppingSession("s-stop"))
        holder.finishStoppingSession("wrong")
        assertFalse(holder.beginStoppingSession("s-stop"))
        holder.finishStoppingSession("s-stop")
        assertTrue(holder.beginStoppingSession("s-stop"))
    }

    @Test
    fun existingSessionRejectedTextCanBeManuallyRecoveredWithoutOverwritingNewDraft() {
        val holder = newPreviewHolder()
        holder.selectSession("s-text")
        holder.messageDraft = "需要找回的追加稿"
        driveSend(holder)
        assertEquals("需要找回的追加稿", holder.messageDraft)
        holder.wirePayload = """{"kind":"error","requestType":"message","code":"future-message-rejection"}"""
        holder.submitWirePayload()
        val failed = holder.outgoingPreviews.single()
        assertEquals(OutgoingImagePreviewState.FAILED, failed.state)
        assertTrue(failed.images.isEmpty())
        assertEquals("需要找回的追加稿", holder.messageDraft)
        holder.messageDraft = "新稿"
        holder.restoreOutgoingPreviewDraft(failed.sendToken)
        assertEquals("新稿", holder.messageDraft)
        assertEquals(1, holder.outgoingPreviews.size)
        holder.messageDraft = ""
        holder.selectSession("other")
        holder.restoreOutgoingPreviewDraft(failed.sendToken)
        assertEquals("", holder.messageDraft)
        holder.selectSession("s-text")
        holder.restoreOutgoingPreviewDraft(failed.sendToken)
        assertEquals("需要找回的追加稿", holder.messageDraft)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    @Test
    fun existingTextUnconfirmedRetainsManualRecoveryWithoutResendingOrOverwritingNewDraft() {
        val holder = newPreviewHolder()
        holder.selectSession("s-text")
        holder.messageDraft = "网络中断前的原稿"
        driveSend(holder)
        holder.messageDraft = "新的草稿"
        holder.handleOutgoingSendUnconfirmed()
        val unconfirmed = holder.outgoingPreviews.single()
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, unconfirmed.state)
        assertEquals("网络中断前的原稿", unconfirmed.draftText)
        holder.restoreOutgoingPreviewDraft(unconfirmed.sendToken)
        assertEquals("新的草稿", holder.messageDraft)
        holder.messageDraft = ""
        holder.selectSession("other")
        holder.restoreOutgoingPreviewDraft(unconfirmed.sendToken)
        assertEquals("", holder.messageDraft)
        holder.selectSession("s-text")
        holder.restoreOutgoingPreviewDraft(unconfirmed.sendToken)
        assertEquals("网络中断前的原稿", holder.messageDraft)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    @Test
    fun textUnconfirmedBeforeLocalSettleRemainsRecoverableAfterEditorIsCleared() {
        val holder = newPreviewHolder()
        holder.selectSession("s-text")
        holder.messageDraft = "原稿"
        holder.setSendInFlightForTest(true)
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        holder.handleOutgoingSendUnconfirmed()
        assertEquals("原稿", holder.messageDraft)
        assertFalse(holder.canRestoreOutgoingDraft)
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("原稿", holder.messageDraft)
        holder.messageDraft = ""
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)
        holder.restoreOutgoingPreviewDraft(outgoing.sendToken)
        assertEquals("原稿", holder.messageDraft)
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    @Test
    fun localFailedImageDraftDoesNotOverwriteNewPhotoPreparationOrUri() {
        for (preparing in listOf(true, false)) {
            val holder = newPreviewHolder()
            holder.selectSession("s-1")
            holder.messageDraft = "发送原稿"
            holder.setPreparedImagesForTest(listOf(preparedTestImage()), uris = listOf("content://old"))
            val submission = holder.captureMessageSubmissionForTest()
            val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
            holder.messageDraft = ""
            holder.setPreparedImagesForTest(emptyList(), uris = if (preparing) emptyList() else listOf("content://new"))
            val preparation = if (preparing) holder.beginImagePreparation("content://new") else null
            holder.settleOutgoingSendLocally(submission, outgoing, sent = false, clearInputOnSuccess = true)
            assertEquals("", holder.messageDraft)
            assertTrue(holder.preparedImages.isEmpty())
            assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
            if (preparing) {
                assertEquals(setOf("content://new"), holder.preparingPhotoUris)
                assertTrue(holder.completeImagePreparation("content://new", preparation!!, preparedTestImage()))
            }
            assertEquals(listOf("content://new"), holder.preparedUris)
            assertFalse(holder.canRestoreOutgoingDraft)
        }
    }

    @Test
    fun malformedSentDoesNotConsumeTextRejectionRecovery_andAcceptedTextHasNoRecovery() {
        for (invalid in listOf<String?>(null, "", " ")) {
            val holder = newPreviewHolder()
            holder.selectSession("s-text")
            holder.messageDraft = "原稿"
            driveSend(holder)
            holder.recordOutgoingSent(invalid)
            rejectMessage(holder)
            assertEquals("原稿", holder.outgoingPreviews.single().draftText)
        }
        val holder = newPreviewHolder()
        holder.selectSession("s-text")
        holder.messageDraft = "已受理"
        driveSend(holder)
        holder.recordOutgoingSent("s-text")
        rejectMessage(holder)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertEquals("", holder.messageDraft)
    }

    @Test
    fun existingTextRejectedBeforeLocalSettleKeepsOriginalOnceWithoutDuplicateRecovery() {
        val holder = newPreviewHolder()
        holder.selectSession("s-text")
        holder.messageDraft = "原稿"
        holder.setSendInFlightForTest(true)
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        rejectMessage(holder)
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("原稿", holder.messageDraft)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertEquals(0L, holder.successfulMessageSendCount)
        holder.messageDraft = "后续消息"
        driveSend(holder)
        holder.recordOutgoingSent("s-text")
        assertEquals("", holder.messageDraft)
    }

    @Test
    fun dispatchedFollowUpTextRejectedAfterLocalAcceptanceRemainsRecoverable() {
        val holder = newPreviewHolder()
        holder.selectSession("s-follow")
        holder.messageDraft = "下一轮追加稿"
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = false)
        holder.messageDraft = ""
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = false)
        rejectMessage(holder)
        assertEquals("下一轮追加稿", holder.outgoingPreviews.single().draftText)
        holder.restoreOutgoingPreviewDraft(outgoing.sendToken)
        assertEquals("下一轮追加稿", holder.messageDraft)
        assertTrue(holder.outgoingPreviews.isEmpty())
    }

    @Test
    fun newSessionFirstTextRejectedAfterLocalSettleRestoresDraft() {
        // 本地写出和网关拒绝都保留原稿。
        val holder = newPreviewHolder()
        holder.messageDraft = "在新目录开工"
        driveNewSessionTextSend(holder)
        assertEquals("等待网关受理时保留原稿", "在新目录开工", holder.messageDraft)
        rejectMessage(holder)
        assertEquals("拒绝后原文回填", "在新目录开工", holder.messageDraft)
        assertEquals(null, holder.snapshot.selectedSessionId)
        // 记录已被消费：再来一次拒绝（无在途请求）不会二次写入
        holder.messageDraft = ""
        rejectMessage(holder)
        assertEquals("", holder.messageDraft)
    }

    @Test
    fun rejectBeforeLocalSettleKeepsInputAndConsumesGuardOnce() {
        // 竞态：网关拒绝先于本地回写回到 Main（sendInFlight 仍为真）→ 回写不得再清空输入区。
        val holder = newPreviewHolder()
        holder.messageDraft = "在新目录开工"
        val (submission, outgoing) = driveNewSessionTextSend(holder, settle = false)
        rejectMessage(holder)
        assertEquals("在新目录开工", holder.messageDraft)
        holder.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("迟到的本地回写不清空已被拒绝的草稿", "在新目录开工", holder.messageDraft)
        assertEquals(0L, holder.successfulMessageSendCount)
        // 守卫只吞这一次：下一条正常发送照常清空
        driveNewSessionTextSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"new","mode":"followUp"}"""
        holder.submitWirePayload()
        assertEquals("", holder.messageDraft)
        assertEquals(1L, holder.successfulMessageSendCount)

        // 带图同理：图片与草稿都保留
        val imageHolder = newPreviewHolder()
        imageHolder.selectSession("s-1")
        imageHolder.messageDraft = "看图"
        imageHolder.setPreparedImagesForTest(listOf(preparedTestImage()))
        imageHolder.setSendInFlightForTest(true)
        val imageSubmission = imageHolder.captureMessageSubmissionForTest()
        val imageOutgoing = imageHolder.beginOutgoingSend(imageSubmission, createPreview = true)
        rejectMessage(imageHolder)
        imageHolder.settleOutgoingSendLocally(imageSubmission, imageOutgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("看图", imageHolder.messageDraft)
        assertEquals(1, imageHolder.preparedImages.size)
        assertEquals(OutgoingImagePreviewState.FAILED, imageHolder.outgoingPreviews.single().state)
    }

    /**
     * 结局层面：sent 出队 / 未确认 / 本地失败之后的迟到拒绝都不回填，拒绝前用户另起的新输入不被覆盖。
     * 注意前三种只验“最终不回填”这一结果：token 已离开等待队列，`handleOutgoingServerReject`
     * 在取队头处即返回，区分不出 `pendingNewSessionText` 是否已被清除——那三处清除是纵深
     * 防御（token 单调分配，离队后不可能再成为队头），本用例不对它们做门控。只有最后一种
     * （新输入）真正走进 `restorePendingNewSessionText` 的守卫。
     */
    @Test
    fun lateRejectAfterSentUnconfirmedOrLocalFailureDoesNotRestore_andNewInputIsKept() {
        // sent 受理出队：之后的拒绝（没有在途请求）不回填
        val sentHolder = newPreviewHolder()
        sentHolder.messageDraft = "已受理"
        driveNewSessionTextSend(sentHolder)
        sentHolder.wirePayload = """{"kind":"sent","sessionId":"s-new","mode":"followUp"}"""
        sentHolder.submitWirePayload()
        rejectMessage(sentHolder)
        assertEquals("", sentHolder.messageDraft)

        // 超时/断连未确认：可能已送达，不自动回填（防重复发送）
        val unconfirmedHolder = newPreviewHolder()
        unconfirmedHolder.messageDraft = "未确认"
        driveNewSessionTextSend(unconfirmedHolder)
        unconfirmedHolder.handleOutgoingSendUnconfirmed()
        rejectMessage(unconfirmedHolder)
        assertEquals("未确认", unconfirmedHolder.messageDraft)

        // 本地失败：草稿本就没清，记录丢弃；用户清空后迟到拒绝不再写回
        val localFailHolder = newPreviewHolder()
        localFailHolder.messageDraft = "本地失败"
        val (failSubmission, failOutgoing) = driveNewSessionTextSend(localFailHolder, settle = false)
        localFailHolder.settleOutgoingSendLocally(failSubmission, failOutgoing, sent = false, clearInputOnSuccess = true)
        assertEquals("本地失败", localFailHolder.messageDraft)
        localFailHolder.messageDraft = ""
        rejectMessage(localFailHolder)
        assertEquals("", localFailHolder.messageDraft)

        // 拒绝到达前用户已另起新输入：不覆盖
        val editedHolder = newPreviewHolder()
        editedHolder.messageDraft = "原文"
        driveNewSessionTextSend(editedHolder)
        editedHolder.messageDraft = "用户新写的"
        rejectMessage(editedHolder)
        assertEquals("用户新写的", editedHolder.messageDraft)
    }

    @Test
    fun localRejectionConvergesAndRestoresDraftWithoutAutoResend() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        // 本地拒绝（runtime 返回 false）：FAILED + 自动回填；不产生任何重发动作。
        holder.settleOutgoingSendLocally(submission, outgoing, sent = false, clearInputOnSuccess = true)
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
        assertEquals("看图", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
        assertEquals(0L, holder.successfulMessageSendCount)
        assertEquals(null, holder.pendingDispatch)
    }

    @Test
    fun lateEchoClearsAutoRestoredDraftOnlyForSameGeneration() {
        // UNCONFIRMED（超时/断连）无自动回填，仅手动恢复入口。
        val holder0 = newPreviewHolder()
        holder0.selectSession("s-1")
        holder0.messageDraft = "看图"
        holder0.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder0)
        holder0.handleOutgoingSendUnconfirmed()
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder0.outgoingPreviews.single().state)
        assertEquals("看图", holder0.messageDraft)

        // 同代（未编辑）：本地失败自动回填 → 迟到 echo 清回填稿，防误重发。
        //（服务端拒绝已按 F10 收窄为不自动回填；代次守卫经仍回填的本地失败路径验证。）
        val holder2 = newPreviewHolder()
        holder2.selectSession("s-2")
        holder2.messageDraft = "看图"
        holder2.setPreparedImagesForTest(listOf(preparedTestImage()))
        val sub2 = holder2.captureMessageSubmissionForTest()
        val out2 = holder2.beginOutgoingSend(sub2, createPreview = true)
        holder2.setPreparedImagesForTest(emptyList())
        holder2.messageDraft = ""
        holder2.settleOutgoingSendLocally(sub2, out2, sent = false, clearInputOnSuccess = true)
        assertEquals("看图", holder2.messageDraft)
        holder2.handleOutgoingEcho("s-2", listOf(userEcho("s-2", out2.images.single().outgoing.attachmentId)))
        assertEquals("", holder2.messageDraft)
        assertEquals(0, holder2.preparedImages.size)
        assertEquals(0, holder2.outgoingPreviews.size)

        // 用户重新编辑（代次变）→ 迟到 echo 不清新草稿。
        val holder3 = newPreviewHolder()
        holder3.selectSession("s-3")
        holder3.messageDraft = "看图"
        holder3.setPreparedImagesForTest(listOf(preparedTestImage()))
        val sub3 = holder3.captureMessageSubmissionForTest()
        val out3 = holder3.beginOutgoingSend(sub3, createPreview = true)
        holder3.setPreparedImagesForTest(emptyList())
        holder3.messageDraft = ""
        holder3.settleOutgoingSendLocally(sub3, out3, sent = false, clearInputOnSuccess = true)
        assertEquals("看图", holder3.messageDraft)
        holder3.messageDraft = "用户改过的稿"
        holder3.handleOutgoingEcho("s-3", listOf(userEcho("s-3", out3.images.single().outgoing.attachmentId)))
        assertEquals("用户改过的稿", holder3.messageDraft)
        assertEquals(0, holder3.outgoingPreviews.size)
    }

    @Test
    fun autoRestoreDoesNotFillForeignSessionAfterUserSwitches() {
        // 失败收敛晚于用户切会话：不得把旧会话失败稿塞进新会话输入区。
        val holder = newPreviewHolder()
        holder.selectSession("s-a")
        holder.messageDraft = "A 会话稿"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        holder.selectSession("s-b")
        holder.messageDraft = "B 的新草稿"
        holder.settleOutgoingSendLocally(submission, outgoing, sent = false, clearInputOnSuccess = true)
        // 输入区非空且预览归属旧会话：不覆盖、不回填，预览保留可切回手动恢复。
        assertEquals("B 的新草稿", holder.messageDraft)
        assertEquals(1, holder.outgoingPreviews.size)
    }

    @Test
    fun manualRestoreRequiresVisiblePreviewAndEmptyInputWithoutLosingPayload() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        val token = outgoing.sendToken!!
        holder.wirePayload = """{"kind":"error","requestType":"message","code":"prompt-rejected"}"""
        holder.submitWirePayload()
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
        // 输入区非空：不消费预览（载荷不丢，输入区保持不变）。
        holder.messageDraft = ""
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        holder.restoreOutgoingPreviewDraft(token)
        assertEquals(1, holder.outgoingPreviews.size)
        assertEquals("", holder.messageDraft)
        // 清空输入区后再恢复：回填 + 移除；不触发重发。
        holder.messageDraft = ""
        holder.setPreparedImagesForTest(emptyList())
        holder.restoreOutgoingPreviewDraft(token)
        assertEquals(0, holder.outgoingPreviews.size)
        assertEquals("看图", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
        // 归属不符：切到别会话后恢复被拒（不把旧稿塞进别的会话页）。
        val holder2 = newPreviewHolder()
        holder2.selectSession("s-x")
        holder2.messageDraft = "X 稿"
        holder2.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing2) = driveSend(holder2)
        holder2.wirePayload = """{"kind":"error","requestType":"message","code":"prompt-rejected"}"""
        holder2.submitWirePayload()
        holder2.messageDraft = ""
        holder2.setPreparedImagesForTest(emptyList())
        holder2.selectSession("s-y")
        holder2.restoreOutgoingPreviewDraft(outgoing2.sendToken!!)
        assertEquals("", holder2.messageDraft)
        assertEquals(1, holder2.outgoingPreviews.size)
    }

    @Test
    fun sdkStartedImageWithoutAttachmentIdDoesNotReportUnconfirmed() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp","disposition":"started","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        // SDK1.0.4会重新创建image块，不保留客户端attachmentId。
        holder.wirePayload = """{"kind":"event","sessionId":"s-1","entryId":"sdk-user","seq":1,"time":1,
            "event":{"type":"message_end","message":{"role":"user","content":[
            {"type":"text","text":"看图"},{"type":"image","mimeType":"image/png","data":"AQID"}]}}}""".replace("\n", "")
        holder.submitWirePayload()
        holder.wirePayload = """{"kind":"event","sessionId":"s-1","entryId":"sdk-answer","seq":2,"time":2,
            "event":{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"合成图片描述"}]}}}""".replace("\n", "")
        holder.submitWirePayload()
        val user = holder.snapshot.conversation.single { it.kind == ConversationItemKind.USER }
        assertEquals("hist-sdk-user-0", user.images.single().attachmentId)
        assertTrue(holder.snapshot.conversation.any { it.text == "合成图片描述" })
        holder.messageDraft = "后写新稿"
        holder.markStaleOutgoingPreviews(testNowMs + 45_001)
        assertTrue("Pi明确受理后，不因回显ID缺失生成未确认恢复条", holder.outgoingPreviews.isEmpty())
        holder.restoreOutgoingPreviewDraft(outgoing.sendToken)
        assertEquals("后写新稿", holder.messageDraft)
        assertTrue(holder.preparedImages.isEmpty())
    }

    @Test
    fun bridgeSubmittedAcknowledgementCompletesItsOwnDraftAndImageWait() {
        val holder = newPreviewHolder()
        holder.selectSession("s-bridge")
        holder.messageDraft = "跨桥原稿"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge","disposition":"submitted","operationId":"${outgoing.sendToken}","instanceId":"i1","epoch":"e1","status":"submitted","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals("已提交回执结束发送等待，不等不存在的 send settled", "", holder.messageDraft)
        assertTrue(holder.preparedImages.isEmpty())
        holder.messageDraft = "后写新稿"
        holder.markStaleOutgoingPreviews(testNowMs + 45_001)
        assertTrue("已提交图片不能因等待原生回显而假报未确认", holder.outgoingPreviews.isEmpty())
        holder.wirePayload = """{"kind":"event","sessionId":"s-bridge","entryId":"other-user","seq":1,"time":1,"event":{"type":"message_end","message":{"role":"user","content":"跨桥原稿"}}}"""
        holder.submitWirePayload()
        assertEquals("普通正文回显不能影响之后编辑的草稿", "后写新稿", holder.messageDraft)
    }

    @Test
    fun abortFailureRestoresQueuedTextOnlyForMatchingOperation() {
        val holder = newPreviewHolder()
        holder.selectSession("s-abort-fail")
        holder.messageDraft = "新草稿"
        assertTrue(holder.beginStoppingSession("s-abort-fail", "abort-op"))

        holder.finishAbortRequest("s-abort-fail", "other-op", "旧队列")
        assertEquals("新草稿", holder.messageDraft)
        assertFalse("错配失败不能结束当前 stop", holder.beginStoppingSession("s-abort-fail", "next"))

        holder.finishAbortRequest("s-abort-fail", "abort-op", "旧队列")
        assertEquals("旧队列\n\n新草稿", holder.messageDraft)
        holder.finishAbortRequest("s-abort-fail", "abort-op", "旧队列")
        assertEquals("重复失败不能重复回填", "旧队列\n\n新草稿", holder.messageDraft)
        assertTrue("匹配失败已结束 stop", holder.beginStoppingSession("s-abort-fail", "next"))
    }

    @Test
    fun nativeRootTrustRefusalKeepsDraftAndWrongTokenCannotOpenConfirmation() {
        val holder = newPreviewHolder()
        try {
            holder.selectFineCatDirectory("/synthetic/new")
            holder.messageDraft = "original draft"
            val submission = holder.captureMessageSubmissionForTest()
            val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
            holder.rememberNewSessionTextForReject(submission, outgoing, true, true)
            holder.handleOutgoingMessageRejection(GatewayRuntimeEvent.RequestRejected(
                requestType = "message", reason = "worktree-trust-required", correlationId = "wrong", serverResponse = true))
            assertFalse(holder.worktreeUi.open)
            assertEquals("original draft", holder.messageDraft)
            holder.handleOutgoingMessageRejection(GatewayRuntimeEvent.RequestRejected(
                requestType = "message", reason = "worktree-trust-required", correlationId = outgoing.sendToken, serverResponse = true))
            assertTrue(holder.worktreeUi.open)
            assertEquals(WorktreePhase.TRUST, holder.worktreeUi.phase)
            assertEquals("/synthetic/new", holder.worktreeUi.targetPath)
            assertEquals("original draft", holder.messageDraft)
            assertNull(holder.snapshot.selectedSessionId)
            holder.closeWorktree()
            assertEquals("original draft", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test
    fun nativeRootRefusalAfterNavigationCannotMoveTrustOrDraftToAnotherSession() {
        val holder = newPreviewHolder()
        try {
            holder.selectFineCatDirectory("/synthetic/new")
            holder.messageDraft = "original draft"
            val submission = holder.captureMessageSubmissionForTest()
            val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
            holder.rememberNewSessionTextForReject(submission, outgoing, true, true)
            holder.selectSession("other")
            holder.messageDraft = "other draft"
            holder.handleOutgoingMessageRejection(GatewayRuntimeEvent.RequestRejected(
                requestType = "message", reason = "worktree-trust-required", correlationId = outgoing.sendToken, serverResponse = true))
            assertFalse(holder.worktreeUi.open)
            assertEquals("other", holder.snapshot.selectedSessionId)
            assertEquals("other draft", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test
    fun bridgeSubmittedAckMustMatchThePendingSendOperation() {
        val holder = newPreviewHolder()
        holder.selectSession("s-bridge-order")
        holder.messageDraft = "B 的原稿"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)

        // A 的迟到 ACK 不能把 B 的等待队头认成自己的收据。
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-order","disposition":"submitted","operationId":"op-a","instanceId":"i1","epoch":"e1","status":"submitted","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals(listOf(outgoing.sendToken), holder.outgoingPreviews.map { it.sendToken })
        assertEquals(OutgoingImagePreviewState.SENDING, holder.outgoingPreviews.single().state)
        assertEquals("B 的原稿", holder.messageDraft)

        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-order","disposition":"submitted","operationId":"${outgoing.sendToken}","instanceId":"i2","epoch":"e2","status":"submitted","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals("匹配提交才结束这条发送", "", holder.messageDraft)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertTrue(holder.preparedImages.isEmpty())
        holder.messageDraft = "C 的原稿"
        driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-order","disposition":"submitted","operationId":"${outgoing.sendToken}","instanceId":"i2","epoch":"e2","status":"submitted","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals("重复旧回执不能清下一条草稿", "C 的原稿", holder.messageDraft)
    }

    @Test
    fun bridgeSubmittedDoesNotLeaveAnUnconfirmedWarningAfterNativeReply() {
        val holder = newPreviewHolder()
        holder.selectSession("s-bridge-text")
        holder.messageDraft = "phone-check"
        val (_, outgoing) = driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-text","disposition":"submitted","operationId":"${outgoing.sendToken}","instanceId":"i1","epoch":"e1","status":"submitted","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals("", holder.messageDraft)
        holder.wirePayload = """{"kind":"event","sessionId":"s-bridge-text","entryId":"answer","seq":1,"time":1,"event":{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"MIDDLE:phone-check"}]}}}"""
        holder.submitWirePayload()
        holder.markStaleOutgoingPreviews(testNowMs + 45_001)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertTrue(holder.snapshot.conversation.any { it.text == "MIDDLE:phone-check" })
    }

    @Test
    fun bridgeSubmittedDoesNotClearTextWrittenAfterTheSend() {
        val holder = newPreviewHolder()
        holder.selectSession("s-bridge-new-draft")
        holder.messageDraft = "已发原稿"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        holder.messageDraft = "后写新稿"
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-new-draft","disposition":"submitted","operationId":"${outgoing.sendToken}","instanceId":"i1","epoch":"e1","status":"submitted","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        assertEquals("后写新稿", holder.messageDraft)
        holder.markStaleOutgoingPreviews(testNowMs + 45_001)
        assertTrue(holder.outgoingPreviews.isEmpty())
    }

    @Test
    fun bridgeSubmittedWithoutImagePreviewDoesNotBlockTheNextSend() {
        val holder = newPreviewHolder()
        holder.selectSession("s-bridge-dispatch")
        holder.messageDraft = "带图追加"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val submission = holder.captureMessageSubmissionForTest()
        // 忙时 steer/followUp 的真实实发边界：不建本地占位气泡，也没有纯文本快照。
        val dispatch = holder.beginOutgoingSend(submission, createPreview = false)
        holder.settleOutgoingSendLocally(submission, dispatch, sent = true, clearInputOnSuccess = false)
        holder.messageDraft = "后写新稿"
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-dispatch","disposition":"submitted","operationId":"${dispatch.sendToken}","instanceId":"i1","epoch":"e1"}"""
        holder.submitWirePayload()
        assertEquals("无本地预览的提交也不能动输入区", "后写新稿", holder.messageDraft)
        holder.setPreparedImagesForTest(emptyList())
        holder.messageDraft = "下一条普通发送"
        val (_, next) = driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-bridge-dispatch","disposition":"submitted","operationId":"${next.sendToken}","instanceId":"i1","epoch":"e1"}"""
        holder.submitWirePayload()
        assertEquals("上一条必须消费等待队头，不能把后续提交全部挡住", "", holder.messageDraft)
    }

    @Test
    fun bridgeSendWithMissingAcknowledgementKeepsExplicitRecovery() {
        val holder = newPreviewHolder()
        holder.selectSession("s-bridge-unknown")
        holder.messageDraft = "没有提交回执的原稿"
        val (_, outgoing) = driveSend(holder)
        holder.handleOutgoingSendUnconfirmed()
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)
        holder.messageDraft = ""
        holder.restoreOutgoingPreviewDraft(outgoing.sendToken)
        assertEquals("没有提交回执的原稿", holder.messageDraft)
    }

    @Test
    fun sdkAcceptedDispositionsCompleteOnlyTheirOwnImageSend() {
        for (disposition in listOf("started", "queued", "handled")) {
            val holder = newPreviewHolder()
            holder.selectSession("s-1")
            holder.messageDraft = "旧失败稿"
            holder.setPreparedImagesForTest(listOf(preparedTestImage()))
            val (_, old) = driveSend(holder)
            holder.handleOutgoingServerReject()
            holder.messageDraft = "本次图片"
            val (_, current) = driveSend(holder)
            holder.messageDraft = "后写新稿"
            holder.wirePayload = """{"kind":"sent","sessionId":"s-1","disposition":"$disposition","isStreaming":true,"isCompacting":false}"""
            holder.submitWirePayload()
            holder.markStaleOutgoingPreviews(testNowMs + 45_001)
            assertEquals(listOf(old.sendToken), holder.outgoingPreviews.map { it.sendToken })
            assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
            holder.restoreOutgoingPreviewDraft(current.sendToken)
            assertEquals("后写新稿", holder.messageDraft)
            assertEquals(1, holder.preparedImages.size)
        }
    }

    @Test
    fun sdkAcceptedHistoryWithoutAttachmentIdKeepsOneFormalImage() {
        val holder = newPreviewHolder()
        holder.messageDraft = "新会话图片"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"created-session","disposition":"started","isStreaming":true,"isCompacting":false}"""
        holder.submitWirePayload()
        holder.wirePayload = """{"kind":"history","sessionId":"created-session","entryIds":["sdk-history-user"],"messages":[
            {"role":"user","content":[{"type":"text","text":"新会话图片"},{"type":"image","mimeType":"image/png","data":"AQID"}]}]}""".replace("\n", "")
        holder.submitWirePayload()
        holder.markStaleOutgoingPreviews(testNowMs + 45_001)
        val user = holder.snapshot.conversation.single { it.kind == ConversationItemKind.USER }
        assertEquals("hist-sdk-history-user-0", user.images.single().attachmentId)
        assertTrue(holder.outgoingPreviews.isEmpty())
        assertEquals("", holder.messageDraft)
    }

    @Test
    fun staleConfirmedPreviewTurnsUnconfirmedAndManualRestoreWorks() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (_, outgoing) = driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        // sent 后超过窗口仍无回显（如扩展 input handled）：未确认（有界收敛，不判失败）。
        holder.markStaleOutgoingPreviews(nowMs = testNowMs + 45_001)
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)
        // 手动恢复：回填草稿，不自动重发。
        holder.restoreOutgoingPreviewDraft(outgoing.sendToken!!)
        assertEquals(0, holder.outgoingPreviews.size)
        assertEquals("看图", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
    }

    @Test
    fun restoredPreviewDraftKeepsUriImageMapping() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()), uris = listOf("content://origin"))
        val (_, outgoing) = driveSend(holder)
        holder.wirePayload = """{"kind":"sent","sessionId":"s-1","mode":"followUp"}"""
        holder.submitWirePayload()
        holder.markStaleOutgoingPreviews(nowMs = testNowMs + 45_001)
        assertEquals(OutgoingImagePreviewState.UNCONFIRMED, holder.outgoingPreviews.single().state)
        // 未确认手动恢复：图片与来源 uri 成对回填，托盘不出"有图无 uri"的错位勾选。
        holder.restoreOutgoingPreviewDraft(outgoing.sendToken)
        assertEquals("看图", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
        assertEquals(listOf("content://origin"), holder.preparedUris)
    }

    @Test
    fun autoRestoredFailedDraftKeepsUriImageMapping() {
        val holder = newPreviewHolder()
        holder.selectSession("s-1")
        holder.messageDraft = "看图"
        holder.setPreparedImagesForTest(listOf(preparedTestImage()), uris = listOf("content://origin"))
        val submission = holder.captureMessageSubmissionForTest()
        val outgoing = holder.beginOutgoingSend(submission, createPreview = true)
        // 发送途中用户清空输入区 → 本地失败自动回填：同样要带上来源 uri。
        //（服务端拒绝已按 F10 收窄为不自动回填。）
        holder.setPreparedImagesForTest(emptyList())
        holder.messageDraft = ""
        holder.settleOutgoingSendLocally(submission, outgoing, sent = false, clearInputOnSuccess = true)
        assertEquals(OutgoingImagePreviewState.FAILED, holder.outgoingPreviews.single().state)
        assertEquals("看图", holder.messageDraft)
        assertEquals(1, holder.preparedImages.size)
        assertEquals(listOf("content://origin"), holder.preparedUris)
    }

    @Test
    fun previewsStayBoundToTheirSessionAndDoNotCrossMatch() {
        val holder = newPreviewHolder()
        holder.selectSession("s-a")
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        val (submissionA, _) = driveSend(holder)
        assertEquals("s-a", submissionA.sessionId)
        // 换会话：预览保留在列表（不串显、不清除）。
        holder.selectSession("s-b")
        assertEquals(1, holder.outgoingPreviews.size)
        assertTrue(!holder.outgoingPreviews.single().isVisibleFor("s-b", holder.newSessionEpoch))
        // b 会话的同文不同 id echo 不移除 A 的预览（禁止内容猜身份）。
        holder.handleOutgoingEcho("s-b", listOf(userEcho("s-b", "b-own-image")))
        assertEquals(1, holder.outgoingPreviews.size)
        // 错误 id 的 a 会话 echo 也不移除（整组精确匹配）。
        holder.handleOutgoingEcho("s-a", listOf(userEcho("s-a", "wrong-id")))
        assertEquals(1, holder.outgoingPreviews.size)
        // 正确归属 + 整组 id：移除。
        holder.handleOutgoingEcho("s-a", listOf(userEcho("s-a", "sent-1")))
        assertEquals(0, holder.outgoingPreviews.size)
    }

    @Test
    fun unboundPreviewOnlyShowsOnSameNewSessionEpoch() {
        val holder = newPreviewHolder()
        holder.prepareNewSession()
        holder.setPreparedImagesForTest(listOf(preparedTestImage()))
        driveSend(holder)
        val preview = holder.outgoingPreviews.single()
        assertEquals(null, preview.sessionId)
        assertTrue(preview.isVisibleFor(null, holder.newSessionEpoch))
        // 重开新建页（换代）：旧未绑定预览不再显示，但列表保留待回显收敛。
        holder.prepareNewSession()
        assertEquals(1, holder.outgoingPreviews.size)
        assertTrue(!holder.outgoingPreviews.single().isVisibleFor(null, holder.newSessionEpoch))
    }

    @Test
    fun newSessionDirectoryFollowsHomePickPriority() {
        val holder = AndroidSharedStateHolder()
        holder.wirePayload =
            """{"kind":"sessions","items":[""" +
            """{"id":"s-1","cwd":"/repo/one","modified":"2026-09-02T07:36:16.694Z","messageCount":2,"firstMessage":"一","running":false},""" +
            """{"id":"s-2","cwd":"/repo/two","modified":"2026-09-01T07:36:16.694Z","messageCount":2,"firstMessage":"二","running":false}]}"""
        holder.submitWirePayload()
        // 旧首页曾显式选 A，随后进入目录 B 的会话；新建必须采用 B。
        holder.selectDirectory("/repo/old-pick")
        holder.selectSession("s-2")

        // 从 s-2 点「+」回空白首页：沿用当前会话目录，不新猜一个。
        holder.prepareNewSession(cwd = "/repo/two")
        assertEquals("/repo/two", holder.newSessionDirectoryPath)
        assertEquals("/repo/two", holder.captureMessageSubmissionForTest().cwd)

        // 用户显式选目录优先于沿用值；清除后回到沿用值。
        holder.selectDirectory("/repo/three")
        holder.prepareNewSession()
        assertEquals("/repo/three", holder.newSessionDirectoryPath)
        assertEquals("/repo/three", holder.captureMessageSubmissionForTest().cwd)
        holder.clearSelectedDirectory()
        assertEquals("/repo/two", holder.newSessionDirectoryPath)

        // 没有沿用值可带（已在空白页再点「+」）时保留既有沿用值，不退回去“猜”最近会话。
        holder.prepareNewSession()
        assertEquals("/repo/two", holder.newSessionDirectoryPath)
        // 选中已有会话后沿用值作废：回退链变成显式选择（已清）> 最近会话目录。
        holder.selectSession("s-1")
        assertEquals("/repo/one", holder.newSessionDirectoryPath)

        // 绑定已有会话后不再带 cwd：会话目录由 pi 侧决定，客户端不覆盖。
        holder.selectSession("s-1")
        assertEquals(null, holder.captureMessageSubmissionForTest().cwd)
    }

    @Test
    fun newSessionFromCurrentStartsEmptyAndPreservesTheOriginalSessionsDraft() {
        val holder = AndroidSharedStateHolder()
        holder.wirePayload =
            """{"kind":"sessions","items":[{"id":"s-1","cwd":"/repo/one",""" +
            """"modified":"2026-09-02T07:36:16.694Z","messageCount":2,"firstMessage":"一","running":false}]}"""
        holder.submitWirePayload()
        holder.selectSession("s-1")
        holder.messageDraft = "还没发出去的字"
        val epochBefore = holder.newSessionEpoch

        // 「+」只做本地切换：新页空稿，原会话手稿单独保留。
        holder.prepareNewSession(cwd = "/repo/one")
        assertEquals(null, holder.snapshot.selectedSessionId)
        assertEquals(epochBefore + 1, holder.newSessionEpoch)
        assertEquals("", holder.messageDraft)

        // 换目录同样不发消息、不动草稿（只影响下一个新会话的落点）。
        holder.selectDirectory("/repo/two")
        assertEquals("", holder.messageDraft)
        assertEquals(null, holder.snapshot.selectedSessionId)
        holder.selectSession("s-1")
        assertEquals("还没发出去的字", holder.messageDraft)
    }

    @Test
    fun fineCatDirectoryRequiresExplicitChoiceAndPreservesAbsoluteIdentity() {
        val holder = AndroidSharedStateHolder()
        holder.wirePayload = """{"kind":"sessions","items":[{"id":"recent","cwd":"/repo/recent","messageCount":2,"firstMessage":"old","running":false}]}"""
        holder.submitWirePayload()
        assertEquals("/repo/recent", holder.newSessionDirectoryPath)
        assertEquals(null, holder.fineCatNewSessionDirectoryPath)

        holder.selectFineCatDirectory("/repo/team-a/project")
        assertEquals("/repo/team-a/project", holder.captureMessageSubmissionForTest().cwd)
        holder.selectFineCatDirectory("/repo/team-b/project")
        assertEquals("/repo/team-b/project", holder.fineCatNewSessionDirectoryPath)
        assertEquals("/repo/team-b/project", holder.captureMessageSubmissionForTest().cwd)
        assertEquals("/repo/recent", holder.snapshot.sessions.single().cwd)
    }

    @Test
    fun fineCatReturningToRootClearsInheritedDirectoryAndBlocksFirstSendWithoutLosingDraft() {
        val holder = AndroidSharedStateHolder()
        holder.prepareNewSession(cwd = "/repo/inherited")
        holder.messageDraft = "保留这份草稿"
        holder.selectFineCatDirectory("/")

        assertEquals(null, holder.selectedDirectoryPath)
        assertEquals(null, holder.fineCatNewSessionDirectoryPath)
        holder.sendComposerDraft(requireDirectory = true)
        assertEquals("先在输入框选择工作目录，草稿已保留", holder.transientNotice)
        assertEquals("保留这份草稿", holder.messageDraft)
        assertEquals(null, holder.snapshot.selectedSessionId)
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    @Test
    fun fineCatDirectoryGateAllowsLocalCommandsBeforeDirectoryChoice() {
        val holder = AndroidSharedStateHolder()
        holder.messageDraft = "/compact"
        holder.sendComposerDraft(requireDirectory = true)
        // C07 接线后引导文案随状态变化；不因本地命令而要求先选目录。
        assertTrue(holder.transientNotice != null)
        assertTrue(holder.transientNotice!!.contains("会话"))
        assertEquals(null, holder.fineCatNewSessionDirectoryPath)
        assertEquals(0L, holder.successfulMessageSendCount)
    }

    /** turn/end 后重拉首页 history（强退重开恢复链路的客户端侧）：
     *  run 落定时文件已完整落盘，重拉补齐强退期间"消失"的 user 消息。 */
    @Test
    fun turnEndReloadsSelectedHistoryWhenIdle() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("s-turn")
        // 首页 startHistory 已发起：无 graph 时无应答，先注入一页 history 结束在途。
        assertEquals(true, holder.snapshot.selectedHistoryIsLoading)
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-turn\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
            "\"entryIds\":[\"e1\"],\"hasMore\":false,\"oldestEntryId\":\"e1\"}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)

        // agent_settled → turn/end：应触发当前会话的首页重拉（isLoading 重新置位）。
        holder.wirePayload = "{\"kind\":\"event\",\"sessionId\":\"s-turn\",\"seq\":2,\"time\":2.0," +
            "\"event\":{\"type\":\"agent_settled\"}}"
        holder.submitWirePayload()
        assertEquals(true, holder.snapshot.selectedHistoryIsLoading)
        // 重拉走 preserveLive：请求在途时不得把正在看的对话抹空（旧实现先清缓冲 →
        // 整屏只剩加载态、滚到一半的位置丢失）。
        assertEquals(1, holder.snapshot.conversation.size)

        // 新页补齐本轮落盘的 user 消息：实时/历史按条目身份对消，不双份。
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-turn\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"user\",\"content\":\"补回来的\"}]," +
            "\"entryIds\":[\"e1\",\"e2\"],\"hasMore\":false,\"oldestEntryId\":\"e1\"}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)
        assertEquals(listOf("hi", "补回来的"), holder.snapshot.conversation.map { it.text })
        assertEquals(null, holder.snapshot.selectedHistoryError)
    }

    @Test
    fun turnEndOfOtherSessionDoesNotReloadSelectedHistory() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("s-current")
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-current\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
            "\"entryIds\":[\"e1\"],\"hasMore\":false,\"oldestEntryId\":\"e1\"}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)

        // 其他会话的 turn/end 不触发当前选中会话的重拉。
        holder.wirePayload = "{\"kind\":\"event\",\"sessionId\":\"s-other\",\"seq\":2,\"time\":2.0," +
            "\"event\":{\"type\":\"agent_settled\"}}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)
    }

    /** turn/end 恰逢在途 history 被跳过时记脏标记；首页落地且仍选中时补拉一次，
     *  且不重复（补拉落地后不再触发第二次）。 */
    @Test
    fun turnEndDuringHistoryLoadDefersReloadUntilPageLands() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("s-defer")
        // 首页 startHistory 在途：turn/end 无法立即重拉。
        assertEquals(true, holder.snapshot.selectedHistoryIsLoading)
        holder.wirePayload = "{\"kind\":\"event\",\"sessionId\":\"s-defer\",\"seq\":2,\"time\":2.0," +
            "\"event\":{\"type\":\"agent_settled\"}}"
        holder.submitWirePayload()
        // 仍无新请求发出（isLoading 保持原在途状态），但已记待补。
        assertEquals(true, holder.snapshot.selectedHistoryIsLoading)

        // 首页落地（批次完成）：仍选中 → 补拉一次（isLoading 再次置位）。
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-defer\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
            "\"entryIds\":[\"e1\"],\"hasMore\":false,\"oldestEntryId\":\"e1\"}"
        holder.submitWirePayload()
        assertEquals(true, holder.snapshot.selectedHistoryIsLoading)

        // 补拉落地：标记已清，不再触发第二次补拉。
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-defer\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
            "\"entryIds\":[\"e1\"],\"hasMore\":false,\"oldestEntryId\":\"e1\"}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)

        // 稳态：再注入一帧 history（无在途请求、无标记）不再触发任何加载。
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-defer\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
            "\"entryIds\":[\"e1\"],\"hasMore\":false,\"oldestEntryId\":\"e1\"}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)
    }

    /** 切走的会话不再补拉：turn/end 的“待取最新页”意图只属于当时的选中会话，
     *  迟到落地的首页不得给一个已经不在看的会话发多余请求。 */
    @Test
    fun turnEndReloadIsDroppedWhenSessionIsNoLongerSelected() {
        val holder = AndroidSharedStateHolder()
        holder.selectSession("s-a")
        assertEquals(true, holder.snapshot.selectedHistoryIsLoading)
        // s-a 首页在途中收到 turn/end：意图归并到投影，等当前批次落地。
        holder.wirePayload = "{\"kind\":\"event\",\"sessionId\":\"s-a\",\"seq\":2,\"time\":2.0," +
            "\"event\":{\"type\":\"agent_settled\"}}"
        holder.submitWirePayload()

        // 切到 s-b 并先让它自己的首页落地（进稳态），再让 s-a 的迟到页落地。
        holder.selectSession("s-b")
        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-b\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"b\"}]," +
            "\"entryIds\":[\"b1\"],\"hasMore\":false,\"oldestEntryId\":\"b1\"}"
        holder.submitWirePayload()
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)

        holder.wirePayload = "{\"kind\":\"history\",\"sessionId\":\"s-a\"," +
            "\"messages\":[{\"role\":\"user\",\"content\":\"a\"}]," +
            "\"entryIds\":[\"a1\"],\"hasMore\":false,\"oldestEntryId\":\"a1\"}"
        holder.submitWirePayload()
        // s-a 已非选中 → 待取意图作废；选中的 s-b 不受影响（没被多余请求重新置位）。
        assertEquals("s-b", holder.snapshot.selectedSessionId)
        assertEquals(false, holder.snapshot.selectedHistoryIsLoading)
    }
}
