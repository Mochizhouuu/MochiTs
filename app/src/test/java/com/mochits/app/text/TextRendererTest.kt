package com.mochits.app.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.mochits.app.model.TextStyleConfig
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
            gradientDirection = "HORIZONTAL"
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
            gradientDirection = "VERTICAL"
        )

        textRenderer.drawStyledText(canvas, "Vertical Gradient", style, 10f, 10f)
        assertNotNull(bitmap)
    }
}
