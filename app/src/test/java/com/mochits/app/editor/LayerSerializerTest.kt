package com.mochits.app.editor

import android.graphics.Color
import com.mochits.app.model.Layer
import com.mochits.app.model.TextStyleConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerSerializerTest {

    private val serializer = LayerSerializer()

    @Test
    fun testSerializeAndDeserializeWithGradient() {
        val style = TextStyleConfig(
            fontName = "Sans",
            fontStyle = "Bold",
            fontSize = 42f,
            isGradientEnabled = true,
            gradientStartColor = Color.RED,
            gradientEndColor = Color.BLUE,
            gradientDirection = "VERTICAL"
        )
        val textLayer = Layer.TextLayer(
            id = "test_layer",
            name = "Text 1",
            text = "Hello World",
            style = style
        )

        val json = serializer.serialize(listOf(textLayer))
        val deserialized = serializer.deserialize(json)

        assertEquals(1, deserialized.size)
        val layer = deserialized[0] as Layer.TextLayer
        assertEquals("Hello World", layer.text)
        assertTrue(layer.style.isGradientEnabled)
        assertEquals(Color.RED, layer.style.gradientStartColor)
        assertEquals(Color.BLUE, layer.style.gradientEndColor)
        assertEquals("VERTICAL", layer.style.gradientDirection)
    }

    @Test
    fun testDeserializeLegacyJsonWithoutGradientFields() {
        val legacyJson = """
            [
              {
                "id": "legacy_1",
                "type": "TEXT",
                "name": "Legacy Layer",
                "x": 0.0,
                "y": 0.0,
                "rotation": 0.0,
                "scaleX": 1.0,
                "scaleY": 1.0,
                "opacity": 1.0,
                "isVisible": true,
                "isLocked": false,
                "text": "Old Project",
                "style": {
                  "fontName": "Default",
                  "fontSize": 36.0,
                  "textColor": -16777216
                }
              }
            ]
        """.trimIndent()

        val deserialized = serializer.deserialize(legacyJson)
        assertEquals(1, deserialized.size)
        val layer = deserialized[0] as Layer.TextLayer
        assertEquals("Old Project", layer.text)
        assertFalse(layer.style.isGradientEnabled)
        assertEquals(Color.BLACK, layer.style.gradientStartColor)
        assertEquals(Color.WHITE, layer.style.gradientEndColor)
        assertEquals("HORIZONTAL", layer.style.gradientDirection)
    }
}
