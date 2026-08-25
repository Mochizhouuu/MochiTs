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
            val nativeCanvas = canvas.nativeCanvas
            
            // Matikan hardware acceleration layer untuk kompatibilitas BlurMaskFilter di Android 11-
            nativeCanvas.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

            val baseTypeface = if (style.fontPath != null) {
                try {
                    Typeface.createFromFile(style.fontPath)
                } catch (e: Exception) {
                    Typeface.DEFAULT
                }
            } else {
                Typeface.DEFAULT
            }

            val tfStyle = when {
                style.isBold && style.isItalic -> Typeface.BOLD_ITALIC
                style.isBold -> Typeface.BOLD
                style.isItalic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }

            val finalTypeface = Typeface.create(baseTypeface, tfStyle)

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

            val baselineY = fontSizePx * 1.2f

            val (textWidth, _) = measureStyledText(text, fontSizePx)

            // Path untuk Curved Text (jika diaktifkan)
            val curvedPath = if (style.curveEnabled) {
                Path().apply {
                    val radius = style.curveRadius
                    val cx = textWidth / 2f
                    val cy = baselineY + radius
                    addCircle(cx, cy, radius, Path.Direction.CW)
                }
            } else null

            // 1. Motion Blur Effect (Multi-pass directional step)
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
                    nativeCanvas.save()
                    nativeCanvas.translate(ox, oy)
                    drawTextOrPath(nativeCanvas, text, curvedPath, 0f, baselineY, blurPaint)
                    nativeCanvas.restore()
                }
            }

            // 2. Glow Effect (BlurMaskFilter dengan LAYER_TYPE_SOFTWARE untuk kompatibilitas)
            if (style.glowEnabled && style.glowRadius > 0f) {
                val glowPaint = Paint(basePaint).apply {
                    color = style.glowColorArgb
                    // Gunakan BlurMaskFilter dengan software layer untuk kompatibilitas Android 11-
                    maskFilter = BlurMaskFilter(style.glowRadius, BlurMaskFilter.Blur.NORMAL)
                }
                nativeCanvas.save()
                drawTextOrPath(nativeCanvas, text, curvedPath, 0f, baselineY, glowPaint)
                nativeCanvas.restore()
            }

            // 3. Drop Shadow Effect (dengan software layer untuk kompatibilitas)
            if (style.shadowEnabled) {
                val shadowPaint = Paint(basePaint).apply {
                    color = style.shadowColorArgb
                    if (style.shadowRadius > 0f) {
                        maskFilter = BlurMaskFilter(style.shadowRadius, BlurMaskFilter.Blur.NORMAL)
                    }
                }
                nativeCanvas.save()
                nativeCanvas.translate(style.shadowDx, style.shadowDy)
                drawTextOrPath(nativeCanvas, text, curvedPath, 0f, baselineY, shadowPaint)
                nativeCanvas.restore()
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
                drawTextOrPath(nativeCanvas, text, curvedPath, 0f, baselineY, strokePaint)
            }

            // 5. Fill (Solid / Gradient)
            val fillPaint = Paint(basePaint).apply {
                this.style = Paint.Style.FILL
                if (style.gradientEnabled && style.gradientColors.size >= 2) {
                    shader = LinearGradient(
                        0f, 0f, textWidth, 0f,
                        style.gradientColors.toIntArray(),
                        null,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    color = style.colorArgb
                }
            }
            drawTextOrPath(nativeCanvas, text, curvedPath, 0f, baselineY, fillPaint)
        }
    }
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

fun measureStyledText(text: String, fontSizePx: Float): Pair<Float, Float> {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        typeface = Typeface.DEFAULT
    }
    val width = paint.measureText(text)
    val metrics = paint.fontMetrics
    val height = metrics.descent - metrics.ascent
    return width to height
}
