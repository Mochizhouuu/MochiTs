package com.mochits.core.imaging.selection

/**
 * Penggabung seleksi murni (tanpa I/O bitmap): replace, add (union),
 * subtract, intersect. Semua diuji unit; tanpa efek samping.
 */
object SelectionCombiner {

    fun replace(current: SelectionMask, next: SelectionMask): SelectionMask {
        require(current.width == next.width && current.height == next.height) { "ukuran beda" }
        return SelectionMask(current.width, current.height, next.pixels.copyOf())
    }

    fun add(current: SelectionMask, next: SelectionMask): SelectionMask {
        require(current.width == next.width && current.height == next.height) { "ukuran beda" }
        val out = ByteArray(current.pixels.size)
        for (i in out.indices) {
            out[i] = if (current.pixels[i].toInt() != 0 || next.pixels[i].toInt() != 0) 255.toByte() else 0
        }
        return SelectionMask(current.width, current.height, out)
    }

    fun subtract(current: SelectionMask, cut: SelectionMask): SelectionMask {
        require(current.width == cut.width && current.height == cut.height) { "ukuran beda" }
        val out = ByteArray(current.pixels.size)
        for (i in out.indices) {
            out[i] = if (current.pixels[i].toInt() != 0 && cut.pixels[i].toInt() == 0) 255.toByte() else 0
        }
        return SelectionMask(current.width, current.height, out)
    }

    fun intersect(current: SelectionMask, other: SelectionMask): SelectionMask {
        require(current.width == other.width && current.height == other.height) { "ukuran beda" }
        val out = ByteArray(current.pixels.size)
        for (i in out.indices) {
            out[i] = if (current.pixels[i].toInt() != 0 && other.pixels[i].toInt() != 0) 255.toByte() else 0
        }
        return SelectionMask(current.width, current.height, out)
    }
}
