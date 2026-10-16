package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pi 事件 JSON → 内部视图的归一契约（pi-mobile-gateway PROTOCOL.md §4.5）。
 * 归一器按会话持有状态，事件必须按到达顺序喂入。
 */
class PiEventNormalizerTest {
    private fun piEvent(json: String): JsonValue =
        JsonValue.fromJsonElement(wireJson.parseToJsonElement(json))

    @Test
    fun userPromptStartEndPairEmitsExactlyOneUserMessage() {
        val normalizer = PiEventNormalizer("s1")
        // pi 内核对每条用户 prompt 依次发 message_start 与 message_end（内容相同）；
        // 两者都归一会产生两条 user/message（seq 不同），聊天气泡重复显示。
        val start = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}""")
        )
        assertTrue(start.isEmpty())
        val end = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}""")
        )
        assertEquals(listOf("user/message"), end.map { it.event.type })
        assertEquals("hi", end.single().event.text)
    }


    @Test
    fun textThinkingAndToolcallDeltasCarryAssistantStreamTurns() {
        val normalizer = PiEventNormalizer("s1")
        // message_start(assistant) 只推进流计数，不产生可见事件。
        assertTrue(
            normalizer
                .normalize(1, 1.0, piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
                .isEmpty()
        )
        val text = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hel","contentIndex":0}}""")
        ).single()
        assertEquals("assistant/chunk", text.event.type)
        assertEquals("text-delta", text.event.chunkType)
        assertEquals("Hel", text.event.text)
        assertEquals(1, text.event.turn)
        assertEquals(0, text.event.step)

        val thinking = normalizer.normalize(
            3, 3.0,
            piEvent("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"推理","contentIndex":0}}""")
        ).single()
        assertEquals("reasoning-delta", thinking.event.chunkType)
        assertEquals("推理", thinking.event.text)
        assertEquals(1, thinking.event.turn)

        val toolcallStart = normalizer.normalize(
            4, 4.0,
            piEvent("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","id":"tc-1","toolName":"Bash","contentIndex":2}}""")
        ).single()
        assertEquals("tool-call-delta", toolcallStart.event.chunkType)
        assertEquals("tc-1", toolcallStart.event.tool?.id)
        assertEquals("Bash", toolcallStart.event.tool?.name)
        assertNull(toolcallStart.event.tool?.argumentsDelta)

        val toolcallDelta = normalizer.normalize(
            5, 5.0,
            piEvent("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","id":"tc-1","toolName":"Bash","delta":"{\"cmd\"","contentIndex":2}}""")
        ).single()
        assertEquals("tool-call-delta", toolcallDelta.event.chunkType)
        assertEquals("tc-1", toolcallDelta.event.tool?.id)
        assertEquals("{\"cmd\"", toolcallDelta.event.tool?.argumentsDelta)

        // 第二条 assistant 消息起泡后，合成 turn 递增。
        assertTrue(
            normalizer
                .normalize(6, 6.0, piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
                .isEmpty()
        )
        val nextTurn = normalizer.normalize(
            7, 7.0,
            piEvent("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"第二条","contentIndex":0}}""")
        ).single()
        assertEquals(2, nextTurn.event.turn)
    }

    @Test
    fun messageEndMapsUserAssistantAndToolResultRoles() {
        val normalizer = PiEventNormalizer("s1")
        val user = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"执行部署"}]}}""")
        ).single()
        assertEquals("user/message", user.event.type)
        assertEquals("执行部署", user.event.text)
        assertEquals("user", user.event.source)

        // assistant message_end 之前必须先喂 message_start(assistant)，合成 turn 由流计数给出。
        assertTrue(
            normalizer
                .normalize(0, 0.0, piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""))
                .isEmpty()
        )
        val assistant = normalizer.normalize(
            2, 2.0,
            piEvent(
                """{"type":"message_end","message":{"role":"assistant","content":[""" +
                    """{"type":"thinking","thinking":"先想一下"},""" +
                    """{"type":"text","text":"结论"},""" +
                    """{"type":"toolCall","toolCallId":"tc-1","toolName":"Bash","input":{"cmd":"ls"}}],""" +
                    """"usage":{"outputTokens":12}}}"""
            )
        ).single()
        assertEquals("assistant/message", assistant.event.type)
        assertEquals("结论", assistant.event.text)
        assertEquals("先想一下", assistant.event.reasoning)
        assertEquals(1, assistant.event.turn)
        val toolCall = assistant.event.toolCalls.orEmpty().single()
        assertEquals("tc-1", toolCall.id)
        assertEquals("Bash", toolCall.name)
        assertEquals(12, assistant.event.usage?.firstInteger(setOf("outputTokens")))

        val toolResult = normalizer.normalize(
            3, 3.0,
            piEvent("""{"type":"message_end","message":{"role":"toolResult","toolCallId":"tc-1","isError":false,"content":[{"type":"text","text":"/tmp"}]}}""")
        ).single()
        assertEquals("tool/result", toolResult.event.type)
        assertEquals("tc-1", toolResult.event.callId)
        assertEquals(false, toolResult.event.isError)
        assertEquals("/tmp", toolResult.event.preview)
    }

    @Test
    fun assistantErrorMessageRemainsVisibleWhenContentIsEmpty() {
        val normalizer = PiEventNormalizer("s1")
        normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        )
        val error = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"401 余额不足"}}""")
        ).single()

        assertEquals("assistant/message", error.event.type)
        assertEquals("", error.event.text)
        assertEquals(true, error.event.isError)
        assertEquals("401 余额不足", error.event.errorMessage)
    }

    @Test
    fun assistantErrorMessagePreservesPartialTextAndTruncatesHugeError() {
        val normalizer = PiEventNormalizer("s1")
        normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        )
        val errorText = "x".repeat(400)
        val error = normalizer.normalize(
            2, 2.0,
            piEvent(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"已输出一部分"}],"stopReason":"error","errorMessage":"$errorText"}}"""
            )
        ).single()

        assertEquals("已输出一部分", error.event.text)
        assertEquals(true, error.event.isError)
        assertEquals(240, error.event.errorMessage?.length)
    }

    @Test
    fun nativeCompactionFailureUsesErrorMessageInsteadOfNonPiStatusFields() {
        val normalizer = PiEventNormalizer("s1")
        val failed = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"compaction_end","reason":"overflow","aborted":false,"willRetry":false,"errorMessage":"summary failed"}""")
        )
        assertEquals(listOf("notice/compact"), failed.map { it.event.type })
        assertEquals("上下文压缩失败，请重试", failed.single().event.text)
        assertEquals(true, failed.single().event.isError)
    }

    @Test
    fun nativeEmptyCompactionHasASpecificSafeMessage() {
        for (error in listOf("Nothing to compact (session too small)", "Compaction failed: Nothing to compact (session too small)")) {
            val result = PiEventNormalizer("s1").normalize(
                1, 1.0,
                piEvent("""{"type":"compaction_end","errorMessage":"$error"}""")
            ).single()
            assertEquals("notice/compact", result.event.type)
            assertEquals("暂无可压缩内容", result.event.text)
            assertEquals(true, result.event.isError)
        }
    }

    @Test
    fun legacyCompactionRefusalsUseTheSameSafeMessages() {
        val cases = listOf(
            """"error":{"message":"Nothing to compact (session too small)"}""" to "暂无可压缩内容",
            """"error":"Already compacted"""" to "暂无需再次压缩",
            """"errorMessage":"Compaction failed: Already compacted"""" to "暂无需再次压缩",
            """"errorMessage":"secret endpoint Nothing to compact (session too small)"""" to "上下文压缩失败，请重试",
        )
        for ((field, expected) in cases) {
            val result = PiEventNormalizer("s1").normalize(
                1, 1.0, piEvent("""{"type":"compaction_end",$field}""")
            ).single()
            assertEquals(expected, result.event.text)
        }
    }

    @Test
    fun compactFailureAndCancellationHaveDistinctLocalOutcomes() {
        for ((fields, reason) in listOf(
            """"aborted":true""" to "aborted",
            """"status":"declined"""" to "declined",
            """"errorMessage":"synthetic failure"""" to "failed"
        )) {
            val record = PiEventNormalizer("s1").normalize(1, 1.0,
                piEvent("""{"type":"compaction_end",$fields}""")).single()
            assertEquals("notice/compact", record.event.type)
            assertEquals(reason, record.event.reason)
        }
    }

    @Test
    fun toolExecutionStartAndSteerUserMessageBindThroughOneSessionState() {
        val normalizer = PiEventNormalizer("s1")
        val call = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"tool_execution_start","toolCallId":"call-1","toolName":"Bash","args":{"cmd":"pwd"}}""")
        ).single()
        assertEquals("tool/call", call.event.type)
        assertEquals("call-1", call.event.callId)
        assertEquals("Bash", call.event.name)
        assertEquals("pwd", call.event.arguments?.get("cmd")?.stringValue)

        // 运行中注入的 steer/followUp 用户消息也走 message_end。
        val steer = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"追加要求"}]}}""")
        ).single()
        assertEquals("user/message", steer.event.type)
        assertEquals("追加要求", steer.event.text)

        // 空 user message 不产生事件。
        assertTrue(
            normalizer
                .normalize(3, 3.0, piEvent("""{"type":"message_end","message":{"role":"user","content":[]}}"""))
                .isEmpty()
        )
    }

    @Test
    fun toolNameEntryIsReleasedAfterResultAssociation() {
        val normalizer = PiEventNormalizer("s1")
        normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"tool_execution_start","toolCallId":"call-9","toolName":"Bash","args":{}}""")
        )
        // 首个结果：无 toolName 回退到 start 登记的名。
        val first = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"message_end","message":{"role":"toolResult","toolCallId":"call-9","content":[{"type":"text","text":"ok"}]}}""")
        ).single()
        assertEquals("tool/result", first.event.type)
        assertEquals("Bash", first.event.name)
        // 登记已释放：同 callId 再来结果（无 toolName）不再命中旧名。
        val second = normalizer.normalize(
            3, 3.0,
            piEvent("""{"type":"message_end","message":{"role":"toolResult","toolCallId":"call-9","content":[{"type":"text","text":"ok2"}]}}""")
        ).single()
        assertNull(second.event.name)
    }

    @Test
    fun turnBoundaryAndTitleEventsMapToInternalVocabulary() {
        val normalizer = PiEventNormalizer("s1")
        assertEquals(
            "turn/start",
            normalizer.normalize(1, 1.0, piEvent("""{"type":"agent_start"}""")).single().event.type
        )
        assertEquals(
            "turn/end",
            normalizer.normalize(2, 2.0, piEvent("""{"type":"agent_settled"}""")).single().event.type
        )
        val title = normalizer.normalize(
            3, 3.0,
            piEvent("""{"type":"session_info_changed","sessionInfo":{"name":"新标题"}}""")
        ).single()
        assertEquals("session/title", title.event.type)
        assertEquals("新标题", title.event.text)
    }

    @Test
    fun queueUpdateMapsToQueueUpdateWithBothQueues() {
        val normalizer = PiEventNormalizer("s1")
        val queued = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"queue_update","steering":["你可以看看"],"followUp":[]}""")
        ).single()
        assertEquals("queue/update", queued.event.type)
        assertEquals(listOf("你可以看看"), queued.event.steering)
        assertEquals(emptyList<String>(), queued.event.followUp)
        // 空队列（steer 已消费）：仍产出事件，驱动 UI 清吸附条。
        val empty = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"queue_update","steering":[],"followUp":[]}""")
        ).single()
        assertEquals("queue/update", empty.event.type)
        assertEquals(emptyList<String>(), empty.event.steering)
    }

    @Test
    fun unrecognizedEventsAndMissingFieldsProduceNoEvents() {
        val normalizer = PiEventNormalizer("s1")
        for (json in listOf(
            """{"type":"auto_retry_start","attempt":1}""",
            """{"type":"event_omitted","originalType":"message_update"}""",
            """{"type":"message_update","assistantMessageEvent":null}""",
            """{"noType":true}"""
        )) {
            assertTrue(normalizer.normalize(1, 1.0, piEvent(json)).isEmpty(), json)
        }
    }

    @Test
    fun everyNormalizedEventCarriesTheSessionOwnedByTheNormalizer() {
        val normalizer = PiEventNormalizer("session-owned")
        val events = normalizer.normalize(
            5, 5.0,
            piEvent("""{"type":"tool_execution_start","toolCallId":"c1","toolName":"Read","args":{}}""")
        )
        assertEquals(1, events.size)
        assertTrue(events.all { it.sessionId == "session-owned" && it.seq == 5 && it.time == 5.0 })
    }

    @Test
    fun stringContentUserMessagesAreNotDropped() {
        // pi 会话文件里 user 消息的 content 可能是裸字符串（fixture 与部分旧会话）；
        // textOf 只认块数组时整条消息会被丢掉。
        val normalizer = PiEventNormalizer("s1")
        val events = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"message_end","message":{"role":"user","content":"hello"}}""")
        )
        assertEquals("user/message", events.single().event.type)
        assertEquals("hello", events.single().event.text)
    }

    @Test
    fun compactionLifecycleNormalizesToStatusAndDone() {
        val normalizer = PiEventNormalizer("s1")
        val start = normalizer.normalize(1, 1.0, piEvent("""{"type":"compaction_start","reason":"manual"}"""))
        assertEquals(listOf("compact/start"), start.map { it.event.type })
        assertEquals("manual", start.single().event.reason)
        // 完成的可见痕迹是 compaction entry（entry_appended）。
        val done = normalizer.normalize(
            2, 2.0,
            piEvent(
                """{"type":"entry_appended","entry":{"type":"compaction","summary":"...","tokensBefore":118000}}"""
            )
        )
        assertEquals(listOf("compact/done"), done.map { it.event.type })
        assertEquals("压缩前 118k tokens", done.single().event.text)
        val end = normalizer.normalize(3, 3.0, piEvent("""{"type":"compaction_end","status":"completed"}"""))
        assertEquals(listOf("compact/end"), end.map { it.event.type })
    }

    @Test
    fun failedAndDeclinedCompactionEndBecomeVisibleNotices() {
        val normalizer = PiEventNormalizer("s1")
        val failed = normalizer.normalize(
            1, 1.0,
            piEvent("""{"type":"compaction_end","status":"failed","error":{"message":"overloaded"}}""" )
        )
        assertEquals(listOf("notice/compact"), failed.map { it.event.type })
        assertEquals("上下文压缩失败，请重试", failed.single().event.text)
        assertEquals(true, failed.single().event.isError)
        val declined = normalizer.normalize(
            2, 2.0,
            piEvent("""{"type":"compaction_end","status":"declined"}""")
        )
        assertEquals("上下文压缩未执行", declined.single().event.text)
        // 老网关/pi 版本的 error 可能是裸字符串。
        val rawString = normalizer.normalize(
            3, 3.0,
            piEvent("""{"type":"compaction_end","status":"failed","error":"timeout"}""")
        )
        assertEquals("上下文压缩失败，请重试", rawString.single().event.text)
    }

    @Test
    fun userMessageEndExtractsImageBlocksWithInjectedAttachmentId() {
        val normalizer = PiEventNormalizer("s1")
        val end = normalizer.normalize(
            1, 1.0,
            piEvent(
                // live 回显形状：网关 shrink 后 base64 变 dataOmitted/dataBytes，
                // attachmentId 是网关按发送帧注入的 display-only 字段。
                """{"type":"message_end","message":{"role":"user","content":[
                   {"type":"text","text":"看图"},
                   {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":2048,
                    "attachmentId":"img-abc"}]}}"""
            )
        )
        val images = end.single().event.images.orEmpty()
        assertEquals(1, images.size)
        assertEquals("img-abc", images.single().attachmentId)
        assertEquals("image/png", images.single().mediaType)
        assertEquals(2048, images.single().bytes)
        assertEquals("看图", end.single().event.text)
    }

    @Test
    fun imageBlockWithInlineDataCarriesBase64ForDirectRender() {
        val normalizer = PiEventNormalizer("s1")
        val end = normalizer.normalize(
            1, 1.0,
            piEvent(
                // 真机 live/history 形状：图在 1MB/512KB 预算内未被裁，data 原样随帧。
                """{"type":"message_end","message":{"role":"user","content":[
                   {"type":"text","text":"看图"},
                   {"type":"image","data":"QUJD","mimeType":"image/jpeg",
                    "attachmentId":"img-5958ce21"}]}}"""
            )
        )
        val image = end.single().event.images.orEmpty().single()
        assertEquals("img-5958ce21", image.attachmentId)
        assertEquals("QUJD", image.dataBase64)
    }

    @Test
    fun imageOnlyUserMessageIsNotDropped() {
        val normalizer = PiEventNormalizer("s1")
        val end = normalizer.normalize(
            1, 1.0,
            piEvent(
                """{"type":"message_end","message":{"role":"user","content":[
                   {"type":"image","mimeType":"image/jpeg","dataOmitted":true,"dataBytes":4096}]}}"""
            )
        )
        assertEquals(listOf("user/message"), end.map { it.event.type })
        assertEquals("", end.single().event.text)
        assertEquals(1, end.single().event.images.orEmpty().size)
    }

    @Test
    fun frameEntryIdFlowsIntoImagesForSection422Addressing() {
        val normalizer = PiEventNormalizer("s1")
        // 帧顶层 entryId（§4.5 message_end 加盖）贯穿 image 提取：
        // fallback id 按 entryId+序号派生（统一 hist- 前缀，与 history 提取同 id）。
        val end = normalizer.normalize(
            1, 1.0,
            piEvent(
                """{"type":"message_end","message":{"role":"user","content":[
                   {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64},
                   {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}}"""
            ),
            entryId = "entry-live-1"
        )
        val images = end.single().event.images.orEmpty()
        assertEquals(2, images.size)
        assertEquals(listOf(0, 1), images.map { it.imageIndex })
        assertEquals(listOf("entry-live-1", "entry-live-1"), images.map { it.entryId })
        assertEquals("hist-entry-live-1-0", images[0].attachmentId)
        assertEquals("hist-entry-live-1-1", images[1].attachmentId)
        // SessionEvent 的 entryId 同样贯穿（身份对消/取图寻址同源）。
        assertEquals("entry-live-1", end.single().entryId)
    }

    @Test
    fun missingFrameEntryIdKeepsLegacyFallbackId() {
        val normalizer = PiEventNormalizer("s1")
        val end = normalizer.normalize(
            1, 1.0,
            piEvent(
                """{"type":"message_end","message":{"role":"user","content":[
                   {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64}]}}"""
            )
        )
        val image = end.single().event.images.orEmpty().single()
        assertNull(image.entryId)
        assertEquals(0, image.imageIndex)
        assertEquals("live-0", image.attachmentId)
    }

    @Test
    fun explicitAttachmentIdWinsOverEntryDerivedFallback() {
        val normalizer = PiEventNormalizer("s1")
        val end = normalizer.normalize(
            1, 1.0,
            piEvent(
                """{"type":"message_end","message":{"role":"user","content":[
                   {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64,
                    "attachmentId":"img-echo"}]}}"""
            ),
            entryId = "entry-live-2"
        )
        val image = end.single().event.images.orEmpty().single()
        // 上传原 attachmentId 保留服务本地预览/整组认领；entryId+序号仅作取图引用。
        assertEquals("img-echo", image.attachmentId)
        assertEquals("entry-live-2", image.entryId)
        assertEquals(0, image.imageIndex)
    }

    @Test
    fun sameEntryYieldsSameFallbackIdsAcrossLiveAndHistoryExtraction() {
        val normalizer = PiEventNormalizer("s1")
        // 真正同一条消息（同 content JSON + 同 entryId）：live 走 message_end 归一
        //（内部前缀 live），history 走投影的 imageAttachmentsOf 直接调用（前缀
        // hist，AndroidGatewayProjection.normalizeHistoryMessages 同形态）。
        // 两次提取必须得到相同占位 id，切换/重连后本地缓存键才能复用。
        val content = """[{"type":"text","text":"看图"},
           {"type":"image","mimeType":"image/png","dataOmitted":true,"dataBytes":64},
           {"type":"image","mimeType":"image/jpeg","dataOmitted":true,"dataBytes":32}]"""
        val live = normalizer.normalize(
            1, 1.0,
            piEvent(
                """{"type":"message_end","message":{"role":"user","content":$content}}"""
            ),
            entryId = "entry-same-1"
        ).single().event.images.orEmpty()
        val history = PiEventNormalizer.imageAttachmentsOf(
            piEvent(content),
            fallbackPrefix = "hist",
            entryId = "entry-same-1"
        )
        assertEquals(listOf("hist-entry-same-1-0", "hist-entry-same-1-1"), live.map { it.attachmentId })
        assertEquals(live.map { it.attachmentId }, history.map { it.attachmentId })

        // 不同 entry / 不同序号互不撞：id 含 entryId 与序号。
        val otherEntry = PiEventNormalizer.imageAttachmentsOf(
            piEvent(content),
            fallbackPrefix = "hist",
            entryId = "entry-same-2"
        )
        assertEquals(listOf("hist-entry-same-2-0", "hist-entry-same-2-1"), otherEntry.map { it.attachmentId })
    }
}
