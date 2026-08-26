package com.mochits.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State non-destruktif untuk Canvas Editor. Base image tidak pernah
 * dimodifikasi langsung — zoom hanya transformasi tampilan.
 *
 * PENTING: posisi text layer ([CanvasTextLayer.xInImagePx]/[yInImagePx])
 * disimpan dalam KOORDINAT PIKSEL GAMBAR ASLI (bukan viewport layar,
 * bukan pula skala 0f..1f). Ini wajib untuk gambar long-strip/webtoon
 * yang jauh lebih tinggi dari layar dan di-scroll — posisi teks harus
 * "menempel" ke titik tertentu pada gambar itu sendiri, bukan ke area
 * layar yang sedang terlihat.
 *
 * Mendukung undo/redo untuk operasi layer (tambah, hapus, pindah,
 * ubah teks/style/ukuran font).
 */
@Stable
class CanvasEditorState(
    baseImagePath: String,
    intrinsicWidthPx: Int,
    intrinsicHeightPx: Int
) {
    val baseImagePath: String = baseImagePath
    val intrinsicWidthPx: Int = intrinsicWidthPx.coerceAtLeast(1)
    val intrinsicHeightPx: Int = intrinsicHeightPx.coerceAtLeast(1)

    var scale by mutableFloatStateOf(1f)
        private set

    var offsetX by mutableFloatStateOf(0f)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    var viewportWidthPx by mutableFloatStateOf(0f)
        private set

    var viewportHeightPx by mutableFloatStateOf(0f)
        private set

    val textLayers = mutableStateListOf<CanvasTextLayer>()

    var selectedLayerId by mutableStateOf<String?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<List<CanvasTextLayer>>()
    private val redoStack = ArrayDeque<List<CanvasTextLayer>>()
    private var idCounter by mutableIntStateOf(0)
    private var lastSnapshotAtMs = 0L

    fun updateViewportSize(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val sizeChanged = viewportWidthPx != widthPx || viewportHeightPx != heightPx
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        if (sizeChanged && !initialFitApplied) {
            setInitialFitScale(widthPx, heightPx)
        }
    }

    private var initialFitApplied = false

    fun setInitialFitScale(vwPx: Float, vhPx: Float) {
        if (initialFitApplied || intrinsicWidthPx <= 0 || intrinsicHeightPx <= 0) return
        initialFitApplied = true
        val fitScale = (vwPx / intrinsicWidthPx.toFloat()).coerceIn(0.05f, 5.0f)
        scale = fitScale
        // Center image vertically and horizontally in viewport initially
        offsetX = (vwPx - intrinsicWidthPx * fitScale) / 2f
        offsetY = (vhPx - intrinsicHeightPx * fitScale) / 2f
        clampOffsets()
    }

    fun panBy(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
        clampOffsets()
    }

    fun zoomAt(zoomFactor: Float, centroidX: Float, centroidY: Float) {
        val oldScale = scale
        val newScale = (oldScale * zoomFactor).coerceIn(0.05f, 10.0f)
        if (newScale == oldScale) return

        // Centroid in image coordinates before zoom
        val imgX = (centroidX - offsetX) / oldScale
        val imgY = (centroidY - offsetY) / oldScale

        scale = newScale
        // Adjust offsets so centroid point remains under the same screen location
        offsetX = centroidX - imgX * newScale
        offsetY = centroidY - imgY * newScale
        clampOffsets()
    }

    fun clampOffsets() {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return
        val imgWidthScreen = intrinsicWidthPx * scale
        val imgHeightScreen = intrinsicHeightPx * scale

        val minX = if (imgWidthScreen > viewportWidthPx) viewportWidthPx - imgWidthScreen else (viewportWidthPx - imgWidthScreen) / 2f
        val maxX = if (imgWidthScreen > viewportWidthPx) 0f else (viewportWidthPx - imgWidthScreen) / 2f

        val minY = if (imgHeightScreen > viewportHeightPx) viewportHeightPx - imgHeightScreen else (viewportHeightPx - imgHeightScreen) / 2f
        val maxY = if (imgHeightScreen > viewportHeightPx) 0f else (viewportHeightPx - imgHeightScreen) / 2f

        offsetX = offsetX.coerceIn(minX, maxX)
        offsetY = offsetY.coerceIn(minY, maxY)
    }

    /**
     * Simpan snapshot layer saat ini ke stack undo (panggil SEBELUM mutasi).
     * Snapshot yang terjadi sangat berdekatan (misal drag slider yang sama)
     * digabung jadi satu entri agar riwayat undo tidak dibanjiri.
     */
    fun pushUndoSnapshot() {
        val now = System.currentTimeMillis()
        val coalesceWithPrevious = (now - lastSnapshotAtMs) in 1 until SNAPSHOT_COALESCE_MS
        lastSnapshotAtMs = now
        if (coalesceWithPrevious && undoStack.isNotEmpty()) return

        undoStack.addLast(textLayers.toList())
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        refreshHistoryFlags()
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(textLayers.toList())
        restoreSnapshot(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(textLayers.toList())
        restoreSnapshot(next)
    }

    private fun restoreSnapshot(snapshot: List<CanvasTextLayer>) {
        textLayers.clear()
        textLayers.addAll(snapshot)
        if (selectedLayerId != null && textLayers.none { it.id == selectedLayerId }) {
            selectedLayerId = null
        }
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun nextLayerId(): String {
        idCounter += 1
        return "text-${System.currentTimeMillis()}-$idCounter"
    }

    /**
     * @param recordUndo false saat memuat layer tersimpan dari project agar
     * operasi loading tidak dianggap aksi user (tombol undo tidak ikut aktif).
     */
    fun addTextLayer(
        text: String,
        xInImagePx: Float,
        yInImagePx: Float,
        recordUndo: Boolean = true
    ) {
        if (recordUndo) pushUndoSnapshot()
        val newLayer = CanvasTextLayer(
            id = nextLayerId(),
            text = text,
            xInImagePx = xInImagePx.coerceIn(0f, intrinsicWidthPx.toFloat()),
            yInImagePx = yInImagePx.coerceIn(0f, intrinsicHeightPx.toFloat())
        )
        textLayers.add(newLayer)
        selectedLayerId = newLayer.id
    }

    /** Duplikat layer dengan offset kecil; mengembalikan id layer baru. */
    fun duplicateLayer(id: String): String? {
        val source = textLayers.firstOrNull { it.id == id } ?: return null
        pushUndoSnapshot()
        val offset = (intrinsicWidthPx * 0.02f).coerceAtLeast(8f)
        val newLayer = source.copy(
            id = nextLayerId(),
            xInImagePx = (source.xInImagePx + offset).coerceIn(0f, intrinsicWidthPx.toFloat()),
            yInImagePx = (source.yInImagePx + offset).coerceIn(0f, intrinsicHeightPx.toFloat())
        )
        textLayers.add(newLayer)
        selectedLayerId = newLayer.id
        return newLayer.id
    }

    fun moveLayerBy(id: String, deltaXInImagePx: Float, deltaYInImagePx: Float) {
        val index = textLayers.indexOfFirst { it.id == id }
        if (index != -1) {
            val current = textLayers[index]
            textLayers[index] = current.copy(
                xInImagePx = (current.xInImagePx + deltaXInImagePx)
                    .coerceIn(0f, intrinsicWidthPx.toFloat()),
                yInImagePx = (current.yInImagePx + deltaYInImagePx)
                    .coerceIn(0f, intrinsicHeightPx.toFloat())
            )
        }
    }

    fun selectLayer(id: String?) {
        selectedLayerId = id
    }

    fun deleteLayer(id: String) {
        pushUndoSnapshot()
        textLayers.removeAll { it.id == id }
        if (selectedLayerId == id) selectedLayerId = null
    }

    /**
     * Muat kumpulan layer hasil deserialize dari project tersimpan.
     * Riwayat undo/redo dikosongkan — memuat project adalah titik mulai
     * baru, bukan aksi yang bisa di-undo.
     */
    fun restoreLayers(layers: List<CanvasTextLayer>) {
        textLayers.clear()
        textLayers.addAll(layers)
        undoStack.clear()
        redoStack.clear()
        lastSnapshotAtMs = 0L
        if (selectedLayerId != null && textLayers.none { it.id == selectedLayerId }) {
            selectedLayerId = null
        }
        refreshHistoryFlags()
    }

    fun updateLayerText(id: String, newText: String, recordUndo: Boolean = true) {
        val index = textLayers.indexOfFirst { it.id == id }
        if (index != -1 && textLayers[index].text != newText) {
            if (recordUndo) pushUndoSnapshot()
            textLayers[index] = textLayers[index].copy(text = newText)
        }
    }

    fun updateLayerStyle(
        id: String,
        newStyle: com.mochits.text.TextStyleConfig,
        recordUndo: Boolean = true
    ) {
        val index = textLayers.indexOfFirst { it.id == id }
        if (index != -1 && textLayers[index].style != newStyle) {
            if (recordUndo) pushUndoSnapshot()
            textLayers[index] = textLayers[index].copy(style = newStyle)
        }
    }

    fun updateLayerFontSize(id: String, newSizeSp: Float, recordUndo: Boolean = true) {
        val index = textLayers.indexOfFirst { it.id == id }
        // Coerce dulu, baru bandingkan — mencegah snapshot undo no-op berulang
        // saat nilai sudah berada di batas clamp (mis. tombol "+" di 200sp).
        val coerced = newSizeSp.coerceIn(8f, 200f)
        if (index != -1 && textLayers[index].fontSizeSp != coerced) {
            if (recordUndo) pushUndoSnapshot()
            textLayers[index] = textLayers[index].copy(fontSizeSp = coerced)
        }
    }

    private companion object {
        const val MAX_HISTORY = 50
        const val SNAPSHOT_COALESCE_MS = 350L
    }
}
