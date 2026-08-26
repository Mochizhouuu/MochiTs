package com.mochits.canvas

import com.mochits.text.TextStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasEditorStateTest {

    @Test
    fun testAddAndMoveLayer() {
        val state = CanvasEditorState("path/to/img.png", 1000, 2000)
        assertTrue(state.textLayers.isEmpty())

        state.addTextLayer("Hello", 100f, 200f)
        assertEquals(1, state.textLayers.size)
        val layer = state.textLayers.first()
        assertEquals("Hello", layer.text)
        assertEquals(100f, layer.xInImagePx)
        assertEquals(200f, layer.yInImagePx)

        state.moveLayerBy(layer.id, 50f, -30f)
        assertEquals(150f, state.textLayers.first().xInImagePx)
        assertEquals(170f, state.textLayers.first().yInImagePx)
    }

    @Test
    fun testUndoRedoForStyleAndFontSize() {
        val state = CanvasEditorState("path/to/img.png", 1000, 2000)
        state.addTextLayer("Test Undo", 100f, 100f)
        val layerId = state.textLayers.first().id

        // Sleep to avoid coalescing snapshot with addTextLayer snapshot
        Thread.sleep(400)

        state.updateLayerFontSize(layerId, 48f)
        assertTrue(state.canUndo)
        assertEquals(48f, state.textLayers.first().fontSizeSp)

        state.undo()
        assertEquals(24f, state.textLayers.first().fontSizeSp)

        state.redo()
        assertEquals(48f, state.textLayers.first().fontSizeSp)
    }

    @Test
    fun testDeleteLayer() {
        val state = CanvasEditorState("path/to/img.png", 1000, 2000)
        state.addTextLayer("Layer 1", 10f, 10f)
        val id1 = state.textLayers.first().id
        state.addTextLayer("Layer 2", 20f, 20f)

        assertEquals(2, state.textLayers.size)
        state.deleteLayer(id1)
        assertEquals(1, state.textLayers.size)
        assertEquals("Layer 2", state.textLayers.first().text)
    }
}
