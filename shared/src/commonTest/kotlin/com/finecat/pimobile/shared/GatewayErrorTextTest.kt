package com.finecat.pimobile.shared

import com.finecat.pimobile.shared.gateway.GatewayErrorText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayErrorTextTest {
    @Test
    fun knownCausesAreDistinctAndConcise() {
        val cases = listOf(
            Triple("directories", "directory-permission-denied", "没有目录访问权限"),
            Triple("directories", "directory-not-found", "目录不存在"),
            Triple("directories", "directory-not-directory", "路径不是目录"),
            Triple("directory-create", "directory-name-invalid", "文件夹名称无效"),
            Triple("history", "history-cursor-invalid", "历史位置已变化，请重新加载"),
            Triple("input-completions", "input-timeout", "请求超时，请重试"),
            Triple("provider-put", "provider-config-write-failed", "保存未确认，请重新打开核对"),
            Triple("set-model", "not-connected", "网关未连接"),
            Triple("name", "bad-request", "会话名称无效"),
            Triple("provider-put", "internal", "Provider保存失败"),
            Triple("dequeue", "editor-unavailable", "输入框被占用或不支持取回排队消息"),
            Triple("dequeue", "dequeue-unconfirmed", "排队消息去向未确认，请在电脑查看"),
        )
        for ((request, code, expected) in cases) {
            val text = GatewayErrorText.request(request, code)
            assertEquals(expected, text)
            assertTrue(text.length <= 24)
        }
    }

    @Test
    fun treeNavigationEditorFailureDoesNotPretendToBeAQueueFailure() {
        val text = GatewayErrorText.request("switch-branch", "editor-unavailable")
        assertEquals("会话编辑器暂不可用，请重载原生 Pi", text)
        assertFalse(text.contains("排队"))
        assertEquals("输入框被占用或不支持取回排队消息", GatewayErrorText.request("dequeue", "editor-unavailable"))
    }

    @Test
    fun unknownErrorsNeverGuessPermissionsOrEchoInput() {
        for (code in listOf("internal", "private-key-at-/secret/path", "私密错误与token", "")) {
            val text = GatewayErrorText.request("directories", code)
            assertEquals("目录加载失败，请重试", text)
            assertFalse(text.contains("权限"))
            assertFalse(text.contains(code) && code.isNotEmpty())
        }
        assertEquals("Provider配置无效", GatewayErrorText.request("provider-put", "bad-request"))
        assertEquals("无法连接网关，请检查网络", GatewayErrorText.connection("unknown-private-detail"))
    }

    @Test
    fun onlyConnectionFailuresOfferReconnect() {
        assertTrue(GatewayErrorText.isConnectionFailure("transport-failed"))
        for (code in listOf("internal", "directory-permission-denied", "image-preprocess-failed", "authentication-required")) {
            assertFalse(GatewayErrorText.isConnectionFailure(code))
        }
        assertEquals("图片无法读取，请重新选择", GatewayErrorText.local("image-preprocess-failed"))
        assertEquals("操作失败，请重试", GatewayErrorText.local("unknown-private-detail"))
        assertEquals("操作失败，请重试", GatewayErrorText.local("私密路径 /secret/path 和 token"))
    }
}
