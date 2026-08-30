package com.mochits.app.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.mochits.app.model.ColorStop
import com.mochits.app.model.TextStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextRendererTest {

    @Test
    fun testDrawStyledTextWithSolidColor() {
        val context = RuntimeEnvironment.getApplication()
        val textRenderer = TextRenderer(context)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val style = TextStyleConfig(
            fontName = "Sans",
            fontSize = 32f,
            textColor = Color.RED,
            strokeColor = Color.BLACK,
            strokeWidth = 2f
        )

        textRenderer.drawStyledText(canvas, "Sample", style, 10f, 10f)
        assertNotNull(bitmap)
    }

    @Test
    fun testDrawStyledTextWithHorizontalGradient() {
        val context = RuntimeEnvironment.getApplication()
        val textRenderer = TextRenderer(context)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val style = TextStyleConfig(
            fontName = "Serif",
            fontSize = 36f,
            isGradientEnabled = true,
            gradientStartColor = Color.BLUE,
            gradientEndColor = Color.YELLOW,
            gradientAngle = 0f
        )

        textRenderer.drawStyledText(canvas, "Gradient Text", style, 10f, 10f)
        assertNotNull(bitmap)
    }

    @Test
    fun testDrawStyledTextWithVerticalGradient() {
        val context = RuntimeEnvironment.getApplication()
        val textRenderer = TextRenderer(context)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val style = TextStyleConfig(
            fontName = "Monospace",
            fontSize = 36f,
            isGradientEnabled = true,
            gradientStartColor = Color.MAGENTA,
            gradientEndColor = Color.CYAN,
            gradientAngle = 90f
        )

        textRenderer.drawStyledText(canvas, "Vertical Gradient", style, 10f, 10f)
        assertNotNull(bitmap)
    }

    @Test
    fun testDrawStyledTextWithMultiColorStopsAndAlpha() {
        val context = RuntimeEnvironment.getApplication()
        val textRenderer = TextRenderer(context)
        val bitmap = Bitmap.createBitmap(300, 150, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val stops = listOf(
            ColorStop(color = Color.argb(255, 255, 0, 0), position = 0.0f),
            ColorStop(color = Color.argb(128, 0, 255, 0), position = 0.5f),
            ColorStop(color = Color.argb(0, 0, 0, 255), position = 1.0f)
        )

        val style = TextStyleConfig(
            fontName = "Sans",
            fontSize = 40f,
            isGradientEnabled = true,
            gradientStops = stops,
            gradientAngle = 45f
        )

        textRenderer.drawStyledText(canvas, "Multi Stop Gradient", style, 10f, 10f)
        assertNotNull(bitmap)
    }

    @Test
    fun testCalculateGradientPointsForVariousAngles() {
        val style0 = TextStyleConfig(gradientAngle = 0f)
        val pts0 = style0.calculateGradientPoints(0f, 0f, 100f, 50f)
        assertEquals(0f, pts0[0], 0.001f)
        assertEquals(25f, pts0[1], 0.001f)
        assertEquals(100f, pts0[2], 0.001f)
        assertEquals(25f, pts0[3], 0.001f)

        val style90 = TextStyleConfig(gradientAngle = 90f)
        val pts90 = style90.calculateGradientPoints(0f, 0f, 100f, 50f)
        assertEquals(50f, pts90[0], 0.001f)
        assertEquals(0f, pts90[1], 0.001f)
        assertEquals(50f, pts90[2], 0.001f)
        assertEquals(50f, pts90[3], 0.001f)

        val style180 = TextStyleConfig(gradientAngle = 180f)
        val pts180 = style180.calculateGradientPoints(0f, 0f, 100f, 50f)
        assertEquals(100f, pts180[0], 0.001f)
        assertEquals(25f, pts180[1], 0.001f)
        assertEquals(0f, pts180[2], 0.001f)
        assertEquals(25f, pts180[3], 0.001f)

        val style270 = TextStyleConfig(gradientAngle = 270f)
        val pts270 = style270.calculateGradientPoints(0f, 0f, 100f, 50f)
        assertEquals(50f, pts270[0], 0.001f)
        assertEquals(50f, pts270[1], 0.001f)
        assertEquals(50f, pts270[2], 0.001f)
        assertEquals(0f, pts270[3], 0.001f)

        val style45 = TextStyleConfig(gradientAngle = 45f)
        val pts45 = style45.calculateGradientPoints(0f, 0f, 100f, 100f)
        assertEquals(0f, pts45[0], 0.01f)
        assertEquals(0f, pts45[1], 0.01f)
        assertEquals(100f, pts45[2], 0.01f)
        assertEquals(100f, pts45[3], 0.01f)
    }
}
