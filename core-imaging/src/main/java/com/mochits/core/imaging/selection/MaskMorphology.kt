package com.mochits.core.imaging.selection

import kotlin.math.max
import kotlin.math.min

/**
 * Morfologi mask murni (tanpa I/O bitmap): expand (dilate), contract
 * (erode), feather (rerata box 3x3, sekali pass per level).
 */
object MaskMorphology {

    fun dilate(src: SelectionMask, radius: Int): SelectionMask {
        if (radius <= 0) return SelectionMask(src.width, src.height, src.pixels.copyOf())
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        val r2 = radius * radius
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (src.pixels[y * w + x].toInt() == 0) continue
                val minY = max(0, y - radius)
                val maxY = min(h - 1, y + radius)
                val minX = max(0, x - radius)
                val maxX = min(w - 1, x + radius)
                for (ny in minY..maxY) {
                    val dy = ny - y
                    val dy2 = dy * dy
                    for (nx in minX..maxX) {
                        val dx = nx - x
                        if (dx * dx + dy2 <= r2) out[ny * w + nx] = 255.toByte()
                    }
                }
            }
        }
        return SelectionMask(w, h, out)
    }

    fun erode(src: SelectionMask, radius: Int): SelectionMask {
        if (radius <= 0) return SelectionMask(src.width, src.height, src.pixels.copyOf())
        val w = src.width
        val h = src.height
        val out = ByteArray(w * h)
        val r2 = radius * radius
        for (y in 0 until h) {
            for (x in 0 until w) {
                var keep = true
                outer@ for (ny in max(0, y - radius)..min(h - 1, y + radius)) {
                    val dy = ny - y
                    for (nx in max(0, x - radius)..min(w - 1, x + radius)) {
                        val dx = nx - x
                        if (dx * dx + dy * dy <= r2 && src.pixels[ny * w + nx].toInt() == 0) {
                            keep = false
                            break@outer
                        }
                    }
                }
                out[y * w + x] = if (keep) 255.toByte() else 0
            }
        }
        return SelectionMask(w, h, out)
    }

    /** Feather: rerata box 3x3 diulang [levels] kali (tepi bergradasi). */
    fun feather(src: SelectionMask, levels: Int): SelectionMask {
        if (levels <= 0) return SelectionMask(src.width, src.height, src.pixels.copyOf())
        val w = src.width
        val h = src.height
        var cur = IntArray(w * h) { if (src.pixels[it].toInt() != 0) 255 else 0 }
        repeat(levels) {
            val nxt = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0
                    var n = 0
                    for (jy in max(0, y - 1)..min(h - 1, y + 1)) {
                        for (jx in max(0, x - 1)..min(w - 1, x + 1)) {
                            sum += cur[jy * w + jx]
                            n++
                        }
                    }
                    nxt[y * w + x] = sum / n
                }
            }
            cur = nxt
        }
        // Kembalikan biner (>=128) agar kontrak mask tetap 0/255.
        val out = ByteArray(w * h) { if (cur[it] >= 128) 255.toByte() else 0 }
        return SelectionMask(w, h, out)
    }
}
