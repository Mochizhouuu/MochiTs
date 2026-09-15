package com.mochits.core.imaging

import android.graphics.Bitmap

object NativeBridge {
    init {
        try {
            System.loadLibrary("imaging_native")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun nativeGetOpenCVVersion(): String

    // Native MaskSelection Operations
    external fun nativeDrawCircle(bitmap: Bitmap, cx: Float, cy: Float, radius: Float, draw: Boolean)
    external fun nativeDrawLine(bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, draw: Boolean)
    external fun nativeDrawPolygon(bitmap: Bitmap, pointsX: FloatArray, pointsY: FloatArray, draw: Boolean)
    external fun nativeMagicWandSelect(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float)
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

        val tol = tolerance.toInt()
        val visited = BooleanArray(w * h)
        val queueInt = IntArray(w * h)
        var head = 0
        var tail = 0

        queueInt[tail++] = startY * w + startX
        visited[startY * w + startX] = true

        val buffer = java.nio.ByteBuffer.allocate(w * h)
        maskBitmap.copyPixelsToBuffer(buffer)
        val maskPixels = buffer.array()

        while (head < tail) {
            val idx = queueInt[head++]
            val cx = idx % w
            val cy = idx / w

            maskPixels[idx] = 0xFF.toByte()

            if (cy > 0) {
                val nIdx = idx - w
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    val c = pixels[nIdx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (kotlin.math.abs(r - targetR) <= tol &&
                        kotlin.math.abs(g - targetG) <= tol &&
                        kotlin.math.abs(b - targetB) <= tol) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
            if (cy < h - 1) {
                val nIdx = idx + w
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    val c = pixels[nIdx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (kotlin.math.abs(r - targetR) <= tol &&
                        kotlin.math.abs(g - targetG) <= tol &&
                        kotlin.math.abs(b - targetB) <= tol) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
            if (cx > 0) {
                val nIdx = idx - 1
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    val c = pixels[nIdx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (kotlin.math.abs(r - targetR) <= tol &&
                        kotlin.math.abs(g - targetG) <= tol &&
                        kotlin.math.abs(b - targetB) <= tol) {
                        queueInt[tail++] = nIdx
                    }
                }
            }
            if (cx < w - 1) {
                val nIdx = idx + 1
                if (!visited[nIdx]) {
                    visited[nIdx] = true
                    val c = pixels[nIdx]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (kotlin.math.abs(r - targetR) <= tol &&
                        kotlin.math.abs(g - targetG) <= tol &&
                        kotlin.math.abs(b - targetB) <= tol) {
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

    fun magicWandSelectSafe(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float) {
        try {
            nativeMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
        } catch (e: UnsatisfiedLinkError) {
            fallbackMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
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

    // Native OpenCV Inpaint
    external fun nativeInpaintTelea(srcBitmap: Bitmap, maskBitmap: Bitmap, dstBitmap: Bitmap, radius: Float): Boolean
}
