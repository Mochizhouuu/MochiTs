package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.mochits.app.model.RadialBlurMode
import com.mochits.app.model.TexturePreset
import com.mochits.app.text.MotionBlur
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import com.mochits.app.util.Logger
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
    ): Bitmap? = processedBitmap(
        src = src,
        motionBlurRadius = radius,
        motionBlurAngle = angleDegrees
    )

    /**
     * Pipeline bitmap gambar: motion blur, radial blur, lalu texture.
     * Diproses pada bitmap kecil (maks 768 px) agar slider tetap responsif.
     * @return null bila semua efek netral (pemanggil memakai bitmap asli).
     */
    suspend fun processedBitmap(
        src: Bitmap,
        motionBlurRadius: Float = 0f,
        motionBlurAngle: Float = 0f,
        radialBlurStrength: Float = 0f,
        radialBlurMode: RadialBlurMode = RadialBlurMode.SPIN,
        radialBlurCenterX: Float = 0.5f,
        radialBlurCenterY: Float = 0.5f,
        texturePreset: TexturePreset = TexturePreset.NONE,
        textureStrength: Float = 0f,
        textureScale: Float = 1f
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return@withContext null
        val hasMotion = motionBlurRadius > 0f
        val hasRadial = radialBlurStrength > 0f
        val hasTexture = texturePreset != TexturePreset.NONE && textureStrength > 0f
        if (!hasMotion && !hasRadial && !hasTexture) return@withContext null
        try {
            val maxDim = if (hasRadial) 640f else 768f
            val down = minOf(1f, maxDim / maxOf(src.width, src.height).toFloat())
            val ww = maxOf(1, (src.width * down).toInt())
            val hh = maxOf(1, (src.height * down).toInt())
            val small = Bitmap.createScaledBitmap(src, ww, hh, true)
            val px = IntArray(ww * hh)
            small.getPixels(px, 0, ww, 0, 0, ww, hh)
            var out = px
            if (hasMotion) {
                out = MotionBlur.blurDirectional(
                    out, ww, hh, motionBlurAngle, motionBlurRadius * down
                )
            }
            if (hasRadial) {
                out = radialBlurPixels(
                    out, ww, hh,
                    strength = radialBlurStrength.coerceIn(0f, 1f),
                    mode = radialBlurMode,
                    centerX = radialBlurCenterX.coerceIn(0f, 1f),
                    centerY = radialBlurCenterY.coerceIn(0f, 1f)
                )
            }
            if (hasTexture) {
                out = applyTexturePixels(
                    out, ww, hh,
                    preset = texturePreset,
                    strength = textureStrength.coerceIn(0f, 1f),
                    scale = (textureScale.coerceIn(0.5f, 4f) * down).coerceAtLeast(0.2f)
                )
            }
            if (out !== px) {
                small.setPixels(out, 0, ww, 0, 0, ww, hh)
            }
            val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            if (result != small) {
                try { small.recycle() } catch (_: Exception) {}
            }
            result
        } catch (t: Throwable) {
            Logger.e("processedBitmap gagal: ${t.message}", t)
            null
        }
    }

    /**
     * Radial blur murni Kotlin (aman API 29). Pusat tidak ikut blur:
     * SPIN memutar sampel di radius yang sama, ZOOM menarik/mendorong sampel
     * sepanjang garis radius. Pinggir memakai bilinear + bobot Gaussian.
     */
    fun radialBlurPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        mode: RadialBlurMode,
        centerX: Float,
        centerY: Float
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || width <= 0 || height <= 0 || pixels.size < width * height) return pixels
        val cx = centerX.coerceIn(0f, 1f) * (width - 1)
        val cy = centerY.coerceIn(0f, 1f) * (height - 1)
        val out = IntArray(width * height)
        val taps = 17
        val maxAngle = 1.75f * s
        val maxZoom = 0.55f * s
        val centerRadius = 1.4f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val dx = x - cx
                val dy = y - cy
                val dist = hypot(dx, dy)
                if (dist <= centerRadius) {
                    out[idx] = pixels[idx]
                    continue
                }
                var alphaSum = 0f
                var weightSum = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                var wsum = 0f
                for (t in 0 until taps) {
                    val f = t / (taps - 1f) - 0.5f
                    val weight = kotlin.math.exp(-(f * f) * 9f)
                    val sx: Float
                    val sy: Float
                    if (mode == RadialBlurMode.ZOOM) {
                        val m = 1f + f * maxZoom
                        sx = cx + dx * m
                        sy = cy + dy * m
                    } else {
                        val ang = f * maxAngle
                        val ca = cos(ang)
                        val sa = sin(ang)
                        sx = cx + dx * ca - dy * sa
                        sy = cy + dx * sa + dy * ca
                    }
                    val sampled = sampleBilinear(pixels, width, height, sx, sy)
                    if (sampled != 0) {
                        val sa = (sampled ushr 24) and 0xFF
                        val wa = sa / 255f * weight
                        weightSum += weight
                        alphaSum += sa * weight
                        if (wa > 0f) {
                            r += ((sampled shr 16) and 0xFF) * wa
                            g += ((sampled shr 8) and 0xFF) * wa
                            b += (sampled and 0xFF) * wa
                            wsum += wa
                        }
                    }
                }
                if (wsum <= 0f || weightSum <= 0f) {
                    out[idx] = pixels[idx]
                } else {
                    val aa = (alphaSum / weightSum).toInt().coerceIn(0, 255)
                    out[idx] = (aa shl 24) or
                        ((r / wsum).toInt().coerceIn(0, 255) shl 16) or
                        ((g / wsum).toInt().coerceIn(0, 255) shl 8) or
                        (b / wsum).toInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    /**
     * Texture deterministik tanpa aset: grain, kertas, dan anyaman kanvas.
     * Hanya mengubah RGB; alpha dipertahankan.
     */
    fun applyTexturePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        preset: TexturePreset,
        strength: Float,
        scale: Float
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (preset == TexturePreset.NONE || s <= 0f) return pixels
        val out = IntArray(pixels.size)
        val sc = scale.coerceAtLeast(0.2f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha == 0) {
                    out[y * width + x] = 0
                    continue
                }
                val sx = x / sc
                val sy = y / sc
                val noise = when (preset) {
                    TexturePreset.GRAIN -> hashNoise(sx.toInt(), sy.toInt(), 11)
                    TexturePreset.PAPER -> {
                        val low = hashNoise(sx.toInt(), sy.toInt(), 23)
                        val fine = hashNoise((sx * 2.7f).toInt(), (sy * 2.7f).toInt(), 41)
                        low * 0.65f + fine * 0.35f
                    }
                    TexturePreset.CANVAS -> {
                        val weave = (sin(sx * 1.7f) * sin(sy * 1.7f) + 1f) * 0.5f
                        weave * 0.7f + hashNoise(sx.toInt(), sy.toInt(), 67) * 0.3f
                    }
                    TexturePreset.NONE -> 0.5f
                }
                val delta = (noise - 0.5f) * 2f * s * 0.62f
                val factor = (1f + delta).coerceIn(0.15f, 1.85f)
                val nr = (((p shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val ng = (((p shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val nb = ((p and 0xFF) * factor).toInt().coerceIn(0, 255)
                out[y * width + x] = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    private fun hashNoise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
    }

    private fun sampleBilinear(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Int {
        if (x < 0f || y < 0f || x > width - 1f || y > height - 1f) return 0
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = x - x0
        val fy = y - y0
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy
        fun ch(shift: Int): Int {
            val v = ((p00 ushr shift) and 0xFF) * w00 +
                ((p10 ushr shift) and 0xFF) * w10 +
                ((p01 ushr shift) and 0xFF) * w01 +
                ((p11 ushr shift) and 0xFF) * w11
            return v.toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

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
            Logger.e("outerGlowBitmap gagal: ${t.message}", t)
            null
        }
    }
}
