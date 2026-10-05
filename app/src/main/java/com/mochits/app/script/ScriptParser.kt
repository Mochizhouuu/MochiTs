package com.mochits.app.script

/**
 * Parser naskah TL (F-script): teks polos -> halaman -> entri.
 *
 * Aturan (dari file TL nyata):
 * - Pemisah halaman = REGEX full-match (default `P\d+` untuk "P1".."P19";
 *   "===" literal juga bisa karena regex-nya cocok persis).
 * - Bila file PUNYA pemisah, semua baris sebelum pemisah pertama dibuang
 *   (kepala file berisi judul/nick/legenda yang kebetulan bersimbol).
 * - Baris kosong -> lewati.
 * - Baris diawali simbol dikenal (cocok awalan TERPANJANG) -> entri baru,
 *   KECUALI simbol bertipe sambung (mis. `//-`, `//[]`) yang menempel ke
 *   entri sebelumnya (gabung newline); tanpa entri sebelumnya -> entri baru.
 * - Baris lain -> sambungan entri sebelumnya; tanpa entri -> buang.
 */
object ScriptParser {

    data class ScriptEntry(val symbol: String, val text: String)
    data class ScriptPage(val entries: List<ScriptEntry>, val label: String = "")
    data class ScriptDoc(val pages: List<ScriptPage>) {
        val totalEntries: Int get() = pages.sumOf { it.entries.size }
    }

    fun parse(
        rawText: String,
        mappings: List<ScriptSymbolEntry>,
        separator: String
    ): ScriptDoc {
        val sepRegex = try {
            separator.takeIf { it.isNotBlank() }?.let { Regex(it) }
        } catch (_: Exception) {
            null
        }
        val ordered = mappings.map { it.symbol.trim() to it }
            .filter { (s, _) -> s.isNotEmpty() }
            .sortedByDescending { (s, _) -> s.length }

        var lines = rawText.split("\n")
        if (sepRegex != null) {
            val first = lines.indexOfFirst { it.trim().matches(sepRegex) }
            if (first >= 0) {
                lines = lines.drop(first)
            }
        }

        val pages = mutableListOf<Pair<String, MutableList<ScriptEntry>>>()
        var current = mutableListOf<ScriptEntry>()
        var currentLabel = ""
        var seenSep = false
        pages.add(currentLabel to current)

        fun pushPage(label: String) {
            current = mutableListOf()
            currentLabel = label
            pages.add(currentLabel to current)
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (sepRegex != null && line.matches(sepRegex)) {
                seenSep = true
                pushPage(line)
                continue
            }
            val hit = ordered.firstOrNull { (s, _) -> line.startsWith(s) }
            if (hit != null) {
                val (sym, entry) = hit
                val text = line.substring(sym.length).trimStart()
                if (entry.append && current.isNotEmpty()) {
                    val last = current.removeAt(current.size - 1)
                    current.add(last.copy(text = if (last.text.isEmpty()) text else last.text + "\n" + text))
                } else {
                    current.add(ScriptEntry(sym, text))
                }
            } else if (current.isNotEmpty()) {
                val last = current.removeAt(current.size - 1)
                current.add(last.copy(text = if (last.text.isEmpty()) line else last.text + "\n" + line))
            }
            // Tanpa entri sebelumnya: buang.
        }
        val nonEmpty = pages.filter { (_, entries) -> entries.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ScriptDoc(listOf(ScriptPage(emptyList(), "")))
        // Bila tak ada separator yang cocok, label halaman kosong semua.
        return ScriptDoc(nonEmpty.map { (label, entries) ->
            ScriptPage(entries, if (seenSep) label else "")
        })
    }
}
