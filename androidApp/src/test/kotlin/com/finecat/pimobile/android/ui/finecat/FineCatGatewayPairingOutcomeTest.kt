package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.shared.gateway.GatewayConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 配对请求与连接状态分开：已有 CONNECTED 不能证明本次配对成功。 */
class FineCatGatewayPairingOutcomeTest {

    @Test
    fun `validation error wins over connection state`() {
        val outcome = pairingOutcomeText(
            connection = GatewayConnectionState.CONNECTED,
            didAttemptPair = true,
            validationError = "配对内容不是有效的 Base64URL 字符串",
            lastError = null,
        )!!
        assertEquals(true, outcome.isError)
        assertEquals("配对信息无效", outcome.text)
    }

    @Test
    fun `no attempt renders nothing`() {
        assertNull(
            pairingOutcomeText(
                connection = GatewayConnectionState.DISCONNECTED,
                didAttemptPair = false,
                validationError = null,
                lastError = null,
            )
        )
    }

    @Test
    fun `existing connection never claims this pairing succeeded`() {
        val ok = pairingOutcomeText(
            connection = GatewayConnectionState.CONNECTED,
            didAttemptPair = true,
            validationError = null,
            lastError = null,
        )!!
        assertEquals(false, ok.isError)
        assertEquals("已发起配对，请核对上方连接状态。", ok.text)
        // 连接中不报成功
        val pending = pairingOutcomeText(
            connection = GatewayConnectionState.CONNECTING,
            didAttemptPair = true,
            validationError = null,
            lastError = null,
        )!!
        assertEquals("正在提交配对信息…", pending.text)
    }

    @Test
    fun `failed maps known lastError to actionable text`() {
        val expired = pairingOutcomeText(
            connection = GatewayConnectionState.FAILED,
            didAttemptPair = true,
            validationError = null,
            lastError = "pairing-code-invalid",
        )!!
        assertEquals(true, expired.isError)
        assertEquals("配对码已失效，请重新生成", expired.text)

        val unreachable = pairingOutcomeText(
            connection = GatewayConnectionState.FAILED,
            didAttemptPair = true,
            validationError = null,
            lastError = "endpoint-unreachable",
        )!!
        assertEquals("无法连接网关，请检查网络", unreachable.text)
    }
}
