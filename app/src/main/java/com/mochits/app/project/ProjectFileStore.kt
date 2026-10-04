package com.mochits.app.project

import android.content.Context
import android.graphics.Bitmap
import com.mochits.app.util.atomicReplace
import java.io.File
import java.io.FileOutputStream

/**
 * Satu-satunya pemilik path & tulis file proyek (S2/B28): tidak ada lagi
 * string "projects/..." tersebar. Semua tulis bitmap lewat
 * [writeBitmapAtomic] (tmp + fsync + pindah atomic).
 */
class ProjectFileStore(private val context: Context) {

    // ---------- Proyek ----------

    fun projectDir(id: String): File =
        File(context.filesDir, "projects/$id").apply { mkdirs() }

    fun baseImage(id: String): File =
        File(projectDir(id), "base_image.png")

    fun thumbnail(id: String): File =
        File(projectDir(id), "thumbnail.jpg")

    fun layersDir(id: String): File =
        File(projectDir(id), "layers").apply { mkdirs() }

    fun layerFile(id: String, layerId: String): File =
        File(layersDir(id), "layer_$layerId.png")

    fun historyDir(id: String): File =
        File(projectDir(id), "history").apply { mkdirs() }

    fun selectedLayerFile(id: String): File =
        File(projectDir(id), "selected_layer.txt")

    // ---------- Bersama ----------

    fun fontsDir(): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    fun customFontsDir(): File =
        File(context.filesDir, "custom_fonts").apply { mkdirs() }

    fun modelsDir(): File =
        File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Tulis bitmap secara atomic: kompres ke .tmp + fsync + pindah tanpa
     * hapus dulu. @return true bila target valid.
     */
    fun writeBitmapAtomic(
        target: File,
        bmp: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Boolean {
        if (bmp.isRecycled) return false
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                if (!bmp.compress(format, quality, out)) return false
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
            atomicReplace(tmp, target)
        } catch (t: Throwable) {
            com.mochits.app.util.Logger.e("Gagal menulis ${target.name}: ${t.message}", t)
            false
        }
    }
}
