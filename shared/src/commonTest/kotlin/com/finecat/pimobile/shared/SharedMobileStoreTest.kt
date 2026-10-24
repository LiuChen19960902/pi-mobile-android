package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.facade.SharedMobileFacade
import com.finecat.pimobile.shared.facade.SharedMobileStore
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.SessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMobileStoreTest {
    @Test fun nativeResumeReceiptCannotSelectAnOldSessionFromTheNewConversationPage() {
        val store = SharedMobileStore()
        store.selectSession("old")
        store.selectSession(null)
        val after = store.acceptFrame("""{"kind":"sent","sessionId":"old","nativeResume":true,"disposition":"submitted"}""")
        assertNull(after.selectedSessionId)
        assertTrue(after.sessions.any { it.id == "old" })
        assertEquals("new", store.acceptFrame("""{"kind":"sent","sessionId":"new"}""").selectedSessionId)
    }

    @Test fun bridgeModeCannotDisplayStatusesFromAnOldSdkHandle() {
        val store = SharedMobileStore()
        val sdk = """{"kind":"extension-status","sessionId":"same","statuses":[{"key":"agent","text":"SDK only"}]}"""
        assertEquals("SDK only", store.acceptFrame(sdk).extensionStatuses.getValue("same").single().text)
        assertTrue(store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""").extensionStatuses.isEmpty())
        assertTrue(store.acceptFrame(sdk).extensionStatuses.isEmpty())
        store.acceptFrame("""{"kind":"host-mode","mode":"sdk","changed":true}""")
        assertEquals("SDK only", store.acceptFrame(sdk).extensionStatuses.getValue("same").single().text)
    }

    // ---- R1：桥接拉式快照按会话/代际/已消费水位排序，旧结果不得回退运行态 ----

    @Test fun leavingBridgeModeDropsStaleWatermarksBeforeNextBridgeInstance() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":9,"busy":true,"isStreaming":true,"isCompacting":false}""")
        // 切回 sdk 再切回 bridge：新实例代际不同，若沿用旧水位/代际，新的
        // state 应答会被当成异代际全拒，直到新快照到达。
        store.acceptFrame("""{"kind":"host-mode","mode":"sdk","changed":true}""")
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        val after = store.acceptFrame(
            """{"kind":"state","sessionId":"s1","instanceId":"i2","epoch":"e2","bridgeSeq":1,"isStreaming":false,"isCompacting":false}"""
        ).sessionStates.getValue("s1")
        assertFalse(after.isStreaming, "新代际的低水位应答仍要生效")

    }

    @Test fun staleEventBodyDoesNotRollBackNewerState() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":true,"isStreaming":true,"isCompacting":false}""")
        // 拉式 state（另一条请求 socket）先带回 seq5 的空闲状态。
        store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":5,"isStreaming":false,"isCompacting":false}""")
        assertFalse(store.snapshot().sessionStates.getValue("s1").isStreaming)
        // 订阅上随后到达 seq2 的事件（正文已在 Runtime 投影）：不能把状态翻回运行中。
        val afterStaleEvent = store.acceptFrame(
            """{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"isStreaming":true,"isCompacting":false,"event":{"type":"message_end"}}"""
        )
        assertFalse(afterStaleEvent.sessionStates.getValue("s1").isStreaming, "旧事件的状态字段不得覆盖更新的 state")
        // 同一事件重放不推进正文水位，也不改状态。
        store.acceptFrame("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"isStreaming":true,"isCompacting":false,"event":{"type":"message_end"}}""")
        // 更新的实时事件（seq6）仍要生效。
        val afterNewEvent = store.acceptFrame(
            """{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":6,"isStreaming":true,"isCompacting":false,"event":{"type":"agent_start"}}"""
        )
        assertTrue(afterNewEvent.sessionStates.getValue("s1").isStreaming, "更新的实时事件仍要改状态")
    }

    // ---- R6：状态门控必须穿透 normalized 事件与同代际旧快照 ----

    @Test fun normalizedTurnEventFromStaleFrameDoesNotFlipStreaming() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":true,"isStreaming":true,"isCompacting":false}""")
        store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":5,"isStreaming":false,"isCompacting":false}""")
        // agent_start（seq2）归一后是 turn/start：raw 字段已被门控，normalized 入口同样
        // 不能把更新的 idle 翻回运行中；正文/列表元数据仍照常合并。
        val after = store.acceptEventFrame(
            GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"isStreaming":true,"isCompacting":false,"event":{"type":"agent_start"}}"""),
            listOf(
                SessionEvent("s1", 2, 2.0, GatewayEvent(type = "turn/start"))
            )
        )
        assertFalse(after.sessionStates.getValue("s1").isStreaming, "旧事件的 turn/start 不得改回运行态")
        assertEquals(false, after.sessions.first { it.id == "s1" }.isRunning, "抽屉活动同样不得回退")
        // 旧的 settled（seq3）同样不能清掉更新的运行态。
        store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":7,"isStreaming":true,"isCompacting":false}""")
        val afterSettled = store.acceptEventFrame(
            GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":3,"isStreaming":false,"isCompacting":false,"event":{"type":"agent_settled"}}"""),
            listOf(
                SessionEvent("s1", 3, 3.0, GatewayEvent(type = "turn/end"))
            )
        )
        assertTrue(afterSettled.sessionStates.getValue("s1").isStreaming, "旧 settled 不得清掉更新的运行态")
        assertEquals(true, afterSettled.sessions.first { it.id == "s1" }.isRunning)
        // 旧事件的正文仍要合并进事件流（对话投影照收，不因状态过期被丢）。
        store.selectSession("s1")
        val afterBody = store.acceptEventFrame(
            GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":4,"isStreaming":true,"isCompacting":false,"event":{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"late-body"}]}}}"""),
            listOf(
                SessionEvent("s1", 4, 4.0, GatewayEvent(type = "user/message", text = "late-body", source = "user"))
            )
        )
        assertEquals(listOf("late-body"), afterBody.conversation.map { it.text })
        assertTrue(afterBody.sessionStates.getValue("s1").isStreaming, "正文到达不得连带改回旧状态（仍应是 state seq7 的运行中）")
    }

    @Test fun staleCompactAndQueueEventsDoNotOverrideNewerState() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":false,"isStreaming":false,"isCompacting":false}""")
        store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":5,"isStreaming":false,"isCompacting":false}""")
        val afterCompact = store.acceptEventFrame(
            GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"isStreaming":true,"isCompacting":true,"event":{"type":"compaction_start"}}"""),
            listOf(
                SessionEvent("s1", 2, 2.0, GatewayEvent(type = "compact/start"))
            )
        )
        assertFalse(afterCompact.sessionStates.getValue("s1").isCompacting, "旧事件的 compact/start 不得覆盖更新 state")
        val afterQueue = store.acceptEventFrame(
            GatewayWireDecoder.decode("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":3,"isStreaming":false,"isCompacting":false,"event":{"type":"queue_update","steering":["late"],"followUp":[]}}"""),
            listOf(
                SessionEvent("s1", 3, 3.0, GatewayEvent(type = "queue/update", steering = listOf("late"), followUp = emptyList()))
            )
        )
        assertNull(afterQueue.sessionStates.getValue("s1").queuedMessages, "旧事件的队列内容不得覆盖更新 state")
        assertFalse(afterQueue.sessionStates.getValue("s1").pendingMessages)
    }

    @Test fun sameEpochOlderSnapshotKeepsNewerStatus() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":true,"isStreaming":true,"isCompacting":false}""")
        store.acceptFrame(
            """{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":5,"isStreaming":false,"isCompacting":false,"model":{"provider":"p","modelId":"new"}}"""
        )
        // 订阅 socket 上较早捕获的同代际快照（seq2）后到：状态保持 state 的结论，
        // 水位不被降回 2（随后的 seq4 state 仍必须被拒）。
        val afterOld = store.acceptFrame(
            """{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"busy":true,"isStreaming":true,"isCompacting":false,"model":{"provider":"p","modelId":"old"}}"""
        ).sessionStates.getValue("s1")
        assertFalse(afterOld.isStreaming, "旧快照不得恢复运行态")
        assertEquals("new", afterOld.model?.modelId, "旧快照不得改回旧 model")
        val afterMidState = store.acceptFrame(
            """{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":4,"isStreaming":true,"isCompacting":false}"""
        ).sessionStates.getValue("s1")
        assertFalse(afterMidState.isStreaming, "快照不能把状态水位降到 2")
        // 等水位快照仍是权威：按它重建状态。
        val afterSame = store.acceptFrame(
            """{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":5,"busy":true,"isStreaming":true,"isCompacting":false}"""
        ).sessionStates.getValue("s1")
        assertTrue(afterSame.isStreaming, "等水位快照仍要生效")
        // 新代际不受旧 stateSeq 限制。
        val afterEpoch = store.acceptFrame(
            """{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i2","epoch":"e2","bridgeSeq":1,"busy":false,"isStreaming":false,"isCompacting":false}"""
        ).sessionStates.getValue("s1")
        assertFalse(afterEpoch.isStreaming, "新代际的低水位快照仍要生效")
    }

    @Test fun strictlyOlderSnapshotContentIsStillRejected() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":false,"isStreaming":false,"isCompacting":false}""")
        store.acceptFrame("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":6,"isStreaming":true,"isCompacting":false,"event":{"type":"agent_start"}}""")
        // 正文水位已到 6：seq4 的快照是迟到重放，整帧不接受（状态也不改）。
        val after = store.acceptFrame(
            """{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":4,"busy":false,"isStreaming":false,"isCompacting":false}"""
        ).sessionStates.getValue("s1")
        assertTrue(after.isStreaming, "严格低正文水位的快照不得回退状态")
    }

    @Test fun bridgeStaleStateCannotReviveWorkingAfterSettled() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":true,"isStreaming":true,"isCompacting":false,"pending":false}""")
        store.acceptFrame("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":3,"isStreaming":false,"isCompacting":false,"event":{"type":"agent_settled"}}""")
        // 前台对账的旧应答晚到：seq2 早于已消费的 seq3，不能把 Working 改回运行中。
        val after = store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"isStreaming":true,"isCompacting":false}""")
            .sessionStates.getValue("s1")
        assertFalse(after.isStreaming)
    }

    @Test fun bridgeStaleStateCannotClearNewTurn() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"busy":false,"isStreaming":false,"isCompacting":false}""")
        store.acceptFrame("""{"kind":"event","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"isStreaming":true,"isCompacting":false,"event":{"type":"agent_start"}}""")
        val after = store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":1,"isStreaming":false,"isCompacting":false}""")
            .sessionStates.getValue("s1")
        assertTrue(after.isStreaming)
    }

    @Test fun bridgeStateFromOldEpochIsIgnored() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i2","epoch":"e2","bridgeSeq":5,"busy":true,"isStreaming":true,"isCompacting":false}""")
        val after = store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":99,"isStreaming":false,"isCompacting":false,"model":{"provider":"p","modelId":"old"}}""")
            .sessionStates.getValue("s1")
        assertTrue(after.isStreaming)
        // 旧代际不能顺带改写模型/统计。
        assertNull(after.model)
    }

    @Test fun bridgeStateWithoutIdentityIsNotAppliedToRunningState() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":2,"busy":true,"isStreaming":true,"isCompacting":false}""")
        // 磁盘回退/旧网关形状（无身份无水位）不能清掉运行态。
        val after = store.acceptFrame("""{"kind":"state","sessionId":"s1","isStreaming":false,"isCompacting":false}""")
            .sessionStates.getValue("s1")
        assertTrue(after.isStreaming)
    }

    @Test fun bridgeSameOrNewerStateStillReconciles() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":3,"busy":false,"isStreaming":false,"isCompacting":false}""")
        val same = store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":3,"isStreaming":true,"isCompacting":false}""")
            .sessionStates.getValue("s1")
        assertTrue(same.isStreaming)
        val newer = store.acceptFrame("""{"kind":"state","sessionId":"s1","instanceId":"i1","epoch":"e1","bridgeSeq":7,"isStreaming":false,"isCompacting":false}""")
            .sessionStates.getValue("s1")
        assertFalse(newer.isStreaming)
    }

    @Test fun sdkStateStillAppliesWithoutIdentity() {
        val store = SharedMobileStore()
        // SDK 模式合同不变：state 不带身份/水位也照常落状态。
        val state = store.acceptFrame("""{"kind":"state","sessionId":"s1","isStreaming":true,"isCompacting":false}""")
            .sessionStates.getValue("s1")
        assertTrue(state.isStreaming)
    }

    @Test fun nativeBoundaryWithoutUsageKeepsKnownValueAndAbortCanCorrectIt() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"context","busy":true,"isStreaming":false,"isCompacting":true,"contextUsage":{"tokens":500,"contextWindow":1000,"percent":50}}""")
        val boundary = store.acceptFrame("""{"kind":"event","sessionId":"context","instanceId":"i","epoch":"e","isStreaming":false,"isCompacting":false,"event":{"type":"agent_settled"}}""").sessionStates.getValue("context")
        assertEquals(500L, boundary.contextUsage?.tokens)
        val aborted = store.acceptFrame("""{"kind":"aborted","sessionId":"context","isStreaming":false,"isCompacting":false,"contextUsage":{"tokens":null,"contextWindow":1000,"percent":null}}""").sessionStates.getValue("context")
        assertNull(aborted.contextUsage?.tokens)
        assertEquals(1000L, aborted.contextUsage?.contextWindow)
    }

    @Test fun nativeCompactionSnapshotReportsUsageWithoutInventingStreaming() {
        val store = SharedMobileStore()
        val state = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"context","busy":true,"pending":false,"isStreaming":false,"isCompacting":true,"contextUsage":{"tokens":500,"contextWindow":1000,"percent":50}}""").sessionStates.getValue("context")
        assertEquals(true, state.isCompacting)
        assertEquals(false, state.isStreaming)
        assertEquals(500L, state.contextUsage?.tokens)
        val after = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"context","busy":false,"pending":false,"isStreaming":false,"isCompacting":false,"contextUsage":{"tokens":null,"contextWindow":1000,"percent":null}}""").sessionStates.getValue("context")
        assertNull(after.contextUsage?.tokens)
        assertNull(after.contextUsage?.percent)
        assertEquals(1000L, after.contextUsage?.contextWindow)
        assertEquals(false, after.isCompacting)
    }

    @Test fun nativeBoundaryUpdatesContextAndOfflineClearsStaleUsage() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"context","busy":true,"isStreaming":false,"isCompacting":true,"contextUsage":{"tokens":500,"contextWindow":1000,"percent":50}}""")
        val end = store.acceptFrame("""{"kind":"event","sessionId":"context","instanceId":"i","epoch":"e","seq":1,"isStreaming":false,"isCompacting":false,"contextUsage":{"tokens":null,"contextWindow":1000,"percent":null},"event":{"type":"compaction_end","aborted":false}}""").sessionStates.getValue("context")
        assertEquals(false, end.isCompacting)
        assertEquals(false, end.isStreaming)
        assertNull(end.contextUsage?.tokens)
        assertEquals(1000L, end.contextUsage?.contextWindow)
        assertNull(store.acceptFrame("""{"kind":"bridge-offline","sessionId":"context"}""").sessionStates.getValue("context").contextUsage)
    }


    @Test fun nativeReadCatalogIsAvailableOnHomeAndBoundToTheSelectedOfflineSession() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        store.acceptFrame("""{"kind":"models","nativeRead":true,"groups":[{"provider":"home","models":[]}]}""")
        assertEquals("home", store.snapshot().modelCatalog?.groups?.single()?.provider)
        assertNull(store.selectSession("offline").modelCatalog)
        store.acceptFrame("""{"kind":"models","nativeRead":true,"groups":[{"provider":"late-home","models":[]}]}""")
        assertNull(store.snapshot().modelCatalog)
        store.acceptFrame("""{"kind":"models","sessionId":"offline","nativeRead":true,"groups":[{"provider":"offline","models":[]}]}""")
        assertEquals("offline", store.snapshot().modelCatalog?.groups?.single()?.provider)
        store.acceptFrame("""{"kind":"model-set","sessionId":"offline","model":{"provider":"offline","modelId":"chosen"},"thinkingLevel":"low"}""")
        assertEquals("chosen", store.snapshot().sessionStates.getValue("offline").model?.modelId)
    }

    @Test fun nativeModelCatalogNeverLeaksBetweenSessionsOrModes() {
        val store = SharedMobileStore()
        store.selectSession("s1")
        store.acceptFrame("""{"kind":"models","sessionId":"s1","groups":[{"provider":"one","models":[]}]}""")
        assertEquals("one", store.snapshot().modelCatalog?.groups?.single()?.provider)
        assertNull(store.selectSession("s2").modelCatalog)
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge","changed":true}""")
        assertNull(store.acceptFrame("""{"kind":"models","groups":[{"provider":"late-sdk","models":[]}]}""").modelCatalog)
        assertNull(store.acceptFrame("""{"kind":"models","sessionId":"s1","groups":[{"provider":"stale","models":[]}]}""").modelCatalog)
        store.acceptFrame("""{"kind":"models","sessionId":"s2","groups":[{"provider":"two","models":[]}]}""")
        assertEquals("two", store.snapshot().modelCatalog?.groups?.single()?.provider)
        assertNull(store.acceptFrame("""{"kind":"host-mode","mode":"sdk","changed":true}""").modelCatalog)
        store.acceptFrame("""{"kind":"models","groups":[{"provider":"sdk","models":[]}]}""")
        assertEquals("sdk", store.selectSession("s3").modelCatalog?.groups?.single()?.provider)
    }

    @Test
    fun bridgePendingFlagKeepsQueueVisibleWithoutInventingText() {
        val store = SharedMobileStore()
        for (raw in listOf(
            """{"kind":"bridge-snapshot","sessionId":"s-queue","busy":true,"pending":true}""",
            """{"kind":"state","sessionId":"s-queue","isStreaming":true,"isCompacting":false,"pendingMessages":true,"queuedMessages":{"steering":[],"followUp":[]}}""",
            """{"kind":"history","sessionId":"s-queue","messages":[],"isStreaming":true,"isCompacting":false,"pendingMessages":true,"queuedMessages":{"steering":[],"followUp":[]}}"""
        )) {
            val state = store.acceptFrame(raw).sessionStates.getValue("s-queue")
            assertEquals(true, state.pendingMessages)
            assertEquals(true, state.hasQueuedMessages)
            assertEquals(true, state.queuedMessages?.isEmpty ?: true)
            assertEquals(true, state.isStreaming)
        }
    }

    @Test
    fun bridgeQueueClearsOnRecallOfflineSettleAndSdkMode() {
        for (terminal in listOf(
            """{"kind":"dequeued","sessionId":"s-queue","queueRequestId":"q1","isStreaming":true,"queuedMessages":{"steering":[],"followUp":[]},"queued":["queued draft"]}""",
            """{"kind":"aborted","sessionId":"s-queue","isStreaming":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["queued draft"]}""",
            """{"kind":"bridge-offline","sessionId":"s-queue"}""",
            """{"kind":"bridge-snapshot","sessionId":"s-queue","busy":false,"pending":false}""",
            """{"kind":"event","sessionId":"s-queue","seq":1,"time":1,"event":{"type":"agent_settled"}}""",
            """{"kind":"host-mode","mode":"sdk"}"""
        )) {
            val store = SharedMobileStore()
            val busy = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s-queue","busy":true,"pending":true}""")
            assertEquals(true, busy.sessionStates.getValue("s-queue").hasQueuedMessages)
            val snapshot = if (terminal.contains("agent_settled")) {
                // SharedMobileStore 接收的是 Runtime 已归一的事件，而非原始 Pi 帧。
                store.acceptLiveEvents(listOf(SessionEvent("s-queue", 1, 1.0, GatewayEvent(type = "turn/end"))))
            } else store.acceptFrame(terminal)
            val state = snapshot.sessionStates.getValue("s-queue")
            assertEquals(false, state.pendingMessages)
            assertEquals(false, state.hasQueuedMessages)
            if (terminal.contains("dequeued")) assertEquals(true, state.isStreaming)
        }
    }

    @Test
    fun submittedWithoutPendingSnapshotDoesNotEraseKnownNativeQueue() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s-queue","busy":true,"pending":true}""")
        val submitted = store.acceptFrame("""{"kind":"sent","sessionId":"s-queue","disposition":"submitted","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]}}""")
        assertEquals(true, submitted.sessionStates.getValue("s-queue").hasQueuedMessages)
        val consumed = store.acceptFrame("""{"kind":"state","sessionId":"s-queue","isStreaming":true,"isCompacting":false,"pendingMessages":false,"queuedMessages":{"steering":[],"followUp":[]}}""")
        assertEquals(false, consumed.sessionStates.getValue("s-queue").hasQueuedMessages)
        assertEquals(true, consumed.sessionStates.getValue("s-queue").isStreaming)
    }

    @Test
    fun nativeQueueShowsConfirmedDeliveryModesWithoutInventingQueueContents() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge"}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":1,"busy":true,"pending":true}""")
        for (mode in listOf("followUp", "steer", "followUp")) {
            store.acceptFrame("""{"kind":"sent","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":1,"mode":"$mode","disposition":"submitted","isStreaming":true,"isCompacting":false,"pendingMessages":true}""")
        }
        val queued = store.snapshot().sessionStates.getValue("s-queue")
        assertEquals(listOf("followUp", "steer"), queued.submittedQueueModes)
        assertEquals(null, queued.queuedMessages)
        val replay = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":2,"busy":true,"pending":true}""")
        assertEquals(listOf("followUp", "steer"), replay.sessionStates.getValue("s-queue").submittedQueueModes)
        val cleared = store.acceptFrame("""{"kind":"state","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":3,"isStreaming":true,"pendingMessages":false}""")
        assertEquals(emptyList(), cleared.sessionStates.getValue("s-queue").submittedQueueModes)
        val laterDesktopQueue = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":4,"busy":true,"pending":true}""")
        assertEquals(emptyList(), laterDesktopQueue.sessionStates.getValue("s-queue").submittedQueueModes)
    }

    @Test
    fun nativeDeliveryHintDoesNotCrossGenerationOrOtherSessions() {
        for (terminal in listOf(
            """{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i","epoch":"new","bridgeSeq":1,"busy":true,"pending":true}""",
            """{"kind":"bridge-offline","sessionId":"s-queue"}""",
            """{"kind":"host-mode","mode":"sdk"}"""
        )) {
            val store = SharedMobileStore()
            store.acceptFrame("""{"kind":"host-mode","mode":"bridge"}""")
            store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":1,"busy":true,"pending":true}""")
            store.acceptFrame("""{"kind":"sent","sessionId":"s-queue","instanceId":"i","epoch":"e","bridgeSeq":1,"mode":"followUp","disposition":"submitted","isStreaming":true,"isCompacting":false,"pendingMessages":true}""")
            val other = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"other","instanceId":"o","epoch":"e","bridgeSeq":1,"busy":true,"pending":true}""")
            assertEquals(emptyList(), other.sessionStates.getValue("other").submittedQueueModes)
            assertEquals(emptyList(), store.acceptFrame(terminal).sessionStates.getValue("s-queue").submittedQueueModes)
        }
    }

    @Test
    fun lateSubmittedAckCannotRestoreClearedQueueHint() {
        val sent = """{"kind":"sent","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":2,"disposition":"submitted","mode":"followUp","isStreaming":true,"isCompacting":false,"pendingMessages":true}"""
        for (terminal in listOf(
            """{"kind":"state","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":3,"isStreaming":false,"pendingMessages":false}""",
            """{"kind":"bridge-offline","sessionId":"s"}""",
            """{"kind":"bridge-snapshot","sessionId":"s","instanceId":"i","epoch":"new","bridgeSeq":1,"busy":false,"pending":false}"""
        )) {
            val store = SharedMobileStore()
            store.acceptFrame("""{"kind":"host-mode","mode":"bridge"}""")
            store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":1,"busy":true,"pending":false}""")
            store.acceptFrame(sent)
            store.acceptFrame(terminal)
            val late = store.acceptFrame(sent).sessionStates.getValue("s")
            assertEquals(false, late.isStreaming)
            assertEquals(false, late.hasQueuedMessages)
            assertEquals(emptyList(), late.submittedQueueModes)
        }
    }

    @Test
    fun renamingPreservesPendingHintButDuplicateSubmittedDoesNotReviveIt() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"host-mode","mode":"bridge"}""")
        store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":1,"busy":true,"pending":false}""")
        store.acceptFrame("""{"kind":"sent","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":2,"disposition":"submitted","mode":"steer","isStreaming":true,"isCompacting":false,"pendingMessages":true}""")
        val renamed = store.acceptFrame("""{"kind":"session-named","sessionId":"s","name":"new name"}""").sessionStates.getValue("s")
        assertEquals(listOf("steer"), renamed.submittedQueueModes)
        assertEquals(true, renamed.hasQueuedMessages)
        store.acceptFrame("""{"kind":"state","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":3,"isStreaming":false,"pendingMessages":false}""")
        val duplicate = store.acceptFrame("""{"kind":"sent","sessionId":"s","instanceId":"i","epoch":"e","bridgeSeq":4,"duplicate":true,"disposition":"submitted","mode":"steer","isStreaming":true,"isCompacting":false,"pendingMessages":true}""").sessionStates.getValue("s")
        assertEquals(false, duplicate.hasQueuedMessages)
        assertEquals(emptyList(), duplicate.submittedQueueModes)
    }

    @Test
    fun facadeRejectsFrameWithoutKindInsteadOfBeautifying() {
        // 缺 kind 即违背协议骨架：decodeFrameKind 返回 null（上游按 decode-error 处理），
        // 不再美化成 event。
        assertNull(
            SharedMobileFacade().decodeFrameKind(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        )
    }

    @Test
    fun manualFixtureUsesSharedReducersAndProjection() {
        val store = SharedMobileStore()
        val snapshot = store.loadManualTestFixture()
        assertEquals("android-demo", snapshot.selectedSessionId)
        assertEquals(1, snapshot.sessions.size)
        assertEquals("请验证 Android 是否使用共享 Reducer", snapshot.sessions.single().title)
        assertEquals(2, snapshot.conversation.size)
        assertEquals("共享协议解码、Reducer 与投影已接入。", snapshot.conversation.last().text)
        assertEquals("sessions", snapshot.lastFrameKind)
        assertNull(snapshot.lastError)
    }

    @Test
    fun piModelsFramePopulatesModelCatalog() {
        val store = SharedMobileStore()
        val snapshot = store.acceptFrame(GatewayProtocolFixtures.PI_MODELS)
        val group = snapshot.modelCatalog?.groups?.single()
        assertEquals("relayB", group?.provider)
        val model = group?.models?.single()
        assertEquals("example-model", model?.id)
        assertEquals(1_000_000L, model?.contextWindow)
        assertEquals(true, model?.reasoning)
        assertEquals(listOf("text"), model?.input)
        assertEquals(listOf("off", "minimal", "low", "medium", "high"), model?.thinkingLevels)
    }

    @Test
    fun piStateAndModelSetFramesPopulatePerSessionStates() {
        val store = SharedMobileStore()

        val state = store.acceptFrame(GatewayProtocolFixtures.PI_STATE)
        val stateEntry = state.sessionStates.getValue("s1")
        assertEquals("relayB", stateEntry.model?.provider)
        assertEquals("example-model", stateEntry.model?.modelId)
        assertEquals("high", stateEntry.thinkingLevel)
        assertEquals(false, stateEntry.isStreaming)
        assertEquals(12.5, stateEntry.contextUsage?.percent)
        assertEquals(1_000_000L, stateEntry.contextUsage?.contextWindow)

        val modelSet = store.acceptFrame(GatewayProtocolFixtures.PI_MODEL_SET)
        val modelSetEntry = modelSet.sessionStates.getValue("s1")
        assertEquals("example-model", modelSetEntry.model?.modelId)
        assertNull(modelSetEntry.contextUsage?.percent)

        // 无 sessionId 的 model-set 无法归属到会话，忽略。
        val orphan = store.acceptFrame(
            """{"kind":"model-set","isStreaming":false,"model":{"provider":"p","modelId":"m"}}"""
        )
        assertEquals(setOf("s1"), orphan.sessionStates.keys)
    }

    @Test
    fun sessionNamedFrameRefreshesListTitleInOneDataFlow() {
        // 网关 session-named 应答带 name：store 单一数据流刷新会话列表标题，
        // 顶栏点击/列表长按改名后 UI 无需本地补丁。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"session-named","sessionId":"android-demo","name":"重构计划","isStreaming":false,"isCompacting":false}"""
        )
        assertEquals("重构计划", snapshot.sessions.single().title)
        assertTrue(!snapshot.sessionStates.getValue("android-demo").isStreaming)
        // 空 name 不动列表（容错）。
        store.acceptFrame(
            """{"kind":"session-named","sessionId":"android-demo","isStreaming":false,"isCompacting":false}"""
        )
        assertEquals("重构计划", store.snapshot().sessions.single().title)
    }

    @Test
    fun invalidFrameIsReportedWithoutDestroyingCurrentState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame("not-json")
        assertEquals(1, snapshot.sessions.size)
        assertTrue(!snapshot.lastError.isNullOrBlank())
    }

    @Test
    fun branchSwitchedFrameUpdatesSessionStateSnapshot() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"branch-switched","sessionId":"android-demo","entryId":"e9","leafId":"e9","alreadyAt":false,"editorText":null,"queued":[],"isStreaming":false,"isCompacting":false,"model":{"provider":"relayB","modelId":"example-model"},"thinkingLevel":"high","queuedMessages":{"steering":[],"followUp":[]},"contextUsage":{"percent":8.0,"contextWindow":1000000,"tokens":80000}}"""
        )
        val state = snapshot.sessionStates.getValue("android-demo")
        assertTrue(!state.isStreaming)
        assertEquals(8.0, state.contextUsage?.percent)
    }

    @Test
    fun historyFrameAppliesSessionStateSnapshotLikeStateFrame() {
        // 断连期间 turn/end 丢失 → 重连 catch-up 的 history 帧必须纠正
        // isStreaming，否则 Working 指示器陈旧（拉式 state 轮内不会来）。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"history","sessionId":"android-demo","messages":[],"entryIds":[],"hasMore":false,"bytes":0,"isStreaming":false,"isCompacting":false,"model":{"provider":"relayB","modelId":"example-model"},"thinkingLevel":"high","queuedMessages":{"steering":[],"followUp":[]},"contextUsage":{"percent":8.0,"contextWindow":1000000,"tokens":80000},"latestEntryId":null}"""
        )
        val state = snapshot.sessionStates.getValue("android-demo")
        assertTrue(!state.isStreaming)
        assertEquals(8.0, state.contextUsage?.percent)
    }

    @Test
    fun dequeuedSnapshotClearsQueueButPreservesCurrentRun() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"state","sessionId":"android-demo","isStreaming":true,"queuedMessages":{"steering":["s"],"followUp":["f"]}}""")
        val snapshot = store.acceptFrame("""{"kind":"dequeued","sessionId":"android-demo","queueRequestId":"q-1","isStreaming":true,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["s","f"]}""")
        assertTrue(snapshot.sessionStates.getValue("android-demo").isStreaming)
        assertNull(snapshot.sessionStates.getValue("android-demo").queuedMessages)
    }

    @Test
    fun abortedSnapshotClearsWorkingQueueWithoutInventingAgentEvents_andLegacyAckKeepsQueue() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"state","sessionId":"android-demo","isStreaming":true,"queuedMessages":{"steering":[],"followUp":["pending"]}}""")
        val legacy = store.acceptFrame("""{"kind":"aborted","sessionId":"android-demo"}""")
        assertEquals(listOf("pending"), legacy.sessionStates.getValue("android-demo").queuedMessages?.followUp)
        val stopped = store.acceptFrame("""{"kind":"aborted","sessionId":"android-demo","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]},"queued":["pending"]}""")
        val state = stopped.sessionStates.getValue("android-demo")
        assertEquals(false, state.isStreaming)
        assertEquals(false, state.isCompacting)
        assertNull(state.queuedMessages)
        assertEquals(2, stopped.conversation.size)
    }

    @Test
    fun queueUpdateLiveEventsRefreshQueuedMessagesSnapshot() {
        // pi 原生 queue_update 归一：steer 消费后吸附条立即消失（对齐 TUI），
        // 不再依赖 turn/end 后的 state 请求-应答。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(steering: List<String>, followUp: List<String>) = store.acceptLiveEvents(
            listOf(
                SessionEvent(sessionId, (0..100).random(), 10.0, GatewayEvent(type = "queue/update", steering = steering, followUp = followUp))
            )
        )
        feed(listOf("你可以看看"), emptyList())
        assertEquals(listOf("你可以看看"), store.snapshot().sessionStates.getValue(sessionId).queuedMessages?.steering)
        feed(emptyList(), emptyList())
        assertNull(store.snapshot().sessionStates.getValue(sessionId).queuedMessages)
    }

    @Test
    fun compactionStatusEventsUpdateSessionCompactingState() {
        // 实时事件流（归一后）驱动 isCompacting：compact/start 置真，
        // compact/end 收尾；notice/compact（失败可见行）既收尾又进对话流。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(type: String, extra: String = "") = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    sessionId, (0..100).random(), 10.0,
                    GatewayEvent(type = type, text = if (type == "notice/compact") "上下文压缩失败：x" else null, isError = type == "notice/compact")
                )
            )
        )
        feed("compact/start")
        assertTrue(store.snapshot().sessionStates.getValue(sessionId).isCompacting)
        feed("compact/end")
        assertTrue(!store.snapshot().sessionStates.getValue(sessionId).isCompacting)
        feed("compact/start")
        val failure = feed("notice/compact")
        assertTrue(!store.snapshot().sessionStates.getValue(sessionId).isCompacting)
        // notice/compact 进对话流；compact/start/end 不进。
        assertTrue(failure.conversation.any { it.title == "上下文压缩" && it.isError })
    }

    @Test
    fun manualCompactionUpdatesDrawerWithoutAnAgentSettledEvent() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        fun feed(seq: Int, type: String) = store.acceptLiveEvents(listOf(
            SessionEvent("android-demo", seq, 100.0 + seq, GatewayEvent(type = type))
        ))
        feed(1, "compact/start")
        assertTrue(store.snapshot().sessions.single().isRunning)
        feed(2, "compact/end")
        assertTrue(!store.snapshot().sessions.single().isRunning)
        assertTrue(!store.snapshot().sessions.single().hasUnread)
        assertTrue(store.snapshot().conversation.none { it.title == "compact/start" || it.title == "compact/end" })
    }

    @Test
    fun finishedSnapshotClearsStaleRemoteRunningAndMarksOnlyUnviewedSessionUnread() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"sessions","items":[{"id":"a","running":true},{"id":"b","running":false}]}""")
        store.selectSession("b")
        store.acceptFrame("""{"kind":"compact-finished","sessionId":"a","isStreaming":false,"isCompacting":false}""")
        val sessions = store.snapshot().sessions.associateBy { it.id }
        assertTrue(!sessions.getValue("a").isRunning)
        assertTrue(sessions.getValue("a").hasUnread)
        assertTrue(!sessions.getValue("b").hasUnread)
        assertTrue(!store.selectSession("a").sessions.first { it.id == "a" }.hasUnread)
    }

    @Test
    fun lifecycleKeepsRunningUntilBothChatAndCompactionHaveEnded() {
        for (firstEnd in listOf("turn/end", "compact/end")) {
            val store = SharedMobileStore()
            store.loadManualTestFixture()
            listOf("turn/start", "compact/start", firstEnd).forEachIndexed { i, type ->
                store.acceptLiveEvents(listOf(SessionEvent("android-demo", i + 1, 100.0 + i, GatewayEvent(type))))
            }
            assertTrue(store.snapshot().sessions.single().isRunning)
            val lastEnd = if (firstEnd == "turn/end") "compact/end" else "turn/end"
            store.acceptLiveEvents(listOf(SessionEvent("android-demo", 4, 104.0, GatewayEvent(lastEnd))))
            assertTrue(!store.snapshot().sessions.single().isRunning)
        }
    }

    @Test
    fun stateAndHistorySnapshotsReconcileDrawerAfterMissedEndEvent() {
        for (kind in listOf("state", "history", "model-set", "compact-finished")) {
            val store = SharedMobileStore()
            store.loadManualTestFixture()
            store.acceptFrame("""{"kind":"compact-started","sessionId":"android-demo","isStreaming":false,"isCompacting":true}""")
            assertTrue(store.snapshot().sessions.single().isRunning)
            store.acceptFrame("""{"kind":"$kind","sessionId":"android-demo","isStreaming":false,"isCompacting":false}""")
            assertTrue(!store.snapshot().sessions.single().isRunning)
            assertTrue(!store.snapshot().sessions.single().hasUnread)
        }
    }

    @Test
    fun backgroundCompactionBecomesUnreadOnlyWhenItFinishes() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"sessions","items":[{"id":"a","running":false},{"id":"b","running":false}]}""")
        store.selectSession("b")
        store.acceptLiveEvents(listOf(SessionEvent("a", 1, 100.0, GatewayEvent("compact/start"))))
        val started = store.snapshot().sessions.first { it.id == "a" }
        assertTrue(started.isRunning)
        assertTrue(!started.hasUnread)
        store.acceptLiveEvents(listOf(SessionEvent("a", 2, 101.0, GatewayEvent("compact/end"))))
        val finished = store.snapshot().sessions.first { it.id == "a" }
        assertTrue(!finished.isRunning)
        assertTrue(finished.hasUnread)
    }

    @Test
    fun finishedSnapshotDoesNotStopAnActuallyRunningChat() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"compact-finished","sessionId":"android-demo","isStreaming":true,"isCompacting":false}""")
        assertTrue(store.snapshot().sessions.single().isRunning)
    }

    @Test
    fun turnEventsFlipSessionStreamingState() {
        // 运行中发送键变停止键后，isStreaming 卡死会永久阻塞输入；轮末 state
        // 应答丢失时，事件流（turn/end = agent_settled）本身就是纠正信号。
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(type: String) = store.acceptLiveEvents(
            listOf(
                SessionEvent(sessionId, (0..100).random(), 10.0, GatewayEvent(type = type))
            )
        )
        fun isStreaming() = store.snapshot().sessionStates[sessionId]?.isStreaming == true
        assertTrue(!isStreaming())
        feed("turn/start")
        assertTrue(isStreaming())
        feed("turn/end")
        assertTrue(!isStreaming())
    }

    @Test
    fun upstreamErrorDuringRetryKeepsWorkingUntilSettled() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val sessionId = "android-demo"
        fun feed(seq: Int, type: String, errorMessage: String? = null) = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    sessionId,
                    seq,
                    10.0 + seq,
                    GatewayEvent(type = type, isError = errorMessage != null, errorMessage = errorMessage)
                )
            )
        )

        feed(10, "turn/start")
        feed(11, "assistant/message", "上游 502")
        // agent_end(willRetry=true) 不会归一成 turn/end；退避和重试期间仍 working。
        assertTrue(store.snapshot().sessionStates.getValue(sessionId).isStreaming)
        assertTrue(store.snapshot().conversation.any { it.isError && it.text == "上游 502" })

        // 只有 agent_settled → turn/end 才结束 working。
        feed(12, "turn/end")
        assertTrue(!store.snapshot().sessionStates.getValue(sessionId).isStreaming)
    }

    @Test
    fun piSessionsFramePopulatesSessionListWithIsoTimestamps() {
        val store = SharedMobileStore()
        val snapshot = store.acceptFrame(GatewayProtocolFixtures.PI_SESSIONS)
        assertEquals(1, snapshot.sessions.size)
        assertEquals("s1", snapshot.sessions.single().id)
        assertEquals("第一句话", snapshot.sessions.single().title)
        assertEquals("/tmp/pi-demo", snapshot.sessions.single().cwd)
        assertEquals(1_788_248_176.694, snapshot.sessions.single().lastActivityEpochSeconds)
        assertTrue(!snapshot.sessions.single().isRunning)
    }

    @Test
    fun liveEventsCreateSessionsWithReliableTimestamps() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        val snapshot = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    "stream", 1, 1_800_000_001_000.0,
                    GatewayEvent(type = "assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "delta")
                )
            )
        )
        assertEquals(
            1_800_000_001.0,
            snapshot.sessions.first { it.id == "stream" }.lastActivityEpochSeconds
        )

        store.clearSessionEvents("stream")
    }

    @Test
    fun sentResponseSelectsTheServerAssignedSessionForImmediateLiveProjection() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        var snapshot = store.acceptFrame(
            """{"kind":"sent","sessionId":"session-new","mode":"followUp"}"""
        )
        assertEquals("session-new", snapshot.selectedSessionId)
        assertEquals("session-new", snapshot.sessions.single().id)

        snapshot = store.acceptLiveEvents(
            listOf(
                SessionEvent(
                    "session-new", 1, 1_800_000_001.0,
                    GatewayEvent(type = "user/message", text = "第一句话", source = "user")
                )
            )
        )
        assertEquals(listOf("第一句话"), snapshot.conversation.map { it.text })
    }

    @Test
    fun turnBoundariesKeepRunningStateInSyncWithPiEvents() {
        val store = SharedMobileStore()
        store.acceptFrame(GatewayProtocolFixtures.PI_SESSIONS)
        store.selectSession("s1")

        var snapshot = store.acceptLiveEvents(
            listOf(
                SessionEvent("s1", 1, 1_788_248_180.0, GatewayEvent(type = "turn/start")),
                SessionEvent("s1", 2, 1_788_248_181.0, GatewayEvent(type = "assistant/message", text = "进行中"))
            )
        )
        assertTrue(snapshot.sessions.single().isRunning)
        assertTrue(snapshot.sessions.single().hasUnread.not())

        snapshot = store.acceptLiveEvents(
            listOf(SessionEvent("s1", 3, 1_788_248_182.0, GatewayEvent(type = "turn/end")))
        )
        assertTrue(!snapshot.sessions.single().isRunning)

        // 会话事件清理后不再投影。
        store.clearSessionEvents("s1")
        assertEquals(0, store.snapshot().conversation.size)
    }

    @Test
    fun extensionStatusPushReplacesWholeSessionSnapshotAndNeverTouchesSessionState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        store.acceptFrame("""{"kind":"state","sessionId":"android-demo","isStreaming":true,"isCompacting":false}""")

        val first = store.acceptFrame(
            """{"kind":"extension-status","sessionId":"android-demo","statuses":[{"key":"subagents","text":"1 agent running"},{"key":"review","text":"审查中"}]}"""
        )
        assertEquals(
            listOf("subagents", "review"),
            first.extensionStatuses.getValue("android-demo").map { it.key }
        )
        assertEquals(
            listOf("1 agent running", "审查中"),
            first.extensionStatuses.getValue("android-demo").map { it.text }
        )
        // 状态只是显示提示：不能用来推断忙闲。
        assertTrue(first.sessionStates.getValue("android-demo").isStreaming)

        // 完整快照：新帧没带的 key 即消失；重复 key 后者覆盖；空白文本不进入显示。
        val second = store.acceptFrame(
            """{"kind":"extension-status","sessionId":"android-demo","statuses":[{"key":"subagents","text":"2 agents running"},{"key":"subagents","text":"3 agents running"},{"key":"blank","text":"   "}]}"""
        )
        val entries = second.extensionStatuses.getValue("android-demo")
        assertEquals(listOf("subagents"), entries.map { it.key })
        assertEquals("3 agents running", entries.single().text)

        // 空数组清除该会话条目。
        val cleared = store.acceptFrame("""{"kind":"extension-status","sessionId":"android-demo","statuses":[]}""")
        assertTrue(cleared.extensionStatuses.isEmpty())
    }

    @Test
    fun extensionStatusWithoutSessionIdIsIgnored() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame(
            """{"kind":"extension-status","statuses":[{"key":"subagents","text":"1 agent running"}]}"""
        )
        assertTrue(snapshot.extensionStatuses.isEmpty())
        assertNull(snapshot.lastError)
    }

    @Test
    fun subscribedAndSessionRestartedRepliesInitializeExtensionStatuses() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()

        // 切会话/重连后先由 subscribed 应答带上当前快照，不必等下一次 setStatus。
        val subscribed = store.acceptFrame(
            """{"kind":"subscribed","sessionId":"android-demo","statuses":[{"key":"subagents","text":"1 agent running"}]}"""
        )
        assertEquals("1 agent running", subscribed.extensionStatuses.getValue("android-demo").single().text)

        // session-restarted（重载配置）重建会话后同一字段同样覆盖（而不是累加）。
        val restarted = store.acceptFrame(
            """{"kind":"session-restarted","sessionId":"android-demo","isStreaming":false,"isCompacting":false,"statuses":[{"key":"subagents","text":"2 agents running"}]}"""
        )
        assertEquals(listOf("2 agents running"), restarted.extensionStatuses.getValue("android-demo").map { it.text })
        assertTrue(!restarted.sessionStates.getValue("android-demo").isStreaming)

        // reset 清空状态行（换网关/配对重置不残留上一个连接的状态）。
        assertTrue(store.reset().extensionStatuses.isEmpty())
    }
}

class BridgeFramesTest {
    @Test
    fun bridgeSessionsCarryOnlineFlagAndBridgeOfflineClearsIt() {
        val store = SharedMobileStore()
        val listed = store.acceptFrame(
            """{"kind":"sessions","mode":"bridge","items":[{"id":"s1","cwd":"/tmp","created":"2026-10-20T00:00:00.000Z","modified":"2026-10-20T00:00:00.000Z","messageCount":0,"firstMessage":"","running":true,"online":true,"source":"bridge","runtime":{"instanceId":"i1","epoch":"e1","pid":42}}]}"""
        )
        val session = listed.sessions.single()
        assertEquals(true, session.isOnline)
        assertEquals(true, session.isRunning)

        val offline = store.acceptFrame("""{"kind":"bridge-offline","sessionId":"s1","reason":"process-exited"}""")
        assertEquals(false, offline.sessions.single().isOnline)
        assertEquals(false, offline.sessions.single().isRunning)
        assertEquals(false, offline.sessionStates.getValue("s1").isStreaming)

        // 切回 sdk 后旧标记必须清空，避免继续显示“离线”。
        val sdk = store.acceptFrame("""{"kind":"host-mode","mode":"sdk","switchable":true}""")
        assertEquals(null, sdk.sessions.single().isOnline)
    }

    @Test
    fun sdkSessionsReplyClearsBridgeOnlineMarks() {
        val store = SharedMobileStore()
        store.acceptFrame(
            """{"kind":"sessions","mode":"bridge","items":[{"id":"s1","cwd":"/tmp","created":"2026-10-20T00:00:00.000Z","modified":"2026-10-20T00:00:00.000Z","messageCount":0,"firstMessage":"","running":false,"online":true}]}"""
        )
        val sdk = store.acceptFrame(
            """{"kind":"sessions","items":[{"id":"s1","cwd":"/tmp","created":"2026-10-20T00:00:00.000Z","modified":"2026-10-20T00:00:00.000Z","messageCount":0,"firstMessage":"","running":false}]}"""
        )
        assertEquals(null, sdk.sessions.single().isOnline)
    }

    @Test
    fun bridgeOfflineClearsCompactionAndQueuedMessages() {
        val store = SharedMobileStore()
        store.acceptFrame(
            """{"kind":"sessions","mode":"bridge","items":[{"id":"s1","cwd":"/tmp","created":"2026-10-20T00:00:00.000Z","modified":"2026-10-20T00:00:00.000Z","messageCount":0,"firstMessage":"","running":true,"online":true}]}"""
        )
        val busy = store.acceptFrame(
            """{"kind":"state","sessionId":"s1","isStreaming":true,"isCompacting":true,"queuedMessages":{"steering":["steer"],"followUp":["later"]}}"""
        )
        assertEquals("later", busy.sessionStates.getValue("s1").queuedMessages?.followUp?.single())
        val offline = store.acceptFrame("""{"kind":"bridge-offline","sessionId":"s1","reason":"process-exited"}""")
        val state = offline.sessionStates.getValue("s1")
        // 离线后压缩和服务器队列都已失效；未确认提交由界面恢复记录承载，不能留在队列里。
        assertEquals(false, state.isCompacting)
        assertEquals(true, state.queuedMessages?.isEmpty ?: true)
        assertEquals(false, state.isStreaming)
    }

    @Test
    fun bridgeSnapshotDrivesActivityWithoutSentAck() {
        val store = SharedMobileStore()
        store.acceptFrame(
            """{"kind":"sessions","mode":"bridge","items":[{"id":"s1","cwd":"/tmp","created":"2026-10-20T00:00:00.000Z","modified":"2026-10-20T00:00:00.000Z","messageCount":0,"firstMessage":"","running":false,"online":true}]}"""
        )
        val busy = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","busy":true,"pending":true}""")
        assertEquals(true, busy.sessionStates.getValue("s1").isStreaming)
        assertEquals(true, busy.sessions.single().isRunning)
        assertEquals(true, busy.sessions.single().isOnline)

        val idle = store.acceptFrame("""{"kind":"bridge-snapshot","sessionId":"s1","busy":false,"pending":false}""")
        assertEquals(false, idle.sessions.single().isRunning)
    }
}
