@file:JvmName("RustNative")

package com.mochits.app.imaging

import com.mochits.app.model.TexturePreset

/**
 * Jembatan ke library Rust (`libmochi_image.so`).
 *
 * Fungsi external sengaja TOP-LEVEL (bukan member object) supaya menjadi
 * static method `RustNative.applyTexture` — JNI tidak menerima argumen `this`.
 * Nama symbol Rust: Java_com_mochits_app_imaging_RustNative_applyTexture.
 *
 * [RustBridge.available] aman di JVM/Robolectric: library tidak ada → caller
 * WAJIB fallback ke implementasi Kotlin.
 */
val available: Boolean = try {
    System.loadLibrary("mochi_image")
    true
} catch (t: Throwable) {
    false
}

/** Kode preset harus sinkron dengan `texture.rs` di crate Rust. */
fun presetCode(preset: TexturePreset): Int = when (preset) {
    TexturePreset.NONE -> 0
    TexturePreset.GRAIN -> 1
    TexturePreset.PAPER -> 2
    TexturePreset.CANVAS -> 3
    TexturePreset.HALFTONE -> 4
    TexturePreset.STRIPES -> 5
    TexturePreset.DOTS -> 6
    TexturePreset.CHEVRON -> 7
    TexturePreset.BRICKS -> 8
    TexturePreset.CUSTOM -> 9
}

external fun applyTexture(
    src: IntArray,
    width: Int,
    height: Int,
    preset: Int,
    strength: Float,
    scale: Float,
    tint: Int,
    angleDeg: Float
): IntArray

/** Penanda ketersediaan core Rust (dipakai facade di ImageEffects). */
object RustBridge {
    val available: Boolean get() = com.mochits.app.imaging.available
    fun presetCode(preset: TexturePreset): Int = com.mochits.app.imaging.presetCode(preset)
}
