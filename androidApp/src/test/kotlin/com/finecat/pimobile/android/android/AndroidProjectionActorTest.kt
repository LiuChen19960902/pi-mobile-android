package com.finecat.pimobile.android

import com.finecat.pimobile.shared.facade.SharedMobileSnapshot
import com.finecat.pimobile.shared.facade.SharedMobileStore
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式渲染链的 actor 级验证：同 turn 的 text_delta 高频到达时，节流合并不得
 * 丢内容；流式期间必须以中间态 publish 到 UI（否则表现为"憋到最后一次性出现"）。
 */
class AndroidProjectionActorTest {
    private class FakeClock {
        var nanos = 0L
        fun advanceNanos(value: Long) { nanos += value }
    }

    private fun newActor(
        projection: AndroidGatewayProjection,
        clock: FakeClock,
        published: MutableList<SharedMobileSnapshot>
    ): AndroidProjectionActor = AndroidProjectionActor(
        projection = projection,
        uiDispatcher = Dispatchers.Unconfined,
        publish = { snapshot, _ -> published += snapshot },
        nowNanos = { clock.nanos }
    )

    @Test
    fun streamingDeltasFlushAtIntervalAndKeepTheFullText() = runTest {
        val clock = FakeClock()
        val projection = AndroidGatewayProjection(SharedMobileStore())
        val published = mutableListOf<SharedMobileSnapshot>()
        val actor = newActor(projection, clock, published)
        actor.selectSession("session-a")
        val normalizer = PiEventNormalizer("session-a")
        var seq = 1
        suspend fun feed(eventJson: String) {
            val raw = """{"kind":"event","sessionId":"session-a","seq":$seq,"time":100.0,"event":$eventJson}"""
            val frame = GatewayWireDecoder.decode(raw)
            val events = normalizer.normalize(seq, 100.0, requireNotNull(frame.event))
            seq += 1
            clock.advanceNanos(30_000_000L) // 网关实测 ~30ms 一帧
            actor.acceptStreamingFrame(raw, frame, "session-a", events)
        }
        feed("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        repeat(20) { index ->
            feed("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"字$index"}}""")
        }
        // 流式中间态必须被 publish 过：至少一次快照的尾部是仍在增长的 streaming item。
        val streamingSnapshots = published.map { it.conversation }
            .filter { list -> list.lastOrNull()?.id?.startsWith("stream-") == true }
        assertTrue("流式期间应出现中间态快照，实际发布 ${published.size} 次", streamingSnapshots.isNotEmpty())
        assertTrue(
            "中间态文本应为增长中的部分正文，实际 ${streamingSnapshots.last().last().text}",
            streamingSnapshots.last().last().text.isNotEmpty()
        )
        feed("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"流式正文"}]}}""")
        val assistant = projection.snapshot().conversation.filter { it.kind.name == "ASSISTANT" }.single()
        // 定稿后 ID 不再是 stream-，正文完整。
        assertEquals("流式正文", assistant.text)
        assertTrue(assistant.id.startsWith("stream-").not())
    }

    @Test
    fun streamingToolDeltasKeepEveryArgumentChunkAndNeverCrossToolCalls() = runTest {
        val clock = FakeClock()
        val projection = AndroidGatewayProjection(SharedMobileStore())
        val published = mutableListOf<SharedMobileSnapshot>()
        val actor = newActor(projection, clock, published)
        actor.selectSession("session-a")
        val normalizer = PiEventNormalizer("session-a")
        var seq = 1
        suspend fun feed(eventJson: String) {
            val raw = """{"kind":"event","sessionId":"session-a","seq":$seq,"time":100.0,"event":$eventJson}"""
            val frame = GatewayWireDecoder.decode(raw)
            val events = normalizer.normalize(seq, 100.0, requireNotNull(frame.event))
            seq += 1
            // 10ms/帧：必须让相邻 tool 增量落在同一个 50ms 合并窗口里，否则
            // 「不同 toolCall 不互相合并」这条断言会因为 pending 已被 flush 而空转。
            clock.advanceNanos(10_000_000L)
            actor.acceptStreamingFrame(raw, frame, "session-a", events)
        }
        suspend fun toolDelta(id: String, name: String, delta: String) {
            // delta 是工具参数的 JSON 片段，插进外层 JSON 前必须转义。
            val escaped = delta.replace("\\", "\\\\").replace("\"", "\\\"")
            feed(
                """{"type":"message_update","assistantMessageEvent":{""" +
                    """"type":"toolcall_delta","id":"$id","toolName":"$name","delta":"$escaped"}}"""
            )
        }
        feed("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
        // 同一 toolCall 的三段增量：合并后必须全部保留。
        toolDelta("call-1", "read", "{\"path\":")
        toolDelta("call-1", "read", "\"/tmp/a\"")
        toolDelta("call-1", "read", "}")
        // 同一 turn 内第二个 toolCall：不得被并进 call-1。
        toolDelta("call-2", "grep", "{\"pattern\":\"x\"}")
        feed("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}""")

        val toolRows = projection.snapshot().conversation.filter { it.id.startsWith("stream-tool-") }
        val first = toolRows.single { it.id == "stream-tool-call-1" }
        assertEquals("""{"path":"/tmp/a"}""", first.text)
        val second = toolRows.single { it.id == "stream-tool-call-2" }
        assertEquals("""{"pattern":"x"}""", second.text)
    }

    @Test
    fun nonStreamingFrameForcesPendingFlush() = runTest {
        val clock = FakeClock()
        val projection = AndroidGatewayProjection(SharedMobileStore())
        val published = mutableListOf<SharedMobileSnapshot>()
        val actor = newActor(projection, clock, published)
        actor.selectSession("session-a")
        val normalizer = PiEventNormalizer("session-a")
        var seq = 1
        suspend fun feedStreaming() {
            val raw = """{"kind":"event","sessionId":"session-a","seq":$seq,"time":100.0,""" +
                """"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"增"}}}"""
            val frame = GatewayWireDecoder.decode(raw)
            val events = normalizer.normalize(seq, 100.0, requireNotNull(frame.event))
            seq += 1
            actor.acceptStreamingFrame(raw, frame, "session-a", events)
        }
        feedStreaming()
        // 非流式帧（agent_end）到达：pending 必须先 flush，不能被吞进流式批次。
        val raw = """{"kind":"event","sessionId":"session-a","seq":$seq,"time":100.0,"event":{"type":"agent_end","willRetry":false}}"""
        val frame = GatewayWireDecoder.decode(raw)
        val events = normalizer.normalize(seq, 100.0, requireNotNull(frame.event))
        actor.acceptFrame(raw, frame, "session-a", events)
        assertTrue("pending 流式批次应在非流式帧前 flush，实际发布 ${published.size} 次", published.size >= 2)
    }
}
