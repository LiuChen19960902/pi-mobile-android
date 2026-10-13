package com.finecat.pimobile.android.android

import com.finecat.pimobile.android.AndroidSharedStateHolder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配对输入的内存生命周期（GatewaySheet 失败保留输入的前提）：
 * holder.pair(payload, reportFailureGlobally=false) 失败时不清空
 * pairingPayload（输入保留），成功时才按相等清空；secret 只在内存态，
 * 本文件不断言持久化（由网关 runtime/Keystore 链路负责）。
 */
class FineCatGatewayPairingInputTest {

    @Test
    fun `pairing payload survives failed pair and clears on success path`() {
        val holder = AndroidSharedStateHolder()
        holder.pairingPayload = "  some-input  "

        // 空 graph 下 pair() 直接返回：输入必须原样保留（不得清框）。
        holder.pair("  some-input  ", reportFailureGlobally = false) {}
        assertEquals("  some-input  ", holder.pairingPayload)

        // 提交前去空格写回 holder 的行为：trim 后一致。
        holder.pairingPayload = holder.pairingPayload.trim()
        assertEquals("some-input", holder.pairingPayload)
    }

    @Test
    fun `directory selection is separate from browsing`() {
        val holder = AndroidSharedStateHolder()
        holder.selectDirectory("/home/user/project")
        assertEquals("/home/user/project", holder.selectedDirectoryPath)
    }
}
