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

    suspend fun inpaintTelea(
        sourceBitmap: Bitmap,
        maskBitmap: Bitmap,
        radius: Float = 5f
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
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
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
