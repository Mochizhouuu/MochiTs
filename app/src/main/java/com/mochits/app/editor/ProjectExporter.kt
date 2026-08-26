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
                        textRenderer.drawStyledText(
                            canvas = canvas,
                            text = layer.text,
                            style = layer.style,
                            x = layer.x,
                            y = layer.y
                        )
                    }
                    is Layer.ImageLayer -> {
                        layer.bitmap?.let { imgBmp ->
                            canvas.drawBitmap(imgBmp, layer.x, layer.y, null)
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
