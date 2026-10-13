package com.finecat.pimobile.android.ui.finecat

import androidx.compose.ui.unit.dp
import com.finecat.pimobile.android.ui.finecat.conversation.fineCatImageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FineCatImageSizeTest {
    @Test fun landscapeAndPortraitRetainBothEdges() {
        val landscape = fineCatImageSize(4_000, 3_000, 320.dp, 96.dp)
        assertEquals(128f, landscape.width.value, 0.001f)
        assertEquals(96f, landscape.height.value, 0.001f)
        val portrait = fineCatImageSize(3_000, 4_000, 320.dp, 96.dp)
        assertEquals(72f, portrait.width.value, 0.001f)
        assertEquals(96f, portrait.height.value, 0.001f)
    }

    @Test fun panoramaAndNarrowScreenFitWithoutCropping() {
        val size = fineCatImageSize(10_000, 1_000, 240.dp, 96.dp)
        assertEquals(240f, size.width.value, 0.001f)
        assertEquals(24f, size.height.value, 0.001f)
        val narrow = fineCatImageSize(4_000, 3_000, 80.dp, 96.dp)
        assertEquals(80f, narrow.width.value, 0.001f)
        assertEquals(60f, narrow.height.value, 0.001f)
    }

    @Test fun incompleteDimensionsUseSquareInsteadOfDividingByZero() {
        for ((width, height) in listOf(0 to 0, 800 to 0, -1 to 400)) {
            val size = fineCatImageSize(width, height, 80.dp, 96.dp)
            assertEquals(80f, size.width.value, 0.001f)
            assertEquals(80f, size.height.value, 0.001f)
        }
    }

    @Test fun lightboxIsConstrainedByBothScreenWidthAndHeight() {
        val landscape = fineCatImageSize(4_000, 3_000, 328.dp, 600.dp)
        assertEquals(328f, landscape.width.value, 0.001f)
        assertEquals(246f, landscape.height.value, 0.001f)
        val portrait = fineCatImageSize(1_000, 4_000, 328.dp, 600.dp)
        assertEquals(150f, portrait.width.value, 0.001f)
        assertEquals(600f, portrait.height.value, 0.001f)
    }
}
