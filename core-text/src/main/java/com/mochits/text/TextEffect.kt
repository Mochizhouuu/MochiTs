package com.mochits.text

/**
 * Efek teks kustom dirender manual via kombinasi Paint/Shader/
 * BlurMaskFilter/PathMeasure/Matrix — dengan deteksi versi API agar
 * tetap konsisten di Android 11 ke bawah (motion blur & glow tidak
 * memakai RenderEffect bawaan demi kompatibilitas).
 */
sealed class TextEffect {
    data class Stroke(val widthPx: Float, val colorArgb: Int) : TextEffect()
    data class Shadow(val dx: Float, val dy: Float, val radius: Float, val colorArgb: Int) : TextEffect()
    data class Glow(val radius: Float, val colorArgb: Int) : TextEffect()
    data class Gradient(val colors: List<Int>, val angleDegrees: Float) : TextEffect()
    data class MotionBlur(val angleDegrees: Float, val distancePx: Float) : TextEffect()
    data class Warp(val curveStrength: Float) : TextEffect()
    data class Perspective(val matrixValues: FloatArray) : TextEffect()
}

data class StylePreset(
    val id: String,
    val name: String,
    val effects: List<TextEffect>
)
