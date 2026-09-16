package com.mochits.app.canvas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasEditorStateTest {

    @Test
    fun `fitToWidth centers tapped point at viewport middle`() {
        val state = CanvasEditorState()
        // Viewport 1080x2400, gambar tinggi 1080x4000 -> skala 1.0.
        state.fitToWidth(
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            imageWidth = 1080f,
            imageHeight = 4000f,
            focusCanvasY = 2000f
        )
        assertEquals(1f, state.scale, 0.001f)
        assertEquals(0f, state.offsetX, 0.001f)
        // Titik kanvas Y=2000 harus jatuh di tengah layar Y=1200.
        val screenY = 2000f * state.scale + state.offsetY
        assertEquals(1200f, screenY, 0.5f)
    }

    @Test
    fun `fitToWidth centers whole image when it fits viewport`() {
        val state = CanvasEditorState()
        // Gambar 1080x1000 muat di viewport 1080x2400; fokus diabaikan.
        state.fitToWidth(
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            imageWidth = 1080f,
            imageHeight = 1000f,
            focusCanvasY = 900f
        )
        assertEquals(1f, state.scale, 0.001f)
        assertEquals((2400f - 1000f) / 2f, state.offsetY, 0.001f)
    }

    @Test
    fun `fitToWidth clamps focus near bottom edge`() {
        val state = CanvasEditorState()
        // Ketuk dekat bawah (Y=3900); gambar tidak boleh lewat batas bawah layar.
        state.fitToWidth(
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            imageWidth = 1080f,
            imageHeight = 4000f,
            focusCanvasY = 3900f
        )
        // offsetY minimum = viewport - tinggi gambar = -1600.
        assertEquals(2400f - 4000f, state.offsetY, 0.5f)
    }

    @Test
    fun `fitToWidth fits image width to viewport`() {
        val state = CanvasEditorState()
        // Gambar 2000x2000 di viewport 1080x2400 -> skala 0.54, X terpusat.
        state.fitToWidth(
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            imageWidth = 2000f,
            imageHeight = 2000f,
            focusCanvasY = null
        )
        assertEquals(0.54f, state.scale, 0.001f)
        val screenCenterX = 1000f * state.scale + state.offsetX
        assertEquals(540f, screenCenterX, 0.5f)
    }
}
