package com.mochits.inpaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.common.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Engine inferensi LaMa Inpainting TFLite dengan dukungan GPU Delegate dan fallback ke CPU/NNAPI.
 */
class LamaInpaintEngineImpl(
    private val context: Context
) : LamaInpaintEngine {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isLoaded = false

    override suspend fun loadModel(assetPath: String): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelBuffer = loadModelBuffer(assetPath)
                ?: return@withContext OperationResult.Failure(
                    IllegalStateException("File model TFLite tidak ditemukan: $assetPath"),
                    "Model LaMa belum diunduh"
                )

            val options = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                options.addDelegate(gpuDelegate)
            } else {
                options.setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
            }

            interpreter = Interpreter(modelBuffer, options)
            isLoaded = true
            OperationResult.Success(Unit)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal memuat model LaMa: ${e.message}")
        }
    }

    override suspend fun infer(source: Bitmap, mask: Bitmap): OperationResult<Bitmap> = withContext(Dispatchers.Default) {
        if (!isLoaded || interpreter == null) {
            return@withContext OperationResult.Failure(
                IllegalStateException("Model LaMa belum dimuat"),
                "Model LaMa belum siap"
            )
        }

        try {
            val inputSize = 512 // Standar resolusi input LaMa TFLite (512x512)
            val scaledSource = Bitmap.createScaledBitmap(source, inputSize, inputSize, true)
            val scaledMask = Bitmap.createScaledBitmap(mask, inputSize, inputSize, true)

            // Buffer input: Image (1x512x512x3) & Mask (1x512x512x1)
            val imgBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            val maskBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 1 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            val srcPixels = IntArray(inputSize * inputSize)
            val maskPixels = IntArray(inputSize * inputSize)
            scaledSource.getPixels(srcPixels, 0, inputSize, 0, 0, inputSize, inputSize)
            scaledMask.getPixels(maskPixels, 0, inputSize, 0, 0, inputSize, inputSize)

            for (i in 0 until inputSize * inputSize) {
                val c = srcPixels[i]
                imgBuffer.putFloat(((c shr 16 and 0xFF) / 255.0f))
                imgBuffer.putFloat(((c shr 8 and 0xFF) / 255.0f))
                imgBuffer.putFloat(((c and 0xFF) / 255.0f))

                val m = maskPixels[i]
                val maskVal = if (Color.alpha(m) > 10 || (m and 0xFFFFFF) > 0) 1.0f else 0.0f
                maskBuffer.putFloat(maskVal)
            }

            val inputs = arrayOf<Any>(imgBuffer, maskBuffer)
            val outputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            val outputs = mutableMapOf<Int, Any>(0 to outputBuffer)

            interpreter?.runForMultipleInputsOutputs(inputs, outputs)

            outputBuffer.rewind()
            val resultBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            val outPixels = IntArray(inputSize * inputSize)

            for (i in 0 until inputSize * inputSize) {
                val r = (outputBuffer.float.coerceIn(0f, 1f) * 255).toInt()
                val g = (outputBuffer.float.coerceIn(0f, 1f) * 255).toInt()
                val b = (outputBuffer.float.coerceIn(0f, 1f) * 255).toInt()
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            resultBitmap.setPixels(outPixels, 0, inputSize, 0, 0, inputSize, inputSize)

            // Scale hasil inferensi ke ukuran asli bitmap sumber
            val scaledResult = Bitmap.createScaledBitmap(resultBitmap, source.width, source.height, true)

            // Komposit: piksel di luar mask dipertahankan dari gambar asli,
            // hanya area di dalam mask yang diganti hasil LaMa — agar detail
            // gambar asli di luar mask tidak hilang akibat resize 512x512.
            val w = source.width
            val h = source.height
            val origPixels = IntArray(w * h)
            val genPixels = IntArray(w * h)
            val compMaskPixels = IntArray(w * h)
            source.getPixels(origPixels, 0, w, 0, 0, w, h)
            scaledResult.getPixels(genPixels, 0, w, 0, 0, w, h)
            val scaledMask = Bitmap.createScaledBitmap(mask, w, h, true)
            scaledMask.getPixels(compMaskPixels, 0, w, 0, 0, w, h)

            for (i in 0 until w * h) {
                val m = compMaskPixels[i]
                val isMasked = Color.alpha(m) > 10 || (m and 0xFFFFFF) > 0
                if (!isMasked) {
                    genPixels[i] = origPixels[i]
                }
            }

            val finalOutput = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            finalOutput.setPixels(genPixels, 0, w, 0, 0, w, h)
            OperationResult.Success(finalOutput)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal menjalankan inferensi LaMa: ${e.message}")
        }
    }

    override fun release() {
        try {
            interpreter?.close()
            gpuDelegate?.close()
        } catch (ignored: Exception) {}
        interpreter = null
        gpuDelegate = null
        isLoaded = false
    }

    private fun loadModelBuffer(pathStr: String): ByteBuffer? {
        return try {
            val file = File(pathStr)
            if (file.exists()) {
                val inputStream = FileInputStream(file)
                val fileChannel = inputStream.channel
                fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
            } else {
                val assetFileDescriptor = context.assets.openFd(pathStr)
                val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
                val fileChannel = inputStream.channel
                val startOffset = assetFileDescriptor.startOffset
                val declaredLength = assetFileDescriptor.declaredLength
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            }
        } catch (e: Exception) {
            null
        }
    }
}
