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
        val newMask = createSafeMaskBitmap(newWidth, newHeight)
        val newRawMask = createSafeMaskBitmap(newWidth, newHeight)

        val canvas = android.graphics.Canvas(newMask)
        canvas.drawBitmap(maskBitmap, 0f, 0f, null)

        val rawCanvas = android.graphics.Canvas(newRawMask)
        rawCanvas.drawBitmap(rawMaskBitmap, 0f, 0f, null)

        maskBitmap.recycle()
        rawMaskBitmap.recycle()

        maskBitmap = newMask
        rawMaskBitmap = newRawMask
        width = newWidth
        height = newHeight
    }

    fun startStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
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
        if (srcBitmap == null || srcBitmap.isRecycled) return
        val startX = point.x.toInt()
        val startY = point.y.toInt()
        // Map UI tolerance scale (0..100) to full RGB Euclidean distance (0..441.673f)
        val mappedTolerance = (tolerance.coerceIn(0f, 100f) / 100f) * 441.673f
        currentExpandPixels = expandPixels.coerceIn(0, 30)
        NativeBridge.magicWandSelectSafe(srcBitmap, rawMaskBitmap, startX, startY, mappedTolerance)
        applyExpandInternal()
    }

    fun applyExpand(expandPixels: Int) {
        currentExpandPixels = expandPixels.coerceIn(0, 30)
        applyExpandInternal()
    }

    private fun applyExpandInternal() {
        if (currentExpandPixels <= 0) {
            NativeBridge.dilateMaskSafe(rawMaskBitmap, maskBitmap, 0)
        } else {
            NativeBridge.dilateMaskSafe(rawMaskBitmap, maskBitmap, currentExpandPixels)
        }
    }

    fun clearMask() {
        NativeBridge.clearMaskSafe(rawMaskBitmap)
        NativeBridge.clearMaskSafe(maskBitmap)
    }

    fun invertMask() {
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
