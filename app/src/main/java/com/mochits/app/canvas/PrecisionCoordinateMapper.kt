package com.mochits.app.canvas

import androidx.compose.ui.geometry.Offset

class PrecisionCoordinateMapper {
    var scale: Float = 1f
        private set
    var translationX: Float = 0f
        private set
    var translationY: Float = 0f
        private set

    fun updateTransform(scale: Float, translationX: Float, translationY: Float) {
        this.scale = scale
        this.translationX = translationX
        this.translationY = translationY
    }

    /**
     * Maps screen touch coordinate to image/canvas pixel coordinate.
     */
    fun screenToCanvas(screenX: Float, screenY: Float): Offset {
        val cx = (screenX - translationX) / scale
        val cy = (screenY - translationY) / scale
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
