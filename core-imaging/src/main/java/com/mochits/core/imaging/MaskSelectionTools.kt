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

    /** Kunci semua baca/tulis mask (flood latar vs gambar overlay vs undo). */
    val maskLock = Any()

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
        synchronized(maskLock) {
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
        // Catat ukuran ASLI (bukan yang diminta): alokasi bisa dipotong
        // saat memori sempit; kalau tidak, resync tak pernah terjadi lagi.
        width = newMask.width
        height = newMask.height
        invalidateCache()
        }
    }

    fun startStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        synchronized(maskLock) {
        invalidateCache()
        val radius = brushSize / 2f
        val draw = mode != MaskToolMode.ERASER

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                NativeBridge.drawCircleSafe(rawMaskBitmap, point.x, point.y, radius, draw)
                NativeBridge.drawCircleSafe(maskBitmap, point.x, point.y, radius, draw)
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
    }

    fun updateStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        synchronized(maskLock) {
        invalidateCache()
        val radius = brushSize / 2f
        val draw = mode != MaskToolMode.ERASER

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                lastPoint?.let { prev ->
                    NativeBridge.drawLineSafe(rawMaskBitmap, prev.x, prev.y, point.x, point.y, radius, draw)
                    NativeBridge.drawLineSafe(maskBitmap, prev.x, prev.y, point.x, point.y, radius, draw)
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
    }

    fun endStroke(point: Offset, mode: MaskToolMode, brushSize: Float = 0f) {
        synchronized(maskLock) {
        invalidateCache()
        when (mode) {
            MaskToolMode.LASSO -> {
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
                currentLassoPoints.add(point)
                if (lassoPathX.size >= 3) {
                    NativeBridge.drawPolygonSafe(rawMaskBitmap, lassoPathX.toFloatArray(), lassoPathY.toFloatArray(), true)
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
    }

    /**
     * Hotfix review: seed = piksel yang diketuk, tanpa heuristik.
     * Ketuk menumpuk ke seleksi (union); Bersihkan untuk mulai baru.
     * @return hasil flood (ok + jumlah piksel; gagal → UI wajib melapor).
     */
    fun magicWandSelect(
        srcBitmap: Bitmap?,
        point: Offset,
        tolerance: Float,
        expandPixels: Int = currentExpandPixels,
        gapRadius: Int = 0
    ): NativeBridge.WandOutcome {
        synchronized(maskLock) {
            invalidateCache()
            if (srcBitmap == null || srcBitmap.isRecycled) return NativeBridge.WandOutcome(false, 0L)
            if (point.x < 0f || point.y < 0f || point.x >= srcBitmap.width.toFloat() || point.y >= srcBitmap.height.toFloat()) return NativeBridge.WandOutcome(false, 0L)
            if (maskBitmap.width != srcBitmap.width || maskBitmap.height != srcBitmap.height ||
                rawMaskBitmap.width != srcBitmap.width || rawMaskBitmap.height != srcBitmap.height) {
                resetSize(srcBitmap.width, srcBitmap.height)
                if (rawMaskBitmap.width != srcBitmap.width || rawMaskBitmap.height != srcBitmap.height) return NativeBridge.WandOutcome(false, 0L)
            }
            val startX = kotlin.math.floor(point.x).toInt()
            val startY = kotlin.math.floor(point.y).toInt()
            currentExpandPixels = expandPixels.coerceIn(0, 30)

            // Direct full-resolution execution using CIE Lab DeltaE in native/JVM fallback
            val ok = NativeBridge.magicWandSelectSafe(srcBitmap, rawMaskBitmap, startX, startY, tolerance, 0)
            applyExpandInternal()
            return ok
        }
    }

    fun applyExpand(expandPixels: Int) = synchronized(maskLock) {
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
        synchronized(maskLock) {
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
    }

    fun getMaskByteArray(): ByteArray? {
        synchronized(maskLock) {
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
    }

    fun restoreRawMaskByteArray(bytes: ByteArray) {
        synchronized(maskLock) {
        val bmp = rawMaskBitmap
        if (bmp.isRecycled) return
        invalidateCache()
        try {
            if (bytes.size != bmp.byteCount) {
                NativeBridge.clearMaskSafe(bmp)
                return
            }
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            bmp.copyPixelsFromBuffer(buffer)
            cachedRawMaskBytes = bytes.clone()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        }
    }

    fun restoreMaskByteArray(bytes: ByteArray) {
        synchronized(maskLock) {
        val bmp = maskBitmap
        if (bmp.isRecycled) return
        invalidateCache()
        try {
            if (bytes.size != bmp.byteCount) {
                NativeBridge.clearMaskSafe(bmp)
                return
            }
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            bmp.copyPixelsFromBuffer(buffer)
            cachedMaskBytes = bytes.clone()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        }
    }

    fun clearMask() = synchronized(maskLock) {
        invalidateCache()
        NativeBridge.clearMaskSafe(rawMaskBitmap)
        NativeBridge.clearMaskSafe(maskBitmap)
    }

    fun invertMask() = synchronized(maskLock) {
        invalidateCache()
        NativeBridge.invertMaskSafe(rawMaskBitmap)
        applyExpandInternal()
    }

    fun hasMask(): Boolean = synchronized(maskLock) {
        NativeBridge.hasMaskSafe(maskBitmap)
    }
}
