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

    private fun getAlpha(pixel: Int): Int = (pixel ushr 24) or (pixel and 0xFF)

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
        val maskedPixelVal = getAlpha(maskBmp.getPixel(10, 10))
        assertEquals(255, maskedPixelVal)

        // Check that black pixel region (e.g. 10, 30) is NOT masked (alpha == 0)
        val unmaskedPixelVal = getAlpha(maskBmp.getPixel(10, 30))
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
        var p1Val = getAlpha(tools.maskBitmap.getPixel(1, 0))
        assertEquals(0, p1Val)

        // Clear mask and try higher tolerance (10%) which SHOULD select (1,0)
        tools.clearMask()
        tools.magicWandSelect(srcBitmap, Offset(0f, 0f), tolerance = 10f)
        p1Val = getAlpha(tools.maskBitmap.getPixel(1, 0))
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
                if (getAlpha(maskBmp1.getPixel(x, y)) > 0) lowTolCount++
            }
        }

        // High tolerance (e.g. 50%) from center (25,25)
        tools.clearMask()
        tools.magicWandSelect(srcBitmap, Offset(25f, 25f), tolerance = 50f)
        var highTolCount = 0
        val maskBmp2 = tools.maskBitmap
        for (y in 0 until 50) {
            for (x in 0 until 50) {
                if (getAlpha(maskBmp2.getPixel(x, y)) > 0) highTolCount++
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
                if (getAlpha(tools.maskBitmap.getPixel(x, y)) > 0) unexpandedCount++
            }
        }
        assertEquals(4, unexpandedCount)

        // Apply expandPixels = 5 without re-tapping
        tools.applyExpand(expandPixels = 5)

        var expandedCount = 0
        for (y in 0 until 30) {
            for (x in 0 until 30) {
                if (getAlpha(tools.maskBitmap.getPixel(x, y)) > 0) expandedCount++
            }
        }

        assertTrue("Expanded mask pixel count ($expandedCount) must be significantly larger than unexpanded ($unexpandedCount)", expandedCount > unexpandedCount * 5)

        // Check pixel at radius ~4 from center (14, 14) is now selected
        val pixelNearEdge = getAlpha(tools.maskBitmap.getPixel(10, 14))
        assertEquals(255, pixelNearEdge)

        // Reset expandPixels back to 0 without re-tapping
        tools.applyExpand(expandPixels = 0)
        var resetCount = 0
        for (y in 0 until 30) {
            for (x in 0 until 30) {
                if (getAlpha(tools.maskBitmap.getPixel(x, y)) > 0) resetCount++
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

        assertEquals(255, getAlpha(tools.rawMaskBitmap.getPixel(15, 15)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(15, 15)))
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(75, 75)))
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(50, 50)))

        // 2. Expand Object A by 5px
        tools.applyExpand(expandPixels = 5)
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(5, 15)))
        assertEquals(0, getAlpha(tools.rawMaskBitmap.getPixel(5, 15)))

        // 3. Select Object B (different object) with expand = 5.
        // Semantik menumpuk: ketuk baru ditambahkan ke seleksi lama.
        tools.magicWandSelect(srcBitmap, Offset(75f, 75f), tolerance = 10f, expandPixels = 5)

        // Verify Object A is KEPT and Object B is added
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(15, 15)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(75, 75)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(65, 75)))

        // CRITICAL CHECK: Background pixel at (50, 50) MUST NOT be selected
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(50, 50)))
        assertEquals(0, getAlpha(tools.rawMaskBitmap.getPixel(50, 50)))

        // 4. Reset expand back to 0
        tools.applyExpand(expandPixels = 0)

        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(15, 15)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(75, 75)))
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(5, 15)))
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(65, 75)))
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

    @Test
    fun magicWand_singlePixel_shouldNotSelectBackground() {
        val src = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(8, 8, Color.BLACK)
        }

        val tools = MaskSelectionTools(16, 16)

        tools.magicWandSelect(
            srcBitmap = src,
            point = Offset(8f, 8f),
            tolerance = 0f,
            expandPixels = 0
        )

        assertEquals(
            255,
            (tools.maskBitmap.getPixel(8, 8) ushr 24) and 0xFF
        )
        assertEquals(
            0,
            (tools.maskBitmap.getPixel(0, 0) ushr 24) and 0xFF
        )
    }

    @Test
    fun testMagicWandSelect_thinLineEdgeAware() {
        // Create 30x30 bitmap with white background and a 1px black line at x=15
        val tools = MaskSelectionTools(30, 30)
        val srcBitmap = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)
        for (y in 0 until 30) {
            srcBitmap.setPixel(15, y, Color.BLACK)
        }

        // Tap left region (5, 15) with gapRadius=1
        tools.magicWandSelect(srcBitmap, Offset(5f, 15f), tolerance = 10f, gapRadius = 1)

        // Left region should be masked (255)
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(5, 15)))
        // Right region across the black line (20, 15) must NOT be masked
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(20, 15)))
    }

    @Test
    fun testMagicWandSelect_transparentVSBlackAlphaHandling() {
        val tools = MaskSelectionTools(20, 20)
        val srcBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        // Fill top half transparent (0,0,0,0) and bottom half solid black (255,0,0,0)
        for (y in 0 until 10) {
            for (x in 0 until 20) {
                srcBitmap.setPixel(x, y, Color.TRANSPARENT)
            }
        }
        for (y in 10 until 20) {
            for (x in 0 until 20) {
                srcBitmap.setPixel(x, y, Color.BLACK)
            }
        }

        // Tap transparent region (5, 5) with low tolerance
        tools.magicWandSelect(srcBitmap, Offset(5f, 5f), tolerance = 10f)

        // Transparent area masked
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(5, 5)))
        // Solid black area across alpha boundary must NOT be masked
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(5, 15)))
    }

    @Test
    fun testMagicWandSelect_contiguousVSGlobal() {
        val tools = MaskSelectionTools(50, 50)
        val srcBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)

        // Two separated red squares (10..19, 10..19) and (30..39, 30..39)
        for (y in 10 until 20) {
            for (x in 10 until 20) {
                srcBitmap.setPixel(x, y, Color.RED)
            }
        }
        for (y in 30 until 40) {
            for (x in 30 until 40) {
                srcBitmap.setPixel(x, y, Color.RED)
            }
        }

        // 1. Contiguous tap on square A (15, 15)
        tools.magicWandSelect(srcBitmap, Offset(15f, 15f), tolerance = 10f, isGlobal = false, wandMode = 0)
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(15, 15)))
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(35, 35)))

        // 2. Global tap on square A (15, 15)
        tools.clearMask()
        tools.magicWandSelect(srcBitmap, Offset(15f, 15f), tolerance = 10f, isGlobal = true, wandMode = 0)
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(15, 15)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(35, 35)))
    }

    @Test
    fun testMagicWandSelect_modesReplaceAddSubtract() {
        val tools = MaskSelectionTools(50, 50)
        val srcBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)

        // Red square at 0..24 x 0..24
        for (y in 0 until 25) {
            for (x in 0 until 25) {
                srcBitmap.setPixel(x, y, Color.RED)
            }
        }
        // Blue square at 20..44 x 20..44
        for (y in 20 until 45) {
            for (x in 20 until 45) {
                srcBitmap.setPixel(x, y, Color.BLUE)
            }
        }

        // 1. Mode 0 (REPLACE): select Red
        tools.magicWandSelect(srcBitmap, Offset(10f, 10f), tolerance = 10f, wandMode = 0)
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(10, 10)))

        // Mode 0 (REPLACE): select Blue -> Red selection cleared
        tools.magicWandSelect(srcBitmap, Offset(30f, 30f), tolerance = 10f, wandMode = 0)
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(10, 10)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(30, 30)))

        // 2. Mode 1 (ADD): add Red back
        tools.magicWandSelect(srcBitmap, Offset(10f, 10f), tolerance = 10f, wandMode = 1)
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(10, 10)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(30, 30)))

        // 3. Mode 2 (SUBTRACT): subtract Red
        tools.magicWandSelect(srcBitmap, Offset(10f, 10f), tolerance = 10f, wandMode = 2)
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(10, 10)))
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(30, 30)))
    }

    @Test
    fun testMagicWandSelect_feathering() {
        val tools = MaskSelectionTools(40, 40)
        val srcBitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)
        for (y in 0 until 20) {
            for (x in 0 until 40) {
                srcBitmap.setPixel(x, y, Color.BLACK)
            }
        }

        // Select with featherRadius = 5f
        tools.magicWandSelect(srcBitmap, Offset(10f, 10f), tolerance = 10f, featherRadius = 5f)

        // Inside selection center
        assertEquals(255, getAlpha(tools.maskBitmap.getPixel(10, 5)))
        // Outside selection
        assertEquals(0, getAlpha(tools.maskBitmap.getPixel(10, 35)))

        // Edge transition pixel near y=20 should have intermediate alpha value (between 0 and 255)
        val edgeAlpha = getAlpha(tools.maskBitmap.getPixel(10, 20))
        assertTrue("Feathered edge alpha ($edgeAlpha) should be soft (> 0 and < 255)", edgeAlpha in 1..254)
    }

    @Test
    fun testMagicWandSelect_largeImagePerformance() {
        val size = 2400
        val tools = MaskSelectionTools(size, size)
        val srcBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(Color.WHITE)

        val startTime = System.currentTimeMillis()
        val outcome = tools.magicWandSelect(srcBitmap, Offset(100f, 100f), tolerance = 10f)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Large image magic wand must succeed without error", outcome.ok)
        assertTrue("Execution time on large bitmap ($size x $size) should be reasonable (was ${elapsed}ms)", elapsed < 10000)
    }
}
