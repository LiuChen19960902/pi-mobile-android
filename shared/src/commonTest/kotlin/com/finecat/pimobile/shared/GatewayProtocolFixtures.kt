package com.finecat.pimobile.shared

/**
 * pi-mobile-gateway pi 原生协议样本（pi-mobile-gateway/PROTOCOL.md §2/§4），
 * 与 GatewayDtos/GatewayWireDecoder/PiEventNormalizer 的 wire 形状对齐。
 */
object GatewayProtocolFixtures {
    /** pi event 帧省略 kind 时的兼容形状（GatewayWireDecoder 会补全 kind=event）。 */
    const val LIVE_EVENT_WITHOUT_KIND =
        """{"sessionId":"s1","seq":7,"time":1001.5,"event":{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"thinking","contentIndex":0}}}"""

    /** 43 字符无 padding Base64URL 的一次性配对码（PROTOCOL.md §2）。 */
    const val PAIRING_CODE = "0123456789abcdef0123456789abcdef0123456789"

    const val PAIRED =
        """{"kind":"paired","token":"long-lived-secret","device":{"id":"d1","name":"test desktop"}}"""

    const val HELLO =
        """{"kind":"hello","protocol":1,"authenticated":true,"port":3082,"clients":1,"capabilities":["ping","sessions","subscribe","message","abort","history","state"]}"""

    const val PI_SESSIONS =
        """{"kind":"sessions","items":[{"id":"s1","path":"/home/user/.pi/agent/sessions/s1.jsonl","cwd":"/tmp/pi-demo","name":null,"created":"2026-09-01T07:30:00.000Z","modified":"2026-09-01T07:36:16.694Z","messageCount":3,"firstMessage":"第一句话","running":false}]}"""

    const val PI_HISTORY_PAGE =
        """{"kind":"history","sessionId":"s1","messages":[],"entryIds":["e2","e3"],"hasMore":true,"oldestEntryId":"e2","bytes":1234,"isStreaming":false,"isCompacting":false}"""

    /** pi `models` 应答：只含已配置认证的模型，按 provider 分组（PROTOCOL.md §4.13）。 */
    const val PI_MODELS =
        """{"kind":"models","groups":[{"provider":"relayB","models":[{"id":"example-model","name":"example-model","contextWindow":1000000,"maxTokens":131072,"reasoning":true,"input":["text"],"thinkingLevels":["off","minimal","low","medium","high"]}]}]}"""

    /** pi `state` 应答：会话级模型状态快照（PROTOCOL.md §4.7）。 */
    const val PI_STATE =
        """{"kind":"state","sessionId":"s1","isStreaming":false,"isCompacting":false,"model":{"provider":"relayB","modelId":"example-model"},"thinkingLevel":"high","queuedMessages":{"steering":[],"followUp":[]},"contextUsage":{"percent":12.5,"contextWindow":1000000,"tokens":125000}}"""

    /** pi `set-model` 应答：成功即一次 state 快照（PROTOCOL.md §4.14）。 */
    const val PI_MODEL_SET =
        """{"kind":"model-set","sessionId":"s1","isStreaming":false,"isCompacting":false,"model":{"provider":"relayB","modelId":"example-model"},"thinkingLevel":"high","queuedMessages":{"steering":[],"followUp":[]},"contextUsage":null}"""

    const val PI_PROMPT_REJECTED =
        """{"kind":"error","code":"prompt-rejected","message":"无可用模型","requestType":"message","sessionId":"s1"}"""

    /** pi 事件帧：assistant 文本增量（PROTOCOL.md §4.5）。 */
    const val PI_EVENT_TEXT_DELTA =
        """{"kind":"event","sessionId":"s1","seq":9,"time":1786937355.25,"event":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"你好","contentIndex":0}}}"""

    /** pi 事件帧：回合落定（后台保活以此收尾）。 */
    const val PI_EVENT_AGENT_SETTLED =
        """{"kind":"event","sessionId":"s1","seq":10,"time":1786937356.0,"event":{"type":"agent_settled"}}"""
}
