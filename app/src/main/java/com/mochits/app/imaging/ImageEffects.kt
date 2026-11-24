package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.mochits.app.model.RadialBlurMode
import com.mochits.app.model.TexturePreset
import com.mochits.app.text.MotionBlur
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
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
        textureScale: Float = 1f,
        textureColor: Int = android.graphics.Color.TRANSPARENT,
        textureAngle: Float = 0f,
        /** 0 = kualitas penuh; > 0 = batas sisi bitmap saat pratinjau slider. */
        maxDim: Float = 0f,
        customTexture: Bitmap? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return@withContext null
        val hasMotion = motionBlurRadius > 0f
        val hasRadial = radialBlurStrength > 0f
        val hasTexture = texturePreset != TexturePreset.NONE && textureStrength > 0f
        if (!hasMotion && !hasRadial && !hasTexture) return@withContext null
        try {
            val qualityDim = if (maxDim > 0f) maxDim else if (hasRadial) 640f else 768f
            val down = minOf(1f, qualityDim / maxOf(src.width, src.height).toFloat())
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
                    centerY = radialBlurCenterY.coerceIn(0f, 1f),
                    // Layer gambar memenuhi bitmap: sampel keluar batas harus
                    // di-clamp (replikasi tepi), BUKAN dianggap transparan —
                    // dulu pinggiran gambar rontok alphanya saat zoom/spin.
                    clampEdges = true
                )
            }
            if (hasTexture) {
                out = if (texturePreset == TexturePreset.CUSTOM && customTexture != null &&
                    !customTexture.isRecycled
                ) {
                    applyCustomTexturePixels(
                        out, ww, hh, customTexture,
                        strength = textureStrength.coerceIn(0f, 1f),
                        scale = (textureScale.coerceIn(0.5f, 4f) * down).coerceAtLeast(0.2f),
                        angleDeg = textureAngle
                    )
                } else {
                    applyTexturePixels(
                        out, ww, hh,
                        preset = texturePreset,
                        strength = textureStrength.coerceIn(0f, 1f),
                        scale = (textureScale.coerceIn(0.5f, 4f) * down).coerceAtLeast(0.2f),
                        tint = textureColor,
                        angleDeg = textureAngle
                    )
                }
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
     *
     * Performa: bobot Gaussian dan trig per-tap TIDAK bergantung piksel,
     * jadi semuanya dihitung sekali di luar loop (dulu exp/cos/sin dihitung
     * ulang untuk tiap piksel × tiap tap — ribuan kali lebih berat; inilah
     * penyebab slider Radial Blur terasa ngelag).
     */
    /** facade: core Rust bila tersedia, fallback Kotlin di JVM/test. */
    fun radialBlurPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        mode: RadialBlurMode,
        centerX: Float,
        centerY: Float,
        clampEdges: Boolean = false
    ): IntArray {
        if (RustBridge.available && strength > 0f && width > 0 && height > 0 &&
            pixels.size >= width * height
        ) {
            try {
                return radialBlurPixelsNative(
                    pixels, width, height, strength,
                    if (mode == RadialBlurMode.ZOOM) 1 else 0,
                    centerX, centerY, clampEdges
                )
            } catch (t: Throwable) {
                Logger.w("Rust radial blur gagal, fallback Kotlin: ${t.message}")
            }
        }
        return radialBlurPixelsKotlin(pixels, width, height, strength, mode, centerX, centerY, clampEdges)
    }

    private fun radialBlurPixelsKotlin(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        mode: RadialBlurMode,
        centerX: Float,
        centerY: Float,
        clampEdges: Boolean = false
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || width <= 0 || height <= 0 || pixels.size < width * height) return pixels
        val cx = centerX.coerceIn(0f, 1f) * (width - 1)
        val cy = centerY.coerceIn(0f, 1f) * (height - 1)
        val out = IntArray(width * height)
        // Sapu moderat: cukup terlihat jelas pada strength penuh tanpa
        // menghancurkan bentuk (pernah 2.6/0.85/gauss6 -> smear ekstrem).
        val taps = 21
        val maxAngle = 2.0f * s
        val maxZoom = 0.6f * s
        val centerRadius2 = 1.4f * 1.4f
        // Konstanta per-tap: bobot Gaussian + sampel geometri (rotasi/zoom).
        val tapWeight = FloatArray(taps)
        val tapCa = FloatArray(taps)
        val tapSa = FloatArray(taps)
        val tapM = FloatArray(taps)
        for (t in 0 until taps) {
            val f = t / (taps - 1f) - 0.5f
            tapWeight[t] = kotlin.math.exp(-(f * f) * 8f)
            if (mode == RadialBlurMode.ZOOM) {
                tapM[t] = 1f + f * maxZoom
            } else {
                val ang = f * maxAngle
                tapCa[t] = cos(ang)
                tapSa[t] = sin(ang)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val dx = x - cx
                val dy = y - cy
                val dist2 = dx * dx + dy * dy
                if (dist2 <= centerRadius2) {
                    out[idx] = pixels[idx]
                    continue
                }
                // Akumulasi PREMULTIPLIED di atas SEMUA tap (sampel
                // transparan menyumbang nol, tetapi bobotnya tetap masuk
                // pembagi). Dulu sampel kosong di-skip: satu sampel kena
                // glyph langsung membuat alpha=255 -> smear keras solid,
                // dan interpolasi straight-alpha menggelapkan warna tepi
                // antialias (alpha 50% = warna setengah) sehingga smear
                // ZOOM di font orange tampak kotor kehijauan (olive).
                var sumA = 0f
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f
                for (t in 0 until taps) {
                    val weight = tapWeight[t]
                    val sx: Float
                    val sy: Float
                    if (mode == RadialBlurMode.ZOOM) {
                        val m = tapM[t]
                        sx = cx + dx * m
                        sy = cy + dy * m
                    } else {
                        val ca = tapCa[t]
                        val sa = tapSa[t]
                        sx = cx + dx * ca - dy * sa
                        sy = cy + dx * sa + dy * ca
                    }
                    // Layer gambar (bitmap penuh): tepi direplikasi agar alpha
                    // pinggir tidak rontok. Layer teks (bitmap transparan):
                    // sampel keluar bitmap = transparan (kontribusi nol).
                    val sampled = if (clampEdges) {
                        sampleBilinearClamped(pixels, width, height, sx, sy)
                    } else {
                        sampleBilinear(pixels, width, height, sx, sy)
                    }
                    sumW += weight
                    val sampA = (sampled ushr 24) and 0xFF
                    if (sampA != 0) {
                        // sampled PREMULTIPLIED: kanal sudah dikali alpha.
                        sumA += weight * sampA
                        sumR += weight * ((sampled shr 16) and 0xFF)
                        sumG += weight * ((sampled shr 8) and 0xFF)
                        sumB += weight * (sampled and 0xFF)
                    }
                }
                if (sumW <= 0f || sumA < 0.5f) {
                    out[idx] = if (sumW <= 0f) pixels[idx] else 0
                } else {
                    // Alpha = coverage blur sejati (memudar mulus ke tepi),
                    // warna = un-premultiply (premult/alpha). Round, bukan
                    // trunc, agar 255.0f tak jatuh ke 254.
                    val aa = (sumA / sumW + 0.5f).toInt().coerceIn(0, 255)
                    val inv = 255f / sumA
                    out[idx] = (aa shl 24) or
                        ((sumR * inv + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                        ((sumG * inv + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                        (sumB * inv + 0.5f).toInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    /**
     * Bilinear dengan tepi replikasi (clamp): koordinat di luar bitmap
     * dicubit ke tepi terdekat. Dipakai radial blur untuk layer gambar
     * yang memenuhi bitmap, agar tepi tidak memudar/transparan.
     *
     * HASIL PREMULTIPLIED: kanal warna sudah dikali alpha (c·a/255).
     * Menginterpolasi warna straight-alpha membuat tepi antialias
     * menggelap (alpha 50% = warna setengah) — sumber smear kotor
     * kehijauan pada radial blur ZOOM.
     */
    private fun sampleBilinearClamped(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float
    ): Int {
        val xc = x.coerceIn(0f, width - 1f)
        val yc = y.coerceIn(0f, height - 1f)
        val x0 = xc.toInt()
        val y0 = yc.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = xc - x0
        val fy = yc - y0
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        // Interpolasi kanal PREMULTIPLIED dari sumber (c*a/255), BUKAN
        // interpolasi straight lalu dikali alpha (dobat-kuantisasi; tepi
        // tetap menggelap -> smear kehijauan). Alpha diinterpolasi terpisah.
        fun prem(shift: Int): Float = (
            ((p00 ushr shift) and 0xFF) * ((p00 ushr 24) and 0xFF) * (1f - fx) * (1f - fy) +
                ((p10 ushr shift) and 0xFF) * ((p10 ushr 24) and 0xFF) * fx * (1f - fy) +
                ((p01 ushr shift) and 0xFF) * ((p01 ushr 24) and 0xFF) * (1f - fx) * fy +
                ((p11 ushr shift) and 0xFF) * ((p11 ushr 24) and 0xFF) * fx * fy
            ) / 255f
        fun rawA(): Float =
            ((p00 ushr 24) and 0xFF) * (1f - fx) * (1f - fy) +
                ((p10 ushr 24) and 0xFF) * fx * (1f - fy) +
                ((p01 ushr 24) and 0xFF) * (1f - fx) * fy +
                ((p11 ushr 24) and 0xFF) * fx * fy
        val aF = rawA()
        fun ch(shift: Int): Int = (prem(shift) + 0.5f).toInt().coerceIn(0, 255)
        return ((aF + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * ENGINE TEXTURE — satu-satunya sumber kebenaran pola.
     *
     * Thumbnail panel memakai [texturePreviewBitmap] yang memanggil fungsi ini
     * pada input abu-abu → pratinjau PASTI sama dengan hasil render (masalah
     * lama: thumbnail digambar manual jadi tidak mirip hasilnya).
     *
     * Dasar riset:
     * - Film grain: signal-dependent (paling kuat di mid-tone), luma-driven,
     *   tiga oktaf noise + debu mikro — bukan statik warna-warni.
     * - Halftone: screen 45° (sudut cetak klasik), diameter mengikuti
     *   luminance DAN skala texture.
     * - Pola geometris: nat lebih tebal dari AA → kotak tetap kotak.
     * - Jalinan OVERLAY dua arah: struktur terlihat di gelap & terang tanpa
     *   menghilangkan warna asli. Pilihan blend mode DIHAPUS karena jalur
     *   tint tidak menggunakannya dan user tidak melihat bedanya.
     */
    /**
     * facade texture: pakai core Rust bila .so tersedia, kalau tidak (JVM/test
     * atau library gagal load) jatuh ke [applyTexturePixelsKotlin].
     */
    fun applyTexturePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        preset: TexturePreset,
        strength: Float,
        scale: Float,
        tint: Int = android.graphics.Color.TRANSPARENT,
        angleDeg: Float = 0f
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (preset == TexturePreset.NONE || preset == TexturePreset.CUSTOM || s <= 0f) return pixels
        if (RustBridge.available) {
            try {
                return applyTexture(
                    pixels, width, height,
                    presetCode(preset), s, scale, tint, angleDeg
                )
            } catch (t: Throwable) {
                Logger.w("Rust texture gagal, fallback Kotlin: ${t.message}")
            }
        }
        return applyTexturePixelsKotlin(pixels, width, height, preset, s, scale, tint, angleDeg)
    }

    private fun applyTexturePixelsKotlin(
        pixels: IntArray,
        width: Int,
        height: Int,
        preset: TexturePreset,
        strength: Float,
        scale: Float,
        tint: Int = android.graphics.Color.TRANSPARENT,
        angleDeg: Float = 0f
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (preset == TexturePreset.NONE || s <= 0f) return pixels
        val out = IntArray(pixels.size)
        val sc = scale.coerceAtLeast(0.2f)
        val rotated = angleDeg % 360f != 0f
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = (if (rotated) cos(rad) else 1.0).toFloat()
        val sinA = (if (rotated) sin(rad) else 0.0).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val invSqrt2 = 0.70710678f
        val tintAlpha = (tint ushr 24) and 0xFF
        val hasTint = tintAlpha > 8
        val tintR = (tint shr 16) and 0xFF
        val tintG = (tint shr 8) and 0xFF
        val tintB = tint and 0xFF
        val tintK = tintAlpha / 255f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha == 0) {
                    out[y * width + x] = 0
                    continue
                }
                // Ruang sampel pola — diputar di sekitar pusat bitmap.
                val sx: Float
                val sy: Float
                if (rotated) {
                    val dx = x - cx
                    val dy = y - cy
                    sx = (dx * cosA + dy * sinA + cx) / sc
                    sy = (-dx * sinA + dy * cosA + cy) / sc
                } else {
                    sx = x / sc
                    sy = y / sc
                }
                val lum = (0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)) / 255f
                // t: 0.5 = netral; <0.5 gelap, >0.5 terang.
                // darkCov: cakupan bagian gelap pola (untuk mask tint & AA).
                var t = 0.5f
                var darkCov = 0f
                when (preset) {
                    TexturePreset.GRAIN -> {
                        val n1 = valueNoise(sx * 1.6f, sy * 1.6f, 11)
                        val n2 = valueNoise(sx * 0.55f, sy * 0.55f, 17)
                        val n3 = hashNoise(sx.toInt(), sy.toInt(), 23)
                        val raw = (n1 - 0.5f) * 0.55f + (n2 - 0.5f) * 0.3f + (n3 - 0.5f) * 0.15f
                        // Respons film: grain memuncak di mid-tone, meredup
                        // di bayangan & highlight (signal-dependent).
                        val response = 0.35f + 0.65f *
                            (1f - kotlin.math.abs(lum - 0.5f) * 1.6f).coerceIn(0f, 1f)
                        val shaped = sign(raw) * sqrt(abs(raw)) * 1.3f * response
                        t = (0.5f + shaped).coerceIn(0.02f, 0.98f)
                    }
                    TexturePreset.PAPER -> {
                        val base = valueNoise(sx * 0.055f, sy * 0.055f, 23)
                        val mid = valueNoise(sx * 0.16f, sy * 0.16f, 29)
                        // Serat kertas mengikuti dua arah diagonal, bukan
                        // blok-blok kasar hasil hash pada koordinat bulat.
                        val fiber = valueNoise(
                            sx * 0.75f + sy * 0.31f, sy * 0.75f - sx * 0.31f, 41
                        )
                        val micro = hashNoise(sx.toInt(), sy.toInt(), 43) - 0.5f
                        t = (0.5f +
                            ((base * 0.45f + mid * 0.35f + fiber * 0.2f) - 0.5f) * 1.9f +
                            micro * 0.06f).coerceIn(0.02f, 0.98f)
                    }
                    TexturePreset.CANVAS -> {
                        // Anyaman kain = grid kotak lurus dengan variasi per kotak.
                        val cell = 12f
                        val line = 2.2f
                        val aa = edgeAA(sc, line)
                        val gx = fmod(sx, cell)
                        val gy = fmod(sy, cell)
                        darkCov = maxOf(
                            bandCoverage(gy, 0f, line, aa),
                            bandCoverage(gx, 0f, line, aa)
                        )
                        val seed = hashNoise(
                            floor(sx / cell).toInt(), floor(sy / cell).toInt(), 67
                        )
                        val thread = 0.63f + (seed - 0.5f) * 0.18f
                        t = thread - darkCov * (thread - 0.30f)
                    }
                    TexturePreset.HALFTONE -> {
                        // Screen tone 45°: u/v diputar 45° dari grid piksel,
                        // radius titik = kecerahan × skala texture.
                        val u = (sx + sy) * invSqrt2
                        val v = (sy - sx) * invSqrt2
                        val per = 6f
                        val gx = fmod(u, per) / per - 0.5f
                        val gy = fmod(v, per) / per - 0.5f
                        val dist = sqrt(gx * gx + gy * gy)
                        val r = (1f - lum) * (0.62f * sc).coerceIn(0.05f, 2.2f)
                        val aa = edgeAA(sc, r * 2f)
                        darkCov = 1f - smoothstep(r - aa, r + aa, dist)
                        t = 0.86f - darkCov * 0.72f
                    }
                    TexturePreset.STRIPES -> {
                        val per = 8f
                        val d = fmod(sx + sy, per)
                        darkCov = bandCoverage(d, 0f, per * 0.45f, edgeAA(sc, per * 0.45f))
                        t = 0.86f - darkCov * 0.72f
                    }
                    TexturePreset.DOTS -> {
                        val per = 7f
                        val gx = fmod(sx, per) / per - 0.5f
                        val gy = fmod(sy, per) / per - 0.5f
                        val dist = sqrt(gx * gx + gy * gy)
                        val r = 0.34f * sc
                        val aa = edgeAA(sc, r * 2f)
                        darkCov = 1f - smoothstep(r - aa, r + aa, dist)
                        t = 0.86f - darkCov * 0.72f
                    }
                    TexturePreset.CHEVRON -> {
                        val zigPer = 10f
                        val zigAmp = 5f
                        val stripePer = 7f
                        val xm = fmod(sx, zigPer)
                        val tri = if (xm < zigPer * 0.5f) xm else zigPer - xm
                        val yy = fmod(sy + tri / (zigPer * 0.5f) * zigAmp, stripePer)
                        darkCov = bandCoverage(
                            yy, 0f, stripePer * 0.5f, edgeAA(sc, stripePer * 0.5f)
                        )
                        t = 0.86f - darkCov * 0.72f
                    }
                    TexturePreset.BRICKS -> {
                        val bw = 20f
                        val bh = 10f
                        val mortar = 2.6f
                        val aa = edgeAA(sc, mortar)
                        val row = floor(sy / bh)
                        val colShift = fmod(row, 2f) * (bw * 0.5f)
                        val xx = fmod(sx + colShift, bw)
                        val yy = fmod(sy, bh)
                        darkCov = maxOf(
                            bandCoverage(yy, 0f, mortar, aa),
                            bandCoverage(xx, 0f, mortar, aa)
                        )
                        val seed = hashNoise(
                            floor((sx + colShift) / bw).toInt(), row.toInt(), 97
                        )
                        val brick = 0.63f + (seed - 0.5f) * 0.18f
                        t = brick - darkCov * (brick - 0.30f)
                    }
                    TexturePreset.NONE -> {}
                    // CUSTOM digambar lewat applyCustomTexturePixels; branch
                    // ini hanya untuk keeksahaan when.
                    TexturePreset.CUSTOM -> {}
                }

                val nr: Int
                val ng: Int
                val nb: Int
                if (hasTint) {
                    // Warna: pattern tegas mengisi bagian gelap (seperti tinta),
                    // pattern lembut menempel pada puncak struktur terang.
                    val mask = if (darkCov > 0f) {
                        darkCov
                    } else {
                        ((t - 0.5f) * 2f).coerceIn(0f, 1f)
                    }
                    val k = mask * s * tintK
                    val br = (p shr 16) and 0xFF
                    val bg = (p shr 8) and 0xFF
                    val bb = p and 0xFF
                    nr = (br + (tintR - br) * k).toInt().coerceIn(0, 255)
                    ng = (bg + (tintG - bg) * k).toInt().coerceIn(0, 255)
                    nb = (bb + (tintB - bb) * k).toInt().coerceIn(0, 255)
                } else {
                    // OVERLAY dua arah per kanal: t = 0.5 identitas.
                    val baseR = ((p shr 16) and 0xFF) / 255f
                    val baseG = ((p shr 8) and 0xFF) / 255f
                    val baseB = (p and 0xFF) / 255f
                    val oR = if (baseR < 0.5f) 2f * baseR * t else 1f - 2f * (1f - baseR) * (1f - t)
                    val oG = if (baseG < 0.5f) 2f * baseG * t else 1f - 2f * (1f - baseG) * (1f - t)
                    val oB = if (baseB < 0.5f) 2f * baseB * t else 1f - 2f * (1f - baseB) * (1f - t)
                    nr = ((baseR + (oR - baseR) * s) * 255f).toInt().coerceIn(0, 255)
                    ng = ((baseG + (oG - baseG) * s) * 255f).toInt().coerceIn(0, 255)
                    nb = ((baseB + (oB - baseB) * s) * 255f).toInt().coerceIn(0, 255)
                }
                out[y * width + x] = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    /**
     * Pratinjau texture: DIRENDER dari [applyTexturePixels] pada abu-abu.
     * Satu-satunya sumber kebenaran → thumbnail identik dengan hasil.
     */
    fun texturePreviewBitmap(preset: TexturePreset, tint: Int, size: Int = 72): Bitmap {
        val base = IntArray(size * size) { android.graphics.Color.argb(255, 148, 148, 148) }
        val out = applyTexturePixels(base, size, size, preset, 1f, 1f, tint, 0f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, size, 0, 0, size, size)
        return bmp
    }

    /**
     * Texture IMPORAN: bitmap pengguna digembokan (tile) di atas layer.
     * Sampling BILINEAR premultiplied dengan wrap, rotasi [angleDeg] derajat
     * di sekitar pusat bitmap. Alpha layer asli dipertahankan.
     */
    /** facade: core Rust bila tersedia, fallback Kotlin di JVM/test. */
    fun applyCustomTexturePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        texture: Bitmap,
        strength: Float,
        scale: Float,
        angleDeg: Float = 0f
    ): IntArray {
        if (RustBridge.available && strength > 0f && width > 0 && height > 0 &&
            pixels.size >= width * height && !texture.isRecycled &&
            texture.width > 0 && texture.height > 0
        ) {
            try {
                val tex = IntArray(texture.width * texture.height)
                texture.getPixels(tex, 0, texture.width, 0, 0, texture.width, texture.height)
                return applyCustomTexture(
                    pixels, width, height, tex,
                    texture.width, texture.height, strength, scale, angleDeg
                )
            } catch (t: Throwable) {
                Logger.w("Rust custom texture gagal, fallback Kotlin: ${t.message}")
            }
        }
        return applyCustomTexturePixelsKotlin(
            pixels, width, height, texture, strength, scale, angleDeg
        )
    }

    private fun applyCustomTexturePixelsKotlin(
        pixels: IntArray,
        width: Int,
        height: Int,
        texture: Bitmap,
        strength: Float,
        scale: Float,
        angleDeg: Float = 0f
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || width <= 0 || height <= 0 || pixels.size < width * height) return pixels
        if (texture.isRecycled || texture.width <= 0 || texture.height <= 0) return pixels
        val out = IntArray(pixels.size)
        val sc = scale.coerceAtLeast(0.2f)
        val tw = texture.width
        val th = texture.height
        val tex = IntArray(tw * th)
        texture.getPixels(tex, 0, tw, 0, 0, tw, th)
        val tileW = tw * sc
        val tileH = th * sc
        val rotated = angleDeg % 360f != 0f
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = (if (rotated) cos(rad) else 1.0).toFloat()
        val sinA = (if (rotated) sin(rad) else 0.0).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha == 0) {
                    out[y * width + x] = 0
                    continue
                }
                val sx: Float
                val sy: Float
                if (rotated) {
                    val dx = x - cx
                    val dy = y - cy
                    sx = dx * cosA + dy * sinA + cx
                    sy = -dx * sinA + dy * cosA + cy
                } else {
                    sx = x.toFloat()
                    sy = y.toFloat()
                }
                val fx = fmod(sx, tileW) / sc
                val fy = fmod(sy, tileH) / sc
                val tx0 = floor(fx).toInt().coerceIn(0, tw - 1)
                val ty0 = floor(fy).toInt().coerceIn(0, th - 1)
                val tx1 = (tx0 + 1) % tw
                val ty1 = (ty0 + 1) % th
                val fu = (fx - tx0).coerceIn(0f, 1f)
                val fv = (fy - ty0).coerceIn(0f, 1f)
                val c00 = tex[ty0 * tw + tx0]
                val c10 = tex[ty0 * tw + tx1]
                val c01 = tex[ty1 * tw + tx0]
                val c11 = tex[ty1 * tw + tx1]
                val w00 = (1f - fu) * (1f - fv)
                val w10 = fu * (1f - fv)
                val w01 = (1f - fu) * fv
                val w11 = fu * fv
                val a00 = ((c00 ushr 24) and 0xFF) / 255f
                val a10 = ((c10 ushr 24) and 0xFF) / 255f
                val a01 = ((c01 ushr 24) and 0xFF) / 255f
                val a11 = ((c11 ushr 24) and 0xFF) / 255f
                val aT = a00 * w00 + a10 * w10 + a01 * w01 + a11 * w11
                if (aT <= 1e-4f) {
                    out[y * width + x] = p
                    continue
                }
                val rP = (((c00 shr 16) and 0xFF) / 255f * a00) * w00 +
                    (((c10 shr 16) and 0xFF) / 255f * a10) * w10 +
                    (((c01 shr 16) and 0xFF) / 255f * a01) * w01 +
                    (((c11 shr 16) and 0xFF) / 255f * a11) * w11
                val gP = (((c00 shr 8) and 0xFF) / 255f * a00) * w00 +
                    (((c10 shr 8) and 0xFF) / 255f * a10) * w10 +
                    (((c01 shr 8) and 0xFF) / 255f * a01) * w01 +
                    (((c11 shr 8) and 0xFF) / 255f * a11) * w11
                val bP = ((c00 and 0xFF) / 255f * a00) * w00 +
                    ((c10 and 0xFF) / 255f * a10) * w10 +
                    ((c01 and 0xFF) / 255f * a01) * w01 +
                    ((c11 and 0xFF) / 255f * a11) * w11
                val baseR = ((p shr 16) and 0xFF) / 255f
                val baseG = ((p shr 8) and 0xFF) / 255f
                val baseB = (p and 0xFF) / 255f
                val e = s * aT
                val nr = (baseR + ((rP / aT).coerceIn(0f, 1f) - baseR) * e).toInt().coerceIn(0, 255)
                val ng = (baseG + ((gP / aT).coerceIn(0f, 1f) - baseG) * e).toInt().coerceIn(0, 255)
                val nb = (baseB + ((bP / aT).coerceIn(0f, 1f) - baseB) * e).toInt().coerceIn(0, 255)
                out[y * width + x] = (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }

    /** Modulo float yang selalu non-negatif (sampel rotasi bisa negatif). */
    private fun fmod(a: Float, b: Float): Float {
        val m = a % b
        return if (m < 0f) m + b else m
    }

    /** smoothstep(e0..e1) — tepi anti-alias pola. */
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = if (e1 == e0) (if (x < e0) 0f else 1f) else ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Cakupan band a..b dengan tepi anti-alias selebar aa (0..1). */
    private fun bandCoverage(v: Float, a: Float, b: Float, aa: Float): Float {
        return smoothstep(a - aa, a + aa, v) * (1f - smoothstep(b - aa, b + aa, v))
    }

    /**
     * Lebar tepi anti-alias (setengah transisi, ruang sampel):
     * maksimal ~0.5 px layar dan maksimal 30% tebal fitur — kalau tidak,
     * garis kotak melebur jadi bercak bulat.
     */
    private fun edgeAA(scale: Float, featureWidth: Float): Float {
        val pixel = 0.5f / scale.coerceAtLeast(0.2f)
        val fraction = (featureWidth.coerceAtLeast(0.05f) * 0.3f).coerceAtMost(0.6f)
        return pixel.coerceAtMost(fraction).coerceIn(0.02f, 0.6f)
    }

    private fun hashNoise(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
    }

    /**
     * Value noise: hash acak yang diinterpolasi halus (smoothstep), jadi
     * hasilnya berumpul menggulung — bukan statik per piksel. Dipakai untuk
     * struktur permukaan (kertas/kanvas) yang terlihat seperti tekstur
     * nyata, bukan seperti gangguan sinyal.
     */
    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val u = fx * fx * (3f - 2f * fx)
        val v = fy * fy * (3f - 2f * fy)
        val a = hashNoise(x0, y0, seed)
        val b = hashNoise(x0 + 1, y0, seed)
        val c = hashNoise(x0, y0 + 1, seed)
        val d = hashNoise(x0 + 1, y0 + 1, seed)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    /**
     * Bilinear dengan sampel keluar bitmap = transparan (jalur teks).
     * HASIL PREMULTIPLIED: kanal warna dikali alpha (c·a/255) — lihat
     * catatan di [sampleBilinearClamped].
     */
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
        // Premultiplied dari sumber (lihat catatan di sampleBilinearClamped).
        fun prem(shift: Int): Float = (
            ((p00 ushr shift) and 0xFF) * ((p00 ushr 24) and 0xFF) * w00 +
                ((p10 ushr shift) and 0xFF) * ((p10 ushr 24) and 0xFF) * w10 +
                ((p01 ushr shift) and 0xFF) * ((p01 ushr 24) and 0xFF) * w01 +
                ((p11 ushr shift) and 0xFF) * ((p11 ushr 24) and 0xFF) * w11
            ) / 255f
        fun rawA(): Float =
            ((p00 ushr 24) and 0xFF) * w00 +
                ((p10 ushr 24) and 0xFF) * w10 +
                ((p01 ushr 24) and 0xFF) * w01 +
                ((p11 ushr 24) and 0xFF) * w11
        val aF = rawA()
        fun ch(shift: Int): Int = (prem(shift) + 0.5f).toInt().coerceIn(0, 255)
        return ((aF + 0.5f).toInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
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
