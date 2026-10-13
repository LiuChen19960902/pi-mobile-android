package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.projection.TrajectoryNodeKind
import com.finecat.pimobile.shared.projection.TrajectoryProjection
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.SessionEvent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5a（C5）：pi-ai 0.84.3 Usage 真键（input/output/cacheRead/cacheWrite/reasoning）
 * 的固定 fixture 契约校验。真键来自
 * `$PI_GATEWAY_DIR/node_modules/@earendil-works/pi-coding-agent/node_modules/
 * @earendil-works/pi-ai/dist/types.d.ts:265-281`（本机只读核对）。
 */
class TrajectoryUsageContractTest {

    /** 与网关 wire 解码同链路：JSON 字符串 → 项目 JsonValue。 */
    private fun usageValue(json: String): com.finecat.pimobile.shared.protocol.JsonValue =
        com.finecat.pimobile.shared.protocol.JsonValue.fromJsonElement(
            com.finecat.pimobile.shared.protocol.wireJson.parseToJsonElement(json),
        )

    private fun sessionEvent(seq: Int, time: Double, event: GatewayEvent) =
        SessionEvent(sessionId = "s1", seq = seq, time = time, event = event)

    private fun requestNodeUsage(events: List<SessionEvent>): com.finecat.pimobile.shared.projection.RequestTokenUsage? =
        TrajectoryProjection.make(events)
            .firstOrNull { it.kind == TrajectoryNodeKind.REQUEST }
            ?.request?.usage

    /** 真键 fixture：assistant/message.usage 携带 SDK Usage 形状 → 解析不再恒 0。 */
    @Test
    fun `real sdk usage keys are parsed`() {
        val usage = usageValue("""{"input":12,"output":30,"cacheRead":400,"cacheWrite":7,"reasoning":18,"totalTokens":449}""")
        val usageNode = requestNodeUsage(
            listOf(
                sessionEvent(1, 100.0, GatewayEvent(type = "assistant/chunk", turn = 1, step = 0, chunkType = "text-delta", text = "你好")),
                sessionEvent(2, 101.0, GatewayEvent(type = "assistant/message", turn = 1, step = 0, usage = usage)),
            ),
        )
        assertEquals(12, usageNode?.uncachedInput)
        assertEquals(400, usageNode?.cachedInput)
        assertEquals(30, usageNode?.output)
        // reasoning 是 output 子集：只展示，totalInput 不并入
        assertEquals(18, usageNode?.reasoning)
        assertEquals(412, usageNode?.totalInput)
    }

    /** 旧键名兼容：早期/别名形状仍可读。 */
    @Test
    fun `legacy key aliases still parse`() {
        val usage = usageValue("""{"inputTokens":5,"cacheReadTokens":50,"outputTokens":9,"reasoningTokens":3}""")
        val usageNode = requestNodeUsage(
            listOf(
                sessionEvent(1, 100.0, GatewayEvent(type = "assistant/message", turn = 1, step = 0, usage = usage)),
            ),
        )
        assertEquals(5, usageNode?.uncachedInput)
        assertEquals(50, usageNode?.cachedInput)
        assertEquals(9, usageNode?.output)
        assertEquals(3, usageNode?.reasoning)
    }

    /** usage 缺失：全 0（SDK Usage 为非空对象、缺省即 0），不得伪造。 */
    @Test
    fun `missing usage stays zero`() {
        val usageNode = requestNodeUsage(
            listOf(
                sessionEvent(1, 100.0, GatewayEvent(type = "assistant/message", turn = 1, step = 0)),
            ),
        )
        assertEquals(0, usageNode?.uncachedInput)
        assertEquals(0, usageNode?.cachedInput)
        assertEquals(0, usageNode?.output)
        assertEquals(0, usageNode?.reasoning)
    }

    /** 未知 provider 额外键（如 totalTokens）不误读；cost 嵌套不干扰顶层同名键。 */
    @Test
    fun `top level keys win over nested cost object`() {
        val usage = usageValue("""{"input":8,"output":4,"totalTokens":999,"cost":{"input":0.004,"output":0.002,"total":0.006}}""")
        val usageNode = requestNodeUsage(
            listOf(
                sessionEvent(1, 100.0, GatewayEvent(type = "assistant/message", turn = 1, step = 0, usage = usage)),
            ),
        )
        assertEquals(8, usageNode?.uncachedInput)
        assertEquals(4, usageNode?.output)
        // totalTokens 不是已定义键，不产生读数
        assertTrue(usageNode?.totalInput == 8)
    }
}
