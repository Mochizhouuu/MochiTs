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

    fun updateStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
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

    fun endStroke(point: Offset, mode: MaskToolMode, brushSize: Float = 0f) {
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
        // Jangan gunakan piksel tepi hasil anti-alias sebagai benih: ambil
        // piksel dalam 3x3 yang paling dekat ke median (warna dominan
        // area yang diketuk) agar seleksi tak bocor ke luar area.
        val (seedX, seedY) = pickRegionSeed(srcBitmap, startX, startY)
        // Map UI tolerance scale (0..100) to full RGB Euclidean distance (0..441.673f).
        // Euclidean (sphere) is tighter than per-channel Chebyshev (cube) and avoids
        // leaking into neighbouring colors diagonally (e.g. corner (81,81,81)).
        val mappedTolerance = (tolerance.coerceIn(0f, 100f) / 100f) * 441.673f
        currentExpandPixels = expandPixels.coerceIn(0, 30)
        NativeBridge.magicWandSelectSafe(srcBitmap, rawMaskBitmap, seedX, seedY, mappedTolerance)
        applyExpandInternal()
    }

    /**
     * Benih flood-fill anti-tepi: ketukan TEPAT di piksel campuran
     * (anti-alias tepi garis) dipakai apa adanya akan menyeleksi kedua
     * sisi garis sekaligus. Bila piksel tengah jauh dari SEMUA tetangga
     * (>48 Euclidean = bukan bagian klaster warna), geser benih 1px ke
     * tetangga terdekat. Piksel murni (punya tetangga sewarna, walau
     * objeknya kecil seperti titik 2x2) tidak pernah digeser.
     */
    private fun pickRegionSeed(src: Bitmap, cx: Int, cy: Int): Pair<Int, Int> {
        val w = src.width
        val h = src.height
        if (w < 3 || h < 3) return cx to cy
        val xs = ((cx - 1)..(cx + 1)).filter { it in 0 until w }
        val ys = ((cy - 1)..(cy + 1)).filter { it in 0 until h }
        if (xs.size < 2 || ys.size < 2) return cx to cy
        val cols = ArrayList<Int>(9)
        var centerIdx = -1
        try {
            for (yy in ys) {
                for (xx in xs) {
                    if (xx == cx && yy == cy) centerIdx = cols.size
                    cols.add(src.getPixel(xx, yy))
                }
            }
        } catch (_: Exception) {
            return cx to cy
        }
        if (cols.isEmpty() || centerIdx < 0) return cx to cy
        val center = cols[centerIdx]
        val cr = (center ushr 16) and 0xFF
        val cg = (center ushr 8) and 0xFF
        val cb = center and 0xFF
        var nearestX = cx
        var nearestY = cy
        var nearestD = Long.MAX_VALUE
        var i = 0
        for (yy in ys) {
            for (xx in xs) {
                val c = cols[i++]
                if (xx == cx && yy == cy) continue
                val dr = (((c ushr 16) and 0xFF) - cr).toLong()
                val dg = (((c ushr 8) and 0xFF) - cg).toLong()
                val db = ((c and 0xFF) - cb).toLong()
                val d = dr * dr + dg * dg + db * db
                if (d < nearestD) {
                    nearestD = d
                    nearestX = xx
                    nearestY = yy
                }
            }
        }
        // Ambang campuran: 48 Euclidean kuadrat = 2304.
        return if (nearestD <= 2304L) cx to cy else nearestX to nearestY
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
        NativeBridge.invertMaskSafe(rawMaskBitmap)
        applyExpandInternal()
    }

    fun hasMask(): Boolean {
        return NativeBridge.hasMaskSafe(maskBitmap)
    }
}
