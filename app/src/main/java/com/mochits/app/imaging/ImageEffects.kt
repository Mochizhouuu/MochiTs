package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.mochits.app.text.MotionBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Efek khusus gambar (ImageLayer), terpisah dari efek teks.
 *
 * Semua API yang dipakai (ColorMatrix, get/setPixels) tersedia sejak
 * API 1, jadi aman untuk syarat support Android 11 ke bawah.
 */
object ImageEffects {

    /**
     * Filter warna gabungan grayscale + brightness + contrast.
     * @return null bila semua parameter netral (hemat satu alokasi filter).
     */
    fun imageColorFilter(
        grayscale: Float,
        brightness: Float,
        contrast: Float
    ): ColorMatrixColorFilter? {
        val g = grayscale.coerceIn(0f, 1f)
        val c = contrast.coerceIn(0f, 2f)
        val b = brightness.coerceIn(-1f, 1f)
        if (g == 0f && b == 0f && c == 1f) return null

        // out = c * (in - 128) + 128 + b * 255
        val translate = 128f * (1f - c) + b * 255f
        val tone = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val combined = ColorMatrix()
        combined.setSaturation(1f - g)
        // Terapkan tone dulu, saturasi sesudahnya.
        combined.postConcat(tone)
        return ColorMatrixColorFilter(combined)
    }

    /**
     * Motion blur searah sudut untuk bitmap gambar.
     * Dikerjakan di bitmap kecil (maks 768px) lalu di-upscale agar
     * slider tetap responsif; hasilnya dikembalikan seukuran sumber.
     */
    suspend fun motionBlurredBitmap(
        src: Bitmap,
        radius: Float,
        angleDegrees: Float
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return@withContext null
        if (radius <= 0f) return@withContext null
        try {
            val down = minOf(1f, 768f / maxOf(src.width, src.height).toFloat())
            val ww = maxOf(1, (src.width * down).toInt())
            val hh = maxOf(1, (src.height * down).toInt())
            val small = Bitmap.createScaledBitmap(src, ww, hh, true)
            val px = IntArray(ww * hh)
            small.getPixels(px, 0, ww, 0, 0, ww, hh)
            val blurred = MotionBlur.blurDirectional(px, ww, hh, angleDegrees, radius * down)
            small.setPixels(blurred, 0, ww, 0, 0, ww, hh)
            val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            if (result != small) {
                try { small.recycle() } catch (_: Exception) {}
    /**
     * Outer glow satu warna untuk bitmap gambar.
     * Siluet alfa diblur dua arah (H lalu V via [MotionBlur]) lalu
     * diwarnai; interior tertutup gambar asli saat digambar di belakangnya.
     * @return bitmap glow + padding full-res px (digambar di x-pad, y-pad),
     * atau null bila radius/warna tidak aktif.
     */
    suspend fun outerGlowBitmap(
        src: Bitmap,
        glowColor: Int,
        radius: Float
    ): Pair<Bitmap, Float>? = withContext(Dispatchers.Default) {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return@withContext null
        if (radius <= 0f || glowColor == 0) return@withContext null
        try {
            val down = minOf(1f, 512f / maxOf(src.width, src.height).toFloat())
            val ww = maxOf(1, (src.width * down).toInt())
            val hh = maxOf(1, (src.height * down).toInt())
            val small = Bitmap.createScaledBitmap(src, ww, hh, true)
            val srcPx = IntArray(ww * hh)
            small.getPixels(srcPx, 0, ww, 0, 0, ww, hh)
            try { small.recycle() } catch (_: Exception) {}

            val workR = radius * down
            val padW = kotlin.math.ceil(workR.toDouble()).toInt() + 4
            val pw = ww + 2 * padW
            val ph = hh + 2 * padW
            // Siluet putih ber-alfa asli di kanvas berpadding.
            val silhouettes = IntArray(pw * ph) { 0 }
            for (y in 0 until hh) {
                for (x in 0 until ww) {
                    val a = (srcPx[y * ww + x] ushr 24) and 0xFF
                    if (a != 0) silhouettes[(y + padW) * pw + (x + padW)] = (a shl 24) or 0x00FFFFFF
                }
            }
            // Blur kotak dua-pass (H lalu V) sebagai pendekatan glow merata.
            val blurH = MotionBlur.blurDirectional(silhouettes, pw, ph, 0f, workR)
            val blurHV = MotionBlur.blurDirectional(blurH, pw, ph, 90f, workR)

            val glowA = (glowColor ushr 24) and 0xFF
            val glowR = (glowColor shr 16) and 0xFF
            val glowG = (glowColor shr 8) and 0xFF
            val glowB = glowColor and 0xFF
            val out = IntArray(pw * ph)
            for (i in blurHV.indices) {
                val a = (((blurHV[i] ushr 24) and 0xFF) * glowA) / 255
                out[i] = (a shl 24) or (glowR shl 16) or (glowG shl 8) or glowB
            }
            var glow = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            glow.setPixels(out, 0, pw, 0, 0, pw, ph)

            val padFull = kotlin.math.ceil(radius.toDouble()).toInt() + 4
            val fullW = src.width + 2 * padFull
            val fullH = src.height + 2 * padFull
            val upscaled = Bitmap.createScaledBitmap(glow, fullW, fullH, true)
            if (upscaled != glow) {
                try { glow.recycle() } catch (_: Exception) {}
                glow = upscaled
            }
            Pair(glow, padFull.toFloat())
        } catch (t: Throwable) {
            null
        }
    }
}
            result
        } catch (t: Throwable) {
            null
        }
    }
}
