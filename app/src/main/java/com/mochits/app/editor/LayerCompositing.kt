package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.mochits.app.model.Layer
import com.mochits.app.model.LayerBlendMode
import com.mochits.app.text.TextRenderer

/** Pemetaan blend mode Photoshop-like ke API Android 29+. */
object LayerCompositing {
    fun toAndroidBlendMode(mode: LayerBlendMode): BlendMode = when (mode) {
        LayerBlendMode.NORMAL -> BlendMode.SRC_OVER
        LayerBlendMode.DARKEN -> BlendMode.DARKEN
        LayerBlendMode.MULTIPLY -> BlendMode.MULTIPLY
        LayerBlendMode.COLOR_BURN -> BlendMode.COLOR_BURN
        LayerBlendMode.LIGHTEN -> BlendMode.LIGHTEN
        LayerBlendMode.SCREEN -> BlendMode.SCREEN
        LayerBlendMode.COLOR_DODGE -> BlendMode.COLOR_DODGE
        LayerBlendMode.OVERLAY -> BlendMode.OVERLAY
        LayerBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
        LayerBlendMode.HARD_LIGHT -> BlendMode.HARD_LIGHT
        LayerBlendMode.DIFFERENCE -> BlendMode.DIFFERENCE
        LayerBlendMode.EXCLUSION -> BlendMode.EXCLUSION
        LayerBlendMode.HUE -> BlendMode.HUE
        LayerBlendMode.SATURATION -> BlendMode.SATURATION
        LayerBlendMode.COLOR -> BlendMode.COLOR
        LayerBlendMode.LUMINOSITY -> BlendMode.LUMINOSITY
    }

    fun layerPaint(alpha: Int, blendMode: LayerBlendMode): Paint = Paint().apply {
        this.alpha = alpha.coerceIn(0, 255)
        this.blendMode = toAndroidBlendMode(blendMode)
        isFilterBitmap = true
    }

    /** Potong isi layer yang sedang di-saveLayer dengan alpha base. */
    fun applyClipMask(canvas: Canvas, mask: Bitmap, width: Int, height: Int) {
        if (mask.isRecycled || width <= 0 || height <= 0) return
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(mask, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
    }
}

/**
 * Cache siluet alpha layer dasar (base) untuk clipping mask.
 * Base digambar datar (tanpa warp/efek) ke bitmap, lalu extractAlpha.
 * Cache dibatasi 3 grup agar memori aman di kanvas besar.
 */
class ClipMaskCache(
    private val textRenderer: TextRenderer,
    private val imageBitmapFor: (Layer.ImageLayer) -> Bitmap?
) {
    private companion object {
        const val MASK_MAX_DIM = 1280
    }
    private data class Key(val layerHash: Int, val width: Int, val height: Int)

    private val cache = object : LinkedHashMap<Key, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean {
            if (size > 3) {
                try { eldest?.value?.recycle() } catch (_: Exception) {}
                return true
            }
            return false
        }
    }

    @Synchronized
    fun maskFor(base: Layer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = Key(base.hashCode(), width, height)
        cache[key]?.let { if (!it.isRecycled) return it }

        // Mask boleh beresolusi lebih rendah: hanya alpha yang dipakai,
        // dan hasilnya diskalakan lagi saat DST_IN. Ini menjaga memori aman.
        val scale = (MASK_MAX_DIM.toFloat() / maxOf(width, height).toFloat()).coerceAtMost(1f)
        val maskW = (width * scale).toInt().coerceAtLeast(1)
        val maskH = (height * scale).toInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        bmp.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        // Opacity base ikut menentukan alpha siluet clipping.
        val baseAlpha = (base.opacity * 255).toInt().coerceIn(0, 255)
        val layerCount = if (baseAlpha < 255) {
            canvas.saveLayer(
                null,
                Paint().apply { alpha = baseAlpha }
            )
        } else -1
        when (base) {
            is Layer.TextLayer -> {
                if (base.text.isEmpty()) {
                    bmp.recycle()
                    return null
                }
                if (base.rotation != 0f) {
                    val bounds = textRenderer.getTextBounds(base)
                    canvas.rotate(base.rotation, bounds.centerX(), bounds.centerY())
                }
                textRenderer.drawStyledText(canvas, base)
            }
            is Layer.ImageLayer -> {
                // Selalu pakai bitmap asli: hasil efek bisa berukuran lebih kecil
                // sehingga geometri clipping tidak lagi cocok dengan layer.
                val image = base.bitmap?.takeIf { !it.isRecycled }
                    ?: imageBitmapFor(base)?.takeIf { !it.isRecycled }
                if (image == null) {
                    bmp.recycle()
                    return null
                }
                canvas.drawBitmap(image, base.x, base.y, Paint().apply { isFilterBitmap = true })
            }
        }
        if (layerCount >= 0) canvas.restoreToCount(layerCount)
        val alpha = try {
            bmp.extractAlpha()
        } catch (_: Throwable) {
            null
        }
        bmp.recycle()
        if (alpha == null) return null
        cache[key] = alpha
        return alpha
    }

    @Synchronized
    fun clear() {
        cache.values.forEach { try { it.recycle() } catch (_: Exception) {} }
        cache.clear()
    }
}
