package com.mochits.core.imaging.selection

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionEngineTest {

    private fun whiteBlackSrc(): Bitmap {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (y in 25 until 50) {
            for (x in 0 until 50) {
                bmp.setPixel(x, y, Color.BLACK)
            }
        }
        return bmp
    }

    @Test
    fun engineRejectsBadInput() {
        val badSeed = SelectionEngine.select(whiteBlackSrc(), WandRequest(-1, 0, 10f))
        assertFalse(badSeed.success)

        val badConn = SelectionEngine.select(whiteBlackSrc(), WandRequest(10, 10, 10f, connectivity = 8))
        assertFalse(badConn.success)

        val badRange = SelectionEngine.select(whiteBlackSrc(), WandRequest(10, 10, 10f, fixedRange = false))
        assertFalse(badRange.success)

        val nullSrc = SelectionEngine.select(null, WandRequest(10, 10, 10f))
        assertFalse(nullSrc.success)
    }

    @Test
    fun engineSelectsConnectedOnly() {
        val res = SelectionEngine.select(whiteBlackSrc(), WandRequest(10, 10, 10f))
        assertTrue(res.success)
        val mask = res.mask!!
        assertEquals(50, mask.width)
        assertEquals(50, mask.height)
        // Baris putih terpilih, baris hitam tidak.
        assertEquals(255.toByte(), mask.pixels[10 * 50 + 10])
        assertEquals(0.toByte(), mask.pixels[30 * 50 + 10])
        assertTrue(res.selectedPixels > 0L)
    }

    @Test
    fun combinerUnionAndSubtract() {
        val a = SelectionMask(4, 4, ByteArray(16) { if (it < 4) 255.toByte() else 0 })
        val b = SelectionMask(4, 4, ByteArray(16) { if (it % 4 == 0) 255.toByte() else 0 })
        val union = SelectionCombiner.add(a, b)
        assertEquals(255.toByte(), union.pixels[0])
        assertEquals(255.toByte(), union.pixels[1])
        assertEquals(255.toByte(), union.pixels[4])
        assertEquals(0.toByte(), union.pixels[5])

        val sub = SelectionCombiner.subtract(union, b)
        assertEquals(0.toByte(), sub.pixels[0])
        assertEquals(255.toByte(), sub.pixels[1])

        val inter = SelectionCombiner.intersect(a, b)
        assertEquals(255.toByte(), inter.pixels[0])
        assertEquals(0.toByte(), inter.pixels[1])

        val rep = SelectionCombiner.replace(a, b)
        assertTrue(rep.pixels.contentEquals(b.pixels))
    }

    @Test
    fun morphologyDilateErodeRoundTrip() {
        // Satu piksel tengah di 9x9.
        val single = SelectionMask(9, 9, ByteArray(81))
        single.pixels[4 * 9 + 4] = 255.toByte()
        val dilated = MaskMorphology.dilate(single, 2)
        // Plus-shape berjari 2: (4,2..6) dan (2..6,4) terisi.
        assertEquals(255.toByte(), dilated.pixels[4 * 9 + 2])
        assertEquals(255.toByte(), dilated.pixels[2 * 9 + 4])
        assertEquals(0.toByte(), dilated.pixels[0])
        // Erode 2 mengembalikan titik semula.
        val eroded = MaskMorphology.erode(dilated, 2)
        assertEquals(255.toByte(), eroded.pixels[4 * 9 + 4])
        assertEquals(0.toByte(), eroded.pixels[4 * 9 + 5])
    }

    @Test
    fun readWritePackedRoundTrip() {
        val bmp = Bitmap.createBitmap(13, 17, Bitmap.Config.ALPHA_8)
        val packed = ByteArray(13 * 17) { if (it % 3 == 0) 255.toByte() else 0 }
        SelectionEngine.writePacked(bmp, packed)
        val back = SelectionEngine.readPacked(bmp, 13, 17)
        assertTrue(back.contentEquals(packed))
    }
}
