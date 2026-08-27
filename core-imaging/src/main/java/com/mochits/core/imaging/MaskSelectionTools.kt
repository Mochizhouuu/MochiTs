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

    private val lassoPathX = mutableListOf<Float>()
    private val lassoPathY = mutableListOf<Float>()
    private var lastPoint: Offset? = null

    fun resetSize(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) return
        val newMask = createSafeMaskBitmap(newWidth, newHeight)
        // Copy old contents onto new native mask
        val canvas = android.graphics.Canvas(newMask)
        canvas.drawBitmap(maskBitmap, 0f, 0f, null)
        maskBitmap.recycle()
        maskBitmap = newMask
        width = newWidth
        height = newHeight
    }

    fun startStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        val radius = brushSize / 2f
        val draw = mode != MaskToolMode.ERASER

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                NativeBridge.nativeDrawCircle(maskBitmap, point.x, point.y, radius, draw)
                lastPoint = point
            }
            MaskToolMode.LASSO -> {
                lassoPathX.clear()
                lassoPathY.clear()
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
                lastPoint = point
            }
            MaskToolMode.RECTANGLE -> {
                lastPoint = point
            }
        }
    }

    fun updateStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        val radius = brushSize / 2f
        val draw = mode != MaskToolMode.ERASER

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                lastPoint?.let { prev ->
                    NativeBridge.nativeDrawLine(maskBitmap, prev.x, prev.y, point.x, point.y, radius, draw)
                }
                lastPoint = point
            }
            MaskToolMode.LASSO -> {
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
            }
            MaskToolMode.RECTANGLE -> {
                // Interactive preview rendered on UI layer if needed; endStroke fills mask
            }
        }
    }

    fun endStroke(point: Offset, mode: MaskToolMode, brushSize: Float = 0f) {
        when (mode) {
            MaskToolMode.LASSO -> {
                lassoPathX.add(point.x)
                lassoPathY.add(point.y)
                if (lassoPathX.size >= 3) {
                    NativeBridge.nativeDrawPolygon(maskBitmap, lassoPathX.toFloatArray(), lassoPathY.toFloatArray(), true)
                }
                lassoPathX.clear()
                lassoPathY.clear()
            }
            MaskToolMode.RECTANGLE -> {
                lastPoint?.let { start ->
                    NativeBridge.nativeDrawRect(maskBitmap, start.x, start.y, point.x, point.y, true)
                }
            }
            else -> {}
        }
        lastPoint = null
    }

    fun clearMask() {
        NativeBridge.nativeClearMask(maskBitmap)
    }

    fun invertMask() {
        NativeBridge.nativeInvertMask(maskBitmap)
    }

    fun hasMask(): Boolean {
        return NativeBridge.nativeHasMask(maskBitmap)
    }
}
