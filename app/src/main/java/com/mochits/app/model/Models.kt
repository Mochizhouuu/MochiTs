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

/**
 * Mode blending layer, dipetakan ke android.graphics.BlendMode (API 29+).
 * Nama mengikuti istilah Photoshop agar familiar.
 */
enum class LayerBlendMode(val label: String) {
    NORMAL("Normal"),
    DARKEN("Darken"),
    MULTIPLY("Multiply"),
    COLOR_BURN("Color Burn"),
    LIGHTEN("Lighten"),
    SCREEN("Screen"),
    COLOR_DODGE("Color Dodge"),
    OVERLAY("Overlay"),
    SOFT_LIGHT("Soft Light"),
    HARD_LIGHT("Hard Light"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    HUE("Hue"),
    SATURATION("Saturation"),
    COLOR("Color"),
    LUMINOSITY("Luminosity")
}

enum class RadialBlurMode(val label: String) {
    SPIN("Putar"),
    ZOOM("Zoom")
}

enum class TexturePreset(val label: String) {
    NONE("Nonaktif"),
    GRAIN("Grain"),
    PAPER("Kertas"),
    CANVAS("Kanvas"),
    HALFTONE("Halftone"),
    STRIPES("Garis"),
    DOTS("Titik"),
    CUSTOM("Imporan")
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
    HISTORY,
    SCRIPT,
    SETTINGS
}

/**
 * Pesan UI terstruktur (B18): sukses/warning/error tidak lagi satu string
 * campur; [detail] opsional (mis. diagnosis unduhan) dibuka via aksi.
 */
data class UiMessage(
    val text: String,
    val kind: Kind = Kind.INFO,
    val detail: String? = null
) {
    enum class Kind { SUCCESS, INFO, WARNING, ERROR }
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

/**
 * Gaya per kata dalam satu layer teks (rich text fase 1: warna, font,
 * gaya font, ukuran). null = ikut gaya layer. start inklusif, end eksklusif.
 */
data class TextSpanStyle(
    val start: Int,
    val end: Int,
    val fontName: String? = null,
    val fontStyle: String? = null,
    val fontSize: Float? = null,
    val textColor: Int? = null
)

/**
 * Lapisan stroke tambahan (multi-stroke). Digambar di BAWAH stroke utama,
 * biasanya lebih tebal sehingga terlihat sebagai lapis luar bertingkat.
 */
data class StrokeSpec(
    val color: Int = Color.BLACK,
    val width: Float = 4f
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
    // Lapis stroke tambahan di bawah stroke utama (multi-stroke).
    // Nullable: JSON proyek lama tak memuat field ini (Gson → null).
    val extraStrokes: List<StrokeSpec>? = null,
    // Stroke memakai gradasi warna teks (stop/sudut yang sama).
    val strokeUseGradient: Boolean = false,
    val isGradientEnabled: Boolean = false,
    val gradientStartColor: Int = Color.BLACK,
    val gradientEndColor: Int = Color.WHITE,
    val gradientStops: List<ColorStop> = emptyList(),
    val gradientDirection: String = "HORIZONTAL", // "HORIZONTAL", "VERTICAL"
    val gradientAngle: Float = 0f, // Angle in degrees (0-360)
    val motionBlurRadius: Float = 0f, // 0 = mati; px uji searah sudut
    val motionBlurAngle: Float = 0f, // Arah blur derajat (0-360), konvensi sama dgn gradientAngle
    // Radial blur: pusat mengikuti pusat blok teks, jadi tepi memutar/memancar.
    val radialBlurStrength: Float = 0f, // 0..1
    val radialBlurMode: RadialBlurMode = RadialBlurMode.SPIN,
    // Texture overlay deterministik (tanpa aset eksternal).
    val texturePreset: TexturePreset = TexturePreset.NONE,
    val textureStrength: Float = 0f, // 0..1
    val textureScale: Float = 1f, // 0.5..4
    // Warna texture: TRANSPARENT = shading netral (terang-gelap saja);
    // berwarna = pattern (halftone/garis/titik) digambar memakai warna ini.
    // Field lama tanpa key ini terbaca 0 (= TRANSPARENT) oleh Gson — aman.
    val textureColor: Int = Color.TRANSPARENT,
    // Texture impor: NAMA FILE di folder texture proyek (bukan path penuh).
    // Dipakai saat texturePreset == CUSTOM.
    val textureAsset: String? = null
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
        // Kurang dari 2 stop membuat LinearGradient native crash.
        if (stops != null && stops.size >= 2) {
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
    open val isLocked: Boolean,
    open val blendMode: LayerBlendMode,
    open val clippingMask: Boolean
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
        val motionBlurAngle: Float = 0f, // derajat, konvensi sama dgn teks
        val glowColor: Int = Color.TRANSPARENT,
        val glowRadius: Float = 0f, // 0 = mati
        // Quad perspektif ternormalisasi [0..1]: TL,TR,BR,BL (8 float).
        // null = mati.
        val perspQuad: List<Float>? = null,
        // Grid warp mesh 4x4 ternormalisasi [0..1], row-major (32 float).
        // null = mati. Eksklusif dengan perspQuad.
        val meshGrid: List<Float>? = null,
        override val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
        override val clippingMask: Boolean = false,
        val radialBlurStrength: Float = 0f,
        val radialBlurMode: RadialBlurMode = RadialBlurMode.SPIN,
        val radialBlurCenterX: Float = 0.5f,
        val radialBlurCenterY: Float = 0.5f,
        val texturePreset: TexturePreset = TexturePreset.NONE,
        val textureStrength: Float = 0f,
        val textureScale: Float = 1f,
        val textureColor: Int = Color.TRANSPARENT,
        val textureAsset: String? = null
    ) : Layer(id, name, x, y, rotation, scaleX, scaleY, opacity, isVisible, isLocked, blendMode, clippingMask)

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
        // Rich text per kata (fase 1). Kosong = seluruh teks ikut style.
        val richSpans: List<TextSpanStyle> = emptyList(),
        val textContainerShape: TextContainerShape = TextContainerShape.BOX,
        val boxWidth: Float? = null,
        val boxHeight: Float? = null,
        // Quad perspektif ternormalisasi [0..1]: TL,TR,BR,BL (8 float).
        // null = mati.
        val perspQuad: List<Float>? = null,
        // Grid warp mesh 4x4 ternormalisasi [0..1], row-major (32 float).
        // null = mati. Eksklusif dengan perspQuad.
        val meshGrid: List<Float>? = null,
        override val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
        override val clippingMask: Boolean = false
    ) : Layer(id, name, x, y, rotation, scaleX, scaleY, opacity, isVisible, isLocked, blendMode, clippingMask)
}
