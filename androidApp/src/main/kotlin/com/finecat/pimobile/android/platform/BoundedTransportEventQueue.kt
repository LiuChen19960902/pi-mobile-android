package com.finecat.pimobile.android.platform

import com.finecat.pimobile.shared.platform.GatewayTransportEvent
import com.finecat.pimobile.shared.platform.GatewayTransportFrame
import com.finecat.pimobile.shared.platform.GatewayTransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow

internal class BoundedTransportEventQueue(
    private val maximumFrameBytes: Long,
    private val frameOverheadBytes: Long = 0L
) {
    // State 数量只由 transport 生命周期产生；使用无界控制通道可保证 failure 永不因
    // frame 背压而丢失，frame 本身仍由下方总字节硬上限约束。
    private val channel = Channel<GatewayTransportEvent>(Channel.UNLIMITED)
    private val budgetLock = Any()
    private var frameBytes = 0L

    // 预算在下游真正取走后才归还（finally）：驻留上限严格等于最大值，
    // 而非“最大值+单帧最大”。慢消费触发的是 offer 侧 overflow 关连接（既定语义）。
    val events: Flow<GatewayTransportEvent> = flow {
        channel.consumeAsFlow().collect { event ->
            try {
                emit(event)
            } finally {
                if (event is GatewayTransportEvent.Frame) {
                    synchronized(budgetLock) {
                        frameBytes -= event.value.queueWeight()
                    }
                }
            }
        }
    }

    fun offerFrame(frame: GatewayTransportFrame): Boolean {
        val weight = frame.queueWeight()
        synchronized(budgetLock) {
            if (frameBytes + weight > maximumFrameBytes) return false
            frameBytes += weight
        }
        if (channel.trySend(GatewayTransportEvent.Frame(frame)).isSuccess) return true
        synchronized(budgetLock) { frameBytes -= weight }
        return false
    }

    fun offerState(state: GatewayTransportState): Boolean =
        channel.trySend(GatewayTransportEvent.State(state)).isSuccess

    private fun GatewayTransportFrame.queueWeight(): Long =
        byteCount.toLong() + frameOverheadBytes
}
