package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import com.mochits.canvas.CanvasEditorState
import com.mochits.common.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Pengolah ekspor project ke berkas gambar (JPEG / PNG).
 * Menggabungkan base image (bersama perbaikan inpaint) dan seluruh layer teks / efek.
 */
object ProjectExporter {

    suspend fun exportProjectToGallery(
        baseImageBitmap: Bitmap,
        state: CanvasEditorState,
        outputFile: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): OperationResult<File> = withContext(Dispatchers.IO) {
        try {
            val width = state.intrinsicWidthPx
            val height = state.intrinsicHeightPx

            val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)

            // 1. Gambar Base Image
            canvas.drawBitmap(baseImageBitmap, 0f, 0f, null)

            // 2. Gambar setiap Text Layer berserta seluruh Efek Teks pada koordinat piksel gambar asli
            state.textLayers.forEach { layer ->
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = layer.fontSizeSp * 2f
                    typeface = Typeface.DEFAULT
                }

                val x = layer.xInImagePx
                val y = layer.yInImagePx + textPaint.textSize
                val style = layer.style

                // Motion Blur
                if (style.motionBlurEnabled && style.motionBlurDistance > 0f) {
                    val samples = 5
                    val rad = Math.toRadians(style.motionBlurAngle.toDouble())
                    val dxStep = (Math.cos(rad) * style.motionBlurDistance / samples).toFloat() * 2f
                    val dyStep = (Math.sin(rad) * style.motionBlurDistance / samples).toFloat() * 2f

                    val blurPaint = Paint(textPaint).apply {
                        color = style.colorArgb
                        alpha = (255 / (samples + 1)).coerceIn(20, 100)
                    }

                    for (i in 1..samples) {
                        canvas.save()
                        canvas.translate(dxStep * i, dyStep * i)
                        canvas.drawText(layer.text, x, y, blurPaint)
                        canvas.restore()
                    }
                }

                // Glow
                if (style.glowEnabled && style.glowRadius > 0f) {
                    val glowPaint = Paint(textPaint).apply {
                        color = style.glowColorArgb
                        maskFilter = android.graphics.BlurMaskFilter(style.glowRadius * 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.drawText(layer.text, x, y, glowPaint)
                }

                // Drop Shadow
                if (style.shadowEnabled) {
                    val shadowPaint = Paint(textPaint).apply {
                        color = style.shadowColorArgb
                        if (style.shadowRadius > 0f) {
                            maskFilter = android.graphics.BlurMaskFilter(style.shadowRadius * 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        }
                    }
                    canvas.save()
                    canvas.translate(style.shadowDx * 2f, style.shadowDy * 2f)
                    canvas.drawText(layer.text, x, y, shadowPaint)
                    canvas.restore()
                }

                // Stroke
                if (style.strokeEnabled) {
                    val strokePaint = Paint(textPaint).apply {
                        this.style = Paint.Style.STROKE
                        strokeWidth = style.strokeWidthPx * 2f
                        color = style.strokeColorArgb
                    }
                    canvas.drawText(layer.text, x, y, strokePaint)
                }

                // Fill
                val fillPaint = Paint(textPaint).apply {
                    this.style = Paint.Style.FILL
                    if (style.gradientEnabled && style.gradientColors.size >= 2) {
                        val textWidth = textPaint.measureText(layer.text)
                        shader = android.graphics.LinearGradient(
                            x, y, x + textWidth, y,
                            style.gradientColors.toIntArray(),
                            null,
                            android.graphics.Shader.TileMode.CLAMP
                        )
                    } else {
                        color = style.colorArgb
                    }
                }
                canvas.drawText(layer.text, x, y, fillPaint)
            }

            // 3. Simpan ke File
            outputFile.parentFile?.mkdirs()
            val fos = FileOutputStream(outputFile)
            resultBitmap.compress(format, quality, fos)
            fos.flush()
            fos.close()

            OperationResult.Success(outputFile)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal meng-ekspor gambar project: ${e.message}")
        }
    }
}
