package com.mochits.app.text

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion blur direksional (searah sudut, bukan radial).
 *
 * Murni operasi piksel Kotlin tanpa RenderEffect/RenderScript sehingga
 * berjalan di semua level API (wajib support Android 11 ke bawah).
 *
 * Konvensi sudut sama dengan gradientAngle: 0° = horizontal (+x),
 * 90° = vertikal ke bawah (+y, koordinat layar).
 */
object MotionBlur {

    /** Batas sampel per piksel agar slider tetap responsif. */
    const val MAX_TAPS = 65

    /**
     * @param pixels piksel ARGB (hasil getPixels), ukuran harus width*height.
     * @param radius jangkauan blur ke tiap sisi dalam px (0 = tanpa efek).
     * @return array piksel baru hasil blur; array asal tidak diubah.
     */
    fun blurDirectional(
        pixels: IntArray,
        width: Int,
        height: Int,
        angleDegrees: Float,
        radius: Float
    ): IntArray {
        if (pixels.size != width * height || width <= 0 || height <= 0) return pixels
        if (radius <= 0f) return pixels

        val rad = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()

        val taps = (2 * ceil(radius.toDouble()).toInt() + 1).coerceIn(1, MAX_TAPS)
        val step = if (taps > 1) (2f * radius) / (taps - 1) else 0f
        val tapOx = FloatArray(taps) { i -> dx * (-radius + i * step) }
        val tapOy = FloatArray(taps) { i -> dy * (-radius + i * step) }

        val out = IntArray(pixels.size)
        for (by in 0 until height) {
            for (bx in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (t in 0 until taps) {
                    // CLAMP: tepi diulang (cocok untuk smear blur).
                    val sx = (bx + tapOx[t]).toInt().coerceIn(0, width - 1)
                    val sy = (by + tapOy[t]).toInt().coerceIn(0, height - 1)
                    val px = pixels[sy * width + sx]
                    a += (px ushr 24) and 0xFF
                    r += (px shr 16) and 0xFF
                    g += (px shr 8) and 0xFF
                    b += px and 0xFF
                }
                out[by * width + bx] =
                    ((a / taps) shl 24) or ((r / taps) shl 16) or ((g / taps) shl 8) or (b / taps)
            }
        }
        return out
    }
}
