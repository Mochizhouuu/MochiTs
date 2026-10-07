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
    external fun nativeMagicWandSelect(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float): Int
    external fun nativeDilateMask(srcMaskBitmap: Bitmap, dstMaskBitmap: Bitmap, radius: Int)
    external fun nativeClearMask(bitmap: Bitmap)
    external fun nativeInvertMask(bitmap: Bitmap)
    external fun nativeHasMask(bitmap: Bitmap): Boolean

    // Fallback implementations for host-side unit tests when native library is not present
    private fun fallbackMagicWandSelect(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float) {
        val w = srcBitmap.width
        val h = srcBitmap.height
        if (maskBitmap.width != w || maskBitmap.height != h) return
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return

        val pixels = IntArray(w * h)
        srcBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val targetColor = pixels[startY * w + startX]
        val targetR = (targetColor ushr 16) and 0xFF
        val targetG = (targetColor ushr 8) and 0xFF
        val targetB = targetColor and 0xFF

        // Euclidean RGB distance (tolerance is already mapped to 0..441.673).
        // Tighter than per-channel Chebyshev; prevents diagonal color leaks.
        val tolSq = tolerance * tolerance
        val visited = BooleanArray(w * h)
        val queueInt = IntArray(w * h)
        var head = 0
        var tail = 0

        queueInt[tail++] = startY * w + startX
        visited[startY * w + startX] = true

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
            return (dr * dr + dg * dg + db * db).toFloat() <= tolSq
        }

        while (head < tail) {
            val idx = queueInt[head++]
            val cx = idx % w
            val cy = idx / w

            maskPixels[cy * rowBytes + cx] = 0xFF.toByte()

            if (cy > 0) {
                val nIdx = idx - w
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    if (colorMatches(pixels[nIdx])) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
            if (cy < h - 1) {
                val nIdx = idx + w
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    if (colorMatches(pixels[nIdx])) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
            if (cx > 0) {
                val nIdx = idx - 1
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    if (colorMatches(pixels[nIdx])) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
            if (cx < w - 1) {
                val nIdx = idx + 1
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    if (colorMatches(pixels[nIdx])) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
        }

        val outBuffer = java.nio.ByteBuffer.wrap(maskPixels)
        maskBitmap.copyPixelsFromBuffer(outBuffer)
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
     * Kontrak: true bila seleksi berjalan (native status 0 atau fallback
     * selesai). False → pemanggil WAJIB memberi tahu UI (jangan sunyi).
     */
    fun magicWandSelectSafe(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float): Boolean {
        if (isNativeAvailable) {
            try {
                if (nativeMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance) == 0) return true
            } catch (_: Throwable) {
            }
        }
        return try {
            fallbackMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
            true
        } catch (_: Throwable) {
            false
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
