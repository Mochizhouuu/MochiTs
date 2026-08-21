package com.mochits.text

/**
 * Konfigurasi style teks yang bisa diatur user secara langsung (bukan
 * dari [StylePreset] tersimpan) — warna dasar, stroke (outline), dan
 * shadow. Dipakai oleh [TextRenderer] untuk menggambar teks via Paint
 * native Android agar stroke/shadow bisa dikontrol presisi (BasicText
 * bawaan Compose tidak mendukung stroke+fill dua lapis).
 */
data class TextStyleConfig(
    val colorArgb: Int = 0xFF000000.toInt(), // hitam default
    val strokeEnabled: Boolean = false,
    val strokeWidthPx: Float = 4f,
    val strokeColorArgb: Int = 0xFFFFFFFF.toInt(), // putih default
    val shadowEnabled: Boolean = false,
    val shadowDx: Float = 4f,
    val shadowDy: Float = 4f,
    val shadowRadius: Float = 6f,
    val shadowColorArgb: Int = 0x80000000.toInt() // hitam transparan default
)
