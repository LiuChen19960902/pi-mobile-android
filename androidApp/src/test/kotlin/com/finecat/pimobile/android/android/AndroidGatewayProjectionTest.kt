package com.finecat.pimobile.android

import com.finecat.pimobile.shared.facade.SharedMviEvent
import com.finecat.pimobile.shared.protocol.GatewayFrame
import com.finecat.pimobile.shared.projection.ConversationItemKind
import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.SessionEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pi 原生协议下的投影契约：实时事件走 PiEventNormalizer 的归一结果，history 是
 * pi 条目游标分页（messages/entryIds/oldestEntryId）。
 */
class AndroidGatewayProjectionTest {
    @Test
    fun handledAckUsesActualStateWithoutInventingTurnEnd() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("s-handled")
        val normalizer = assistantNormalizer("s-handled")
        val running = live(normalizer, "s-handled", 1, 1.0, """{"type":"agent_start"}""")
        projection.acceptFrame(running.raw, running.frame, "s-handled", running.events)
        assertEquals(true, projection.snapshot().sessionStates["s-handled"]?.isStreaming)
        val ack = """{"kind":"sent","sessionId":"s-handled","disposition":"handled","isStreaming":false,"isCompacting":false,"queuedMessages":{"steering":[],"followUp":[]}}"""
        projection.acceptFrame(ack, GatewayWireDecoder.decode(ack), "s-handled")
        assertEquals(false, projection.snapshot().sessionStates["s-handled"]?.isStreaming)
        assertTrue(projection.snapshot().conversation.isEmpty())
        projection.close()
    }

    @Test
    fun visibleCustomMessagesMergeWithHistoryByEntryIdAndIgnorePrivateTypes() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("s-custom")
        val normalizer = assistantNormalizer("s-custom")
        val first = live(normalizer, "s-custom", 1, 1.0,
            """{"type":"message_end","message":{"role":"custom","customType":"compaction","content":"same","display":true}}""", "n1")
        projection.acceptFrame(first.raw, first.frame, "s-custom", first.events)
        val second = live(normalizer, "s-custom", 2, 2.0,
            """{"type":"message_end","message":{"role":"custom","customType":"empty-stop-retry","content":"same","display":true}}""", "n2")
        projection.acceptFrame(second.raw, second.frame, "s-custom", second.events)
        assertEquals(2, projection.snapshot().conversation.size)
        val ids = projection.snapshot().conversation.map { it.id }.toSet()
        val history = historyFrame("s-custom", listOf(
            """{"role":"custom","entryType":"custom_message","customType":"compaction","content":"same","display":true}""",
            """{"role":"custom","entryType":"custom_message","customType":"empty-stop-retry","content":"same","display":true}""",
            """{"role":"custom","entryType":"custom_message","customType":"compaction","content":"hidden","display":false}""",
            """{"role":"custom","entryType":"custom_message","customType":"notice","content":"missing visibility"}""",
            """{"role":"custom","entryType":"custom","customType":"private","content":"internal","display":true}""",
            """{"role":"custom","entryType":"wire_oversized","customType":"wire-oversized","content":"消息过大，已省略","display":true}"""
        ), listOf("n1", "n2", "hidden", "missing", "private", "omitted"), false)
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "s-custom")
        val items = projection.snapshot().conversation
        assertEquals(3, items.size)
        assertEquals(2, items.count { it.text == "same" })
        assertTrue(items.filter { it.text == "same" }.all { it.id in ids && it.title == "通知" && !it.isError })
        assertTrue(items.any { it.text == "消息过大，已省略" })
        projection.close()
    }

    @Test
    fun failedLatestAssistantSurvivesReadAndClearsOnBranchReload() {
        val projection = AndroidGatewayProjection()
        try {
            val catalog = """{"kind":"sessions","items":[{"id":"session-a","name":"A","running":false}]}"""
            projection.acceptFrame(catalog, GatewayWireDecoder.decode(catalog), null)
            val failed = historyFrame("session-a", listOf(
                """{"role":"assistant","content":[],"stopReason":"error","errorMessage":"synthetic failure"}"""
            ), listOf("e2"), false)
            projection.acceptFrame(failed, GatewayWireDecoder.decode(failed), "session-a")
            assertTrue(projection.snapshot().sessions.single().hasFailure)
            projection.selectSession("session-a")
            assertTrue("标记已读不能清掉失败", projection.snapshot().sessions.single().hasFailure)
            val toolError = live(PiEventNormalizer("session-a"), "session-a", 1, 101.0,
                """{"type":"message_end","message":{"role":"toolResult","toolCallId":"tool-1","toolName":"bash","content":[],"isError":true}}""")
            projection.acceptFrame(toolError.raw, toolError.frame, "session-a", toolError.events)
            assertTrue("选择重载期间工具结果也不能清掉已确认失败", projection.snapshot().sessions.single().hasFailure)
            projection.acceptFrame(failed, GatewayWireDecoder.decode(failed), "session-a")
            projection.loadHistory("session-a", older = false)
            assertFalse("重新载入分支前不能沿用旧分支失败", projection.snapshot().sessions.single().hasFailure)
        } finally {
            projection.close()
        }
    }

    @Test
    fun deferredSelectionReloadKeepsKnownFailureAndNewSuccessReplacesIt() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val normalizer = PiEventNormalizer("session-a")
            val failed = live(normalizer, "session-a", 1, 101.0,
                """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error"}}""")
            projection.acceptFrame(failed.raw, failed.frame, "session-a", failed.events)
            projection.selectSession("session-a") // 当前取页尚在途，新的选择重载须排队。
            val firstPage = historyFrame("session-a", emptyList(), emptyList(), false)
            projection.acceptFrame(firstPage, GatewayWireDecoder.decode(firstPage), "session-a")
            assertTrue("排队重载的清缓冲也不能清掉已确认失败", projection.snapshot().sessions.single().hasFailure)
            val success = historyFrame("session-a", listOf(assistantMessage("成功")), listOf("e2"), false)
            projection.acceptFrame(success, GatewayWireDecoder.decode(success), "session-a")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
            projection.reset()
            assertTrue(projection.snapshot().sessions.isEmpty())
        } finally {
            projection.close()
        }
    }

    @Test
    fun olderPageErrorCannotReplaceLatestSuccessfulOutcome() {
        val projection = AndroidGatewayProjection()
        try {
            val catalog = """{"kind":"sessions","items":[{"id":"session-a","name":"A","running":false}]}"""
            projection.acceptFrame(catalog, GatewayWireDecoder.decode(catalog), null)
            projection.selectSession("session-a")
            val recent = historyFrame("session-a", listOf(assistantMessage("成功")), listOf("e2"), true)
            projection.acceptFrame(recent, GatewayWireDecoder.decode(recent), "session-a")
            projection.loadHistory("session-a", older = true)
            val older = historyFrame("session-a", listOf(
                """{"role":"assistant","content":[],"stopReason":"error"}"""
            ), listOf("e1"), false)
            projection.acceptFrame(older, GatewayWireDecoder.decode(older), "session-a")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
        } finally {
            projection.close()
        }
    }

    @Test
    fun failedLiveTurnClearsForRetryAndSuccess() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val normalizer = PiEventNormalizer("session-a")
            fun receive(seq: Int, event: String) {
                val frame = live(normalizer, "session-a", seq, 100.0 + seq, event)
                projection.acceptFrame(frame.raw, frame.frame, "session-a", frame.events)
            }
            receive(1, """{"type":"agent_start"}""")
            receive(2, """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error"}}""")
            assertFalse("仍在执行时不展示最终失败", projection.snapshot().sessions.single().hasFailure)
            receive(3, """{"type":"agent_settled"}""")
            assertTrue(projection.snapshot().sessions.single().hasFailure)
            receive(4, """{"type":"agent_start"}""")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
            receive(5, """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"成功"}],"stopReason":"stop"}}""")
            receive(6, """{"type":"agent_settled"}""")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
        } finally {
            projection.close()
        }
    }

    @Test
    fun toolErrorAbortedAndNewUserInputAreNotAssistantFailure() {
        val projection = AndroidGatewayProjection()
        try {
            val normalizer = PiEventNormalizer("session-a")
            fun receive(seq: Int, message: String) {
                val frame = live(normalizer, "session-a", seq, 100.0 + seq,
                    """{"type":"message_end","message":$message}""")
                projection.acceptFrame(frame.raw, frame.frame, "session-a", frame.events)
            }
            receive(1, """{"role":"toolResult","toolCallId":"tool-1","toolName":"bash","content":[{"type":"text","text":"失败"}],"isError":true}""")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
            receive(2, """{"role":"assistant","content":[],"stopReason":"aborted"}""")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
            receive(3, """{"role":"assistant","content":[],"stopReason":"error"}""")
            assertTrue(projection.snapshot().sessions.single().hasFailure)
            receive(4, userMessage("重试"))
            assertFalse(projection.snapshot().sessions.single().hasFailure)
        } finally {
            projection.close()
        }
    }

    @Test
    fun compactionFailureRemainsVisibleAfterFinishedSnapshotAndRead() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val normalizer = PiEventNormalizer("session-a")
            fun receive(seq: Int, event: String) {
                val f = live(normalizer, "session-a", seq, 100.0 + seq, event)
                projection.acceptFrame(f.raw, f.frame, "session-a", f.events)
            }
            receive(1, """{"type":"compaction_start","reason":"manual"}""")
            assertTrue(projection.snapshot().sessions.single().isRunning)
            receive(2, """{"type":"compaction_end","errorMessage":"Compaction failed: synthetic error","aborted":false}""")
            assertFalse(projection.snapshot().sessions.single().isRunning)
            assertTrue(projection.snapshot().sessions.single().hasFailure)
            val finished = """{"kind":"compact-finished","sessionId":"session-a","isStreaming":false,"isCompacting":false}"""
            projection.acceptFrame(finished, GatewayWireDecoder.decode(finished), "session-a")
            assertTrue("finished只代表收尾，不能洗掉失败", projection.snapshot().sessions.single().hasFailure)
            projection.selectSession("session-a")
            assertTrue(projection.snapshot().sessions.single().hasFailure)
            val oldHistory = historyFrame("session-a", listOf(assistantMessage("上次成功")), listOf("old-reply"), false)
            projection.acceptFrame(oldHistory, GatewayWireDecoder.decode(oldHistory), "session-a")
            assertTrue("压缩失败不落盘，旧历史不得覆盖本次已知失败", projection.snapshot().sessions.single().hasFailure)
            receive(3, """{"type":"compaction_start","reason":"manual"}""")
            assertTrue(projection.snapshot().sessions.single().isRunning)
            assertFalse(projection.snapshot().sessions.single().hasFailure)
            receive(4, """{"type":"compaction_end","result":{"summary":"done"},"aborted":false}""")
            assertFalse(projection.snapshot().sessions.single().isRunning)
            assertFalse(projection.snapshot().sessions.single().hasFailure)
        } finally { projection.close() }
    }

    @Test
    fun successfulCompactionClearsPreviousAssistantFailureWithoutAgentSettled() {
        val projection = AndroidGatewayProjection()
        try {
            val normalizer = PiEventNormalizer("session-a")
            val events = listOf(
                """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error"}}""",
                """{"type":"compaction_start","reason":"manual"}""",
                """{"type":"compaction_end","result":{"summary":"done"},"aborted":false}"""
            )
            events.forEachIndexed { i, event ->
                val f = live(normalizer, "session-a", i + 1, 100.0 + i, event)
                projection.acceptFrame(f.raw, f.frame, "session-a", f.events)
                if (i == 0) assertTrue(projection.snapshot().sessions.single().hasFailure)
            }
            val session = projection.snapshot().sessions.single()
            assertFalse(session.isRunning)
            assertFalse(session.hasFailure)
            assertTrue(session.hasUnread)
        } finally { projection.close() }
    }

    @Test
    fun newerHistoryOutcomeCanReplaceKnownCompactionFailure() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val normalizer = PiEventNormalizer("session-a")
            val failed = live(normalizer, "session-a", 1, 1_790_000_000.0,
                """{"type":"compaction_end","errorMessage":"synthetic failure"}""")
            projection.acceptFrame(failed.raw, failed.frame, "session-a", failed.events)
            assertTrue(projection.snapshot().sessions.single().hasFailure)
            val newer = historyFrame("session-a", listOf(
                """{"role":"assistant","timestamp":1790000001000,"content":[{"type":"text","text":"newer success"}]}"""
            ), listOf("newer-reply"), false)
            projection.acceptFrame(newer, GatewayWireDecoder.decode(newer), "session-a")
            assertFalse(projection.snapshot().sessions.single().hasFailure)
        } finally { projection.close() }
    }

    @Test
    fun cancelledAndDeclinedCompactionDoNotBecomeDrawerFailures() {
        for (end in listOf("""{"type":"compaction_end","aborted":true}""", """{"type":"compaction_end","status":"declined"}""")) {
            val projection = AndroidGatewayProjection()
            try {
                val normalizer = PiEventNormalizer("session-a")
                for ((i, event) in listOf("""{"type":"compaction_start"}""", end).withIndex()) {
                    val f = live(normalizer, "session-a", i + 1, 100.0 + i, event)
                    projection.acceptFrame(f.raw, f.frame, "session-a", f.events)
                }
                assertFalse(projection.snapshot().sessions.single().isRunning)
                assertFalse(projection.snapshot().sessions.single().hasFailure)
                assertTrue(projection.snapshot().sessions.single().hasUnread)
            } finally { projection.close() }
        }
    }

    @Test
    fun hiddenHistoryPageKeepsItsCursorWithoutVisibleEvents() {
        val requests = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requests += Triple(session, cursor, reset) }
        )
        try {
            projection.selectSession("session-a")
            val hiddenPage = historyFrame(
                "session-a",
                List(20) { """{"role":"custom","customType":"empty-stop-retry","display":false,"content":"hidden"}""" },
                (21..40).map { "e$it" },
                hasMore = true
            )
            projection.acceptFrame(hiddenPage, GatewayWireDecoder.decode(hiddenPage), "session-a")
            assertTrue(projection.snapshot().conversation.isEmpty())
            assertEquals(null, projection.snapshot().selectedHistoryEarliestSequence)
            assertEquals("e21", projection.snapshot().selectedHistoryNextCursor)
            assertTrue(projection.snapshot().selectedHistoryHasMore)

            projection.loadHistory("session-a", older = true)
            projection.loadHistory("session-a", older = true)
            assertEquals(listOf(Triple("session-a", null, false), Triple("session-a", "e21", false)), requests)
            val older = historyFrame("session-a", listOf(userMessage("更早的对话")), listOf("e1"), false)
            projection.acceptFrame(older, GatewayWireDecoder.decode(older), "session-a")
            assertEquals(listOf("更早的对话"), projection.snapshot().conversation.map { it.text })
            assertFalse(projection.snapshot().selectedHistoryHasMore)
            projection.loadHistory("session-a", older = true)
            assertEquals(2, requests.size)

            projection.catchUpSelectedHistoryAfterReconnect()
            assertEquals(Triple("session-a", null, true), requests.last())
            assertEquals(null, projection.snapshot().selectedHistoryNextCursor)
        } finally {
            projection.close()
        }
    }

    @Test
    fun olderSmallPageKeepsExistingMessageIdentitiesAndOrder() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-a")
            val recent = historyFrame("session-a", (21..40).map { userMessage("消息$it") }, (21..40).map { "e$it" }, true)
            projection.acceptFrame(recent, GatewayWireDecoder.decode(recent), "session-a")
            val previousIds = projection.snapshot().conversation.map { it.id }
            projection.loadHistory("session-a", older = true)
            val older = historyFrame("session-a", (1..20).map { userMessage("消息$it") }, (1..20).map { "e$it" }, false)
            projection.acceptFrame(older, GatewayWireDecoder.decode(older), "session-a")
            val combined = projection.snapshot().conversation
            assertEquals((1..40).map { "消息$it" }, combined.map { it.text })
            assertEquals(previousIds, combined.takeLast(20).map { it.id })
        } finally {
            projection.close()
        }
    }

    @Test
    fun resetAndReconnectDiscardOldBranchMarkers() {
        for (resetAll in listOf(false, true)) {
            val projection = AndroidGatewayProjection()
            try {
                projection.selectSession("session-a")
                val first = """{"kind":"history","sessionId":"session-a","messages":[{"role":"user","content":"recent"}],"entryIds":["e2"],"latestEntryId":"e2","oldestEntryId":"e2","hasMore":true}"""
                projection.acceptFrame(first, GatewayWireDecoder.decode(first), "session-a")
                projection.loadHistory("session-a", older = true)
                // 翻旧页期间主线前进，原链尾 e2 落后于新叶子 e3。
                val older = """{"kind":"history","sessionId":"session-a","messages":[{"role":"user","content":"older"}],"entryIds":["e1"],"latestEntryId":"e3","oldestEntryId":"e1","hasMore":false}"""
                projection.acceptFrame(older, GatewayWireDecoder.decode(older), "session-a")
                assertTrue(projection.isBehindLatest("session-a"))

                if (resetAll) projection.reset() else projection.catchUpSelectedHistoryAfterReconnect()

                assertFalse("新一轮同步前不能沿用旧链尾", projection.isBehindLatest("session-a"))
                assertEquals(null, projection.latestEntryId("session-a"))
            } finally {
                projection.close()
            }
        }
    }

    /**
     * 树页 label-put 写入的 LabelEntry 会成为文件叶子但不投影成消息：
     * history 帧 latestEntryId=LabelEntry 而 entryIds 尾是真实消息，isBehindLatest
     * 不得误报“跳到最新”（误报时点下去会把 switch-branch 打到 LabelEntry 上）。
     * 标记只豁免本端写入的 id；后续主线真有新消息（叶子换成新消息 id）时判定恢复。
     */
    @Test
    fun ownLabelEntryLeafDoesNotFakeBehindLatestAndRealProgressStillDetects() {
        val projection = AndroidGatewayProjection()
        try {
            projection.selectSession("session-l")
            val first = """{"kind":"history","sessionId":"session-l","messages":[{"role":"user","content":"问题"}],"entryIds":["m1"],"latestEntryId":"m1","oldestEntryId":"m1","hasMore":false}"""
            projection.acceptFrame(first, GatewayWireDecoder.decode(first), "session-l")
            assertFalse(projection.isBehindLatest("session-l"))

            // 写标签：文件叶子变成 LabelEntry（lab-1），页内 entryIds 尾仍是 m1。
            val afterLabel = """{"kind":"history","sessionId":"session-l","messages":[{"role":"user","content":"问题"}],"entryIds":["m1"],"latestEntryId":"lab-1","oldestEntryId":"m1","hasMore":false}"""
            projection.markNonRenderingLeaf("session-l", "lab-1")
            projection.acceptFrame(afterLabel, GatewayWireDecoder.decode(afterLabel), "session-l")
            assertFalse("自己写出的标签叶子不算落后", projection.isBehindLatest("session-l"))

            // 主线真的前进：叶子换成新消息 m2，豁免不得吞掉真实的“跳到最新”。
            val advanced = """{"kind":"history","sessionId":"session-l","messages":[{"role":"user","content":"问题"}],"entryIds":["m1"],"latestEntryId":"m2","oldestEntryId":"m1","hasMore":false}"""
            projection.acceptFrame(advanced, GatewayWireDecoder.decode(advanced), "session-l")
            assertTrue("新消息叶子仍应提示落后", projection.isBehindLatest("session-l"))

            // 未标记的其他簿记叶子（如改会话名）仍按原行为误报——整类归一见 TODO HIST03，
            // 这里钉住当前边界，防止窄修被误当整类修复。
            val afterRename = """{"kind":"history","sessionId":"session-l","messages":[{"role":"user","content":"问题"}],"entryIds":["m1"],"latestEntryId":"info-1","oldestEntryId":"m1","hasMore":false}"""
            projection.acceptFrame(afterRename, GatewayWireDecoder.decode(afterRename), "session-l")
            assertTrue("未标记的簿记叶子维持现状（HIST03 待裁定归一）", projection.isBehindLatest("session-l"))

            // 竞态顺序（复审C）：网关 appendLabelChange 后叶子立即前进，同一连接上
            // history 帧可能先于 label-put 应答到达——豁免按具体 entryId 命中，与
            // 登记/帧的先后无关，mark 到位后判定即收敛为不落后。
            val frameFirst = """{"kind":"history","sessionId":"session-l","messages":[{"role":"user","content":"问题"}],"entryIds":["m1"],"latestEntryId":"lab-2","oldestEntryId":"m1","hasMore":false}"""
            projection.acceptFrame(frameFirst, GatewayWireDecoder.decode(frameFirst), "session-l")
            assertTrue("mark 前的短暂窗口内仍误报（当前实现的诚实边界）", projection.isBehindLatest("session-l"))
            projection.markNonRenderingLeaf("session-l", "lab-2")
            assertFalse("mark 后收敛为不落后，顺序无关", projection.isBehindLatest("session-l"))
        } finally {
            projection.close()
        }
    }

    @Test
    fun assistantChunkBypassesLegacyFullConversationProjection() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val normalizer = assistantNormalizer("session-a")
        val chunk = live(
            normalizer, "session-a", 2, 101.0,
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"增量","contentIndex":0}}"""
        )

        // rawJson 只供旧 MobileStore 使用；chunk 应完全由归一后的增量事件消费。
        projection.acceptFrame("not-json", chunk.frame, "session-a", chunk.events)

        assertEquals("增量", projection.snapshot().conversation.single().text)
        assertEquals(null, projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun burstOfTinyChunksKeepsExactTextWithoutFallingBackToFullReprojection() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val normalizer = assistantNormalizer("session-a")
        val expected = buildString {
            repeat(1_200) { index ->
                val delta = ('a'.code + index % 26).toChar().toString()
                append(delta)
                val record = live(
                    normalizer, "session-a", index + 2, (index + 2).toDouble(),
                    """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"$delta","contentIndex":0}}"""
                )
                projection.acceptFrame(record.raw, record.frame, "session-a", record.events)
            }
        }
        val finalRecord = live(
            normalizer, "session-a", 1_202, 1_202.0,
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$expected"}]}}"""
        )
        projection.acceptFrame(finalRecord.raw, finalRecord.frame, "session-a", finalRecord.events)

        assertEquals(expected, projection.snapshot().conversation.single().text)
        assertTrue(projection.snapshot().conversation.none { it.id.startsWith("stream-") })
        projection.close()
    }

    @Test
    fun newSessionSentResponseBindsLiveConversationWithoutLeavingTheScreen() {
        val projection = AndroidGatewayProjection()
        projection.selectSession(null)

        val sent = """{"kind":"sent","sessionId":"session-new","mode":"followUp"}"""
        projection.acceptFrame(sent, GatewayWireDecoder.decode(sent), "session-new")
        assertEquals("session-new", projection.snapshot().selectedSessionId)

        val normalizer = PiEventNormalizer("session-new")
        val user = live(
            normalizer, "session-new", 1, 100.0,
            """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"123"}]}}"""
        )
        projection.acceptFrame(user.raw, user.frame, "session-new", user.events)
        val assistant = live(
            normalizer, "session-new", 2, 101.0,
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"reply"}]}}"""
        )
        projection.acceptFrame(assistant.raw, assistant.frame, "session-new", assistant.events)

        assertEquals(listOf("123", "reply"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    /** 回执先到：后续空历史页仍应保留已收到的实时图片消息。 */
    @Test
    fun sentBeforeUserImageEchoKeepsLiveAcrossEmptyHistoryBootstrap() {
        val projection = AndroidGatewayProjection()
        projection.selectSession(null)

        val sent = """{"kind":"sent","sessionId":"session-new","mode":"followUp"}"""
        projection.acceptFrame(sent, GatewayWireDecoder.decode(sent), "session-new")
        assertEquals("session-new", projection.snapshot().selectedSessionId)

        val normalizer = PiEventNormalizer("session-new")
        val user = live(
            normalizer, "session-new", 1, 100.0,
            """{"type":"message_end","message":{"role":"user","content":[{"type":"image","mimeType":"image/png","attachmentId":"att-1","dataOmitted":true,"dataBytes":2048}]}}"""
        )
        projection.acceptFrame(user.raw, user.frame, "session-new", user.events)
        assertEquals(1, projection.snapshot().conversation.size)

        val emptyPage = historyFrame("session-new", emptyList(), emptyList(), hasMore = false)
        projection.acceptFrame(emptyPage, GatewayWireDecoder.decode(emptyPage), "session-new")
        assertEquals(null, projection.snapshot().selectedHistoryError)
        val afterEmptyPage = projection.snapshot().conversation
        assertEquals(1, afterEmptyPage.size)
        assertEquals(ConversationItemKind.USER, afterEmptyPage.single().kind)
        assertEquals(1, afterEmptyPage.single().images.size)
        projection.close()
    }

    /** 实时事件先到：首次引导保留气泡，重复回执不重拉，空页或重叠页均不丢消息。 */
    @Test
    fun newSessionUserImageEchoBeforeSentSurvivesSentTriggeredHistoryRebuild() {
        for ((userContent, userText) in listOf(
            """[{"type":"image","mimeType":"image/png","attachmentId":"att-1","dataOmitted":true,"dataBytes":2048}]""" to "",
            """[{"type":"text","text":"看图"},{"type":"image","mimeType":"image/png","attachmentId":"att-1","dataOmitted":true,"dataBytes":2048}]""" to "看图"
        )) {
            for (overlapsLive in listOf(false, true)) {
                val requested = mutableListOf<Pair<String, String?>>()
                val projection = AndroidGatewayProjection(
                    onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
                )
                projection.selectSession(null)

                // 模拟服务端先发用户事件、后发接受回执。live message_end 帧顶层的
                // entryId 与下方 history 重叠页平行同源（PROTOCOL §4.5/§4.6）：新旧
                // 契约都不能按内容猜同消息，重叠靠准确 entryId 对消。
                val normalizer = PiEventNormalizer("session-new")
                val user = live(
                    normalizer, "session-new", 1, 100.0,
                    """{"type":"message_end","message":{"role":"user","content":$userContent}}""",
                    entryId = "e1"
                )
                projection.acceptFrame(user.raw, user.frame, "session-new", user.events)

                val sent = """{"kind":"sent","sessionId":"session-new","mode":"followUp"}"""
                projection.acceptFrame(sent, GatewayWireDecoder.decode(sent), "session-new")
                assertEquals("session-new", projection.snapshot().selectedSessionId)
                assertEquals("sent 首次引导只发起一次 history 请求", listOf("session-new" to null), requested)

                // sent 后尚无 assistant 回复：用户气泡（含图像）必须已在场且恰好一条。
                val afterSent = projection.snapshot().conversation
                assertEquals(1, afterSent.size)
                assertEquals(ConversationItemKind.USER, afterSent.single().kind)
                assertEquals(1, afterSent.single().images.size)
                assertEquals("att-1", afterSent.single().images.single().attachmentId)
                assertEquals(userText, afterSent.single().text)

                // 后续 sent（followUp）不得再次补拉 history。
                projection.acceptFrame(sent, GatewayWireDecoder.decode(sent), "session-new")
                assertEquals("重复 sent 不得重新补拉", listOf("session-new" to null), requested)

                // history 应答页：空页（尚未落盘）或与 live 气泡重叠的页，均不丢、不重复。
                val page = if (overlapsLive) {
                    historyFrame(
                        "session-new",
                        listOf("""{"role":"user","content":$userContent}"""),
                        entryIds = listOf("e1"),
                        hasMore = false
                    )
                } else {
                    historyFrame("session-new", emptyList(), emptyList(), hasMore = false)
                }
                projection.acceptFrame(page, GatewayWireDecoder.decode(page), "session-new")
                assertEquals(null, projection.snapshot().selectedHistoryError)
                val afterPage = projection.snapshot().conversation
                assertEquals(1, afterPage.size)
                assertEquals(ConversationItemKind.USER, afterPage.single().kind)
                assertEquals(1, afterPage.single().images.size)
                assertEquals("att-1", afterPage.single().images.single().attachmentId)
                assertEquals(userText, afterPage.single().text)
                projection.close()
            }
        }
    }

    @Test
    fun historyUserMessageCarriesImageAttachmentIntoConversationItem() {
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { _, _, _ -> }
        )
        projection.selectSession("session-img")

        val history = historyFrame(
            "session-img",
            messages = listOf(
                """{"role":"user","content":[
                    {"type":"text","text":"看图"},
                    {"type":"image","mimeType":"image/png","attachmentId":"img-xyz",
                     "dataOmitted":true,"dataBytes":2048}]}"""
            ),
            entryIds = listOf("e-img"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-img")
        val item = projection.snapshot().conversation.single()
        assertEquals("看图", item.text)
        val images = item.images
        assertEquals(1, images.size)
        assertEquals("img-xyz", images.single().attachmentId)
        assertEquals("image/png", images.single().mediaType)
        assertEquals(2048, images.single().bytes)
        // §4.22：history 侧 entryId+序号随图带出，供按需取回寻址。
        assertEquals("e-img", images.single().entryId)
        assertEquals(0, images.single().imageIndex)
        projection.close()
    }

    @Test
    fun historyImageWithoutAttachmentIdDerivesStableIdFromEntryAndIndex() {
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { _, _, _ -> }
        )
        projection.selectSession("session-img")

        // 旧会话历史无 attachmentId（网关剥图占位）：fallback id 按 entryId+序号
        // 稳定派生，不同条目不互撞；entryId/imageIndex 随块带出供 §4.22 取图。
        val history = historyFrame(
            "session-img",
            messages = listOf(
                """{"role":"user","content":[
                    {"type":"text","text":"两张"},
                    {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64},
                    {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}""",
                """{"role":"user","content":[
                    {"type":"text","text":"单张"},
                    {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}"""
            ),
            entryIds = listOf("e-a", "e-b"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-img")
        val first = projection.snapshot().conversation[0].images
        assertEquals(listOf("hist-e-a-0", "hist-e-a-1"), first.map { it.attachmentId })
        assertEquals(listOf(0, 1), first.map { it.imageIndex })
        val second = projection.snapshot().conversation[1].images
        assertEquals(listOf("hist-e-b-0"), second.map { it.attachmentId })
        assertEquals("e-b", second.single().entryId)
        projection.close()
    }

    @Test
    fun correlatedHistoryCannotWriteIntoNewSelectionAndLivePatchesRemainIncremental() {
        val requested = mutableListOf<Pair<String, String?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        projection.selectSession("session-b")
        assertEquals(listOf("session-a" to null, "session-b" to null), requested)

        // a 的 history 应答与当前选中的 b 无关：不得写入 b 的视图，也不触发额外请求。
        val history = historyFrame(
            "session-a",
            messages = listOf(userMessage("history-a")),
            entryIds = listOf("e1"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertEquals(listOf("session-a" to null, "session-b" to null), requested)

        // live 事件按会话缓冲，不进入当前选中的 b。
        val normalizer = PiEventNormalizer("session-a")
        val liveUser = live(
            normalizer, "session-a", 1, 100.0,
            """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"live"}]}}"""
        )
        projection.acceptFrame(liveUser.raw, liveUser.frame, "session-a", liveUser.events)
        assertTrue(projection.snapshot().conversation.isEmpty())

        // 切回 a 按 pi 语义清空重建：应答最新一页后展示 a 的内容。
        projection.selectSession("session-a")
        assertEquals("session-a" to null, requested.last())
        val refresh = historyFrame(
            "session-a",
            messages = listOf(userMessage("history-a"), assistantMessage("final")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(refresh, GatewayWireDecoder.decode(refresh), "session-a")
        assertEquals(listOf("history-a", "final"), projection.snapshot().conversation.map { it.text })

        val hello = """{"kind":"hello","authenticated":true}"""
        projection.acceptFrame(hello, GatewayWireDecoder.decode(hello), null)
        assertEquals(listOf("history-a", "final"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    @Test
    fun rejectedHistorySurfacesFailureAndRetryClearsIt() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        projection.historyRejected("session-a", "history-cursor-invalid")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertEquals("history-cursor-invalid", projection.snapshot().selectedHistoryError)
        projection.loadHistory("session-a", older = false)
        assertEquals(null, projection.snapshot().selectedHistoryError)
        val page = historyFrame("session-a", listOf(userMessage("recovered")), listOf("e1"), false)
        projection.acceptFrame(page, GatewayWireDecoder.decode(page), "session-a")
        assertEquals(listOf("recovered"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    @Test
    fun historyCancellationEndsLoadingSoSelectionCanRequestAgain() {
        val requested = mutableListOf<Pair<String, String?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertFalse(projection.snapshot().selectedHistoryIsLoadingOlder)
        projection.historyCancelled("session-a")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        projection.selectSession("session-a")
        assertEquals(listOf("session-a" to null, "session-a" to null), requested)
        projection.close()
    }

    @Test
    fun historyMessagesWithPiMetadataDecodeInsteadOfBeingDropped() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        // pi 的 message 条目带 usage/stopReason/model/timestamp 等渲染外字段（网关原样
        // 透传）；严格解码会整条丢弃，造成 entryIds 与 messages 数量错开。
        val history = historyFrame(
            "session-a",
            messages = listOf(
                """{"role":"assistant","content":[{"type":"text","text":"reply"}],""" +
                    """"usage":{"input":1,"output":2,"totalTokens":3},"stopReason":"stop",""" +
                    """"model":"m","provider":"p","customMeta":{"nested":1},"timestamp":1757539200000}"""
            ),
            entryIds = listOf("e1"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")

        assertEquals(listOf("reply"), projection.snapshot().conversation.map { it.text })
        assertEquals(null, projection.snapshot().selectedHistoryError)
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        // 耗时计算依赖历史项的时间戳：pi 的 timestamp 是 epoch 毫秒，必须归一到秒，
        // 否则历史项停留在 0（时间未知），与实时事件混在一组时会出现荒谬耗时。
        assertEquals(1757539200.0, projection.snapshot().conversation.first().epochSeconds, 0.001)
        projection.close()
    }

    @Test
    fun historyAssistantErrorWithEmptyContentRestoresVisibleError() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val history = historyFrame(
            "session-a",
            messages = listOf(
                """{"role":"assistant","content":[],"stopReason":"error","errorMessage":"上游 502"}"""
            ),
            entryIds = listOf("e-error"),
            hasMore = false
        )

        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")

        val error = projection.snapshot().conversation.single()
        assertEquals(ConversationItemKind.SYSTEM, error.kind)
        assertTrue(error.isError)
        assertEquals("上游 502", error.text)
        projection.close()
    }

    @Test
    fun historyAssistantErrorKeepsPartialTextAndErrorRow() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val history = historyFrame(
            "session-a",
            messages = listOf(
                """{"role":"assistant","content":[{"type":"text","text":"部分结果"}],"stopReason":"error","errorMessage":"连接断开"}"""
            ),
            entryIds = listOf("e-error"),
            hasMore = false
        )

        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")

        assertEquals(listOf("部分结果", "连接断开"), projection.snapshot().conversation.map { it.text })
        assertTrue(projection.snapshot().conversation.last().isError)
        projection.close()
    }

    @Test
    fun historyWithoutTimestampStaysUnknownInsteadOfInventingEpochZero() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val history = historyFrame(
            "session-a",
            messages = listOf(userMessage("无时间戳的历史")),
            entryIds = listOf("e1"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        // 0 = 时间未知（旧网关或不带 timestamp 的合成消息）；下游按未知处理，不做差值。
        assertEquals(0.0, projection.snapshot().conversation.first().epochSeconds, 0.001)
        projection.close()
    }


    @Test
    fun historyHiddenCustomMessageDoesNotRenderAsUpstreamError() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        // 通用隐藏custom_message不显示，不依赖任何私有类型。
        val history = historyFrame(
            "session-a",
            messages = listOf(
                """{"role":"custom","entryType":"custom_message","customType":"notice","content":"继续","display":false}"""
            ),
            entryIds = listOf("e3"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        assertEquals(0, projection.snapshot().conversation.size)
        projection.close()
    }

    @Test
    fun historyCompactionEntryRestoresCompactDoneRow() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        // 历史重建：compaction entry 经网关变成 role=custom/customType=compaction 的 wire
        // message（details.tokensBefore）；还原为与实时侧一致的 compact/done 行。
        val history = historyFrame(
            "session-a",
            messages = listOf(
                """{"role":"custom","entryType":"compaction","customType":"compaction","content":"摘要...","display":true,"details":{"tokensBefore":118000,"firstKeptEntryId":"e9"}}"""
            ),
            entryIds = listOf("e20"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-a")
        val done = projection.snapshot().conversation.single()
        assertEquals(ConversationItemKind.SYSTEM, done.kind)
        assertEquals("上下文已压缩", done.title)
        assertEquals("压缩前 118k tokens", done.text)
        projection.close()
    }

    @Test
    fun liveTextDeltasGrowTheStreamingAssistantItem() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val normalizer = assistantNormalizer("session-a")
        var seq = 2
        fun feed(eventJson: String) {
            val frame = live(normalizer, "session-a", seq, 100.0 + seq, eventJson)
            seq += 1
            projection.acceptFrame(frame.raw, frame.frame, "session-a", frame.events)
        }
        feed("""{"type":"agent_start"}""")
        feed("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        val words = listOf("流", "式", "输", "出", "测", "试")
        words.forEachIndexed { index, word ->
            feed("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"$word"}}""")
            val tail = projection.snapshot().conversation.last()
            assertTrue("delta $index 后应有 streaming item，实际 ${tail.id}", tail.id.startsWith("stream-"))
            assertEquals(ConversationItemKind.ASSISTANT, tail.kind)
            val expected = words.take(index + 1).joinToString("")
            assertEquals(expected, tail.text)
        }
        // message_end 定稿：streaming item 被最终文本替换，stream id 退出。
        feed(
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"流式输出测试"}]}}"""
        )
        val finalized = projection.snapshot().conversation.last()
        assertEquals("流式输出测试", finalized.text)
        assertFalse(finalized.id.startsWith("stream-"))
        // 定稿后新 turn 的 chunk 不再被旧 key 挡住。
        feed("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        feed("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"二"}}""")
        val second = projection.snapshot().conversation.last()
        assertTrue("second turn 应有 streaming item: ${second.id}/${second.text}", second.id.startsWith("stream-") && second.text == "二")
        projection.close()
    }

    @Test
    fun failedHistoryPageCancelsLoadingAndSurfacesRetryableError() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val mismatch = historyFrame(
            "session-a",
            messages = listOf(userMessage("only-one")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(mismatch, GatewayWireDecoder.decode(mismatch), "session-a")

        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertEquals("history-entry-mismatch", projection.snapshot().selectedHistoryError)

        // 重试先清失败态，正常应答后内容恢复。
        projection.selectSession("session-a")
        assertEquals(null, projection.snapshot().selectedHistoryError)
        val retry = historyFrame(
            "session-a",
            messages = listOf(userMessage("recovered")),
            entryIds = listOf("e1"),
            hasMore = false
        )
        projection.acceptFrame(retry, GatewayWireDecoder.decode(retry), "session-a")
        assertEquals(listOf("recovered"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    @Test
    fun reconnectHistoryBackfillsMissedUserMessageBeforeLiveAssistantReply() {
        val requested = mutableListOf<Pair<String, String?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        val emptyBaseline = historyFrame("session-a", emptyList(), emptyList(), hasMore = false)
        projection.acceptFrame(emptyBaseline, GatewayWireDecoder.decode(emptyBaseline), "session-a")

        // 断线重连后只有更晚的 assistant 事件先到；catch-up 清空缓冲并按 history 重建，
        // 因此离线期间漏掉的用户消息必须由 history 补齐，且不产生重复回复。
        val normalizer = PiEventNormalizer("session-a")
        val liveAssistant = live(
            normalizer, "session-a", 3, 103.0,
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Agent 回复"}]}}"""
        )
        projection.acceptFrame(liveAssistant.raw, liveAssistant.frame, "session-a", liveAssistant.events)
        assertEquals(listOf("Agent 回复"), projection.snapshot().conversation.map { it.text })

        projection.catchUpSelectedHistoryAfterReconnect()
        val catchUpHistory = historyFrame(
            "session-a",
            messages = listOf(userMessage("后台期间的问题"), assistantMessage("Agent 回复")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(catchUpHistory, GatewayWireDecoder.decode(catchUpHistory), "session-a")

        assertEquals(
            listOf("后台期间的问题", "Agent 回复"),
            projection.snapshot().conversation.map { it.text }
        )
        assertEquals(listOf("session-a" to null, "session-a" to null), requested)
        projection.close()
    }

    /** turn/end 的 preserveLive 重拉：请求在途时不清屏；新页整窗替换旧的最新窗口，
     *  实时尾部（本轮已达的消息）保住且与页内同 entryId 副本只留一条。 */
    @Test
    fun preserveLiveReloadKeepsViewInFlightAndReplacesStaleWindow() {
        val requested = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requested += Triple(session, cursor, reset) }
        )
        projection.selectSession("session-a")
        val staleWindow = historyFrame(
            "session-a",
            messages = listOf(userMessage("一问"), assistantMessage("二答")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(staleWindow, GatewayWireDecoder.decode(staleWindow), "session-a")
        val normalizer = PiEventNormalizer("session-a")
        val liveTurn = live(
            normalizer, "session-a", 5, 105.0,
            """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"四问"}]}}""",
            entryId = "e4"
        )
        projection.acceptFrame(liveTurn.raw, liveTurn.frame, "session-a", liveTurn.events)
        assertEquals(listOf("一问", "二答", "四问"), projection.snapshot().conversation.map { it.text })
        // 重拉前已可见条目的 item id（历史部分由合成负 seq 派生，重拉会整窗重新分配）。
        val idsBeforeReload = projection.snapshot().conversation.map { it.id }
        val liveItemId = idsBeforeReload.last()

        projection.loadHistory("session-a", older = false, preserveLive = true)
        // 关键：重拉在途期间不得把正在看的对话抹成空/加载态（旧实现先清缓冲）。
        assertEquals(listOf("一问", "二答", "四问"), projection.snapshot().conversation.map { it.text })
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(Triple("session-a", null, false), requested.last())

        // 落盘后的最新页：e3 终于进了文件，e4 与实时副本同源。
        val freshWindow = historyFrame(
            "session-a",
            messages = listOf(
                userMessage("一问"), assistantMessage("二答"), userMessage("三问"), userMessage("四问")
            ),
            entryIds = listOf("e1", "e2", "e3", "e4"),
            hasMore = false
        )
        projection.acceptFrame(freshWindow, GatewayWireDecoder.decode(freshWindow), "session-a")

        assertEquals(null, projection.snapshot().selectedHistoryError)
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        // 补回的 e3 落在它本来的位置（不整窗替换时它的合成 seq 比旧窗口更负，会排到
        // 整段对话之前），实时 e4 保留原 item id。
        assertEquals(listOf("一问", "二答", "三问", "四问"), projection.snapshot().conversation.map { it.text })
        assertEquals(1, projection.snapshot().conversation.count { it.text == "四问" })
        val afterReload = projection.snapshot().conversation
        // 身份不得漂移：重拉前已可见的三条 id 原样保留，只多出补回的 e3。
        // id 变了会连带丢滚动位置（LazyColumn key）与过程组折叠态（remember(id)）。
        assertEquals(idsBeforeReload, afterReload.filter { it.id in idsBeforeReload }.map { it.id })
        assertEquals(4, afterReload.map { it.id }.distinct().size)
        assertEquals(liveItemId, afterReload.last().id)
        projection.close()
    }

    /** 无实时记录的会话做 preserveLive 重拉：整窗替换后新窗口尾号允许低于旧窗口，
     *  否则适配器会把合法重拉判成 seq 越界 → 错误地 “history-adapter-failed” 并永久毒化。 */
    @Test
    fun preserveLiveReloadWithoutLiveTailAcceptsLowerWindowTail() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("session-a")
        val firstWindow = historyFrame(
            "session-a",
            messages = listOf(userMessage("一"), assistantMessage("二"), userMessage("三")),
            entryIds = listOf("e1", "e2", "e3"),
            hasMore = false
        )
        projection.acceptFrame(firstWindow, GatewayWireDecoder.decode(firstWindow), "session-a")
        assertEquals(3, projection.snapshot().conversation.size)

        projection.loadHistory("session-a", older = false, preserveLive = true)
        // 5 条消息、最后一条无正文（归一后不占记录）→ 本页可用尾号比旧窗口更低。
        val freshWindow = historyFrame(
            "session-a",
            messages = listOf(
                assistantMessage("零"), userMessage("一"), assistantMessage("二"),
                userMessage("三"), """{"role":"user","content":[]}"""
            ),
            entryIds = listOf("e0", "e1", "e2", "e3", "e4"),
            hasMore = false
        )
        projection.acceptFrame(freshWindow, GatewayWireDecoder.decode(freshWindow), "session-a")

        assertEquals(null, projection.snapshot().selectedHistoryError)
        assertEquals(listOf("零", "一", "二", "三"), projection.snapshot().conversation.map { it.text })
        projection.close()
    }

    /** 同一会话已有批次在途时的“取最新页”：不清缓冲、不发注定被 reducer 拒掉的
     *  重复请求，当前批次落地后补发（旧实现：清完缓冲才发现被拒 → 会话停在旧窗口）。 */
    @Test
    fun latestReloadWhileBatchInFlightIsDeferredThenRefetched() {
        val requested = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requested += Triple(session, cursor, reset) }
        )
        projection.selectSession("session-a")
        val firstWindow = historyFrame(
            "session-a",
            messages = listOf(userMessage("一"), assistantMessage("二")),
            entryIds = listOf("e2", "e3"),
            hasMore = true
        )
        projection.acceptFrame(firstWindow, GatewayWireDecoder.decode(firstWindow), "session-a")

        projection.loadHistory("session-a", older = true)   // 翻旧页在途
        projection.loadHistory("session-a", older = false)   // branch-switched / 手动重载
        assertEquals(2, requested.size)
        // 关键：已显示的内容不会在请求真正发出前被清掉。
        assertEquals(2, projection.snapshot().conversation.size)

        val olderPage = historyFrame(
            "session-a",
            messages = listOf(userMessage("更早")),
            entryIds = listOf("e1"),
            hasMore = false
        )
        projection.acceptFrame(olderPage, GatewayWireDecoder.decode(olderPage), "session-a")

        assertEquals(3, requested.size)
        assertEquals(Triple("session-a", null, false), requested.last())
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        projection.close()
    }

    /** 重连补拉撞上在途批次：同样归并为待取意图，批次落地后按 resetLeaf 补发。 */
    @Test
    fun catchUpWhileBatchInFlightDefersAndKeepsResetLeaf() {
        val requested = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requested += Triple(session, cursor, reset) }
        )
        projection.selectSession("session-a")
        val firstWindow = historyFrame(
            "session-a",
            messages = listOf(userMessage("一")),
            entryIds = listOf("e2"),
            hasMore = true
        )
        projection.acceptFrame(firstWindow, GatewayWireDecoder.decode(firstWindow), "session-a")
        projection.loadHistory("session-a", older = true)
        assertEquals(2, requested.size)

        projection.catchUpSelectedHistoryAfterReconnect()
        assertEquals(2, requested.size)
        assertEquals(1, projection.snapshot().conversation.size)

        val olderPage = historyFrame(
            "session-a",
            messages = listOf(userMessage("更早")),
            entryIds = listOf("e1"),
            hasMore = false
        )
        projection.acceptFrame(olderPage, GatewayWireDecoder.decode(olderPage), "session-a")
        assertEquals(3, requested.size)
        assertEquals(Triple("session-a", null, true), requested.last())
        projection.close()
    }

    /** 在途批次失败时也要把待取的“最新页”补出去：否则会话停在“无在途、无新页”
     *  的旧窗口，只能用户手动重进。 */
    @Test
    fun deferredLatestReloadIsFlushedWhenInFlightBatchFails() {
        val requested = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requested += Triple(session, cursor, reset) }
        )
        projection.selectSession("session-a")
        val firstWindow = historyFrame(
            "session-a",
            messages = listOf(userMessage("一")),
            entryIds = listOf("e2"),
            hasMore = true
        )
        projection.acceptFrame(firstWindow, GatewayWireDecoder.decode(firstWindow), "session-a")
        projection.loadHistory("session-a", older = true)   // 翻旧页在途
        projection.loadHistory("session-a", older = false, preserveLive = true)   // turn/end → 归并
        assertEquals(2, requested.size)

        // 在途的旧页应答是半页（entryIds 与 messages 不同长）→ 批次失败。
        val broken = historyFrame(
            "session-a",
            messages = listOf(userMessage("更早")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(broken, GatewayWireDecoder.decode(broken), "session-a")

        // 补发新一页：会话级失败标记随新一轮加载作废（再失败会重新记录）。
        assertEquals(null, projection.snapshot().selectedHistoryError)
        assertEquals(3, requested.size)
        assertEquals(Triple("session-a", null, false), requested.last())
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(1, projection.snapshot().conversation.size)
        projection.close()
    }

    /** preserveLive 重拉失败：旧视图仍在（不再“只剩错误卡”），失败码照常记录，
     *  且在途标记不会死转。 */
    @Test
    fun failedPreserveLiveReloadKeepsPreviousWindowAndRecordsError() {
        val requested = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requested += Triple(session, cursor, reset) }
        )
        projection.selectSession("session-a")
        val window = historyFrame(
            "session-a",
            messages = listOf(userMessage("一"), assistantMessage("二")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(window, GatewayWireDecoder.decode(window), "session-a")

        projection.loadHistory("session-a", older = false, preserveLive = true)
        val broken = historyFrame(
            "session-a",
            messages = listOf(userMessage("半页")),
            entryIds = listOf("e1", "e2"),
            hasMore = false
        )
        projection.acceptFrame(broken, GatewayWireDecoder.decode(broken), "session-a")

        assertEquals("history-entry-mismatch", projection.snapshot().selectedHistoryError)
        assertEquals(2, projection.snapshot().conversation.size)
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(2, requested.size)
        projection.close()
    }

    /**
     * Finding 1：preserveLive 重拉失败后必须还能继往翻旧页。旧实现先在 start 里把
     * hasMore/nextCursor 抹回初始值（latest 路径把 hasLocalEvents 当 false 传），
     * 重拉一失败，翻页游标就永久丢了（holder 的 loadOlderHistory 以游标为闸门）。
     */
    @Test
    fun failedPreserveLiveReloadKeepsOlderCursor() {
        val requested = mutableListOf<Triple<String, String?, Boolean>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { session, cursor, reset -> requested += Triple(session, cursor, reset) }
        )
        projection.selectSession("session-a")
        val latestPage = historyFrame(
            "session-a", (21..40).map { userMessage("消息$it") }, (21..40).map { "e$it" }, hasMore = true
        )
        projection.acceptFrame(latestPage, GatewayWireDecoder.decode(latestPage), "session-a")
        projection.loadHistory("session-a", older = true)
        val olderPage = historyFrame(
            "session-a", (11..20).map { userMessage("消息$it") }, (11..20).map { "e$it" }, hasMore = true
        )
        projection.acceptFrame(olderPage, GatewayWireDecoder.decode(olderPage), "session-a")
        assertEquals("e11", projection.snapshot().selectedHistoryNextCursor)

        // turn/end 触发的 preserveLive 重拉：请求本身不带游标。
        projection.loadHistory("session-a", older = false, preserveLive = true)
        assertEquals(Triple("session-a", null, false), requested.last())
        val broken = historyFrame(
            "session-a", listOf(userMessage("半页")), listOf("e1", "e2"), hasMore = false
        )
        projection.acceptFrame(broken, GatewayWireDecoder.decode(broken), "session-a")

        // 旧窗口保留、错误照常上报，翻页入口（hasMore + 游标）不得被抹掉。
        assertEquals("history-entry-mismatch", projection.snapshot().selectedHistoryError)
        assertEquals(30, projection.snapshot().conversation.size)
        assertTrue(projection.snapshot().selectedHistoryHasMore)
        assertEquals("e11", projection.snapshot().selectedHistoryNextCursor)

        // 失败后仍能继续翻旧页（旧行为：reducer 早退，一个请求也发不出去）。
        val requestsBefore = requested.size
        projection.loadHistory("session-a", older = true)
        assertEquals(requestsBefore + 1, requested.size)
        assertEquals(Triple("session-a", "e11", false), requested.last())
        projection.close()
    }

    @Test
    fun initialHistoryStopsAfterOneBatchAndLeavesOlderPagesForUserScroll() {
        val requested = mutableListOf<Pair<String, String?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        projection.selectSession("session-a")
        assertTrue(projection.snapshot().selectedHistoryIsLoading)
        assertEquals(0, projection.snapshot().selectedHistoryLoadedEventCount)
        assertEquals(null, projection.snapshot().selectedHistoryTotalEventCount)

        // 每批一页：第一页 hasMore=true 仍会停下，更旧的页留给用户滚动触发。
        val firstPage = historyFrame(
            "session-a",
            messages = listOf(userMessage("history-2")),
            entryIds = listOf("e2"),
            hasMore = true
        )
        projection.acceptFrame(firstPage, GatewayWireDecoder.decode(firstPage), "session-a")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertTrue(projection.snapshot().selectedHistoryHasMore)
        assertEquals(-1, projection.snapshot().selectedHistoryEarliestSequence)
        assertEquals("session-a" to null, requested.last())

        projection.loadHistory("session-a", older = true)
        assertTrue(projection.snapshot().selectedHistoryIsLoadingOlder)
        assertEquals("session-a" to "e2", requested.last())
        val finalPage = historyFrame("session-a", emptyList(), emptyList(), hasMore = false)
        projection.acceptFrame(finalPage, GatewayWireDecoder.decode(finalPage), "session-a")
        assertFalse(projection.snapshot().selectedHistoryIsLoading)
        assertFalse(projection.snapshot().selectedHistoryHasMore)
        assertEquals(0, projection.snapshot().selectedHistoryLoadedEventCount)
        assertEquals(null, projection.snapshot().selectedHistoryTotalEventCount)
        projection.close()
    }

    @Test
    fun mviEnvelopeRejectsDuplicateGapWrongDomainAndStaysFailedClosed() {
        val valid = MviEnvelopeValidator("history")
        val snapshot = event(sequence = 7, transaction = "snapshot", kind = "snapshot")
        assertTrue(valid.validate(snapshot))
        valid.commit(snapshot)
        val next = event(sequence = 8, transaction = "next")
        assertTrue(valid.validate(next))
        valid.commit(next)
        assertFalse(valid.validate(event(sequence = 9, transaction = "next")))
        assertFalse(valid.validate(event(sequence = 10, transaction = "after-duplicate")))

        val gap = MviEnvelopeValidator("history")
        val gapSnapshot = event(sequence = 0, transaction = "snapshot", kind = "snapshot")
        assertTrue(gap.validate(gapSnapshot))
        gap.commit(gapSnapshot)
        assertFalse(gap.validate(event(sequence = 2, transaction = "gap")))

        val wrongDomain = MviEnvelopeValidator("history")
        assertFalse(
            wrongDomain.validate(
                event(sequence = 0, transaction = "wrong", kind = "snapshot", domain = "conversation")
            )
        )

        val badPatch = MviEnvelopeValidator("history")
        val badSnapshot = event(sequence = 0, transaction = "snapshot", kind = "snapshot")
        assertTrue(badPatch.validate(badSnapshot))
        badPatch.commit(badSnapshot)
        badPatch.reject()
        assertFalse(badPatch.validate(event(sequence = 1, transaction = "valid-after-bad-patch")))

        val bounded = MviEnvelopeValidator("history")
        repeat(100) { index ->
            val value = event(
                sequence = index.toLong(),
                transaction = "transaction-$index",
                kind = if (index == 0) "snapshot" else "transition"
            )
            assertTrue(bounded.validate(value))
            bounded.commit(value)
        }
        assertEquals(64, bounded.retainedTransactionCountForTest)
    }

    @Test
    fun malformedHistoryPayloadAndEffectCommitNeitherStateNorIoAndStayFailedClosed() {
        val requested = mutableListOf<Pair<String, String?>>()
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> requested += sessionId to before }
        )
        val before = projection.snapshot()
        projection.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "malformed:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","eventPatch":{"kind":"unknown"},"outcome":"request-page"}""",
                effectsJson =
                    """[{"action":"request-page","sessionId":"session-a","beforeEntryId":"e1"}]"""
            )
        )
        assertEquals(before.conversation, projection.snapshot().conversation)
        assertTrue(requested.isEmpty())
        assertEquals("history-adapter-failed", projection.snapshot().lastError)

        projection.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "valid-after-failure:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","outcome":"request-page"}""",
                effectsJson =
                    """[{"action":"request-page","sessionId":"session-a","beforeEntryId":"e1"}]"""
            )
        )
        assertTrue(requested.isEmpty())
        assertEquals("history-envelope-invalid", projection.snapshot().lastError)
        projection.close()

        val badEffectRequests = mutableListOf<Pair<String, String?>>()
        val badEffectProjection = AndroidGatewayProjection(
            onHistoryPageRequested = { sessionId, before, _ -> badEffectRequests += sessionId to before }
        )
        badEffectProjection.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "bad-effect:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","outcome":"request-page"}""",
                effectsJson =
                    """[{"action":"request-page","sessionId":"session-b","beforeEntryId":"e1"}]"""
            )
        )
        assertTrue(badEffectRequests.isEmpty())
        assertEquals("history-adapter-failed", badEffectProjection.snapshot().lastError)
        badEffectProjection.close()
    }

    @Test
    fun unknownConversationOperationCommitsNoPartialItemsAndClosesAdapter() {
        val projection = AndroidGatewayProjection()
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "bad-operation:1",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[{"kind":"move","itemId":"missing"}],"replacesAll":false,"lastSequence":1}"""
            )
        )
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertEquals("conversation-adapter-failed", projection.snapshot().lastError)
        projection.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "after-bad-operation:1",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":false,"lastSequence":1}"""
            )
        )
        assertEquals("conversation-envelope-invalid", projection.snapshot().lastError)
        projection.close()
    }

    @Test
    fun perSessionConversationAndHistorySequencesRejectRegressionAndDuplicateAppend() {
        val conversation = AndroidGatewayProjection()
        conversation.selectSession("session-a")
        // selectSession 的 clear 已占用 conversation 事务序号 1，基线从 2 开始。
        conversation.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "conversation-baseline:2",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[{"id":"one","kind":"USER","title":"You","text":"one","images":[],"isError":false,"epochSeconds":1.0}],"lastSequence":5}"""
            )
        )
        assertEquals("one", conversation.snapshot().conversation.firstOrNull()?.text)
        conversation.acceptConversationMviEventForTest(
            SharedMviEvent(
                sequence = 3,
                transactionId = "conversation-regression:3",
                domain = "conversation",
                kind = "transition",
                statePayloadJson =
                    """{"schema":1,"sessionId":"session-a","operations":[],"replacesAll":true,"replacementItems":[],"lastSequence":4}"""
            )
        )
        assertEquals("conversation-adapter-failed", conversation.snapshot().lastError)
        assertEquals("one", conversation.snapshot().conversation.firstOrNull()?.text)
        conversation.close()

        val history = AndroidGatewayProjection()
        history.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "history-append:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","eventPatch":{"kind":"append","record":{"sessionId":"session-a","seq":5,"time":1.0,"event":{"type":"user/message","text":"one"}},"index":0},"outcome":"none"}"""
            )
        )
        history.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "history-duplicate-append:2",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","eventPatch":{"kind":"append","record":{"sessionId":"session-a","seq":5,"time":2.0,"event":{"type":"user/message","text":"duplicate"}},"index":1},"outcome":"none"}"""
            )
        )
        assertEquals("history-adapter-failed", history.snapshot().lastError)
        history.close()

        val replaceRegression = AndroidGatewayProjection()
        replaceRegression.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 1,
                transactionId = "history-replace-baseline:1",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","eventPatch":{"kind":"replace","replacementEvents":[{"sessionId":"session-a","seq":5,"time":1.0,"event":{"type":"user/message","text":"newer"}}]},"outcome":"none"}"""
            )
        )
        replaceRegression.acceptHistoryMviEventForTest(
            SharedMviEvent(
                sequence = 2,
                transactionId = "history-replace-regression:2",
                domain = "history",
                kind = "transition",
                statePayloadJson =
                    """{"schema":2,"sessionId":"session-a","eventPatch":{"kind":"replace","replacementEvents":[{"sessionId":"session-a","seq":4,"time":2.0,"event":{"type":"user/message","text":"older"}}]},"outcome":"none"}"""
            )
        )
        assertEquals("history-adapter-failed", replaceRegression.snapshot().lastError)
        replaceRegression.close()
    }

    @Test
    fun historyParallelEntryIdsFlowIntoSessionEventsAndLiveUsesFrameTopLevelEntryId() {
        val projection = AndroidGatewayProjection(
            onHistoryPageRequested = { _, _, _ -> }
        )
        projection.selectSession("session-entry")

        // history：entryIds 与 messages 平行同源，每条消息的 entryId 要落在 SessionEvent 上。
        val history = historyFrame(
            "session-entry",
            messages = listOf(userMessage("早安"), assistantMessage("晚安")),
            entryIds = listOf("e-u1", "e-a1"),
            hasMore = false
        )
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "session-entry")
        // trajectory 会把同一条 assistant/message 同时放进 REQUEST 节点（请求收尾）
        // 与 ASSISTANT 节点（消息本体），flat 看会重复；身份断言按节点观察层分组：
        // INPUT 节点恰一条 user record（e-u1），ASSISTANT 节点恰一条 assistant
        // record（e-a1），不 distinct 蒙混。
        val trajectoryNodes = projection.trajectory("session-entry")
        assertEquals(
            listOf("e-u1"),
            trajectoryNodes.filter { it.kind == TrajectoryNodeKind.INPUT }.flatMap { it.records }.map { it.entryId }
        )
        assertEquals(
            listOf("e-a1"),
            trajectoryNodes.filter { it.kind == TrajectoryNodeKind.ASSISTANT }.flatMap { it.records }.map { it.entryId }
        )

        // live：entryId 只在帧顶层（PROTOCOL §4.5）；normalizer 的 helper 不带
        // 第五参 —— 用 production 链路的 GatewayWireDecoder+holder 同款调用
        // （normalize(seq,time,event,frame.entryId)）手工透传后 acceptFrame。
        val raw =
            """{"kind":"event","sessionId":"session-entry","seq":10,"time":110.0,"entryId":"e-live-1","event":{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"继续"}]}}}"""
        val frame = GatewayWireDecoder.decode(raw)
        val normalizer = PiEventNormalizer("session-entry")
        val events = normalizer.normalize(frame.seq ?: 0, frame.time ?: 0.0, requireNotNull(frame.event), frame.entryId)
        assertEquals("e-live-1", events.single().entryId)
        projection.acceptFrame(raw, frame, "session-entry", events)
        val liveRecords = projection.trajectory("session-entry").filter { it.kind == TrajectoryNodeKind.INPUT }.flatMap { it.records }
        assertEquals(listOf("e-live-1"), liveRecords.filter { it.seq == 10 }.map { it.entryId })
        projection.close()
    }

    private fun event(
        sequence: Long,
        transaction: String,
        kind: String = "transition",
        domain: String = "history"
    ) = SharedMviEvent(
        sequence = sequence,
        transactionId = transaction,
        domain = domain,
        kind = kind,
        statePayloadJson = "{}"
    )

    private data class LiveFrame(
        val raw: String,
        val frame: GatewayFrame,
        val events: List<SessionEvent>
    )

    private fun assistantNormalizer(sessionId: String): PiEventNormalizer =
        PiEventNormalizer(sessionId).also { normalizer ->
            normalizer.normalize(
                1, 100.0,
                piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
            )
        }

    private fun live(
        normalizer: PiEventNormalizer,
        sessionId: String,
        sequence: Int,
        time: Double,
        eventJson: String,
        entryId: String? = null
    ): LiveFrame {
        val entryIdField = entryId?.let { "\"entryId\":\"$it\"," } ?: ""
        val raw = """{"kind":"event","sessionId":"$sessionId","seq":$sequence,"time":$time,$entryIdField"event":$eventJson}"""
        val frame = GatewayWireDecoder.decode(raw)
        // 生产链路同款：帧顶层 entryId（PROTOCOL §4.5）随归一透传。
        val events = normalizer.normalize(sequence, time, requireNotNull(frame.event), frame.entryId)
        return LiveFrame(raw, frame, events)
    }

    private fun historyFrame(
        sessionId: String,
        messages: List<String>,
        entryIds: List<String>,
        hasMore: Boolean,
        bytes: Int = 64
    ): String {
        val entryIdsJson = entryIds.joinToString(",") { "\"$it\"" }
        val oldestEntryId = entryIds.firstOrNull()?.let { "\"$it\"" } ?: "null"
        return """{"kind":"history","sessionId":"$sessionId","messages":[${messages.joinToString(",")}],""" +
            """"entryIds":[$entryIdsJson],"hasMore":$hasMore,"oldestEntryId":$oldestEntryId,"bytes":$bytes}"""
    }

    private fun userMessage(text: String): String =
        """{"role":"user","content":[{"type":"text","text":"$text"}]}"""

    private fun assistantMessage(text: String): String =
        """{"role":"assistant","content":[{"type":"text","text":"$text"}]}"""

    private fun piEvent(json: String): JsonValue =
        JsonValue.fromJsonElement(Json.parseToJsonElement(json))
}
