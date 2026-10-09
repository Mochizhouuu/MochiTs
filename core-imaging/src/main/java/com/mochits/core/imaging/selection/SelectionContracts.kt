package com.mochits.core.imaging.selection

/**
 * Permintaan seleksi eksplisit (kontrak mesin, bukan parameter lepas).
 * Nilai dalam koordinat piksel gambar; toleransi skala UI 0..100.
 */
data class WandRequest(
    val seedX: Int,
    val seedY: Int,
    /** Skala UI 0..100, dipetakan mesin ke jarak warna. */
    val tolerance: Float,
    /** 4 (didukung). 8 belum diimplementasikan. */
    val connectivity: Int = 4,
    /** true = banding ke seed (tanpa drift); false belum didukung. */
    val fixedRange: Boolean = true,
    /** Ambang gradien luminance untuk dinding tepi (0 = mati). */
    val edgeSensitivity: Int = 100
)

/**
 * Mask hasil seleksi: packed row-major (tanpa stride), 0 = lepas,
 * 255 = terpilih. Satu-satunya sumber kebenaran antar alat.
 */
data class SelectionMask(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
) {
    init {
        require(pixels.size == width * height) { "SelectionMask: ${pixels.size} != $width x $height" }
    }

    fun countSelected(): Long {
        var n = 0L
        for (b in pixels) if (b.toInt() != 0) n++
        return n
    }

    companion object {
        fun empty(w: Int, h: Int) = SelectionMask(w, h, ByteArray(w * h))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelectionMask) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var r = width
        r = 31 * r + height
        r = 31 * r + pixels.contentHashCode()
        return r
    }
}

/**
 * Hasil mesin seleksi: mask eksplisit + hitungan + status.
 * Gagal → mask null + error terisi (tak pernah sunyi).
 */
data class WandResult(
    val mask: SelectionMask?,
    val selectedPixels: Long,
    val success: Boolean,
    val error: String? = null
)
