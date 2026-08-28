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

        val paint = Paint().apply {
            isAntiAlias = true
            textSize = style.fontSize
            color = style.textColor
            typeface = getTypeface(style.fontName)
        }

        val fontMetrics = paint.fontMetrics
        val baselineY = y - fontMetrics.top

        // Draw Glow / Drop Shadow if present
        if (style.glowColor != Color.TRANSPARENT && style.glowRadius > 0f) {
            val glowPaint = Paint(paint).apply {
                color = style.glowColor
                setShadowLayer(style.glowRadius, 0f, 0f, style.glowColor)
            }
            canvas.drawText(text, x, baselineY, glowPaint)
        }

        if (style.shadowColor != Color.TRANSPARENT && style.shadowRadius > 0f) {
            val shadowPaint = Paint(paint).apply {
                color = style.textColor
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
            typeface = getTypeface(style.fontName)
        }
        val textWidth = paint.measureText(if (text.isEmpty()) " " else text).coerceAtLeast(20f)
        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(20f)
        return android.graphics.RectF(x, y, x + textWidth, y + textHeight)
    }

    private fun getTypeface(fontName: String): Typeface {
        return when (fontName.lowercase()) {
            "serif" -> Typeface.SERIF
            "sans" -> Typeface.SANS_SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT_BOLD
        }
    }
}
