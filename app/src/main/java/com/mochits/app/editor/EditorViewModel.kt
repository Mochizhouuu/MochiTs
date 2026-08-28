package com.mochits.app.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.app.canvas.CanvasEditorState
import com.mochits.core.imaging.InpaintEngine
import com.mochits.core.imaging.MaskSelectionTools
import com.mochits.core.imaging.Result
import com.mochits.app.imaging.LaMaInpaintEngine
import com.mochits.app.imaging.LaMaModelManager
import com.mochits.app.model.EditorPanel
import com.mochits.app.model.Layer
import com.mochits.app.model.MaskToolMode
import com.mochits.app.model.TextStyleConfig
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val projectId: String = checkNotNull(savedStateHandle["projectId"])

    val canvasState = CanvasEditorState()
    val inpaintEngine = InpaintEngine()
    val serializer = LayerSerializer()
    val exporter = ProjectExporter(context)

    val project = MutableStateFlow<ProjectEntity?>(null)
    val baseBitmap = MutableStateFlow<Bitmap?>(null)
    val layers = MutableStateFlow<List<Layer>>(emptyList())
    val selectedLayerId = MutableStateFlow<String?>(null)
    val activePanel = MutableStateFlow(EditorPanel.NONE)

    enum class InpaintModel { TELEA, LAMA }

    val selectedInpaintModel = MutableStateFlow(InpaintModel.TELEA)
    val isDownloadingLaMaModel = MutableStateFlow(false)
    val lamaDownloadProgress = MutableStateFlow(0f)
    val userMessage = MutableStateFlow<String?>(null)

    val lamaModelManager = LaMaModelManager(context)
    val lamaInpaintEngine = LaMaInpaintEngine(context)

    val maskToolMode = MutableStateFlow(MaskToolMode.BRUSH)
    val brushSize = MutableStateFlow(40f)
    val defaultTextStyle = MutableStateFlow(TextStyleConfig())

    var maskSelectionTools: MaskSelectionTools? = null
        private set

    val isProcessingInpaint = MutableStateFlow(false)
    val isExporting = MutableStateFlow(false)
    val isLoadingImage = MutableStateFlow(false)

    data class HistorySnapshot(
        val layers: List<Layer>,
        val baseBitmap: Bitmap?,
        val maskBytes: ByteArray?
    )

    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()

    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    init {
        loadProject()
    }

    private fun updateUndoRedoState() {
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
    }

    private fun calculateMaxHistorySteps(): Int {
        val w = project.value?.width ?: 1080
        val h = project.value?.height ?: 1920
        val pixels = w.toLong() * h.toLong()
        return when {
            pixels > 12_000_000L -> 10
            pixels > 4_000_000L -> 15
            else -> 25
        }
    }

    fun getMaskByteArray(): ByteArray? {
        val tools = maskSelectionTools ?: return null
        val bmp = tools.maskBitmap
        if (bmp.isRecycled) return null
        return try {
            val buffer = java.nio.ByteBuffer.allocate(bmp.byteCount)
            bmp.copyPixelsToBuffer(buffer)
            buffer.array()
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun restoreMaskByteArray(bytes: ByteArray) {
        val tools = maskSelectionTools ?: return
        val bmp = tools.maskBitmap
        if (bmp.isRecycled) return
        try {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            bmp.copyPixelsFromBuffer(buffer)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun saveUndoSnapshot() {
        val snapshot = HistorySnapshot(
            layers = layers.value,
            baseBitmap = baseBitmap.value,
            maskBytes = getMaskByteArray()
        )
        undoStack.addLast(snapshot)
        val maxHistory = calculateMaxHistorySteps()
        while (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        updateUndoRedoState()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val currentSnapshot = HistorySnapshot(
            layers = layers.value,
            baseBitmap = baseBitmap.value,
            maskBytes = getMaskByteArray()
        )
        redoStack.addLast(currentSnapshot)
        val previousState = undoStack.removeLast()
        restoreSnapshot(previousState)
        updateUndoRedoState()
        autoSave()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val currentSnapshot = HistorySnapshot(
            layers = layers.value,
            baseBitmap = baseBitmap.value,
            maskBytes = getMaskByteArray()
        )
        undoStack.addLast(currentSnapshot)
        val nextState = redoStack.removeLast()
        restoreSnapshot(nextState)
        updateUndoRedoState()
        autoSave()
    }

    private fun restoreSnapshot(snapshot: HistorySnapshot) {
        layers.value = snapshot.layers
        baseBitmap.value = snapshot.baseBitmap
        snapshot.maskBytes?.let { bytes ->
            restoreMaskByteArray(bytes)
        }
    }

    private fun loadProject() {
        viewModelScope.launch {
            try {
                val proj = repository.getProject(projectId)
                if (proj != null) {
                    project.value = proj
                    layers.value = serializer.deserialize(proj.layersJson)
                    var loadedBmp: Bitmap? = null
                    proj.thumbnailPath?.let { path ->
                        val file = File(path)
                        if (file.exists()) {
                            try {
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inMutable = true
                                }
                                val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                                if (decoded != null) {
                                    loadedBmp = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                                        decoded
                                    } else {
                                        val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                                        decoded.recycle()
                                        copy
                                    }
                                }
                            } catch (t: Throwable) {
                                t.printStackTrace()
                            }
                        }
                    }
                    if (loadedBmp != null) {
                        baseBitmap.value = loadedBmp
                        setupCanvasSize(loadedBmp!!.width, loadedBmp!!.height)
                    } else {
                        setupCanvasSize(proj.width, proj.height)
                    }
                } else {
                    setupCanvasSize(1080, 1920)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                setupCanvasSize(1080, 1920)
            }
        }
    }

    private fun autoSave() {
        val currentProj = project.value ?: return
        viewModelScope.launch {
            val json = serializer.serialize(layers.value)
            repository.saveProject(currentProj.copy(layersJson = json))
        }
    }

    fun setupCanvasSize(width: Int, height: Int) {
        val safeW = width.coerceIn(1, 32768)
        val safeH = height.coerceIn(1, 32768)
        if (maskSelectionTools == null || maskSelectionTools?.width != safeW || maskSelectionTools?.height != safeH) {
            maskSelectionTools = MaskSelectionTools(safeW, safeH)
        }
        if (baseBitmap.value == null) {
            try {
                val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                baseBitmap.value = bmp
            } catch (t: Throwable) {
                t.printStackTrace()
                // Fallback to safe standard dimensions if extreme allocation fails
                val fallbackW = safeW.coerceAtMost(2048)
                val fallbackH = safeH.coerceAtMost(4096)
                try {
                    val bmp = Bitmap.createBitmap(fallbackW, fallbackH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    baseBitmap.value = bmp
                } catch (t2: Throwable) {
                    t2.printStackTrace()
                }
            }
        }
    }

    fun setBaseImage(bitmap: Bitmap) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            isLoadingImage.value = true
            try {
                baseBitmap.value = bitmap
                setupCanvasSize(bitmap.width, bitmap.height)

                val projectDir = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
                val imageFile = File(projectDir, "base_image.png")
                java.io.FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val currentProj = project.value
                if (currentProj != null) {
                    val updated = currentProj.copy(
                        width = bitmap.width,
                        height = bitmap.height,
                        thumbnailPath = imageFile.absolutePath,
                        layersJson = serializer.serialize(layers.value)
                    )
                    project.value = updated
                    repository.saveProject(updated)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingImage.value = false
            }
        }
    }

    fun setActivePanel(panel: EditorPanel) {
        activePanel.value = if (activePanel.value == panel) EditorPanel.NONE else panel
    }

    fun setMaskToolMode(mode: MaskToolMode) {
        maskToolMode.value = mode
    }

    fun setBrushSize(size: Float) {
        brushSize.value = size
    }

    fun updateProjectTitle(newTitle: String) {
        val currentProj = project.value ?: return
        val updated = currentProj.copy(title = newTitle)
        project.value = updated
        viewModelScope.launch {
            repository.saveProject(updated)
        }
    }

    fun addImageLayer(bitmap: Bitmap) {
        saveUndoSnapshot()
        val newLayer = Layer.ImageLayer(
            id = UUID.randomUUID().toString(),
            name = "Image ${layers.value.size + 1}",
            x = (project.value?.width ?: 1080) / 4f,
            y = (project.value?.height ?: 1920) / 4f,
            bitmap = bitmap
        )
        layers.value = layers.value + newLayer
        selectedLayerId.value = newLayer.id
        autoSave()
    }

    fun setInpaintModel(model: InpaintModel) {
        selectedInpaintModel.value = model
    }

    fun clearUserMessage() {
        userMessage.value = null
    }

    fun downloadLaMaModel(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            isDownloadingLaMaModel.value = true
            val success = lamaModelManager.downloadModel { progress ->
                lamaDownloadProgress.value = progress
            }
            isDownloadingLaMaModel.value = false
            if (!success) {
                userMessage.value = "Gagal mengunduh model LaMa. Menggunakan model Telea."
            }
            onComplete(success)
        }
    }

    fun runEraseInpaint() {
        val currentBase = baseBitmap.value ?: return
        val tools = maskSelectionTools ?: return

        viewModelScope.launch {
            isProcessingInpaint.value = true

            if (selectedInpaintModel.value == InpaintModel.LAMA) {
                if (!lamaModelManager.isModelDownloaded()) {
                    userMessage.value = "Mengunduh model LaMa..."
                    isDownloadingLaMaModel.value = true
                    val downloaded = lamaModelManager.downloadModel { progress ->
                        lamaDownloadProgress.value = progress
                    }
                    isDownloadingLaMaModel.value = false
                    if (!downloaded) {
                        userMessage.value = "Gagal mengunduh model LaMa, menggunakan Telea."
                        runTeleaFallback(currentBase, tools)
                        isProcessingInpaint.value = false
                        return@launch
                    }
                }

                saveUndoSnapshot()
                when (val lamaResult = lamaInpaintEngine.inpaintLaMa(currentBase, tools.maskBitmap)) {
                    is Result.Success -> {
                        baseBitmap.value = lamaResult.data
                        tools.clearMask()
                        autoSave()
                    }
                    is Result.Error -> {
                        userMessage.value = "Inference LaMa gagal. Fallback ke Telea."
                        runTeleaFallback(currentBase, tools)
                    }
                    else -> {}
                }
            } else {
                saveUndoSnapshot()
                runTeleaFallback(currentBase, tools)
            }

            isProcessingInpaint.value = false
        }
    }

    private suspend fun runTeleaFallback(currentBase: Bitmap, tools: MaskSelectionTools) {
        when (val result = inpaintEngine.inpaintTelea(currentBase, tools.maskBitmap)) {
            is Result.Success -> {
                baseBitmap.value = result.data
                tools.clearMask()
                autoSave()
            }
            is Result.Error -> {
                userMessage.value = "Gagal memproses inpaint Telea: ${result.exception.message}"
            }
            else -> {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        lamaInpaintEngine.close()
    }

    fun addTextLayer(
        text: String,
        style: TextStyleConfig = defaultTextStyle.value,
        viewportWidth: Float = 0f,
        viewportHeight: Float = 0f
    ) {
        saveUndoSnapshot()
        val canvasW = baseBitmap.value?.width ?: project.value?.width ?: 1080
        val canvasH = baseBitmap.value?.height ?: project.value?.height ?: 1920

        val proportionalFontSize = (canvasH * 0.035f).coerceIn(24f, 200f)
        val effectiveStyle = if (style.fontSize == 36f) style.copy(fontSize = proportionalFontSize) else style

        val posX = canvasW / 4f
        val posY = canvasH / 4f

        val newLayer = Layer.TextLayer(
            id = UUID.randomUUID().toString(),
            name = "Text ${layers.value.size + 1}",
            x = posX,
            y = posY,
            text = text,
            style = effectiveStyle
        )
        layers.value = layers.value + newLayer
        selectedLayerId.value = newLayer.id

        if (viewportWidth > 0f && viewportHeight > 0f) {
            canvasState.focusOnCanvasPoint(posX, posY, viewportWidth, viewportHeight)
        }

        autoSave()
    }

    fun updateSelectedTextLayerStyle(style: TextStyleConfig, saveUndo: Boolean = true) {
        defaultTextStyle.value = style
        val selectedId = selectedLayerId.value
        if (selectedId != null) {
            if (saveUndo) {
                saveUndoSnapshot()
            }
            layers.value = layers.value.map { layer ->
                if (layer.id == selectedId && layer is Layer.TextLayer) {
                    layer.copy(style = style)
                } else {
                    layer
                }
            }
            if (saveUndo) {
                autoSave()
            }
        }
    }

    fun updateSelectedLayerOpacity(opacity: Float) {
        val selectedId = selectedLayerId.value ?: return
        saveUndoSnapshot()
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId) {
                when (layer) {
                    is Layer.TextLayer -> layer.copy(opacity = opacity)
                    is Layer.ImageLayer -> layer.copy(opacity = opacity)
                }
            } else {
                layer
            }
        }
        autoSave()
    }

    fun updateSelectedTextContent(newText: String) {
        val selectedId = selectedLayerId.value ?: return
        saveUndoSnapshot()
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                layer.copy(text = newText)
            } else {
                layer
            }
        }
        autoSave()
    }

    fun updateSelectedTextLayerRotation(rotation: Float, saveUndo: Boolean = true) {
        if (saveUndo) {
            saveUndoSnapshot()
        }
        val selectedId = selectedLayerId.value ?: return
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                layer.copy(rotation = rotation)
            } else {
                layer
            }
        }
        if (saveUndo) {
            autoSave()
        }
    }

    fun finalizeTextTransform() {
        autoSave()
    }

    fun selectLayer(id: String?) {
        selectedLayerId.value = id
    }

    fun moveLayer(id: String, direction: Int) {
        val list = layers.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index == -1) return
        val newIndex = index + direction
        if (newIndex in 0 until list.size) {
            saveUndoSnapshot()
            val item = list.removeAt(index)
            list.add(newIndex, item)
            layers.value = list
            autoSave()
        }
    }

    fun toggleLayerVisibility(id: String) {
        saveUndoSnapshot()
        layers.value = layers.value.map { layer ->
            if (layer.id == id) {
                when (layer) {
                    is Layer.TextLayer -> layer.copy(isVisible = !layer.isVisible)
                    is Layer.ImageLayer -> layer.copy(isVisible = !layer.isVisible)
                }
            } else layer
        }
        autoSave()
    }

    fun deleteLayer(id: String) {
        saveUndoSnapshot()
        layers.value = layers.value.filter { it.id != id }
        if (selectedLayerId.value == id) {
            selectedLayerId.value = null
        }
        autoSave()
    }

    fun exportProject(
        outputFile: File,
        format: android.graphics.Bitmap.CompressFormat = android.graphics.Bitmap.CompressFormat.PNG,
        quality: Int = 100,
        onComplete: (Boolean) -> Unit
    ) {
        val base = baseBitmap.value ?: return
        viewModelScope.launch {
            isExporting.value = true
            val success = exporter.exportToFile(base, layers.value, outputFile, format, quality)
            isExporting.value = false
            onComplete(success)
        }
    }
}
