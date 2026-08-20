package com.mochits.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * State non-destruktif untuk Canvas Editor: base image tidak pernah
 * dimodifikasi langsung — zoom/pan hanya transformasi tampilan, dan
 * text layer adalah data terpisah yang di-composite saat render.
 */
@Stable
class CanvasEditorState(baseImagePath: String) {
    val baseImagePath: String = baseImagePath

    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    val textLayers = mutableStateListOf<CanvasTextLayer>()

    var selectedLayerId by mutableStateOf<String?>(null)
        private set

    fun onTransform(zoomChange: Float, panChange: Offset) {
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset += panChange
    }

    fun resetTransform() {
        scale = 1f
        offset = Offset.Zero
    }

    fun addTextLayer(text: String) {
        val newLayer = CanvasTextLayer(
            id = "text-${System.currentTimeMillis()}",
            text = text,
            relativeX = 0.5f,
            relativeY = 0.5f
        )
        textLayers.add(newLayer)
        selectedLayerId = newLayer.id
    }

    fun updateLayerPosition(id: String, relativeX: Float, relativeY: Float) {
        val index = textLayers.indexOfFirst { it.id == id }
        if (index != -1) {
            textLayers[index] = textLayers[index].copy(
                relativeX = relativeX.coerceIn(0f, 1f),
                relativeY = relativeY.coerceIn(0f, 1f)
            )
        }
    }

    fun selectLayer(id: String?) {
        selectedLayerId = id
    }

    fun deleteLayer(id: String) {
        textLayers.removeAll { it.id == id }
        if (selectedLayerId == id) selectedLayerId = null
    }

    fun updateLayerText(id: String, newText: String) {
        val index = textLayers.indexOfFirst { it.id == id }
        if (index != -1) {
            textLayers[index] = textLayers[index].copy(text = newText)
        }
    }
}
