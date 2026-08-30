package com.mochits.app.editor

import android.graphics.Color
import com.mochits.app.model.ColorStop
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
            gradientAngle = 90f
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
        assertEquals(90f, layer.style.gradientAngle, 0.001f)
        assertEquals(2, layer.style.gradientStops.size)
        assertEquals(0f, layer.style.gradientStops[0].position, 0.001f)
        assertEquals(1f, layer.style.gradientStops[1].position, 0.001f)
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
                  "textColor": -16777216,
                  "gradientStartColor": -65536,
                  "gradientEndColor": -16776961
                }
              }
            ]
        """.trimIndent()

        val deserialized = serializer.deserialize(legacyJson)
        assertEquals(1, deserialized.size)
        val layer = deserialized[0] as Layer.TextLayer
        assertEquals("Old Project", layer.text)
        assertFalse(layer.style.isGradientEnabled)
        assertEquals(2, layer.style.gradientStops.size)
        assertEquals(Color.RED, layer.style.gradientStops[0].color)
        assertEquals(0f, layer.style.gradientStops[0].position, 0.001f)
        assertEquals(Color.BLUE, layer.style.gradientStops[1].color)
        assertEquals(1f, layer.style.gradientStops[1].position, 0.001f)
        assertEquals(0f, layer.style.gradientAngle, 0.001f)
    }

    @Test
    fun testSerializeAndDeserializeMultiColorStops() {
        val stops = listOf(
            ColorStop(color = Color.RED, position = 0.0f),
            ColorStop(color = Color.GREEN, position = 0.4f),
            ColorStop(color = Color.BLUE, position = 1.0f)
        )
        val style = TextStyleConfig(
            fontName = "Sans",
            fontSize = 36f,
            isGradientEnabled = true,
            gradientStops = stops
        )
        val textLayer = Layer.TextLayer(
            id = "multi_stop_layer",
            name = "Multi Stop",
            text = "3 Colors",
            style = style
        )

        val json = serializer.serialize(listOf(textLayer))
        val deserialized = serializer.deserialize(json)

        assertEquals(1, deserialized.size)
        val layer = deserialized[0] as Layer.TextLayer
        assertEquals(3, layer.style.gradientStops.size)
        assertEquals(Color.GREEN, layer.style.gradientStops[1].color)
        assertEquals(0.4f, layer.style.gradientStops[1].position, 0.001f)
    }

    @Test
    fun testSerializeAndDeserializeGradientAngle() {
        val style = TextStyleConfig(
            isGradientEnabled = true,
            gradientAngle = 135f
        )
        val textLayer = Layer.TextLayer(
            id = "angle_layer",
            name = "Angle Layer",
            text = "Angle 135",
            style = style
        )

        val json = serializer.serialize(listOf(textLayer))
        val deserialized = serializer.deserialize(json)

        assertEquals(1, deserialized.size)
        val layer = deserialized[0] as Layer.TextLayer
        assertEquals(135f, layer.style.gradientAngle, 0.001f)
    }

    @Test
    fun testDeserializeLegacyJsonWithVerticalDirection() {
        val legacyVerticalJson = """
            [
              {
                "id": "legacy_v",
                "type": "TEXT",
                "name": "Legacy Vertical",
                "x": 0.0,
                "y": 0.0,
                "rotation": 0.0,
                "scaleX": 1.0,
                "scaleY": 1.0,
                "opacity": 1.0,
                "isVisible": true,
                "isLocked": false,
                "text": "Vertical Text",
                "style": {
                  "fontName": "Default",
                  "fontSize": 36.0,
                  "isGradientEnabled": true,
                  "gradientDirection": "VERTICAL"
                }
              }
            ]
        """.trimIndent()

        val deserialized = serializer.deserialize(legacyVerticalJson)
        assertEquals(1, deserialized.size)
        val layer = deserialized[0] as Layer.TextLayer
        assertEquals(90f, layer.style.gradientAngle, 0.001f)
    }
}
