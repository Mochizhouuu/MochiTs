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
        // Pusat sapuan harus PUTIH penuh, bukan sekadar alpha > 0
        assertEquals(Color.WHITE, mask.getPixel(10, 10))
        assertEquals(Color.WHITE, mask.getPixel(20, 20))
    }

    @Test
    fun drawBrush_singleDotDiameterMatchesTwoTimesRadius() {
        val result = tools.drawBrush(
            maskWidth = 100,
            maskHeight = 100,
            existingMask = null,
            pathPoints = listOf(Pair(50f, 50f)),
            brushRadiusPx = 5f,
            isSubtract = false
        )

        assertTrue(result is OperationResult.Success)
        val mask = (result as OperationResult.Success).data
        // Titik tunggal: diameter = 2 x radius. Dalam radius harus putih,
        // di luar radius+1 harus transparan (bug lama menghasilkan 4r).
        assertEquals(Color.WHITE, mask.getPixel(50, 50))
        assertEquals(Color.WHITE, mask.getPixel(50, 52))
        val outside = mask.getPixel(50, 57)
        assertEquals(0, Color.alpha(outside))
    }

    @Test
    fun lassoSelect_insideWhiteOutsideTransparent() {
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
        // Di dalam polygon PUTIH penuh; di luar polygon TRANSPARAN
        assertEquals(Color.WHITE, mask.getPixel(30, 30))
        assertEquals(Color.WHITE, mask.getPixel(15, 15))
        assertEquals(0, Color.alpha(mask.getPixel(70, 70)))
    }

    @Test
    fun lassoSelect_subtractMenghapusAreaTersasarTanpaResiduTepi() {
        // Mask awal penuh putih
        val base = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        base.eraseColor(Color.WHITE)

        val polygon = listOf(
            Pair(30f, 30f),
            Pair(60f, 30f),
            Pair(60f, 60f),
            Pair(30f, 60f)
        )
        val result = tools.lassoSelect(
            maskWidth = 100,
            maskHeight = 100,
            existingMask = base,
            points = polygon,
            isSubtract = true
        )

        assertTrue(result is OperationResult.Success)
        val mask = (result as OperationResult.Success).data
        // Interior polygon terhapus
        assertEquals(0, Color.alpha(mask.getPixel(45, 45)))
        // Luar polygon tetap putih
        assertEquals(Color.WHITE, mask.getPixel(10, 10))
        // Tepi polygon juga bersih - bug lama menyisakan cincin putih anti-alias
        assertEquals(0, Color.alpha(mask.getPixel(31, 31)))
        assertEquals(0, Color.alpha(mask.getPixel(59, 59)))
    }
}
