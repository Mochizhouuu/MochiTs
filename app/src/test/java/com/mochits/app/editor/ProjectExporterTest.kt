package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.app.model.ColorStop
import com.mochits.app.model.Layer
import com.mochits.app.model.TextStyleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectExporterTest {

    @Test
    fun testExportToBitmap_doesNotIncludeRedOutline() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val exporter = ProjectExporter(context)

        // Create a plain white base bitmap (as created for a new transparent/blank canvas project)
        val width = 200
        val height = 200
        val baseBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        baseBmp.eraseColor(Color.WHITE)

        val exportedBmp = exporter.exportToBitmap(baseBmp, emptyList<Layer>())

        assertNotNull(exportedBmp)
        assertEquals(width, exportedBmp.width)
        assertEquals(height, exportedBmp.height)

        // Verify border pixels (top-left, top-right, bottom-left, bottom-right corners and edges) do NOT have red color
        val topLeftPx = exportedBmp.getPixel(0, 0)
        val topRightPx = exportedBmp.getPixel(width - 1, 0)
        val bottomLeftPx = exportedBmp.getPixel(0, height - 1)
        val bottomRightPx = exportedBmp.getPixel(width - 1, height - 1)

        // Color.RED is ARGB: 0xFFFF0000 (Red=255, Green=0, Blue=0)
        assertFalse("Top-left pixel must not be red outline", topLeftPx == Color.RED)
        assertFalse("Top-right pixel must not be red outline", topRightPx == Color.RED)
        assertFalse("Bottom-left pixel must not be red outline", bottomLeftPx == Color.RED)
        assertFalse("Bottom-right pixel must not be red outline", bottomRightPx == Color.RED)

        assertEquals("Exported image pixel should be white", Color.WHITE, topLeftPx)
    }

    @Test
    fun testExportToBitmap_withGradientTextLayer() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val exporter = ProjectExporter(context)

        val baseBmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        baseBmp.eraseColor(Color.WHITE)

        val gradientStyle = TextStyleConfig(
            fontName = "Sans",
            fontSize = 48f,
            isGradientEnabled = true,
            gradientStartColor = Color.BLUE,
            gradientEndColor = Color.YELLOW,
            gradientDirection = "HORIZONTAL"
        )
        val textLayer = Layer.TextLayer(
            id = "layer_1",
            name = "Text Layer",
            x = 50f,
            y = 50f,
            text = "Export Test",
            style = gradientStyle
        )

        val exportedBmp = exporter.exportToBitmap(baseBmp, listOf(textLayer))
        assertNotNull(exportedBmp)
        assertEquals(400, exportedBmp.width)
        assertEquals(400, exportedBmp.height)
    }

    @Test
    fun testExportToBitmap_withMultiColorStopAlphaGradient() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val exporter = ProjectExporter(context)

        val baseBmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        baseBmp.eraseColor(Color.WHITE)

        val stops = listOf(
            ColorStop(color = Color.argb(255, 255, 0, 0), position = 0.0f),
            ColorStop(color = Color.argb(128, 0, 255, 0), position = 0.5f),
            ColorStop(color = Color.argb(0, 0, 0, 255), position = 1.0f)
        )
        val gradientStyle = TextStyleConfig(
            fontName = "Sans",
            fontSize = 48f,
            isGradientEnabled = true,
            gradientStops = stops,
            gradientDirection = "VERTICAL"
        )
        val textLayer = Layer.TextLayer(
            id = "layer_multi",
            name = "Multi Stop Layer",
            x = 50f,
            y = 50f,
            text = "Export Multi Stop",
            style = gradientStyle
        )

        val exportedBmp = exporter.exportToBitmap(baseBmp, listOf(textLayer))
        assertNotNull(exportedBmp)
        assertEquals(400, exportedBmp.width)
        assertEquals(400, exportedBmp.height)
    }
}
