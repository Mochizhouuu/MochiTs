package com.mochits.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.mochits.app.imaging.ImageEffects
import com.mochits.app.model.Layer
import com.mochits.app.text.TextRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ProjectExporter(private val context: Context) {

    private val textRenderer = TextRenderer(context)

    suspend fun exportToBitmap(
        baseBitmap: Bitmap,
        layers: List<Layer>,
        imageBitmapFor: ((Layer.ImageLayer) -> Bitmap?)? = null,
        imageGlowFor: ((Layer.ImageLayer) -> Pair<Bitmap, Float>?)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        // Guard dulu di level Kotlin: tanpa ini, drawBitmap(bitmap recycled)
        // menembus ke native dan meng-abort seluruh Test Executor (exit 134,
        // "cannot access an invalid/free'd bitmap") sehingga SEMUA test release
        // mati, bukan cuma 1 test yang gagal.
        require(!baseBitmap.isRecycled) { "baseBitmap is recycled" }
        val width = baseBitmap.width
        val height = baseBitmap.height
        require(width > 0 && height > 0) { "baseBitmap has invalid size" }
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)

        // Render base bitmap
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        // Render visible layers
        layers.forEach { layer ->
            if (layer.isVisible) {
                when (layer) {
                    is Layer.TextLayer -> {
                        val alphaPaint = android.graphics.Paint().apply {
                            alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                        }
                        val count = canvas.saveLayer(null, alphaPaint)

                        val bounds = textRenderer.getTextBounds(layer)
                        val textCenterX = bounds.centerX()
                        val textCenterY = bounds.centerY()

                        canvas.save()
                        if (layer.rotation != 0f) {
                            canvas.rotate(layer.rotation, textCenterX, textCenterY)
                        }

                        textRenderer.drawStyledText(
                            canvas = canvas,
                            layer = layer
                        )

                        canvas.restore()
                        canvas.restoreToCount(count)
                    }
                    is Layer.ImageLayer -> {
                        val imgBmp = imageBitmapFor?.invoke(layer)?.takeIf { !it.isRecycled }
                            ?: layer.bitmap?.takeIf { !it.isRecycled }
                        if (imgBmp != null) {
                            val layerAlpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                            imageGlowFor?.invoke(layer)?.let { (glowBmp, pad) ->
                                if (!glowBmp.isRecycled) {
                                    val glowPaint = android.graphics.Paint().apply {
                                        alpha = layerAlpha
                                    }
                                    canvas.drawBitmap(glowBmp, layer.x - pad, layer.y - pad, glowPaint)
                                }
                            }
                            val imgPaint = android.graphics.Paint().apply {
                                alpha = layerAlpha
                                colorFilter = ImageEffects.imageColorFilter(
                                    layer.grayscale, layer.brightness, layer.contrast
                                )
                            }
                            canvas.drawBitmap(imgBmp, layer.x, layer.y, imgPaint)
                        }
                    }
                }
            }
        }

        outputBitmap
    }

    suspend fun exportToFile(
        baseBitmap: Bitmap,
        layers: List<Layer>,
        outputFile: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100,
        imageBitmapFor: ((Layer.ImageLayer) -> Bitmap?)? = null,
        imageGlowFor: ((Layer.ImageLayer) -> Pair<Bitmap, Float>?)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bmp = exportToBitmap(baseBitmap, layers, imageBitmapFor, imageGlowFor)
            FileOutputStream(outputFile).use { out ->
                bmp.compress(format, quality, out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
