package com.mochits.app.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

class CanvasEditorState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)

    var minScale by mutableFloatStateOf(0.1f)
    var maxScale by mutableFloatStateOf(10f)

    var isTransformInitialized by mutableStateOf(false)

    val mapper = PrecisionCoordinateMapper()

    fun updateTransform(newScale: Float, newOffsetX: Float, newOffsetY: Float) {
        scale = newScale.coerceIn(minScale, maxScale)
        offsetX = newOffsetX
        offsetY = newOffsetY
        mapper.updateTransform(scale, offsetX, offsetY)
    }

    fun onGestureTransform(centroid: Offset, pan: Offset, zoom: Float) {
        val oldScale = scale
        val targetScale = (oldScale * zoom).coerceIn(minScale, maxScale)
        val scaleFactor = targetScale / oldScale

        val newOffsetX = (offsetX - centroid.x) * scaleFactor + centroid.x + pan.x
        val newOffsetY = (offsetY - centroid.y) * scaleFactor + centroid.y + pan.y

        updateTransform(targetScale, newOffsetX, newOffsetY)
    }

    fun resetTransform(viewportWidth: Float, viewportHeight: Float, imageWidth: Float, imageHeight: Float) {
        if (imageWidth <= 0 || imageHeight <= 0) return
        val scaleX = viewportWidth / imageWidth
        val scaleY = viewportHeight / imageHeight
        val fitScale = minOf(scaleX, scaleY) * 0.9f

        val initialX = (viewportWidth - imageWidth * fitScale) / 2f
        val initialY = (viewportHeight - imageHeight * fitScale) / 2f

        updateTransform(fitScale, initialX, initialY)
        isTransformInitialized = true
    }

    fun focusOnCanvasPoint(canvasX: Float, canvasY: Float, viewportWidth: Float, viewportHeight: Float) {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        val targetScale = scale.coerceAtLeast(0.8f)
        val newOffsetX = (viewportWidth / 2f) - (canvasX * targetScale)
        val newOffsetY = (viewportHeight / 2f) - (canvasY * targetScale)
        updateTransform(targetScale, newOffsetX, newOffsetY)
    }
}
