package com.mochits.app.ui.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ColorUtilsTest {

    @Test
    fun testParseHexColor_valid6Digit() {
        val parsed = ColorUtils.parseHexColor("#FF0000")
        assertNotNull(parsed)
        // Opaque Red: 0xFFFF0000 -> -65536
        assertEquals(0xFFFF0000.toInt(), parsed)
    }

    @Test
    fun testParseHexColor_valid8Digit() {
        val parsed = ColorUtils.parseHexColor("#8000FF00")
        assertNotNull(parsed)
        assertEquals(0x8000FF00.toInt(), parsed)
    }

    @Test
    fun testParseHexColor_invalidHex() {
        assertNull(ColorUtils.parseHexColor("invalid"))
        assertNull(ColorUtils.parseHexColor("#12345"))
    }

    @Test
    fun testColorToHex_includeAlpha() {
        val color = 0x80FF5722.toInt()
        val hex = ColorUtils.colorToHex(color, includeAlpha = true)
        assertEquals("#80FF5722", hex.uppercase())
    }

    @Test
    fun testColorToHex_excludeAlpha() {
        val color = 0xFFFF5722.toInt()
        val hex = ColorUtils.colorToHex(color, includeAlpha = false)
        assertEquals("#FF5722", hex.uppercase())
    }

    @Test
    fun testHsvToColorAndBack() {
        val redColor = ColorUtils.hsvToColor(0f, 1f, 1f, 1f)
        assertEquals(0xFFFF0000.toInt(), redColor)

        val greenColor = ColorUtils.hsvToColor(120f, 1f, 1f, 0.5f)
        val alphaGreen = (greenColor ushr 24) and 0xFF
        assertEquals(127, alphaGreen)
    }
}
