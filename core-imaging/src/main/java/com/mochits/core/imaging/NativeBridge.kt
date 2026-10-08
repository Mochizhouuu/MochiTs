package com.mochits.core.imaging

import android.graphics.Bitmap

object NativeBridge {
    /** Sekali gagal dimuat, jangan pernah panggil native lagi (B4). */
    @Volatile
    var isNativeAvailable: Boolean = false
        private set

    init {
        isNativeAvailable = try {
            System.loadLibrary("imaging_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NativeBridge", "imaging_native gagal dimuat, pakai fallback JVM", e)
            false
        }
    }

    external fun nativeGetOpenCVVersion(): String

    // Native MaskSelection Operations
    external fun nativeDrawCircle(bitmap: Bitmap, cx: Float, cy: Float, radius: Float, draw: Boolean)
    external fun nativeDrawLine(bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, draw: Boolean)
    external fun nativeDrawPolygon(bitmap: Bitmap, pointsX: FloatArray, pointsY: FloatArray, draw: Boolean)
    external fun nativeMagicWandSelect(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float): Long
    external fun nativeDilateMask(srcMaskBitmap: Bitmap, dstMaskBitmap: Bitmap, radius: Int)
    external fun nativeClearMask(bitmap: Bitmap)
    external fun nativeInvertMask(bitmap: Bitmap)
    external fun nativeHasMask(bitmap: Bitmap): Boolean

    // Fallback implementations for host-side unit tests when native library is not present
    // @return pixel ter-seleksi, atau -1 bila gagal (ukuran/seed) agar
    // pemanggil bisa melapor, bukan mengklaim sukses.
    private fun fallbackMagicWandSelect(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float): Long {
        val w = srcBitmap.width
        val h = srcBitmap.height
        if (maskBitmap.width != w || maskBitmap.height != h) return -1L
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return -1L

        val pixels = IntArray(w * h)
        srcBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Premultiply SEKALI di awal (ruang native): tanpa ini tiap
        // perbandingan alokasi IntArray per piksel (hang di gambar besar).
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            if (a < 255) {
                pixels[i] = (a shl 24) or ((((c ushr 16) and 0xFF) * a / 255) shl 16) or
                    ((((c ushr 8) and 0xFF) * a / 255) shl 8) or ((c and 0xFF) * a / 255)
            }
        }

        val targetColor = pixels[startY * w + startX]
        val targetR = (targetColor ushr 16) and 0xFF
        val targetG = (targetColor ushr 8) and 0xFF
        val targetB = targetColor and 0xFF
        val targetA = (targetColor ushr 24) and 0xFF

        // Euclidean RGB distance (tolerance is already mapped to 0..441.673).
        // Tighter than per-channel Chebyshev; prevents diagonal color leaks.
        val tolSq = tolerance * tolerance
        val buffer = java.nio.ByteBuffer.allocate(maskBitmap.byteCount)
        maskBitmap.copyPixelsToBuffer(buffer)
        val maskPixels = buffer.array()
        // Offset byte = y * rowBytes + x (stride-aware; padding ikut tersalin
        // utuh oleh copyPixelsToBuffer/FromBuffer).
        val rowBytes = maskBitmap.rowBytes

        fun colorMatches(c: Int): Boolean {
            val dr = ((c ushr 16) and 0xFF) - targetR
            val dg = ((c ushr 8) and 0xFF) - targetG
            val db = (c and 0xFF) - targetB
            val da = ((c ushr 24) and 0xFF) - targetA
            return (dr * dr + dg * dg + db * db + da * da).toFloat() <= tolSq
        }

        // Span flood ala Paintroid: antrean berisi rentang baris (bukan
        // piksel) -> hasil komponen terhubung SAMA, memori kecil.
        val filled = BooleanArray(w * h)
        var selectedCount = 0L

        fun matchAt(x: Int, y: Int): Boolean = colorMatches(pixels[y * w + x])

        fun fillRun(y: Int, xa: Int, xb: Int) {
            val off = y * w
            val mb = y * rowBytes
            for (x in xa..xb) {
                filled[off + x] = true
                maskPixels[mb + x] = 0xFF.toByte()
                selectedCount++
            }
        }

        var sx1 = startX
        var sx2 = startX
        while (sx1 - 1 >= 0 && matchAt(sx1 - 1, startY)) sx1--
        while (sx2 + 1 < w && matchAt(sx2 + 1, startY)) sx2++
        fillRun(startY, sx1, sx2)

        data class Span(val y: Int, val x1: Int, val x2: Int, val dy: Int)
        val stack = ArrayDeque<Span>()
        stack.addLast(Span(startY, sx1, sx2, 1))
        stack.addLast(Span(startY, sx1, sx2, -1))
        while (stack.isNotEmpty()) {
            val s = stack.removeLast()
            val ny = s.y + s.dy
            if (ny < 0 || ny >= h) continue
            val nOff = ny * w
            var x = s.x1
            while (x <= s.x2) {
                if (filled[nOff + x] || !matchAt(x, ny)) {
                    x++
                    continue
                }
                var nx1 = x
                var nx2 = x
                while (nx1 - 1 >= 0 && !filled[nOff + nx1 - 1] && matchAt(nx1 - 1, ny)) nx1--
                while (nx2 + 1 < w && !filled[nOff + nx2 + 1] && matchAt(nx2 + 1, ny)) nx2++
                fillRun(ny, nx1, nx2)
                stack.addLast(Span(ny, nx1, nx2, s.dy))
                stack.addLast(Span(ny, nx1, nx2, -s.dy))
                x = nx2 + 1
            }
        }

        val outBuffer = java.nio.ByteBuffer.wrap(maskPixels)
        maskBitmap.copyPixelsFromBuffer(outBuffer)
        return selectedCount
    }

    private fun fallbackClearMask(bitmap: Bitmap) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, 0)
            }
        }
    }

    private fun fallbackHasMask(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pix = bitmap.getPixel(x, y)
                if ((pix ushr 24) > 0 || (pix and 0xFF) > 0) return true
            }
        }
        return false
    }

    private fun fallbackDilateMask(srcMaskBitmap: Bitmap, dstMaskBitmap: Bitmap, radius: Int) {
        val w = srcMaskBitmap.width
        val h = srcMaskBitmap.height
        if (dstMaskBitmap.width != w || dstMaskBitmap.height != h) return

        if (radius <= 0) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    dstMaskBitmap.setPixel(x, y, srcMaskBitmap.getPixel(x, y))
                }
            }
            return
        }

        // Copy source to dest first
        for (y in 0 until h) {
            for (x in 0 until w) {
                dstMaskBitmap.setPixel(x, y, srcMaskBitmap.getPixel(x, y))
            }
        }

        val r2 = radius * radius
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pix = srcMaskBitmap.getPixel(x, y)
                if ((pix ushr 24) > 0 || (pix and 0xFF) > 0) {
                    val minY = (y - radius).coerceAtLeast(0)
                    val maxY = (y + radius).coerceAtMost(h - 1)
                    val minX = (x - radius).coerceAtLeast(0)
                    val maxX = (x + radius).coerceAtMost(w - 1)
                    for (ny in minY..maxY) {
                        val dy = ny - y
                        val dy2 = dy * dy
                        for (nx in minX..maxX) {
                            val dx = nx - x
                            if (dx * dx + dy2 <= r2) {
                                dstMaskBitmap.setPixel(nx, ny, android.graphics.Color.WHITE)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Kontrak: ok + jumlah piksel terseleksi. Gagal (native status != 0
     * atau fallback -1/exception) → ok=false agar UI melapor, bukan sunyi.
     */
    data class WandOutcome(val ok: Boolean, val selectedCount: Long)

    fun magicWandSelectSafe(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float): WandOutcome {
        if (isNativeAvailable) {
            try {
                val res = nativeMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
                val status = (res and 0xFFFFFFFFL).toInt()
                if (status == 0) return WandOutcome(true, res ushr 32)
                android.util.Log.w("NativeBridge", "wand native status=$status, tanpa fallback sunyi")
                // Status fatal (format/lock/OOM) atau ukuran/seed salah:
                // JANGAN fallback diam-diam (hang/OOM ganda), laporkan gagal.
                return WandOutcome(false, 0L)
            } catch (_: Throwable) {
            }
        }
        return try {
            // Fallback belum mendukung Tutup celah (terdokumentasi).
            val count = fallbackMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
            if (count >= 0L) WandOutcome(true, count) else WandOutcome(false, 0L)
        } catch (_: Throwable) {
            WandOutcome(false, 0L)
        }
    }

    fun dilateMaskSafe(srcMaskBitmap: Bitmap, dstMaskBitmap: Bitmap, radius: Int) {
        try {
            nativeDilateMask(srcMaskBitmap, dstMaskBitmap, radius)
        } catch (e: UnsatisfiedLinkError) {
            fallbackDilateMask(srcMaskBitmap, dstMaskBitmap, radius)
        }
    }

    fun clearMaskSafe(bitmap: Bitmap) {
        try {
            nativeClearMask(bitmap)
        } catch (e: UnsatisfiedLinkError) {
            fallbackClearMask(bitmap)
        }
    }

    fun hasMaskSafe(bitmap: Bitmap): Boolean {
        return try {
            nativeHasMask(bitmap)
        } catch (e: UnsatisfiedLinkError) {
            fallbackHasMask(bitmap)
        }
    }

    // ---------- Draw/invert Safe + fallback JVM (B4) ----------

    private fun paintCircleInto(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float, color: Int) {
        if (radius <= 0f) return
        val r2 = radius * radius
        val y0 = maxOf(0, (cy - radius).toInt())
        val y1 = minOf(h - 1, (cy + radius).toInt())
        val x0 = maxOf(0, (cx - radius).toInt())
        val x1 = minOf(w - 1, (cx + radius).toInt())
        for (y in y0..y1) {
            val dy = y - cy
            var idx = y * w + x0
            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) px[idx] = color
                idx++
            }
        }
    }

    private fun fallbackDrawCircle(bitmap: Bitmap, cx: Float, cy: Float, radius: Float, draw: Boolean) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        paintCircleInto(px, w, h, cx, cy, radius, if (draw) 0xFFFFFFFF.toInt() else 0)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun fallbackDrawLine(
        bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, draw: Boolean
    ) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val color = if (draw) 0xFFFFFFFF.toInt() else 0
        val dx = x1 - x0
        val dy = y1 - y0
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val steps = maxOf(1, (dist / maxOf(radius / 2f, 1f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            paintCircleInto(px, w, h, x0 + dx * t, y0 + dy * t, radius, color)
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun fallbackDrawPolygon(bitmap: Bitmap, pointsX: FloatArray, pointsY: FloatArray, draw: Boolean) {
        val n = pointsX.size
        if (n < 3 || pointsY.size != n) return
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val color = if (draw) 0xFFFFFFFF.toInt() else 0
        for (y in 0 until h) {
            val fy = y.toFloat()
            // Kumpulkan perpotongan scanline dengan sisi poligon (even-odd).
            var count = 0
            val xs = FloatArray(n)
            for (i in 0 until n) {
                val j = (i + 1) % n
                val y1 = pointsY[i]
                val y2 = pointsY[j]
                if ((y1 <= fy && fy < y2) || (y2 <= fy && fy < y1)) {
                    val x1 = pointsX[i]
                    val x2 = pointsX[j]
                    xs[count++] = x1 + (fy - y1) / (y2 - y1) * (x2 - x1)
                }
            }
            xs.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                val xa = maxOf(0, xs[k].toInt())
                val xb = minOf(w - 1, xs[k + 1].toInt())
                for (x in xa..xb) px[y * w + x] = color
                k += 2
            }
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun fallbackInvertMask(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = 255 - ((px[i] ushr 24) and 0xFF)
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    private inline fun <T> runNativeOrFallback(crossinline native: () -> T, crossinline fallback: () -> T): T {
        if (isNativeAvailable) {
            try {
                return native()
            } catch (e: UnsatisfiedLinkError) {
                isNativeAvailable = false
            }
        }
        return fallback()
    }

    fun drawCircleSafe(bitmap: Bitmap, cx: Float, cy: Float, radius: Float, draw: Boolean) {
        runNativeOrFallback(
            { nativeDrawCircle(bitmap, cx, cy, radius, draw) },
            { fallbackDrawCircle(bitmap, cx, cy, radius, draw) }
        )
    }

    fun drawLineSafe(
        bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, draw: Boolean
    ) {
        runNativeOrFallback(
            { nativeDrawLine(bitmap, x0, y0, x1, y1, radius, draw) },
            { fallbackDrawLine(bitmap, x0, y0, x1, y1, radius, draw) }
        )
    }

    fun drawPolygonSafe(bitmap: Bitmap, pointsX: FloatArray, pointsY: FloatArray, draw: Boolean) {
        runNativeOrFallback(
            { nativeDrawPolygon(bitmap, pointsX, pointsY, draw) },
            { fallbackDrawPolygon(bitmap, pointsX, pointsY, draw) }
        )
    }

    fun invertMaskSafe(bitmap: Bitmap) {
        runNativeOrFallback(
            { nativeInvertMask(bitmap) },
            { fallbackInvertMask(bitmap) }
        )
    }

    // Native OpenCV Inpaint
    external fun nativeInpaintTelea(srcBitmap: Bitmap, maskBitmap: Bitmap, dstBitmap: Bitmap, radius: Float): Boolean
}
