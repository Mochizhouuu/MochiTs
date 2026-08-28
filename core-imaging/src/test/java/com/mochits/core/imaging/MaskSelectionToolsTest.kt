package com.mochits.core.imaging

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskSelectionToolsTest {

    @Test
    fun testInitializationAndSize() {
        val tools = MaskSelectionTools(100, 200)
        assertEquals(100, tools.width)
        assertEquals(200, tools.height)
        assertNotNull(tools.maskBitmap)
    }

    @Test
    fun testResetSize() {
        val tools = MaskSelectionTools(100, 100)
        tools.resetSize(300, 400)
        assertEquals(300, tools.width)
        assertEquals(400, tools.height)
    }

    @Test
    fun testMagicWandSelect_solidColorArea() {
        val tools = MaskSelectionTools(50, 50)
        val srcBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        // Fill top half white (255,255,255) and bottom half black (0,0,0)
        for (y in 0 until 25) {
            for (x in 0 until 50) {
                srcBitmap.setPixel(x, y, Color.WHITE)
            }
        }
        for (y in 25 until 50) {
            for (x in 0 until 50) {
                srcBitmap.setPixel(x, y, Color.BLACK)
            }
        }

        assertFalse(tools.hasMask())

        // Tap white region with low tolerance
        tools.magicWandSelect(srcBitmap, Offset(10f, 10f), tolerance = 10f)

        assertTrue(tools.hasMask())

        // Check that white pixel region (e.g. 10, 10) is masked (alpha > 0)
        val maskBmp = tools.maskBitmap
        val maskedPixelVal = maskBmp.getPixel(10, 10) and 0xFF
        assertEquals(255, maskedPixelVal)

        // Check that black pixel region (e.g. 10, 30) is NOT masked (alpha == 0)
        val unmaskedPixelVal = maskBmp.getPixel(10, 30) and 0xFF
        assertEquals(0, unmaskedPixelVal)
    }

    @Test
    fun testMagicWandSelect_toleranceThreshold() {
        val tools = MaskSelectionTools(20, 20)
        val srcBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)

        // Pixel (0,0) is RGB(100, 100, 100), Pixel (1,0) is RGB(120, 100, 100)
        // Distance is 20
        srcBitmap.setPixel(0, 0, Color.rgb(100, 100, 100))
        srcBitmap.setPixel(1, 0, Color.rgb(120, 100, 100))

        // Low tolerance (10) should NOT select (1,0)
        tools.magicWandSelect(srcBitmap, Offset(0f, 0f), tolerance = 10f)
        var p1Val = tools.maskBitmap.getPixel(1, 0) and 0xFF
        assertEquals(0, p1Val)

        // Clear mask and try higher tolerance (30) which SHOULD select (1,0)
        tools.clearMask()
        tools.magicWandSelect(srcBitmap, Offset(0f, 0f), tolerance = 30f)
        p1Val = tools.maskBitmap.getPixel(1, 0) and 0xFF
        assertEquals(255, p1Val)
    }

    @Test
    fun testMagicWandSelect_edgeCases() {
        val tools = MaskSelectionTools(10, 10)
        val srcBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.RED)

        // Tap outside bounds (e.g., -5, -5 or 100, 100) should not crash
        tools.magicWandSelect(srcBitmap, Offset(-5f, -5f), tolerance = 20f)
        tools.magicWandSelect(srcBitmap, Offset(100f, 100f), tolerance = 20f)

        // Tap corner (0,0)
        tools.magicWandSelect(srcBitmap, Offset(0f, 0f), tolerance = 20f)
        assertTrue(tools.hasMask())
    }
}
