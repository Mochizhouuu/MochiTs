package com.mochits.app.project

/**
 * Perencana impor ZIP: menentukan folder virtual, judul proyek, urutan
 * natural, dan entri mana yang diabaikan. Murni (tanpa Android) agar mudah
 * diuji; pembacaan stream dilakukan oleh ProjectImporter.
 */
object ZipImportPlanner {

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")

    data class Entry(
        val path: String,
        val fileName: String,
        val title: String,
        val folderName: String?,
        val isBundle: Boolean,
        val isImage: Boolean
    )

    fun isImage(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    fun isBundle(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").equals("mts", ignoreCase = true)

    /** Bersihkan nama folder/judul: buang karakter path & kontrol. */
    fun sanitizeName(raw: String, fallback: String = "Impor"): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|]"""), " ")
            .filter { !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .take(60)
            .trim()
        return cleaned.ifBlank { fallback }
    }

    fun zipBaseName(zipDisplayName: String?): String {
        val raw = (zipDisplayName ?: "").trim()
        if (raw.isEmpty()) return "Impor ZIP"
        val withoutExt = raw.substringBeforeLast('.', raw)
        return sanitizeName(withoutExt, "Impor ZIP")
    }

    /**
     * Susun rencana impor dari daftar path di dalam ZIP.
     *
     * - Gambar di root ZIP -> folder bernama ZIP (atau folder tujuan).
     * - Gambar di subfolder -> folder bernama segmen pertama subfolder.
     * - `.mts` diperlakukan sebagai proyek yang bisa diedit.
     * - `__MACOSX`, file tersembunyi, folder, dan path traversal diabaikan.
     * - Urutan: natural (angka dibandingkan sebagai angka), case-insensitive.
     */
    fun plan(paths: List<String>, zipBase: String): List<Entry> {
        val rootFolder = sanitizeName(zipBase, "Impor ZIP")
        val result = LinkedHashMap<String, Entry>()
        for (rawPath in paths) {
            val path = rawPath.replace('\\', '/').trim()
            if (path.isEmpty() || path.endsWith("/")) continue
            val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.isEmpty()) continue
            if (segments.any { it == ".." }) continue
            if (segments.first().equals("__MACOSX", ignoreCase = true)) continue
            if (segments.any { it.startsWith(".") }) continue
            val fileName = segments.last()
            if (fileName.startsWith("~$")) continue
            val image = isImage(fileName)
            val bundle = isBundle(fileName)
            if (!image && !bundle) continue
            if (result.containsKey(path)) continue

            val folderName = if (segments.size <= 1) {
                rootFolder
            } else {
                sanitizeName(segments.first(), rootFolder)
            }
            val title = if (bundle) {
                sanitizeName(fileName.substringBeforeLast('.'), "Proyek")
            } else {
                sanitizeName(fileName.substringBeforeLast('.'), "Gambar")
            }
            result[path] = Entry(
                path = path,
                fileName = fileName,
                title = title,
                folderName = folderName,
                isBundle = bundle,
                isImage = image
            )
        }
        return result.values.sortedWith { a, b -> naturalCompare(a.path, b.path) }
    }

    /** Perbandingan natural: "2" sebelum "10", case-insensitive. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var iEnd = i
                while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
                val aNum = a.substring(i, iEnd).trimStart('0')
                val bNum = b.substring(j, jEnd).trimStart('0')
                val byLength = aNum.length.compareTo(bNum.length)
                if (byLength != 0) return byLength
                val byValue = aNum.compareTo(bNum)
                if (byValue != 0) return byValue
                i = iEnd
                j = jEnd
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
