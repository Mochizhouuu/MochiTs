package com.mochits.imaging

import android.graphics.Bitmap
import com.mochits.common.OperationResult

/**
 * Wrapper untuk Imgproc.inpaint (algoritma Telea) dari OpenCV Android SDK.
 * Dipanggil langsung tanpa platform channel/bridge.
 */
interface TeleaInpainter {
    suspend fun inpaint(source: Bitmap, mask: Bitmap): OperationResult<Bitmap>
}
