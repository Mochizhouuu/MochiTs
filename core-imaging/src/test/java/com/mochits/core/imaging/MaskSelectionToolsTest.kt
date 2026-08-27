package com.mochits.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskSelectionToolsTest {

    @Test
    fun testInitializationAndSize() {
        val tools = MaskSelectionTools(100, 200)
        assertEquals(100, tools.width)
        assertEquals(200, tools.height)
        assertNotNull(tools.maskBitmap)
    }

    @Test
    fun testResetSize() {
        val tools = MaskSelectionTools(100, 100)
        tools.resetSize(300, 400)
        assertEquals(300, tools.width)
        assertEquals(400, tools.height)
    }
}
