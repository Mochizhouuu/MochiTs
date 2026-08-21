package com.mochits.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State non-destruktif untuk Canvas Editor. Base image tidak pernah
 * dimodifikasi langsung — zoom hanya transformasi tampilan.
 *
 * PENTING: posisi text layer ([CanvasTextLayer.xInImagePx]/[yInImagePx])
 * disimpan dalam KOORDINAT PIKSEL GAMBAR ASLI (bukan viewport layar,
 * bukan pula skala 0f..1f). Ini wajib untuk gambar long-strip/webtoon
 * yang jauh lebih tinggi dari layar dan di-scroll — posisi teks harus
 * "menempel" ke titik tertentu pada gambar itu sendiri, bukan ke area
 * layar yang sedang terlihat.
 */
@Stable
class CanvasEditorState(
    baseImagePath: String,
    intrinsicWidthPx: Int,
    intrinsicHeightPx: Int
) {
    val baseImagePath: String = baseImagePath
    val intrinsicWidthPx: Int = intrinsicWidthPx.coerceAtLeast(1)
    val intrinsicHeightPx: Int = intrinsicHeightPx.coerceAtLeast(1)

    var scale by mutableFloatStateOf(1f)
        private set

    val textLayers = mutableStateListOf<CanvasTextLayer>()

    var selectedLayerId by mutableStateOf<String?>(null)
        private set

    fun updateScale(newScale: Float) {
        scale = newScale.coerceIn(0.5f, 4f)
    }

    fun addTextLayer(text: String, xInImagePx: Float, yInImagePx: Float) {
        val newLayer = CanvasTextLayer(
            id = "text-${System.currentTimeMillis()}",
            text = text,
            xInImagePx = xInImagePx.coerceIn(0f, intrinsicWidthPx.toFloat()),
            yInImagePx = yInImagePx.coerceIn(0f, intrinsicHeightPx.toFloat())
        )
        textLayers.add(newLayer)
        selectedLayerId = newLayer.id
    }

    fun moveLayerBy(id: String, deltaXInImagePx: Float, deltaYInImagePx: Float) {
        val index = textLayers.indexOfFirst { it.id == id }
        if (index != -1) {
            val current = textLayers[index]
            textLayers[index] = current.copy(
                xInImagePx = (current.xInImagePx + deltaXInImagePx)
                    .coerceIn(0f, intrinsicWidthPx.toFloat()),
                yInImagePx = (current.yInImagePx + deltaYInImagePx)
                    .coerceIn(0f, intrinsicHeightPx.toFloat())
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
