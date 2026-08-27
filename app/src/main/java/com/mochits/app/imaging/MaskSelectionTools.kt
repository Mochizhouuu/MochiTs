package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.MaskToolMode

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

    private var maskCanvas: Canvas = Canvas(maskBitmap)

    companion object {
        private fun createSafeMaskBitmap(w: Int, h: Int): Bitmap {
            return try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            } catch (t: Throwable) {
                t.printStackTrace()
                // Safe downsampled fallback if allocation fails
                val safeW = w.coerceAtMost(2048)
                val safeH = h.coerceAtMost(4096)
                Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ALPHA_8)
            }
        }
    }

    private val brushPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val eraserPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val fillPaint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var currentPath: Path? = null
    private var lastPoint: Offset? = null

    fun resetSize(newWidth: Int, newHeight: Int) {
        if (width == newWidth && height == newHeight) return
        val newMask = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ALPHA_8)
        val newCanvas = Canvas(newMask)
        newCanvas.drawBitmap(maskBitmap, 0f, 0f, null)
        maskBitmap.recycle()
        maskBitmap = newMask
        maskCanvas = newCanvas
        width = newWidth
        height = newHeight
    }

    fun startStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        val paint = if (mode == MaskToolMode.ERASER) eraserPaint else brushPaint
        paint.strokeWidth = brushSize

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                currentPath = Path().apply {
                    moveTo(point.x, point.y)
                }
                maskCanvas.drawCircle(point.x, point.y, brushSize / 2f, paint)
                lastPoint = point
            }
            MaskToolMode.LASSO -> {
                currentPath = Path().apply {
                    moveTo(point.x, point.y)
                }
                lastPoint = point
            }
            MaskToolMode.RECTANGLE -> {
                lastPoint = point
            }
        }
    }

    fun updateStroke(point: Offset, mode: MaskToolMode, brushSize: Float) {
        val paint = if (mode == MaskToolMode.ERASER) eraserPaint else brushPaint
        paint.strokeWidth = brushSize

        when (mode) {
            MaskToolMode.BRUSH, MaskToolMode.ERASER -> {
                lastPoint?.let { prev ->
                    val path = Path().apply {
                        moveTo(prev.x, prev.y)
                        lineTo(point.x, point.y)
                    }
                    maskCanvas.drawPath(path, paint)
                }
                lastPoint = point
            }
            MaskToolMode.LASSO -> {
                currentPath?.lineTo(point.x, point.y)
            }
            MaskToolMode.RECTANGLE -> {
                // Handled in endStroke or interactive preview
            }
        }
    }

    fun endStroke(point: Offset, mode: MaskToolMode, @Suppress("UNUSED_PARAMETER") brushSize: Float = 0f) {
        when (mode) {
            MaskToolMode.LASSO -> {
                currentPath?.let { path ->
                    path.lineTo(point.x, point.y)
                    path.close()
                    maskCanvas.drawPath(path, fillPaint)
                }
            }
            MaskToolMode.RECTANGLE -> {
                lastPoint?.let { start ->
                    val left = minOf(start.x, point.x)
                    val top = minOf(start.y, point.y)
                    val right = maxOf(start.x, point.x)
                    val bottom = maxOf(start.y, point.y)
                    maskCanvas.drawRect(left, top, right, bottom, fillPaint)
                }
            }
            else -> {}
        }
        currentPath = null
        lastPoint = null
    }

    fun clearMask() {
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    fun invertMask() {
        val inverted = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val invCanvas = Canvas(inverted)
        invCanvas.drawColor(Color.WHITE)
        val eraseOld = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        invCanvas.drawBitmap(maskBitmap, 0f, 0f, eraseOld)

        maskBitmap.recycle()
        maskBitmap = inverted
        maskCanvas = Canvas(maskBitmap)
    }

    fun hasMask(): Boolean {
        // Quick check
        val pixels = IntArray(100)
        maskBitmap.getPixels(pixels, 0, width, 0, 0, minOf(width, 10), minOf(height, 10))
        return pixels.any { (it and 0xFF) > 0 }
    }
}
