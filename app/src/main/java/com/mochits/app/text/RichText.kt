package com.mochits.app.text

import com.mochits.app.model.TextSpanStyle

/**
 * Logika rich-text per kata (fase 1). Murni Kotlin, tanpa Android,
 * agar bisa di-unit-test cepat. Offset dalam satuan karakter.
 */

/** Satu potongan teks dengan gaya efektif (hasil split terhadap span). */
data class StyledRun(
    val text: String,
    /** Offset karakter awal run dalam teks penuh. */
    val start: Int,
    val fontName: String?,
    val fontStyle: String?,
    val fontSize: Float?,
    val textColor: Int?
)

/** Jepit + urutkan span ke dalam [0, textLength]; buang yang kosong. */
fun normalizeSpans(spans: List<TextSpanStyle>, textLength: Int): List<TextSpanStyle> {
    if (textLength <= 0) return emptyList()
    return spans.mapNotNull { s ->
        val a = s.start.coerceIn(0, textLength)
        val b = s.end.coerceIn(0, textLength)
        if (b <= a) null else s.copy(start = a, end = b)
    }.sortedWith(compareBy({ it.start }, { it.end }))
}

/**
 * Pecah teks menjadi run-run gaya-konstan. Batas span selalu jadi batas run
 * (aman walau batas jatuh di tengah kata).
 */
fun splitToRuns(text: String, spans: List<TextSpanStyle>): List<StyledRun> {
    if (text.isEmpty()) return emptyList()
    val norm = normalizeSpans(spans, text.length)
    if (norm.isEmpty()) {
        return listOf(StyledRun(text, 0, null, null, null, null))
    }
    // Kumpulkan semua titik batas yang relevan.
    val cuts = sortedSetOf(0, text.length)
    for (s in norm) {
        cuts.add(s.start)
        cuts.add(s.end)
    }
    val out = ArrayList<StyledRun>()
    val pts = cuts.toList()
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        if (b <= a) continue
        // Span terakhir yang mencakup [a, b): sorted + overlap -> yang
        // mulai paling akhir menang (perilaku patch-menimpa).
        var pick: TextSpanStyle? = null
        for (s in norm) {
            if (s.start <= a && s.end >= b) pick = s
        }
        out.add(
            StyledRun(
                text = text.substring(a, b),
                start = a,
                fontName = pick?.fontName,
                fontStyle = pick?.fontStyle,
                fontSize = pick?.fontSize,
                textColor = pick?.textColor
            )
        )
    }
    return out
}

/**
 * Tempel patch gaya ke rentang [start, end): potong span lama yang
 * bertabrakan, gabung yang bersebelahan dan identik. Field null pada patch
 * MEWARISI field span lama yang tertimpa (bukan menghapusnya), sehingga
 * warna + ukuran bisa ditumpuk bertahap.
 */
fun applySpanPatch(
    spans: List<TextSpanStyle>,
    textLength: Int,
    start: Int,
    end: Int,
    fontName: String? = null,
    fontStyle: String? = null,
    fontSize: Float? = null,
    textColor: Int? = null
): List<TextSpanStyle> {
    val a = start.coerceIn(0, textLength)
    val b = end.coerceIn(0, textLength)
    if (b <= a) return normalizeSpans(spans, textLength)
    val norm = normalizeSpans(spans, textLength)
    val kept = ArrayList<TextSpanStyle>()
    // Sisi kiri/kanan span lama yang tersisa (di luar [a, b)).
    for (s in norm) {
        if (s.end <= a || s.start >= b) {
            kept.add(s)
            continue
        }
        if (s.start < a) {
            kept.add(s.copy(end = a))
        }
        if (s.end > b) {
            kept.add(s.copy(start = b))
        }
    }
    // Isi [a, b) per sub-segmen: tiap bagian mewarisi span lama yang
    // menutupinya, ditimpa field patch yang non-null.
    val overlapping = norm.filter { it.start < b && it.end > a }
    val cuts = sortedSetOf(a, b)
    for (s in overlapping) {
        cuts.add(s.start.coerceIn(a, b))
        cuts.add(s.end.coerceIn(a, b))
    }
    val pts = cuts.toList()
    for (i in 0 until pts.size - 1) {
        val sa = pts[i]
        val sb = pts[i + 1]
        if (sb <= sa) continue
        val old = overlapping.firstOrNull { it.start <= sa && it.end >= sb }
        kept.add(
            TextSpanStyle(
                start = sa,
                end = sb,
                fontName = fontName ?: old?.fontName,
                fontStyle = fontStyle ?: old?.fontStyle,
                fontSize = fontSize ?: old?.fontSize,
                textColor = textColor ?: old?.textColor
            )
        )
    }
    // Gabung tetangga yang identik agar daftar tetap ringkas.
    val sorted = kept.sortedWith(compareBy({ it.start }, { it.end }))
    val merged = ArrayList<TextSpanStyle>()
    for (s in sorted) {
        val last = merged.lastOrNull()
        if (last != null && last.end == s.start &&
            last.fontName == s.fontName &&
            last.fontStyle == s.fontStyle &&
            last.fontSize == s.fontSize &&
            last.textColor == s.textColor
        ) {
            merged[merged.lastIndex] = last.copy(end = s.end)
        } else {
            merged.add(s)
        }
    }
    return merged
}

/** Hapus semua gaya pada rentang [start, end) (kembali ikut layer). */
fun clearSpanRange(
    spans: List<TextSpanStyle>,
    textLength: Int,
    start: Int,
    end: Int
): List<TextSpanStyle> {
    val a = start.coerceIn(0, textLength)
    val b = end.coerceIn(0, textLength)
    if (b <= a) return normalizeSpans(spans, textLength)
    val kept = ArrayList<TextSpanStyle>()
    for (s in normalizeSpans(spans, textLength)) {
        if (s.end <= a || s.start >= b) {
            kept.add(s)
            continue
        }
        if (s.start < a) kept.add(s.copy(end = a))
        if (s.end > b) kept.add(s.copy(start = b))
    }
    return kept
}

/**
 * Geser span mengikuti edit teks (per ketikan): cari prefix/suffix sama,
 * petakan offset lama ke baru. Teks diganti total -> span hangus.
 */
fun shiftSpansOnEdit(
    oldText: String,
    oldSpans: List<TextSpanStyle>,
    newText: String
): List<TextSpanStyle> {
    if (oldSpans.isEmpty() || oldText == newText) {
        return normalizeSpans(oldSpans, newText.length)
    }
    // Prefix sama terpanjang.
    var pre = 0
    val preMax = minOf(oldText.length, newText.length)
    while (pre < preMax && oldText[pre] == newText[pre]) pre++
    // Suffix sama terpanjang (tak overlap prefix).
    var sufOld = oldText.length
    var sufNew = newText.length
    while (sufOld > pre && sufNew > pre && oldText[sufOld - 1] == newText[sufNew - 1]) {
        sufOld--
        sufNew--
    }
    // Daerah tengah old [pre, sufOld) diganti new [pre, sufNew).
    val delta = (sufNew - pre) - (sufOld - pre)
    val out = ArrayList<TextSpanStyle>()
    for (s in normalizeSpans(oldSpans, oldText.length)) {
        when {
            // Seluruhnya sebelum edit: tetap.
            s.end <= pre -> out.add(s)
            // Seluruhnya sesudah edit: geser.
            s.start >= sufOld -> out.add(s.copy(start = s.start + delta, end = s.end + delta))
            // Menabrak daerah edit.
            else -> {
                // Pertahankan bagian kiri yang utuh.
                if (s.start < pre) {
                    out.add(s.copy(end = pre))
                }
                // Bagian kanan yang utuh digeser ke posisi baru.
                if (s.end > sufOld) {
                    val rightLen = s.end - sufOld
                    val newStart = sufNew
                    out.add(s.copy(start = newStart, end = newStart + rightLen))
                }
                // Bagian tengah yang diketik ulang: hangus (ikut teks baru).
            }
        }
    }
    return normalizeSpans(out, newText.length)
}

/**
 * Jepit seleksi ke batas kata ([start, end) menutupi kata utuh).
 * null bila kosong/kosong-kosong (tak ada kata tersentuh).
 */
fun snapToWords(text: String, selStart: Int, selEnd: Int): IntRange? {
    if (text.isEmpty()) return null
    var a = selStart.coerceIn(0, text.length)
    var b = selEnd.coerceIn(0, text.length)
    if (a > b) {
        val t = a
        a = b
        b = t
    }
    if (a == b) return null
    // Perluas kiri ke awal kata.
    while (a > 0 && !text[a - 1].isWhitespace()) a--
    // Perluas kanan ke akhir kata.
    while (b < text.length && !text[b].isWhitespace()) b++
    if (b <= a) return null
    // Abaikan seleksi yang hanya whitespace.
    if (text.substring(a, b).isBlank()) return null
    return a until b
}
