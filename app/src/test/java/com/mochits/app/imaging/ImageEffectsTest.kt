package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEffectsTest {

    private fun applyFilter(src: Bitmap, grayscale: Float, brightness: Float, contrast: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = ImageEffects.imageColorFilter(grayscale, brightness, contrast)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    @Test
    fun neutralParams_returnNullFilter() {
        assertNull(ImageEffects.imageColorFilter(0f, 0f, 1f))
    }

    @Test
    fun fullGrayscale_equalizesChannels() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.RED)
        val out = applyFilter(src, 1f, 0f, 1f)
        val px = out.getPixel(0, 0)
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        assertEquals(r, g)
        assertEquals(g, b)
        // Luminansi merah murni = 255 * 0.213 ≈ 54 (koefisien grayscale Android).
        assertTrue(r in 45..65)
    }

    @Test
    fun brightness_shiftsChannels() {
        val src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.rgb(100, 100, 100))
        val brighter = applyFilter(src, 0f, 0.2f, 1f).getPixel(0, 0)
        val darker = applyFilter(src, 0f, -0.2f, 1f).getPixel(0, 0)
        assertTrue(((brighter shr 16) and 0xFF) > 100)
        assertTrue(((darker shr 16) and 0xFF) < 100)
    }

    @Test
    fun contrast_spreadsAroundMidGray() {
        val src = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        src.setPixel(0, 0, Color.rgb(200, 200, 200))
        src.setPixel(1, 0, Color.rgb(50, 50, 50))
        val out = applyFilter(src, 0f, 0f, 2f)
        val hi = (out.getPixel(0, 0) shr 16) and 0xFF
        val lo = (out.getPixel(1, 0) shr 16) and 0xFF
        assertTrue(hi > 200)
        assertTrue(lo < 50)
    }

    @Test
    fun motionBlur_spreadsDotAlongAngle() = runBlocking {
        val src = Bitmap.createBitmap(21, 21, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.TRANSPARENT)
        src.setPixel(10, 10, Color.WHITE)
        val out = ImageEffects.motionBlurredBitmap(src, 5f, 0f)
        assertNotNull(out)
        assertEquals(21, out!!.width)
        assertEquals(21, out.height)
        // Titik asal tetap terang, tetangga horizontal ikut terang.
        assertTrue(Color.alpha(out.getPixel(10, 10)) > 0)
        assertTrue(Color.alpha(out.getPixel(14, 10)) > 0)
        // Tetangga vertikal tetap transparan (blur horizontal).
        assertEquals(0, Color.alpha(out.getPixel(10, 14)))
    }

    @Test
    fun motionBlur_zeroRadiusReturnsNull() = runBlocking {
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertNull(ImageEffects.motionBlurredBitmap(src, 0f, 45f))
    }

    @Test
    fun outerGlow_inactiveParamsReturnNull() = runBlocking {
        val src = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        assertNull(ImageEffects.outerGlowBitmap(src, Color.YELLOW, 0f))
        assertNull(ImageEffects.outerGlowBitmap(src, Color.TRANSPARENT, 8f))
    }

    @Test
    fun outerGlow_spreadsBeyondShape() = runBlocking {
        val src = Bitmap.createBitmap(21, 21, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.TRANSPARENT)
        src.setPixel(10, 10, Color.WHITE)
        val (glow, pad) = ImageEffects.outerGlowBitmap(src, Color.YELLOW, 4f)!!
        // Glow mencakup padding di sekeliling bentuk asal.
        assertTrue(pad >= 4f)
        assertEquals(21 + 2 * pad.toInt(), glow.width)
        // Titik di luar bentuk asal ikut bercahaya kuning.
        val edge = glow.getPixel((10 + pad.toInt() + 3), (10 + pad.toInt()))
        assertTrue(Color.alpha(edge) > 0)
        assertTrue((edge and 0x00FFFFFF) != 0)
    }
}
