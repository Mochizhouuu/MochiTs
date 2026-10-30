package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mochits.app.model.RadialBlurMode
import com.mochits.app.model.TexturePreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun radialBlur_keepsCenterAndSpreadsRing() {
        val w = 33
        val h = 33
        val px = IntArray(w * h)
        // Cincin putih di sekitar tengah.
        for (x in 0 until w) {
            px[8 * w + x] = Color.WHITE
            px[24 * w + x] = Color.WHITE
        }
        val out = ImageEffects.radialBlurPixels(
            px, w, h, 0.8f, RadialBlurMode.SPIN, 0.5f, 0.5f
        )
        // Pusat tetap transparan (tidak ikut tersapu).
        assertEquals(0, Color.alpha(out[16 * w + 16]))
        // Bagian cincin masih memiliki piksel.
        var lit = 0
        for (p in out) if (Color.alpha(p) > 0) lit++
        assertTrue(lit > 0)
    }

    @Test
    fun texture_isDeterministicAndPreservesAlpha() {
        val w = 8
        val h = 8
        val src = IntArray(w * h) { Color.argb(128, 120, 140, 160) }
        val a = ImageEffects.applyTexturePixels(src, w, h, TexturePreset.GRAIN, 0.75f, 1.5f)
        val b = ImageEffects.applyTexturePixels(src, w, h, TexturePreset.GRAIN, 0.75f, 1.5f)
        assertArrayEquals(a, b)
        for (p in a) assertEquals(128, Color.alpha(p))
        assertTrue(a.any { it != src[0] })
    }

    @Test
    fun texture_halftoneDeterministicAndTintable() {
        val w = 16
        val h = 16
        val src = IntArray(w * h) { Color.argb(200, 230, 90, 60) }
        val a = ImageEffects.applyTexturePixels(src, w, h, TexturePreset.HALFTONE, 0.8f, 1f)
        val b = ImageEffects.applyTexturePixels(src, w, h, TexturePreset.HALFTONE, 0.8f, 1f)
        assertArrayEquals(a, b)
        for (p in a) assertEquals(200, Color.alpha(p))
        assertTrue(a.any { it != src[0] })
        // Tint biru mengubah warna piksel bertitik, alpha tetap.
        val blue = Color.argb(255, 40, 90, 255)
        val tinted = ImageEffects.applyTexturePixels(src, w, h, TexturePreset.HALFTONE, 0.8f, 1f, blue)
        for (p in tinted) assertEquals(200, Color.alpha(p))
        assertTrue(!tinted.contentEquals(a))
    }

    @Test
    fun radialBlur_clampedEdgesKeepAlpha() {
        val w = 16
        val h = 16
        val px = IntArray(w * h) { Color.argb(255, 200, 100, 50) }
        val out = ImageEffects.radialBlurPixels(
            px, w, h, 0.9f, RadialBlurMode.ZOOM, 0.5f, 0.5f, clampEdges = true
        )
        // Edge clamp: tidak ada piksel yang alphanya rontok di pinggiran.
        for (p in out) assertEquals(255, Color.alpha(p))
    }

    @Test
    fun radialBlur_warnaTepiTetapOrangeMurniPadaSemuaAlpha() {
        val w = 48
        val h = 48
        val px = IntArray(w * h)
        // Batang orange dengan tepi antialias (ramp alpha) — meniru glyph
        // hasil render teks. Laporan pengguna: blur ZOOM memunculkan warna
        // kehijauan (olive) pada font orange.
        for (y in 20 until 28) for (x in 8 until 40) px[y * w + x] = Color.argb(255, 255, 140, 0)
        for (x in 8 until 40) {
            px[19 * w + x] = Color.argb(170, 255, 140, 0)
            px[18 * w + x] = Color.argb(85, 255, 140, 0)
            px[28 * w + x] = Color.argb(170, 255, 140, 0)
            px[29 * w + x] = Color.argb(85, 255, 140, 0)
        }
        val out = ImageEffects.radialBlurPixels(
            px, w, h, 0.8f, RadialBlurMode.ZOOM, 0.5f, 0.5f
        )
        for (p in out) {
            if (Color.alpha(p) > 24) {
                // Warna wajib tetap ORANGE MURNI pada semua tingkat alpha:
                // bila kanal ikut menggelap saat alpha turun (artefak
                // interpolasi straight-alpha), kompositnya jadi olive.
                assertEquals(255, Color.red(p))
                assertTrue(Color.green(p) < Color.red(p))
            }
        }
    }

    @Test
    fun processedBitmap_neutralReturnsNull() = runBlocking {
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertNull(
            ImageEffects.processedBitmap(
                src,
                texturePreset = TexturePreset.NONE,
                textureStrength = 0f
            )
        )
    }
}
