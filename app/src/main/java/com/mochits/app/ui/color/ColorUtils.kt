package com.mochits.app.ui.color

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ColorUtils {

    fun colorToHsv(color: Int): FloatArray {
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val cMax = max(r, max(g, b))
        val cMin = min(r, min(g, b))
        val delta = cMax - cMin

        var h = 0f
        if (delta != 0f) {
            h = when (cMax) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            if (h < 0f) h += 360f
        }

        val s = if (cMax == 0f) 0f else delta / cMax
        val v = cMax

        return floatArrayOf(h, s, v)
    }

    fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()

        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c

        val (rPrime, gPrime, bPrime) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((rPrime + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((gPrime + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((bPrime + m) * 255f).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun colorToHex(color: Int, includeAlpha: Boolean = true): String {
        return if (includeAlpha) {
            String.format("#%08X", color)
        } else {
            String.format("#%06X", 0x00FFFFFF and color)
        }
    }

    fun parseHexColor(hex: String): Int? {
        val cleaned = hex.trim().removePrefix("#")
        return try {
            when (cleaned.length) {
                6 -> {
                    val rgb = cleaned.toLong(16).toInt()
                    (0xFF shl 24) or (rgb and 0x00FFFFFF)
                }
                8 -> {
                    cleaned.toLong(16).toInt()
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun samplePixelColor(bitmap: Bitmap?, x: Float, y: Float): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        val ix = x.toInt().coerceIn(0, bitmap.width - 1)
        val iy = y.toInt().coerceIn(0, bitmap.height - 1)
        return try {
            bitmap.getPixel(ix, iy)
        } catch (_: Exception) {
            null
        }
    }
}
