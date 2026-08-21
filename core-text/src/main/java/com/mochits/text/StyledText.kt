package com.mochits.text

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * Menggambar teks dengan stroke (outline) dan shadow via native
 * [Paint] Android — BasicText bawaan Compose tidak mendukung stroke +
 * fill dua lapis sekaligus, sehingga rendering manual diperlukan.
 *
 * Urutan gambar (dari bawah ke atas): shadow (jika aktif) -> stroke
 * (jika aktif, digambar sebelum fill agar fill menutupi bagian dalam
 * stroke, menghasilkan outline yang bersih) -> fill (warna utama).
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

            val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizePx
                typeface = Typeface.DEFAULT
            }

            // Baseline sederhana: teks digambar mulai dari (0, fontSize)
            // relatif terhadap Canvas ini — pemanggil bertanggung jawab
            // menempatkan Canvas ini di posisi layer yang benar.
            val baselineY = fontSizePx

            if (style.shadowEnabled) {
                val shadowPaint = Paint(basePaint).apply {
                    color = style.shadowColorArgb
                    if (style.strokeEnabled) {
                        this.style = Paint.Style.FILL_AND_STROKE
                        strokeWidth = style.strokeWidthPx
                    }
                }
                nativeCanvas.save()
                nativeCanvas.translate(style.shadowDx, style.shadowDy)
                // shadowRadius disimulasikan dengan setShadowLayer agar
                // hasilnya benar-benar blur, bukan cuma offset solid.
                shadowPaint.setShadowLayer(
                    style.shadowRadius, 0f, 0f, style.shadowColorArgb
                )
                nativeCanvas.drawText(text, 0f, baselineY, shadowPaint)
                nativeCanvas.restore()
            }

            if (style.strokeEnabled) {
                val strokePaint = Paint(basePaint).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = style.strokeWidthPx
                    color = style.strokeColorArgb
                }
                nativeCanvas.drawText(text, 0f, baselineY, strokePaint)
            }

            val fillPaint = Paint(basePaint).apply {
                this.style = Paint.Style.FILL
                color = style.colorArgb
            }
            nativeCanvas.drawText(text, 0f, baselineY, fillPaint)
        }
    }
}

/**
 * Mengukur lebar & tinggi kira-kira dari [text] pada [fontSizePx],
 * dipakai pemanggil untuk menentukan ukuran [StyledText] (Canvas
 * Compose tidak auto-sizing seperti BasicText).
 */
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
