package com.mochits.app.imaging

import androidx.compose.ui.geometry.Offset
import com.mochits.core.imaging.MaskSelectionTools
import com.mochits.core.imaging.MaskToolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskSelectionToolsTest {

    @Test
    fun `initialization and size check`() {
        val tools = MaskSelectionTools(100, 100)
        assertEquals(100, tools.width)
        assertEquals(100, tools.height)
        assertNotNull(tools.maskBitmap)
    }

    @Test
    fun `resetSize updates dimensions`() {
        val tools = MaskSelectionTools(100, 100)
        tools.resetSize(200, 300)
        assertEquals(200, tools.width)
        assertEquals(300, tools.height)
    }
}
