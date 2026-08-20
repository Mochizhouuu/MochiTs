package com.mochits.inpaint

import android.graphics.Bitmap
import com.mochits.common.OperationResult

/**
 * Engine inferensi model LaMa (TensorFlow Lite). GPU delegate dipakai
 * bila tersedia, dengan fallback otomatis ke CPU (NNAPI/XNNPACK).
 * Selalu dijalankan di background dispatcher — tidak boleh blocking UI thread.
 */
interface LamaInpaintEngine {
    suspend fun loadModel(assetPath: String = "models/lama_int8.tflite"): OperationResult<Unit>
    suspend fun infer(source: Bitmap, mask: Bitmap): OperationResult<Bitmap>
    fun release()
}
