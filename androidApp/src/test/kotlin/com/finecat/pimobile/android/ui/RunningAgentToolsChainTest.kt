package com.finecat.pimobile.android.ui

import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.PiEventNormalizer
import com.finecat.pimobile.shared.projection.ConversationProjector
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 复现常驻条数据链：pi 事件 JSON → 归一 → 投影 → runningAgentTools。 */
class RunningAgentToolsChainTest {
    private val wireJson = Json { ignoreUnknownKeys = true }
    private fun piEvent(json: String): JsonValue =
        JsonValue.fromJsonElement(wireJson.parseToJsonElement(json))

    @Test
    fun toolExecutionStartFlowsToRunningAgentStrip() {
        val normalizer = PiEventNormalizer("s1")
        val events = normalizer.normalize(
            1, 1000.0,
            piEvent("""{"type":"tool_execution_start","toolCallId":"tool-xyz","toolName":"Agent","args":{"description":"验证常驻条","subagent_type":"general-purpose"}}""")
        )
        assertEquals(listOf("tool/call"), events.map { it.event.type })
        val call = events.single()
        assertEquals("tool-xyz", call.event.callId)
        assertEquals("Agent", call.event.name)

        val items = ConversationProjector.make(listOf(call))
        val agent = items.single { it.title == "Agent" }
        assertEquals("tool-xyz", agent.toolCallId)

        val running = runningAgentTools(items, nowEpochSeconds = 1100.0)
        assertEquals(1, running.size)
        assertEquals("tool-xyz", running.single().call?.toolCallId)
    }

    @Test
    fun streamingDeltaPlusExecutionStartStillYieldsStrip() {
        // 完整序列：流式 toolCall delta（toolCallId=null 的 stream item）
        // + tool_execution_start（带 callId 的 final item）→ 条必须出现。
        val normalizer = PiEventNormalizer("s1")
        val events = normalizer.normalize(
            1, 1000.0,
            piEvent("""{"type":"message_update","message":{"role":"assistant","content":[{"type":"toolCall","id":"tool-xyz","name":"Agent","arguments":{"description":"流式参数"}}]}}""")
        ) + normalizer.normalize(
            2, 1001.0,
            piEvent("""{"type":"tool_execution_start","toolCallId":"tool-xyz","toolName":"Agent","args":{"description":"流式参数"}}""")
        )
        assertTrue("有 tool 事件", events.isNotEmpty())
        val items = ConversationProjector.make(events)
        val agentItems = items.filter { it.title == "Agent" }
        assertTrue("投影含 Agent 工具项: " + items.joinToString { it.title + ":" + it.toolCallId }, agentItems.isNotEmpty())
        val running = runningAgentTools(items, nowEpochSeconds = 1100.0)
        assertTrue(
            "运行中条应有数据，Agent items=" + agentItems.joinToString { it.toolCallId.toString() },
            running.isNotEmpty()
        )
    }

    @Test
    fun finishedAgentCallDoesNotShowStrip() {
        val normalizer = PiEventNormalizer("s1")
        val events = normalizer.normalize(
            1, 1000.0,
            piEvent("""{"type":"tool_execution_start","toolCallId":"tool-xyz","toolName":"Agent","args":{}}""")
        ) + normalizer.normalize(
            2, 1010.0,
            piEvent("""{"type":"message_end","message":{"role":"toolResult","toolCallId":"tool-xyz","content":[{"type":"text","text":"started"}]}}""")
        )
        val items = ConversationProjector.make(events)
        assertTrue(items.any { it.title == "Agent" })
        assertTrue(runningAgentTools(items, nowEpochSeconds = 1020.0).isEmpty())
    }
}
