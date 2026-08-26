package com.mochits.app.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mochits.canvas.CanvasEditorState
import com.mochits.common.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pengolah ekspor project ke berkas gambar (JPEG / PNG) dan penyimpanan
 * ke galeri perangkat.
 *
 * Kompatibilitas penyimpanan:
 * - Android 10+ (API 29): MediaStore dengan RELATIVE_PATH — tanpa izin apa pun.
 * - Android 9 ke bawah : folder app-specific (getExternalFilesDir/Pictures)
 *   yang juga bebas izin, bisa diakses via file manager / aplikasi galeri.
 */
object ProjectExporter {

    /**
     * Merender seluruh layer di atas base image lalu menyimpan hasilnya ke
     * galeri perangkat pada sub-folder [subfolder] dengan kualitas [quality].
     * @return path/uri hasil simpan sebagai String.
     */
    suspend fun exportToGallery(
        context: Context,
        baseImageBitmap: Bitmap,
        state: CanvasEditorState,
        subfolder: String,
        quality: Int = 100,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG
    ): OperationResult<String> = withContext(Dispatchers.IO) {
        try {
            val resultBitmap = renderComposite(baseImageBitmap, state)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // Ekstensi & MIME harus konsisten dengan format kompresi —
            // sebelumnya hardcode PNG meski format bisa JPEG.
            val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
            val fileName = "MochiTs_$timestamp.$extension"
            val safeSubfolder = subfolder.split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/")

            val savedRef: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, resultBitmap, fileName, safeSubfolder, format, quality)
                    ?: return@withContext OperationResult.Failure(
                        IllegalStateException("Gagal membuat entri MediaStore"),
                        "Gagal menyimpan ke galeri"
                    )
            } else {
                saveToAppSpecificDir(context, resultBitmap, fileName, safeSubfolder, format, quality)
            }

            OperationResult.Success(savedRef)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal meng-ekspor gambar: ${e.message}")
        }
    }

    /** Menyusun bitmap akhir: base image + seluruh text layer & efeknya. */
    fun renderComposite(baseImageBitmap: Bitmap, state: CanvasEditorState): Bitmap {
        val width = state.intrinsicWidthPx
        val height = state.intrinsicHeightPx

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // 1. Gambar Base Image
        canvas.drawBitmap(baseImageBitmap, 0f, 0f, null)

        // 2. Gambar setiap Text Layer berserta seluruh Efek Teks pada koordinat piksel gambar asli
        //    menggunakan shared rendering function drawStyledTextOnNativeCanvas (WYSIWYG 100%).
        val screenDensity = android.content.res.Resources.getSystem().displayMetrics.density

        state.textLayers.forEach { layer ->
            val fontSizePx = layer.fontSizeSp * screenDensity
            com.mochits.text.drawStyledTextOnNativeCanvas(
                canvas = canvas,
                text = layer.text,
                xPx = layer.xInImagePx,
                yPx = layer.yInImagePx,
                fontSizePx = fontSizePx,
                style = layer.style
            )
        }

        return resultBitmap
    }

    /** Android 10+ (API 29): tulis via MediaStore tanpa permission khusus. */
    private fun saveViaMediaStore(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        subfolder: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): String? {
        val resolver = context.contentResolver
        val relativePath = buildString {
            append(Environment.DIRECTORY_PICTURES)
            if (subfolder.isNotEmpty()) append("/").append(subfolder)
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(
                MediaStore.Images.Media.MIME_TYPE,
                if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            )
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(format, quality.coerceIn(1, 100), out)) {
                throw IllegalStateException("Gagal mengompres bitmap")
            }
        } ?: return null

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    /** Android 9 ke bawah: folder app-specific (bebas izin runtime). */
    private fun saveToAppSpecificDir(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        subfolder: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): String {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        val targetDir = if (subfolder.isNotEmpty()) File(baseDir, subfolder) else baseDir
        if (!targetDir.exists()) targetDir.mkdirs()

        val outFile = File(targetDir, fileName)
        FileOutputStream(outFile).use { out ->
            if (!bitmap.compress(format, quality.coerceIn(1, 100), out)) {
                throw IllegalStateException("Gagal mengompres bitmap")
            }
        }
        return outFile.absolutePath
    }
}
