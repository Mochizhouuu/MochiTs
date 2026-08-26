package com.mochits.app.imaging

import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.MaskToolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskSelectionToolsTest {

    @Test
    fun `brush stroke updates mask bitmap correctly`() {
        val tools = MaskSelectionTools(100, 100)
        tools.startStroke(Offset(50f, 50f), MaskToolMode.BRUSH, 20f)
        tools.updateStroke(Offset(60f, 50f), MaskToolMode.BRUSH, 20f)
        tools.endStroke(Offset(60f, 50f), MaskToolMode.BRUSH, 20f)

        val pixel = tools.maskBitmap.getPixel(55, 50)
        val alpha = pixel ushr 24
        assertTrue("Pixel at stroke location should be masked (alpha > 0)", alpha > 0)
    }

    @Test
    fun `clearMask resets mask bitmap`() {
        val tools = MaskSelectionTools(100, 100)
        tools.startStroke(Offset(50f, 50f), MaskToolMode.BRUSH, 20f)
        tools.clearMask()

        val alpha = tools.maskBitmap.getPixel(50, 50) ushr 24
        assertEquals("Mask pixel should be cleared (alpha == 0)", 0, alpha)
    }
}
