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
            // Pisahkan try init Mat dari proses fill: exception programming
            // (mis. ukuran existingMask != source) tidak boleh diam-diam
            // memicu fallback dan menyembunyikan bug.
            val openCvReady = runCatching {
                Pair(
                    Mat(height, width, CvType.CV_8UC4).also {
                        org.opencv.android.Utils.bitmapToMat(source, it)
                    },
                    Mat.zeros(height + 2, width + 2, CvType.CV_8UC1)
                )
            }
            if (openCvReady.isSuccess) {
                val (matSrc, matMask) = openCvReady.getOrThrow()
                try {
                    val seedPoint = Point(clampedX.toDouble(), clampedY.toDouble())
                    val newVal = Scalar(255.0, 255.0, 255.0)
                    val loDiff = Scalar(tolerance.toDouble(), tolerance.toDouble(), tolerance.toDouble(), 255.0)
                    val upDiff = Scalar(tolerance.toDouble(), tolerance.toDouble(), tolerance.toDouble(), 255.0)

                    val flags = 4 or (255 shl 8) or Imgproc.FLOODFILL_FIXED_RANGE

                    Imgproc.floodFill(matSrc, matMask, seedPoint, newVal, null, loDiff, upDiff, flags)

                    // Salin hasil floodFill dari Mat mask ke bitmap secara BULK
                    // (baca per-baris + setPixels sekali panggil). Loop get/setPixel
                    // per-piksel memicu panggilan JNI jutaan kali pada gambar
                    // webtoon berukuran besar dan membuat UI membeku.
                    val outPixels = IntArray(width * height)
                    resultMask.getPixels(outPixels, 0, width, 0, 0, width, height)
                    val rowData = DoubleArray(width)
                    for (r in 0 until height) {
                        val count = matMask.get(r + 1, 1, rowData)
                        var base = r * width
                        for (c in 0 until count) {
                            if (rowData[c] > 0.0) {
                                outPixels[base + c] = Color.WHITE
                            }
                        }
                    }
                    resultMask.setPixels(outPixels, 0, width, 0, 0, width, height)
                    isOpenCvSuccess = true
                } finally {
                    matSrc.release()
                    matMask.release()
                }
            } else {
                android.util.Log.w("MaskSelectionTools", "OpenCV tidak tersedia, fallback ke BFS Kotlin")
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

            // Pre-draw path HANYA untuk mode tambah. Saat isSubtract,
            // menggambar putih lalu menimpa interior dengan 0 menyisakan
            // cincin piksel anti-alias putih di tepi polygon — operasi
            // "kurangi mask" justru menambah mask. Pengurangan cukup
            // ditangani loop point-in-polygon di bawah.
            if (!isSubtract) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
                canvas.drawPath(path, paint)
            }

            // Fill pixels inside polygon using point-in-polygon algorithm to guarantee full coverage without anti-alias artifacts
            // (dioperasikan pada array piksel lalu ditulis balik sekali —
            // setPixel per-piksel terlalu lambat untuk gambar besar).
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

            val outPixels = IntArray(maskWidth * maskHeight)
            resultMask.getPixels(outPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

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
                        outPixels[y * maskWidth + x] = if (isSubtract) 0 else Color.WHITE
                    }
                }
            }
            resultMask.setPixels(outPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
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
                // Titik tunggal: pakai FILL agar diameternya = 2x radius,
                // konsisten dengan garis sapuan (STROKE melebar ke dua sisi
                // sehingga drawCircle ber-radius r menghasilkan diameter 4r).
                val p = pathPoints[0]
                val dotPaint = Paint(paint).apply {
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(p.first, p.second, brushRadiusPx, dotPaint)
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
        val targetA = (targetColor ushr 24) and 0xFF
        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        val visited = BooleanArray(width * height)
        val queueInt = IntArray(width * height)
        var head = 0
        var tail = 0

        queueInt[tail++] = startY * width + startX
        visited[startY * width + startX] = true

        val pixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        outputMask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        while (head < tail) {
            val idx = queueInt[head++]
            val px = idx % width
            val py = idx / width

            maskPixels[idx] = Color.WHITE

            // Empat tetangga di-unroll tanpa alokasi array per piksel
            // (intArrayOf dalam hot loop BFS jutaan piksel membebani GC).
            if (px > 0) {
                val nIdx = idx - 1
                if (!visited[nIdx] && colorClose(pixels[nIdx], targetA, targetR, targetG, targetB, tolerance)) {
                    visited[nIdx] = true
                    queueInt[tail++] = nIdx
                }
            }
            if (px < width - 1) {
                val nIdx = idx + 1
                if (!visited[nIdx] && colorClose(pixels[nIdx], targetA, targetR, targetG, targetB, tolerance)) {
                    visited[nIdx] = true
                    queueInt[tail++] = nIdx
                }
            }
            if (py > 0) {
                val nIdx = idx - width
                if (!visited[nIdx] && colorClose(pixels[nIdx], targetA, targetR, targetG, targetB, tolerance)) {
                    visited[nIdx] = true
                    queueInt[tail++] = nIdx
                }
            }
            if (py < height - 1) {
                val nIdx = idx + width
                if (!visited[nIdx] && colorClose(pixels[nIdx], targetA, targetR, targetG, targetB, tolerance)) {
                    visited[nIdx] = true
                    queueInt[tail++] = nIdx
                }
            }
        }
        outputMask.setPixels(maskPixels, 0, width, 0, 0, width, height)
    }

    private fun colorClose(
        c: Int,
        targetA: Int,
        targetR: Int,
        targetG: Int,
        targetB: Int,
        tolerance: Int
    ): Boolean {
        val a = (c ushr 24) and 0xFF
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return abs(r - targetR) <= tolerance &&
            abs(g - targetG) <= tolerance &&
            abs(b - targetB) <= tolerance &&
            abs(a - targetA) <= tolerance
    }
}
