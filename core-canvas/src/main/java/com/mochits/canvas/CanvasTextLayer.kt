package com.mochits.canvas

/**
 * Representasi text layer di atas canvas. Posisi ([xInImagePx],
 * [yInImagePx]) disimpan dalam KOORDINAT PIKSEL GAMBAR ASLI (bukan
 * layar/viewport) — ini yang membuat teks tetap menempel benar pada
 * bagian gambar yang dimaksud meski gambar di-scroll atau di-zoom.
 * Efek teks (stroke, shadow, dll) akan ditambahkan di :core-text pada
 * tahap berikutnya — untuk sekarang baru teks polos.
 */
data class CanvasTextLayer(
    val id: String,
    val text: String,
    val xInImagePx: Float,
    val yInImagePx: Float,
    val fontSizeSp: Float = 24f
)
