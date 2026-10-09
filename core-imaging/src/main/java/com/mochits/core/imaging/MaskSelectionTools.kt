package com.mochits.core.imaging

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.mochits.core.imaging.selection.SelectionCombiner
import com.mochits.core.imaging.selection.SelectionEngine
import com.mochits.core.imaging.selection.SelectionMask
import com.mochits.core.imaging.selection.WandRequest

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
    fun magicWandSelect(srcBitmap: Bitmap?, point: Offset, tolerance: Float, expandPixels: Int = currentExpandPixels): NativeBridge.WandOutcome {
        synchronized(maskLock) {
        invalidateCache()
        if (srcBitmap == null || srcBitmap.isRecycled) return NativeBridge.WandOutcome(false, 0L)
        // Reject taps outside the source image using float comparison first.
        // (Using toInt() directly would truncate -0.5 -> 0 and falsely hit the edge.)
        if (point.x < 0f || point.y < 0f || point.x >= srcBitmap.width.toFloat() || point.y >= srcBitmap.height.toFloat()) return NativeBridge.WandOutcome(false, 0L)
        if (maskBitmap.width != srcBitmap.width || maskBitmap.height != srcBitmap.height ||
            rawMaskBitmap.width != srcBitmap.width || rawMaskBitmap.height != srcBitmap.height) {
            // Keep mask aligned with source; silently resync instead of writing out of bounds.
            resetSize(srcBitmap.width, srcBitmap.height)
            if (rawMaskBitmap.width != srcBitmap.width || rawMaskBitmap.height != srcBitmap.height) return NativeBridge.WandOutcome(false, 0L)
        }
        val startX = kotlin.math.floor(point.x).toInt()
        val startY = kotlin.math.floor(point.y).toInt()
        currentExpandPixels = expandPixels.coerceIn(0, 30)
        // Gambar raksasa: flood di komposit 0.5x (maks sisi 2000px) lalu
        // upscale mask (4x hemat waktu+memori; tepi dikompensasi Expand).
        val longest = maxOf(srcBitmap.width, srcBitmap.height)
        if (longest > 2000) {
            return downscaledWand(srcBitmap, point, tolerance, expandPixels)
        }
        // Mesin seleksi: mask eksplisit, lalu union eksplisit via combiner.
        val res = SelectionEngine.select(
            srcBitmap,
            WandRequest(startX, startY, tolerance)
        )
        if (!res.success || res.mask == null) return NativeBridge.WandOutcome(false, 0L)
        val w = srcBitmap.width
        val h = srcBitmap.height
        val cur = SelectionMask(w, h, SelectionEngine.readPacked(rawMaskBitmap, w, h))
        val merged = SelectionCombiner.add(cur, res.mask)
        SelectionEngine.writePacked(rawMaskBitmap, merged.pixels)
        applyExpandInternal()
        return NativeBridge.WandOutcome(true, res.selectedPixels)
        }
    }

    /**
     * Flood di bitmap 0.5x lalu upscale hasilnya ke mask penuh.
     * Toleransi (ruang warna) tak terpengaruh skala; koordinat diskalakan.
     */
    private fun downscaledWand(
        src: Bitmap,
        point: Offset,
        tolerance: Float,
        expandPixels: Int
    ): NativeBridge.WandOutcome {
        val sx0 = kotlin.math.floor(point.x).toInt().coerceIn(0, src.width - 1)
        val sy0 = kotlin.math.floor(point.y).toInt().coerceIn(0, src.height - 1)
        val seedAlpha = try {
            (src.getPixel(sx0, sy0) ushr 24) and 0xFF
        } catch (_: Exception) {
            255
        }
        val maxDist = if (seedAlpha >= 255) 441.673f else 510.3f
        val mappedTolerance = (tolerance.coerceIn(0f, 100f) / 100f) * maxDist
        val scale = 2000f / maxOf(src.width, src.height).toFloat()
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val smallSrc = try {
            // Nearest (filter=false): warna piksel TEPAT seperti aslinya.
            // Bilinear menghaluskan tepi -> seed campuran -> seleksi ngawur.
            Bitmap.createScaledBitmap(src, sw, sh, false)
        } catch (t: Throwable) {
            return NativeBridge.WandOutcome(false, 0L)
        }
        val smallMask = try {
            Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        } catch (t: Throwable) {
            try { smallSrc.recycle() } catch (_: Exception) {}
            return NativeBridge.WandOutcome(false, 0L)
        }
        try {
            val sx = kotlin.math.floor(point.x * scale).toInt().coerceIn(0, sw - 1)
            val sy = kotlin.math.floor(point.y * scale).toInt().coerceIn(0, sh - 1)
            val res = NativeBridge.magicWandSelectSafe(smallSrc, smallMask, sx, sy, mappedTolerance)
            if (!res.ok) return NativeBridge.WandOutcome(false, 0L)
            // Tiap ketuk menumpuk (union) seperti jalur penuh.
            // Upscale nearest (mask keras; tepi dikompensasi Expand).
            invalidateCache()
            val paint = android.graphics.Paint().apply { isFilterBitmap = false }
            val canvas = android.graphics.Canvas(rawMaskBitmap)
            canvas.drawBitmap(smallMask, null, android.graphics.Rect(0, 0, rawMaskBitmap.width, rawMaskBitmap.height), paint)
            applyExpandInternal()
            val approx = (res.selectedCount / (scale * scale)).toLong()
            return NativeBridge.WandOutcome(true, approx)
        } finally {
            try { smallSrc.recycle() } catch (_: Exception) {}
            try { smallMask.recycle() } catch (_: Exception) {}
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
