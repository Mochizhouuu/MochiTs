package com.mochits.app.text

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Motion blur direksional (searah sudut, bukan radial).
 *
 * Murni operasi piksel Kotlin tanpa RenderEffect/RenderScript sehingga
 * berjalan di semua level API (wajib support Android 11 ke bawah).
 *
 * Profil Gaussian (bukan rata-rata box): inti objek tetap tegas terbaca
 * sementara ekor memudar mulus — seperti motion blur kamera. Sampling
 * bilinear + akumulasi premultiplied agar streak panjang tidak patah-patah
 * dan tepinya tidak menggelap.
 *
 * Konvensi sudut sama dengan gradientAngle: 0° = horizontal (+x),
 * 90° = vertikal ke bawah (+y, koordinat layar).
 */
object MotionBlur {

    /** Batas sampel per piksel agar slider tetap responsif. */
    const val MAX_TAPS = 97

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

        val sigma = maxOf(radius / 2.5, 0.4).toDouble()
        val twoSigmaSq = 2.0 * sigma * sigma
        val taps = (2 * ceil(radius.toDouble()).toInt() + 1).coerceIn(1, MAX_TAPS)
        val step = if (taps > 1) (2f * radius) / (taps - 1) else 0f
        val tapOx = FloatArray(taps) { i -> dx * (-radius + i * step) }
        val tapOy = FloatArray(taps) { i -> dy * (-radius + i * step) }
        val tapW = DoubleArray(taps) { i ->
            val d = (-radius + i * step).toDouble()
            exp(-(d * d) / twoSigmaSq)
        }
        var weightSum = 0.0
        for (w in tapW) weightSum += w
        if (weightSum <= 0.0) return pixels

        val maxX = (width - 1).toFloat()
        val maxY = (height - 1).toFloat()
        val out = IntArray(pixels.size)
        for (by in 0 until height) {
            for (bx in 0 until width) {
                var sumA = 0.0
                var sumRA = 0.0
                var sumGA = 0.0
                var sumBA = 0.0
                for (t in 0 until taps) {
                    val w = tapW[t] / weightSum
                    // Bilinear: titik sampel pecahan diinterpolasi dari 4 tetangga.
                    val fx = (bx + tapOx[t]).coerceIn(0f, maxX)
                    val fy = (by + tapOy[t]).coerceIn(0f, maxY)
                    val x0 = fx.toInt()
                    val y0 = fy.toInt()
                    val x1 = min(x0 + 1, width - 1)
                    val y1 = min(y0 + 1, height - 1)
                    val tx = (fx - x0).toDouble()
                    val ty = (fy - y0).toDouble()
                    val b00 = (1.0 - tx) * (1.0 - ty)
                    val b10 = tx * (1.0 - ty)
                    val b01 = (1.0 - tx) * ty
                    val b11 = tx * ty

                    val p00 = pixels[y0 * width + x0]
                    var ww = w * b00
                    var aa = ((p00 ushr 24) and 0xFF).toDouble()
                    sumA += ww * aa
                    sumRA += ww * aa * ((p00 shr 16) and 0xFF)
                    sumGA += ww * aa * ((p00 shr 8) and 0xFF)
                    sumBA += ww * aa * (p00 and 0xFF)

                    val p10 = pixels[y0 * width + x1]
                    ww = w * b10
                    aa = ((p10 ushr 24) and 0xFF).toDouble()
                    sumA += ww * aa
                    sumRA += ww * aa * ((p10 shr 16) and 0xFF)
                    sumGA += ww * aa * ((p10 shr 8) and 0xFF)
                    sumBA += ww * aa * (p10 and 0xFF)

                    val p01 = pixels[y1 * width + x0]
                    ww = w * b01
                    aa = ((p01 ushr 24) and 0xFF).toDouble()
                    sumA += ww * aa
                    sumRA += ww * aa * ((p01 shr 16) and 0xFF)
                    sumGA += ww * aa * ((p01 shr 8) and 0xFF)
                    sumBA += ww * aa * (p01 and 0xFF)

                    val p11 = pixels[y1 * width + x1]
                    ww = w * b11
                    aa = ((p11 ushr 24) and 0xFF).toDouble()
                    sumA += ww * aa
                    sumRA += ww * aa * ((p11 shr 16) and 0xFF)
                    sumGA += ww * aa * ((p11 shr 8) and 0xFF)
                    sumBA += ww * aa * (p11 and 0xFF)
                }
                val aOut = sumA.coerceIn(0.0, 255.0)
                out[by * width + bx] = if (aOut < 0.5) {
                    0
                } else {
                    val rOut = (sumRA / aOut).coerceIn(0.0, 255.0)
                    val gOut = (sumGA / aOut).coerceIn(0.0, 255.0)
                    val bOut = (sumBA / aOut).coerceIn(0.0, 255.0)
                    (((aOut + 0.5).toInt() shl 24) or
                        ((rOut + 0.5).toInt() shl 16) or
                        ((gOut + 0.5).toInt() shl 8) or
                        (bOut + 0.5).toInt())
                }
            }
        }
        return out
    }
}
