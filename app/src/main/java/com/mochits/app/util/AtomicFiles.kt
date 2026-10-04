package com.mochits.app.util

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
