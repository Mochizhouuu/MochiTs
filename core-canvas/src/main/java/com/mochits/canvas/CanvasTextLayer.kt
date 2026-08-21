package com.mochits.canvas

import com.mochits.text.TextStyleConfig

/**
 * Representasi text layer di atas canvas. Posisi ([xInImagePx],
 * [yInImagePx]) disimpan dalam KOORDINAT PIKSEL GAMBAR ASLI (bukan
 * layar/viewport) — ini yang membuat teks tetap menempel benar pada
 * bagian gambar yang dimaksud meski gambar di-scroll atau di-zoom.
 * [style] mengatur warna, stroke, dan shadow — dirender via
 * StyledText di :core-text.
 */
data class CanvasTextLayer(
    val id: String,
    val text: String,
    val xInImagePx: Float,
    val yInImagePx: Float,
    val fontSizeSp: Float = 24f,
    val style: TextStyleConfig = TextStyleConfig()
)
