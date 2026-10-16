package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.protocol.GatewayTreeView
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayTreeViewContractTest {
    @Test
    fun optionalViewPreservesLegacyRequestAndEncodesSearchLiterally() {
        assertFalse(Json.parseToJsonElement(GatewayRequests.tree("s").payload).jsonObject.containsKey("view"))
        val options = GatewayTreeView("all", "引号\" 标签", listOf("n1"))
        val view = Json.parseToJsonElement(GatewayRequests.tree("s", options).payload).jsonObject["view"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("\"all\""), view["filter"])
        assertEquals(Json.parseToJsonElement("[\"n1\"]"), view["foldedIds"])
    }

    @Test
    fun treeViewResponseKeepsPiPrefixTextFoldAndSelection() {
        val frame = GatewayWireDecoder.decode("""{"kind":"tree","sessionId":"s","leafId":"leaf","selectedId":"parent",
            "view":{"filter":"default","search":"needle","foldedIds":["parent"]},
            "nodes":[{"id":"parent","type":"message","role":"user","prefix":"├⊞ ","onActivePath":true,
            "preview":"legacy","displayText":"user: **literal**","foldable":true,"folded":true}]}""")
        assertEquals("parent", frame.selectedId)
        assertEquals("needle", frame.view?.search)
        val node = frame.nodes!!.single()
        assertEquals("├⊞ ", node.prefix)
        assertEquals("user: **literal**", node.displayText)
        assertTrue(node.folded && node.foldable && node.onActivePath)
    }

    /** 树页动作请求（PROTOCOL §4.26/§4.27）：目标三元完整，清除标签必须是显式 null。 */
    @Test
    fun treeActionRequestsCarryTargetsAndExplicitNullLabelForClear() {
        val copy = Json.parseToJsonElement(GatewayRequests.entryCopy("s-1", "e-9").payload).jsonObject
        assertEquals("entry-copy", copy["type"]!!.jsonPrimitive.content)
        assertEquals("s-1", copy["sessionId"]!!.jsonPrimitive.content)
        assertEquals("e-9", copy["entryId"]!!.jsonPrimitive.content)

        val put = Json.parseToJsonElement(GatewayRequests.labelPut("s-1", "e-9", " 交付 ").payload).jsonObject
        assertEquals("label-put", put["type"]!!.jsonPrimitive.content)
        assertEquals("e-9", put["entryId"]!!.jsonPrimitive.content)
        // 客户端不预先 trim：Pi LabelInput 的 trim/空值清除语义由网关执行，两边不各做一半。
        assertEquals(" 交付 ", put["label"]!!.jsonPrimitive.content)

        // 清除：必须发显式 null（而不是缺键），否则网关无法区分“清除”与“不改”。
        val clear = Json.parseToJsonElement(GatewayRequests.labelPut("s-1", "e-9", null).payload).jsonObject
        assertTrue(clear.containsKey("label"))
        assertEquals(JsonNull, clear["label"])
    }

    /** 应答解码：全文/超限标志/标签与叶子都要能从 wire 读到，不丢字段。 */
    @Test
    fun treeActionResponsesKeepFullTextOversizeFlagAndLabelLeaf() {
        val copied = GatewayWireDecoder.decode(
            """{"kind":"entry-copy","sessionId":"s","entryId":"e1","bytes":11,"text":"hi there全\n文"}"""
        )
        assertEquals("hi there全\n文", copied.text)
        assertEquals(11, copied.bytes)
        assertEquals(null, copied.oversized)

        // 超限帧不带 text：客户端据此明确报“未复制”，不得静默截断当全文。
        val oversized = GatewayWireDecoder.decode(
            """{"kind":"entry-copy","sessionId":"s","entryId":"e2","bytes":600000,"oversized":true}"""
        )
        assertEquals(null, oversized.text)
        assertEquals(true, oversized.oversized)
        assertEquals(600000, oversized.bytes)

        val labeled = GatewayWireDecoder.decode(
            """{"kind":"label-put","sessionId":"s","entryId":"e1","label":"交付",
               "labelEntryId":"lab-1","leafId":"lab-1"}"""
        )
        assertEquals("交付", labeled.label)
        assertEquals("lab-1", labeled.labelEntryId)
        assertEquals("lab-1", labeled.leafId)

        val cleared = GatewayWireDecoder.decode(
            """{"kind":"label-put","sessionId":"s","entryId":"e1","label":null,
               "labelEntryId":"lab-2","leafId":"lab-2"}"""
        )
        assertEquals(null, cleared.label)
        assertEquals("lab-2", cleared.labelEntryId)

        // 错误帧保留关联三元，客户端才能把失败归到正确的动作卡。
        val failure = GatewayWireDecoder.decode(
            """{"kind":"error","code":"entry-copy-empty","message":"no text",
               "requestType":"entry-copy","sessionId":"s","entryId":"e0"}"""
        )
        assertEquals("entry-copy-empty", failure.code)
        assertEquals("entry-copy", failure.requestType)
        assertEquals("e0", failure.entryId)
        // 错条目/迟到帧的关联依据：没有 entryId 的错误帧不能被当成某条目的结果。
        assertNull(
            GatewayWireDecoder.decode("""{"kind":"error","code":"internal","message":"y","requestType":"entry-copy"}""").entryId
        )
    }
}
