package com.finecat.pimobile.android.ui

import com.finecat.pimobile.android.ui.waveDotLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent 卡片波条（waveDotLevels，Canvas 圆点波浪）的纯函数测试。 */
class WaveBrailleBarTest {
    @Test
    fun levelsHaveRequestedCellsAndStayInUnitRange() {
        assertEquals(6, waveDotLevels(0, 0).size)
        assertEquals(9, waveDotLevels(12, 3, cells = 9).size)
        for (frame in 0 until 40) {
            for (seed in 0 until 8) {
                assertTrue(waveDotLevels(frame, seed).all { it in 0f..1f })
            }
        }
    }

    @Test
    fun levelsAnimateAcrossFrames() {
        // 波形周期≈20 帧@80ms：24 帧内至少两种不同形态，否则没有动画感。
        val frames = (0 until 24).map { waveDotLevels(it, 1) }.toSet()
        assertTrue(frames.size > 1)
    }
}
