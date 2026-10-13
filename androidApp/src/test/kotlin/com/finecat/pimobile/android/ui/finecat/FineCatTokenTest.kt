package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ui.finecat.theme.darkFineCatColors
import com.finecat.pimobile.android.ui.finecat.theme.fineCatType
import androidx.compose.ui.graphics.Color
import com.finecat.pimobile.android.ui.finecat.theme.lightFineCatColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1 的静态部分：亮暗两套同名 token 值不同、关键字号不被圆整。
 * 视觉与点击部分由 FineCatGalleryDeviceTest 在模拟器上取证。
 */
class FineCatTokenTest {

    @Test
    fun `light and dark tokens differ for the same names`() {
        val light = lightFineCatColors()
        val dark = darkFineCatColors()
        assertNotEquals(light.bg, dark.bg)
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.surface2, dark.surface2)
        assertNotEquals(light.surface3, dark.surface3)
        assertNotEquals(light.text, dark.text)
        assertNotEquals(light.text2, dark.text2)
        assertNotEquals(light.text3, dark.text3)
        assertNotEquals(light.line, dark.line)
        assertNotEquals(light.brand, dark.brand)
        assertNotEquals(light.ink, dark.ink)
        assertNotEquals(light.ok, dark.ok)
        assertNotEquals(light.err, dark.err)
        assertEquals(light.isDark, false)
        assertEquals(dark.isDark, true)
    }

    @Test
    fun `key token hex values are locked to styles css`() {
        val light = lightFineCatColors()
        val dark = darkFineCatColors()
        // :root
        assertEquals(Color(0xFFFAFAFA), light.bg)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFFF0F0EE), light.surface2)
        assertEquals(Color(0xFFF8F8F6), light.surfaceFloat)
        assertEquals(Color(0xFF1A1A1C), light.text)
        assertEquals(Color(0xFF63636A), light.text2)
        assertEquals(Color(0xFF2F5FE0), light.brand)
        assertEquals(Color(0xFF1A1A1C), light.ink)
        assertEquals(Color(0xFF1A9A6C), light.ok)
        assertEquals(Color(0xFFCC3F3F), light.err)
        // [data-theme=dark]
        assertEquals(Color(0xFF0C0C0E), dark.bg)
        assertEquals(Color(0xFF17171A), dark.surface)
        assertEquals(Color(0xFF1C1C20), dark.surfaceFloat)
        assertEquals(Color(0xFFF2F2F3), dark.text)
        assertEquals(Color(0xFF6F97FF), dark.brand)
        assertEquals(Color(0xFFF2F2F3), dark.ink)
        assertEquals(Color(0xFF35C48C), dark.ok)
        assertEquals(Color(0xFFF06A6A), dark.err)
        // alpha 精确落 CSS rgba（Color 按 8bit 量化，容差 1/255）
        val q = 1f / 255f
        assertEquals(0.34f, light.overlay.alpha, q)
        assertEquals(0.60f, dark.overlay.alpha, q)
        assertEquals(0.10f, light.brandSoft.alpha, q)
    }

    @Test
    fun `ink and brand stay separate in both themes`() {
        listOf(lightFineCatColors(), darkFineCatColors()).forEach { c ->
            assertNotEquals(c.ink, c.brand)
            assertNotEquals(c.onInk, c.text)
        }
    }

    @Test
    fun `bubble 15 and prose 17 are kept unrounded`() {
        val type = fineCatType()
        assertEquals(15f, type.bubble.fontSize.value, 0f)
        assertEquals(17f, type.prose.fontSize.value, 0f)
        assertEquals(16f, type.body.fontSize.value, 0f)
        // 气泡/正文/正文行高按原型倍率
        assertEquals(23.25f, type.bubble.lineHeight?.value ?: 0f, 0.01f)
        assertTrue(type.prose.lineHeight!!.value > type.body.lineHeight!!.value)
    }
}
