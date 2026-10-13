package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import com.finecat.pimobile.shared.protocol.JsonValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 一级行副标题的口径：「方言 · N 个模型 · M 项改动」。
 *
 * 副标题从 baseUrl 换成这三段是本轮改动（原型 `pvRow` 就是这么写的），
 * 这里钉住「M 项改动」的算法：各模型 `compat` **顶层键数**之和，不递归嵌套值，
 * 也不把 null 值算进去。
 */
class ProviderRowSubtitleTest {

    @Test
    fun overridesSumTopLevelCompatKeysAcrossModels() {
        val provider = provider(
            model("m1", compat("a", "b")),
            model("m2", compat("c")),
            model("m3", compat = null),
        )
        // 副标题最后一段是「3 项改动」
        assertTrue(providerRowSubtitle(provider).endsWith("3 项改动"))
    }

    @Test
    fun overridesIgnoreNestedObjectsAndNullValues() {
        // compat = {a: {x:1}, b: null} → 顶层只有 2 个键，嵌套里的 x 不单独计数
        val provider = provider(
            model(
                "m1",
                compat = JsonValue.ObjectValue(
                    mapOf(
                        "a" to JsonValue.ObjectValue(mapOf("x" to JsonValue.NumberValue(1.0))),
                        "b" to JsonValue.NullValue,
                    ),
                ),
            ),
        )
        assertTrue(providerRowSubtitle(provider).endsWith("2 项改动"))
    }

    @Test
    fun overridesZeroWhenNoCompatAnywhere() {
        assertFalse(providerRowSubtitle(provider(model("m1", compat = null))).contains("改动"))
    }

    private fun provider(vararg models: GatewayProviderModel) = GatewayProvider(
        id = "p1",
        name = "智谱",
        api = "openai-completions",
        models = models.toList(),
    )

    private fun model(id: String, compat: JsonValue?) = GatewayProviderModel(id = id, compat = compat)

    private fun compat(vararg keys: String): JsonValue =
        JsonValue.ObjectValue(keys.associateWith { JsonValue.BooleanValue(true) })
}
