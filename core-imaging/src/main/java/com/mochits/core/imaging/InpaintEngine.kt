package com.mochits.core.imaging

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable) : Result<Nothing>()
    object Loading : Result<Nothing>()
}

class InpaintEngine {

    /** OpenCV benar-benar tersedia (bukan fallback no-op). */
    private val openCvAvailable: Boolean by lazy {
        NativeBridge.isNativeAvailable && try {
            NativeBridge.nativeGetOpenCVVersion() != "OpenCV Not Loaded"
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    suspend fun inpaintTelea(
        sourceBitmap: Bitmap,
        maskBitmap: Bitmap,
        radius: Float = 5f
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        if (!openCvAvailable) {
            return@withContext Result.Error(
                IllegalStateException(
                    "Mesin inpaint Telea (OpenCV) tidak tersedia di build ini. Gunakan model LaMa."
                )
            )
        }
        try {
            val width = sourceBitmap.width
            val height = sourceBitmap.height

            val safeSource = if (sourceBitmap.config == Bitmap.Config.ARGB_8888) {
                sourceBitmap
            } else {
                sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }

            val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val success = NativeBridge.nativeInpaintTelea(
                safeSource,
                maskBitmap,
                resultBitmap,
                radius
            )

            if (safeSource != sourceBitmap) {
                safeSource.recycle()
            }

            if (success) {
                Result.Success(resultBitmap)
            } else {
                resultBitmap.recycle()
                Result.Error(RuntimeException("Native inpaint Telea failed"))
            }
        } catch (oom: OutOfMemoryError) {
            System.gc()
            Result.Error(Exception("Memori tidak cukup untuk menginpaint gambar sebesar ini.", oom))
        } catch (e: Throwable) {
            Result.Error(e)
        }
    }
}
