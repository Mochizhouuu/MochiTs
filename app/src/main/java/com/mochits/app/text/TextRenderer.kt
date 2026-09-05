package com.mochits.app.text

import java.io.File
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mochits.app.model.Layer
import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextStyleConfig
import kotlin.math.sqrt

data class RenderedLine(
    val text: String,
    val width: Float,
    val xOffset: Float
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
    val boxWidth: Float?,
    val boxHeight: Float?,
    val alignment: TextAlignment
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
        alignment: TextAlignment = TextAlignment.CENTER
    ): TextLayoutResult {
        val key = TextLayoutKey(
            text = text,
            textSize = paint.textSize,
            typeface = paint.typeface,
            shape = shape,
            boxWidth = boxWidth,
            boxHeight = boxHeight,
            alignment = alignment
        )

        synchronized(layoutCache) {
            layoutCache[key]?.let { return it }
        }

        val result = computeLayoutText(text, paint, shape, boxWidth, boxHeight, alignment)

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
        alignment: TextAlignment
    ): TextLayoutResult {
        val fontMetrics = paint.fontMetrics
        val lineHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(10f)

        // Fast path for unconstrained BOX text
        if (boxWidth == null && shape == TextContainerShape.BOX) {
            val rawLines = if (text.isEmpty()) listOf("") else text.split("\n")
            val lineWidths = rawLines.map { line ->
                paint.measureText(if (line.isEmpty()) " " else line)
            }
            val maxW = lineWidths.maxOfOrNull { it }?.coerceAtLeast(20f) ?: 20f
            val lines = rawLines.zip(lineWidths).map { (line, w) ->
                val xOff = when (alignment) {
                    TextAlignment.LEFT -> 0f
                    TextAlignment.CENTER -> (maxW - w) / 2f
                    TextAlignment.RIGHT -> maxW - w
                }
                RenderedLine(text = line, width = w, xOffset = xOff)
            }
            val totalH = (lines.size * lineHeight).coerceAtLeast(lineHeight)
            val finalH = boxHeight ?: totalH
            val topOffset = if (boxHeight != null) ((finalH - totalH) / 2f).coerceAtLeast(0f) else 0f
            val nonEmptyLines = lines.filter { it.text.isNotEmpty() }
            val minX = if (nonEmptyLines.isEmpty()) 0f else (nonEmptyLines.minOfOrNull { it.xOffset } ?: 0f)
            val maxX = if (nonEmptyLines.isEmpty()) maxW else (nonEmptyLines.maxOfOrNull { it.xOffset + it.width }?.coerceAtLeast(minX + 20f) ?: maxW)
            return TextLayoutResult(lines, maxW, finalH, lineHeight, minX, maxX, topOffset)
        }

        // Determine container dimensions
        val targetW = (boxWidth ?: 200f).coerceAtLeast(20f)

        // Fast non-recursive estimation of container height for OVAL when boxHeight is null
        val effectiveBoxHeight: Float? = if (shape == TextContainerShape.OVAL && boxHeight == null) {
            var estimatedLinesCount = 0
            val rawParas = if (text.isEmpty()) listOf("") else text.split("\n")
            for (p in rawParas) {
                val w = paint.measureText(if (p.isEmpty()) " " else p)
                estimatedLinesCount += (w / (targetW * 0.7f)).toInt() + 1
            }
            (estimatedLinesCount.coerceAtLeast(1) * lineHeight * 1.5f).coerceAtLeast(40f)
        } else {
            boxHeight
        }

        val minLineWidth = paint.measureText("M-").coerceAtLeast(20f)
        val ovalA = targetW / 2f
        val ovalB = (effectiveBoxHeight ?: 20f).coerceAtLeast(20f) / 2f

        // Helper function to build lines given a vertical top offset
        fun buildLinesForOffset(computedTopOffset: Float): List<RenderedLine> {
            val availableWidthCache = FloatArray(100) { -1f }

            fun getAvailableWidth(lineIdx: Int): Float {
                if (shape == TextContainerShape.BOX || effectiveBoxHeight == null) {
                    return targetW
                }
                if (lineIdx in availableWidthCache.indices && availableWidthCache[lineIdx] >= 0f) {
                    return availableWidthCache[lineIdx]
                }
                val yRelativeToContainerTop = computedTopOffset + (lineIdx + 0.5f) * lineHeight
                val yRelativeToCenter = yRelativeToContainerTop - ovalB
                val ratio = (yRelativeToCenter / ovalB).coerceIn(-0.98f, 0.98f)
                val avail = (2f * ovalA * sqrt(1f - ratio * ratio)).coerceAtLeast(minLineWidth)
                if (lineIdx in availableWidthCache.indices) {
                    availableWidthCache[lineIdx] = avail
                }
                return avail
            }

            fun calculateLineXOffset(lineIdx: Int, lineW: Float): Float {
                val availW = getAvailableWidth(lineIdx)
                val sliceStart = if (shape == TextContainerShape.OVAL) (targetW - availW) / 2f else 0f
                return when (alignment) {
                    TextAlignment.LEFT -> sliceStart
                    TextAlignment.CENTER -> sliceStart + (availW - lineW) / 2f
                    TextAlignment.RIGHT -> sliceStart + availW - lineW
                }
            }

            val lines = mutableListOf<RenderedLine>()
            val paragraphs = if (text.isEmpty()) listOf("") else text.split("\n")
            var currentLineIdx = 0

            for (para in paragraphs) {
                if (para.isEmpty()) {
                    val xOff = calculateLineXOffset(currentLineIdx, 0f)
                    lines.add(RenderedLine("", 0f, xOff))
                    currentLineIdx++
                    continue
                }

                val tokens = tokenizeParagraph(para).toMutableList()
                var currentLineStr = ""
                var tokenIdx = 0

                while (tokenIdx < tokens.size) {
                    val token = tokens[tokenIdx]
                    val availW = getAvailableWidth(currentLineIdx)

                    if (currentLineStr.isEmpty() && token.trim().isEmpty()) {
                        tokenIdx++
                        continue
                    }

                    val candidate = currentLineStr + token
                    val candidateW = paint.measureText(candidate)

                    if (candidateW <= availW) {
                        currentLineStr = candidate
                        tokenIdx++
                    } else {
                        if (currentLineStr.isNotEmpty()) {
                            val lineStr = currentLineStr.trimEnd()
                            val lineW = paint.measureText(if (lineStr.isEmpty()) " " else lineStr)
                            val xOff = calculateLineXOffset(currentLineIdx, lineW)
                            lines.add(RenderedLine(lineStr, lineW, xOff))
                            currentLineIdx++
                            currentLineStr = ""
                        } else {
                            val cleanToken = token.trim()
                            if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
                                val yRelativeToTop = computedTopOffset + (currentLineIdx + 0.5f) * lineHeight
                                if (yRelativeToTop < ovalB && availW < targetW * 0.45f && paint.measureText(cleanToken) > availW) {
                                    currentLineIdx++
                                    continue
                                }
                            }
                            val syllables = SyllableSplitter.getSyllables(cleanToken)

                            var bestSyllableCount = 0
                            for (k in (syllables.size - 1) downTo 1) {
                                val part1Candidate = syllables.take(k).joinToString("") + "-"
                                if (paint.measureText(part1Candidate) <= availW) {
                                    bestSyllableCount = k
                                    break
                                }
                            }

                            if (bestSyllableCount > 0) {
                                val part1 = syllables.take(bestSyllableCount).joinToString("") + "-"
                                val remaining = syllables.drop(bestSyllableCount).joinToString("")
                                val lineW = paint.measureText(part1)
                                val xOff = calculateLineXOffset(currentLineIdx, lineW)
                                lines.add(RenderedLine(part1, lineW, xOff))
                                currentLineIdx++
                                tokens[tokenIdx] = remaining
                            } else {
                                var splitIdx = 0
                                for (c in 2..(cleanToken.length - 2)) {
                                    val sub = cleanToken.substring(0, c) + "-"
                                    if (paint.measureText(sub) <= availW) {
                                        splitIdx = c
                                    } else {
                                        break
                                    }
                                }

                                if (splitIdx >= 2) {
                                    val part1 = cleanToken.substring(0, splitIdx) + "-"
                                    val remaining = cleanToken.substring(splitIdx)
                                    val lineW = paint.measureText(part1)
                                    val xOff = calculateLineXOffset(currentLineIdx, lineW)
                                    lines.add(RenderedLine(part1, lineW, xOff))
                                    currentLineIdx++
                                    tokens[tokenIdx] = remaining
                                } else {
                                    val part1 = cleanToken.substring(0, 1)
                                    val remaining = cleanToken.substring(1)
                                    val lineW = paint.measureText(part1)
                                    val xOff = calculateLineXOffset(currentLineIdx, lineW)
                                    lines.add(RenderedLine(part1, lineW, xOff))
                                    currentLineIdx++
                                    if (remaining.isNotEmpty()) {
                                        tokens[tokenIdx] = remaining
                                    } else {
                                        tokenIdx++
                                    }
                                }
                            }
                        }
                    }
                }

                if (currentLineStr.isNotEmpty()) {
                    val lineStr = currentLineStr.trimEnd()
                    val lineW = paint.measureText(if (lineStr.isEmpty()) " " else lineStr)
                    val xOff = calculateLineXOffset(currentLineIdx, lineW)
                    lines.add(RenderedLine(lineStr, lineW, xOff))
                    currentLineIdx++
                }
            }

            return lines
        }

        // Pass 1: Build lines with estimated topOffset to discover lines count and center text vertically
        val initialTopOffsetForOval: Float = if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
            val rawParas = if (text.isEmpty()) listOf("") else text.split("\n")
            var estLinesCount = 0
            val estAvailW = (targetW * 0.8f).coerceAtLeast(20f)
            for (p in rawParas) {
                val w = paint.measureText(if (p.isEmpty()) " " else p)
                estLinesCount += (w / estAvailW).toInt() + 1
            }
            val estTextH = estLinesCount.coerceAtLeast(1) * lineHeight
            ((effectiveBoxHeight - estTextH) / 2f).coerceAtLeast(0f)
        } else {
            0f
        }
        val pass1Lines = buildLinesForOffset(initialTopOffsetForOval)

        val lines = if (shape == TextContainerShape.OVAL && effectiveBoxHeight != null) {
            val totalTextH1 = pass1Lines.size * lineHeight
            val topOffset1 = ((effectiveBoxHeight - totalTextH1) / 2f).coerceAtLeast(0f)
            if (topOffset1 > 0f) {
                val pass2Lines = buildLinesForOffset(topOffset1)
                val totalTextH2 = pass2Lines.size * lineHeight
                val topOffset2 = ((effectiveBoxHeight - totalTextH2) / 2f).coerceAtLeast(0f)
                if (kotlin.math.abs(topOffset2 - topOffset1) > 1f) {
                    buildLinesForOffset(topOffset2)
                } else {
                    pass2Lines
                }
            } else {
                pass1Lines
            }
        } else {
            pass1Lines
        }

        val totalTextHeight = lines.size * lineHeight
        val finalContainerW = boxWidth ?: (lines.maxOfOrNull { it.width }?.coerceAtLeast(20f) ?: 20f)
        val finalContainerH = boxHeight ?: (effectiveBoxHeight ?: totalTextHeight).coerceAtLeast(lineHeight)

        val topOffset = if (boxHeight != null || effectiveBoxHeight != null) {
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
            boxHeight = layer.boxHeight
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
        boxHeight: Float? = null
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

        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight, style.alignment)
        val fontMetrics = paint.fontMetrics

        if (style.isGradientEnabled) {
            val gradX = x + layoutResult.minX
            val gradW = (layoutResult.maxX - layoutResult.minX).coerceAtLeast(10f)
            val gradY = y + layoutResult.topOffset
            val gradH = (layoutResult.lines.size * layoutResult.lineHeight).coerceAtLeast(10f)
            val (x0, y0, x1, y1) = style.calculateGradientPoints(gradX, gradY, gradW, gradH)
            val stops = style.getEffectiveGradientStops()
            val colors = IntArray(stops.size)
            val positions = FloatArray(stops.size)

            stops.forEachIndexed { i, stop ->
                colors[i] = stop.color
                positions[i] = stop.position.coerceIn(0f, 1f)
            }

            paint.shader = android.graphics.LinearGradient(
                x0, y0, x1, y1,
                colors,
                positions,
                android.graphics.Shader.TileMode.CLAMP
            )
        }

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

        layoutResult.lines.forEachIndexed { i, line ->
            if (line.text.isNotEmpty()) {
                val lineX = x + line.xOffset
                val baselineY = y + layoutResult.topOffset + (i * layoutResult.lineHeight) - fontMetrics.top

                glowPaint?.let { canvas.drawText(line.text, lineX, baselineY, it) }
                shadowPaint?.let { canvas.drawText(line.text, lineX, baselineY, it) }
                strokePaint?.let { canvas.drawText(line.text, lineX, baselineY, it) }
                canvas.drawText(line.text, lineX, baselineY, paint)
            }
        }
    }

    fun getTextBounds(
        layer: Layer.TextLayer
    ): RectF {
        return getTextBounds(
            text = layer.text,
            style = layer.style,
            x = layer.x,
            y = layer.y,
            shape = layer.textContainerShape,
            boxWidth = layer.boxWidth,
            boxHeight = layer.boxHeight
        )
    }

    fun getTextBounds(
        text: String,
        style: TextStyleConfig,
        x: Float,
        y: Float,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null,
        boxHeight: Float? = null
    ): RectF {
        val paint = reusableLayoutPaint.apply {
            reset()
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight, style.alignment)

        val totalTextHeight = (layoutResult.lines.size * layoutResult.lineHeight).coerceAtLeast(layoutResult.lineHeight)

        val left = if (boxWidth != null) x else x + layoutResult.minX
        val right = if (boxWidth != null) x + boxWidth else x + layoutResult.maxX

        val top = if (boxHeight != null) y else y + layoutResult.topOffset
        val bottom = if (boxHeight != null) y + boxHeight else y + layoutResult.topOffset + totalTextHeight

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

        val fontDirs = listOf(
            File(context.filesDir, "fonts"),
            File(context.filesDir, "custom_fonts")
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
                        e.printStackTrace()
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
            boxWidth = layer.boxWidth
        )
    }

    fun getMinBoxHeight(
        text: String,
        style: TextStyleConfig,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null
    ): Float {
        if (text.isEmpty()) return 20f
        val paint = reusableLayoutPaint.apply {
            reset()
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight = null, style.alignment)
        val reqHeight = layoutResult.lines.size * layoutResult.lineHeight
        return if (shape == TextContainerShape.OVAL) {
            (reqHeight * 1.15f).coerceAtLeast(30f)
        } else {
            reqHeight.coerceAtLeast(20f)
        }
    }

    fun getMinBoxWidth(layer: Layer.TextLayer): Float {
        return getMinBoxWidth(
            text = layer.text,
            style = layer.style,
            shape = layer.textContainerShape
        )
    }

    fun getMinBoxWidth(
        text: String,
        style: TextStyleConfig,
        shape: TextContainerShape = TextContainerShape.BOX
    ): Float {
        if (text.isEmpty()) return 30f
        val paint = reusableLayoutPaint.apply {
            reset()
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        var maxChunkW = 0f
        val words = text.split(Regex("\\s+"))
        for (word in words) {
            if (word.isEmpty()) continue
            val wordW = paint.measureText(word)
            if (wordW > maxChunkW) maxChunkW = wordW
            val syllables = SyllableSplitter.getSyllables(word)
            for (syl in syllables) {
                val sylW = paint.measureText("$syl-")
                if (sylW > maxChunkW) maxChunkW = sylW
            }
        }
        val minW = maxChunkW + 12f
        return if (shape == TextContainerShape.OVAL) {
            (minW * 1.35f).coerceAtLeast(40f)
        } else {
            minW.coerceAtLeast(30f)
        }
    }

}
