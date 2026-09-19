package com.mochits.app.imaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerspectiveTest {

    @Test
    fun `identitas dan null tidak aktif`() {
        assertTrue(!Perspective.isActive(null))
        assertTrue(!Perspective.isActive(listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertTrue(Perspective.isActive(listOf(0f, 0f, 1f, 0f, 1f, 1f, 0.2f, 0.8f)))
        assertTrue(!Perspective.isActive(listOf(0f, 0f)))
    }

    @Test
    fun `identitas mengembalikan null`() {
        val px = IntArray(16) { -1 }
        assertNull(Perspective.warpPixels(px, 4, 4, listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
    }

    @Test
    fun `quad degenerat mengembalikan null`() {
        val px = IntArray(16) { -1 }
        // Semua titik segaris: luas nol.
        assertNull(Perspective.warpPixels(px, 4, 4, listOf(0f, 0f, 0.5f, 0f, 1f, 0f, 0.5f, 0f)))
        // Ciut ke satu titik.
        assertNull(Perspective.warpPixels(px, 4, 4, List(8) { 0.5f }))
    }

    @Test
    fun `geser kanan memindahkan konten dan offset`() {
        // 4x4: kolom kiri putih, sisanya transparan.
        val w = 4; val h = 4
        val px = IntArray(w * h) { 0 }
        for (y in 0 until h) px[y * w + 0] = -1
        // Quad = persegi digeser +0.25 (1px) ke kanan.
        val quad = listOf(0.25f, 0f, 1f, 0f, 1f, 1f, 0.25f, 1f)
        val out = Perspective.warpPixels(px, w, h, quad, 64)
        assertNotNull(out)
        val o = out!!
        // Offset mengikuti batas kiri quad (1px).
        assertTrue(o.offsetX in 0.9f..1.1f, "offsetX=${o.offsetX}")
        assertTrue(kotlin.math.abs(o.offsetY) < 0.01f, "offsetY=${o.offsetY}")
        // Kolom pertama output (dulu kolom 0) tetap terang (~setengah karena bilinear).
        var lit = 0
        for (y in 0 until o.height) {
            if (((o.pixels[y * o.width + 0] ushr 24) and 0xFF) > 100) lit++
        }
        assertTrue(lit >= o.height - 1, "kolom hasil terang: $lit")
    }

    @Test
    fun `homografi persegi satuan konsisten`() {
        // Petakan satuan ke dirinya: matriks ~identitas.
        val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val h = Perspective.solveHomography(src, src.copyOf())
        assertNotNull(h)
        val hh = h!!
        assertEquals(1.0, hh[0], 1e-9)
        assertEquals(1.0, hh[4], 1e-9)
        assertEquals(1.0, hh[8], 1e-9)
        assertEquals(0.0, hh[1], 1e-9)
        val inv = Perspective.invert3x3(hh)
        assertNotNull(inv)
        assertEquals(1.0, inv!![0], 1e-9)
        // Singular -> null.
        assertNull(Perspective.invert3x3(DoubleArray(9) { 0.0 }))
    }
}
