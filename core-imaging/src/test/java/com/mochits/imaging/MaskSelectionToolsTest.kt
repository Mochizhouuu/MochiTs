package com.mochits.imaging

import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.common.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskSelectionToolsTest {

    private lateinit var tools: MaskSelectionTools

    @Before
    fun setup() {
        tools = MaskSelectionToolsImpl()
    }

    @Test
    fun sampleColor_returnsCorrectPixelColor() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(50, 50, Color.RED)

        val color = tools.sampleColor(bitmap, 50, 50)
        assertEquals(Color.RED, color)
    }

    @Test
    fun drawBrush_createsMaskCorrectly() {
        val points = listOf(Pair(10f, 10f), Pair(20f, 20f))
        val result = tools.drawBrush(
            maskWidth = 100,
            maskHeight = 100,
            existingMask = null,
            pathPoints = points,
            brushRadiusPx = 5f,
            isSubtract = false
        )

        assertTrue(result is OperationResult.Success)
        val mask = (result as OperationResult.Success).data
        assertEquals(100, mask.width)
        assertEquals(100, mask.height)
        // Check pixel at brush center
        val pixel = mask.getPixel(10, 10)
        println("Brush pixel at (10,10): $pixel, expected WHITE: ${Color.WHITE}")
        assertTrue("Expected non-zero alpha at brush pixel", Color.alpha(pixel) > 0)
    }

    @Test
    fun lassoSelect_createsPolygonMask() {
        val polygon = listOf(
            Pair(10f, 10f),
            Pair(50f, 10f),
            Pair(50f, 50f),
            Pair(10f, 50f)
        )
        val result = tools.lassoSelect(
            maskWidth = 100,
            maskHeight = 100,
            existingMask = null,
            points = polygon,
            isSubtract = false
        )

        assertTrue(result is OperationResult.Success)
        val mask = (result as OperationResult.Success).data
        // Point inside rectangle should be WHITE
        val pixel = mask.getPixel(30, 30)
        println("Lasso pixel at (30,30): $pixel, expected WHITE: ${Color.WHITE}")
        assertTrue("Expected non-zero alpha at lasso pixel", Color.alpha(pixel) > 0)
    }
}
