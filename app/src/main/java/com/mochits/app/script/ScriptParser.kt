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
 *   KECUALI simbol bertipe append yang menggabung teks ke entri sebelumnya.
 * - Simbol bertipe linked (mis. `//-`, `//[]`) tetap entri BARU, ditandai
 *   terhubung ke balon/kotak berikutnya (induk `-` / `[]`), bukan digabung.
 * - Baris lain -> sambungan entri sebelumnya; tanpa entri -> buang.
 *   Spasi indentasi sejajar (sampai kolom teks simbol) dibuang; indentasi
 *   yang lebih dalam dipertahankan.
 */
object ScriptParser {

    data class ScriptEntry(
        val symbol: String,
        val text: String,
        /** Simbol induk untuk entri terhubung (mis. `//-` -> `-`). */
        val linkedParent: String? = null
    )
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

        var lines = rawText
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\t", "    ")
            .split("\n")
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
        /** Kolom awal teks pada baris simbol terakhir (acuan indentasi). */
        var textColumn = 0
        pages.add(currentLabel to current)

        fun pushPage(label: String) {
            current = mutableListOf()
            currentLabel = label
            textColumn = 0
            pages.add(currentLabel to current)
        }

        fun mergeLast(text: String) {
            if (current.isEmpty()) return
            val last = current.removeAt(current.size - 1)
            current.add(last.copy(text = if (last.text.isEmpty()) text else last.text + "\n" + text))
        }

        fun textStartColumn(rawLine: String, sym: String): Int {
            val symStart = rawLine.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
            var i = symStart + sym.length
            while (i < rawLine.length && rawLine[i] == ' ') i++
            return i
        }

        fun linkedParentFor(sym: String, entries: List<ScriptEntry>): String? {
            val stripped = if (sym.startsWith("//")) sym.substring(2) else null
            if (!stripped.isNullOrEmpty()) return stripped
            val prev = entries.lastOrNull() ?: return null
            return prev.linkedParent ?: prev.symbol
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
                    mergeLast(text)
                } else {
                    val parent = if (entry.linked) linkedParentFor(sym, current) else null
                    current.add(ScriptEntry(sym, text, parent))
                    textColumn = textStartColumn(rawLine, sym)
                }
            } else if (current.isNotEmpty()) {
                val leading = rawLine.indexOfFirst { it != ' ' }
                    .let { if (it < 0) rawLine.length else it }
                val continuation = rawLine.substring(minOf(leading, textColumn)).trimEnd()
                if (continuation.isNotEmpty()) mergeLast(continuation)
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
