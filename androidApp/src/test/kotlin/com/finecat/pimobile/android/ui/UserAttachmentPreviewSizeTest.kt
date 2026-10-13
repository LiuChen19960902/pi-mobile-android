package com.finecat.pimobile.android.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class UserAttachmentPreviewSizeTest {
    @Test
    fun squareImageUsesTheSameCompactMaximum() {
        assertEquals(
            UserAttachmentPreviewSize(width = 128.dp, height = 128.dp),
            userSingleAttachmentPreviewSize(4_000, 4_000, 337.dp)
        )
    }

    @Test
    fun landscapeImageKeepsAspectRatioWithinPreviewMaximum() {
        val size = userSingleAttachmentPreviewSize(4_000, 3_000, 337.dp)
        // 比例运算保留小数 dp，断言允许 Float 的舍入误差。
        assertEquals(128f, size.width.value, 0.001f)
        assertEquals(96f, size.height.value, 0.001f)
    }

    @Test
    fun portraitImageUsesMaximumHeightWithoutCropping() {
        val size = userSingleAttachmentPreviewSize(3_000, 4_000, 337.dp)
        assertEquals(96f, size.width.value, 0.001f)
        assertEquals(128f, size.height.value, 0.001f)
    }

    @Test
    fun narrowViewportConstrainsWidthAndPreservesRatio() {
        assertEquals(
            UserAttachmentPreviewSize(width = 80.dp, height = 60.dp),
            userSingleAttachmentPreviewSize(4_000, 3_000, 80.dp)
        )
    }

    @Test
    fun unknownSourceSizeUsesTheCompactPlaceholder() {
        assertEquals(
            UserAttachmentPreviewSize(width = 128.dp, height = 128.dp),
            userSingleAttachmentPreviewSize(0, 0, 337.dp)
        )
        assertEquals(
            UserAttachmentPreviewSize(width = 80.dp, height = 80.dp),
            userSingleAttachmentPreviewSize(0, 0, 80.dp)
        )
    }

    @Test
    fun smallImageIsNotEnlargedToTheMaximum() {
        assertEquals(
            UserAttachmentPreviewSize(width = 40.dp, height = 30.dp),
            userSingleAttachmentPreviewSize(40, 30, 337.dp)
        )
    }
}
