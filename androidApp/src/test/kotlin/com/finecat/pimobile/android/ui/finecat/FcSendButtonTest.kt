package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.composer.sendButtonStops
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送位（同一位置发送/停止）动作判据：忙时有草稿必须发送（门面
 * capturePendingDispatch 吸附成待派发条），无草稿才停止；压缩中等 pi 会拒发
 * 的状态保持停止语义。hasText 由调用方按“文本或已准备图片”给出。
 */
class FcSendButtonTest {
    @Test
    fun `busy with draft dispatches instead of stopping`() {
        // 流式中带文本/图片：点击走 onSend（不是 onStop）。
        assertFalse(sendButtonStops(running = true, hasText = true, dispatchWhileRunning = true))
        // 流式中无草稿：保持方点停止。
        assertTrue(sendButtonStops(running = true, hasText = false, dispatchWhileRunning = true))
        // 空闲点击一律发送。
        assertFalse(sendButtonStops(running = false, hasText = true, dispatchWhileRunning = true))
        assertFalse(sendButtonStops(running = false, hasText = false, dispatchWhileRunning = true))
    }

    @Test
    fun `pi rejected busy states keep stop semantics`() {
        // 压缩中（门面拒 prompt，故 dispatchWhileRunning=false）：带草稿也点停止。
        assertTrue(sendButtonStops(running = true, hasText = true, dispatchWhileRunning = false))
        assertTrue(sendButtonStops(running = true, hasText = false, dispatchWhileRunning = false))
    }
}
