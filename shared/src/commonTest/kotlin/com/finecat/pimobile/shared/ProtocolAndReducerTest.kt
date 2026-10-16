package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.domain.SessionListAction
import com.finecat.pimobile.shared.domain.SessionListReducer
import com.finecat.pimobile.shared.domain.SessionListState
import com.finecat.pimobile.shared.gateway.GatewayOutgoingImage
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadException
import com.finecat.pimobile.shared.gateway.GatewayPairingPayloadParser
import com.finecat.pimobile.shared.gateway.GatewayRequestLanePolicy
import com.finecat.pimobile.shared.gateway.GatewayRequests
import com.finecat.pimobile.shared.gateway.isPrivateWebSocketHost
import com.finecat.pimobile.shared.gateway.ProviderModelUpsert
import com.finecat.pimobile.shared.gateway.requireValidProviderUpsert
import com.finecat.pimobile.shared.platform.GatewayConnectionSpec
import com.finecat.pimobile.shared.platform.GatewayTransportFrame
import com.finecat.pimobile.shared.protocol.GatewayEvent
import com.finecat.pimobile.shared.protocol.GatewayModelSelection
import com.finecat.pimobile.shared.protocol.GatewaySessionSummary
import com.finecat.pimobile.shared.protocol.GatewayWireDecoder
import com.finecat.pimobile.shared.protocol.JsonValue
import com.finecat.pimobile.shared.protocol.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolAndReducerTest {
    @Test
    fun jsonValueIntegerLiteralRoundTripKeepsNumberTypeWithoutSaturation() {
        // 整数字面量往返必须保持数字类型（旧 bug：JsonPrimitive(raw) 产出字符串）。
        val parsed = JsonValue.fromJsonElement(Json.parseToJsonElement("""{"attempt":2}"""))
        val roundTripped = Json.encodeToString(JsonElement.serializer(), parsed.toJsonElement())
        assertEquals("""{"attempt":2}""", roundTripped)
        // 超出 Long 范围的整数对齐 pi 端 JS 语义：double 表示，不饱和、不变字符串。
        val big = JsonValue.fromJsonElement(Json.parseToJsonElement("""{"n":9223372036854775809}"""))
        assertEquals(9.223372036854775809E18, big["n"]?.doubleValue!!, 0.0)
        val bigRound = Json.encodeToString(JsonElement.serializer(), big.toJsonElement())
        assertFalse(bigRound.contains("9223372036854775807"), "saturation leaked into round trip: $bigRound")
    }

    @Test
    fun plaintextWsIsLimitedToLoopbackAndPrivateHosts() {
        for (host in listOf("127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.0.20", "169.254.1.2", "100.64.0.1", "100.127.255.254", "localhost", "mypi", "[::1]", "[fe80::1]", "[fd00::5]")) {
            assertTrue(isPrivateWebSocketHost(host), host)
        }
        for (host in listOf("172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "8.8.8.8", "203.0.113.10", "gateway.example", "[2001:db8::1]")) {
            assertFalse(isPrivateWebSocketHost(host), host)
        }
    }

    @Test
    fun pairingPayloadRequiresVersionOneStrictBase64UrlAndWebSocketEndpoint() {

        val payloadJson =
            """{"version":1,"publicUrl":"ws://192.168.0.10:3082/ws/mobile","pairingCode":"${GatewayProtocolFixtures.PAIRING_CODE}","expiresAt":1788234336499}"""
        val payload = GatewayPairingPayloadParser.parse(
            encodeBase64Url(payloadJson),
            nowEpochMilliseconds = 1_788_234_000_000
        )
        assertEquals(1, payload.version)
        assertEquals("ws://192.168.0.10:3082/ws/mobile", payload.publicUrl)
        assertEquals(GatewayProtocolFixtures.PAIRING_CODE, payload.pairingCode)
        assertEquals(1_788_234_336_499.0, payload.expiresAt)

        // 非 ws/wss、非 v1、带凭据的 authority、过期都必须拒绝。
        for (invalid in listOf(
            """{"version":1,"publicUrl":"https://gateway.example/ws/mobile","pairingCode":"code","expiresAt":101}""",
            """{"version":2,"publicUrl":"ws://192.168.0.10:3082/ws/mobile","pairingCode":"code","expiresAt":101}""",
            """{"version":1,"publicUrl":"wss://user:secret@gateway.example/ws/mobile","pairingCode":"code","expiresAt":101}""",
            // 明文 ws 不出私网：公网地址必须 wss（客户端同步网关约束）。
            """{"version":1,"publicUrl":"ws://203.0.113.10:3082/ws/mobile","pairingCode":"code","expiresAt":1788234336499}"""
        )) {
            assertFailsWith<GatewayPairingPayloadException> {
                GatewayPairingPayloadParser.parse(encodeBase64Url(invalid), nowEpochMilliseconds = 100)
            }
        }
        assertFailsWith<GatewayPairingPayloadException> {
            GatewayPairingPayloadParser.parse(
                encodeBase64Url(
                    """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"code","expiresAt":99}"""
                ),
                nowEpochMilliseconds = 100
            )
        }
        // 严格 Base64URL：拒绝 '=' padding。
        assertFailsWith<GatewayPairingPayloadException> {
            GatewayPairingPayloadParser.parse("$payloadJson=", nowEpochMilliseconds = 100)
        }
    }

    @Test
    fun pairingPayloadAndConnectionSpecNeverLeakCredentialsThroughToString() {
        val payload = GatewayPairingPayloadParser.parse(
            encodeBase64Url(
                """{"version":1,"publicUrl":"wss://gateway.example/ws/mobile","pairingCode":"private-code","expiresAt":101}"""
            ),
            nowEpochMilliseconds = 100
        )
        val payloadDescription = payload.toString()
        assertFalse("private-code" in payloadDescription)
        assertFalse("gateway.example" in payloadDescription)

        val description = GatewayConnectionSpec(
            generation = 1,
            endpoint = "wss://gateway.example/ws/mobile",
            deviceId = "private-device-id",
            bearerToken = "private-token",
            pairingCode = "private-pairing-code"
        ).toString()
        assertFalse("private-device-id" in description)
        assertFalse("private-token" in description)
        assertFalse("private-pairing-code" in description)

        val image = GatewayOutgoingImage("image/png", "private-base64")
        val outgoingDescription = image.toString() + GatewayRequests.message(
            text = "private-message",
            images = listOf(image),
            sessionId = "session-a",
            cwd = null
        ).toString()
        assertFalse("private-base64" in outgoingDescription)
        assertFalse("private-message" in outgoingDescription)

        val event = GatewayEvent(type = "assistant/message", text = "private-event", raw = JsonValue.StringValue("private-raw"))
        assertFalse("private-event" in event.toString())
        val sessionEvent = SessionEvent("s1", 1, 1.0, event)
        assertTrue("s1" in sessionEvent.toString())
        assertFalse("private-event" in sessionEvent.toString())
        assertFalse("private-frame" in GatewayTransportFrame(1, "private-frame", 13).toString())
    }

    @Test
    fun piBusinessRequestsMatchGatewayWireContract() {
        assertEquals("""{"type":"ping"}""", GatewayRequests.ping().payload)
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, GatewayRequests.ping().lanePolicy)
        assertEquals("""{"type":"sessions"}""", GatewayRequests.sessions().payload)
        assertEquals("""{"type":"subscribe","sessionId":"s1"}""", GatewayRequests.subscribe("s1").payload)
        assertEquals("""{"type":"unsubscribe","sessionId":"s1"}""", GatewayRequests.unsubscribe("s1").payload)
        assertEquals("""{"type":"state","sessionId":"s1"}""", GatewayRequests.state("s1").payload)
        assertEquals("""{"type":"abort","sessionId":"s1","restoreQueued":true}""", GatewayRequests.abort("s1").payload)
        assertEquals("""{"type":"dequeue","sessionId":"s1","queueRequestId":"q-1"}""", GatewayRequests.dequeue("s1", "q-1").payload)
        assertEquals("q-1", GatewayRequests.dequeue("s1", "q-1").correlationId)
        // §4.22 attachment：三元寻址，单 lane REJECT_IF_BUSY（同一时刻至多一个在途取图）。
        assertEquals(
            """{"type":"attachment","sessionId":"s1","entryId":"entry-9","imageIndex":2}""",
            GatewayRequests.attachment("s1", "entry-9", 2).payload
        )
        assertEquals(GatewayRequestLanePolicy.REJECT_IF_BUSY, GatewayRequests.attachment("s1", "entry-9", 2).lanePolicy)
        assertEquals("s1", GatewayRequests.attachment("s1", "entry-9", 2).targetSessionId)
        assertEquals(
            """{"type":"history","sessionId":"s1","beforeEntryId":"e9","limit":60}""",
            GatewayRequests.history("s1", beforeEntryId = "e9").payload
        )
        assertEquals(
            """{"type":"history","sessionId":"s1","limit":60}""",
            GatewayRequests.history("s1").payload
        )
        assertEquals(
            """{"type":"message","sessionId":"s1","text":"续聊","mode":"followUp"}""",
            GatewayRequests.message("续聊", emptyList(), "s1", null).payload
        )
        assertEquals(
            """{"type":"message","cwd":"/tmp/pi-demo","text":"新会话","mode":"followUp"}""",
            GatewayRequests.message("新会话", emptyList(), null, "/tmp/pi-demo").payload
        )
        assertEquals(
            GatewayRequestLanePolicy.REJECT_IF_BUSY,
            GatewayRequests.message("x", emptyList(), "s1").lanePolicy
        )
        // 新会话模型预选随帧携带（pi 在首轮前应用，避免默认模型先跑一轮）。
        assertEquals(
            """{"type":"message","sessionId":"s1","text":"x","mode":"followUp","model":{"provider":"relayB","modelId":"example-model-flash","thinkingLevel":"high"}}""",
            GatewayRequests.message("x", emptyList(), "s1", null, GatewayModelSelection("relayB", "example-model-flash", "high")).payload
        )
        assertEquals(
            """{"type":"set-model","sessionId":"s1","provider":"relayB","modelId":"example-model","thinkingLevel":"high"}""",
            GatewayRequests.setModel("s1", "relayB", "example-model", "high").payload
        )
        assertEquals(
            """{"type":"set-model","sessionId":"s1","provider":"relayB","modelId":"example-model"}""",
            GatewayRequests.setModel("s1", "relayB", "example-model", null).payload
        )
        // 新会话预选可能只有模型、没有档位（用户未选档位）：thinkingLevel 必须整字段省略，
        // 不能写成 null——网关 readModelSelection 对非 string 的 thinkingLevel 直接整帧拒绝。
        assertEquals(
            """{"type":"message","cwd":"/tmp","text":"新会话","mode":"followUp","model":{"provider":"relayA","modelId":"example-model-flash"}}""",
            GatewayRequests.message("新会话", emptyList(), null, "/tmp", GatewayModelSelection("relayA", "example-model-flash", null)).payload
        )
        assertEquals(
            """{"type":"models"}""",
            GatewayRequests.models().payload
        )
        // provider-test：字段可省（编辑场景网关回退 models.json 已存值）。
        assertEquals(
            """{"type":"provider-test","id":"relayA","model":"m1"}""",
            GatewayRequests.providerTest(id = "relayA", model = "m1").payload
        )
        // api 必须能随帧发出：新建非 openai 协议时网关无已存值可回退，
        // 缺省会按 openai 端点测（anthropic/google 合法配置永远测不过）。
        assertEquals(
            """{"type":"provider-test","id":"relay","baseUrl":"https://api.anthropic.com","apiKey":"sk-x","model":"m1","api":"anthropic-messages"}""",
            GatewayRequests
                .providerTest(
                    id = "relay",
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-x",
                    model = "m1",
                    api = "anthropic-messages"
                )
                .payload
        )
        assertEquals(
            """{"type":"provider-test","baseUrl":"https://relay.example/v1","apiKey":"sk-x","model":"m1"}""",
            GatewayRequests
                .providerTest(baseUrl = "https://relay.example/v1", apiKey = "sk-x", model = "m1")
                .payload
        )
        assertEquals("provider-test-result", GatewayRequests.providerTest().responseKind)
    }

    /** 协议（api）属于模型：pi 只按 model.api 分派流实现，provider 级字段手机侧不写。 */
    @Test
    fun providerPutCarriesWireProtocolPerModelNeverAtProviderLevel() {
        val frame = GatewayRequests.providerPut(
            id = "mixed-relay",
            baseUrl = "https://relay.example/v1",
            apiKey = null,
            models = listOf(
                ProviderModelUpsert(
                    id = "gpt-4o-mini",
                    api = "openai-completions",
                    contextWindow = 128000,
                    supportsImages = true
                ),
                ProviderModelUpsert(
                    id = "claude-sonnet",
                    api = "anthropic-messages",
                    compat = mapOf("supportsStore" to "false", "maxTokensField" to null)
                )
            )
        ).payload
        val decoded: JsonObject = Json.parseToJsonElement(frame).jsonObject
        assertFalse("api" in decoded, "provider 级不应再带 api：$frame")
        val models = decoded.getValue("models").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("openai-completions", "anthropic-messages"),
            models.map { (it.getValue("api") as JsonPrimitive).content }
        )
        val compat = models[1].getValue("compat").jsonObject
        assertEquals(JsonPrimitive(false), compat.getValue("supportsStore"))
        assertFalse("maxTokensField" in compat, "值为 null 的 compat 项 = 走默认，不应落盘")
    }

    /** provider 显示名：非空才下发（缺省 = 网关保留原值，02 §5.3）。 */
    @Test
    fun providerPutCarriesDisplayNameOnlyWhenFilled() {
        val json = Json.parseToJsonElement(
            GatewayRequests.providerPut(
                id = "relay",
                baseUrl = "https://relay.example/v1",
                apiKey = null,
                models = emptyList(),
                name = "智谱 · 备用",
            ).payload
        ).jsonObject
        assertEquals("智谱 · 备用", (json.getValue("name") as JsonPrimitive).content)

        val withoutName = Json.parseToJsonElement(
            GatewayRequests.providerPut(
                id = "relay",
                baseUrl = "https://relay.example/v1",
                apiKey = null,
                models = emptyList(),
                name = "   ",
            ).payload
        ).jsonObject
        assertTrue(
            "name" !in withoutName,
            "空白显示名不应上 wire：$withoutName",
        )

        // 旧调用方（不传 name）帧形不变
        val legacy = Json.parseToJsonElement(
            GatewayRequests.providerPut("relay", "https://relay.example/v1", null, emptyList()).payload
        ).jsonObject
        assertFalse("name" in legacy)
    }

    /** 本地校验与网关同口径：provider name 过长（>128）当场拒，不等网关返错。 */
    @Test
    fun providerUpsertRejectsOverlongDisplayName() {
        val failure = runCatching {
            requireValidProviderUpsert(
                "relay",
                "https://relay.example/v1",
                null,
                emptyList(),
                "名".repeat(129),
            )
        }.exceptionOrNull()?.message
        assertEquals("provider name 过长", failure)
        // 正常长度不影响旧行为
        requireValidProviderUpsert("relay", "https://relay.example/v1", null, emptyList(), "智谱")
    }

    @Test
    fun providerUpsertRejectsModelWithoutProtocol() {
        val failure = runCatching {
            requireValidProviderUpsert(
                "mixed-relay",
                "https://relay.example/v1",
                null,
                listOf(ProviderModelUpsert(id = "m1", api = "  "))
            )
        }.exceptionOrNull()?.message
        assertEquals("每个模型都要选协议（模型级 api 是 pi 的分派依据）", failure)
    }

    @Test
    fun wireDecoderRejectsFrameWithoutKindInsteadOfBeautifying() {
        // 缺 kind 即违背协议骨架：必须抛走 decode-failed，不美化成 event 掩盖服务端 bug。
        assertFailsWith<MissingFieldException> {
            GatewayWireDecoder.decode(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        }
    }

    @Test
    fun wireDecoderDecodesPiErrorFrame() {
        val error = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_PROMPT_REJECTED)
        assertEquals("error", error.kind)
        assertEquals("prompt-rejected", error.code)
        assertEquals("message", error.requestType)
        assertEquals("s1", error.sessionId)
    }

    @Test
    fun wireDecoderDecodesPiSessionsHistoryAndEventFrames() {
        val sessions = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_SESSIONS)
        assertEquals(1, sessions.items?.size)
        assertEquals("s1", sessions.items?.single()?.get("id")?.stringValue)
        assertNull(sessions.items?.single()?.get("name")?.stringValue)

        val history = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_HISTORY_PAGE)
        assertEquals("history", history.kind)
        assertEquals(listOf("e2", "e3"), history.entryIds)
        assertEquals("e2", history.oldestEntryId)
        assertEquals(true, history.hasMore)
        assertEquals(false, history.isStreaming)

        val event = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_EVENT_TEXT_DELTA)
        assertEquals("event", event.kind)
        assertEquals(9, event.seq)
        assertEquals(1786937355.25, event.time)
        assertEquals("text_delta", event.event?.get("assistantMessageEvent")?.get("type")?.stringValue)

        val settled = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_EVENT_AGENT_SETTLED)
        assertEquals("agent_settled", settled.event?.get("type")?.stringValue)

        // entryId 可选字段：无字段 = 身份未知（null），不依赖客户端猜值。
        assertNull(settled.entryId)
        val withEntry = GatewayWireDecoder.decode(
            """{"kind":"event","sessionId":"s1","seq":10,"time":1.0,"entryId":"e-x",""" +
                """"event":{"type":"message_end","message":{"role":"user","content":[]}}}"""
        )
        assertEquals("e-x", withEntry.entryId)
    }

    @Test
    fun wireDecoderDecodesPiModelsStateAndModelSetFrames() {
        val models = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_MODELS)
        assertEquals("models", models.kind)
        assertEquals(1, models.groups?.size)
        assertEquals("relayB", models.groups?.single()?.provider)

        val state = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_STATE)
        assertEquals("state", state.kind)
        assertEquals("s1", state.sessionId)
        assertEquals("relayB", state.model?.provider)
        assertEquals("example-model", state.model?.modelId)
        assertEquals("high", state.thinkingLevel)
        assertEquals(12.5, state.contextUsage?.percent)

        val modelSet = GatewayWireDecoder.decode(GatewayProtocolFixtures.PI_MODEL_SET)
        assertEquals("model-set", modelSet.kind)
        assertEquals("example-model", modelSet.model?.modelId)
    }

    @Test
    fun jsonValueSupportsNestedLookupPrettyPrintingAndIntegerSearch() {
        val value = JsonValue.ObjectValue(
            mapOf(
                "values" to JsonValue.ObjectValue(mapOf("title" to JsonValue.StringValue("KMP"))),
                "usage" to JsonValue.ObjectValue(mapOf("outputTokens" to JsonValue.NumberValue(42.0)))
            )
        )
        assertEquals("KMP", value["values"]?.get("title")?.stringValue)
        assertEquals(42, value.firstInteger(setOf("outputTokens")))
        assertTrue(value.displayText().contains("\"usage\""))
    }

    @Test
    fun sessionListReducerMergesPiSummariesWithIsoTimestampsAndUnreadSemantics() {
        var state = SessionListState(selectedSessionId = "selected")
        state = SessionListReducer.reduce(
            state,
            SessionListAction.RemoteSessionsReceived(
                listOf(
                    GatewaySessionSummary(
                        id = "s1",
                        cwd = "/tmp/workspace",
                        modified = "2026-09-01T07:36:16.694Z",
                        messageCount = 3,
                        firstMessage = "请验证 Android 是否使用共享 Reducer",
                        running = true
                    )
                )
            )
        )
        // displayTitle = firstMessage 截断；modified ISO-8601 → epoch 秒。
        assertEquals("请验证 Android 是否使用共享 Reducer", state.sessions.single().title)
        assertEquals(1_788_248_176.694, state.sessions.single().lastActivityEpochSeconds)
        assertTrue(state.sessions.single().isRunning)
        assertEquals("/tmp/workspace", state.sessions.single().cwd)

        state = SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(
                SessionEvent(
                    "s1", 2, 1_788_248_177.694,
                    GatewayEvent("assistant/message", text = "done")
                ),
                insertedAtEpochSeconds = 1_788_248_177.694
            )
        )
        assertTrue(state.sessions.single().hasUnread)
    }

    @Test
    fun sessionListReducerFallsBackToCwdBasenameAndBlankTitles() {
        val state = SessionListReducer.reduce(
            SessionListState(),
            SessionListAction.RemoteSessionsReceived(
                listOf(
                    GatewaySessionSummary(id = "by-cwd", cwd = "/Users/mobile/pi-workspace"),
                    GatewaySessionSummary(id = "blank", messageCount = 0, firstMessage = "(no messages)"),
                    GatewaySessionSummary(id = "named", name = "重构计划")
                )
            )
        )
        val titles = state.sessions.associateBy { it.id }
        assertEquals("pi-workspace", titles.getValue("by-cwd").title)
        assertEquals("/Users/mobile/pi-workspace", titles.getValue("by-cwd").cwd)
        assertTrue(titles.getValue("blank").title.startsWith("Blank session"))
        assertEquals("重构计划", titles.getValue("named").title)
    }

    @Test
    fun sessionListReducerKeepsBlankTitleAcrossPlaceholderRefresh() {
        var state = SessionListReducer.reduce(
            SessionListState(),
            SessionListAction.RemoteSessionsReceived(
                listOf(GatewaySessionSummary(id = "blank", messageCount = 0, firstMessage = "(no messages)"))
            )
        )
        assertTrue(state.sessions.single().title.startsWith("Blank session"))
        // 重连/刷新带回占位 firstMessage：blank 标题不掉成目录名，首条消息仍可接管。
        state = SessionListReducer.reduce(
            state,
            SessionListAction.RemoteSessionsReceived(
                listOf(
                    GatewaySessionSummary(
                        id = "blank", cwd = "/tmp/ws", messageCount = 3, firstMessage = "(no messages)"
                    )
                )
            )
        )
        assertTrue(state.sessions.single().title.startsWith("Blank session"))
        // 真名照收（含改名收敛）。
        state = SessionListReducer.reduce(
            state,
            SessionListAction.RemoteSessionsReceived(
                listOf(GatewaySessionSummary(id = "blank", name = "重构计划"))
            )
        )
        assertEquals("重构计划", state.sessions.single().title)
        // 本地 Renamed 不被无真名刷新覆盖。
        state = SessionListReducer.reduce(state, SessionListAction.Renamed("blank", "本地名"))
        state = SessionListReducer.reduce(
            state,
            SessionListAction.RemoteSessionsReceived(
                listOf(GatewaySessionSummary(id = "blank", cwd = "/tmp/ws"))
            )
        )
        assertEquals("本地名", state.sessions.single().title)
    }

    @Test
    fun sessionListBlankTitleIsTakenOverByFirstLiveMessage() {
        var state = SessionListReducer.reduce(
            SessionListState(),
            SessionListAction.RemoteSessionsReceived(
                listOf(GatewaySessionSummary(id = "blank", messageCount = 0, firstMessage = "(no messages)"))
            )
        )
        assertTrue(state.sessions.single().title.startsWith("Blank session"))
        // 首条 live 用户消息经 upsert 接管 blank 标题（与 merge 侧占位口径一致）。
        state = SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(
                SessionEvent("blank", 1, 200.0, GatewayEvent("user/message", text = "第一条消息内容")),
                insertedAtEpochSeconds = 200.0
            )
        )
        assertEquals("第一条消息内容", state.sessions.single().title)
    }

    @Test
    fun sessionListReducerUsesPlatformTimestampOnlyWhenItCreatesLocalSummary() {
        var state = SessionListReducer.reduce(
            SessionListState(),
            SessionListAction.KnownSessionAdded("known", insertedAtEpochSeconds = 123.0)
        )
        assertEquals(123.0, state.sessions.single().lastActivityEpochSeconds)

        state = SessionListReducer.reduce(
            state,
            SessionListAction.EventReceived(
                SessionEvent("chunk-only", 1, 200.0, GatewayEvent("assistant/chunk", text = "delta")),
                insertedAtEpochSeconds = 456.0
            )
        )
        assertEquals(456.0, state.sessions.first { it.id == "chunk-only" }.lastActivityEpochSeconds)
    }

    @Test
    fun `history request carries resetLeaf only when requested`() {
        val plain = GatewayRequests.history("s1").payload
        assertFalse(plain.contains("resetLeaf"), "常规 history 请求不应携带 resetLeaf")
        val reset = GatewayRequests.history("s1", resetLeaf = true).payload
        assertTrue("重连 catch-up 的 history 请求应带 resetLeaf，实际=${'$'}reset") { reset.contains("\"resetLeaf\":true") }
    }
}

private fun encodeBase64Url(value: String): String {
    val bytes = value.encodeToByteArray()
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val result = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index++].toInt() and 0xff
        val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        result.append(alphabet[first shr 2])
        result.append(alphabet[((first and 0x03) shl 4) or if (second >= 0) second shr 4 else 0])
        if (second >= 0) {
            result.append(alphabet[((second and 0x0f) shl 2) or if (third >= 0) third shr 6 else 0])
        }
        if (third >= 0) result.append(alphabet[third and 0x3f])
    }
    return result.toString()
}
