package com.mochits.app.text

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
import java.util.regex.Pattern
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

class TextRenderer(private val context: Context) {

    companion object {
        private val TOKEN_PATTERN: Pattern = Pattern.compile("\\s+|[^\\s]+")
        private val typefaceCache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()
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
        val fontMetrics = paint.fontMetrics
        val lineHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(10f)

        // Fast path for unconstrained BOX text
        if (boxWidth == null && boxHeight == null && shape == TextContainerShape.BOX) {
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
            val nonEmptyLines = lines.filter { it.text.isNotEmpty() }
            val minX = if (nonEmptyLines.isEmpty()) 0f else (nonEmptyLines.minOfOrNull { it.xOffset } ?: 0f)
            val maxX = if (nonEmptyLines.isEmpty()) maxW else (nonEmptyLines.maxOfOrNull { it.xOffset + it.width }?.coerceAtLeast(minX + 20f) ?: maxW)
            return TextLayoutResult(lines, maxW, totalH, lineHeight, minX, maxX, 0f)
        }

        // Determine container dimensions
        val targetW = (boxWidth ?: 200f).coerceAtLeast(20f)

        // If boxHeight is null for OVAL shape, estimate container height from natural wrapped box lines count
        val effectiveBoxHeight: Float? = if (shape == TextContainerShape.OVAL && boxHeight == null) {
            val boxLinesCount = layoutText(text, paint, TextContainerShape.BOX, targetW, null, alignment).lines.size
            (boxLinesCount * lineHeight * 1.5f).coerceAtLeast(40f)
        } else {
            boxHeight
        }

        val minLineWidth = paint.measureText("M-").coerceAtLeast(20f)

        // Function to calculate available width at line index
        fun getAvailableWidth(lineIdx: Int): Float {
            if (shape == TextContainerShape.BOX || effectiveBoxHeight == null) {
                return targetW
            }
            val targetH = effectiveBoxHeight.coerceAtLeast(20f)
            val a = targetW / 2f
            val b = targetH / 2f
            val y = (lineIdx + 0.5f) * lineHeight - b
            val ratio = (y / b).coerceIn(-0.98f, 0.98f)
            val avail = 2f * a * sqrt(1f - ratio * ratio)
            return avail.coerceAtLeast(minLineWidth)
        }

        // Function to calculate line xOffset given available width and actual line width
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

            // Tokenize into words and whitespace spaces
            val tokens = mutableListOf<String>()
            val matcher = TOKEN_PATTERN.matcher(para)
            while (matcher.find()) {
                tokens.add(matcher.group())
            }

            var currentLineStr = ""
            var tokenIdx = 0

            while (tokenIdx < tokens.size) {
                val token = tokens[tokenIdx]
                val availW = getAvailableWidth(currentLineIdx)

                // Skip leading whitespace at start of a line
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
                        // Current line has words, flush it and move to next line
                        val lineStr = currentLineStr.trimEnd()
                        val lineW = paint.measureText(if (lineStr.isEmpty()) " " else lineStr)
                        val xOff = calculateLineXOffset(currentLineIdx, lineW)
                        lines.add(RenderedLine(lineStr, lineW, xOff))
                        currentLineIdx++
                        currentLineStr = ""
                    } else {
                        // Single word exceeds line available width -> perform greedy hyphenation on this word ONLY
                        val cleanToken = token.trim()
                        val hyphenW = paint.measureText("-")
                        var splitIdx = 0

                        for (c in 1 until cleanToken.length) {
                            val sub = cleanToken.substring(0, c)
                            if (paint.measureText(sub) + hyphenW <= availW) {
                                splitIdx = c
                            } else {
                                break
                            }
                        }

                        if (splitIdx > 0) {
                            val part1 = cleanToken.substring(0, splitIdx) + "-"
                            val remaining = cleanToken.substring(splitIdx)
                            val lineW = paint.measureText(part1)
                            val xOff = calculateLineXOffset(currentLineIdx, lineW)
                            lines.add(RenderedLine(part1, lineW, xOff))
                            currentLineIdx++
                            tokens[tokenIdx] = remaining
                        } else {
                            // If not even 1 char + hyphen fits, take 1 char without hyphen
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

            if (currentLineStr.isNotEmpty()) {
                val lineStr = currentLineStr.trimEnd()
                val lineW = paint.measureText(if (lineStr.isEmpty()) " " else lineStr)
                val xOff = calculateLineXOffset(currentLineIdx, lineW)
                lines.add(RenderedLine(lineStr, lineW, xOff))
                currentLineIdx++
            }
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
            val (x0, y0, x1, y1) = style.calculateGradientPoints(
                x, y, layoutResult.containerWidth, layoutResult.containerHeight
            )
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

        // Exact visual bounding box wrapping the rendered text lines precisely
        val totalTextHeight = (layoutResult.lines.size * layoutResult.lineHeight).coerceAtLeast(layoutResult.lineHeight)

        return RectF(
            x + layoutResult.minX,
            y + layoutResult.topOffset,
            x + layoutResult.maxX,
            y + layoutResult.topOffset + totalTextHeight
        )
    }

    private fun getTypeface(fontName: String, fontStyle: String = "Regular"): Typeface {
        val key = "${fontName.lowercase().trim()}_${fontStyle.lowercase().trim()}"
        return typefaceCache.getOrPut(key) {
            val baseTypeface = when (fontName.lowercase().trim()) {
                "serif" -> Typeface.SERIF
                "sans", "sans-serif" -> Typeface.SANS_SERIF
                "monospace", "mono" -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
            val styleInt = when (fontStyle.lowercase().trim()) {
                "bold" -> Typeface.BOLD
                "italic" -> Typeface.ITALIC
                "bolditalic", "bold+italic" -> Typeface.BOLD_ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(baseTypeface, styleInt)
        }
    }
}
