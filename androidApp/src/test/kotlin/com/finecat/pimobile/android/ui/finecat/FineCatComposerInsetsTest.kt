package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.composer.composerBottomGapPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FineCatComposerInsetsTest {
    private fun cardBottomDistance(ime: Int, nav: Int, scale: Int = 1): Int =
        maxOf(ime, nav) + composerBottomGapPx(ime, nav, 8 * scale, 32 * scale)

    @Test fun settledPositionsKeepKeyboardAndNavigationClearance() {
        assertEquals(32, cardBottomDistance(0, 0))
        assertEquals(56, cardBottomDistance(0, 24))
        assertEquals(328, cardBottomDistance(320, 24))
    }

    @Test fun hidingImeNeverPassesTheFinalRestingPosition() {
        for (scale in 1..4) for (nav in listOf(0, 24 * scale, 48 * scale)) {
            val positions = (360 * scale downTo 0).map { cardBottomDistance(it, nav, scale) }
            val resting = nav + 32 * scale
            assertEquals(resting, positions.last())
            assertTrue("关闭途中不能先落到最终位置之下", positions.all { it >= resting })
            assertTrue("下降必须连续且不能回弹", positions.zipWithNext().all { (a, b) -> a - b in 0..1 })
        }
    }

    @Test fun showingImeUsesTheSameContinuousPath() {
        val positions = (0..360).map { cardBottomDistance(it, 24) }
        assertTrue(positions.zipWithNext().all { (a, b) -> b - a in 0..1 })
        assertEquals(positions, (360 downTo 0).map { cardBottomDistance(it, 24) }.reversed())
    }

    @Test fun keyboardZeroBoundaryHasNoTwentyFourDpJump() {
        assertEquals(cardBottomDistance(0, 24), cardBottomDistance(1, 24))
        assertEquals(cardBottomDistance(0, 0), cardBottomDistance(1, 0))
    }
}
