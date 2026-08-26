package com.mochits.app.editor

import com.mochits.text.TextStyleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayerJsonSerializerTest {

    @Test
    fun `roundtrip mempertahankan id, posisi, dan seluruh field style`() {
        val layer = com.mochits.canvas.CanvasTextLayer(
            id = "text-123-1",
            text = "Halo\nDunia",
            xInImagePx = 12.5f,
            yInImagePx = 340f,
            fontSizeSp = 32f,
            style = TextStyleConfig(
                colorArgb = 0xFF123456.toInt(),
                isBold = true,
                strokeEnabled = true,
                strokeWidthPx = 5f,
                glowEnabled = true,
                gradientEnabled = true,
                gradientColors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
                curveEnabled = true,
                curveRadius = 180f
            )
        )

        val json = LayerJsonSerializer.serialize(listOf(layer))
        val loaded = LayerJsonSerializer.deserialize(json)

        assertEquals(1, loaded.size)
        val restored = loaded[0]
        assertEquals("text-123-1", restored.id)
        assertEquals("Halo\nDunia", restored.text)
        assertEquals(12.5f, restored.xInImagePx)
        assertEquals(340f, restored.yInImagePx)
        assertEquals(32f, restored.fontSizeSp)
        assertEquals(layer.style, restored.style)
    }

    @Test
    fun `gradient colors tidak hilang antar sesi`() {
        val style = TextStyleConfig(
            gradientEnabled = true,
            gradientColors = listOf(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt())
        )
        val layer = com.mochits.canvas.CanvasTextLayer(
            id = "g1", text = "grad", xInImagePx = 0f, yInImagePx = 0f, style = style
        )
        val restored = LayerJsonSerializer.deserialize(LayerJsonSerializer.serialize(listOf(layer)))[0]
        assertTrue(restored.style.gradientEnabled)
        assertEquals(style.gradientColors, restored.style.gradientColors)
    }

    @Test
    fun `entri JSON korup dilewati tanpa menggagalkan lainnya`() {
        val good = com.mochits.canvas.CanvasTextLayer(
            id = "ok", text = "baik", xInImagePx = 1f, yInImagePx = 2f
        )
        val validPart = LayerJsonSerializer.serialize(listOf(good)).removeSurrounding("[", "]")
        // Entri 1: tipe field salah; entri 3: field wajib "text" hilang
        val mixed = """[{"id":"rusak","xInImagePx":"bukan-angka"},$validPart,{"id":"kosong"}]"""

        val loaded = LayerJsonSerializer.deserialize(mixed)
        assertEquals(1, loaded.size)
        assertEquals("ok", loaded[0].id)
    }

    @Test
    fun `layer lama tanpa style tetap bisa dimuat dengan default`() {
        val json = """[{"id":"l1","text":"lama","xInImagePx":5,"yInImagePx":6}]"""
        val loaded = LayerJsonSerializer.deserialize(json)
        assertEquals(1, loaded.size)
        assertEquals(TextStyleConfig(), loaded[0].style)
    }
}
