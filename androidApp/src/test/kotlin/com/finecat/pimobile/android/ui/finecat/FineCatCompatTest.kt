package com.finecat.pimobile.android.ui.finecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F12 兼容参数编辑的纯逻辑（`DEC-F09-COMPAT`）。
 *
 * 覆盖三件事：字段集合与计数必须与网关/SDK 冻结的键集一致；「未设置」不能被当成
 * `false`；wire 顺序必须稳定到能逐字比对。
 */
class FineCatCompatTest {

    @Test
    fun planCountsOnlyVisibleConfiguredKeys() {
        val compat = mapOf(
            "supportsStore" to "true",       // 本协议可编辑，已设置
            "thinkingFormat" to "deepseek",  // 本协议可编辑，已设置
            "requiresToolResultName" to null,
            "forceAdaptiveThinking" to "true", // 别的协议的键，编辑区看不见
        )
        val plan = FineCatCompat.plan("openai-completions", compat)
        assertEquals(2, plan.configured)
        assertEquals(listOf("forceAdaptiveThinking"), plan.offProtocol)
        assertEquals(21, plan.keys.size)
        assertEquals("supportsDeveloperRole", plan.keys.first())
    }

    @Test
    fun unsetValuesAreNeverCountedOrShownAsFalse() {
        val compat = mapOf("supportsStore" to null, "supportsDeveloperRole" to "false")
        assertEquals(1, FineCatCompat.plan("openai-completions", compat).configured)
        assertEquals(FineCatCompat.UNSET, FineCatCompat.marker("supportsStore", null))
        assertEquals("关", FineCatCompat.marker("supportsStore", "false"))
        assertEquals("开", FineCatCompat.marker("supportsStore", "true"))
        assertEquals("deepseek", FineCatCompat.marker("thinkingFormat", "deepseek"))
        assertFalse(FineCatCompat.UNSET == FineCatCompat.OFF)
        assertFalse(FineCatCompat.UNSET == FineCatCompat.ON)
    }

    /** 没有 compat 编辑区的协议：事实陈述，不给假入口。 */
    @Test
    fun googleGenerativeAiHasNoCompatEditor() {
        assertFalse(FineCatCompat.hasEditor("google-generative-ai"))
        assertTrue(FineCatCompat.plan("google-generative-ai", emptyMap()).keys.isEmpty())
        assertTrue(FineCatCompat.unsupportedHint("google-generative-ai").contains("不使用 compat"))
        assertTrue(FineCatCompat.hasEditor("openai-responses"))
    }

    @Test
    fun enumKeysAreDistinguishedFromBooleanKeys() {
        assertTrue(FineCatCompat.isEnumKey("thinkingFormat"))
        assertEquals(
            listOf("max_completion_tokens", "max_tokens"),
            FineCatCompat.choices("maxTokensField"),
        )
        assertFalse(FineCatCompat.isEnumKey("supportsStore"))
        assertTrue(FineCatCompat.choices("supportsStore").isEmpty())
    }

    @Test
    fun setValueKeepsEveryOtherKey() {
        val before = mapOf("supportsStore" to "false", "thinkingFormat" to "deepseek")
        val after = FineCatCompat.setValue(before, "supportsStore", "true")
        assertEquals(mapOf("supportsStore" to "true", "thinkingFormat" to "deepseek"), after)
        // 恢复默认 = 值置 null（wire 上表现为省略该键），其余键一个不少
        val cleared = FineCatCompat.setValue(after, "supportsStore", null)
        assertEquals(mapOf("supportsStore" to null, "thinkingFormat" to "deepseek"), cleared)
    }

    /**
     * wire 顺序必须冻结成协议键集声明顺序，否则同一份草稿每次序列化出的 JSON 键序不同，
     * 验收只能比集合、没法逐字比。残留键按字典序追加，不得静默丢。
     */
    @Test
    fun orderedForWireIsStableAndKeepsOffProtocolKeys() {
        val shuffled = linkedMapOf(
            "sessionAffinityFormat" to "openai",
            "supportsStore" to "true",
            "forceAdaptiveThinking" to "false",
            "supportsDeveloperRole" to "true",
        )
        val ordered = FineCatCompat.orderedForWire("openai-completions", shuffled)
        assertEquals(
            listOf(
                "supportsDeveloperRole",
                "supportsStore",
                "sessionAffinityFormat",
                "forceAdaptiveThinking",
            ),
            ordered.keys.toList(),
        )
        // 值是 null 的键不进 wire（网关也直接跳过 null 条目），但要在草稿里留着
        val withUnset = FineCatCompat.orderedForWire(
            "openai-completions",
            linkedMapOf("supportsStore" to null, "maxTokensField" to "max_tokens"),
        )
        assertEquals(listOf("maxTokensField"), withUnset.keys.toList())
        assertNull(FineCatCompat.cleared()["supportsStore"])
        assertTrue(FineCatCompat.cleared().isEmpty())
    }
}