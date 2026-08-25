package com.mochits.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.mochits.common.OperationResult
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Interface & Implementasi alat seleksi gambar: Brush, Lasso, Magic Wand (Flood Fill),
 * serta Color Eyedropper.
 */
interface MaskSelectionTools {
    /**
     * Seleksi Magic Wand menggunakan OpenCV floodFill dengan toleransi warna.
     * @param source Bitmap gambar asli
     * @param existingMask Bitmap mask transparan/hitam-putih yang sudah ada (bisa null jika buat baru)
     * @param startX Koordinat X sentuhan pada gambar (piksel)
     * @param startY Koordinat Y sentuhan pada gambar (piksel)
     * @param tolerance Toleransi perbedaan warna (0..255)
     * @return Bitmap mask berukuran sama (Format ARGB_8888, putih = terpilih, transparan = tidak)
     */
    fun magicWandSelect(
        source: Bitmap,
        existingMask: Bitmap?,
        startX: Int,
        startY: Int,
        tolerance: Int = 20
    ): OperationResult<Bitmap>

    /**
     * Seleksi Lasso bebas (Freehand path).
     */
    fun lassoSelect(
        maskWidth: Int,
        maskHeight: Int,
        existingMask: Bitmap?,
        points: List<Pair<Float, Float>>,
        isSubtract: Boolean = false
    ): OperationResult<Bitmap>

    /**
     * Sapuan Kuas / Brush (Tambah/Kurang pada mask).
     */
    fun drawBrush(
        maskWidth: Int,
        maskHeight: Int,
        existingMask: Bitmap?,
        pathPoints: List<Pair<Float, Float>>,
        brushRadiusPx: Float,
        isSubtract: Boolean = false
    ): OperationResult<Bitmap>

    /**
     * Mengambil warna piksel pada koordinat tertentu (Eyedropper / Pipet).
     */
    fun sampleColor(source: Bitmap, x: Int, y: Int): Int
}

class MaskSelectionToolsImpl : MaskSelectionTools {

    override fun magicWandSelect(
        source: Bitmap,
        existingMask: Bitmap?,
        startX: Int,
        startY: Int,
        tolerance: Int
    ): OperationResult<Bitmap> {
        return try {
            val width = source.width
            val height = source.height

            val clampedX = startX.coerceIn(0, width - 1)
            val clampedY = startY.coerceIn(0, height - 1)

            // Buat bitmap output mask
            val resultMask = existingMask?.copy(Bitmap.Config.ARGB_8888, true)
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Coba OpenCV floodFill jika library OpenCV ter-load, atau fallback ke pure Kotlin floodFill
            var isOpenCvSuccess = false
            try {
                val matSrc = Mat(height, width, CvType.CV_8UC4)
                org.opencv.android.Utils.bitmapToMat(source, matSrc)

                // OpenCV floodFill memerlukan Mat mask berukuran (height + 2, width + 2)
                val matMask = Mat.zeros(height + 2, width + 2, CvType.CV_8UC1)
                val seedPoint = Point(clampedX.toDouble(), clampedY.toDouble())
                val newVal = Scalar(255.0, 255.0, 255.0)
                val loDiff = Scalar(tolerance.toDouble(), tolerance.toDouble(), tolerance.toDouble(), 255.0)
                val upDiff = Scalar(tolerance.toDouble(), tolerance.toDouble(), tolerance.toDouble(), 255.0)

                val flags = 4 or (255 shl 8) or Imgproc.FLOODFILL_FIXED_RANGE

                Imgproc.floodFill(matSrc, matMask, seedPoint, newVal, null, loDiff, upDiff, flags)

                // Mat mask OpenCV menyimpan nilai 255 pada area floodfill
                for (r in 0 until height) {
                    for (c in 0 until width) {
                        val maskPixel = matMask.get(r + 1, c + 1)
                        if (maskPixel != null && maskPixel[0] > 0) {
                            resultMask.setPixel(c, r, Color.WHITE)
                        }
                    }
                }

                matSrc.release()
                matMask.release()
                isOpenCvSuccess = true
            } catch (e: Throwable) {
                // OpenCV belum terinisialisasi / error -> fallback ke pure Kotlin BFS floodFill
                isOpenCvSuccess = false
            }

            if (!isOpenCvSuccess) {
                runKotlinFloodFill(source, resultMask, clampedX, clampedY, tolerance)
            }

            OperationResult.Success(resultMask)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal melakukan Magic Wand selection")
        }
    }

    override fun lassoSelect(
        maskWidth: Int,
        maskHeight: Int,
        existingMask: Bitmap?,
        points: List<Pair<Float, Float>>,
        isSubtract: Boolean
    ): OperationResult<Bitmap> {
        return try {
            val resultMask = existingMask?.copy(Bitmap.Config.ARGB_8888, true)
                ?: Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
            resultMask.setHasAlpha(true)

            if (points.size < 3) {
                return OperationResult.Success(resultMask)
            }

            val canvas = Canvas(resultMask)
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) {
                    lineTo(points[i].first, points[i].second)
                }
                close()
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            canvas.drawPath(path, paint)

            // Fill pixels inside polygon using point-in-polygon algorithm to guarantee full coverage without anti-alias artifacts
            val n = points.size
            val xPoints = FloatArray(n) { points[it].first }
            val yPoints = FloatArray(n) { points[it].second }

            var minX = maskWidth
            var maxX = 0
            var minY = maskHeight
            var maxY = 0
            for (p in points) {
                if (p.first.toInt() < minX) minX = p.first.toInt()
                if (p.first.toInt() > maxX) maxX = p.first.toInt()
                if (p.second.toInt() < minY) minY = p.second.toInt()
                if (p.second.toInt() > maxY) maxY = p.second.toInt()
            }
            minX = minX.coerceIn(0, maskWidth - 1)
            maxX = maxX.coerceIn(0, maskWidth - 1)
            minY = minY.coerceIn(0, maskHeight - 1)
            maxY = maxY.coerceIn(0, maskHeight - 1)

            for (y in minY..maxY) {
                val py = y + 0.5f
                for (x in minX..maxX) {
                    val px = x + 0.5f
                    var inside = false
                    var j = n - 1
                    for (i in 0 until n) {
                        if ((yPoints[i] > py) != (yPoints[j] > py) &&
                            (px < (xPoints[j] - xPoints[i]) * (py - yPoints[i]) / (yPoints[j] - yPoints[i]) + xPoints[i])
                        ) {
                            inside = !inside
                        }
                        j = i
                    }
                    if (inside) {
                        resultMask.setPixel(x, y, if (isSubtract) 0 else Color.WHITE)
                    }
                }
            }
            OperationResult.Success(resultMask)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal melakukan Lasso selection")
        }
    }

    override fun drawBrush(
        maskWidth: Int,
        maskHeight: Int,
        existingMask: Bitmap?,
        pathPoints: List<Pair<Float, Float>>,
        brushRadiusPx: Float,
        isSubtract: Boolean
    ): OperationResult<Bitmap> {
        return try {
            val resultMask = existingMask?.copy(Bitmap.Config.ARGB_8888, true)
                ?: Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)

            if (pathPoints.isEmpty()) {
                return OperationResult.Success(resultMask)
            }

            val canvas = Canvas(resultMask)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = brushRadiusPx * 2f
                if (isSubtract) {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                } else {
                    color = Color.WHITE
                }
            }

            // Gambar sapuan kuas via path dengan ujung membulat — satu kali render
            // oleh Canvas (jauh lebih cepat daripada loop per-piksel).
            if (pathPoints.size == 1) {
                val p = pathPoints[0]
                canvas.drawCircle(p.first, p.second, brushRadiusPx, paint)
            } else {
                val path = Path().apply {
                    moveTo(pathPoints[0].first, pathPoints[0].second)
                    for (i in 1 until pathPoints.size) {
                        lineTo(pathPoints[i].first, pathPoints[i].second)
                    }
                }
                canvas.drawPath(path, paint)
            }

            OperationResult.Success(resultMask)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal melakukan Brush mask")
        }
    }

    override fun sampleColor(source: Bitmap, x: Int, y: Int): Int {
        val clampedX = x.coerceIn(0, source.width - 1)
        val clampedY = y.coerceIn(0, source.height - 1)
        return source.getPixel(clampedX, clampedY)
    }

    private fun runKotlinFloodFill(
        source: Bitmap,
        outputMask: Bitmap,
        startX: Int,
        startY: Int,
        tolerance: Int
    ) {
        val width = source.width
        val height = source.height
        val targetColor = source.getPixel(startX, startY)
        val targetA = Color.alpha(targetColor)
        val targetR = Color.red(targetColor)
        val targetG = Color.green(targetColor)
        val targetB = Color.blue(targetColor)

        val visited = BooleanArray(width * height)
        val queueInt = IntArray(width * height)
        var head = 0
        var tail = 0

        queueInt[tail++] = startY * width + startX
        visited[startY * width + startX] = true

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        while (head < tail) {
            val idx = queueInt[head++]
            val px = idx % width
            val py = idx / width

            outputMask.setPixel(px, py, Color.WHITE)

            // Cek 4 tetangga
            val neighbors = intArrayOf(
                if (px > 0) idx - 1 else -1,
                if (px < width - 1) idx + 1 else -1,
                if (py > 0) idx - width else -1,
                if (py < height - 1) idx + width else -1
            )

            for (nIdx in neighbors) {
                if (nIdx != -1 && !visited[nIdx]) {
                    val c = pixels[nIdx]
                    val diff = abs(Color.red(c) - targetR) +
                            abs(Color.green(c) - targetG) +
                            abs(Color.blue(c) - targetB)
                    if (diff <= tolerance * 3) {
                        visited[nIdx] = true
                        queueInt[tail++] = nIdx
                    }
                }
            }
        }
    }
}
