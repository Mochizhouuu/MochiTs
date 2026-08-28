package com.mochits.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.mochits.core.imaging.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LaMaInpaintEngine(private val context: Context) {

    private val modelManager = LaMaModelManager(context)
    private var interpreter: Interpreter? = null

    suspend fun inpaintLaMa(
        baseBitmap: Bitmap,
        maskBitmap: Bitmap
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        if (!modelManager.isModelDownloaded()) {
            return@withContext Result.Error(IllegalStateException("Model LaMa belum terunduh"))
        }

        val modelFile = modelManager.getModelFile()
        try {
            if (interpreter == null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(modelFile, options)
            }

            val targetSize = 512

            // Scale base bitmap & mask bitmap to 512x512
            val scaledBase = Bitmap.createScaledBitmap(baseBitmap, targetSize, targetSize, true)
            val scaledMask = Bitmap.createScaledBitmap(maskBitmap, targetSize, targetSize, true)

            val imgBuffer = ByteBuffer.allocateDirect(1 * targetSize * targetSize * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            val maskBuffer = ByteBuffer.allocateDirect(1 * targetSize * targetSize * 1 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            val pixels = IntArray(targetSize * targetSize)
            scaledBase.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

            val maskPixels = IntArray(targetSize * targetSize)
            scaledMask.getPixels(maskPixels, 0, targetSize, 0, 0, targetSize, targetSize)

            for (i in 0 until targetSize * targetSize) {
                val px = pixels[i]
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f

                imgBuffer.putFloat(r)
                imgBuffer.putFloat(g)
                imgBuffer.putFloat(b)

                val mPx = maskPixels[i]
                val mVal = if (Color.alpha(mPx) > 0 || (mPx and 0xFF) > 0) 1.0f else 0.0f
                maskBuffer.putFloat(mVal)
            }

            imgBuffer.rewind()
            maskBuffer.rewind()

            val outputBuffer = ByteBuffer.allocateDirect(1 * targetSize * targetSize * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            val inputs = arrayOf<Any>(imgBuffer, maskBuffer)
            val outputs = mutableMapOf<Int, Any>(0 to outputBuffer)

            try {
                interpreter?.runForMultipleInputsOutputs(inputs, outputs)
            } catch (t: Throwable) {
                // Single input fallback if model accepts combined tensor
                interpreter?.run(imgBuffer, outputBuffer)
            }

            outputBuffer.rewind()

            val result512 = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val outPixels = IntArray(targetSize * targetSize)

            for (i in 0 until targetSize * targetSize) {
                val r = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                val g = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                val b = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                outPixels[i] = Color.rgb(r, g, b)
            }
            result512.setPixels(outPixels, 0, targetSize, 0, 0, targetSize, targetSize)

            // Blend output bitmap ONLY over masked regions onto baseBitmap
            val finalBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(finalBitmap)
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            val scaledResult = Bitmap.createScaledBitmap(result512, baseBitmap.width, baseBitmap.height, true)

            // Create composite bitmap that masks the scaled inpainted result
            val maskedResult = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
            val tempCanvas = Canvas(maskedResult)
            tempCanvas.drawBitmap(scaledResult, 0f, 0f, paint)

            val maskPaint = Paint().apply {
                isAntiAlias = true
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            }
            tempCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)

            canvas.drawBitmap(maskedResult, 0f, 0f, paint)

            Result.Success(finalBitmap)
        } catch (t: Throwable) {
            t.printStackTrace()
            Result.Error(Exception("Inference LaMa gagal: ${t.message}", t))
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (_: Throwable) {}
    }
}
