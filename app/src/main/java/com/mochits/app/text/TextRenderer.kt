package com.mochits.app.text
import com.mochits.app.util.Logger

import java.io.File
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import com.mochits.app.model.Layer
import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextSpanStyle
import com.mochits.app.model.TextStyleConfig
import kotlin.math.ceil
import kotlin.math.sqrt

data class RenderedRun(
    val text: String,
    val width: Float,
    /** Offset dari kiri layer (konvensi sama dengan RenderedLine.xOffset). */
    val xOffset: Float,
    val fontSize: Float,
    val fontName: String,
    val fontStyle: String,
    /** null = ikut warna layer. */
    val textColor: Int? = null
)

data class RenderedLine(
    val text: String,
    val width: Float,
    val xOffset: Float,
    /** Run-run gaya pembentuk baris (fase 1 rich text). */
    val runs: List<RenderedRun> = emptyList(),
    /** Tinggi baris efektif = maks gaya di baris ini (0 = warisan lineHeight global). */
    val height: Float = 0f
)

data class TextLayoutResult(
    val lines: List<RenderedLine>,
    val containerWidth: Float,
    val containerHeight: Float,
    val lineHeight: Float,
    val minX: Float = 0f,
    val maxX: Float = containerWidth,
    val topOffset: Float = 0f
)

private data class TextLayoutKey(
    val text: String,
    val textSize: Float,
    val typeface: Typeface?,
    val shape: TextContainerShape,
    val boxWidthHalfPx: Int?,
    val boxHeightHalfPx: Int?,
    val alignment: TextAlignment,
    val baseStyle: TextStyleConfig,
    val spans: List<com.mochits.app.model.TextSpanStyle>
)

/** Token terukur bergaya untuk layout rich-text (internal). */
private data class StyledTok(
    val t: String,
    val w: Float,
    val fs: Float,
    val fn: String,
    val fst: String,
    val tc: Int?
)

private object SyllableSplitter {
    private val indonesianDigraphs = setOf("ng", "ny", "sy", "kh")
    private val englishDigraphs = setOf("th", "sh", "ch", "ph", "wh", "ck", "gh", "ng")

    fun getSyllables(word: String): List<String> {
        val trimmed = word.trim()
        if (trimmed.length <= 3) return listOf(trimmed)

        val punctuationIndex = trimmed.indexOfLast { it.isLetterOrDigit() } + 1
        val cleanWord = if (punctuationIndex > 0) trimmed.substring(0, punctuationIndex) else trimmed
        val trailingPunct = if (punctuationIndex > 0) trimmed.substring(punctuationIndex) else ""

        if (cleanWord.length <= 3) {
            return listOf(cleanWord + trailingPunct)
        }

        val breakpoints = findBreakpoints(cleanWord)
        if (breakpoints.isEmpty()) return listOf(cleanWord + trailingPunct)

        val syllables = mutableListOf<String>()
        var lastIdx = 0
        for (bp in breakpoints) {
            syllables.add(cleanWord.substring(lastIdx, bp))
            lastIdx = bp
        }
        if (lastIdx < cleanWord.length) {
            syllables.add(cleanWord.substring(lastIdx))
        }

        if (trailingPunct.isNotEmpty() && syllables.isNotEmpty()) {
            val lastIdxSyllable = syllables.size - 1
            syllables[lastIdxSyllable] = syllables[lastIdxSyllable] + trailingPunct
        }

        return syllables
    }

    private fun isVowel(c: Char): Boolean {
        return c.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u', 'y')
    }

    private fun findBreakpoints(word: String): List<Int> {
        val len = word.length
        val breaks = mutableListOf<Int>()
        var i = 0

        while (i < len - 1) {
            val c1 = word[i]
            val c2 = word[i + 1]

            if (isVowel(c1)) {
                var cCount = 0
                var j = i + 1
                while (j < len && !isVowel(word[j])) {
                    cCount++
                    j++
                }

                if (j < len) {
                    if (cCount == 0) {
                        val pair = "${c1}${c2}".lowercase()
                        if (pair !in setOf("ai", "au", "oi", "ei", "ou")) {
                            if (i + 1 >= 2 && len - (i + 1) >= 2) {
                                breaks.add(i + 1)
                            }
                        }
                    } else if (cCount == 1) {
                        if (i + 1 >= 2 && len - (i + 1) >= 2) {
                            breaks.add(i + 1)
                        }
                    } else if (cCount == 2) {
                        val pair = word.substring(i + 1, (i + 3).coerceAtMost(len)).lowercase()
                        if (pair in indonesianDigraphs || pair in englishDigraphs) {
                            if (i + 1 >= 2 && len - (i + 1) >= 2) {
                                breaks.add(i + 1)
                            }
                        } else {
                            if (i + 2 >= 2 && len - (i + 2) >= 2) {
                                breaks.add(i + 2)
                            }
                        }
                    } else if (cCount >= 3) {
                        val pair2 = if (i + 3 <= len) word.substring(i + 2, (i + 4).coerceAtMost(len)).lowercase() else ""
                        if (pair2 in indonesianDigraphs || pair2 in englishDigraphs) {
                            if (i + 2 >= 2 && len - (i + 2) >= 2) {
                                breaks.add(i + 2)
                            }
                        } else {
                            if (i + 3 >= 2 && len - (i + 3) >= 2) {
                                breaks.add(i + 3)
                            }
                        }
                    }
                    i = j - 1
                }
            }
            i++
        }
        return breaks.distinct().sorted()
    }
}

class TextRenderer(private val context: Context) {

    /**
     * Resolver displayName font -> filePath aktual. Wajib untuk font impor
     * karena filenya tersimpan sebagai UUID.ttf sehingga tidak bisa
     * ditemukan lewat pencocokan nama file.
     */
    var customFontPathResolver: ((String) -> String?)? = null

    companion object {
        private val typefaceCache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

        private val layoutCache = object : java.util.LinkedHashMap<TextLayoutKey, TextLayoutResult>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextLayoutKey, TextLayoutResult>?): Boolean {
                return size > 256
            }
        }

        private fun tokenizeParagraph(para: String): List<String> {
            if (para.isEmpty()) return emptyList()
            val tokens = ArrayList<String>()
            var start = 0
            val len = para.length
            var inWhitespace = para[0].isWhitespace()
            for (i in 1 until len) {
                val currentIsWhitespace = para[i].isWhitespace()
                if (currentIsWhitespace != inWhitespace) {
                    tokens.add(para.substring(start, i))
                    start = i
                    inWhitespace = currentIsWhitespace
                }
            }
            if (start < len) {
                tokens.add(para.substring(start, len))
            }
            return tokens
        }
    }

    // Reusable paint instances to avoid frequent GC allocations during rapid drag gestures
    private val reusableLayoutPaint = Paint()
    private val reusableRenderPaint = Paint()
    private val reusableGlowPaint = Paint()
    private val reusableShadowPaint = Paint()
    private val reusableStrokePaint = Paint()
    /** Khusus ukur token rich-text (paint pemanggil tak boleh diubah layout). */
    private val reusableMeasurePaint = Paint()
    /** Fill per-run rich-text (dikonfigurasi ulang tiap run). */
    private val reusableRunPaint = Paint()

    /** Cache tinggi baris per gaya (fs|fn|fst) — hindari re-measure font metrics. */
    private val styleHeightCache = object : LinkedHashMap<Triple<Float, String, String>, Float>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Float, String, String>, Float>?): Boolean = size > 128
    }

    /** Tinggi baris satu gaya = fontMetrics.bottom - top (konsisten dgn lineHeight global). */
    private fun styleHeight(fs: Float, fn: String, fst: String): Float {
        val key = Triple(fs, fn.trim().lowercase(), fst.trim().lowercase())
        val cached = synchronized(styleHeightCache) { styleHeightCache[key] }
        if (cached != null) return cached
        val p = reusableMeasurePaint.apply {
            reset()
            isAntiAlias = true
            textSize = fs
            typeface = getTypeface(fn, fst)
        }
        val fm = p.fontMetrics
        val h = (fm.bottom - fm.top).coerceAtLeast(1f)
        synchronized(styleHeightCache) { styleHeightCache[key] = h }
        return h
    }

    /** Tinggi blok teks = jumlah tinggi baris (fallback lineHeight global bila kosong). */
    private fun blockHeight(lines: List<RenderedLine>, globalLineHeight: Float): Float {
        var sum = 0f
        for (l in lines) sum += if (l.height > 0f) l.height else globalLineHeight
        return sum
    }

    /**
     * Complete rewrite of text layout and reflow logic from scratch.
     * Supports both BOX and OVAL container shapes with greedy word-wrapping,
     * conditional hyphenation, alignment, and precise visual bounds.
     */
    fun layoutText(
        text: String,
        paint: Paint,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null,
        boxHeight: Float? = null,
        alignment: TextAlignment = TextAlignment.CENTER,
        baseStyle: TextStyleConfig = TextStyleConfig(),
        spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    ): TextLayoutResult {
        val key = TextLayoutKey(
            text = text,
            textSize = paint.textSize,
            typeface = paint.typeface,
            shape = shape,
            boxWidthHalfPx = boxWidth?.let { kotlin.math.round(it * 2f).toInt() },
            boxHeightHalfPx = boxHeight?.let { kotlin.math.round(it * 2f).toInt() },
            alignment = alignment,
            baseStyle = baseStyle,
            spans = spans
        )

        synchronized(layoutCache) {
            layoutCache[key]?.let { return it }
        }

        val result = computeLayoutText(text, paint, shape, boxWidth, boxHeight, alignment, baseStyle, spans)

        synchronized(layoutCache) {
            layoutCache[key] = result
        }

        return result
    }

    private fun computeLayoutText(
        text: String,
        paint: Paint,
        shape: TextContainerShape,
        boxWidth: Float?,
        boxHeight: Float?,
        alignment: TextAlignment,
        baseStyle: TextStyleConfig,
        spans: List<TextSpanStyle>
    ): TextLayoutResult {
        val fontMetrics = paint.fontMetrics
        var lineHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(10f)

        // Ukur token dengan paint khusus (paint pemanggil milik draw).
        fun measureTok(tokText: String, fs: Float, fn: String, fst: String): Float {
            if (tokText.isEmpty()) return 0f
            reusableMeasurePaint.apply {
                reset()
                isAntiAlias = true
                textSize = fs
                typeface = getTypeface(fn, fst)
            }
            return reusableMeasurePaint.measureText(tokText)
        }
        // Run gaya untuk satu paragraf (offset global -> lokal).
        fun runsForPara(para: String, paraStart: Int): List<StyledRun> {
            if (para.isEmpty()) return emptyList()
            val clipped = spans.mapNotNull { s ->
                val a = maxOf(s.start, paraStart) - paraStart
                val b = minOf(s.end, paraStart + para.length) - paraStart
                if (b <= a) null else s.copy(start = a, end = b)
            }
            return splitToRuns(para, clipped)
        }
        // Tokenize + ukur tiap token dengan gayanya sendiri.
        fun styledTokensOf(para: String, paraStart: Int): MutableList<StyledTok> {
            val out = ArrayList<StyledTok>()
            if (para.isEmpty()) {
                out.add(StyledTok("", 0f, baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle, null))
                return out
            }
            for (run in runsForPara(para, paraStart)) {
                val fs = run.fontSize ?: baseStyle.fontSize
                val fn = run.fontName ?: baseStyle.fontName
                val fst = run.fontStyle ?: baseStyle.fontStyle
                for (tok in tokenizeParagraph(run.text)) {
                    out.add(StyledTok(tok, measureTok(tok, fs, fn, fst), fs, fn, fst, run.textColor))
                }
            }
            return out
        }
        // Tinggi baris = maksimum semua gaya yang dipakai.
        run {
            val seen = HashSet<Triple<Float, String, String>>()
            for (run in splitToRuns(text, spans)) {
                val key = Triple(
                    run.fontSize ?: baseStyle.fontSize,
                    run.fontName ?: baseStyle.fontName,
                    run.fontStyle ?: baseStyle.fontStyle
                )
                if (seen.add(key)) {
                    reusableMeasurePaint.apply {
                        reset()
                        isAntiAlias = true
                        textSize = key.first
                        typeface = getTypeface(key.second, key.third)
                    }
                    val fm = reusableMeasurePaint.fontMetrics
                    lineHeight = maxOf(lineHeight, (fm.bottom - fm.top).coerceAtLeast(10f))
                }
            }
        }

        // Fast path for unconstrained BOX text
        if (boxWidth == null && shape == TextContainerShape.BOX) {
            val rawParas = if (text.isEmpty()) listOf("" to 0) else run {
                var off = 0
                text.split("\n").map { p ->
                    val r = p to off
                    off += p.length + 1
                    r
                }
            }
            // Pass 1: lebar maksimum.
            var maxW = 20f
            val paraToks = rawParas.map { (para, paraStart) ->
                val toks = styledTokensOf(para, paraStart)
                val w = toks.sumOf { it.w.toDouble() }.toFloat()
                if (w > maxW) maxW = w
                toks
            }
            // Pass 2: susun runs setelah maxW diketahui.
            val finalLines = ArrayList<RenderedLine>(rawParas.size)
            for (i in rawParas.indices) {
                val (para, _) = rawParas[i]
                val toks = paraToks[i]
                val w = toks.sumOf { it.w.toDouble() }.toFloat()
                val xOff = when (alignment) {
                    TextAlignment.LEFT -> 0f
                    TextAlignment.CENTER -> (maxW - w) / 2f
                    TextAlignment.RIGHT -> maxW - w
                }
                var cum = 0f
                val runs = toks.map { t ->
                    val r = RenderedRun(t.t, t.w, xOff + cum, t.fs, t.fn, t.fst, t.tc)
                    cum += t.w
                    r
                }
                var paraH = 0f
                for (t in toks) {
                    val h = styleHeight(t.fs, t.fn, t.fst)
                    if (h > paraH) paraH = h
                }
                if (paraH <= 0f) paraH = styleHeight(baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle)
                finalLines.add(RenderedLine(text = para, width = w, xOffset = xOff, runs = runs, height = paraH))
            }
            val totalH = blockHeight(finalLines, lineHeight).coerceAtLeast(lineHeight)
            val finalH = boxHeight ?: totalH
            val topOffset = if (boxHeight != null) ((finalH - totalH) / 2f).coerceAtLeast(0f) else 0f
            val nonEmptyLines = finalLines.filter { it.text.isNotEmpty() }
            val minX = if (nonEmptyLines.isEmpty()) 0f else (nonEmptyLines.minOfOrNull { it.xOffset } ?: 0f)
            val maxX = if (nonEmptyLines.isEmpty()) maxW else (nonEmptyLines.maxOfOrNull { it.xOffset + it.width }?.coerceAtLeast(minX + 20f) ?: maxW)
            return TextLayoutResult(finalLines, maxW, finalH, lineHeight, minX, maxX, topOffset)
        }

        // Determine container dimensions
        val targetW = (boxWidth ?: 200f).coerceAtLeast(20f)

        // Fast non-recursive estimation of container height for OVAL
        val effectiveBoxHeight: Float? = if (shape == TextContainerShape.OVAL) {
            var estimatedLinesCount = 0
            var estOff = 0
            val estParas = if (text.isEmpty()) listOf("" to 0) else text.split("\n").map { p ->
                val r = p to estOff
                estOff += p.length + 1
                r
            }
            for ((p, pStart) in estParas) {
                val w = if (p.isEmpty()) {
                    measureTok(" ", baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle)
                } else {
                    styledTokensOf(p, pStart).sumOf { it.w.toDouble() }.toFloat()
                }
                estimatedLinesCount += (w / (targetW * 0.7f)).toInt() + 1
            }
            if (boxHeight == null) {
                (estimatedLinesCount.coerceAtLeast(1) * lineHeight * 1.5f).coerceAtLeast(40f)
            } else {
                val minReqH = (estimatedLinesCount.coerceAtLeast(1) * lineHeight * 1.35f).coerceAtLeast(30f)
                maxOf(boxHeight, minReqH)
            }
        } else {
            boxHeight
        }

        val ovalA = targetW / 2f
        val ovalB = (effectiveBoxHeight ?: 20f).coerceAtLeast(20f) / 2f

        // Helper function to build lines given a vertical top offset
        fun buildLinesForOffset(computedTopOffset: Float): List<RenderedLine> {
            // Lebar elips di ketinggian tertentu — murni, tanpa cache index.
            fun availWidthAt(yMid: Float): Float {
                if (shape == TextContainerShape.BOX || effectiveBoxHeight == null) return targetW
                val yRelativeToCenter = yMid - ovalB
                val ratio = (yRelativeToCenter / ovalB).coerceIn(-0.98f, 0.98f)
                // Lantai kecil: teks harus tetap di dalam elips walau di
                // garis atas/bawah yang sempit (bukan minLineWidth).
                return (2f * ovalA * sqrt(1f - ratio * ratio)).coerceAtLeast(4f)
            }

            val lines = mutableListOf<RenderedLine>()
            /** Tinggi kumulatif baris yang sudah commit (= top baris berikutnya). */
            var cumulativeTop = 0f
            /** Tinggi baris berjalan (belum commit) = maks gaya token di dalamnya. */
            var currentLineH = 0f
            fun currentAvailWidth(extraH: Float = 0f): Float {
                if (shape == TextContainerShape.BOX || effectiveBoxHeight == null) return targetW
                val h = maxOf(currentLineH, extraH)
                return availWidthAt(computedTopOffset + cumulativeTop + h / 2f)
            }

            var paraOff = 0
            val paragraphs = if (text.isEmpty()) {
                listOf("" to 0)
            } else {
                text.split("\n").map { p ->
                    val r = p to paraOff
                    paraOff += p.length + 1
                    r
                }
            }

            // Commit baris dari token bergaya (potong spasi trailing).
            fun commitToks(toks: List<StyledTok>): RenderedLine {
                var end = toks.size
                var w = toks.sumOf { it.w.toDouble() }.toFloat()
                while (end > 0 && toks[end - 1].t.trim().isEmpty()) {
                    end--
                    w -= toks[end].w
                }
                val kept = toks.subList(0, end)
                var lh = 0f
                for (t in kept) {
                    val h = styleHeight(t.fs, t.fn, t.fst)
                    if (h > lh) lh = h
                }
                if (lh <= 0f) lh = styleHeight(baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle)
                val availW = if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
                    availWidthAt(computedTopOffset + cumulativeTop + lh / 2f)
                } else targetW
                val sliceStart = if (shape == TextContainerShape.OVAL) (targetW - availW) / 2f else 0f
                val xOff = when (alignment) {
                    TextAlignment.LEFT -> sliceStart
                    TextAlignment.CENTER -> sliceStart + (availW - w) / 2f
                    TextAlignment.RIGHT -> sliceStart + availW - w
                }
                val lineStr = kept.joinToString("") { it.t }
                var cum = 0f
                val runs = kept.map { t ->
                    val r = RenderedRun(t.t, t.w, xOff + cum, t.fs, t.fn, t.fst, t.tc)
                    cum += t.w
                    r
                }
                cumulativeTop += lh
                currentLineH = 0f
                return RenderedLine(lineStr, w, xOff, runs, lh)
            }
            // Satu baris dari satu token (hasil hyphenation/penggal).
            fun commitSingle(tokText: String, fs: Float, fn: String, fst: String, tc: Int?): RenderedLine {
                val w = measureTok(tokText, fs, fn, fst)
                return commitToks(listOf(StyledTok(tokText, w, fs, fn, fst, tc)))
            }

            for ((para, paraStart) in paragraphs) {
                if (para.isEmpty()) {
                    val lh = styleHeight(baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle)
                    val availW = currentAvailWidth(lh)
                    val sliceStart = if (shape == TextContainerShape.OVAL) (targetW - availW) / 2f else 0f
                    val xOff = when (alignment) {
                        TextAlignment.LEFT -> sliceStart
                        TextAlignment.CENTER -> sliceStart + availW / 2f
                        TextAlignment.RIGHT -> sliceStart + availW
                    }
                    lines.add(
                        RenderedLine(
                            "", 0f, xOff,
                            listOf(RenderedRun("", 0f, xOff, baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle, null)),
                            lh
                        )
                    )
                    cumulativeTop += lh
                    continue
                }

                val tokens = styledTokensOf(para, paraStart).toMutableList()
                val currentLineToks = ArrayList<StyledTok>()
                var currentLineW = 0f
                var tokenIdx = 0

                while (tokenIdx < tokens.size) {
                    val token = tokens[tokenIdx]
                    val tokenH = styleHeight(token.fs, token.fn, token.fst)
                    val availW = currentAvailWidth(tokenH)

                    if (currentLineToks.isEmpty() && token.t.trim().isEmpty()) {
                        tokenIdx++
                        continue
                    }

                    val candidateW = currentLineW + token.w

                    if (candidateW <= availW) {
                        currentLineToks.add(token)
                        currentLineW = candidateW
                        if (tokenH > currentLineH) currentLineH = tokenH
                        tokenIdx++
                    } else {
                        if (currentLineToks.isNotEmpty()) {
                            lines.add(commitToks(currentLineToks))
                            currentLineToks.clear()
                            currentLineW = 0f
                        } else {
                            val cleanToken = token.t.trim()
                            if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
                                val tokenW = measureTok(cleanToken, token.fs, token.fn, token.fst)
                                val yRelativeToTop = computedTopOffset + cumulativeTop + maxOf(currentLineH, tokenH) / 2f
                                if (yRelativeToTop < ovalB && tokenW <= targetW * 0.95f && availW < tokenW) {
                                    cumulativeTop += tokenH
                                    currentLineH = 0f
                                    continue
                                }
                            }
                            val syllables = SyllableSplitter.getSyllables(cleanToken)

                            var bestSyllableCount = 0
                            for (k in (syllables.size - 1) downTo 1) {
                                val part1Candidate = syllables.take(k).joinToString("") + "-"
                                if (measureTok(part1Candidate, token.fs, token.fn, token.fst) <= availW) {
                                    bestSyllableCount = k
                                    break
                                }
                            }

                            if (bestSyllableCount > 0) {
                                val part1 = syllables.take(bestSyllableCount).joinToString("") + "-"
                                val remaining = syllables.drop(bestSyllableCount).joinToString("")
                                lines.add(commitSingle(part1, token.fs, token.fn, token.fst, token.tc))
                                tokens[tokenIdx] = StyledTok(
                                    remaining,
                                    measureTok(remaining, token.fs, token.fn, token.fst),
                                    token.fs, token.fn, token.fst, token.tc
                                )
                            } else {
                                var splitIdx = 0
                                for (c in 2..(cleanToken.length - 2)) {
                                    val sub = cleanToken.substring(0, c) + "-"
                                    if (measureTok(sub, token.fs, token.fn, token.fst) <= availW) {
                                        splitIdx = c
                                    } else {
                                        break
                                    }
                                }

                                if (splitIdx >= 2) {
                                    val part1 = cleanToken.substring(0, splitIdx) + "-"
                                    val remaining = cleanToken.substring(splitIdx)
                                    lines.add(commitSingle(part1, token.fs, token.fn, token.fst, token.tc))
                                    tokens[tokenIdx] = StyledTok(
                                        remaining,
                                        measureTok(remaining, token.fs, token.fn, token.fst),
                                        token.fs, token.fn, token.fst, token.tc
                                    )
                                } else {
                                    val part1 = cleanToken.substring(0, 1)
                                    val remaining = cleanToken.substring(1)
                                    lines.add(commitSingle(part1, token.fs, token.fn, token.fst, token.tc))
                                    if (remaining.isNotEmpty()) {
                                        tokens[tokenIdx] = StyledTok(
                                            remaining,
                                            measureTok(remaining, token.fs, token.fn, token.fst),
                                            token.fs, token.fn, token.fst, token.tc
                                        )
                                    } else {
                                        tokenIdx++
                                    }
                                }
                            }
                        }
                    }
                }

                if (currentLineToks.isNotEmpty()) {
                    lines.add(commitToks(currentLineToks))
                }
            }

            return lines
        }

        // Pass 1: Build lines with estimated topOffset to discover lines count and center text vertically
        val initialTopOffsetForOval: Float = if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
            val rawParas = if (text.isEmpty()) listOf("" to 0) else run {
                var off = 0
                text.split("\n").map { p ->
                    val r = p to off
                    off += p.length + 1
                    r
                }
            }
            var estLinesCount = 0
            val estAvailW = (targetW * 0.8f).coerceAtLeast(20f)
            for ((p, pStart) in rawParas) {
                val w = if (p.isEmpty()) {
                    measureTok(" ", baseStyle.fontSize, baseStyle.fontName, baseStyle.fontStyle)
                } else {
                    styledTokensOf(p, pStart).sumOf { it.w.toDouble() }.toFloat()
                }
                estLinesCount += (w / estAvailW).toInt() + 1
            }
            val estTextH = estLinesCount.coerceAtLeast(1) * lineHeight
            ((effectiveBoxHeight - estTextH) / 2f).coerceAtLeast(0f)
        } else {
            0f
        }
        var usedTopOffset = initialTopOffsetForOval
        val pass1Lines = buildLinesForOffset(initialTopOffsetForOval)

        val lines = if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
            val totalTextH1 = blockHeight(pass1Lines, lineHeight)
            val topOffset1 = ((effectiveBoxHeight - totalTextH1) / 2f).coerceAtLeast(0f)
            if (topOffset1 > 0f) {
                usedTopOffset = topOffset1
                val pass2Lines = buildLinesForOffset(topOffset1)
                val totalTextH2 = blockHeight(pass2Lines, lineHeight)
                val topOffset2 = ((effectiveBoxHeight - totalTextH2) / 2f).coerceAtLeast(0f)
                if (kotlin.math.abs(topOffset2 - topOffset1) > 1f) {
                    usedTopOffset = topOffset2
                    buildLinesForOffset(topOffset2)
                } else {
                    pass2Lines
                }
            } else {
                usedTopOffset = 0f
                pass1Lines
            }
        } else {
            usedTopOffset = 0f
            pass1Lines
        }

        val totalTextHeight = blockHeight(lines, lineHeight)
        val finalContainerW = if (shape == TextContainerShape.OVAL) {
            // Geometri oval = targetW (sama dgn perhitungan lebar per baris),
            // bukan lebar teks — outline & hit-box ikut geometri asli.
            targetW
        } else {
            boxWidth ?: (lines.maxOfOrNull { it.width }?.coerceAtLeast(20f) ?: 20f)
        }
        // OVAL: kontainer = tinggi geometri yang benar-benar dipakai saat
        // membungkus baris, dan offset gambar = offset pembungkusan. Dengan
        // ini teks tak pernah keluar outline walau boxHeight tersimpan lebih
        // kecil (sumber "kalimat nembus").
        val ovalGeometryH = if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
            maxOf(effectiveBoxHeight, usedTopOffset + totalTextHeight)
        } else null
        val finalContainerH = ovalGeometryH
            ?: boxHeight
            ?: totalTextHeight.coerceAtLeast(lineHeight)

        val topOffset = if (ovalGeometryH != null) {
            usedTopOffset
        } else if (boxHeight != null) {
            ((finalContainerH - totalTextHeight) / 2f).coerceAtLeast(0f)
        } else 0f

        val nonEmptyLines = lines.filter { it.text.isNotEmpty() }
        val minX = if (nonEmptyLines.isEmpty()) 0f else (nonEmptyLines.minOfOrNull { it.xOffset } ?: 0f)
        val maxX = if (nonEmptyLines.isEmpty()) finalContainerW else (nonEmptyLines.maxOfOrNull { it.xOffset + it.width }?.coerceAtLeast(minX + 20f) ?: finalContainerW)

        return TextLayoutResult(
            lines = lines,
            containerWidth = finalContainerW,
            containerHeight = finalContainerH,
            lineHeight = lineHeight,
            minX = minX,
            maxX = maxX,
            topOffset = topOffset
        )
    }

    fun drawStyledText(
        canvas: Canvas,
        layer: Layer.TextLayer
    ) {
        drawStyledText(
            canvas = canvas,
            text = layer.text,
            style = layer.style,
            x = layer.x,
            y = layer.y,
            shape = layer.textContainerShape,
            boxWidth = layer.boxWidth,
            boxHeight = layer.boxHeight,
            spans = layer.richSpans
        )
    }

    fun drawStyledText(
        canvas: Canvas,
        text: String,
        style: TextStyleConfig,
        x: Float = 0f,
        y: Float = 0f,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null,
        boxHeight: Float? = null,
        spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    ) {
        if (text.isEmpty()) return

        val fillAlpha = (style.textOpacity * 255).toInt().coerceIn(0, 255)
        val strokeAlpha = (style.strokeOpacity * 255).toInt().coerceIn(0, 255)

        val paint = reusableRenderPaint.apply {
            reset()
            isAntiAlias = true
            textSize = style.fontSize
            color = style.textColor
            alpha = fillAlpha
            typeface = getTypeface(style.fontName, style.fontStyle)
        }

        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight, style.alignment, style, spans)

        val gradient: android.graphics.Shader? = if (style.isGradientEnabled) {
            val gradX = x + layoutResult.minX
            val gradW = (layoutResult.maxX - layoutResult.minX).coerceAtLeast(10f)
            val gradY = y + layoutResult.topOffset
            val gradH = blockHeight(layoutResult.lines, layoutResult.lineHeight).coerceAtLeast(10f)
            val (x0, y0, x1, y1) = style.calculateGradientPoints(gradX, gradY, gradW, gradH)
            val stops = style.getEffectiveGradientStops()
            val colors = IntArray(stops.size)
            val positions = FloatArray(stops.size)

            stops.forEachIndexed { i, stop ->
                colors[i] = stop.color
                positions[i] = stop.position.coerceIn(0f, 1f)
            }

            android.graphics.LinearGradient(
                x0, y0, x1, y1,
                colors,
                positions,
                android.graphics.Shader.TileMode.CLAMP
            )
        } else null

        // Glow via shadow-layer bawaan (hasil visual yang sudah dikenal).
        val glowPaint = if (style.glowColor != Color.TRANSPARENT && style.glowRadius > 0f) {
            reusableGlowPaint.apply {
                set(paint)
                color = style.glowColor
                alpha = fillAlpha
                setShadowLayer(style.glowRadius, 0f, 0f, style.glowColor)
            }
        } else null

        val shadowPaint = if (style.shadowColor != Color.TRANSPARENT && style.shadowRadius > 0f) {
            reusableShadowPaint.apply {
                set(paint)
                color = style.shadowColor
                alpha = fillAlpha
                setShadowLayer(style.shadowRadius, style.shadowDx, style.shadowDy, style.shadowColor)
            }
        } else null

        val strokePaint = if (style.strokeColor != Color.TRANSPARENT && style.strokeWidth > 0f) {
            reusableStrokePaint.apply {
                set(paint)
                this.style = Paint.Style.STROKE
                strokeWidth = style.strokeWidth
                color = style.strokeColor
                alpha = strokeAlpha
            }
        } else null

        if (style.motionBlurRadius > 0f) {
            // Objeknya sendiri yang blur (tanpa salinan tajam di atasnya,
            // kalau tidak terbaca sebagai drop shadow).
            val smearKey = "$text|${style.hashCode()}|$shape|$boxWidth|$boxHeight|${spans.hashCode()}"
            drawMotionBlurSmear(
                canvas, x, y, layoutResult, style,
                paint, glowPaint, shadowPaint, strokePaint, smearKey, gradient
            )
        } else {
            drawTextRuns(
                canvas, layoutResult.lines, x, y,
                layoutResult.topOffset, layoutResult.lineHeight, style, gradient,
                glowPaint, shadowPaint, strokePaint
            )
        }
    }

    private fun drawTextRuns(
        canvas: Canvas,
        lines: List<RenderedLine>,
        x: Float,
        y: Float,
        topOffset: Float,
        lineHeight: Float,
        baseStyle: TextStyleConfig,
        gradient: android.graphics.Shader?,
        glowPaint: Paint?,
        shadowPaint: Paint?,
        strokePaint: Paint?
    ) {
        // Top kumulatif per baris: baris dengan kata besar hanya meninggikan
        // dirinya sendiri, bukan seluruh blok (height = tinggi baris efektif).
        var blockTop = 0f
        lines.forEach { line ->
            val lh = if (line.height > 0f) line.height else lineHeight
            // Baseline bersama per baris (ascent terbesar di baris itu).
            // Sebelumnya tiap run pakai fontMetrics sendiri → kata yang
            // diperbesar rata-atas & tumbuh ke bawah, bukan sebaris.
            var lineAscent = 0f
            for (run in line.runs) {
                if (run.text.isEmpty()) continue
                val mp = reusableMeasurePaint.apply {
                    reset()
                    isAntiAlias = true
                    textSize = run.fontSize
                    typeface = getTypeface(run.fontName, run.fontStyle)
                }
                val ascent = -mp.fontMetrics.top
                if (ascent > lineAscent) lineAscent = ascent
            }
            if (lineAscent > 0f) {
                for (run in line.runs) {
                    if (run.text.isEmpty()) continue
                    val rp = reusableRunPaint.apply {
                        reset()
                        isAntiAlias = true
                        textSize = run.fontSize
                        typeface = getTypeface(run.fontName, run.fontStyle)
                        color = run.textColor ?: baseStyle.textColor
                        alpha = (baseStyle.textOpacity * 255).toInt().coerceIn(0, 255)
                        if (gradient != null) shader = gradient
                    }
                    val rx = x + run.xOffset
                    val baselineY = y + topOffset + blockTop + lineAscent

                    // Efek ikut ukuran/font run (bukan base) — kata besar
                    // jangan dapat stroke/glow seukuran font layer.
                    glowPaint?.let {
                        it.textSize = run.fontSize
                        it.typeface = rp.typeface
                        canvas.drawText(run.text, rx, baselineY, it)
                    }
                    shadowPaint?.let {
                        it.textSize = run.fontSize
                        it.typeface = rp.typeface
                        canvas.drawText(run.text, rx, baselineY, it)
                    }
                    strokePaint?.let {
                        it.textSize = run.fontSize
                        it.typeface = rp.typeface
                        canvas.drawText(run.text, rx, baselineY, it)
                    }
                    canvas.drawText(run.text, rx, baselineY, rp)
                }
            }
            blockTop += lh
        }
    }

    private var motionBlurBitmap: Bitmap? = null
    private val motionBlurUpscalePaint = Paint().apply { isFilterBitmap = true }

    /**
     * Cache hasil smear: konten bitmap independen posisi (di-render
     * seolah x=0,y=0 lalu ditempel di left/top aktual), jadi teks blur
     * yang diam tidak di-blur ulang tiap frame.
     */
    private val smearCache = object : java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size > 4) {
                try { eldest?.value?.recycle() } catch (_: Exception) {}
                return true
            }
            return false
        }
    }

    // Versi konten render: naik hanya saat benar-benar menggambar ulang
    // (cache-hit tidak menaikkannya agar cache warp ikut hit).
    var flatVersion: Long = 0L
        private set

    private data class MinBoxWidthKey(
        val text: String,
        val textSize: Float,
        val fontName: String,
        val fontStyle: String,
        val shape: TextContainerShape,
        val spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    )

    /** Cache ukur kata/suku kata (pure function; hemat CPU tiap tick slider/ketikan). */
    private val minBoxWidthCache = object : java.util.LinkedHashMap<MinBoxWidthKey, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MinBoxWidthKey, Float>?): Boolean {
            return size > 256
        }
    }

    /**
     * Kunci render teks: semua input yang memengaruhi piksel (posisi
     * dikecualikan — bitmap konten independen posisi, origin mengikuti
     * bounds saat ini sehingga drag teks warp tetap cache-hit).
     */
    private data class FlatKey(
        val layerId: String,
        val text: String,
        val style: TextStyleConfig,
        val shape: TextContainerShape,
        val boxW: Float?,
        val boxH: Float?,
        val spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    )

    private data class FlatEntry(var bitmap: Bitmap, var origin: Offset)

    /** Cache render per layer (maks 6, yang terbuang di-recycle). */
    private val flatCache = object : java.util.LinkedHashMap<FlatKey, FlatEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FlatKey, FlatEntry>?): Boolean {
            if (size > 6) {
                try { eldest?.value?.bitmap?.recycle() } catch (_: Exception) {}
                return true
            }
            return false
        }
    }

    /**
     * Smear motion blur searah [TextStyleConfig.motionBlurAngle].
     * Teks digambar ke bitmap offscreen, diblur manual ([MotionBlur],
     * tanpa API 31+), lalu ditempel kembali sebagai SATU-SATUNYA hasil
     * (objeknya sendiri yang blur, bukan bayangan di belakang teks tajam).
     */
    private fun drawMotionBlurSmear(
        canvas: Canvas,
        x: Float,
        y: Float,
        layoutResult: TextLayoutResult,
        style: TextStyleConfig,
        paint: Paint,
        glowPaint: Paint?,
        shadowPaint: Paint?,
        strokePaint: Paint?,
        cacheKey: String,
        gradient: android.graphics.Shader? = null
    ) {
        val radius = style.motionBlurRadius
        if (radius <= 0f) return
        val textW = (layoutResult.maxX - layoutResult.minX).coerceAtLeast(1f)
        val textH = blockHeight(layoutResult.lines, layoutResult.lineHeight).coerceAtLeast(1f)
        val pad = ceil(radius.toDouble()).toInt() + 4
        val bw = (textW + 2 * pad).toInt().coerceAtLeast(1)
        val bh = (textH + 2 * pad).toInt().coerceAtLeast(1)
        // Kerja di bitmap kecil agar slider tetap mulus, upscale saat ditempel.
        // Radius kerja dibatasi ~48px (downscale ekstra saat radius besar)
        // agar jumlah taps tidak meledak saat streak panjang.
        val down = minOf(1f, 512f / maxOf(bw, bh).toFloat(), 48f / radius)
        val ww = maxOf(1, (bw * down).toInt())
        val hh = maxOf(1, (bh * down).toInt())

        // Cache-hit: langsung tempel, lewati render + blur yang mahal.
        smearCache[cacheKey]?.let { cached ->
            if (!cached.isRecycled && cached.width == ww && cached.height == hh) {
                val left = (x + layoutResult.minX) - pad
                val top = (y + layoutResult.topOffset) - pad
                canvas.drawBitmap(cached, null, RectF(left, top, left + bw, top + bh), motionBlurUpscalePaint)
                return
            }
            try { cached.recycle() } catch (_: Exception) {}
            smearCache.remove(cacheKey)
        }

        var bmp = motionBlurBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != ww || bmp.height != hh) {
            try { bmp?.recycle() } catch (_: Exception) {}
            bmp = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
            motionBlurBitmap = bmp
        }
        val work = bmp ?: return
        work.eraseColor(Color.TRANSPARENT)

        val off = Canvas(work)
        off.scale(down, down)
        off.translate(-(x + layoutResult.minX) + pad, -(y + layoutResult.topOffset) + pad)
        drawTextRuns(
            off, layoutResult.lines, x, y,
            layoutResult.topOffset, layoutResult.lineHeight,
            style, gradient, glowPaint, shadowPaint, strokePaint
        )

        val px = IntArray(ww * hh)
        work.getPixels(px, 0, ww, 0, 0, ww, hh)
        val blurred = MotionBlur.blurDirectional(px, ww, hh, style.motionBlurAngle, radius * down)
        work.setPixels(blurred, 0, ww, 0, 0, ww, hh)
        // Simpan salinan untuk frame berikutnya (buffer kerja dipakai ulang).
        try {
            work.copy(Bitmap.Config.ARGB_8888, false)?.let { smearCache[cacheKey] = it }
        } catch (_: Throwable) {}

        val left = (x + layoutResult.minX) - pad
        val top = (y + layoutResult.topOffset) - pad
        canvas.drawBitmap(work, null, RectF(left, top, left + bw, top + bh), motionBlurUpscalePaint)
    }

    /**
     * Render layer teks ke bitmap (untuk warp perspektif): seluruh
     * [drawStyledText] digambar ke bitmap seukuran bounds + bantalan efek.
     * @return bitmap + origin kiri-atasnya dalam koordinat kanvas,
     * atau null bila teks kosong.
     */
    fun renderToBitmap(layer: Layer.TextLayer): Pair<Bitmap, Offset>? = synchronized(this) {
        if (layer.text.isEmpty()) return@synchronized null
        val bounds = getWarpBounds(layer)
        val w = ceil(bounds.width()).toInt().coerceAtLeast(1)
        val h = ceil(bounds.height()).toInt().coerceAtLeast(1)
        if (w > 4096 || h > 4096) return@synchronized null
        // Cache-hit: konten sama → bitmap dipakai ulang TANPA gambar ulang
        // dan TANPA menaikkan flatVersion (cache warp ikut hit).
        val key = FlatKey(
            layerId = layer.id,
            text = layer.text,
            style = layer.style,
            shape = layer.textContainerShape,
            boxW = layer.boxWidth,
            boxH = layer.boxHeight,
            spans = layer.richSpans
        )
        flatCache[key]?.let { e ->
            if (!e.bitmap.isRecycled && e.bitmap.width == w && e.bitmap.height == h) {
                e.origin = Offset(bounds.left, bounds.top)
                return@synchronized e.bitmap to e.origin
            }
            try { e.bitmap.recycle() } catch (_: Exception) {}
            flatCache.remove(key)
        }
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return@synchronized null
        }
        bmp.eraseColor(Color.TRANSPARENT)
        val off = Canvas(bmp)
        off.translate(-bounds.left, -bounds.top)
        drawStyledText(off, layer)
        flatVersion += 1
        val entry = FlatEntry(bmp, Offset(bounds.left, bounds.top))
        flatCache[key] = entry
        return@synchronized bmp to entry.origin
    }

    /**
     * Bounds konten + bantalan efek (glow/shadow/stroke/motion blur) agar
     * render warp tidak memotong efek. Quad dinormalisasi ke box ini.
     */
    fun getWarpBounds(layer: Layer.TextLayer): RectF {
        val b = getTextBounds(layer)
        val s = layer.style
        val pad = ceil(
            maxOf(
                s.glowRadius,
                s.shadowRadius + abs(s.shadowDx) + abs(s.shadowDy),
                s.strokeWidth,
                s.motionBlurRadius,
                4f
            ).toDouble()
        ).toFloat() + 2f
        return RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    fun getTextBounds(
        layer: Layer.TextLayer
    ): RectF {        return getTextBounds(
            text = layer.text,
            style = layer.style,
            x = layer.x,
            y = layer.y,
            shape = layer.textContainerShape,
            boxWidth = layer.boxWidth,
            boxHeight = layer.boxHeight,
            spans = layer.richSpans
        )
    }

    fun getTextBounds(
        text: String,
        style: TextStyleConfig,
        x: Float,
        y: Float,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null,
        boxHeight: Float? = null,
        spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    ): RectF {
        val paint = reusableLayoutPaint.apply {
            reset()
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight, style.alignment, style, spans)

        // OVAL: bounds = geometri oval asli (targetW x effectiveHeight) supaya
        // outline/pegangan selalu menutup semua baris yang digambar.
        if (shape == TextContainerShape.OVAL) {
            return RectF(x, y, x + layoutResult.containerWidth, y + layoutResult.containerHeight)
        }

        val left = if (boxWidth != null) x else x + layoutResult.minX
        val right = if (boxWidth != null) x + boxWidth else x + layoutResult.maxX

        val top = y
        val bottom = if (boxHeight != null) y + boxHeight else y + layoutResult.containerHeight

        return RectF(left, top, right, bottom)
    }

    private fun getTypeface(fontName: String, fontStyle: String = "Regular"): Typeface {
        val trimmedFont = fontName.trim()
        val trimmedStyle = fontStyle.trim()
        val key = "${trimmedFont.lowercase()}_${trimmedStyle.lowercase()}"

        return typefaceCache.getOrPut(key) {
            val styleInt = when (trimmedStyle.lowercase()) {
                "bold" -> Typeface.BOLD
                "italic" -> Typeface.ITALIC
                "bolditalic", "bold+italic" -> Typeface.BOLD_ITALIC
                else -> Typeface.NORMAL
            }

            when (trimmedFont.lowercase()) {
                "serif" -> return@getOrPut Typeface.create(Typeface.SERIF, styleInt)
                "sans", "sans-serif" -> return@getOrPut Typeface.create(Typeface.SANS_SERIF, styleInt)
                "monospace", "mono" -> return@getOrPut Typeface.create(Typeface.MONOSPACE, styleInt)
                "default" -> return@getOrPut Typeface.create(Typeface.DEFAULT, styleInt)
            }

            val loadedTypeface = findFontFileAndCreateTypeface(trimmedFont)
            if (loadedTypeface != null) {
                try {
                    Typeface.create(loadedTypeface, styleInt)
                } catch (e: Exception) {
                    loadedTypeface
                }
            } else {
                Typeface.create(Typeface.DEFAULT, styleInt)
            }
        }
    }

    private fun findFontFileAndCreateTypeface(fontName: String): Typeface? {
        val normalizedFont = fontName.lowercase().replace("_", " ").trim()

        // Font impor (UUID.ttf): cocokkan lewat resolver displayName -> filePath.
        val resolvedPath = customFontPathResolver?.invoke(fontName)
            ?: customFontPathResolver?.invoke(normalizedFont)
        if (resolvedPath != null) {
            try {
                val resolvedFile = File(resolvedPath)
                if (resolvedFile.isFile) {
                    val tf = Typeface.createFromFile(resolvedFile)
                    if (tf != null) return tf
                }
            } catch (e: Exception) {
                Logger.e("Error: ${e.message}", e)
            }
        }

        val store = com.mochits.app.project.ProjectFileStore(context)
        val fontDirs = listOf(
            store.fontsDir(),
            store.customFontsDir()
        )

        for (dir in fontDirs) {
            if (!dir.exists()) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val nameLower = file.name.lowercase()
                if (!nameLower.endsWith(".ttf") && !nameLower.endsWith(".otf")) continue

                val baseName = file.nameWithoutExtension.lowercase().replace("_", " ").trim()
                if (baseName == normalizedFont || baseName.substringBeforeLast(".") == normalizedFont) {
                    try {
                        val tf = Typeface.createFromFile(file)
                        if (tf != null) return tf
                    } catch (e: Exception) {
                        Logger.e("Error: ${e.message}", e)
                    }
                }
            }
        }
        return null
    }
    fun getMinBoxHeight(layer: Layer.TextLayer): Float {
        return getMinBoxHeight(
            text = layer.text,
            style = layer.style,
            shape = layer.textContainerShape,
            boxWidth = layer.boxWidth,
            boxHeight = layer.boxHeight,
            spans = layer.richSpans
        )
    }

    fun getMinBoxHeight(
        text: String,
        style: TextStyleConfig,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null,
        boxHeight: Float? = null,
        spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    ): Float {
        if (text.isEmpty()) return 20f
        val paint = reusableLayoutPaint.apply {
            reset()
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        if (shape == TextContainerShape.OVAL && boxWidth != null) {
            // Fixpoint: besarkan tinggi geometri sampai teks benar-benar muat
            // (lebar per baris oval menyempit di tepi atas/bawah, jadi 1x ukur
            // tidak cukup). Konvergen karena lebar tepi ikut membesar.
            var h = (boxHeight ?: 40f).coerceAtLeast(40f)
            repeat(6) {
                val res = layoutText(text, paint, shape, boxWidth, h, style.alignment, style, spans)
                val needed = maxOf(
                    res.topOffset + blockHeight(res.lines, res.lineHeight),
                    res.containerHeight
                )
                if (needed <= h + 0.5f) return h.coerceAtLeast(30f)
                h = needed
            }
            return h.coerceAtLeast(30f)
        }
        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight = null, style.alignment, style, spans)
        val reqHeight = blockHeight(layoutResult.lines, layoutResult.lineHeight)
        return reqHeight.coerceAtLeast(if (shape == TextContainerShape.OVAL) 30f else 20f)
    }

    fun getMinBoxWidth(layer: Layer.TextLayer): Float {
        return getMinBoxWidth(
            text = layer.text,
            style = layer.style,
            shape = layer.textContainerShape,
            spans = layer.richSpans
        )
    }

    fun getMinBoxWidth(
        text: String,
        style: TextStyleConfig,
        shape: TextContainerShape = TextContainerShape.BOX,
        spans: List<com.mochits.app.model.TextSpanStyle> = emptyList()
    ): Float {
        if (text.isEmpty()) return 30f
        val key = MinBoxWidthKey(
            text = text,
            textSize = style.fontSize,
            fontName = style.fontName.trim().lowercase(),
            fontStyle = style.fontStyle.trim().lowercase(),
            shape = shape,
            spans = spans
        )
        synchronized(minBoxWidthCache) {
            minBoxWidthCache[key]?.let { return it }
        }
        // Ukur tiap kata dengan gaya efektifnya (fase 1 rich text).
        val runs = splitToRuns(text, spans)
        // Pecah per kata sambil mempertahankan gaya.
        data class WordBit(val w: String, val fs: Float, val fn: String, val fst: String)
        val bits = ArrayList<WordBit>()
        for (run in runs) {
            val fs = run.fontSize ?: style.fontSize
            val fn = run.fontName ?: style.fontName
            val fst = run.fontStyle ?: style.fontStyle
            for (word in run.text.split(Regex("\\s+"))) {
                if (word.isEmpty()) continue
                bits.add(WordBit(word, fs, fn, fst))
            }
        }
        val paint = reusableLayoutPaint
        var maxChunkW = 0f
        for (b in bits) {
            paint.apply {
                reset()
                isAntiAlias = true
                textSize = b.fs
                typeface = getTypeface(b.fn, b.fst)
            }
            val wordW = paint.measureText(b.w)
            if (wordW > maxChunkW) maxChunkW = wordW
            val syllables = SyllableSplitter.getSyllables(b.w)
            for (syl in syllables) {
                val sylW = paint.measureText("$syl-")
                if (sylW > maxChunkW) maxChunkW = sylW
            }
        }
        val minW = maxChunkW + 4f
        val result = if (shape == TextContainerShape.OVAL) {
            (minW * 1.15f).coerceAtLeast(40f)
        } else {
            minW.coerceAtLeast(30f)
        }
        synchronized(minBoxWidthCache) {
            minBoxWidthCache[key] = result
        }
        return result
    }

}
