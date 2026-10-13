package com.finecat.pimobile.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 表单指纹：把「测试通过」绑定到具体一份配置。
 *  只含影响连通性的字段——改了就得重测，纯显示字段改动不该逼用户重测。 */
class ProviderFingerprintTest {

    private fun draft(id: String, api: String = "openai-completions", name: String = "") =
        ProviderModelDraft(id = id, api = api, name = name)

    @Test
    fun baseUrlAndApiKeyChangesInvalidate() {
        val base = providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m1")))
        assertNotEquals(base, providerFingerprint("https://h.example/", "sk-a", listOf(draft("m1"))))
        assertNotEquals(base, providerFingerprint("https://h.example/v1", "sk-b", listOf(draft("m1"))))
    }

    @Test
    fun modelIdAndProtocolChangesInvalidate() {
        val base = providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m1")))
        assertNotEquals(base, providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m2"))))
        assertNotEquals(
            base,
            providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m1", api = "anthropic-messages")))
        )
        // 新增/删除模型同样影响连通性。
        assertNotEquals(
            base,
            providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m1"), draft("m2")))
        )
    }

    @Test
    fun displayOnlyChangesKeepFingerprint() {
        // 只改显示名/上下文窗口不应要求重测（指纹里不含这些字段）。
        val before = providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m1", name = "旧名")))
        val after = providerFingerprint("https://h.example/v1", "sk-a", listOf(draft("m1", name = "新名")))
        assertEquals(before, after)
    }
}
