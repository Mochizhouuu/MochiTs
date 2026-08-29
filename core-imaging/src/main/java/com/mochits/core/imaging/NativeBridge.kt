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
    external fun nativeClearMask(bitmap: Bitmap)
    external fun nativeInvertMask(bitmap: Bitmap)
    external fun nativeHasMask(bitmap: Bitmap): Boolean

    // Fallback implementations for host-side unit tests when native library is not present
    private fun fallbackMagicWandSelect(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float) {
        val w = srcBitmap.width
        val h = srcBitmap.height
        if (maskBitmap.width != w || maskBitmap.height != h) return
        if (startX < 0 || startX >= w || startY < 0 || startY >= h) return

        val targetColor = srcBitmap.getPixel(startX, startY)
        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        val tolSq = tolerance * tolerance
        val visited = BooleanArray(w * h)
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()

        queue.add(Pair(startX, startY))
        visited[startY * w + startX] = true

        val dx = intArrayOf(0, 0, -1, 1)
        val dy = intArrayOf(-1, 1, 0, 0)

        while (queue.isNotEmpty()) {
            val element = queue.poll() ?: break
            val (cx, cy) = element
            maskBitmap.setPixel(cx, cy, android.graphics.Color.WHITE) // 255 alpha

            for (i in 0 until 4) {
                val nx = cx + dx[i]
                val ny = cy + dy[i]
                if (nx in 0 until w && ny in 0 until h) {
                    val nIdx = ny * w + nx
                    if (!visited[nIdx]) {
                        visited[nIdx] = true
                        val c = srcBitmap.getPixel(nx, ny)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF

                        val dr = (r - targetR).toFloat()
                        val dg = (g - targetG).toFloat()
                        val db = (b - targetB).toFloat()
                        val distSq = dr * dr + dg * dg + db * db

                        if (distSq <= tolSq) {
                            queue.add(Pair(nx, ny))
                        }
                    }
                }
            }
        }
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
                if ((bitmap.getPixel(x, y) and 0xFF) > 0) return true
            }
        }
        return false
    }

    fun magicWandSelectSafe(srcBitmap: Bitmap, maskBitmap: Bitmap, startX: Int, startY: Int, tolerance: Float) {
        try {
            nativeMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
        } catch (e: UnsatisfiedLinkError) {
            fallbackMagicWandSelect(srcBitmap, maskBitmap, startX, startY, tolerance)
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
