package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.mochits.app.model.RadialBlurMode
import com.mochits.app.model.TexturePreset
import com.mochits.app.text.MotionBlur
import kotlin.math.cos
import kotlin.math.floor
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
        customTexture: Bitmap? = null
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
                        scale = (textureScale.coerceIn(0.5f, 4f) * down).coerceAtLeast(0.2f)
                    )
                } else {
                    applyTexturePixels(
                        out, ww, hh,
                        preset = texturePreset,
                        strength = textureStrength.coerceIn(0f, 1f),
                        scale = (textureScale.coerceIn(0.5f, 4f) * down).coerceAtLeast(0.2f),
                        tint = textureColor
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
     * Texture deterministik tanpa aset: grain, kertas, kanvas, halftone
     * (titik komik), garis, dan titik pola. Struktur di-blend gaya OVERLAY:
     * menggelapkan area gelap & mencerahkan area terang sehingga tekstur
     * terlihat di semua warna (multiply saja membuat tekstur tak terlihat
     * di warna gelap). Bila [tint] diberi warna + alpha, pattern digambar
     * memakai warna itu (untuk halftone/garis/titik: mask pattern; untuk
     * grain/kertas/kanvas: puncak struktur). Alpha dipertahankan.
     */
    fun applyTexturePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        preset: TexturePreset,
        strength: Float,
        scale: Float,
        tint: Int = android.graphics.Color.TRANSPARENT
    ): IntArray {
        val s = strength.coerceIn(0f, 1f)
        if (preset == TexturePreset.NONE || s <= 0f) return pixels
        val out = IntArray(pixels.size)
        val sc = scale.coerceAtLeast(0.2f)
        // Siapkan tint di luar loop (dipakai tiap piksel).
        val tintAlpha = (tint ushr 24) and 0xFF
        val hasTint = tintAlpha > 8
        val tintR = (tint shr 16) and 0xFF
        val tintG = (tint shr 8) and 0xFF
        val tintB = tint and 0xFF
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
                // Struktur t: 0.5 = netral; <0.5 menggelapkan, >0.5
                // mencerahkan. Preset garis/titik/halftone memakai pola
                // tegas (hard mask) agar cocok diwarnai.
                val t: Float
                val hardMask: Boolean
                when (preset) {
                    TexturePreset.GRAIN -> {
                        t = 0.5f + (hashNoise(sx.toInt(), sy.toInt(), 11) - 0.5f) * 0.85f
                        hardMask = false
                    }
                    TexturePreset.PAPER -> {
                        val base = valueNoise(sx * 0.055f, sy * 0.055f, 23)
                        val mid = valueNoise(sx * 0.16f, sy * 0.16f, 29)
                        val fiber = hashNoise(
                            (sx * 0.75f + sy * 0.31f).toInt(),
                            (sy * 0.75f - sx * 0.31f).toInt(),
                            41
                        )
                        t = 0.5f + ((base * 0.5f + mid * 0.3f + fiber * 0.2f) - 0.5f) * 1.1f
                        hardMask = false
                    }
                    TexturePreset.CANVAS -> {
                        val tx = sin(sx * 0.9f)
                        val ty = sin(sy * 0.9f)
                        val grid = tx * tx * 0.55f + ty * ty * 0.45f
                        val cell = hashNoise((sx / 7f).toInt(), (sy / 7f).toInt(), 67)
                        t = 0.5f + ((grid * 0.72f + cell * 0.28f) - 0.5f) * 1.25f
                        hardMask = false
                    }
                    TexturePreset.HALFTONE -> {
                        // Titik komik (screen tone): radius titik mengikuti
                        // kecerahan piksel — makin gelap makin besar.
                        val lum = (0.299f * ((p shr 16) and 0xFF) +
                            0.587f * ((p shr 8) and 0xFF) +
                            0.114f * (p and 0xFF)) / 255f
                        val gx = (sx % 6f) / 6f - 0.5f
                        val gy = (sy % 6f) / 6f - 0.5f
                        t = if (sqrt(gx * gx + gy * gy) <= (1f - lum) * 0.68f) 0.14f else 0.86f
                        hardMask = true
                    }
                    TexturePreset.STRIPES -> {
                        // Garis diagonal tegas.
                        t = if (sin((sx + sy) * 0.85f) > 0f) 0.84f else 0.16f
                        hardMask = true
                    }
                    TexturePreset.DOTS -> {
                        // Titik pola radius tetap (polkadot).
                        val gx = (sx % 7f) / 7f - 0.5f
                        val gy = (sy % 7f) / 7f - 0.5f
                        t = if (sqrt(gx * gx + gy * gy) <= 0.3f) 0.14f else 0.86f
                        hardMask = true
                    }
                    TexturePreset.NONE -> {
                        t = 0.5f
                        hardMask = false
                    }
                    // CUSTOM digambar lewat applyCustomTexturePixels; branch
                    // ini hanya untuk keeksahaan when.
                    TexturePreset.CUSTOM -> {
                        t = 0.5f
                        hardMask = false
                    }
                }

                val nr: Int
                val ng: Int
                val nb: Int
                if (hasTint) {
                    // Mode berwarna: pattern digambar memakai warna tint.
                    // Hard mask = bagian gelap pattern; soft = puncak struktur.
                    val mask = if (hardMask) {
                        if (t < 0.5f) 1f else 0f
                    } else {
                        ((t - 0.5f) * 2f).coerceIn(0f, 1f)
                    }
                    val k = mask * s * (tintAlpha / 255f)
                    val br = (p shr 16) and 0xFF
                    val bg = (p shr 8) and 0xFF
                    val bb = p and 0xFF
                    nr = (br + (tintR - br) * k).toInt().coerceIn(0, 255)
                    ng = (bg + (tintG - bg) * k).toInt().coerceIn(0, 255)
                    nb = (bb + (tintB - bb) * k).toInt().coerceIn(0, 255)
                } else {
                    // Shading netral: overlay blend per kanal — tekstur
                    // terlihat di warna gelap & terang, t=0.5 tak berubah.
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
     * Texture IMPORAN: bitmap pengguna digembokan (tile) di atas layer.
     * Skala mengubah ukuran tile; strength mengatur opasitas pencampuran.
     * Alpha layer asli dipertahankan (texture hanya mengisi bentuk).
     */
    fun applyCustomTexturePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        texture: Bitmap,
        strength: Float,
        scale: Float
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
        // Skala tile: sx/sy dalam ruang tile yang diperbesar sc kali.
        val tileW = tw * sc
        val tileH = th * sc
        for (y in 0 until height) {
            val ty = (y % tileH.toInt().coerceAtLeast(1)) / sc
            val tyi = ty.toInt().coerceIn(0, th - 1)
            val rowTile = tyi * tw
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val alpha = (p ushr 24) and 0xFF
                if (alpha == 0) {
                    out[y * width + x] = 0
                    continue
                }
                val tx = (x % tileW.toInt().coerceAtLeast(1)) / sc
                val txi = tx.toInt().coerceIn(0, tw - 1)
                val tp = tex[rowTile + txi]
                val ta = ((tp ushr 24) and 0xFF) / 255f * s
                val br = (p shr 16) and 0xFF
                val bg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                val tr = (tp shr 16) and 0xFF
                val tg = (tp shr 8) and 0xFF
                val tb = tp and 0xFF
                val nr = (br + (tr - br) * ta).toInt().coerceIn(0, 255)
                val ng = (bg + (tg - bg) * ta).toInt().coerceIn(0, 255)
                val nb = (bb + (tb - bb) * ta).toInt().coerceIn(0, 255)
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
