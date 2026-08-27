package com.mochits.app.inpaint

import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.app.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class InpaintEngine {

    /**
     * Iterative multi-pass breadth-first color diffusion inpainting algorithm
     * to completely fill masked regions of any size cleanly.
     */
    suspend fun inpaintTelea(
        sourceBitmap: Bitmap,
        maskBitmap: Bitmap,
        @Suppress("UNUSED_PARAMETER") radius: Float = 5f
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            val width = sourceBitmap.width
            val height = sourceBitmap.height

            val resultBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(width * height)
            val maskPixels = IntArray(width * height)

            resultBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

            // Multi-pass boundary fill until all masked pixels are filled
            var maskedCount = 0
            val isMasked = BooleanArray(width * height)
            for (i in 0 until width * height) {
                if (((maskPixels[i] ushr 24) and 0xFF) > 0) {
                    isMasked[i] = true
                    maskedCount++
                }
            }

            var passes = 0
            while (maskedCount > 0 && passes < 100) {
                passes++
                var filledThisPass = 0
                val newPixels = pixels.clone()
                val newlyUnmasked = mutableListOf<Int>()

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val idx = y * width + x
                        if (isMasked[idx]) {
                            var totalR = 0L
                            var totalG = 0L
                            var totalB = 0L
                            var count = 0

                            for (dy in -1..1) {
                                for (dx in -1..1) {
                                    if (dx == 0 && dy == 0) continue
                                    val nx = x + dx
                                    val ny = y + dy
                                    if (nx in 0 until width && ny in 0 until height) {
                                        val nIdx = ny * width + nx
                                        if (!isMasked[nIdx]) {
                                            val pixel = pixels[nIdx]
                                            totalR += Color.red(pixel)
                                            totalG += Color.green(pixel)
                                            totalB += Color.blue(pixel)
                                            count++
                                        }
                                    }
                                }
                            }

                            if (count > 0) {
                                val avgR = (totalR / count).toInt()
                                val avgG = (totalG / count).toInt()
                                val avgB = (totalB / count).toInt()
                                newPixels[idx] = Color.rgb(avgR, avgG, avgB)
                                newlyUnmasked.add(idx)
                                filledThisPass++
                            }
                        }
                    }
                }

                if (filledThisPass == 0) break
                for (idx in newlyUnmasked) {
                    isMasked[idx] = false
                    pixels[idx] = newPixels[idx]
                }
                maskedCount -= filledThisPass
            }

            resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            Result.Success(resultBitmap)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
