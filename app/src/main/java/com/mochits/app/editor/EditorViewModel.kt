package com.mochits.app.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.app.canvas.CanvasEditorState
import com.mochits.app.imaging.MaskSelectionTools
import com.mochits.app.inpaint.InpaintEngine
import com.mochits.app.model.EditorPanel
import com.mochits.app.model.Layer
import com.mochits.app.model.MaskToolMode
import com.mochits.app.model.Result
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

    val maskToolMode = MutableStateFlow(MaskToolMode.BRUSH)
    val brushSize = MutableStateFlow(40f)

    var maskSelectionTools: MaskSelectionTools? = null
        private set

    val isProcessingInpaint = MutableStateFlow(false)
    val isExporting = MutableStateFlow(false)

    init {
        loadProject()
    }

    private fun loadProject() {
        viewModelScope.launch {
            val proj = repository.getProject(projectId)
            if (proj != null) {
                project.value = proj
                layers.value = serializer.deserialize(proj.layersJson)
                setupCanvasSize(proj.width, proj.height)
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
        if (maskSelectionTools == null || maskSelectionTools?.width != width || maskSelectionTools?.height != height) {
            maskSelectionTools = MaskSelectionTools(width, height)
        }
        if (baseBitmap.value == null) {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            baseBitmap.value = bmp
        }
    }

    fun setBaseImage(bitmap: Bitmap) {
        baseBitmap.value = bitmap
        setupCanvasSize(bitmap.width, bitmap.height)
        autoSave()
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

    fun runTeleaInpaint() {
        val currentBase = baseBitmap.value ?: return
        val tools = maskSelectionTools ?: return

        viewModelScope.launch {
            isProcessingInpaint.value = true
            when (val result = inpaintEngine.inpaintTelea(currentBase, tools.maskBitmap)) {
                is Result.Success -> {
                    baseBitmap.value = result.data
                    tools.clearMask()
                    autoSave()
                }
                is Result.Error -> {}
                else -> {}
            }
            isProcessingInpaint.value = false
        }
    }

    fun addTextLayer(text: String, style: TextStyleConfig = TextStyleConfig()) {
        val newLayer = Layer.TextLayer(
            id = UUID.randomUUID().toString(),
            name = "Text ${layers.value.size + 1}",
            x = (project.value?.width ?: 1080) / 4f,
            y = (project.value?.height ?: 1920) / 4f,
            text = text,
            style = style
        )
        layers.value = layers.value + newLayer
        selectedLayerId.value = newLayer.id
        autoSave()
    }

    fun updateSelectedTextLayerStyle(style: TextStyleConfig) {
        val selectedId = selectedLayerId.value ?: return
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                layer.copy(style = style)
            } else {
                layer
            }
        }
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
            val item = list.removeAt(index)
            list.add(newIndex, item)
            layers.value = list
            autoSave()
        }
    }

    fun toggleLayerVisibility(id: String) {
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
        layers.value = layers.value.filter { it.id != id }
        if (selectedLayerId.value == id) {
            selectedLayerId.value = null
        }
        autoSave()
    }

    fun exportProject(outputFile: File, onComplete: (Boolean) -> Unit) {
        val base = baseBitmap.value ?: return
        viewModelScope.launch {
            isExporting.value = true
            val success = exporter.exportToFile(base, layers.value, outputFile)
            isExporting.value = false
            onComplete(success)
        }
    }
}
