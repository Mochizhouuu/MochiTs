package com.mochits.app.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.mochits.core.imaging.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

class LaMaInpaintEngine(private val context: Context) {

    private val modelManager = LaMaModelManager(context)
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    suspend fun inpaintLaMa(
        baseBitmap: Bitmap,
        maskBitmap: Bitmap
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        if (!modelManager.isModelDownloaded()) {
            return@withContext Result.Error(IllegalStateException("Model LaMa Manga belum terunduh"))
        }

        val modelFile = modelManager.getModelFile()
        try {
            if (ortEnv == null) {
                ortEnv = OrtEnvironment.getEnvironment()
            }

            val env = ortEnv ?: return@withContext Result.Error(IllegalStateException("Gagal membuat OrtEnvironment"))

            if (ortSession == null) {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                ortSession = env.createSession(modelFile.absolutePath, opts)
            }

            val session = ortSession ?: return@withContext Result.Error(IllegalStateException("Gagal membuat OrtSession"))

            val targetSize = 512

            val scaledBase = Bitmap.createScaledBitmap(baseBitmap, targetSize, targetSize, true)
            val scaledMask = Bitmap.createScaledBitmap(maskBitmap, targetSize, targetSize, true)

            val imgBuffer = FloatBuffer.allocate(1 * 3 * targetSize * targetSize)
            val maskBuffer = FloatBuffer.allocate(1 * 1 * targetSize * targetSize)

            val pixels = IntArray(targetSize * targetSize)
            scaledBase.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

            val maskPixels = IntArray(targetSize * targetSize)
            scaledMask.getPixels(maskPixels, 0, targetSize, 0, 0, targetSize, targetSize)

            // Fill imgBuffer in NCHW format [1, 3, 512, 512]
            val planeSize = targetSize * targetSize
            for (i in 0 until planeSize) {
                val px = pixels[i]
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f

                imgBuffer.put(0 * planeSize + i, r)
                imgBuffer.put(1 * planeSize + i, g)
                imgBuffer.put(2 * planeSize + i, b)
            }

            // Fill maskBuffer in NCHW format [1, 1, 512, 512]
            for (i in 0 until planeSize) {
                val mPx = maskPixels[i]
                val mVal = if (Color.alpha(mPx) > 0 || (mPx and 0xFF) > 0) 1.0f else 0.0f
                maskBuffer.put(i, mVal)
            }

            imgBuffer.rewind()
            maskBuffer.rewind()

            val imageShape = longArrayOf(1, 3, targetSize.toLong(), targetSize.toLong())
            val maskShape = longArrayOf(1, 1, targetSize.toLong(), targetSize.toLong())

            val imageTensor = OnnxTensor.createTensor(env, imgBuffer, imageShape)
            val maskTensor = OnnxTensor.createTensor(env, maskBuffer, maskShape)

            val inputs = mapOf(
                "image" to imageTensor,
                "mask" to maskTensor
            )

            val results = session.run(inputs)
            val outputTensorValue = results[0].value

            val outPixels = IntArray(planeSize)

            if (outputTensorValue is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val floatArray4D = outputTensorValue as Array<Array<Array<FloatArray>>>
                for (i in 0 until planeSize) {
                    val y = i / targetSize
                    val x = i % targetSize
                    val r = (floatArray4D[0][0][y][x].coerceIn(0f, 1f) * 255f).toInt()
                    val g = (floatArray4D[0][1][y][x].coerceIn(0f, 1f) * 255f).toInt()
                    val b = (floatArray4D[0][2][y][x].coerceIn(0f, 1f) * 255f).toInt()
                    outPixels[i] = Color.rgb(r, g, b)
                }
            } else if (outputTensorValue is FloatBuffer) {
                val buf = outputTensorValue
                buf.rewind()
                for (i in 0 until planeSize) {
                    val r = (buf.get(0 * planeSize + i).coerceIn(0f, 1f) * 255f).toInt()
                    val g = (buf.get(1 * planeSize + i).coerceIn(0f, 1f) * 255f).toInt()
                    val b = (buf.get(2 * planeSize + i).coerceIn(0f, 1f) * 255f).toInt()
                    outPixels[i] = Color.rgb(r, g, b)
                }
            }

            imageTensor.close()
            maskTensor.close()
            results.close()

            val result512 = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            result512.setPixels(outPixels, 0, targetSize, 0, 0, targetSize, targetSize)

            // Blend output bitmap ONLY over masked regions onto baseBitmap
            val finalBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(finalBitmap)
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            val scaledResult = Bitmap.createScaledBitmap(result512, baseBitmap.width, baseBitmap.height, true)

            val maskedResult = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
            val tempCanvas = Canvas(maskedResult)
            tempCanvas.drawBitmap(scaledResult, 0f, 0f, paint)

            val maskPaint = Paint().apply {
                isAntiAlias = true
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            tempCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)

            canvas.drawBitmap(maskedResult, 0f, 0f, paint)

            Result.Success(finalBitmap)
        } catch (t: Throwable) {
            t.printStackTrace()
            Result.Error(Exception("Inference LaMa Manga gagal: ${t.message}", t))
        }
    }

    fun close() {
        try {
            ortSession?.close()
            ortSession = null
            ortEnv?.close()
            ortEnv = null
        } catch (_: Throwable) {}
    }
}
