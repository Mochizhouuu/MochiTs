package com.mochits.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
        layers: List<Layer>
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = baseBitmap.width
        val height = baseBitmap.height
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

                        val bounds = textRenderer.getTextBounds(layer.text, layer.style, layer.x, layer.y)
                        val textCenterX = bounds.centerX()
                        val textCenterY = bounds.centerY()

                        canvas.save()
                        if (layer.rotation != 0f) {
                            canvas.rotate(layer.rotation, textCenterX, textCenterY)
                        }

                        textRenderer.drawStyledText(
                            canvas = canvas,
                            text = layer.text,
                            style = layer.style,
                            x = layer.x,
                            y = layer.y
                        )

                        canvas.restore()
                        canvas.restoreToCount(count)
                    }
                    is Layer.ImageLayer -> {
                        layer.bitmap?.let { imgBmp ->
                            if (!imgBmp.isRecycled) {
                                val imgPaint = android.graphics.Paint().apply {
                                    alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                                }
                                canvas.drawBitmap(imgBmp, layer.x, layer.y, imgPaint)
                            }
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
        quality: Int = 100
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bmp = exportToBitmap(baseBitmap, layers)
            FileOutputStream(outputFile).use { out ->
                bmp.compress(format, quality, out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
