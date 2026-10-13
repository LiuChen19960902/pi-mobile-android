package com.finecat.pimobile.android

import com.finecat.pimobile.android.platform.AndroidPreparedImage
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidBranchDraftRestoreTest {
    private fun holder() = AndroidSharedStateHolder().apply { selectSession("s") }
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
    @Test fun switchingSessionsInvalidatesAutomaticOwnership() {
        val h = holder(); h.branch("第一条"); h.selectSession("other")
        h.branch("别会话的输入", session = "other")
        assertEquals("第一条", h.messageDraft)
    }
    @Test fun emptyEditorTextCanClearOldAutomaticDraft() {
        val h = holder(); h.branch("第一条"); h.branch("")
        assertEquals("", h.messageDraft)
    }
    @Test fun assistantSelectionRestoresNearestRealUserFromMatchingHistory() {
        val h = holder(); h.tree(); h.branch("第一条"); h.assistant()
        assertEquals("", h.messageDraft)
        h.history()
        assertEquals("第二条用户完整内容", h.messageDraft)
        h.branch("第三条"); assertEquals("第三条", h.messageDraft)
    }
    @Test fun staleAndOtherSessionHistoryCannotRestorePendingDraft() {
        val h = holder(); h.tree(); h.assistant()
        h.history(leaf = "a1"); h.history(session = "other")
        assertEquals("", h.messageDraft)
        h.history(); assertEquals("第二条用户完整内容", h.messageDraft)
    }
    @Test fun editsWhileHistoryIsLoadingArePreserved() {
        val h = holder(); h.tree(); h.assistant(); h.messageDraft = "在途手写"
        h.history(); assertEquals("在途手写", h.messageDraft)
    }
    @Test fun newerBranchResponseSupersedesPendingHistory() {
        val h = holder(); h.tree(); h.assistant("a1"); h.assistant("a2")
        h.history(leaf = "a1"); assertEquals("", h.messageDraft)
        h.history(); assertEquals("第二条用户完整内容", h.messageDraft)
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
    @Test fun historyArrivingBeforeTreeWaitsAndRestoresAfterTree() {
        val h = holder(); h.assistant(); h.history()
        assertEquals("", h.messageDraft)
        h.tree(); assertEquals("第二条用户完整内容", h.messageDraft)
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
}
