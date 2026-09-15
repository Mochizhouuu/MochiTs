package com.mochits.core.imaging

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

class MaskSelectionTools(
    w: Int,
    h: Int
) {
    var width: Int = w.coerceIn(1, 32768)
        private set
    var height: Int = h.coerceIn(1, 32768)
        private set

    var maskBitmap: Bitmap = createSafeMaskBitmap(width, height)
        private set

    var rawMaskBitmap: Bitmap = createSafeMaskBitmap(width, height)
        private set

    var currentExpandPixels: Int = 0
        private set

    private var cachedMaskBytes: ByteArray? = null
    private var cachedRawMaskBytes: ByteArray? = null
    private var isMaskDirty: Boolean = true

    fun invalidateCache() {
        isMaskDirty = true
        cachedMaskBytes = null
        cachedRawMaskBytes = null
    }

    companion object {
        private fun createSafeMaskBitmap(w: Int, h: Int): Bitmap {
            return try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            } catch (t: Throwable) {
                t.printStackTrace()
                val safeW = w.coerceAtMost(2048)
                val safeH = h.coerceAtMost(4096)
                Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ALPHA_8)
            }
        }
    }

    val currentLassoPoints = mutableListOf<Offset>()
    private val lassoPathX = mutableListOf<Float>()
    private val lassoPathY = mutableListOf<Float>()
    private var lastPoint: Offset? = null

    fun resetSize(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) return
        // Jangan draw dari bitmap yang sudah di-recycle: di Robolectric NATIVE
        // itu native abort (exit 134), bukan sekadar exception.
        val oldMask = if (maskBitmap.isRecycled) null else maskBitmap
        val oldRawMask = if (rawMaskBitmap.isRecycled) null else rawMaskBitmap
        val newMask = createSafeMaskBitmap(newWidth, newHeight)
        val newRawMask = createSafeMaskBitmap(newWidth, newHeight)

        if (oldMask != null) {
            val canvas = android.graphics.Canvas(newMask)
            canvas.drawBitmap(oldMask, 0f, 0f, null)
        }

        if (oldRawMask != null) {
            val rawCanvas = android.graphics.Canvas(newRawMask)
            rawCanvas.drawBitmap(oldRawMask, 0f, 0f, null)
        }

        if (!maskBitmap.isRecycled) maskBitmap.recycle()
        if (!rawMaskBitmap.isRecycled) rawMaskBitmap.recycle()

        maskBitmap = newMask
        rawMaskBitmap = newRawMask
        width = newWidth
        height = newHeight
        invalidateCache()
    }

    fun startStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        invalidateCache()
        val radius = brushSize / 2f
        val draw = mode != MaskToolMode.ERASER

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                NativeBridge.nativeDrawCircle(rawMaskBitmap, point.x, point.y, radius, draw)
                NativeBridge.nativeDrawCircle(maskBitmap, point.x, point.y, radius, draw)
                lastPoint = point
            }
            MaskToolMode.LASSO -> {
                lassoPathX.clear()
                lassoPathY.clear()
                currentLassoPoints.clear()
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
                currentLassoPoints.add(point)
                lastPoint = point
            }
            else -> {}
        }
    }

    fun updateStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        invalidateCache()
        val radius = brushSize / 2f
        val draw = mode != MaskToolMode.ERASER

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                lastPoint?.let { prev ->
                    NativeBridge.nativeDrawLine(rawMaskBitmap, prev.x, prev.y, point.x, point.y, radius, draw)
                    NativeBridge.nativeDrawLine(maskBitmap, prev.x, prev.y, point.x, point.y, radius, draw)
                }
                lastPoint = point
            }
            MaskToolMode.LASSO -> {
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
                currentLassoPoints.add(point)
            }
            else -> {}
        }
    }

    fun endStroke(point: Offset, mode: MaskToolMode, brushSize: Float = 0f) {
        invalidateCache()
        when (mode) {
            MaskToolMode.LASSO -> {
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
                currentLassoPoints.add(point)
                if (lassoPathX.size >= 3) {
                    NativeBridge.nativeDrawPolygon(rawMaskBitmap, lassoPathX.toFloatArray(), lassoPathY.toFloatArray(), true)
                    applyExpandInternal()
                }
                lassoPathX.clear()
                lassoPathY.clear()
                currentLassoPoints.clear()
            }
            else -> {}
        }
        lastPoint = null
    }

    fun magicWandSelect(srcBitmap: Bitmap?, point: Offset, tolerance: Float, expandPixels: Int = currentExpandPixels) {
        invalidateCache()
        if (srcBitmap == null || srcBitmap.isRecycled) return
        // Reject taps outside the source image using float comparison first.
        // (Using toInt() directly would truncate -0.5 -> 0 and falsely hit the edge.)
        if (point.x < 0f || point.y < 0f || point.x >= srcBitmap.width.toFloat() || point.y >= srcBitmap.height.toFloat()) return
        if (maskBitmap.width != srcBitmap.width || maskBitmap.height != srcBitmap.height) {
            // Keep mask aligned with source; silently resync instead of writing out of bounds.
            resetSize(srcBitmap.width, srcBitmap.height)
        }
        val startX = kotlin.math.floor(point.x).toInt()
        val startY = kotlin.math.floor(point.y).toInt()
        // Map UI tolerance scale (0..100) to full RGB Euclidean distance (0..441.673f).
        // Euclidean (sphere) is tighter than per-channel Chebyshev (cube) and avoids
        // leaking into neighbouring colors diagonally (e.g. corner (81,81,81)).
        val mappedTolerance = (tolerance.coerceIn(0f, 100f) / 100f) * 441.673f
        currentExpandPixels = expandPixels.coerceIn(0, 30)
        NativeBridge.magicWandSelectSafe(srcBitmap, rawMaskBitmap, startX, startY, mappedTolerance)
        applyExpandInternal()
    }

    fun applyExpand(expandPixels: Int) {
        invalidateCache()
        currentExpandPixels = expandPixels.coerceIn(0, 30)
        applyExpandInternal()
    }

    private fun applyExpandInternal() {
        invalidateCache()
        if (currentExpandPixels <= 0) {
            NativeBridge.dilateMaskSafe(rawMaskBitmap, maskBitmap, 0)
        } else {
            NativeBridge.dilateMaskSafe(rawMaskBitmap, maskBitmap, currentExpandPixels)
        }
    }

    fun getRawMaskByteArray(): ByteArray? {
        val bmp = rawMaskBitmap
        if (bmp.isRecycled) return null
        if (!isMaskDirty && cachedRawMaskBytes != null) {
            return cachedRawMaskBytes?.clone()
        }
        return try {
            val buffer = java.nio.ByteBuffer.allocate(bmp.byteCount)
            bmp.copyPixelsToBuffer(buffer)
            val bytes = buffer.array().clone()
            cachedRawMaskBytes = bytes
            bytes.clone()
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun getMaskByteArray(): ByteArray? {
        val bmp = maskBitmap
        if (bmp.isRecycled) return null
        if (!isMaskDirty && cachedMaskBytes != null) {
            return cachedMaskBytes?.clone()
        }
        return try {
            val buffer = java.nio.ByteBuffer.allocate(bmp.byteCount)
            bmp.copyPixelsToBuffer(buffer)
            val bytes = buffer.array().clone()
            cachedMaskBytes = bytes
            bytes.clone()
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun restoreRawMaskByteArray(bytes: ByteArray) {
        val bmp = rawMaskBitmap
        if (bmp.isRecycled) return
        invalidateCache()
        try {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            bmp.copyPixelsFromBuffer(buffer)
            cachedRawMaskBytes = bytes.clone()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun restoreMaskByteArray(bytes: ByteArray) {
        val bmp = maskBitmap
        if (bmp.isRecycled) return
        invalidateCache()
        try {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            bmp.copyPixelsFromBuffer(buffer)
            cachedMaskBytes = bytes.clone()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun clearMask() {
        invalidateCache()
        NativeBridge.clearMaskSafe(rawMaskBitmap)
        NativeBridge.clearMaskSafe(maskBitmap)
    }

    fun invertMask() {
        invalidateCache()
        try {
            NativeBridge.nativeInvertMask(rawMaskBitmap)
            applyExpandInternal()
        } catch (e: UnsatisfiedLinkError) {
            for (y in 0 until rawMaskBitmap.height) {
                for (x in 0 until rawMaskBitmap.width) {
                    val current = rawMaskBitmap.getPixel(x, y) and 0xFF
                    rawMaskBitmap.setPixel(x, y, (255 - current) shl 24)
                }
            }
            applyExpandInternal()
        }
    }

    fun hasMask(): Boolean {
        return NativeBridge.hasMaskSafe(maskBitmap)
    }
}
