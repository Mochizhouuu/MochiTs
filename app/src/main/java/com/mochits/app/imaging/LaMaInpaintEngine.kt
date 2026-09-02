package com.mochits.app.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.core.imaging.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class LaMaInpaintEngine(private val context: Context) {

    private val modelManager = LaMaModelManager.getInstance(context)
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

            val width = baseBitmap.width
            val height = baseBitmap.height

            // 1. Find mask bounding box
            val maskPixels = IntArray(width * height)
            maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    val px = maskPixels[offset + x]
                    val alpha = max((px ushr 24) and 0xFF, px and 0xFF)
                    if (alpha > 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            // If mask is completely empty, return base copy
            if (maxX < 0 || maxY < 0) {
                return@withContext Result.Success(baseBitmap.copy(baseBitmap.config, true))
            }

            // 2. Add padding around mask (at least 64px or 50% of mask dimension)
            val maskW = maxX - minX + 1
            val maskH = maxY - minY + 1
            val padding = max(max(maskW, maskH) / 2, 64)

            var cropLeft = max(0, minX - padding)
            var cropTop = max(0, minY - padding)
            var cropRight = min(width, maxX + 1 + padding)
            var cropBottom = min(height, maxY + 1 + padding)

            // Adjust crop box to be square or close to square
            var cropW = cropRight - cropLeft
            var cropH = cropBottom - cropTop

            if (cropW < cropH) {
                val diff = cropH - cropW
                val addLeft = diff / 2
                val addRight = diff - addLeft
                cropLeft = max(0, cropLeft - addLeft)
                cropRight = min(width, cropRight + addRight)
            } else if (cropH < cropW) {
                val diff = cropW - cropH
                val addTop = diff / 2
                val addBottom = diff - addTop
                cropTop = max(0, cropTop - addTop)
                cropBottom = min(height, cropBottom + addBottom)
            }

            cropW = cropRight - cropLeft
            cropH = cropBottom - cropTop

            // 3. Crop base and mask bitmaps to bounding box
            val croppedBase = Bitmap.createBitmap(baseBitmap, cropLeft, cropTop, cropW, cropH)
            val croppedMask = Bitmap.createBitmap(maskBitmap, cropLeft, cropTop, cropW, cropH)

            // 4. Scale cropped bitmaps to 512x512 using bilinear filtering
            val targetSize = 512
            val scaledBase = Bitmap.createScaledBitmap(croppedBase, targetSize, targetSize, true)
            val scaledMask = Bitmap.createScaledBitmap(croppedMask, targetSize, targetSize, true)

            // 5. Fill ONNX input buffers
            val planeSize = targetSize * targetSize
            val imgBuffer = FloatBuffer.allocate(1 * 3 * planeSize)
            val maskBuffer = FloatBuffer.allocate(1 * 1 * planeSize)

            val scaledBasePixels = IntArray(planeSize)
            scaledBase.getPixels(scaledBasePixels, 0, targetSize, 0, 0, targetSize, targetSize)

            val scaledMaskPixels = IntArray(planeSize)
            scaledMask.getPixels(scaledMaskPixels, 0, targetSize, 0, 0, targetSize, targetSize)

            for (i in 0 until planeSize) {
                val px = scaledBasePixels[i]
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f

                imgBuffer.put(0 * planeSize + i, r)
                imgBuffer.put(1 * planeSize + i, g)
                imgBuffer.put(2 * planeSize + i, b)
            }

            for (i in 0 until planeSize) {
                val mPx = scaledMaskPixels[i]
                val mVal = if (max((mPx ushr 24) and 0xFF, mPx and 0xFF) > 0) 1.0f else 0.0f
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

            // Read output floats to intermediate buffer
            val rawOutputFloats = FloatArray(3 * planeSize)

            if (outputTensorValue is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val floatArray4D = outputTensorValue as Array<Array<Array<FloatArray>>>
                for (ch in 0..2) {
                    val chOffset = ch * planeSize
                    for (y in 0 until targetSize) {
                        val rowOffset = y * targetSize
                        for (x in 0 until targetSize) {
                            rawOutputFloats[chOffset + rowOffset + x] = floatArray4D[0][ch][y][x]
                        }
                    }
                }
            } else if (outputTensorValue is FloatBuffer) {
                val buf = outputTensorValue
                buf.rewind()
                buf.get(rawOutputFloats)
            }

            imageTensor.close()
            maskTensor.close()
            results.close()

            // Determine output range dynamically
            var minVal = Float.MAX_VALUE
            var maxVal = -Float.MAX_VALUE
            for (f in rawOutputFloats) {
                if (f < minVal) minVal = f
                if (f > maxVal) maxVal = f
            }

            val outPixels = IntArray(planeSize)
            val rOffset = 0 * planeSize
            val gOffset = 1 * planeSize
            val bOffset = 2 * planeSize

            for (i in 0 until planeSize) {
                val rRaw = rawOutputFloats[rOffset + i]
                val gRaw = rawOutputFloats[gOffset + i]
                val bRaw = rawOutputFloats[bOffset + i]

                val r = normalizeColorComponent(rRaw, minVal, maxVal)
                val g = normalizeColorComponent(gRaw, minVal, maxVal)
                val b = normalizeColorComponent(bRaw, minVal, maxVal)

                outPixels[i] = Color.rgb(r, g, b)
            }

            val result512 = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            result512.setPixels(outPixels, 0, targetSize, 0, 0, targetSize, targetSize)

            // 6. Scale 512x512 inpaint result back to crop size (cropW, cropH)
            val scaledResult = Bitmap.createScaledBitmap(result512, cropW, cropH, true)

            // 7. Composite inpaint result back ONLY over masked pixels onto a copy of baseBitmap
            val finalBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)

            val basePixels = IntArray(cropW * cropH)
            finalBitmap.getPixels(basePixels, 0, cropW, cropLeft, cropTop, cropW, cropH)

            val croppedMaskPixels = IntArray(cropW * cropH)
            croppedMask.getPixels(croppedMaskPixels, 0, cropW, 0, 0, cropW, cropH)

            val inpaintPixels = IntArray(cropW * cropH)
            scaledResult.getPixels(inpaintPixels, 0, cropW, 0, 0, cropW, cropH)

            for (i in 0 until (cropW * cropH)) {
                val mPx = croppedMaskPixels[i]
                val mAlpha = max((mPx ushr 24) and 0xFF, mPx and 0xFF)
                if (mAlpha > 0) {
                    if (mAlpha == 255) {
                        basePixels[i] = inpaintPixels[i]
                    } else {
                        val factor = mAlpha / 255.0f
                        val invFactor = 1.0f - factor

                        val bp = basePixels[i]
                        val ip = inpaintPixels[i]

                        val rB = (bp shr 16) and 0xFF
                        val gB = (bp shr 8) and 0xFF
                        val bB = bp and 0xFF

                        val rI = (ip shr 16) and 0xFF
                        val gI = (ip shr 8) and 0xFF
                        val bI = ip and 0xFF

                        val rOut = (rI * factor + rB * invFactor).toInt().coerceIn(0, 255)
                        val gOut = (gI * factor + gB * invFactor).toInt().coerceIn(0, 255)
                        val bOut = (bI * factor + bB * invFactor).toInt().coerceIn(0, 255)

                        basePixels[i] = Color.argb(
                            (bp ushr 24) and 0xFF,
                            rOut,
                            gOut,
                            bOut
                        )
                    }
                }
            }

            finalBitmap.setPixels(basePixels, 0, cropW, cropLeft, cropTop, cropW, cropH)

            // Clean up temporary bitmaps
            croppedBase.recycle()
            croppedMask.recycle()
            scaledBase.recycle()
            scaledMask.recycle()
            result512.recycle()
            scaledResult.recycle()

            Result.Success(finalBitmap)
        } catch (t: Throwable) {
            t.printStackTrace()
            Result.Error(Exception("Inference LaMa Manga gagal: ${t.message}", t))
        }
    }

    private fun normalizeColorComponent(valRaw: Float, minVal: Float, maxVal: Float): Int {
        val val255 = when {
            minVal < -0.1f -> ((valRaw + 1f) / 2f * 255f)
            maxVal > 1.5f -> valRaw
            else -> valRaw * 255f
        }
        return val255.toInt().coerceIn(0, 255)
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
