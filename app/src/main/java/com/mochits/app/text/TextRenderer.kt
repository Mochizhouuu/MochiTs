package com.mochits.app.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mochits.app.model.Layer
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
    val lineHeight: Float
)

class TextRenderer(private val context: Context) {

    fun layoutText(
        text: String,
        paint: Paint,
        shape: TextContainerShape = TextContainerShape.BOX,
        boxWidth: Float? = null,
        boxHeight: Float? = null
    ): TextLayoutResult {
        val fontMetrics = paint.fontMetrics
        val lineHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(10f)

        if (boxWidth == null && boxHeight == null) {
            val rawLines = if (text.isEmpty()) listOf("") else text.split("\n")
            val lines = rawLines.map { line ->
                val w = paint.measureText(if (line.isEmpty()) " " else line)
                RenderedLine(text = line, width = w, xOffset = 0f)
            }
            val maxW = lines.maxOfOrNull { it.width }?.coerceAtLeast(20f) ?: 20f
            val totalH = (lines.size * lineHeight).coerceAtLeast(lineHeight)
            return TextLayoutResult(lines, maxW, totalH, lineHeight)
        }

        val targetW = (boxWidth ?: 200f).coerceAtLeast(20f)

        fun getAvailableWidth(lineIdx: Int): Float {
            if (shape == TextContainerShape.BOX || boxHeight == null) {
                return targetW
            }
            val targetH = boxHeight.coerceAtLeast(20f)
            val a = targetW / 2f
            val b = targetH / 2f
            val y = (lineIdx + 0.5f) * lineHeight - b
            val minLineWidth = paint.measureText("M-").coerceAtLeast(15f)
            val ratio = (y / b).coerceIn(-0.98f, 0.98f)
            val avail = 2f * a * sqrt(1f - ratio * ratio)
            return avail.coerceAtLeast(minLineWidth)
        }

        val lines = mutableListOf<RenderedLine>()
        val paragraphs = if (text.isEmpty()) listOf("") else text.split("\n")

        var currentLineIdx = 0

        for (para in paragraphs) {
            if (para.isEmpty()) {
                val availW = getAvailableWidth(currentLineIdx)
                val xOff = if (shape == TextContainerShape.OVAL) (targetW - 0f) / 2f else 0f
                lines.add(RenderedLine("", 0f, xOff))
                currentLineIdx++
                continue
            }

            // Split into tokens (words and spaces)
            val tokens = mutableListOf<String>()
            val matcher = java.util.regex.Pattern.compile("\\s+|[^\\s]+").matcher(para)
            while (matcher.find()) {
                tokens.add(matcher.group())
            }

            var currentLineStr = ""
            var tokenIdx = 0

            while (tokenIdx < tokens.size) {
                val token = tokens[tokenIdx]
                val availW = getAvailableWidth(currentLineIdx)

                // If token is just whitespace and we are at start of line, skip it
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
                        // Push current line
                        val lineStr = currentLineStr.trimEnd()
                        val lineW = paint.measureText(if (lineStr.isEmpty()) " " else lineStr)
                        val xOff = if (shape == TextContainerShape.OVAL) (targetW - lineW) / 2f else 0f
                        lines.add(RenderedLine(lineStr, lineW, xOff))
                        currentLineIdx++
                        currentLineStr = ""
                    } else {
                        // Single word exceeds line available width -> greedy hyphenation
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
                            val xOff = if (shape == TextContainerShape.OVAL) (targetW - lineW) / 2f else 0f
                            lines.add(RenderedLine(part1, lineW, xOff))
                            currentLineIdx++
                            tokens[tokenIdx] = remaining
                        } else {
                            // If not even 1 char + hyphen fits, take 1 char anyway
                            val part1 = cleanToken.substring(0, 1)
                            val remaining = cleanToken.substring(1)
                            val lineW = paint.measureText(part1)
                            val xOff = if (shape == TextContainerShape.OVAL) (targetW - lineW) / 2f else 0f
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
                val xOff = if (shape == TextContainerShape.OVAL) (targetW - lineW) / 2f else 0f
                lines.add(RenderedLine(lineStr, lineW, xOff))
                currentLineIdx++
            }
        }

        val finalContainerW = boxWidth ?: (lines.maxOfOrNull { it.width }?.coerceAtLeast(20f) ?: 20f)
        val calculatedH = (lines.size * lineHeight).coerceAtLeast(lineHeight)
        val finalContainerH = boxHeight ?: calculatedH

        return TextLayoutResult(lines, finalContainerW, finalContainerH, lineHeight)
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

        val paint = Paint().apply {
            isAntiAlias = true
            textSize = style.fontSize
            color = style.textColor
            alpha = fillAlpha
            typeface = getTypeface(style.fontName, style.fontStyle)
        }

        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight)
        val fontMetrics = paint.fontMetrics

        val totalTextHeight = layoutResult.lines.size * layoutResult.lineHeight
        val topOffset = if (boxHeight != null && shape == TextContainerShape.OVAL) {
            ((layoutResult.containerHeight - totalTextHeight) / 2f).coerceAtLeast(0f)
        } else 0f

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
            Paint(paint).apply {
                color = style.glowColor
                alpha = fillAlpha
                setShadowLayer(style.glowRadius, 0f, 0f, style.glowColor)
            }
        } else null

        val shadowPaint = if (style.shadowColor != Color.TRANSPARENT && style.shadowRadius > 0f) {
            Paint(paint).apply {
                color = style.shadowColor
                alpha = fillAlpha
                setShadowLayer(style.shadowRadius, style.shadowDx, style.shadowDy, style.shadowColor)
            }
        } else null

        val strokePaint = if (style.strokeColor != Color.TRANSPARENT && style.strokeWidth > 0f) {
            Paint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = style.strokeWidth
                color = style.strokeColor
                alpha = strokeAlpha
            }
        } else null

        layoutResult.lines.forEachIndexed { i, line ->
            if (line.text.isNotEmpty()) {
                val lineX = x + line.xOffset
                val baselineY = y + topOffset + (i * layoutResult.lineHeight) - fontMetrics.top

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
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        val layoutResult = layoutText(text, paint, shape, boxWidth, boxHeight)
        return RectF(
            x,
            y,
            x + layoutResult.containerWidth,
            y + layoutResult.containerHeight
        )
    }

    private fun getTypeface(fontName: String, fontStyle: String = "Regular"): Typeface {
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
        return Typeface.create(baseTypeface, styleInt)
    }
}
