package com.finecat.pimobile.android.ui.finecat.conversation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** 保留原始比例；未知尺寸用方形占位，横向长图也不超出单张可见宽度。 */
internal fun fineCatImageSize(width: Int, height: Int, maxWidth: Dp, maxHeight: Dp): DpSize {
    val ratio = if (width > 0 && height > 0) width.toFloat() / height else 1f
    val fittedHeight = minOf(maxHeight.value.coerceAtLeast(1f), maxWidth.value.coerceAtLeast(1f) / ratio)
    return DpSize((fittedHeight * ratio).dp, fittedHeight.dp)
}
