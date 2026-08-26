package com.mochits.text

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom Text Rendering dengan efek Photoshop (Stroke, Shadow, Glow, Motion Blur, Gradient, Curved Path)
 * yang 100% kompatibel dengan Android 11 ke bawah (API Level 24+).
 * 
 * CATATAN: BlurMaskFilter memerlukan hardware acceleration dimatikan untuk shadow/glow.
 * Untuk kompatibilitas maksimal di Android 11-, kita gunakan fallback manual blur jika diperlukan.
 */
@Composable
fun StyledText(
    text: String,
    fontSizePx: Float,
    style: TextStyleConfig,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            drawStyledTextOnNativeCanvas(
                canvas = canvas.nativeCanvas,
                text = text,
                xPx = 0f,
                yPx = 0f,
                fontSizePx = fontSizePx,
                style = style
            )
        }
    }
}

/**
 * Shared drawing function untuk merender teks berserta seluruh efeknya
 * (alignment, curved text, gradient fill, stroke, drop shadow, glow, motion blur)
 * langsung pada android.graphics.Canvas native.
 * Dipakai oleh Compose [StyledText] dan [ProjectExporter].
 */
fun drawStyledTextOnNativeCanvas(
    canvas: Canvas,
    text: String,
    xPx: Float,
    yPx: Float,
    fontSizePx: Float,
    style: TextStyleConfig
) {
    val finalTypeface = resolveTypeface(style)

    val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = fontSizePx
        typeface = finalTypeface
        isUnderlineText = style.isUnderline
        isStrikeThruText = style.isStrikethrough
        textAlign = when (style.alignment) {
            TextAlignment.LEFT -> Paint.Align.LEFT
            TextAlignment.CENTER -> Paint.Align.CENTER
            TextAlignment.RIGHT -> Paint.Align.RIGHT
        }
    }

    val baselineY = yPx + fontSizePx * 1.2f

    val (textWidth, _) = measureStyledText(text, fontSizePx, style)

    val alignX = when (style.alignment) {
        TextAlignment.LEFT -> xPx
        TextAlignment.CENTER -> xPx + textWidth / 2f
        TextAlignment.RIGHT -> xPx + textWidth
    }

    val lineHeight = fontSizePx * 1.2f
    val textLines = text.split('\n')

    // Path untuk Curved Text
    val curvedPath = if (style.curveEnabled && textLines.size == 1) {
        Path().apply {
            val radius = style.curveRadius
            val cx = alignX
            val cy = baselineY + radius
            addCircle(cx, cy, radius, Path.Direction.CW)
        }
    } else null

    fun drawAllLines(paint: Paint) {
        if (curvedPath != null) {
            drawTextOrPath(canvas, textLines[0], curvedPath, alignX, baselineY, paint)
        } else {
            textLines.forEachIndexed { index, line ->
                canvas.drawText(line, alignX, baselineY + index * lineHeight, paint)
            }
        }
    }

    // 1. Motion Blur Effect
    if (style.motionBlurEnabled && style.motionBlurDistance > 0f) {
        val samples = 5
        val rad = Math.toRadians(style.motionBlurAngle.toDouble())
        val dxStep = (cos(rad) * style.motionBlurDistance / samples).toFloat()
        val dyStep = (sin(rad) * style.motionBlurDistance / samples).toFloat()

        val blurPaint = Paint(basePaint).apply {
            color = style.colorArgb
            alpha = (255 / (samples + 1)).coerceIn(20, 100)
        }

        for (i in 1..samples) {
            val ox = dxStep * i
            val oy = dyStep * i
            canvas.save()
            canvas.translate(ox, oy)
            drawAllLines(blurPaint)
            canvas.restore()
        }
    }

    // 2. Glow Effect
    if (style.glowEnabled && style.glowRadius > 0f) {
        val glowPaint = Paint(basePaint).apply {
            color = style.glowColorArgb
            maskFilter = BlurMaskFilter(style.glowRadius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        drawAllLines(glowPaint)
        canvas.restore()
    }

    // 3. Drop Shadow Effect
    if (style.shadowEnabled) {
        val shadowPaint = Paint(basePaint).apply {
            color = style.shadowColorArgb
            if (style.shadowRadius > 0f) {
                maskFilter = BlurMaskFilter(style.shadowRadius, BlurMaskFilter.Blur.NORMAL)
            }
        }
        canvas.save()
        canvas.translate(style.shadowDx, style.shadowDy)
        drawAllLines(shadowPaint)
        canvas.restore()
    }

    // 4. Stroke / Outline Effect
    if (style.strokeEnabled) {
        val strokePaint = Paint(basePaint).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = style.strokeWidthPx
            color = style.strokeColorArgb
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        drawAllLines(strokePaint)
    }

    // 5. Fill (Solid / Gradient)
    val fillPaint = Paint(basePaint).apply {
        this.style = Paint.Style.FILL
        if (style.gradientEnabled && style.gradientColors.size >= 2) {
            // Span glyph selalu [xPx, xPx + textWidth]: alignX sudah
            // dikompensasi lewat Paint.textAlign (CENTER/RIGHT), jadi
            // gradient harus tetap dimulai dari xPx untuk SEMUA alignment
            // agar warnanya pas di atas glyph.
            shader = LinearGradient(
                xPx, 0f, xPx + textWidth, 0f,
                style.gradientColors.toIntArray(),
                null,
                Shader.TileMode.CLAMP
            )
        } else {
            color = style.colorArgb
        }
    }
    drawAllLines(fillPaint)
}

private fun drawTextOrPath(
    canvas: Canvas,
    text: String,
    path: Path?,
    x: Float,
    y: Float,
    paint: Paint
) {
    if (path != null) {
        canvas.drawTextOnPath(text, path, 0f, 0f, paint)
    } else {
        canvas.drawText(text, x, y, paint)
    }
}

/**
 * Membentuk [Typeface] dari [TextStyleConfig]: font custom (via path) bila
 * ada, dengan variasi bold/italic yang sesuai. Fallback ke system default
 * jika file font gagal dimuat.
 */
fun resolveTypeface(style: TextStyleConfig?): Typeface {
    val base = style?.fontPath?.let { path ->
        try {
            Typeface.createFromFile(path)
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    } ?: Typeface.DEFAULT

    if (style == null) return base
    val tfStyle = when {
        style.isBold && style.isItalic -> Typeface.BOLD_ITALIC
        style.isBold -> Typeface.BOLD
        style.isItalic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return Typeface.create(base, tfStyle)
}

fun measureStyledText(
    text: String,
    fontSizePx: Float,
    style: TextStyleConfig? = null
): Pair<Float, Float> {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        typeface = resolveTypeface(style)
    }
    val lines = text.split('\n')
    var width = 0f
    for (line in lines) {
        val w = paint.measureText(line)
        if (w > width) width = w
    }
    val metrics = paint.fontMetrics
    val singleLineHeight = metrics.descent - metrics.ascent
    // Spacing antar baris sama dengan yang dipakai saat render (1.2 x ukuran font)
    val height = if (lines.size == 1) {
        singleLineHeight
    } else {
        (lines.size - 1) * fontSizePx * 1.2f + singleLineHeight
    }
    return width to height
}
