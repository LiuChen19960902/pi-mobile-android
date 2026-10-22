package com.finecat.pimobile.android

import androidx.compose.runtime.MutableState
import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import com.finecat.pimobile.shared.gateway.GatewayRuntimeState
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.protocol.GatewayWorktree
import org.junit.Assert.*
import org.junit.Test

class WorktreeStateTest {
    /** 只替换宿主连接快照，操作与草稿仍走生产门面；无网络、设备或真实配置。 */
    @Suppress("UNCHECKED_CAST")
    private fun connected(holder: AndroidSharedStateHolder, bridge: Boolean = false, worktrees: Boolean = true) {
        val field = AndroidSharedStateHolder::class.java.getDeclaredField("gatewayState\$delegate")
        field.isAccessible = true
        (field.get(holder) as MutableState<GatewayRuntimeState>).value = GatewayRuntimeState(
            connection = GatewayConnectionState.CONNECTED,
            capabilities = (if (bridge) setOf("bridge", "bridge-workspace-v1") else emptySet()) +
                (if (worktrees) setOf("worktree-v1") else emptySet()),
            hostMode = if (bridge) "bridge" else "sdk",
        )
    }

    private fun wire(holder: AndroidSharedStateHolder, payload: String) {
        holder.wirePayload = payload
        holder.submitWirePayload()
    }

    private fun query(holder: AndroidSharedStateHolder): String? {
        val field = AndroidSharedStateHolder::class.java.getDeclaredField("activeWorktreeQuery")
        field.isAccessible = true
        return field.get(holder) as String?
    }

    private fun nativeSnapshot(holder: AndroidSharedStateHolder, session: String = "source", epoch: String = "epoch") {
        wire(holder, """{"kind":"bridge-snapshot","sessionId":"$session","instanceId":"instance","epoch":"$epoch","bridgeSeq":1,"cwd":"/synthetic/source","busy":false,"online":true}""")
    }

    @Test fun nativeIdentityArrivalRetriesTheInitialDirectoryQueryAndRevealsTheGitLocation() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder, bridge = true)
            holder.selectSession("source")
            holder.messageDraft = "keep draft"
            holder.refreshWorktreeLocation("/synthetic/source")
            val earlyQuery = requireNotNull(query(holder))
            nativeSnapshot(holder)
            val readyQuery = requireNotNull(query(holder))
            assertNotEquals("identity arrival must retry the early query", earlyQuery, readyQuery)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-list", queryId = readyQuery,
                cwd = "/synthetic/source", repoRoot = "/synthetic/source", branch = "main"))
            assertEquals("/synthetic/source", holder.worktreeLocation?.repoRoot)
            assertEquals("keep draft", holder.messageDraft)
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertFalse(holder.worktreeUi.open)
            nativeSnapshot(holder)
            assertEquals("same identity must not keep querying", readyQuery, query(holder))
            nativeSnapshot(holder, session = "other")
            assertEquals("another session must not replace the query", readyQuery, query(holder))
            nativeSnapshot(holder, epoch = "next")
            assertNotEquals(readyQuery, query(holder))
        } finally { holder.close() }
    }

    @Test fun nativeReconnectAndOfflineAllowTheSameIdentityToRefreshAgain() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder, bridge = true)
            holder.selectSession("source")
            nativeSnapshot(holder)
            val first = requireNotNull(query(holder))
            wire(holder, """{"kind":"bridge-offline","sessionId":"source"}""")
            nativeSnapshot(holder)
            val afterOffline = requireNotNull(query(holder))
            assertNotEquals(first, afterOffline)
            wire(holder, """{"kind":"hello","capabilities":["worktree-v1","bridge","bridge-workspace-v1"]}""")
            nativeSnapshot(holder)
            assertNotEquals(afterOffline, query(holder))
        } finally { holder.close() }
    }

    @Test fun nativeDirectoryRefreshIgnoresMissingIdentityAndSdkSnapshots() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder, bridge = true)
            holder.selectSession("source")
            wire(holder, """{"kind":"bridge-snapshot","sessionId":"source","instanceId":"","epoch":"epoch","bridgeSeq":1,"cwd":"/synthetic/source"}""")
            assertNull(query(holder))
            connected(holder)
            nativeSnapshot(holder)
            assertNull(query(holder))
        } finally { holder.close() }
    }

    @Test fun nativeSnapshotDoesNotConsumeRefreshWhileCapabilitiesAreMissing() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder, bridge = true, worktrees = false)
            holder.selectSession("source")
            nativeSnapshot(holder)
            assertNull(query(holder))
            connected(holder, bridge = true)
            nativeSnapshot(holder)
            assertNotNull(query(holder))
        } finally { holder.close() }
    }

    @Test fun hostModeChangeAndReselectionPermitDirectoryRefreshAgain() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder, bridge = true)
            holder.selectSession("source")
            nativeSnapshot(holder)
            val first = requireNotNull(query(holder))
            wire(holder, """{"kind":"host-mode","mode":"bridge","changed":true}""")
            nativeSnapshot(holder)
            val afterModeChange = requireNotNull(query(holder))
            assertNotEquals(first, afterModeChange)
            holder.selectSession("source")
            nativeSnapshot(holder)
            assertNotEquals(afterModeChange, query(holder))
        } finally { holder.close() }
    }

    private fun begin(holder: AndroidSharedStateHolder): String {
        connected(holder)
        holder.selectSession("source")
        holder.openWorktree("/synthetic/source")
        holder.continueInDirectory("/synthetic/target")
        return requireNotNull(holder.worktreeUi.operationId)
    }

    private fun success(id: String) = GatewayFrame(kind = "worktree-operation", operationId = id,
        action = "continue", status = "succeeded", sessionId = "source", targetSessionId = "target", cwd = "/synthetic/target")

    @Test fun persistedConfirmationNeverContainsDraftAndRejectsAnotherGateway() {
        val id = java.util.UUID.randomUUID().toString()
        val ui = WorktreeUiState(sourceSessionId = "source", sourceCwd = "/synthetic/source", operationId = id,
            action = "continue", targetPath = "/synthetic/target", mode = WorktreeMode.CONTINUE)
        val raw = worktreePendingJson(ui, "synthetic-host-digest")
        val restored = requireNotNull(restoreWorktreePending(raw, "synthetic-host-digest"))
        assertEquals(id, restored.operationId)
        assertEquals(WorktreePhase.CONFIRM, restored.phase)
        assertEquals(-1L, restored.navigationEpoch)
        assertNull(restoreWorktreePending(raw, "other-host"))
        assertNull(restoreWorktreePending("bad-json", "synthetic-host-digest"))
        assertFalse(raw.contains("messageDraft"))
        assertFalse(raw.contains("images"))
    }

    @Test fun authoritativeSuccessCarriesLatestDraftAndImagesOnceAndLeavesHomeTargetAlone() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectFineCatDirectory("/synthetic/home")
            val id = begin(holder)
            holder.messageDraft = "edited while switching"
            val image = AndroidPreparedImage(GatewayOutgoingImage("image/png", "c3ludGhldGlj"), 1, 2, 9)
            holder.setPreparedImagesForTest(listOf(image))
            holder.receiveWorktreeFrame(success(id))
            assertEquals("target", holder.snapshot.selectedSessionId)
            assertEquals("edited while switching", holder.messageDraft)
            assertEquals(listOf(image), holder.preparedImages)
            assertEquals("/synthetic/home", holder.fineCatNewSessionDirectoryPath)
            assertEquals("/synthetic/target", holder.currentSessionDirectory)
            holder.receiveWorktreeFrame(success(id))
            assertEquals("edited while switching", holder.messageDraft)
            holder.selectSession("source")
            assertEquals("", holder.messageDraft)
            assertTrue(holder.preparedImages.isEmpty())
            holder.selectSession("target")
            assertEquals("edited while switching", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun lateSuccessCannotChangeAnotherSessionOrStealTheSourceDraft() {
        val holder = AndroidSharedStateHolder()
        try {
            val id = begin(holder)
            holder.messageDraft = "source draft"
            holder.selectSession("other")
            holder.messageDraft = "other draft"
            holder.receiveWorktreeFrame(success(id))
            assertEquals("other", holder.snapshot.selectedSessionId)
            assertEquals("other draft", holder.messageDraft)
            holder.selectSession("source")
            assertEquals("source draft", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun trustRejectionAndMalformedSuccessBothReleaseTheBusyCardWithoutLosingDraft() {
        val holder = AndroidSharedStateHolder()
        try {
            val id = begin(holder)
            holder.messageDraft = "keep"
            holder.rejectWorktreeRequest("worktree-continue", id, "worktree-trust-required")
            assertEquals(WorktreePhase.TRUST, holder.worktreeUi.phase)
            holder.closeWorktree()
            assertFalse(holder.worktreeUi.open)
            holder.openWorktree("/synthetic/source")
            holder.receiveWorktreeFrame(success(id).copy(targetSessionId = null))
            assertEquals(WorktreePhase.CONFIRM, holder.worktreeUi.phase)
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("keep", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun lateCreateAcknowledgementNeverNavigatesAwayFromTheNewlySelectedSession() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectSession("source")
            holder.openWorktree("/synthetic/source")
            holder.createWorktree("fix/test", "HEAD", "/synthetic/new", false)
            val id = holder.worktreeUi.operationId
            holder.selectSession("other")
            holder.messageDraft = "other draft"
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-operation", operationId = id, action = "create",
                status = "succeeded", cwd = "/synthetic/new", worktree = GatewayWorktree("/synthetic/new", "fix/test")))
            assertEquals("other", holder.snapshot.selectedSessionId)
            assertEquals("other draft", holder.messageDraft)
            assertEquals(WorktreePhase.LIST, holder.worktreeUi.phase)
            assertFalse(holder.worktreeUi.open)
        } finally { holder.close() }
    }

    @Test fun staleRejectionCannotCloseTheCurrentOperation() {
        val holder = AndroidSharedStateHolder()
        try {
            begin(holder)
            holder.rejectWorktreeRequest("worktree-continue", "previous", "worktree-busy")
            assertEquals(WorktreePhase.SWITCHING, holder.worktreeUi.phase)
        } finally { holder.close() }
    }

    @Test fun onlyCreateKeepsComposerUsableAndClosingUnknownNeverMovesLaterEdits() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectSession("source")
            holder.messageDraft = "stay here"
            holder.openWorktree("/synthetic/source")
            holder.createWorktree("fix/test", "HEAD", "/synthetic/new", true)
            assertTrue(holder.canSend)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-operation", operationId = holder.worktreeUi.operationId,
                action = "create", status = "succeeded", cwd = "/synthetic/new"))
            // 实际续聊确认期间阻止发送；关闭确认卡后留在原会话继续编辑。
            holder.continueInDirectory("/synthetic/target")
            val id = requireNotNull(holder.worktreeUi.operationId)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-operation", operationId = id,
                action = "continue", status = "unknown"))
            assertFalse(holder.canSend)
            holder.closeWorktree()
            assertTrue(holder.canSend)
            holder.messageDraft = "edited after closing"
            holder.receiveWorktreeFrame(success(id))
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("edited after closing", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun directoryNewSessionWaitsForTrustAndKeepsTheOriginalDraft() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectSession("source")
            holder.messageDraft = "original draft"
            holder.newSessionInWorkspace("/synthetic/target")
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("select", holder.worktreeUi.action)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-trust", operationId = holder.worktreeUi.operationId,
                cwd = "/synthetic/target"))
            assertNull(holder.snapshot.selectedSessionId)
            assertEquals("/synthetic/target", holder.fineCatNewSessionDirectoryPath)
            holder.selectSession("source")
            assertEquals("original draft", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun createFailureRetainsFormAndReturningToListDropsOldReceipts() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectSession("source")
            holder.messageDraft = "keep source"
            holder.openWorktree("/synthetic/source")
            holder.createWorktree("fix/saved", "main", "/synthetic/saved", false)
            val id = requireNotNull(holder.worktreeUi.operationId)
            holder.rejectWorktreeRequest("worktree-create", id, "worktree-create-failed")
            holder.closeWorktree()
            holder.openWorktree("/synthetic/source")
            assertEquals("fix/saved", holder.worktreeUi.createBranch)
            assertEquals("main", holder.worktreeUi.createBase)
            assertEquals("/synthetic/saved", holder.worktreeUi.createPath)
            holder.worktreeForm(false)
            holder.receiveWorktreeFrame(success(id))
            assertEquals(WorktreePhase.LIST, holder.worktreeUi.phase)
            assertNull(holder.worktreeUi.operationId)
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("keep source", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun homeTargetTrustCanBeCancelledAndDirectErrorsUseTheSameTerminalMapping() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectFineCatDirectory("/synthetic/previous")
            holder.newSessionInWorkspace("/synthetic/untrusted")
            val id = requireNotNull(holder.worktreeUi.operationId)
            holder.rejectWorktreeRequest("worktree-trust", id, "worktree-trust-required")
            holder.closeWorktree()
            assertEquals("/synthetic/previous", holder.fineCatNewSessionDirectoryPath)
            holder.openWorktree("/synthetic/previous")
            holder.receiveWorktreeFrame(GatewayFrame(kind = "error", requestType = "worktree-trust", operationId = id,
                code = "worktree-target-unavailable"))
            assertEquals(WorktreePhase.TARGET_GONE, holder.worktreeUi.phase)
        } finally { holder.close() }
    }
    private fun sessionList(holder: AndroidSharedStateHolder, items: String = """[
        {"id":"source","cwd":"/synthetic/source","name":"Source","modified":"2026-10-01T10:00:00Z"},
        {"id":"target","cwd":"/synthetic/target","name":"Older","modified":"2026-10-02T10:00:00Z"},
        {"id":"target-new","cwd":"/synthetic/target/","name":"Latest","modified":"2026-10-03T10:00:00Z"},
        {"id":"child","cwd":"/synthetic/target/child","name":"Child"},
        {"id":"prefix","cwd":"/synthetic/target-copy","name":"Other worktree"}
    ]""") {
        holder.wirePayload = """{"kind":"sessions","items":$items}"""
        holder.submitWirePayload()
    }

    private fun browseExisting(holder: AndroidSharedStateHolder, path: String = "/synthetic/target") {
        connected(holder)
        holder.openWorktree("/synthetic/source")
        holder.worktreeMode(WorktreeMode.EXISTING)
        holder.chooseWorktree(path)
        sessionList(holder)
    }

    @Test fun homeDefaultsToNewWhileHistoryDefaultsToContinueAndExistingDoesNotNavigate() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectFineCatDirectory("/synthetic/source")
            holder.openWorktree("/synthetic/source")
            assertEquals(WorktreeMode.NEW, holder.worktreeUi.mode)
            holder.worktreeMode(WorktreeMode.CONTINUE)
            assertEquals(WorktreeMode.NEW, holder.worktreeUi.mode)
            holder.worktreeMode(WorktreeMode.EXISTING)
            holder.chooseWorktree("/synthetic/target")
            assertEquals(WorktreePhase.SESSIONS, holder.worktreeUi.phase)
            assertNull(holder.snapshot.selectedSessionId)
            assertEquals("/synthetic/source", holder.fineCatNewSessionDirectoryPath)
            holder.closeWorktree()
            holder.selectSession("source")
            holder.openWorktree("/synthetic/source")
            assertEquals(WorktreeMode.CONTINUE, holder.worktreeUi.mode)
        } finally { holder.close() }
    }

    @Test fun existingListsOnlyExactDirectoryAndOnlyLatestAuthoritativeItems() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("source")
            browseExisting(holder)
            assertEquals(listOf("target-new", "target"), holder.worktreeSessions.map { it.id })
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertNull(holder.worktreeUi.operationId)
            sessionList(holder, """[{"id":"target","cwd":"/synthetic/target","name":"Only remaining"}]""")
            assertEquals(listOf("target"), holder.worktreeSessions.map { it.id })
            // 全局Reducer会保留旧摘要，面板不能让已在最新列表缺席的条目仍可选。
            assertTrue(holder.snapshot.sessions.any { it.id == "target-new" })
            holder.openWorktreeSession("target-new")
            assertNull(holder.worktreeUi.operationId)
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertNotNull(holder.worktreeUi.error)
            holder.refreshWorktreeSessions()
            assertNull(holder.worktreeUi.error)
        } finally { holder.close() }
    }

    @Test fun openingExistingWaitsForTrustThenRestoresItsOwnDraftAndImages() {
        val holder = AndroidSharedStateHolder()
        val targetImage = AndroidPreparedImage(GatewayOutgoingImage("image/png", "c3ludGhldGljLXRhcmdldA==", "target-attachment"), 1, 1, 9)
        val sourceImage = AndroidPreparedImage(GatewayOutgoingImage("image/png", "c3ludGhldGljLXNvdXJjZQ==", "source-attachment"), 1, 1, 9)
        try {
            connected(holder)
            holder.selectSession("target")
            holder.messageDraft = "target draft"
            holder.setPreparedImagesForTest(listOf(targetImage))
            holder.selectSession("source")
            holder.messageDraft = "source draft"
            holder.setPreparedImagesForTest(listOf(sourceImage))
            browseExisting(holder)
            holder.openWorktreeSession("target")
            val id = requireNotNull(holder.worktreeUi.operationId)
            assertEquals("open", holder.worktreeUi.action)
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("source draft", holder.messageDraft)
            holder.rejectWorktreeRequest("worktree-trust", id, "worktree-trust-required")
            assertEquals(WorktreePhase.TRUST, holder.worktreeUi.phase)
            holder.confirmWorktreeTrust()
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-trust", operationId = id, cwd = "/synthetic/target"))
            assertEquals("target", holder.snapshot.selectedSessionId)
            assertEquals("target draft", holder.messageDraft)
            assertEquals(listOf(targetImage), holder.preparedImages)
            assertFalse(holder.worktreeUi.open)
            holder.selectSession("source")
            assertEquals("source draft", holder.messageDraft)
            assertEquals(listOf(sourceImage), holder.preparedImages)
        } finally { holder.close() }
    }

    @Test fun existingTrustFailureNeverBecomesContinuationOrAcceptsLateReceipt() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("source")
            holder.messageDraft = "keep this"
            browseExisting(holder)
            holder.openWorktreeSession("target")
            val id = requireNotNull(holder.worktreeUi.operationId)
            holder.rejectWorktreeRequest("worktree-trust", id, "worktree-target-unavailable")
            assertEquals(WorktreePhase.SESSIONS, holder.worktreeUi.phase)
            assertNull(holder.worktreeUi.operationId)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-trust", operationId = id, cwd = "/synthetic/target"))
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("keep this", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun returningToSourceDoesNotAuthorizeOldExistingSessionReceipt() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("source")
            browseExisting(holder)
            holder.openWorktreeSession("target")
            val id = requireNotNull(holder.worktreeUi.operationId)
            holder.selectSession("other")
            holder.selectSession("source")
            holder.messageDraft = "new source draft"
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-trust", operationId = id, cwd = "/synthetic/target"))
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("new source draft", holder.messageDraft)
            assertFalse(holder.worktreeUi.open)
        } finally { holder.close() }
    }

    @Test fun browsingAndOpeningExistingAreAllowedWhileSourceRuns() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("source")
            holder.wirePayload = """{"kind":"state","sessionId":"source","isStreaming":true}"""
            holder.submitWirePayload()
            assertNotNull(holder.worktreeBlockReason())
            browseExisting(holder)
            holder.openWorktreeSession("target")
            assertEquals("open", holder.worktreeUi.action)
            assertEquals(WorktreePhase.SWITCHING, holder.worktreeUi.phase)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-trust", operationId = holder.worktreeUi.operationId, cwd = "/synthetic/target"))
            assertEquals("target", holder.snapshot.selectedSessionId)
            assertTrue(holder.snapshot.sessionStates["source"]?.isStreaming == true)
        } finally { holder.close() }
    }

    @Test fun malformedSessionsCanRetryAndBackDoesNotChangeDirectoryOrDraft() {
        val holder = AndroidSharedStateHolder()
        try {
            connected(holder)
            holder.selectSession("source")
            holder.messageDraft = "source draft"
            holder.openWorktree("/synthetic/source")
            holder.worktreeMode(WorktreeMode.EXISTING)
            holder.chooseWorktree("/synthetic/target")
            holder.receiveWorktreeFrame(GatewayFrame(kind = "sessions"))
            assertNotNull(holder.worktreeUi.sessionsError)
            assertFalse(holder.worktreeUi.sessionsLoading)
            holder.refreshWorktreeSessions()
            assertTrue(holder.worktreeUi.sessionsLoading)
            // 无queryId的旧sessions取消/拒绝不能结束新面板的本地等待。
            holder.rejectWorktreeRequest("sessions", null, "request-coalesced")
            assertTrue(holder.worktreeUi.sessionsLoading)
            sessionList(holder, "[]")
            assertFalse(holder.worktreeUi.sessionsLoading)
            assertNull(holder.worktreeUi.sessionsError)
            assertTrue(holder.worktreeSessions.isEmpty())
            holder.worktreeSessionListBack()
            assertEquals(WorktreePhase.LIST, holder.worktreeUi.phase)
            assertEquals("source", holder.snapshot.selectedSessionId)
            assertEquals("source draft", holder.messageDraft)
        } finally { holder.close() }
    }

    @Test fun existingSessionTrustAcceptsNormalizedTrailingSlash() {
        val holder = AndroidSharedStateHolder()
        try {
            holder.selectSession("source")
            browseExisting(holder, "/synthetic/target/")
            holder.openWorktreeSession("target")
            val id = requireNotNull(holder.worktreeUi.operationId)
            holder.receiveWorktreeFrame(GatewayFrame(kind = "worktree-trust", operationId = id, cwd = "/synthetic/target"))
            assertEquals("target", holder.snapshot.selectedSessionId)
            assertFalse(holder.worktreeUi.open)
        } finally { holder.close() }
    }

}
