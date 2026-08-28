package com.mochits.app.canvas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrecisionCoordinateMapperTest {

    @Test
    fun `screenToCanvas accurately transforms coordinates with zoom and pan`() {
        val mapper = PrecisionCoordinateMapper()
        // Scale 2x, Pan X=100, Y=200
        mapper.updateTransform(scale = 2f, translationX = 100f, translationY = 200f)

        // Screen coordinate (300, 400) -> Canvas should be ((300-100)/2, (400-200)/2) = (100, 100)
        val canvasPt = mapper.screenToCanvas(300f, 400f)
        assertEquals(100f, canvasPt.x, 0.001f)
        assertEquals(100f, canvasPt.y, 0.001f)

        // Reverse check
        val screenPt = mapper.canvasToScreen(100f, 100f)
        assertEquals(300f, screenPt.x, 0.001f)
        assertEquals(400f, screenPt.y, 0.001f)
    }

    @Test
    fun `canvasState updates scale and offset when pan zoom gesture occurs outside text handles`() {
        val state = CanvasEditorState()
        state.updateTransform(1.0f, 0f, 0f)

        // Simulate pan gesture on canvas outside text handle bounds
        val centroid = androidx.compose.ui.geometry.Offset(500f, 500f)
        val pan = androidx.compose.ui.geometry.Offset(50f, 100f)
        val zoom = 1.2f

        state.onGestureTransform(centroid, pan, zoom)

        assertEquals(1.2f, state.scale, 0.001f)
        assertEquals(-50f, state.offsetX, 0.001f)
        assertEquals(0f, state.offsetY, 0.001f)
    }
}
