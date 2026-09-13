package com.mochits.app.canvas

import androidx.compose.ui.geometry.Offset

class PrecisionCoordinateMapper(
    private var canvasWidth: Int = 1080,
    private var canvasHeight: Int = 1920
) {
    var scale: Float = 1f
        private set
    var translationX: Float = 0f
        private set
    var translationY: Float = 0f
        private set

    private val minScale = 0.1f
    private val maxScale = 10f

    fun updateCanvasSize(width: Int, height: Int) {
        canvasWidth = width.coerceAtLeast(1)
        canvasHeight = height.coerceAtLeast(1)
    }

    fun updateTransform(scale: Float, translationX: Float, translationY: Float) {
        this.scale = scale.coerceIn(minScale, maxScale)
        val maxOffset = maxOf(canvasWidth, canvasHeight) * 2f
        this.translationX = translationX.coerceIn(-maxOffset, maxOffset)
        this.translationY = translationY.coerceIn(-maxOffset, maxOffset)
    }

    /**
     * Maps screen touch coordinate to image/canvas pixel coordinate.
     */
    fun screenToCanvas(screenX: Float, screenY: Float): Offset {
        if (scale == 0f) return Offset.Zero

        val cx = ((screenX - translationX) / scale).coerceIn(
            -canvasWidth.toFloat() * 2,
            canvasWidth.toFloat() * 3
        )
        val cy = ((screenY - translationY) / scale).coerceIn(
            -canvasHeight.toFloat() * 2,
            canvasHeight.toFloat() * 3
        )
        return Offset(cx, cy)
    }

    /**
     * Maps image/canvas pixel coordinate to screen coordinate.
     */
    fun canvasToScreen(canvasX: Float, canvasY: Float): Offset {
        val sx = canvasX * scale + translationX
        val sy = canvasY * scale + translationY
        return Offset(sx, sy)
    }
}
