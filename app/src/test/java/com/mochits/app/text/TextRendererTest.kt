package com.mochits.app.text

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.Layer
import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testScenario1_NewLongTextInOvalWithNullDimensions() {
        val paint = Paint().apply { textSize = 24f }
        val text = "Ini adalah contoh teks baru yang lumayan panjang untuk ditaruh di dalam shape oval tanpa ukuran boxHeight eksplisit"

        val result = textRenderer.layoutText(
            text = text,
            paint = paint,
            shape = TextContainerShape.OVAL,
            boxWidth = 200f,
            boxHeight = null
        )

        assertTrue("Should have multiple rendered lines", result.lines.size > 1)

        val avgLineCharCount = result.lines.map { it.text.trim().length }.average()
        assertTrue("Average line length should be reasonable (> 5 chars), got $avgLineCharCount", avgLineCharCount > 5)
    }

    @Test
    fun testScenario2And3_StretchOvalLargerThenSmaller() {
        val paint = Paint().apply { textSize = 28f }
        val text = "Pertama kali teks dimasukkan ke dalam bentuk oval lalu di-stretch menjadi lebih besar dan dikembalikan lebih kecil."

        val initialResult = textRenderer.layoutText(
            text = text, paint = paint, shape = TextContainerShape.OVAL, boxWidth = 200f, boxHeight = 150f
        )

        val largerResult = textRenderer.layoutText(
            text = text, paint = paint, shape = TextContainerShape.OVAL, boxWidth = 400f, boxHeight = 300f
        )

        val smallerResult = textRenderer.layoutText(
            text = text, paint = paint, shape = TextContainerShape.OVAL, boxWidth = 120f, boxHeight = 90f
        )

        assertTrue("Larger box should have fewer or equal lines than initial", largerResult.lines.size <= initialResult.lines.size)
        assertTrue("Smaller box should have more or equal lines than initial", smallerResult.lines.size >= initialResult.lines.size)

        for (line in smallerResult.lines) {
            val trimmed = line.text.trim()
            if (trimmed.endsWith("-")) {
                assertTrue("Hyphenated sub-word should contain valid text before hyphen", trimmed.length >= 2)
            }
        }
    }

    @Test
    fun testScenario4_SwitchingShapeRepeatedly() {
        val paint = Paint().apply { textSize = 30f }
        val text = "Contoh paragraf dengan beberapa kata panjang untuk memicu pemenggalan atau reflow yang berbeda antara box dan oval"

        val resultBox1 = textRenderer.layoutText(text, paint, TextContainerShape.BOX, 150f, 200f)
        val resultOval1 = textRenderer.layoutText(text, paint, TextContainerShape.OVAL, 150f, 200f)
        val resultBox2 = textRenderer.layoutText(text, paint, TextContainerShape.BOX, 150f, 200f)
        val resultOval2 = textRenderer.layoutText(text, paint, TextContainerShape.OVAL, 150f, 200f)

        assertEquals(resultBox1.lines, resultBox2.lines)
        assertEquals(resultOval1.lines, resultOval2.lines)
        assertFalse("Box and Oval line structures should differ due to ellipse width constraints", resultBox1.lines == resultOval1.lines)
    }

    @Test
    fun testScenario5_VeryShortAndVeryLongTextInBothShapes() {
        val paint = Paint().apply { textSize = 24f }
        val shortText = "Halo"
        val longText = "Kata1 Kata2 Kata3 Kata4 Kata5 Kata6 Kata7 Kata8 Kata9 Kata10 Kata11 Kata12 Kata13 Kata14 Kata15"

        val shortBox = textRenderer.layoutText(shortText, paint, TextContainerShape.BOX, 200f, 200f)
        val shortOval = textRenderer.layoutText(shortText, paint, TextContainerShape.OVAL, 200f, 200f)
        assertEquals(1, shortBox.lines.size)
        assertEquals(1, shortOval.lines.size)

        val longBox = textRenderer.layoutText(longText, paint, TextContainerShape.BOX, 200f, 200f)
        val longOval = textRenderer.layoutText(longText, paint, TextContainerShape.OVAL, 200f, 200f)
        assertTrue(longBox.lines.size > 1)
        assertTrue(longOval.lines.size > 1)
    }

    @Test
    fun testScenario6_AlignmentInBothShapes() {
        val paint = Paint().apply { textSize = 24f }
        val text = "Short line text"

        for (shape in listOf(TextContainerShape.BOX, TextContainerShape.OVAL)) {
            val left = textRenderer.layoutText(text, paint, shape, 300f, 200f, TextAlignment.LEFT)
            val center = textRenderer.layoutText(text, paint, shape, 300f, 200f, TextAlignment.CENTER)
            val right = textRenderer.layoutText(text, paint, shape, 300f, 200f, TextAlignment.RIGHT)

            assertTrue(left.lines[0].xOffset < center.lines[0].xOffset)
            assertTrue(center.lines[0].xOffset < right.lines[0].xOffset)
        }
    }

    @Test
    fun testScenario7_ExplicitContainerBoundingBox() {
        val style = TextStyleConfig(fontSize = 36f, alignment = TextAlignment.CENTER)
        val ovalLayer = Layer.TextLayer(
            id = "oval1", name = "Oval Text", text = "Hi",
            style = style, textContainerShape = TextContainerShape.OVAL, boxWidth = 300f, boxHeight = 200f
        )

        val bounds = textRenderer.getTextBounds(ovalLayer)
        assertEquals("Container width should be explicitly 300px", 300f, bounds.width(), 0.01f)
        assertEquals("Container height should be explicitly 200px", 200f, bounds.height(), 0.01f)
    }

    @Test
    fun testHyphenation_OnLongWordExceedingWidth() {
        val paint = Paint().apply { textSize = 30f }
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
            val rad = Math.toRadians(angle.toDouble())
            val cosA = Math.cos(rad)
            val sinA = Math.sin(rad)
            val dx = (topHandleUnrotated.x - textCenterX).toDouble()
            val dy = (topHandleUnrotated.y - textCenterY).toDouble()

            val rotatedTouchPt = Offset(
                (textCenterX + dx * cosA - dy * sinA).toFloat(),
                (textCenterY + dx * sinA + dy * cosA).toFloat()
            )

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
    fun testBoxVerticalStretch_updatesHeightWithoutForcingWidthWrap() {
        val style = TextStyleConfig(fontSize = 30f, alignment = TextAlignment.LEFT)
        val text = "A long single line of text that should remain unconstrained horizontally"

        val layerInitial = Layer.TextLayer(
            id = "t1", name = "Text", text = text, style = style,
            textContainerShape = TextContainerShape.BOX, boxWidth = null, boxHeight = null
        )
        val initialLayout = textRenderer.layoutText(
            text = text, paint = Paint().apply { textSize = 30f },
            shape = TextContainerShape.BOX, boxWidth = null, boxHeight = null
        )

        assertEquals("Should be single line", 1, initialLayout.lines.size)

        val stretchedHeight = 300f
        val layerStretched = layerInitial.copy(boxHeight = stretchedHeight)

        val stretchedLayout = textRenderer.layoutText(
            text = text, paint = Paint().apply { textSize = 30f },
            shape = TextContainerShape.BOX, boxWidth = null, boxHeight = stretchedHeight
        )
        val stretchedBounds = textRenderer.getTextBounds(layerStretched)

        assertEquals("Line count should remain 1", 1, stretchedLayout.lines.size)
        assertEquals("Container width should equal initial natural width", initialLayout.containerWidth, stretchedLayout.containerWidth, 0.01f)

        assertEquals("Bounds height should reflect explicit boxHeight", stretchedHeight, stretchedBounds.height(), 0.01f)
        assertEquals("Bounds bottom should equal y + boxHeight", layerStretched.y + stretchedHeight, stretchedBounds.bottom, 0.01f)
    }

    @Test
    fun testPerformanceAndMemory_ReflowBoxVsOval() {
        val paint = Paint().apply { textSize = 28f }
        val text = "Teks ini digunakan untuk menguji performa reflow dan layout pada shape BOX dan OVAL berulang kali selama gesture drag"

        textRenderer.layoutText(text, paint, TextContainerShape.BOX, 200f, 300f)
        textRenderer.layoutText(text, paint, TextContainerShape.OVAL, 200f, 300f)

        val iterations = 1000

        val boxStartTime = System.nanoTime()
        repeat(iterations) {
            textRenderer.layoutText(text, paint, TextContainerShape.BOX, 200f + (it % 10), 300f + (it % 10))
        }

        val ovalStartTime = System.nanoTime()
        repeat(iterations) {
            textRenderer.layoutText(text, paint, TextContainerShape.OVAL, 200f + (it % 10), 300f + (it % 10))
        }
        val ovalDurationMs = (System.nanoTime() - ovalStartTime) / 1_000_000.0

        assertTrue("OVAL layout performance should be fast (< 200ms for 1000 calls), took ${ovalDurationMs}ms", ovalDurationMs < 200.0)
    }

    @Test
    fun testSyllableBasedHyphenation_IndonesianLongWord() {
        val paint = Paint().apply { textSize = 30f }
        val longWord = "pertanggungjawaban"

        // Force a narrow box where the full word cannot fit on line 1
        val result = textRenderer.layoutText(
            text = longWord,
            paint = paint,
            shape = TextContainerShape.BOX,
            boxWidth = 180f,
            boxHeight = 300f
        )

        assertTrue("Long word should be broken into multiple lines", result.lines.size > 1)
        val line1 = result.lines[0].text
        assertTrue("First line should end with hyphen -", line1.endsWith("-"))

        val prefix = line1.removeSuffix("-")
        val validSyllablePrefixes = listOf("per", "pertang", "pertanggung", "pertanggungja", "pertanggungjawa")
        assertTrue("Hyphenation prefix should be a valid Indonesian syllable boundary, was '$prefix'", prefix.lowercase() in validSyllablePrefixes)
    }

    @Test
    fun testAutoReflow_WhenTextContentChangesWithBoxWidth() {
        val paint = Paint().apply { textSize = 24f }
        val initialText = "Short text"
        val longText = "This is a much longer text sequence that should automatically wrap into multiple lines when boxWidth is specified"

        val initialResult = textRenderer.layoutText(initialText, paint, TextContainerShape.BOX, 200f, 300f)
        val longResult = textRenderer.layoutText(longText, paint, TextContainerShape.BOX, 200f, 300f)

        assertEquals("Initial short text should fit in 1 line", 1, initialResult.lines.size)
        assertTrue("Long text should automatically reflow into multiple lines", longResult.lines.size > 1)
        assertEquals(200f, longResult.containerWidth, 0.01f)
    }
}
