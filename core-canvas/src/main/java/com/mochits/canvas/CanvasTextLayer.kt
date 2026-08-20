package com.mochits.canvas

/**
 * Representasi text layer yang bisa diposisikan bebas di atas canvas
 * (koordinat relatif terhadap base image, dalam skala 0f..1f agar tetap
 * konsisten meski canvas di-zoom/di-pan atau ukuran layar berbeda).
 * Efek teks (stroke, shadow, dll) akan ditambahkan di :core-text pada
 * tahap berikutnya — untuk sekarang baru teks polos.
 */
data class CanvasTextLayer(
    val id: String,
    val text: String,
    val relativeX: Float,
    val relativeY: Float,
    val fontSizeSp: Float = 24f
)
