package com.mochits.app.imaging

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Warp perspektif: memetakan persegi satuan ke segiempat bebas (quad)
 * lewat homografi 3x3.
 *
 * Murni operasi piksel Kotlin tanpa RenderEffect/RenderScript sehingga
 * berjalan di semua level API (wajib support Android 11 ke bawah).
 *
 * @param quad 8 float ternormalisasi [0..1]: TL, TR, BR, BL
 * (x0,y0, x1,y1, x2,y2, x3,y3). null/identitas = tanpa warp.
 */
object Perspective {

    /** Grid warp mesh: N x N titik, row-major, 2*N*N float ternormalisasi. */
    const val MESH_N = 4

    /** Grid identitas (seragam): titik (i/(N-1), j/(N-1)). */
    fun identityGrid(): List<Float> {
        val n = MESH_N
        val out = ArrayList<Float>(2 * n * n)
        for (j in 0 until n) {
            for (i in 0 until n) {
                out.add(i.toFloat() / (n - 1))
                out.add(j.toFloat() / (n - 1))
            }
        }
        return out
    }

    /** true bila grid valid dan benar-benar mengubah bentuk. */
    fun isMeshActive(grid: List<Float>?): Boolean {
        if (grid == null || grid.size != 2 * MESH_N * MESH_N) return false
        if (grid.any { !it.isFinite() }) return false
        val id = identityGrid()
        for (i in grid.indices) {
            if (kotlin.math.abs(grid[i] - id[i]) > 1e-4f) return true
        }
        return false
    }

    /**
     * Gambar [bitmap] mengikuti grid mesh via drawBitmapMesh bawaan
     * (native, full-res, API 1+). Grid dinormalisasi ke box konten
     * (originX/Y, contentW/H dalam koordinat kanvas tujuan).
     * @return true bila digambar.
     */
    fun drawMeshBitmap(
        canvas: android.graphics.Canvas,
        bitmap: android.graphics.Bitmap,
        originX: Float,
        originY: Float,
        contentW: Float,
        contentH: Float,
        grid: List<Float>?,
        paint: android.graphics.Paint
    ): Boolean {
        if (!isMeshActive(grid)) return false
        if (bitmap.isRecycled || contentW <= 0f || contentH <= 0f) return false
        val n = MESH_N
        val g = grid!!
        if (g.size != 2 * n * n) return false
        return try {
            val verts = FloatArray(2 * n * n)
            for (j in 0 until n) {
                for (i in 0 until n) {
                    val k = (j * n + i) * 2
                    verts[k] = originX + g[k].coerceIn(-0.5f, 1.5f) * contentW
                    verts[k + 1] = originY + g[k + 1].coerceIn(-0.5f, 1.5f) * contentH
                }
            }
            canvas.drawBitmapMesh(bitmap, n - 1, n - 1, verts, 0, null, 0, paint)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Hasil warp: piksel + ukuran + offset kiri-atas relatif konten asal. */
    data class WarpedPixels(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val offsetX: Float,
        val offsetY: Float,
        /** Skala kerja (output = span*scale); bagi ukuran output dengan ini. */
        val scale: Float
    )

    private val IDENTITY = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /** true bila quad valid (8 angka) dan benar-benar mengubah bentuk. */
    fun isActive(quad: List<Float>?): Boolean {
        if (quad == null || quad.size != 8) return false
        if (quad.any { !it.isFinite() }) return false
        for (i in 0 until 8) {
            if (abs(quad[i] - IDENTITY[i]) > 1e-4f) return true
        }
        return false
    }

    /**
     * Warp [pixels] (w*h) mengikuti [quad] ternormalisasi.
     * @return null bila quad tidak aktif atau degenerat (luas ~nol).
     */
    fun warpPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        quad: List<Float>,
        maxDim: Int = 768
    ): WarpedPixels? {
        if (!isActive(quad)) return null
        if (pixels.size != width * height || width <= 0 || height <= 0) return null

        // Quad dalam piksel.
        val qx = DoubleArray(4) { i -> quad[i * 2].toDouble() * width }
        val qy = DoubleArray(4) { i -> quad[i * 2 + 1].toDouble() * height }

        // Batas output + skala kerja agar interaktif.
        var minX = qx[0]; var maxX = qx[0]
        var minY = qy[0]; var maxY = qy[0]
        for (i in 1 until 4) {
            if (qx[i] < minX) minX = qx[i]
            if (qx[i] > maxX) maxX = qx[i]
            if (qy[i] < minY) minY = qy[i]
            if (qy[i] > maxY) maxY = qy[i]
        }
        val span = maxOf(maxX - minX, maxY - minY)
        if (span <= 0.5) return null
        val scale = min(1.0, maxDim / span)
        val ow = maxOf(1, ceil((maxX - minX) * scale).toInt())
        val oh = maxOf(1, ceil((maxY - minY) * scale).toInt())
        if (ow * oh > 1600 * 1600) return null

        // Homografi: persegi satuan -> quad (ruang kerja).
        val src = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val dst = DoubleArray(8) { i ->
            if (i % 2 == 0) (qx[i / 2] - minX) * scale else (qy[i / 2] - minY) * scale
        }
        val h = solveHomography(src, dst) ?: return null
        val hi = invert3x3(h) ?: return null

        val out = IntArray(ow * oh)
        val wMax = (width - 1).toDouble()
        val hMax = (height - 1).toDouble()
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                // Petakan balik ke ruang satuan, lalu ke piksel sumber.
                val px = ox + 0.5
                val py = oy + 0.5
                val wInv = hi[6] * px + hi[7] * py + hi[8]
                if (abs(wInv) < 1e-9) continue
                val su = (hi[0] * px + hi[1] * py + hi[2]) / wInv
                val sv = (hi[3] * px + hi[4] * py + hi[5]) / wInv
                if (su < 0.0 || su > 1.0 || sv < 0.0 || sv > 1.0) continue
                val fx = (su * wMax).coerceIn(0.0, wMax)
                val fy = (sv * hMax).coerceIn(0.0, hMax)
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = min(x0 + 1, width - 1)
                val y1 = min(y0 + 1, height - 1)
                val tx = fx - x0
                val ty = fy - y0
                val p00 = pixels[y0 * width + x0]
                val p10 = pixels[y0 * width + x1]
                val p01 = pixels[y1 * width + x0]
                val p11 = pixels[y1 * width + x1]
                // Bilinear premultiplied: tepi warp tidak menggelap.
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                fun acc(pxv: Int, wgt: Double) {
                    val pa = ((pxv ushr 24) and 0xFF).toDouble()
                    a += wgt * pa
                    r += wgt * pa * ((pxv shr 16) and 0xFF)
                    g += wgt * pa * ((pxv shr 8) and 0xFF)
                    b += wgt * pa * (pxv and 0xFF)
                }
                acc(p00, (1 - tx) * (1 - ty))
                acc(p10, tx * (1 - ty))
                acc(p01, (1 - tx) * ty)
                acc(p11, tx * ty)
                if (a >= 0.5) {
                    out[oy * ow + ox] =
                        (((a + 0.5).toInt().coerceIn(0, 255)) shl 24) or
                            (((r / a + 0.5).toInt().coerceIn(0, 255)) shl 16) or
                            (((g / a + 0.5).toInt().coerceIn(0, 255)) shl 8) or
                            ((b / a + 0.5).toInt().coerceIn(0, 255))
                }
            }
        }
        return WarpedPixels(out, ow, oh, minX.toFloat(), minY.toFloat(), scale.toFloat())
    }

    /**
     * Selesaikan homografi 3x3 (8 DOF) dari 4 korespondensi titik
     * via eliminasi Gauss 8x8. @return 9 elemen baris-mayor, atau null
     * bila degenerat.
     */
    fun solveHomography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        if (src.size != 8 || dst.size != 8) return null
        // Matriks augmented 8x9.
        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i * 2]; val y = src[i * 2 + 1]
            val u = dst[i * 2]; val v = dst[i * 2 + 1]
            m[i * 2][0] = x; m[i * 2][1] = y; m[i * 2][2] = 1.0
            m[i * 2][6] = -u * x; m[i * 2][7] = -u * y; m[i * 2][8] = u
            m[i * 2 + 1][3] = x; m[i * 2 + 1][4] = y; m[i * 2 + 1][5] = 1.0
            m[i * 2 + 1][6] = -v * x; m[i * 2 + 1][7] = -v * y; m[i * 2 + 1][8] = v
        }
        for (col in 0 until 8) {
            var pivot = col
            var best = abs(m[col][col])
            for (row in col + 1 until 8) {
                val v = abs(m[row][col])
                if (v > best) { best = v; pivot = row }
            }
            if (best < 1e-12) return null
            if (pivot != col) {
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            }
            val div = m[col][col]
            for (k in col until 9) m[col][k] /= div
            for (row in 0 until 8) {
                if (row == col) continue
                val f = m[row][col]
                if (f != 0.0) {
                    for (k in col until 9) m[row][k] -= f * m[col][k]
                }
            }
        }
        return DoubleArray(9) { i -> if (i < 8) m[i][8] else 1.0 }
    }

    /** Invers matriks 3x3 baris-mayor; null bila singular. */
    fun invert3x3(h: DoubleArray): DoubleArray? {
        if (h.size != 9) return null
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val hh = h[7]; val ii = h[8]
        val det = a * (e * ii - f * hh) - b * (d * ii - f * g) + c * (d * hh - e * g)
        if (abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * ii - f * hh) / det, (c * hh - b * ii) / det, (b * f - c * e) / det,
            (f * g - d * ii) / det, (a * ii - c * g) / det, (c * d - a * f) / det,
            (d * hh - e * g) / det, (b * g - a * hh) / det, (a * e - b * d) / det
        )
    }
}
