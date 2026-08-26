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

    @Test
    fun testUpdateDenganRecordUndoFalseTidakMencemariStackUndo() {
        val state = CanvasEditorState("path/to/img.png", 1000, 2000)
        val layer = CanvasTextLayer(id = "l1", text = "awal", xInImagePx = 0f, yInImagePx = 0f)
        state.restoreLayers(listOf(layer))
        assertFalse(state.canUndo)

        // Simulasi loading project: update tanpa record undo
        state.updateLayerFontSize("l1", 40f, recordUndo = false)
        state.updateLayerText("l1", "baru", recordUndo = false)
        state.updateLayerStyle("l1", TextStyleConfig(isBold = true), recordUndo = false)

        assertEquals(40f, state.textLayers.first().fontSizeSp)
        assertEquals("baru", state.textLayers.first().text)
        assertTrue(state.textLayers.first().style.isBold)
        assertFalse(state.canUndo)
    }

    @Test
    fun testFontSizeDiBatasClampTidakMendorongSnapshotNoOp() {
        val state = CanvasEditorState("path/to/img.png", 1000, 2000)
        state.addTextLayer("tes", 0f, 0f)
        val id = state.textLayers.first().id

        Thread.sleep(400) // lewati window coalescing snapshot
        state.updateLayerFontSize(id, 200f)
        assertTrue(state.canUndo)

        Thread.sleep(400)
        // Nilai sudah di batas atas: tekan "+" lagi tidak boleh menambah entri undo baru.
        // Verifikasi via redoStack tetap ter-reset & undo tetap hanya satu langkah efektif:
        val snapshotBefore = state.canUndo to state.canRedo
        state.updateLayerFontSize(id, 300f) // akan di-coerce ke 200f == nilai sekarang
        assertEquals(snapshotBefore, state.canUndo to state.canRedo)
        assertEquals(200f, state.textLayers.first().fontSizeSp)

        // undo mengembalikan ke default 24sp (bukan no-op berulang)
        state.undo()
        assertEquals(24f, state.textLayers.first().fontSizeSp)
    }

    @Test
    fun testRestoreLayersMenggantiIsiTanpaUndo() {
        val state = CanvasEditorState("path/to/img.png", 1000, 2000)
        state.addTextLayer("sementara", 0f, 0f)

        val loaded = listOf(
            CanvasTextLayer(id = "a", text = "A", xInImagePx = 1f, yInImagePx = 2f),
            CanvasTextLayer(id = "b", text = "B", xInImagePx = 3f, yInImagePx = 4f, fontSizeSp = 30f)
        )
        state.restoreLayers(loaded)

        assertEquals(2, state.textLayers.size)
        assertEquals("a", state.textLayers[0].id)
        assertEquals(30f, state.textLayers[1].fontSizeSp)
        assertFalse(state.canUndo)
    }

    @Test
    fun testSetInitialFitScaleHanyaBerlakuSekali() {
        val state = CanvasEditorState("path/to/img.png", 1000, 4000)
        state.setInitialFitScale(500f, 1000f)
        assertEquals(0.5f, state.scale)

        // Panggilan ulang (rotasi/insets berubah) TIDAK boleh mereset zoom user
        state.zoomAt(4f, 250f, 500f)
        state.setInitialFitScale(500f, 1000f)
        assertEquals(2f, state.scale)
    }
}
