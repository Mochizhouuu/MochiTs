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
        // Euclidean distance is sqrt(20^2) = 20.
        // Standard full scale (0..441.673): distance 20 is ~4.5% of max distance.
        srcBitmap.setPixel(0, 0, Color.rgb(100, 100, 100))
        srcBitmap.setPixel(1, 0, Color.rgb(120, 100, 100))

        // Low tolerance (2%) should NOT select (1,0)
        tools.magicWandSelect(srcBitmap, Offset(0f, 0f), tolerance = 2f)
        var p1Val = tools.maskBitmap.getPixel(1, 0) and 0xFF
        assertEquals(0, p1Val)

        // Clear mask and try higher tolerance (10%) which SHOULD select (1,0)
        tools.clearMask()
        tools.magicWandSelect(srcBitmap, Offset(0f, 0f), tolerance = 10f)
        p1Val = tools.maskBitmap.getPixel(1, 0) and 0xFF
        assertEquals(255, p1Val)
    }

    @Test
    fun testMagicWandSelect_antiAliasedGradientSelectionArea() {
        // Create a 50x50 bitmap with radial gradient simulating font anti-aliasing edge
        val tools = MaskSelectionTools(50, 50)
        val srcBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val centerX = 25f
        val centerY = 25f

        for (y in 0 until 50) {
            for (x in 0 until 50) {
                val dist = kotlin.math.hypot(x - centerX, y - centerY)
                // Color fades from Black (0,0,0) at center to White (255,255,255) at dist = 25
                val factor = (dist / 25f).coerceIn(0f, 1f)
                val c = (factor * 255).toInt()
                srcBitmap.setPixel(x, y, Color.rgb(c, c, c))
            }
        }

        // Low tolerance (e.g. 5%) from center (25,25)
        tools.magicWandSelect(srcBitmap, Offset(25f, 25f), tolerance = 5f)
        var lowTolCount = 0
        val maskBmp1 = tools.maskBitmap
        for (y in 0 until 50) {
            for (x in 0 until 50) {
                if ((maskBmp1.getPixel(x, y) and 0xFF) > 0) lowTolCount++
            }
        }

        // High tolerance (e.g. 50%) from center (25,25)
        tools.clearMask()
        tools.magicWandSelect(srcBitmap, Offset(25f, 25f), tolerance = 50f)
        var highTolCount = 0
        val maskBmp2 = tools.maskBitmap
        for (y in 0 until 50) {
            for (x in 0 until 50) {
                if ((maskBmp2.getPixel(x, y) and 0xFF) > 0) highTolCount++
            }
        }

        // Higher tolerance must select significantly more pixels than low tolerance
        assertTrue("High tolerance count ($highTolCount) must be > low tolerance count ($lowTolCount)", highTolCount > lowTolCount * 2)
    }

    @Test
    fun testMagicWandSelect_expandPixelsDilate() {
        // Create a 30x30 bitmap with a single 2x2 solid pixel block in the middle (14..15, 14..15)
        val tools = MaskSelectionTools(30, 30)
        val srcBitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)
        srcBitmap.setPixel(14, 14, Color.BLACK)
        srcBitmap.setPixel(15, 14, Color.BLACK)
        srcBitmap.setPixel(14, 15, Color.BLACK)
        srcBitmap.setPixel(15, 15, Color.BLACK)

        // Select with expandPixels = 0
        tools.magicWandSelect(srcBitmap, Offset(14f, 14f), tolerance = 10f, expandPixels = 0)

        var unexpandedCount = 0
        for (y in 0 until 30) {
            for (x in 0 until 30) {
                if ((tools.maskBitmap.getPixel(x, y) and 0xFF) > 0) unexpandedCount++
            }
        }
        assertEquals(4, unexpandedCount)

        // Apply expandPixels = 5 without re-tapping
        tools.applyExpand(expandPixels = 5)

        var expandedCount = 0
        for (y in 0 until 30) {
            for (x in 0 until 30) {
                if ((tools.maskBitmap.getPixel(x, y) and 0xFF) > 0) expandedCount++
            }
        }

        assertTrue("Expanded mask pixel count ($expandedCount) must be significantly larger than unexpanded ($unexpandedCount)", expandedCount > unexpandedCount * 5)

        // Check pixel at radius ~4 from center (14, 14) is now selected
        val pixelNearEdge = tools.maskBitmap.getPixel(10, 14) and 0xFF
        assertEquals(255, pixelNearEdge)

        // Reset expandPixels back to 0 without re-tapping
        tools.applyExpand(expandPixels = 0)
        var resetCount = 0
        for (y in 0 until 30) {
            for (x in 0 until 30) {
                if ((tools.maskBitmap.getPixel(x, y) and 0xFF) > 0) resetCount++
            }
        }
        assertEquals(4, resetCount)
    }

    @Test
    fun testMagicWandSelect_multipleTapsWithExpandAndResetExpand() {
        val tools = MaskSelectionTools(100, 100)
        val srcBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)

        // Draw Object A (black 10x10 square at 10..19, 10..19)
        for (y in 10 until 20) {
            for (x in 10 until 20) {
                srcBitmap.setPixel(x, y, Color.BLACK)
            }
        }

        // Draw Object B (black 10x10 square at 70..79, 70..79)
        for (y in 70 until 80) {
            for (x in 70 until 80) {
                srcBitmap.setPixel(x, y, Color.BLACK)
            }
        }

        // 1. Select Object A with expand = 0
        tools.magicWandSelect(srcBitmap, Offset(15f, 15f), tolerance = 10f, expandPixels = 0)

        assertEquals(255, tools.rawMaskBitmap.getPixel(15, 15) and 0xFF)
        assertEquals(255, tools.maskBitmap.getPixel(15, 15) and 0xFF)
        assertEquals(0, tools.maskBitmap.getPixel(75, 75) and 0xFF)
        assertEquals(0, tools.maskBitmap.getPixel(50, 50) and 0xFF)

        // 2. Expand Object A by 5px
        tools.applyExpand(expandPixels = 5)
        assertEquals(255, tools.maskBitmap.getPixel(5, 15) and 0xFF)
        assertEquals(0, tools.rawMaskBitmap.getPixel(5, 15) and 0xFF)

        // 3. Select Object B (different object) with expand = 5
        tools.magicWandSelect(srcBitmap, Offset(75f, 75f), tolerance = 10f, expandPixels = 5)

        // Verify Object A (expanded) and Object B (expanded) are both selected
        assertEquals(255, tools.maskBitmap.getPixel(15, 15) and 0xFF)
        assertEquals(255, tools.maskBitmap.getPixel(75, 75) and 0xFF)
        assertEquals(255, tools.maskBitmap.getPixel(65, 75) and 0xFF)

        // CRITICAL CHECK: Background pixel at (50, 50) MUST NOT be selected
        assertEquals(0, tools.maskBitmap.getPixel(50, 50) and 0xFF)
        assertEquals(0, tools.rawMaskBitmap.getPixel(50, 50) and 0xFF)

        // 4. Reset expand back to 0
        tools.applyExpand(expandPixels = 0)

        assertEquals(255, tools.maskBitmap.getPixel(15, 15) and 0xFF)
        assertEquals(255, tools.maskBitmap.getPixel(75, 75) and 0xFF)
        assertEquals(0, tools.maskBitmap.getPixel(5, 15) and 0xFF)
        assertEquals(0, tools.maskBitmap.getPixel(65, 75) and 0xFF)
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
