package com.mochits.canvas

/**
 * Representasi layer non-destruktif: base image, mask, dan text layer
 * tidak pernah memodifikasi bitmap sumber secara langsung — setiap
 * perubahan state memicu render ulang via Coroutine Flow.
 */
sealed class Layer {
    abstract val id: String
    abstract val isVisible: Boolean

    data class BaseImage(
        override val id: String,
        val sourcePath: String,
        override val isVisible: Boolean = true
    ) : Layer()

    data class Mask(
        override val id: String,
        val maskPath: String,
        override val isVisible: Boolean = true
    ) : Layer()

    data class TextLayer(
        override val id: String,
        val text: String,
        val stylePresetId: String?,
        override val isVisible: Boolean = true
    ) : Layer()
}
