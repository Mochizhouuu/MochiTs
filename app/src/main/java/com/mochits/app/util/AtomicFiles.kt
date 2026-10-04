package com.mochits.app.util

import android.graphics.Bitmap
import java.io.File
import java.nio.file.StandardCopyOption

/**
 * Pengganti file se-atomik mungkin (fondasi B3): pemanggil menulis ke file
 * `.tmp` dulu, lalu fungsi ini memindahkannya TANPA menghapus target lebih
 * dulu. `renameTo` POSIX menimpa secara atomic bila satu filesystem;
 * fallback `copyTo` + hapus bila pindah atomic tidak didukung.
 *
 * @return true bila target valid setelah panggilan.
 */
fun atomicReplace(tmpFile: File, targetFile: File): Boolean {
    if (!tmpFile.exists() || tmpFile.length() <= 0L) return false
    return try {
        targetFile.parentFile?.mkdirs()
        java.nio.file.Files.move(
            tmpFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
        true
    } catch (_: Exception) {
        try {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
            targetFile.exists() && targetFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Thumbnail JPEG kecil (B8) terpisah dari base full-res agar kartu Home
 * tidak men-decode puluhan megapiksel. @return true bila tertulis valid.
 */
fun writeThumbnail(src: Bitmap, thumbFile: File, maxDim: Int = 512): Boolean {
    if (src.isRecycled || src.width <= 0 || src.height <= 0) return false
    return try {
        val scale = minOf(1f, maxDim / maxOf(src.width, src.height).toFloat())
        val tw = maxOf(1, (src.width * scale).toInt())
        val th = maxOf(1, (src.height * scale).toInt())
        val small = if (tw == src.width && th == src.height) src
        else Bitmap.createScaledBitmap(src, tw, th, true)
        try {
            val tmp = File(thumbFile.parentFile, "${thumbFile.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                if (!small.compress(Bitmap.CompressFormat.JPEG, 85, out)) return false
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
            atomicReplace(tmp, thumbFile)
        } finally {
            if (small !== src) {
                try { small.recycle() } catch (_: Exception) {}
            }
        }
    } catch (_: Throwable) {
        false
    }
}
