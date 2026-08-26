package com.mochits.imaging

import android.graphics.Bitmap
import com.mochits.common.OperationResult
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Implementasi TeleaInpainter menggunakan OpenCV Imgproc.inpaint.
 * Jika OpenCV native library tidak/belum ter-load, menyediakan fallback
 * inpainting berbasis average pixel neighborhood.
 */
class TeleaInpainterImpl : TeleaInpainter {

    override suspend fun inpaint(source: Bitmap, mask: Bitmap): OperationResult<Bitmap> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val width = source.width
            val height = source.height

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            var isOpenCvSuccess: Boolean
            try {
                // Utils.bitmapToMat hanya menerima ARGB_8888 (CV_8UC4) — pastikan
                // kedua bitmap dikonversi dulu, lalu ubah ke BGR/gray untuk OpenCV.
                val safeSource = if (source.config == Bitmap.Config.ARGB_8888) source
                else source.copy(Bitmap.Config.ARGB_8888, false)
                val safeMask = if (mask.config == Bitmap.Config.ARGB_8888) mask
                else mask.copy(Bitmap.Config.ARGB_8888, false)

                val srcRgba = Mat(height, width, CvType.CV_8UC4)
                val dstRgba = Mat(height, width, CvType.CV_8UC4)
                val maskRgba = Mat(height, width, CvType.CV_8UC4)
                val srcBgr = Mat()
                val maskGray = Mat()
                val dstBgr = Mat()
                try {
                    Utils.bitmapToMat(safeSource, srcRgba)
                    Imgproc.cvtColor(srcRgba, srcBgr, Imgproc.COLOR_RGBA2BGR)

                    Utils.bitmapToMat(safeMask, maskRgba)
                    Imgproc.cvtColor(maskRgba, maskGray, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.threshold(maskGray, maskGray, 8.0, 255.0, Imgproc.THRESH_BINARY)

                    // Imgproc.inpaint (Telea radius = 3.0)
                    Photo.inpaint(srcBgr, maskGray, dstBgr, 3.0, Photo.INPAINT_TELEA)

                    Imgproc.cvtColor(dstBgr, dstRgba, Imgproc.COLOR_BGR2RGBA)
                    Utils.matToBitmap(dstRgba, output)
                } finally {
                    srcRgba.release()
                    maskRgba.release()
                    dstRgba.release()
                    srcBgr.release()
                    maskGray.release()
                    dstBgr.release()
                }
                isOpenCvSuccess = true
            } catch (e: Throwable) {
                isOpenCvSuccess = false
            }

            if (!isOpenCvSuccess) {
                android.util.Log.w("TeleaInpainter", "OpenCV tidak tersedia, pakai fallback inpainting Kotlin")
                // Fallback inpainter sederhana jika OpenCV native SO file belum dimuat
                runFallbackInpaint(source, mask, output)
            }

                OperationResult.Success(output)
            } catch (e: Exception) {
                OperationResult.Failure(e, "Gagal melakukan Telea Inpainting")
            }
        }

    /**
     * Fallback inpainting tanpa OpenCV: isi piksel ter-mask dengan rata-rata
     * tetangga yang valid, secara ITERATIF — pass berikutnya memakai hasil
     * pass sebelumnya sehingga area mask yang seluruh lingkungannya juga
     * ter-mask (lubang dalam) tetap terisi, tidak dibiarkan kosong.
     *
     * Kriteria mask hanya dari alpha (>10): cek tambahan "rgb != 0" membuat
     * piksel transparan dengan residu RGB (umum pada PNG hasil editing)
     * salah terdeteksi sebagai mask.
     */
    private fun runFallbackInpaint(source: Bitmap, mask: Bitmap, output: Bitmap) {
        val width = source.width
        val height = source.height

        val srcPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        val outPixels = IntArray(width * height)

        source.getPixels(srcPixels, 0, width, 0, 0, width, height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        System.arraycopy(srcPixels, 0, outPixels, 0, srcPixels.size)

        val isMasked = BooleanArray(width * height) { (maskPixels[it] ushr 24) > 10 }
        val isFilled = BooleanArray(width * height)
        var remaining = isMasked.count { it }

        val radius = 5
        var guard = 0
        while (remaining > 0 && guard < 64) {
            guard++
            var changedThisPass = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    if (!isMasked[idx] || isFilled[idx]) continue

                    var rSum = 0L
                    var gSum = 0L
                    var bSum = 0L
                    var count = 0

                    for (dy in -radius..radius) {
                        val ny = y + dy
                        if (ny in 0 until height) {
                            for (dx in -radius..radius) {
                                val nx = x + dx
                                if (nx in 0 until width) {
                                    val nIdx = ny * width + nx
                                    // Sumber warna: piksel asli tak ter-mask ATAU
                                    // piksel ter-mask yang sudah terisi pass lain.
                                    if ((!isMasked[nIdx] || isFilled[nIdx]) && nIdx != idx) {
                                        val c = outPixels[nIdx]
                                        rSum += (c shr 16) and 0xFF
                                        gSum += (c shr 8) and 0xFF
                                        bSum += c and 0xFF
                                        count++
                                    }
                                }
                            }
                        }
                    }

                    if (count > 0) {
                        val r = (rSum / count).toInt()
                        val g = (gSum / count).toInt()
                        val b = (bSum / count).toInt()
                        outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        isFilled[idx] = true
                        changedThisPass++
                    }
                }
            }
            if (changedThisPass == 0) break // tidak ada progres — hentikan
            remaining -= changedThisPass
        }
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
    }
}
