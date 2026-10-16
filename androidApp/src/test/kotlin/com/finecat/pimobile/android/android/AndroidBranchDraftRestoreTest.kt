package com.finecat.pimobile.android

import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import org.junit.Assert.*
import org.junit.Test

class AndroidBranchDraftRestoreTest {
    private fun holder() = AndroidSharedStateHolder().apply { elapsedRealtimeMs = { 1_000L }; selectSession("s") }
    private fun AndroidSharedStateHolder.frame(payload: String) {
        wirePayload = payload
        submitWirePayload()
    }
    private fun AndroidSharedStateHolder.branch(text: String, extra: String = "", session: String = "s") {
        frame("""{"kind":"branch-switched","sessionId":"$session","editorText":"$text"$extra}""")
    }
    private fun AndroidSharedStateHolder.tree() {
        frame("""{"kind":"tree","sessionId":"s","nodes":[
          {"id":"u1","type":"message","role":"user","preview":"第一条"},
          {"id":"u2","type":"message","role":"user","preview":"第二条"},
          {"id":"a1","parentId":"u1","type":"message","role":"assistant","preview":"回答1"},
          {"id":"a2","parentId":"u2","type":"message","role":"assistant","preview":"回答2"}]}""")
    }
    private fun AndroidSharedStateHolder.assistant(id: String = "a2", extra: String = "") {
        frame("""{"kind":"branch-switched","sessionId":"s","entryId":"$id","leafId":"$id"$extra}""")
    }
    private fun AndroidSharedStateHolder.history(leaf: String = "a2", session: String = "s") {
        frame("""{"kind":"history","sessionId":"$session","entryIds":["u2","summary","$leaf"],"messages":[
          {"role":"user","content":[{"type":"text","text":"第二条用户完整内容"}]},
          {"role":"user","content":"分支总结不是用户输入"},
          {"role":"assistant","content":[{"type":"text","text":"回答"}]}],"hasMore":false}""")
    }

    @Test fun repeatedUserNodeSelectionUpdatesAutomaticDraft() {
        val h = holder()
        h.branch("第一条"); assertEquals("第一条", h.messageDraft)
        h.branch("第二条"); assertEquals("第二条", h.messageDraft)
        h.branch("第三条"); assertEquals("第三条", h.messageDraft)
    }
    @Test fun manuallyEditedDraftSurvivesLaterSelection_evenWhenTextIsEditedBack() {
        val h = holder()
        h.branch("第一条")
        h.messageDraft = "手改"; h.messageDraft = "第一条"
        h.branch("第二条")
        assertEquals("第一条", h.messageDraft)
    }
    @Test fun existingManualDraftIsNotOverwritten() {
        val h = holder(); h.messageDraft = "手写未发稿"
        h.branch("历史输入"); assertEquals("手写未发稿", h.messageDraft)
    }
    @Test fun clearingDraftAllowsNewAutomaticRestore() {
        val h = holder(); h.branch("第一条"); h.messageDraft = ""
        h.branch("第二条"); assertEquals("第二条", h.messageDraft)
    }
    @Test fun queuedDraftIsMergedAndNeverOverwrittenByEditorText() {
        val h = holder(); h.branch("第一条")
        h.branch("第二条", """, "queued":["排队1","排队2"]""")
        assertEquals("排队1  排队2  第一条", h.messageDraft)
        h.branch("第三条"); assertEquals("排队1  排队2  第一条", h.messageDraft)
    }
    @Test fun attachmentsProtectTheDraft() {
        val h = holder(); h.branch("第一条")
        h.setPreparedImagesForTest(listOf(AndroidPreparedImage(
            GatewayOutgoingImage("image/png", "AQID"), width = 1, height = 1, byteCount = 3)))
        h.branch("第二条"); assertEquals("第一条", h.messageDraft)
    }
    @Test fun otherSessionAndAlreadyAtAndErrorDoNotReplaceDraft() {
        val h = holder(); h.branch("第一条")
        h.branch("别会话", session = "other")
        h.branch("同叶", """, "alreadyAt":true""")
        h.frame("""{"kind":"error","sessionId":"s","requestType":"switch-branch","error":"failed"}""")
        assertEquals("第一条", h.messageDraft)
    }
    @Test fun switchingSessionsClearsOnlyTheUneditedAutomaticDraft() {
        val h = holder(); h.branch("第一条"); h.selectSession("other")
        assertEquals("", h.messageDraft)
        h.branch("别会话的输入", session = "other")
        assertEquals("别会话的输入", h.messageDraft)
    }
    @Test fun reselectingTheSameSessionKeepsAutomaticDraftReplaceable() {
        val h = holder(); h.branch("第一条"); h.selectSession("s")
        assertEquals("第一条", h.messageDraft)
        h.branch("第二条"); assertEquals("第二条", h.messageDraft)
    }
    @Test fun openingANewSessionClearsTheUneditedBranchDraft() {
        val h = holder(); h.branch("第一条"); h.prepareNewSession()
        assertEquals("", h.messageDraft)
    }
    @Test fun sessionNavigationKeepsEachManualDraftAndAttachmentsWithItsOwner() {
        val h = holder(); h.branch("自动稿"); h.messageDraft = "A手写稿"
        val image = AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)
        h.setPreparedImagesForTest(listOf(image), listOf("content://a"))
        h.selectSession("other")
        assertEquals("", h.messageDraft); assertTrue(h.preparedImages.isEmpty())
        h.messageDraft = "B手写稿"
        h.selectSession("s")
        assertEquals("A手写稿", h.messageDraft)
        assertEquals(listOf(image), h.preparedImages); assertEquals(listOf("content://a"), h.preparedUris)
        h.prepareNewSession(); assertEquals("", h.messageDraft); assertTrue(h.preparedImages.isEmpty())
        h.selectSession("other"); assertEquals("B手写稿", h.messageDraft)
        h.selectSession("s"); assertEquals("A手写稿", h.messageDraft)
    }
    @Test fun sameSessionReselectionCancelsOldAssistantHistoryRestore() {
        val h = holder(); h.tree(); h.assistant(); h.selectSession("s")
        h.history(); assertEquals("", h.messageDraft)
    }
    @Test fun jumpingToTheParentAssistantLeafKeepsTheChildUsersAutomaticDraft() {
        val h = holder(); h.tree(); h.branch("第二轮用户输入")
        // Pi跳user后leaf在其父assistant；再选这个父节点alreadyAt不改编辑器。
        h.assistant("a1", """, "alreadyAt":true""")
        h.frame("""{"kind":"history","sessionId":"s","entryIds":["u1","a1"],"messages":[
          {"role":"user","content":"第一轮用户完整内容"},
          {"role":"assistant","content":"第一轮回复"}],"hasMore":false}""")
        assertEquals("第二轮用户输入", h.messageDraft)
    }
    @Test fun emptyEditorTextCanClearOldAutomaticDraft() {
        val h = holder(); h.branch("第一条"); h.branch("")
        assertEquals("", h.messageDraft)
    }
    @Test fun assistantSelectionKeepsExistingAutomaticDraftAndUserSelectionStillUpdatesIt() {
        val h = holder(); h.tree(); h.branch("第一条"); h.assistant()
        assertEquals("第一条", h.messageDraft)
        h.history(); assertEquals("第一条", h.messageDraft)
        h.branch("第三条"); assertEquals("第三条", h.messageDraft)
    }
    @Test fun historyCannotFillAnEmptyAssistantDraftAcrossAnySession() {
        val h = holder(); h.tree(); h.assistant()
        h.history(leaf = "a1"); h.history(session = "other")
        assertEquals("", h.messageDraft)
        h.history(); assertEquals("", h.messageDraft)
    }
    @Test fun editsWhileHistoryIsLoadingArePreserved() {
        val h = holder(); h.tree(); h.assistant(); h.messageDraft = "在途手写"
        h.history(); assertEquals("在途手写", h.messageDraft)
    }
    @Test fun consecutiveAssistantSelectionsNeverFillUserContent() {
        val h = holder(); h.tree(); h.assistant("a1"); h.assistant("a2")
        h.history(leaf = "a1"); assertEquals("", h.messageDraft)
        h.history(); assertEquals("", h.messageDraft)
    }
    @Test fun queueAndAlreadyAtNeverTriggerAssistantFallback() {
        val h = holder(); h.tree()
        h.assistant(extra = """, "queued":["排队稿"]"""); h.history()
        assertEquals("排队稿", h.messageDraft)
        h.messageDraft = ""; h.assistant(extra = """, "alreadyAt":true"""); h.history()
        assertEquals("", h.messageDraft)
    }
    @Test fun historyWithNoMatchingRealUserNeverUsesSummary() {
        val h = holder(); h.assistant(); h.history()
        assertEquals("", h.messageDraft)
    }
    @Test fun historyArrivingBeforeOrAfterTreeNeverBackfillsAssistantDraft() {
        val h = holder(); h.assistant(); h.history()
        assertEquals("", h.messageDraft)
        h.tree(); assertEquals("", h.messageDraft)
    }
    @Test fun incompleteBranchReplyDoesNotClearPreviousAutomaticDraft() {
        val h = holder(); h.branch("第一条")
        h.frame("""{"kind":"branch-switched","sessionId":"s","entryId":"a2"}""")
        assertEquals("第一条", h.messageDraft)
        h.branch("第二条"); assertEquals("第二条", h.messageDraft)
    }
    @Test fun imageStillPreparingProtectsAutomaticDraft() {
        val h = holder(); h.branch("第一条"); h.beginImagePreparation("content://synthetic")
        h.branch("第二条"); assertEquals("第一条", h.messageDraft)
    }
    @Test fun imageStartedDuringHistoryWaitCancelsRestore() {
        val h = holder(); h.tree(); h.assistant(); h.beginImagePreparation("content://synthetic")
        h.history(); h.cancelImagePreparation("content://synthetic"); h.tree()
        assertEquals("", h.messageDraft)
    }
    @Test fun missingNearestUserNeverFallsBackToAnOlderKnownUser() {
        val h = holder()
        h.frame("""{"kind":"tree","sessionId":"s","nodes":[
          {"id":"u1","type":"message","role":"user","preview":"更早"},
          {"id":"a2","parentId":"missing-user","type":"message","role":"assistant","preview":"回答"}]}""")
        h.assistant()
        h.frame("""{"kind":"history","sessionId":"s","entryIds":["u1","missing-user","a2"],
          "messages":[{"role":"user","content":"更早的已知用户"},
          {"role":"user","content":"真正的最近用户"},{"role":"assistant","content":"回答"}]}""")
        assertEquals("", h.messageDraft)
    }

    @Test fun uneditedAutomaticDraftDoesNotReturnAfterLeavingTheSession() {
        val h = holder(); h.branch("自动回填")
        h.selectSession("other"); h.selectSession("s")
        assertEquals("", h.messageDraft)
    }
    @Test fun photoCompletionUpdatesOnlyItsOriginSessionEvenWithSameUriSelectedElsewhere() {
        val h = holder()
        val first = h.beginImagePreparation("content://same")!!
        h.selectSession("other")
        val second = h.beginImagePreparation("content://same")!!
        val imageA = AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)
        val imageB = imageA.copy(outgoing = GatewayOutgoingImage("image/png", "BAUG"))
        assertTrue(h.completeImagePreparation("content://same", first, imageA))
        assertTrue(h.preparedImages.isEmpty()); assertEquals(setOf("content://same"), h.preparingPhotoUris)
        assertTrue(h.completeImagePreparation("content://same", second, imageB))
        assertEquals(listOf(imageB), h.preparedImages)
        h.selectSession("s"); assertEquals(listOf(imageA), h.preparedImages)
        assertTrue(h.preparingPhotoUris.isEmpty())
    }
    @Test fun newPageCannotBeRepopulatedByDiscardedPhotoWork() {
        val h = AndroidSharedStateHolder()
        val token = h.beginImagePreparation("content://old-page")!!
        h.prepareNewSession()
        assertFalse(h.completeImagePreparation("content://old-page", token,
            AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)))
        assertTrue(h.preparedImages.isEmpty())
    }
    @Test fun manualUserNodeReplacementRequiresConfirmationAndWaitsForItsReply() {
        val h = holder(); h.tree(); h.messageDraft = "手写稿"
        val image = AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)
        h.setPreparedImagesForTest(listOf(image), listOf("content://a"))
        h.switchBranchTo("u2")
        assertNotNull(h.branchDraftConfirmation)
        h.confirmBranchDraftNavigation(useNodeDraft = true)
        assertEquals("手写稿", h.messageDraft); assertEquals(listOf(image), h.preparedImages)
        h.branch("第二条用户完整内容", """, "entryId":"u2","leafId":"a1"""")
        assertEquals("第二条用户完整内容", h.messageDraft)
        assertTrue(h.preparedImages.isEmpty()); assertTrue(h.preparedUris.isEmpty())
    }
    @Test fun choosingKeepDraftOnUserNodeNavigatesWithoutReplacingManualText() {
        val h = holder(); h.tree(); h.messageDraft = "手写稿"
        h.switchBranchTo("u2"); assertNotNull(h.branchDraftConfirmation)
        h.confirmBranchDraftNavigation(useNodeDraft = false)
        h.branch("节点文本", """, "entryId":"u2","leafId":"a1""""); h.history()
        assertEquals("手写稿", h.messageDraft)
    }
    @Test fun cancelConfirmationDoesNotAuthorizeReplacement() {
        val h = holder(); h.messageDraft = "手写稿"
        h.switchBranchTo("a2"); h.dismissBranchDraftConfirmation()
        assertNull(h.branchDraftConfirmation)
        h.confirmBranchDraftNavigation(useNodeDraft = true)
        h.branch("节点文案")
        assertEquals("手写稿", h.messageDraft)
    }
    @Test fun editingAfterChoosingUserNodeReplaceStillProtectsNewDraft() {
        val h = holder(); h.tree(); h.messageDraft = "手写稿"
        h.switchBranchTo("u2"); h.confirmBranchDraftNavigation(useNodeDraft = true)
        h.messageDraft = "等待时新写"
        h.branch("节点文本", """, "entryId":"u2","leafId":"a1"""")
        assertEquals("等待时新写", h.messageDraft)
    }
    @Test fun failedTargetHistoryKeepsOldAutomaticDraftAndCannotApplyLater() {
        val h = holder(); h.tree(); h.branch("旧自动稿"); h.assistant()
        assertEquals("旧自动稿", h.messageDraft)
        h.frame("""{"kind":"error","sessionId":"s","requestType":"history","error":"failed"}""")
        h.history(); assertEquals("旧自动稿", h.messageDraft)
    }
    @Test fun returnedQueueFromOtherSessionNeverChangesVisibleDraft() {
        val h = holder(); h.messageDraft = "A草稿"; h.selectSession("other"); h.messageDraft = "B草稿"
        h.branch("无关回填", """, "queued":["A撤回稿"]""")
        assertEquals("B草稿", h.messageDraft)
        h.selectSession("s"); assertEquals("A撤回稿  A草稿", h.messageDraft)
    }
    @Test fun localSendSuccessKeepsTextUntilGatewayAccepts() {
        val h = holder(); h.messageDraft = "待确认"
        val submission = h.captureMessageSubmissionForTest()
        val outgoing = h.beginOutgoingSend(submission, createPreview = true)
        h.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        assertEquals("待确认", h.messageDraft)
        h.recordOutgoingSent("s"); assertEquals("", h.messageDraft)
    }
    @Test fun acceptedSendClearsSavedOriginalDraftWithoutTouchingAnotherSession() {
        val h = holder(); h.messageDraft = "A发送稿"
        val submission = h.captureMessageSubmissionForTest()
        h.beginOutgoingSend(submission, createPreview = true)
        h.selectSession("other"); h.messageDraft = "B新稿"
        h.recordOutgoingSent("s")
        assertEquals("B新稿", h.messageDraft)
        h.selectSession("s"); assertEquals("", h.messageDraft)
    }
    @Test fun lateAcceptedSendCannotClearReeditedDraftEvenWhenTextIsEditedBack() {
        val h = holder(); h.messageDraft = "A发送稿"
        val submission = h.captureMessageSubmissionForTest()
        h.beginOutgoingSend(submission, createPreview = true)
        h.messageDraft = "改动"; h.messageDraft = "A发送稿"
        h.selectSession("other"); h.recordOutgoingSent("s"); h.selectSession("s")
        assertEquals("A发送稿", h.messageDraft)
    }
    @Test fun failureAndUnconfirmedSendKeepOriginalEditorContent() {
        val h = holder(); h.messageDraft = "未送达"
        val submission = h.captureMessageSubmissionForTest()
        val outgoing = h.beginOutgoingSend(submission, createPreview = true)
        h.settleOutgoingSendLocally(submission, outgoing, sent = true, clearInputOnSuccess = true)
        h.handleOutgoingSendUnconfirmed()
        assertEquals("未送达", h.messageDraft)
    }

    @Test fun interruptedNavigationCanRetryAndDoesNotClearOldDraft() {
        val h = holder(); h.branch("旧自动稿")
        h.switchBranchTo("u1")
        h.finishBranchDraftRequest("switch-branch", "s")
        assertEquals("旧自动稿", h.messageDraft)
        h.switchBranchTo("u2")
        h.branch("新节点", """, "entryId":"u2","leafId":"a1"""")
        assertEquals("新节点", h.messageDraft)
    }
    @Test fun staleHistoryAfterLeavingAndReturningCannotReplaceTheManualDraft() {
        val h = holder(); h.tree(); h.branch("旧自动稿"); h.assistant()
        h.selectSession("other"); h.selectSession("s"); h.messageDraft = "返回后新写"
        h.history(); assertEquals("返回后新写", h.messageDraft)
    }
    @Test fun gatewayAcceptanceKeepsNewerTextInTheSameSession() {
        val h = holder(); h.messageDraft = "发送稿"
        h.beginOutgoingSend(h.captureMessageSubmissionForTest(), createPreview = true)
        h.messageDraft = "发送后新写"
        h.recordOutgoingSent("s")
        assertEquals("发送后新写", h.messageDraft)
    }

    @Test fun clippedVisibleTreeRootDoesNotProveThereIsNoUserDraft() {
        val h = holder(); h.branch("旧自动稿")
        h.frame("""{"kind":"tree","sessionId":"s","nodes":[
          {"id":"a2","type":"message","role":"assistant","preview":"可见根并非真实根"}],"truncated":true}""")
        h.assistant(); h.history()
        assertEquals("旧自动稿", h.messageDraft)
    }
    @Test fun assistantHistoryWithNoUserNeverClearsOldAutomaticDraft() {
        val h = holder(); h.branch("旧自动稿")
        h.frame("""{"kind":"tree","sessionId":"s","nodes":[
          {"id":"a2","type":"message","role":"assistant","preview":"只有assistant"}]}""")
        h.assistant()
        h.frame("""{"kind":"history","sessionId":"s","entryIds":["a2"],
          "messages":[{"role":"assistant","content":"回答"}],"hasMore":false}""")
        assertEquals("旧自动稿", h.messageDraft)
    }

    @Test fun unrelatedHistoryFailuresCannotChangeAssistantDraft() {
        val h = holder(); h.tree(); h.branch("旧自动稿"); h.assistant()
        h.finishBranchDraftRequest("history", null)
        h.finishBranchDraftRequest("history", "other")
        h.history(); assertEquals("旧自动稿", h.messageDraft)
    }
    @Test fun imageAddedAfterSendIsNewDraftContentAndMustSurviveTheOldAcknowledgement() {
        val h = holder(); h.messageDraft = "发送稿"
        h.beginOutgoingSend(h.captureMessageSubmissionForTest(), createPreview = true)
        val token = h.beginImagePreparation("content://added-after-send")!!
        h.selectSession("other")
        val image = AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)
        assertTrue(h.completeImagePreparation("content://added-after-send", token, image))
        h.recordOutgoingSent("s"); h.selectSession("s")
        assertEquals("发送稿", h.messageDraft); assertEquals(listOf(image), h.preparedImages)
    }
    @Test fun lateBranchHistoryNeverCancelsAnotherSessionsPhotoPreparation() {
        val h = holder(); h.tree(); h.branch("旧自动稿"); h.assistant()
        h.selectSession("other"); h.messageDraft = "B草稿"
        val token = h.beginImagePreparation("content://b")!!
        h.history()
        assertEquals("B草稿", h.messageDraft)
        assertEquals(setOf("content://b"), h.preparingPhotoUris)
        assertTrue(h.completeImagePreparation("content://b", token,
            AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)))
        assertEquals(1, h.preparedImages.size)
    }

    @Test fun knownAssistantNodeSkipsReplacementConfirmationAndKeepsManualTextAndImages() {
        val h = holder(); h.tree(); h.messageDraft = "手写稿"
        val image = AndroidPreparedImage(GatewayOutgoingImage("image/png", "AQID"), 1, 1, 3)
        h.setPreparedImagesForTest(listOf(image), listOf("content://a"))
        val token = h.beginImagePreparation("content://processing")!!
        h.switchBranchTo("a2")
        assertNull(h.branchDraftConfirmation)
        h.assistant(); h.history()
        assertEquals("手写稿", h.messageDraft)
        assertEquals(listOf(image), h.preparedImages); assertEquals(listOf("content://a"), h.preparedUris)
        assertEquals(setOf("content://processing"), h.preparingPhotoUris)
        assertTrue(h.completeImagePreparation("content://processing", token,
            image.copy(outgoing = GatewayOutgoingImage("image/png", "BAUG"))))
        assertEquals(2, h.preparedImages.size)
    }
}
