package com.mochits.app.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionBlurTest {

    private fun alpha(px: Int) = (px ushr 24) and 0xFF

    @Test
    fun `radius nol mengembalikan array asal`() {
        val pixels = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertSame(pixels, MotionBlur.blurDirectional(pixels, 2, 1, 0f, 0f))
    }

    @Test
    fun `gambar seragam tidak berubah`() {
        val pixels = IntArray(25) { 0xFF884488.toInt() }
        val out = MotionBlur.blurDirectional(pixels, 5, 5, 37f, 4f)
        out.forEach { assertEquals(0xFF884488.toInt(), it) }
    }

    @Test
    fun `blur horizontal menyebar satu piksel ke kiri dan kanan`() {
        // Baris 5px: hanya tengah yang opaque.
        val w = 5
        val pixels = IntArray(w) { 0 }
        pixels[2] = 0xFFFFFFFF.toInt()
        val out = MotionBlur.blurDirectional(pixels, w, 1, 0f, 1f)
        // radius 1 -> 3 taps: tiap piksel merata-ratakan dirinya + tetangga.
        assertEquals(0, alpha(out[0]))
        assertEquals(85, alpha(out[1]))
        assertEquals(85, alpha(out[2]))
        assertEquals(85, alpha(out[3]))
        assertEquals(0, alpha(out[4]))
    }

    @Test
    fun `blur vertikal tidak menyebar horizontal`() {
        // Kolom tengah opaque pada grid 5x5.
        val w = 5
        val h = 5
        val pixels = IntArray(w * h) { 0 }
        for (y in 0 until h) pixels[y * w + 2] = 0xFFFFFFFF.toInt()
        val out = MotionBlur.blurDirectional(pixels, w, h, 90f, 2f)
        // Kolom tetangga tetap transparan.
        for (y in 0 until h) {
            assertEquals(0, alpha(out[y * w + 0]))
            assertEquals(0, alpha(out[y * w + 4]))
            assertEquals(255, alpha(out[y * w + 2]))
        }
    }

    @Test
    fun `sudut berbeda menghasilkan sebaran berbeda`() {
        val w = 7
        val h = 7
        fun singleDot(): IntArray {
            val p = IntArray(w * h) { 0 }
            p[3 * w + 3] = 0xFFFFFFFF.toInt()
            return p
        }
        val horizontal = MotionBlur.blurDirectional(singleDot(), w, h, 0f, 3f)
        val vertical = MotionBlur.blurDirectional(singleDot(), w, h, 90f, 3f)
        // Blur horizontal: ujung kiri-kanan baris tengah ikut terang.
        assertTrue(alpha(horizontal[3 * w + 0]) > 0)
        assertEquals(0, alpha(horizontal[0 * w + 3]))
        // Blur vertikal: sebaliknya.
        assertTrue(alpha(vertical[0 * w + 3]) > 0)
        assertEquals(0, alpha(vertical[3 * w + 0]))
    }
}
