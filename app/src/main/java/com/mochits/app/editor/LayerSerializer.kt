package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mochits.app.model.Layer
import com.mochits.app.model.LayerBlendMode
import com.mochits.app.model.RadialBlurMode
import com.mochits.app.model.TexturePreset
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
    val imagePath: String? = null,
    // Efek gambar (nullable agar JSON lama tetap terbaca).
    val grayscale: Float? = null,
    val brightness: Float? = null,
    val contrast: Float? = null,
    val motionBlurRadius: Float? = null,
    val motionBlurAngle: Float? = null,
    val glowColor: Int? = null,
    val glowRadius: Float? = null,
    // Quad perspektif (nullable agar JSON lama tetap terbaca).
    val perspQuad: List<Float>? = null,
    // Grid warp mesh 4x4 (nullable agar JSON lama tetap terbaca).
    val meshGrid: List<Float>? = null,
    // Rich text per kata (nullable agar JSON lama tetap terbaca).
    val richSpans: List<com.mochits.app.model.TextSpanStyle>? = null,
    // Blend + clipping mask (nullable agar JSON lama tetap terbaca).
    val blendMode: LayerBlendMode? = null,
    val clippingMask: Boolean? = null,
    // Radial blur + texture untuk gambar (nullable agar JSON lama tetap terbaca).
    val radialBlurStrength: Float? = null,
    val radialBlurMode: RadialBlurMode? = null,
    val radialBlurCenterX: Float? = null,
    val radialBlurCenterY: Float? = null,
    val texturePreset: TexturePreset? = null,
    val textureStrength: Float? = null,
    val textureScale: Float? = null,
    val textureColor: Int? = null,
    val textureAsset: String? = null
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
                    boxHeight = layer.boxHeight,
                    perspQuad = layer.perspQuad,
                    meshGrid = layer.meshGrid,
                    richSpans = layer.richSpans.takeIf { it.isNotEmpty() },
                    blendMode = layer.blendMode,
                    clippingMask = layer.clippingMask
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
                    imagePath = layer.imagePath,
                    grayscale = layer.grayscale,
                    brightness = layer.brightness,
                    contrast = layer.contrast,
                    motionBlurRadius = layer.motionBlurRadius,
                    motionBlurAngle = layer.motionBlurAngle,
                    glowColor = layer.glowColor,
                    glowRadius = layer.glowRadius,
                    perspQuad = layer.perspQuad,
                    meshGrid = layer.meshGrid,
                    blendMode = layer.blendMode,
                    clippingMask = layer.clippingMask,
                    radialBlurStrength = layer.radialBlurStrength,
                    radialBlurMode = layer.radialBlurMode,
                    radialBlurCenterX = layer.radialBlurCenterX,
                    radialBlurCenterY = layer.radialBlurCenterY,
                    texturePreset = layer.texturePreset,
                    textureStrength = layer.textureStrength,
                    textureScale = layer.textureScale,
                    textureColor = layer.textureColor,
                    textureAsset = layer.textureAsset
                )
            }
        }
        return gson.toJson(dtos)
    }

    fun deserialize(json: String): List<Layer> {
        if (json.isBlank() || json == "[]") return emptyList()
        // JSON korup tidak boleh meledakkan load proyek: kembali kosong.
        val listType: java.lang.reflect.Type =
            object : TypeToken<List<LayerJsonDto>>() {}.type
        val dtos: List<LayerJsonDto> = try {
            gson.fromJson<List<LayerJsonDto>>(json, listType) ?: return emptyList()
        } catch (t: Throwable) {
            com.mochits.app.util.Logger.e("Layers JSON korup, mulai kosong: ${t.message}", t)
            return emptyList()
        }

        val out = ArrayList<Layer>(dtos.size)
        for (dto in dtos) {
            try {
                when (dto.type) {
                    "TEXT" -> out.add(buildTextLayer(dto))
                    "IMAGE" -> out.add(buildImageLayer(dto))
                    else -> com.mochits.app.util.Logger.e("Layer type tak dikenal: ${dto.type}")
                }
            } catch (t: Throwable) {
                // Field wajib hilang (id null dsb): lewati layer korup ini saja.
                com.mochits.app.util.Logger.e("Layer korup dilewati: ${t.message}")
            }
        }
        return out
    }

    private fun buildTextLayer(dto: LayerJsonDto): Layer.TextLayer {
        val rawStyle = dto.style ?: TextStyleConfig()
        var migratedStyle = if (rawStyle.gradientStops.isEmpty()) {
            rawStyle.copy(gradientStops = rawStyle.getEffectiveGradientStops())
        } else rawStyle
        if (migratedStyle.gradientDirection.equals("VERTICAL", ignoreCase = true) && migratedStyle.gradientAngle == 0f) {
            migratedStyle = migratedStyle.copy(gradientAngle = 90f)
        }
        return Layer.TextLayer(
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
            boxWidth = dto.boxWidth?.takeIf { it.isFinite() && it > 0f },
            boxHeight = dto.boxHeight?.takeIf { it.isFinite() && it > 0f },
            perspQuad = dto.perspQuad?.takeIf { it.size == 8 && it.all { v -> v.isFinite() } },
            meshGrid = dto.meshGrid?.takeIf { it.size == 32 && it.all { v -> v.isFinite() } },
            richSpans = dto.richSpans
                ?.mapNotNull { s ->
                    val a = s.start.coerceAtLeast(0)
                    val b = s.end.coerceAtLeast(0)
                    if (b <= a) null else s.copy(
                        start = a,
                        end = b,
                        fontSize = s.fontSize?.takeIf { it.isFinite() && it > 0f }
                    )
                }
                ?.takeIf { it.isNotEmpty() }
                ?: emptyList(),
            blendMode = dto.blendMode ?: LayerBlendMode.NORMAL,
            clippingMask = dto.clippingMask ?: false
        )
    }

    private fun buildImageLayer(dto: LayerJsonDto): Layer.ImageLayer {
        val imageBitmap = dto.imagePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                try {
                    // Batasi decode (B2): layer raksasa di-sample seperti base.
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val sample = maxOf(
                        bounds.outWidth / 8192,
                        bounds.outHeight / 8192,
                        1
                    )
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inMutable = true
                        inSampleSize = sample
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                } catch (t: Throwable) {
                    null
                }
            } else null
        }
        return Layer.ImageLayer(
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
            bitmap = imageBitmap,
            imagePath = dto.imagePath,
            grayscale = dto.grayscale ?: 0f,
            brightness = dto.brightness ?: 0f,
            contrast = dto.contrast ?: 1f,
            motionBlurRadius = dto.motionBlurRadius ?: 0f,
            motionBlurAngle = dto.motionBlurAngle ?: 0f,
            glowColor = dto.glowColor ?: android.graphics.Color.TRANSPARENT,
            glowRadius = dto.glowRadius ?: 0f,
            perspQuad = dto.perspQuad?.takeIf { it.size == 8 && it.all { v -> v.isFinite() } },
            meshGrid = dto.meshGrid?.takeIf { it.size == 32 && it.all { v -> v.isFinite() } },
            blendMode = dto.blendMode ?: LayerBlendMode.NORMAL,
            clippingMask = dto.clippingMask ?: false,
            radialBlurStrength = (dto.radialBlurStrength ?: 0f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            radialBlurMode = dto.radialBlurMode ?: RadialBlurMode.SPIN,
            radialBlurCenterX = (dto.radialBlurCenterX ?: 0.5f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
            radialBlurCenterY = (dto.radialBlurCenterY ?: 0.5f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
            texturePreset = dto.texturePreset ?: TexturePreset.NONE,
            textureStrength = (dto.textureStrength ?: 0f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            textureScale = (dto.textureScale ?: 1f).takeIf { it.isFinite() }?.coerceIn(0.5f, 4f) ?: 1f,
            textureColor = dto.textureColor ?: android.graphics.Color.TRANSPARENT,
            textureAsset = dto.textureAsset
        )
    }
}
