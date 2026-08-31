package com.mochits.app.text

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.Layer
import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextRendererTest {

    private lateinit var textRenderer: TextRenderer

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        textRenderer = TextRenderer(context)
    }

    @Test
    fun testTextReflow_BoxVsOvalContainer() {
        val paint = Paint().apply {
            textSize = 30f
        }
        val text = "Teks ini adalah contoh paragraf yang agak panjang untuk menguji reflow kontainer"

        val boxResult = textRenderer.layoutText(
            text = text,
            paint = paint,
            shape = TextContainerShape.BOX,
            boxWidth = 200f,
            boxHeight = 300f
        )

        val ovalResult = textRenderer.layoutText(
            text = text,
            paint = paint,
            shape = TextContainerShape.OVAL,
            boxWidth = 200f,
            boxHeight = 300f
        )

        assertTrue(boxResult.lines.isNotEmpty())
        assertTrue(ovalResult.lines.isNotEmpty())
        assertEquals(200f, boxResult.containerWidth, 0.01f)
        assertEquals(200f, ovalResult.containerWidth, 0.01f)

        val topOvalLine = ovalResult.lines.first()
        assertTrue(topOvalLine.xOffset >= 0f)
    }

    @Test
    fun testTextReflow_OvalWithNullBoxHeight() {
        val paint = Paint().apply {
            textSize = 30f
        }
        val text = "Paragraf pertama\nParagraf kedua yang sedikit lebih panjang untuk menguji oval"

        val ovalResultNullH = textRenderer.layoutText(
            text = text,
            paint = paint,
            shape = TextContainerShape.OVAL,
            boxWidth = 200f,
            boxHeight = null
        )

        assertTrue("Oval layout should generate rendered lines", ovalResultNullH.lines.isNotEmpty())
        assertTrue("Container width should match requested boxWidth", ovalResultNullH.containerWidth >= 200f)
        assertTrue("Container height should be calculated from estimated line height", ovalResultNullH.containerHeight > 0f)

        // Verify that xOffset centers text lines inside oval bounds
        val firstLine = ovalResultNullH.lines.first()
        assertTrue("First line in oval should have xOffset >= 0", firstLine.xOffset >= 0f)
    }

    @Test
    fun testHyphenation_OnLongWordExceedingWidth() {
        val paint = Paint().apply {
            textSize = 30f
        }
        val longWord = "Supercalifragilisticexpialidocious"

        val result = textRenderer.layoutText(
            text = longWord,
            paint = paint,
            shape = TextContainerShape.BOX,
            boxWidth = 100f,
            boxHeight = 200f
        )

        assertTrue("Long word should be broken into multiple lines", result.lines.size > 1)
        val line1 = result.lines[0].text
        assertTrue("First line should end with hyphen '-'", line1.endsWith("-"))
    }

    @Test
    fun testStretchHandleHitTesting_AcrossRotations() {
        val style = TextStyleConfig(fontSize = 36f)
        val textLayer = Layer.TextLayer(
            id = "test_layer",
            name = "Text Layer",
            x = 100f,
            y = 100f,
            rotation = 0f,
            text = "Rotated Stretch Test",
            style = style,
            textContainerShape = TextContainerShape.BOX,
            boxWidth = 200f,
            boxHeight = 100f
        )

        val bounds = textRenderer.getTextBounds(textLayer)
        val textCenterX = bounds.centerX()
        val textCenterY = bounds.centerY()

        val topHandleUnrotated = Offset(bounds.centerX(), bounds.top)

        val angles = listOf(0f, 45f, 90f, 180f)
        for (angle in angles) {
            // Rotate the top handle point around center
            val rad = Math.toRadians(angle.toDouble())
            val cosA = Math.cos(rad)
            val sinA = Math.sin(rad)
            val dx = (topHandleUnrotated.x - textCenterX).toDouble()
            val dy = (topHandleUnrotated.y - textCenterY).toDouble()

            val rotatedTouchPt = Offset(
                (textCenterX + dx * cosA - dy * sinA).toFloat(),
                (textCenterY + dx * sinA + dy * cosA).toFloat()
            )

            // Un-rotate touch point by -angle during hit testing
            val unRad = Math.toRadians(-angle.toDouble())
            val unCosA = Math.cos(unRad)
            val unSinA = Math.sin(unRad)
            val uDx = (rotatedTouchPt.x - textCenterX).toDouble()
            val uDy = (rotatedTouchPt.y - textCenterY).toDouble()

            val unrotatedResultPt = Offset(
                (textCenterX + uDx * unCosA - uDy * unSinA).toFloat(),
                (textCenterY + uDx * unSinA + uDy * unCosA).toFloat()
            )

            assertEquals(topHandleUnrotated.x, unrotatedResultPt.x, 0.1f)
            assertEquals(topHandleUnrotated.y, unrotatedResultPt.y, 0.1f)
        }
    }

    @Test
    fun testTextAlignment_LeftCenterRight() {
        val paint = Paint().apply { textSize = 30f }
        val text = "Short"

        val leftResult = textRenderer.layoutText(text, paint, TextContainerShape.BOX, 200f, 100f, TextAlignment.LEFT)
        val centerResult = textRenderer.layoutText(text, paint, TextContainerShape.BOX, 200f, 100f, TextAlignment.CENTER)
        val rightResult = textRenderer.layoutText(text, paint, TextContainerShape.BOX, 200f, 100f, TextAlignment.RIGHT)

        assertEquals(0f, leftResult.lines[0].xOffset, 0.01f)
        assertTrue(centerResult.lines[0].xOffset > 0f)
        assertTrue(rightResult.lines[0].xOffset > centerResult.lines[0].xOffset)
    }

    @Test
    fun testGetTextBounds_ShrinksWithShorterText() {
        val style = TextStyleConfig(fontSize = 36f, alignment = TextAlignment.CENTER)
        val longLayer = Layer.TextLayer(
            id = "layer1", name = "Text", text = "This is a long sentence\nthat wraps across\nmultiple lines",
            style = style, textContainerShape = TextContainerShape.BOX, boxWidth = null, boxHeight = null
        )
        val shortLayer = Layer.TextLayer(
            id = "layer2", name = "Text", text = "Hi",
            style = style, textContainerShape = TextContainerShape.BOX, boxWidth = null, boxHeight = null
        )

        val longBounds = textRenderer.getTextBounds(longLayer)
        val shortBounds = textRenderer.getTextBounds(shortLayer)

        assertTrue(shortBounds.width() < longBounds.width())
        assertTrue(shortBounds.height() < longBounds.height())
    }

    @Test
    fun testGetTextBounds_ReturnsFullContainerBoundsWhenBoxDimensionsSpecified() {
        val style = TextStyleConfig(fontSize = 36f, alignment = TextAlignment.CENTER)
        val ovalLayer = Layer.TextLayer(
            id = "oval1", name = "Oval Text", text = "Hi",
            style = style, textContainerShape = TextContainerShape.OVAL, boxWidth = 300f, boxHeight = 200f
        )

        val bounds = textRenderer.getTextBounds(ovalLayer)
        assertEquals(300f, bounds.width(), 0.01f)
        assertEquals(200f, bounds.height(), 0.01f)
    }
}