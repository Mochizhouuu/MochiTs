package com.mochits.app.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.mochits.app.model.TextStyleConfig

class TextRenderer(private val context: Context) {

    fun drawStyledText(
        canvas: Canvas,
        text: String,
        style: TextStyleConfig,
        x: Float = 0f,
        y: Float = 0f
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

        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(20f)
        val textWidth = paint.measureText(if (text.isEmpty()) " " else text).coerceAtLeast(20f)
        val baselineY = y - fontMetrics.top

        if (style.isGradientEnabled) {
            val (x0, y0, x1, y1) = if (style.gradientDirection.equals("VERTICAL", ignoreCase = true)) {
                floatArrayOf(x, y, x, y + textHeight)
            } else {
                floatArrayOf(x, y, x + textWidth, y)
            }
            paint.shader = android.graphics.LinearGradient(
                x0, y0, x1, y1,
                style.gradientStartColor,
                style.gradientEndColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        // Draw Glow / Drop Shadow if present
        if (style.glowColor != Color.TRANSPARENT && style.glowRadius > 0f) {
            val glowPaint = Paint(paint).apply {
                color = style.glowColor
                alpha = fillAlpha
                setShadowLayer(style.glowRadius, 0f, 0f, style.glowColor)
            }
            canvas.drawText(text, x, baselineY, glowPaint)
        }

        if (style.shadowColor != Color.TRANSPARENT && style.shadowRadius > 0f) {
            val shadowPaint = Paint(paint).apply {
                color = style.shadowColor
                alpha = fillAlpha
                setShadowLayer(style.shadowRadius, style.shadowDx, style.shadowDy, style.shadowColor)
            }
            canvas.drawText(text, x, baselineY, shadowPaint)
        }

        // Draw Stroke
        if (style.strokeColor != Color.TRANSPARENT && style.strokeWidth > 0f) {
            val strokePaint = Paint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = style.strokeWidth
                color = style.strokeColor
                alpha = strokeAlpha
            }
            canvas.drawText(text, x, baselineY, strokePaint)
        }

        // Draw Main Fill Text
        canvas.drawText(text, x, baselineY, paint)
    }

    fun getTextBounds(text: String, style: TextStyleConfig, x: Float, y: Float): android.graphics.RectF {
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = style.fontSize
            typeface = getTypeface(style.fontName, style.fontStyle)
        }
        val textWidth = paint.measureText(if (text.isEmpty()) " " else text).coerceAtLeast(20f)
        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(20f)
        return android.graphics.RectF(x, y, x + textWidth, y + textHeight)
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
