package com.mochits.core.imaging.selection

import android.graphics.Bitmap
import com.mochits.core.imaging.NativeBridge

/**
 * Mesin seleksi Mode 1 (Connected Color Wand): validasi request,
 * flood via backend teruji, mask eksplisit stride-aman.
 */
object SelectionEngine {

    fun select(src: Bitmap?, req: WandRequest): WandResult {
        if (src == null || src.isRecycled) {
            return WandResult(null, 0L, false, "src null/recycled")
        }
        if (req.seedX !in 0 until src.width || req.seedY !in 0 until src.height) {
            return WandResult(null, 0L, false, "seed di luar")
        }
        if (req.connectivity != 4) {
            return WandResult(null, 0L, false, "connectivity!=4 belum didukung")
        }
        if (!req.fixedRange) {
            return WandResult(null, 0L, false, "floating range belum didukung")
        }
        val w = src.width
        val h = src.height
        // Linearitas slider: seed opaque -> rentang RGB (441.673),
        // seed transparan -> sertakan alpha (510.3).
        val seedAlpha = try {
            (src.getPixel(req.seedX, req.seedY) ushr 24) and 0xFF
        } catch (_: Exception) {
            255
        }
        val maxDist = if (seedAlpha >= 255) 441.673f else 510.3f
        val mapped = (req.tolerance.coerceIn(0f, 100f) / 100f) * maxDist
        // Temp mask packed: bebas masalah stride bitmap faktor.
        val tmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        } catch (t: Throwable) {
            return WandResult(null, 0L, false, "alloc: ${t.message}")
        }
        try {
            val outcome = NativeBridge.magicWandSelectSafe(src, tmp, req.seedX, req.seedY, mapped)
            if (!outcome.ok) {
                return WandResult(null, 0L, false, "flood gagal")
            }
            val packed = readPacked(tmp, w, h)
            val mask = SelectionMask(w, h, packed)
            return WandResult(mask, mask.countSelected(), true, null)
        } finally {
            try {
                tmp.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Baca bitmap (mungkin ber-stride) menjadi packed row-major. */
    fun readPacked(bmp: Bitmap, w: Int = bmp.width, h: Int = bmp.height): ByteArray {
        val rb = bmp.rowBytes
        val buf = java.nio.ByteBuffer.allocate(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)
        val raw = buf.array()
        if (rb == w) return raw.copyOf(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(raw, y * rb, out, y * w, w)
        }
        return out
    }

    /** Tulis packed ke bitmap (mungkin ber-stride); padding dibiarkan 0. */
    fun writePacked(bmp: Bitmap, packed: ByteArray) {
        val w = bmp.width
        val h = bmp.height
        require(packed.size == w * h) { "packed ${packed.size} != $w x $h" }
        val rb = bmp.rowBytes
        if (rb == w) {
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(packed))
            return
        }
        val buf = java.nio.ByteBuffer.allocate(bmp.byteCount)
        for (y in 0 until h) {
            buf.position(y * rb)
            buf.put(packed, y * w, w)
        }
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
    }
}
