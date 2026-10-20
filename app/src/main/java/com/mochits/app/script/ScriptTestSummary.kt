package com.mochits.app.script

import android.content.Context
import android.net.Uri

/**
 * Ringkasan hasil parse file script untuk alat "Tes Script" di Pengaturan.
 * Membantu melihat apakah simbol (mis. `//-`) dikenali dan berapa entri
 * yang terbentuk sebelum file dimuat ke editor.
 */
data class ScriptTestSummary(
    val fileName: String?,
    val pages: Int,
    val entries: Int,
    val linkedEntries: Int,
    val symbolCounts: List<Pair<String, Int>>,
    val warnings: List<String>
) {
    companion object {
        fun build(
            context: Context,
            uri: Uri,
            mappings: List<ScriptSymbolEntry>,
            separator: String
        ): ScriptTestSummary? {
            val read = ScriptFileReader.read(context, uri) ?: return null
            return fromText(read.text, read.fileName, mappings, separator)
        }

        fun fromText(
            text: String,
            fileName: String?,
            mappings: List<ScriptSymbolEntry>,
            separator: String
        ): ScriptTestSummary {
            val doc = ScriptParser.parse(text, mappings, separator)
            val entries = doc.pages.flatMap { it.entries }
            val counts = entries.groupingBy { it.symbol }.eachCount()
                .toList()
                .sortedByDescending { it.second }
            val symbols = mappings.map { it.symbol }.toSet()
            val normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            val warnings = buildList {
                if (doc.totalEntries == 0) {
                    add("Tidak ada entri bersimbol yang terbaca.")
                }
                if (text.isBlank()) add("File kosong.")
                if ("//-" !in symbols && Regex("(?m)^\\s*//-").containsMatchIn(normalized)) {
                    add("Baris //- ditemukan, tetapi simbol //- belum ada di mapping.")
                }
                if ("//[]" !in symbols && Regex("(?m)^\\s*//\\[\\]").containsMatchIn(normalized)) {
                    add("Baris //[] ditemukan, tetapi simbol //[] belum ada di mapping.")
                }
                val known = mappings.map { it.symbol }.filter { it.isNotEmpty() }
                    .sortedByDescending { it.length }
                val unknownSlashLines = normalized.lineSequence()
                    .map { it.trim() }
                    .count { line ->
                        line.startsWith("//") && known.none { line.startsWith(it) }
                    }
                if (unknownSlashLines > 0) {
                    add("$unknownSlashLines baris berawalan // tidak cocok dengan simbol mana pun; akan dianggap lanjutan entri sebelumnya.")
                }
            }
            return ScriptTestSummary(
                fileName = fileName,
                pages = doc.pages.size,
                entries = doc.totalEntries,
                linkedEntries = entries.count { it.linkedParent != null },
                symbolCounts = counts,
                warnings = warnings
            )
        }
    }
}
