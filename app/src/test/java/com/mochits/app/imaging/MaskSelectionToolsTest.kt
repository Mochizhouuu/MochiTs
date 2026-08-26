package com.mochits.app.imaging

import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.MaskToolMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaskSelectionToolsTest {

    @Test
    fun `brush stroke updates mask bitmap correctly`() {
        val tools = MaskSelectionTools(100, 100)
        tools.startStroke(Offset(50f, 50f), MaskToolMode.BRUSH, 20f)
        tools.updateStroke(Offset(60f, 50f), MaskToolMode.BRUSH, 20f)
        tools.endStroke(Offset(60f, 50f), MaskToolMode.BRUSH, 20f)

        val alpha = tools.maskBitmap.getPixel(50, 50) ushr 24
        assertTrue(alpha > 0, "Pixel at stroke location should be masked (alpha > 0)")
    }

    @Test
    fun `clearMask resets mask bitmap`() {
        val tools = MaskSelectionTools(100, 100)
        tools.startStroke(Offset(50f, 50f), MaskToolMode.BRUSH, 20f)
        tools.clearMask()

        val alpha = tools.maskBitmap.getPixel(50, 50) ushr 24
        assertEquals(0, alpha, "Mask pixel should be cleared (alpha == 0)")
    }
}
