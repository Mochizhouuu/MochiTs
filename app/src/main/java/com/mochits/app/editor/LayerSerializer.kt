package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mochits.app.model.Layer
import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextStyleConfig
import com.mochits.app.text.TextRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class LayerJsonDto(
    val id: String,
    val type: String, // "TEXT" or "IMAGE"
    val name: String,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val scaleX: Float,
    val scaleY: Float,
    val opacity: Float,
    val isVisible: Boolean,
    val isLocked: Boolean,
    val text: String? = null,
    val style: TextStyleConfig? = null,
    val textContainerShape: TextContainerShape? = null,
    val boxWidth: Float? = null,
    val boxHeight: Float? = null,
    val imagePath: String? = null
)

class LayerSerializer {
    private val gson = Gson()

    fun serialize(layers: List<Layer>): String {
        val dtos = layers.map { layer ->
            when (layer) {
                is Layer.TextLayer -> LayerJsonDto(
                    id = layer.id,
                    type = "TEXT",
                    name = layer.name,
                    x = layer.x,
                    y = layer.y,
                    rotation = layer.rotation,
                    scaleX = layer.scaleX,
                    scaleY = layer.scaleY,
                    opacity = layer.opacity,
                    isVisible = layer.isVisible,
                    isLocked = layer.isLocked,
                    text = layer.text,
                    style = layer.style,
                    textContainerShape = layer.textContainerShape,
                    boxWidth = layer.boxWidth,
                    boxHeight = layer.boxHeight
                )
                is Layer.ImageLayer -> LayerJsonDto(
                    id = layer.id,
                    type = "IMAGE",
                    name = layer.name,
                    x = layer.x,
                    y = layer.y,
                    rotation = layer.rotation,
                    scaleX = layer.scaleX,
                    scaleY = layer.scaleY,
                    opacity = layer.opacity,
                    isVisible = layer.isVisible,
                    isLocked = layer.isLocked,
                    imagePath = layer.imagePath
                )
            }
        }
        return gson.toJson(dtos)
    }

    fun deserialize(json: String): List<Layer> {
        if (json.isBlank() || json == "[]") return emptyList()
        val type = object : TypeToken<List<LayerJsonDto>>() {}.type
        val dtos: List<LayerJsonDto> = gson.fromJson(json, type) ?: return emptyList()

        return dtos.map { dto ->
            if (dto.type == "TEXT") {
                val rawStyle = dto.style ?: TextStyleConfig()
                var migratedStyle = if (rawStyle.gradientStops.isEmpty()) {
                    rawStyle.copy(gradientStops = rawStyle.getEffectiveGradientStops())
                } else rawStyle
                if (migratedStyle.gradientDirection.equals("VERTICAL", ignoreCase = true) && migratedStyle.gradientAngle == 0f) {
                    migratedStyle = migratedStyle.copy(gradientAngle = 90f)
                }
                Layer.TextLayer(
                    id = dto.id,
                    name = dto.name,
                    x = dto.x,
                    y = dto.y,
                    rotation = dto.rotation,
                    scaleX = dto.scaleX,
                    scaleY = dto.scaleY,
                    opacity = dto.opacity,
                    isVisible = dto.isVisible,
                    isLocked = dto.isLocked,
                    text = dto.text ?: "",
                    style = migratedStyle,
                    textContainerShape = dto.textContainerShape ?: TextContainerShape.BOX,
                    boxWidth = dto.boxWidth,
                    boxHeight = dto.boxHeight
                )
            } else {
                Layer.ImageLayer(
                    id = dto.id,
                    name = dto.name,
                    x = dto.x,
                    y = dto.y,
                    rotation = dto.rotation,
                    scaleX = dto.scaleX,
                    scaleY = dto.scaleY,
                    opacity = dto.opacity,
                    isVisible = dto.isVisible,
                    isLocked = dto.isLocked,
                    imagePath = dto.imagePath
                )
            }
        }
    }
}
