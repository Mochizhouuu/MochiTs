package com.mochits.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.mochits.app.imaging.ImageEffects
import com.mochits.app.imaging.Perspective
import com.mochits.app.model.Layer
import com.mochits.app.text.TextRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ProjectExporter(private val context: Context) {

    val textRenderer = TextRenderer(context)

    /** Bitmap warp + posisi kiri-atas absolut + skala (ukuran gambar = w/scale). */
    data class WarpedDraw(
        val bitmap: Bitmap,
        val x: Float,
        val y: Float,
        val scale: Float
    )

    /** Glow + quad/grid yang sudah dipetakan ke ruang glow (untuk warp). */
    data class MeshGlow(
        val bitmap: Bitmap,
        val pad: Float,
        val grid: List<Float>
    )

    /** Render datar teks + origin absolut (untuk warp mesh). */
    data class TextMeshDraw(
        val bitmap: Bitmap,
        val x: Float,
        val y: Float
    )

    suspend fun exportToBitmap(
        baseBitmap: Bitmap,
        layers: List<Layer>,
        imageBitmapFor: ((Layer.ImageLayer) -> Bitmap?)? = null,
        imageGlowFor: ((Layer.ImageLayer) -> Pair<Bitmap, Float>?)? = null,
        imageWarpFor: ((Layer.ImageLayer) -> WarpedDraw?)? = null,
        textWarpFor: ((Layer.TextLayer) -> WarpedDraw?)? = null,
        imageGlowWarpFor: ((Layer.ImageLayer) -> WarpedDraw?)? = null,
        textMeshFor: ((Layer.TextLayer) -> TextMeshDraw?)? = null,
        imageMeshGlowFor: ((Layer.ImageLayer) -> MeshGlow?)? = null,
        fontLookup: ((String) -> String?)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        // Guard dulu di level Kotlin: tanpa ini, drawBitmap(bitmap recycled)
        // menembus ke native dan meng-abort seluruh Test Executor (exit 134,
        // "cannot access an invalid/free'd bitmap") sehingga SEMUA test release
        // mati, bukan cuma 1 test yang gagal.
        require(!baseBitmap.isRecycled) { "baseBitmap is recycled" }
        val width = baseBitmap.width
        val height = baseBitmap.height
        require(width > 0 && height > 0) { "baseBitmap has invalid size" }
        textRenderer.customFontPathResolver = fontLookup
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val clipMasks = ClipMaskCache(textRenderer) { image ->
            image.bitmap ?: imageBitmapFor?.invoke(image)
        }

        // Render base bitmap
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        // Render visible layers. Base clipping = layer non-clip terdekat di bawah.
        var clipBase: Layer? = null
        layers.forEach { layer ->
            if (!layer.clippingMask) clipBase = layer
            val maskBase = if (layer.clippingMask) clipBase else null
            if (layer.clippingMask && (maskBase == null || !maskBase.isVisible)) {
                return@forEach
            }
            val clipMask = if (layer.clippingMask && maskBase != null && layer.isVisible) {
                clipMasks.maskFor(maskBase, width, height)
            } else null
            if (layer.isVisible) {
                when (layer) {
                    is Layer.TextLayer -> {
                        val layerAlpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                        val count = canvas.saveLayer(
                            null,
                            LayerCompositing.layerPaint(layerAlpha, layer.blendMode)
                        )

                        val bounds = textRenderer.getTextBounds(layer)
                        val textCenterX = bounds.centerX()
                        val textCenterY = bounds.centerY()

                        canvas.save()
                        if (layer.rotation != 0f) {
                            canvas.rotate(layer.rotation, textCenterX, textCenterY)
                        }

                        val textWarped = textWarpFor?.invoke(layer)
                            ?.takeIf { !it.bitmap.isRecycled && it.scale > 1e-6f }
                        if (textWarped != null) {
                            // Opacity sudah via saveLayer luar: paint full alpha.
                            val wPaint = android.graphics.Paint().apply {
                                isFilterBitmap = true
                            }
                            canvas.drawBitmap(
                                textWarped.bitmap,
                                null,
                                android.graphics.RectF(
                                    textWarped.x, textWarped.y,
                                    textWarped.x + textWarped.bitmap.width / textWarped.scale,
                                    textWarped.y + textWarped.bitmap.height / textWarped.scale
                                ),
                                wPaint
                            )
                        } else {
                            val textMesh = textMeshFor?.invoke(layer)
                                ?.takeIf { !it.bitmap.isRecycled }
                            var drewMesh = false
                            if (textMesh != null) {
                                val mPaint = android.graphics.Paint().apply {
                                    isFilterBitmap = true
                                }
                                drewMesh = Perspective.drawMeshBitmap(
                                    canvas,
                                    textMesh.bitmap,
                                    textMesh.x, textMesh.y,
                                    textMesh.bitmap.width.toFloat(),
                                    textMesh.bitmap.height.toFloat(),
                                    layer.meshGrid,
                                    mPaint
                                )
                            }
                            if (!drewMesh) {
                                textRenderer.drawStyledText(
                                    canvas = canvas,
                                    layer = layer
                                )
                            }
                        }

                        canvas.restore()
                        if (clipMask != null) {
                            LayerCompositing.applyClipMask(canvas, clipMask, width, height)
                        }
                        canvas.restoreToCount(count)
                    }
                    is Layer.ImageLayer -> {
                        val imgBmp = imageBitmapFor?.invoke(layer)?.takeIf { !it.isRecycled }
                            ?: layer.bitmap?.takeIf { !it.isRecycled }
                        if (imgBmp != null) {
                            val layerAlpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                            val count = canvas.saveLayer(
                                null,
                                LayerCompositing.layerPaint(layerAlpha, layer.blendMode)
                            )
                            val contentAlpha = 255
                            val glowWarped = imageGlowWarpFor?.invoke(layer)
                                ?.takeIf { !it.bitmap.isRecycled && it.scale > 1e-6f }
                            if (glowWarped != null) {
                                val glowPaint = android.graphics.Paint().apply {
                                    alpha = contentAlpha
                                    isFilterBitmap = true
                                }
                                canvas.drawBitmap(
                                    glowWarped.bitmap,
                                    null,
                                    android.graphics.RectF(
                                        glowWarped.x, glowWarped.y,
                                        glowWarped.x + glowWarped.bitmap.width / glowWarped.scale,
                                        glowWarped.y + glowWarped.bitmap.height / glowWarped.scale
                                    ),
                                    glowPaint
                                )
                            } else {
                                val meshGlow = imageMeshGlowFor?.invoke(layer)
                                    ?.takeIf { !it.bitmap.isRecycled }
                                var drewGlowMesh = false
                                if (meshGlow != null) {
                                    val glowMeshPaint = android.graphics.Paint().apply {
                                        alpha = contentAlpha
                                        isFilterBitmap = true
                                    }
                                    drewGlowMesh = Perspective.drawMeshBitmap(
                                        canvas,
                                        meshGlow.bitmap,
                                        layer.x - meshGlow.pad, layer.y - meshGlow.pad,
                                        imgBmp.width + 2f * meshGlow.pad,
                                        imgBmp.height + 2f * meshGlow.pad,
                                        meshGlow.grid,
                                        glowMeshPaint
                                    )
                                }
                                if (!drewGlowMesh) {
                                    imageGlowFor?.invoke(layer)?.let { (glowBmp, pad) ->
                                        if (!glowBmp.isRecycled) {
                                            val glowPaint = android.graphics.Paint().apply {
                                                alpha = contentAlpha
                                            }
                                            canvas.drawBitmap(glowBmp, layer.x - pad, layer.y - pad, glowPaint)
                                        }
                                    }
                                }
                            }
                            val imgPaint = android.graphics.Paint().apply {
                                alpha = contentAlpha
                                colorFilter = ImageEffects.imageColorFilter(
                                    layer.grayscale, layer.brightness, layer.contrast
                                )
                            }
                            val imgWarped = imageWarpFor?.invoke(layer)
                                ?.takeIf { !it.bitmap.isRecycled && it.scale > 1e-6f }
                            if (imgWarped != null) {
                                val wPaint = android.graphics.Paint().apply {
                                    alpha = contentAlpha
                                    colorFilter = imgPaint.colorFilter
                                    isFilterBitmap = true
                                }
                                canvas.drawBitmap(
                                    imgWarped.bitmap,
                                    null,
                                    android.graphics.RectF(
                                        imgWarped.x, imgWarped.y,
                                        imgWarped.x + imgWarped.bitmap.width / imgWarped.scale,
                                        imgWarped.y + imgWarped.bitmap.height / imgWarped.scale
                                    ),
                                    wPaint
                                )
                            } else {
                                val imgMeshPaint = android.graphics.Paint().apply {
                                    alpha = contentAlpha
                                    colorFilter = imgPaint.colorFilter
                                    isFilterBitmap = true
                                }
                                val drewImgMesh = Perspective.drawMeshBitmap(
                                    canvas,
                                    imgBmp,
                                    layer.x, layer.y,
                                    imgBmp.width.toFloat(), imgBmp.height.toFloat(),
                                    layer.meshGrid,
                                    imgMeshPaint
                                )
                                if (!drewImgMesh) {
                                    canvas.drawBitmap(imgBmp, layer.x, layer.y, imgPaint)
                                }
                            }
                            if (clipMask != null) {
                                LayerCompositing.applyClipMask(canvas, clipMask, width, height)
                            }
                            canvas.restoreToCount(count)
                        }
                    }
                }
            }
        }

        clipMasks.clear()
        outputBitmap
    }

    suspend fun exportToFile(
        baseBitmap: Bitmap,
        layers: List<Layer>,
        outputFile: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100,
        imageBitmapFor: ((Layer.ImageLayer) -> Bitmap?)? = null,
        imageGlowFor: ((Layer.ImageLayer) -> Pair<Bitmap, Float>?)? = null,
        imageWarpFor: ((Layer.ImageLayer) -> WarpedDraw?)? = null,
        textWarpFor: ((Layer.TextLayer) -> WarpedDraw?)? = null,
        imageGlowWarpFor: ((Layer.ImageLayer) -> WarpedDraw?)? = null,
        textMeshFor: ((Layer.TextLayer) -> TextMeshDraw?)? = null,
        imageMeshGlowFor: ((Layer.ImageLayer) -> MeshGlow?)? = null,
        fontLookup: ((String) -> String?)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var bmp: Bitmap? = null
        try {
            bmp = exportToBitmap(
                baseBitmap, layers, imageBitmapFor, imageGlowFor,
                imageWarpFor, textWarpFor, imageGlowWarpFor,
                textMeshFor, imageMeshGlowFor, fontLookup
            )
            FileOutputStream(outputFile).use { out ->
                bmp.compress(format, quality, out)
            }
            true
        } catch (t: Throwable) {
            com.mochits.app.util.Logger.e(
                "Ekspor gagal ke ${outputFile.absolutePath}: ${t.message}", t
            )
            false
        } finally {
            bmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }
}
