package com.mochits.app.model

import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.core.imaging.Result

typealias MaskToolMode = com.mochits.core.imaging.MaskToolMode

enum class EditorPanel {
    NONE,
    MASK,
    INPAINT,
    ERASE,
    TEXT,
    EFFECT,
    LAYERS,
    SETTINGS
}

data class TextStyleConfig(
    val fontName: String = "Default",
    val fontStyle: String = "Regular", // "Regular", "Bold", "Italic", "BoldItalic"
    val fontSize: Float = 36f,
    val textColor: Int = Color.BLACK,
    val strokeColor: Int = Color.TRANSPARENT,
    val strokeWidth: Float = 0f,
    val shadowColor: Int = Color.TRANSPARENT,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0f,
    val glowColor: Int = Color.TRANSPARENT,
    val glowRadius: Float = 0f,
    val isVertical: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.0f
)

sealed class Layer(
    open val id: String,
    open val name: String,
    open val x: Float,
    open val y: Float,
    open val rotation: Float,
    open val scaleX: Float,
    open val scaleY: Float,
    open val opacity: Float,
    open val isVisible: Boolean,
    open val isLocked: Boolean
) {
    data class ImageLayer(
        override val id: String,
        override val name: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val rotation: Float = 0f,
        override val scaleX: Float = 1f,
        override val scaleY: Float = 1f,
        override val opacity: Float = 1f,
        override val isVisible: Boolean = true,
        override val isLocked: Boolean = false,
        val bitmap: Bitmap? = null,
        val imagePath: String? = null
    ) : Layer(id, name, x, y, rotation, scaleX, scaleY, opacity, isVisible, isLocked)

    data class TextLayer(
        override val id: String,
        override val name: String,
        override val x: Float = 0f,
        override val y: Float = 0f,
        override val rotation: Float = 0f,
        override val scaleX: Float = 1f,
        override val scaleY: Float = 1f,
        override val opacity: Float = 1f,
        override val isVisible: Boolean = true,
        override val isLocked: Boolean = false,
        val text: String,
        val style: TextStyleConfig = TextStyleConfig()
    ) : Layer(id, name, x, y, rotation, scaleX, scaleY, opacity, isVisible, isLocked)
}
