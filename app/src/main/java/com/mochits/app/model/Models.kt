package com.mochits.app.model

import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.core.imaging.Result

typealias MaskToolMode = com.mochits.core.imaging.MaskToolMode


enum class TextAlignment {
    LEFT,
    CENTER,
    RIGHT
}

enum class TextContainerShape {
    BOX,
    OVAL
}

enum class EditorPanel {
    NONE,
    MASK,
    INPAINT,
    ERASE,
    TEXT,
    EFFECT,
    FONT,
    STYLE,
    LAYERS,
    SETTINGS
}

data class ColorStop(
    val color: Int = Color.BLACK,
    val position: Float = 0f
)

/**
 * Preset gaya teks: kombinasi Font + Alignment + Shape yang bisa disimpan
 * dan dipakai ulang dari panel Style tanpa mengatur satu per satu.
 * Ukuran font sengaja tidak ikut — skala diatur via handle resize.
 */
data class TextStylePreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val fontName: String = "Default",
    val fontStyle: String = "Regular", // "Regular", "Bold", "Italic", "BoldItalic"
    val alignment: TextAlignment = TextAlignment.CENTER,
    val shape: TextContainerShape = TextContainerShape.BOX,
    val isBuiltIn: Boolean = false
)

data class TextStyleConfig(
    val fontName: String = "Default",
    val fontStyle: String = "Regular", // "Regular", "Bold", "Italic", "BoldItalic"
    val fontSize: Float = 36f,
    val textColor: Int = Color.BLACK,
    val textOpacity: Float = 1.0f,
    val strokeColor: Int = Color.TRANSPARENT,
    val strokeWidth: Float = 0f,
    val strokeOpacity: Float = 1.0f,
    val shadowColor: Int = Color.TRANSPARENT,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0f,
    val glowColor: Int = Color.TRANSPARENT,
    val glowRadius: Float = 0f,
    val isVertical: Boolean = false,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.0f,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val isGradientEnabled: Boolean = false,
    val gradientStartColor: Int = Color.BLACK,
    val gradientEndColor: Int = Color.WHITE,
    val gradientStops: List<ColorStop> = emptyList(),
    val gradientDirection: String = "HORIZONTAL", // "HORIZONTAL", "VERTICAL"
    val gradientAngle: Float = 0f, // Angle in degrees (0-360)
    val motionBlurRadius: Float = 0f, // 0 = mati; px uji searah sudut
    val motionBlurAngle: Float = 0f // Arah blur derajat (0-360), konvensi sama dgn gradientAngle
) {
    fun calculateGradientPoints(x: Float, y: Float, width: Float, height: Float): FloatArray {
        val cx = x + width / 2f
        val cy = y + height / 2f
        val rad = Math.toRadians(gradientAngle.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()
        val halfLength = (kotlin.math.abs(width * cosA) + kotlin.math.abs(height * sinA)) / 2f
        val x0 = cx - cosA * halfLength
        val y0 = cy - sinA * halfLength
        val x1 = cx + cosA * halfLength
        val y1 = cy + sinA * halfLength
        return floatArrayOf(x0, y0, x1, y1)
    }
    fun getEffectiveGradientStops(): List<ColorStop> {
        val stops = gradientStops
        if (!stops.isNullOrEmpty()) {
            return stops.sortedBy { it.position }
        }
        return listOf(
            ColorStop(color = gradientStartColor, position = 0f),
            ColorStop(color = gradientEndColor, position = 1f)
        )
    }
}

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
        val imagePath: String? = null,
        // Efek gambar (dirender via ImageEffects; terpisah dari efek teks).
        val grayscale: Float = 0f, // 0..1 (0 = warna asli)
        val brightness: Float = 0f, // -1..1 (0 = normal)
        val contrast: Float = 1f, // 0..2 (1 = normal)
        val motionBlurRadius: Float = 0f, // 0 = mati
        val motionBlurAngle: Float = 0f // derajat, konvensi sama dgn teks
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
        val style: TextStyleConfig = TextStyleConfig(),
        val textContainerShape: TextContainerShape = TextContainerShape.BOX,
        val boxWidth: Float? = null,
        val boxHeight: Float? = null
    ) : Layer(id, name, x, y, rotation, scaleX, scaleY, opacity, isVisible, isLocked)
}
