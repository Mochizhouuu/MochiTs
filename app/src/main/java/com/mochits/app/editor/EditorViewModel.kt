package com.mochits.app.editor

import com.mochits.app.font.FontRepository
import com.mochits.app.font.FontItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mochits.app.settings.ExportSettingsRepository
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
import com.mochits.app.ui.color.ColorUtils
import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.TextStyleConfig
import com.mochits.app.model.TextStylePreset
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.mochits.app.util.Logger

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
    val exportSettingsRepository: ExportSettingsRepository,
    val fontRepository: FontRepository,
    val lamaModelManager: com.mochits.app.imaging.LaMaModelManager,
    savedStateHandle: SavedStateHandle,
    // Param terakhir + default agar pemanggil posisional lama (termasuk test)
    // tetap kompil tanpa perubahan. Binding Hilt disediakan di AppModule.
    val stylePresetRepository: com.mochits.app.style.StylePresetRepository =
        com.mochits.app.style.StylePresetRepository(context)
) : ViewModel() {

    val allFonts: StateFlow<List<FontItem>> = fontRepository.getAllFontsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val projectId: String = checkNotNull(savedStateHandle["projectId"])

    val canvasState = CanvasEditorState()
    val inpaintEngine = InpaintEngine()
    val serializer = LayerSerializer()
    val exporter = ProjectExporter(context)
    val textRenderer = com.mochits.app.text.TextRenderer(context)

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
    val lamaInpaintEngine = com.mochits.app.imaging.LaMaInpaintEngine(lamaModelManager)

    val maskToolMode = MutableStateFlow(MaskToolMode.BRUSH)
    val brushSize = MutableStateFlow(40f)
    val magicWandTolerance = MutableStateFlow(32f)
    val magicWandExpand = MutableStateFlow(0f)

    val isEyedropperActive = MutableStateFlow(false)
    val eyedropperCanvasPt = MutableStateFlow<Offset?>(null)
    val sampledColorPreview = MutableStateFlow<Int?>(null)
    private var compositeBitmap: Bitmap? = null
    private var cachedFlattenedBitmap: Bitmap? = null
    private var isFlattenedDirty = true
    private var eyedropperTargetConsumer: ((Int) -> Unit)? = null

    fun invalidateFlattenedCache() {
        isFlattenedDirty = true
    }

    suspend fun flattenForSelection(): Bitmap? {
        val base = baseBitmap.value ?: return null
        val cached = cachedFlattenedBitmap
        if (!isFlattenedDirty && cached != null && !cached.isRecycled &&
            cached.width == base.width && cached.height == base.height
        ) {
            return cached
        }
        cachedFlattenedBitmap?.let { if (!it.isRecycled) it.recycle() }
        val flattened = exporter.exportToBitmap(base, layers.value)
        cachedFlattenedBitmap = flattened
        isFlattenedDirty = false
        return flattened
    }

    fun startEyedropper(onColorSelected: (Int) -> Unit) {
        val base = baseBitmap.value ?: return
        compositeBitmap?.let { if (!it.isRecycled) it.recycle() }
        compositeBitmap = null
        eyedropperTargetConsumer = onColorSelected

        val initialPt = Offset(base.width / 2f, base.height / 2f)
        eyedropperCanvasPt.value = initialPt
        val initialColor = ColorUtils.samplePixelColor(base, initialPt.x, initialPt.y) ?: android.graphics.Color.BLACK
        sampledColorPreview.value = initialColor
        isEyedropperActive.value = true

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val comp = exporter.exportToBitmap(base, layers.value)
                compositeBitmap = comp
                val compColor = ColorUtils.samplePixelColor(comp, initialPt.x, initialPt.y) ?: initialColor
                sampledColorPreview.value = compColor
            } catch (t: Throwable) {
                Logger.e("Error: ${t.message}", t)
            }
        }
    }

    fun updateEyedropperPosition(canvasPt: Offset) {
        val targetBmp = compositeBitmap ?: baseBitmap.value ?: return
        val clampedPt = Offset(
            canvasPt.x.coerceIn(0f, targetBmp.width - 1f),
            canvasPt.y.coerceIn(0f, targetBmp.height - 1f)
        )
        eyedropperCanvasPt.value = clampedPt
        val color = ColorUtils.samplePixelColor(targetBmp, clampedPt.x, clampedPt.y)
        if (color != null) {
            sampledColorPreview.value = color
        }
    }

    fun confirmEyedropper() {
        val color = sampledColorPreview.value
        val consumer = eyedropperTargetConsumer
        cancelEyedropper()
        if (color != null && consumer != null) {
            consumer.invoke(color)
        }
    }

    fun cancelEyedropper() {
        isEyedropperActive.value = false
        eyedropperCanvasPt.value = null
        sampledColorPreview.value = null
        eyedropperTargetConsumer = null
        compositeBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        compositeBitmap = null
    }


    fun updateSelectedTextContent(newText: String, saveUndo: Boolean = true) {
        val selectedId = selectedLayerId.value
        if (selectedId != null) {
            if (saveUndo) {
                saveUndoSnapshot()
            }
            layers.value = layers.value.map { layer ->
                if (layer.id == selectedId && layer is Layer.TextLayer) {
                    layer.copy(text = newText)
                } else {
                    layer
                }
            }
            if (saveUndo) {
                autoSave()
            }
        }
    }

    fun applyCapitalizationTransform(transformType: String) {
        val selectedId = selectedLayerId.value ?: return
        val layer = layers.value.find { it.id == selectedId } as? Layer.TextLayer ?: return
        val newText = when (transformType.lowercase()) {
            "uppercase" -> layer.text.uppercase()
            "lowercase" -> layer.text.lowercase()
            "titlecase" -> layer.text.split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            else -> layer.text
        }
        updateSelectedTextContent(newText)
    }

val defaultTextStyle = MutableStateFlow(TextStyleConfig())

    /** Bentuk kontainer default untuk teks baru (diubah saat preset dipakai tanpa seleksi). */
    val defaultTextShape = MutableStateFlow(com.mochits.app.model.TextContainerShape.BOX)

    /** Daftar preset gaya (bawaan + simpanan pengguna). Flow repo yang hot. */
    val stylePresets: StateFlow<List<TextStylePreset>> = stylePresetRepository.presets

    /** ID preset yang disematkan ke paling atas. */
    val pinnedPresetIds: StateFlow<List<String>> = stylePresetRepository.pinnedPresetIds

    /**
     * Simpan kombinasi Font + Alignment + Shape saat ini sebagai preset.
     * Sumbernya layer teks terpilih, atau gaya default bila tidak ada seleksi.
     */
    fun saveStylePreset(name: String): TextStylePreset? {
        val cleanName = name.trim().take(40)
        if (cleanName.isEmpty()) return null
        val selected = selectedLayerId.value?.let { sid ->
            layers.value.find { it.id == sid } as? Layer.TextLayer
        }
        val preset = if (selected != null) {
            TextStylePreset(
                name = cleanName,
                fontName = selected.style.fontName,
                fontStyle = selected.style.fontStyle,
                alignment = selected.style.alignment,
                shape = selected.textContainerShape
            )
        } else {
            val style = defaultTextStyle.value
            TextStylePreset(
                name = cleanName,
                fontName = style.fontName,
                fontStyle = style.fontStyle,
                alignment = style.alignment,
                shape = defaultTextShape.value
            )
        }
        return stylePresetRepository.savePreset(preset)
    }

    /**
     * Pakai preset: ke layer teks terpilih (satu langkah undo), atau sebagai
     * default untuk teks baru bila tidak ada layer terpilih.
     */
    fun applyStylePreset(preset: TextStylePreset) {
        val selected = selectedLayerId.value?.let { sid ->
            layers.value.find { it.id == sid } as? Layer.TextLayer
        }
        if (selected == null) {
            defaultTextStyle.value = defaultTextStyle.value.copy(
                fontName = preset.fontName,
                fontStyle = preset.fontStyle,
                alignment = preset.alignment
            )
            defaultTextShape.value = preset.shape
            return
        }
        val newStyle = selected.style.copy(
            fontName = preset.fontName,
            fontStyle = preset.fontStyle,
            alignment = preset.alignment
        )
        if (selected.textContainerShape != preset.shape) {
            // Ganti style tanpa snapshot; updateSelectedTextLayerContainerShape
            // membuat snapshot + autosave sendiri (satu langkah undo).
            updateSelectedTextLayerStyle(newStyle, saveUndo = false)
            updateSelectedTextLayerContainerShape(preset.shape)
        } else {
            updateSelectedTextLayerStyle(newStyle, saveUndo = true)
        }
    }

    fun deleteStylePreset(id: String): Boolean {
        return stylePresetRepository.deletePreset(id)
    }

    /** Sematkan/lepas preset dari urutan paling atas. */
    fun togglePinnedPreset(id: String): Boolean {
        val pinned = stylePresetRepository.pinnedPresetIds.value.contains(id)
        return stylePresetRepository.setPresetPinned(id, !pinned)
    }

    var maskSelectionTools: MaskSelectionTools? = null
        private set

    val isProcessingInpaint = MutableStateFlow(false)
    val isExporting = MutableStateFlow(false)

    private val saveMutex = Mutex()
    private var pendingSaveJob: kotlinx.coroutines.Job? = null
    val isSaving = MutableStateFlow(false)

    /**
     * True only when the session started with an existing source file that could
     * not be decoded (corrupt/unreadable). While true, saves must NOT write the
     * base bitmap: the in-memory bitmap is only a blank fallback, and writing it
     * would permanently overwrite the user's file with a blank canvas. Normal
     * sessions are unaffected. Cleared as soon as a genuine base arrives
     * (picked image, reloaded bitmap, inpaint result).
     */
    private var baseImageSuspect = false

    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        bitmap?.let {
            if (!it.isRecycled) {
                try {
                    it.recycle()
                } catch (e: Exception) {
                    Logger.e("Error recycling bitmap: ${e.message}", e)
                }
            }
        }
    }

    val isLoadingImage = MutableStateFlow(false)


data class HistoryStepEntry(
    val layersJson: String,
    val bitmapFileName: String? = null,
    val maskFileName: String? = null,
    val rawMaskFileName: String? = null
)

data class HistoryManifest(
    val undoSteps: List<HistoryStepEntry>,
    val redoSteps: List<HistoryStepEntry>
)

    data class HistorySnapshot(
        val layers: List<Layer>,
        val baseBitmap: Bitmap? = null,
        val bitmapFilePath: String? = null,
        val maskBytes: ByteArray? = null,
        val rawMaskBytes: ByteArray? = null
    ) {
        fun getOrLoadBitmap(): Bitmap? {
            if (baseBitmap != null && !baseBitmap.isRecycled) return baseBitmap
            if (bitmapFilePath != null) {
                val file = File(bitmapFilePath)
                if (file.exists()) {
                    try {
                        val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true }
                        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                        if (decoded != null) {
                            return if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                                decoded
                            } else {
                                val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                                decoded.recycle()
                                copy
                            }
                        }
                    } catch (t: Throwable) {
                        Logger.e("Error: ${t.message}", t)
                    }
                }
            }
            return null
        }
    }

    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()

    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    init {
        // Magic Wand samples from a flattened composite. Any layer/base change must
        // invalidate the cached bitmap, otherwise taps sample a stale image and the
        // selection appears outside the tapped object.
        viewModelScope.launch {
            layers.collect { invalidateFlattenedCache() }
        }
        viewModelScope.launch {
            baseBitmap.collect { bmp ->
                if (bmp != null && !bmp.isRecycled) {
                    canvasState.mapper.updateCanvasSize(bmp.width, bmp.height)
                }
                invalidateFlattenedCache()
            }
        }
        loadProject()
    }

    private fun updateUndoRedoState() {
        synchronized(undoStack) {
            canUndo.value = undoStack.isNotEmpty()
            canRedo.value = redoStack.isNotEmpty()
        }
    }

    private fun calculateMaxHistorySteps(): Int = synchronized(undoStack) {
        val w = project.value?.width ?: 1080
        val h = project.value?.height ?: 1920
        val pixels = w.toLong() * h.toLong()

        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory

        val memoryPerSnapshot = pixels * 4L
        val maxHistoryMemory = (availableMemory * 0.3).toLong()
        val maxStepsByMemory = if (memoryPerSnapshot > 0) (maxHistoryMemory / memoryPerSnapshot).toInt() else 20

        val maxStepsByPixels = when {
            pixels > 16_000_000L -> 5
            pixels > 8_000_000L -> 8
            pixels > 4_000_000L -> 12
            else -> 20
        }

        minOf(maxStepsByMemory, maxStepsByPixels).coerceIn(3, 25)
    }

    fun getMaskByteArray(): ByteArray? {
        return maskSelectionTools?.getMaskByteArray()
    }

    fun getRawMaskByteArray(): ByteArray? {
        return maskSelectionTools?.getRawMaskByteArray()
    }

    fun restoreMaskByteArray(bytes: ByteArray) {
        maskSelectionTools?.restoreMaskByteArray(bytes)
    }

    fun restoreRawMaskByteArray(bytes: ByteArray) {
        maskSelectionTools?.restoreRawMaskByteArray(bytes)
    }

    fun saveUndoSnapshot() {
        val snapshot = HistorySnapshot(
            layers = layers.value,
            baseBitmap = baseBitmap.value,
            maskBytes = getMaskByteArray(),
            rawMaskBytes = getRawMaskByteArray()
        )
        synchronized(undoStack) {
            undoStack.addLast(snapshot)
            val maxHistory = calculateMaxHistorySteps()
            while (undoStack.size > maxHistory) {
                undoStack.removeFirst()
            }
            redoStack.clear()
        }
        updateUndoRedoState()
    }

    fun rollbackUndoSnapshot() {
        val snapshot = synchronized(undoStack) {
            if (undoStack.isNotEmpty()) undoStack.removeLast() else null
        }
        if (snapshot != null) {
            restoreSnapshot(snapshot)
            updateUndoRedoState()
        }
    }

    fun undo() {
        val previousState = synchronized(undoStack) {
            if (undoStack.isEmpty()) return
            val currentSnapshot = HistorySnapshot(
                layers = layers.value,
                baseBitmap = baseBitmap.value,
                maskBytes = getMaskByteArray(),
                rawMaskBytes = getRawMaskByteArray()
            )
            redoStack.addLast(currentSnapshot)
            undoStack.removeLast()
        }
        restoreSnapshot(previousState)
        updateUndoRedoState()
        autoSave()
    }

    fun redo() {
        val nextState = synchronized(undoStack) {
            if (redoStack.isEmpty()) return
            val currentSnapshot = HistorySnapshot(
                layers = layers.value,
                baseBitmap = baseBitmap.value,
                maskBytes = getMaskByteArray(),
                rawMaskBytes = getRawMaskByteArray()
            )
            undoStack.addLast(currentSnapshot)
            redoStack.removeLast()
        }
        restoreSnapshot(nextState)
        updateUndoRedoState()
        autoSave()
    }

    private fun restoreSnapshot(snapshot: HistorySnapshot) {
        layers.value = snapshot.layers
        // Jaga seleksi agar tidak dangling setelah undo/redo.
        val current = selectedLayerId.value
        if (current != null && snapshot.layers.none { it.id == current }) {
            selectedLayerId.value = snapshot.layers.lastOrNull()?.id
        }
        invalidateFlattenedCache()
        val loadedBmp = snapshot.getOrLoadBitmap()
        if (loadedBmp != null) {
            baseBitmap.value = loadedBmp
        }
        snapshot.maskBytes?.let { bytes ->
            restoreMaskByteArray(bytes)
        }
        snapshot.rawMaskBytes?.let { bytes ->
            restoreRawMaskByteArray(bytes)
        }
    }

    private fun loadProject() {
        viewModelScope.launch {
            isLoadingImage.value = true
            try {
                val proj = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.getProject(projectId)
                }

                if (proj != null) {
                    project.value = proj

                    var loadedBmp: Bitmap? = null
                    val baseFile = proj.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
                        ?: File(context.filesDir, "projects/${proj.id}/base_image.png").takeIf { it.exists() }

                    if (baseFile != null && baseFile.length() > 0) {
                        try {
                            val options = android.graphics.BitmapFactory.Options().apply {
                                inMutable = true
                                inJustDecodeBounds = true
                            }

                            android.graphics.BitmapFactory.decodeFile(baseFile.absolutePath, options)

                            val maxDimension = 8192
                            if (options.outWidth > maxDimension || options.outHeight > maxDimension) {
                                val scale = maxOf(
                                    options.outWidth.toFloat() / maxDimension,
                                    options.outHeight.toFloat() / maxDimension
                                )
                                options.inSampleSize = scale.toInt().coerceAtLeast(1)
                            }

                            options.inJustDecodeBounds = false
                            val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                android.graphics.BitmapFactory.decodeFile(baseFile.absolutePath, options)
                            }

                            if (decoded != null) {
                                loadedBmp = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                                    decoded
                                } else {
                                    val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                                    decoded.recycle()
                                    copy
                                }
                            } else {
                                Logger.e("Failed to decode base image at ${baseFile.absolutePath}, keeping file for retry")
                            }
                        } catch (t: Throwable) {
                            // Never delete the user's file on transient errors (e.g. OOM):
                            // deleting + later saving a blank fallback would destroy the image.
                            Logger.e("Error loading base bitmap: ${t.message}", t)
                        }
                    }

                    if (loadedBmp != null) {
                        val oldBmp = baseBitmap.value
                        baseBitmap.value = loadedBmp
                        recycleBitmapSafely(oldBmp)
                        setupCanvasSize(loadedBmp.width, loadedBmp.height)

                        val deserialized = serializer.deserialize(proj.layersJson)
                        applyLoadedLayers(deserialized, proj.id)

                        loadHistoryFromDisk(proj.id)
                    } else {
                        if (baseFile != null) {
                            // A source file exists but could not be decoded. Work on a
                            // blank fallback but never overwrite the user's file with it.
                            baseImageSuspect = true
                        }
                        val w = proj.width.coerceIn(1, 32768)
                        val h = proj.height.coerceIn(1, 32768)
                        setupCanvasSize(w, h)

                        val deserialized = serializer.deserialize(proj.layersJson)
                        applyLoadedLayers(deserialized, proj.id)
                    }
                } else {
                    setupCanvasSize(1080, 1920)
                }
            } catch (oom: OutOfMemoryError) {
                Logger.e("Out of memory loading project", oom)
                System.gc()
                try {
                    setupCanvasSize(1080, 1920)
                } catch (e: Exception) {
                    Logger.e("Failed to create fallback canvas", e)
                }
            } catch (t: Throwable) {
                Logger.e("Error loading project: ${t.message}", t)
                setupCanvasSize(1080, 1920)
            } finally {
                isLoadingImage.value = false
            }
        }
    }

    /**
     * Debounced autosave: dijadwalkan 400ms setelah perubahan terakhir.
     * Tidak ada lagi data yang dibuang seperti throttle lama (`return` kalau
     * <500ms). Flush yang dipanggil saat keluar akan membatalkan debounce
     * ini lalu menyimpan state TERBARU secara sinkron.
     */
    fun autoSave() {
        if (project.value == null) return
        pendingSaveJob?.cancel()
        pendingSaveJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(400)
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val currentProj = project.value ?: return
        val currentBmp = baseBitmap.value
        // Dipanggil dari coroutine yang sudah di-IO; kunci agar tidak balapan
        // dengan flush/exit yang memakai file base_image.png.tmp yang sama.
        saveMutex.withLock {
            isSaving.value = true
            try {
                saveProjectInternal(currentProj, currentBmp)
            } catch (e: Exception) {
                Logger.e("Error in autoSave: ${e.message}", e)
            } finally {
                isSaving.value = false
            }
        }
    }

    private suspend fun saveProjectInternal(currentProj: ProjectEntity, currentBmp: Bitmap?) {
        val layersToSave = persistImageLayersInternal(currentProj.id)
        if (!baseImageSuspect) {
            saveBaseBitmapToDiskInternal(currentProj.id, currentBmp)
        }
        persistSelectedLayerInternal(currentProj.id, selectedLayerId.value)
        val json = serializer.serialize(layersToSave)
        val imageFile = File(context.filesDir, "projects/${currentProj.id}/base_image.png")
        val updatedProj = currentProj.copy(
            layersJson = json,
            thumbnailPath = if (imageFile.exists()) imageFile.absolutePath else currentProj.thumbnailPath
        )
        project.value = updatedProj
        repository.saveProject(updatedProj)
        syncHistoryToDiskInternal(currentProj.id)
    }

    /**
     * Fire-and-forget untuk ON_PAUSE/ON_STOP: batalkan debounce lalu simpan
     * state terbaru segera (tanpa throttle). Dipanggil juga oleh UI saat back,
     * tapi untuk navigasi gunakan [flushBlocking] agar sempat selesai.
     */
    fun flushToDisk() {
        if (project.value == null) return
        pendingSaveJob?.cancel()
        pendingSaveJob = null
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            saveMutex.withLock {
                isSaving.value = true
                try {
                    val currentProj = project.value ?: return@withLock
                    saveProjectInternal(currentProj, baseBitmap.value)
                } catch (e: Exception) {
                    Logger.e("Error flushing project to disk: ${e.message}", e)
                } finally {
                    isSaving.value = false
                }
            }
        }
    }

    /**
     * Versi suspending: WAJIB dipakai sebelum `onNavigateBack()` agar tidak ada
     * teks yang hilang karena navigasi jalan sebelum save selesai.
     */
    suspend fun flushBlocking() {
        pendingSaveJob?.cancel()
        pendingSaveJob = null
        // NonCancellable agar tetap selesai walau scope pembatalan saat exit.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            saveMutex.withLock {
                isSaving.value = true
                try {
                    val currentProj = project.value ?: return@withLock
                    saveProjectInternal(currentProj, baseBitmap.value)
                } catch (e: Exception) {
                    Logger.e("Error flushing project to disk: ${e.message}", e)
                } finally {
                    isSaving.value = false
                }
            }
        }
    }

    private fun selectedLayerFile(projId: String): File =
        File(context.filesDir, "projects/$projId/selected_layer.txt")

    private fun persistSelectedLayerInternal(projId: String, selectedId: String?) {
        try {
            val file = selectedLayerFile(projId)
            file.parentFile?.mkdirs()
            if (selectedId == null) {
                if (file.exists()) file.delete()
            } else {
                file.writeText(selectedId)
            }
        } catch (t: Throwable) {
            Logger.e("Error persisting selected layer: ${t.message}", t)
        }
    }

    private fun readPersistedSelectedLayer(projId: String): String? {
        return try {
            val file = selectedLayerFile(projId)
            if (!file.exists()) return null
            file.readText().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyLoadedLayers(deserialized: List<Layer>, projId: String) {
        if (layers.value.isEmpty() && deserialized.isNotEmpty()) {
            layers.value = deserialized
        }
        if (selectedLayerId.value == null && layers.value.isNotEmpty()) {
            val persisted = readPersistedSelectedLayer(projId)
            selectedLayerId.value = if (persisted != null && layers.value.any { it.id == persisted }) {
                persisted
            } else {
                // Fallback: layer teratas tetap aktif agar tidak terasa hilang.
                layers.value.last().id
            }
        }
    }

    private fun saveBaseBitmapToDiskInternal(projId: String, bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        try {
            val projectDir = File(context.filesDir, "projects/$projId").apply { mkdirs() }
            val imageFile = File(projectDir, "base_image.png")
            val tmpFile = File(projectDir, "base_image.png.tmp")

            java.io.FileOutputStream(tmpFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (imageFile.exists()) {
                    imageFile.delete()
                }
                if (!tmpFile.renameTo(imageFile)) {
                    tmpFile.copyTo(imageFile, overwrite = true)
                    tmpFile.delete()
                }
                Logger.d("Atomic save base_image.png successful: ${imageFile.length()} bytes, dimensions=${bmp.width}x${bmp.height}")
            }
        } catch (e: Exception) {
            Logger.e("Error saving base bitmap to disk: ${e.message}", e)
        }
    }

    /**
     * Persists in-memory image-layer bitmaps to the project folder and returns
     * the layer list with valid [Layer.ImageLayer.imagePath] values.
     *
     * Without this, layers added via "Tambah Gambar" are serialized with
     * imagePath=null, so after leaving/reopening the project those images come
     * back empty (the picture looks corrupted/lost). Must be called on an IO
     * thread while holding [saveMutex]. Also sweeps orphaned layer files.
     */
    private fun persistImageLayersInternal(projId: String): List<Layer> {
        val current = layers.value

        val referencedIds = mutableSetOf<String>()
        current.filterIsInstance<Layer.ImageLayer>().forEach { referencedIds.add(it.id) }
        synchronized(undoStack) {
            (undoStack + redoStack).forEach { snap ->
                snap.layers.filterIsInstance<Layer.ImageLayer>().forEach { referencedIds.add(it.id) }
            }
        }

        var changed = false
        val updated = current.map { layer ->
            if (layer is Layer.ImageLayer) {
                val bmp = layer.bitmap
                val fileOk = layer.imagePath?.let { File(it).let { f -> f.exists() && f.length() > 0 } } == true
                if (bmp != null && !bmp.isRecycled && !fileOk) {
                    try {
                        val dir = File(context.filesDir, "projects/$projId/layers").apply { mkdirs() }
                        val file = File(dir, "layer_${layer.id}.png")
                        val tmp = File(dir, "layer_${layer.id}.png.tmp")
                        java.io.FileOutputStream(tmp).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                        }
                        if (tmp.exists() && tmp.length() > 0) {
                            if (file.exists()) file.delete()
                            if (!tmp.renameTo(file)) {
                                tmp.copyTo(file, overwrite = true)
                                tmp.delete()
                            }
                            changed = true
                            layer.copy(imagePath = file.absolutePath)
                        } else layer
                    } catch (t: Throwable) {
                        Logger.e("Error persisting image layer: ${t.message}", t)
                        layer
                    }
                } else layer
            } else layer
        }
        if (changed) {
            layers.value = updated
        }

        try {
            val dir = File(context.filesDir, "projects/$projId/layers")
            dir.listFiles()?.forEach { f ->
                val n = f.name
                if (n.endsWith(".tmp")) {
                    try { f.delete() } catch (_: Exception) {}
                } else if (n.startsWith("layer_") && n.endsWith(".png")) {
                    val id = n.removePrefix("layer_").removeSuffix(".png")
                    if (id !in referencedIds) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e("Error sweeping orphan layer files: ${t.message}", t)
        }

        return if (changed) updated else current
    }

    /**
     * Fills imagePath=null image layers (from snapshots taken before the bitmap
     * was persisted) using the current layer list, matched by id.
     */
    private fun healImagePaths(snapshotLayers: List<Layer>): List<Layer> {
        val pathsById = layers.value
            .filterIsInstance<Layer.ImageLayer>()
            .associate { it.id to it.imagePath }
        if (pathsById.isEmpty()) return snapshotLayers
        return snapshotLayers.map { layer ->
            if (layer is Layer.ImageLayer && layer.imagePath == null) {
                layer.copy(imagePath = pathsById[layer.id])
            } else layer
        }
    }

    private fun syncHistoryToDiskInternal(projId: String) {
        try {
            val historyDir = File(context.filesDir, "projects/$projId/history").apply { mkdirs() }
            val referencedFiles = mutableSetOf<String>()

            val undoSnapshotList = synchronized(undoStack) { undoStack.toList() }
            val redoSnapshotList = synchronized(undoStack) { redoStack.toList() }

            val undoEntries = undoSnapshotList.mapIndexed { index, snapshot ->
                var fileName: String? = snapshot.bitmapFilePath?.let { File(it).name }
                val bmp = snapshot.baseBitmap
                if (bmp != null && !bmp.isRecycled) {
                    val fName = "undo_bmp_$index.png"
                    val tmpName = "undo_bmp_$index.png.tmp"
                    val file = File(historyDir, fName)
                    val tmpFile = File(historyDir, tmpName)
                    try {
                        java.io.FileOutputStream(tmpFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                        }
                        if (tmpFile.exists() && tmpFile.length() > 0) {
                            if (file.exists()) file.delete()
                            if (!tmpFile.renameTo(file)) {
                                tmpFile.copyTo(file, overwrite = true)
                                tmpFile.delete()
                            }
                            fileName = fName
                        }
                    } catch (t: Throwable) {
                        Logger.e("Error compressing undo history bitmap: ${t.message}", t)
                    }
                }
                val validName = fileName
                if (validName != null) referencedFiles.add(validName)
                HistoryStepEntry(
                    layersJson = serializer.serialize(healImagePaths(snapshot.layers)),
                    bitmapFileName = fileName
                )
            }

            val redoEntries = redoSnapshotList.mapIndexed { index, snapshot ->
                var fileName: String? = snapshot.bitmapFilePath?.let { File(it).name }
                val bmp = snapshot.baseBitmap
                if (bmp != null && !bmp.isRecycled) {
                    val fName = "redo_bmp_$index.png"
                    val tmpName = "redo_bmp_$index.png.tmp"
                    val file = File(historyDir, fName)
                    val tmpFile = File(historyDir, tmpName)
                    try {
                        java.io.FileOutputStream(tmpFile).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                        }
                        if (tmpFile.exists() && tmpFile.length() > 0) {
                            if (file.exists()) file.delete()
                            if (!tmpFile.renameTo(file)) {
                                tmpFile.copyTo(file, overwrite = true)
                                tmpFile.delete()
                            }
                            fileName = fName
                        }
                    } catch (t: Throwable) {
                        Logger.e("Error compressing redo history bitmap: ${t.message}", t)
                    }
                }
                val validName = fileName
                if (validName != null) referencedFiles.add(validName)
                HistoryStepEntry(
                    layersJson = serializer.serialize(healImagePaths(snapshot.layers)),
                    bitmapFileName = fileName
                )
            }

            val manifest = HistoryManifest(undoEntries, redoEntries)
            val manifestFile = File(historyDir, "manifest.json")
            val manifestJson = com.google.gson.Gson().toJson(manifest)
            manifestFile.writeText(manifestJson)

            historyDir.listFiles()?.forEach { file ->
                if (file.name != "manifest.json" && !referencedFiles.contains(file.name)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Logger.e("Error in syncHistoryToDiskInternal: ${e.message}", e)
        }
    }

        private fun loadHistoryBitmap(filePath: String?): Bitmap? {
        if (filePath == null) return null
        val file = File(filePath)
        if (!file.exists()) return null
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true }
            val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            if (decoded != null) {
                if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                    decoded
                } else {
                    val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                    decoded.recycle()
                    copy
                }
            } else null
        } catch (t: Throwable) {
            Logger.e("Error loading history bitmap: ${t.message}", t)
            null
        }
    }

    private fun loadHistoryFromDisk(projId: String) {
        try {
            val historyDir = File(context.filesDir, "projects/$projId/history")
            val manifestFile = File(historyDir, "manifest.json")
            if (!manifestFile.exists()) return

            val manifestJson = manifestFile.readText()
            val manifest = com.google.gson.Gson().fromJson(manifestJson, HistoryManifest::class.java) ?: return

            synchronized(undoStack) {
                undoStack.clear()
                manifest.undoSteps.forEach { entry ->
                    val snapshotLayers = serializer.deserialize(entry.layersJson)
                    val bmpPath = entry.bitmapFileName?.let { File(historyDir, it).absolutePath }
                    val loadedBmp = loadHistoryBitmap(bmpPath)
                    undoStack.addLast(
                        HistorySnapshot(
                            layers = snapshotLayers,
                            baseBitmap = loadedBmp,
                            bitmapFilePath = bmpPath
                        )
                    )
                }

                redoStack.clear()
                manifest.redoSteps.forEach { entry ->
                    val snapshotLayers = serializer.deserialize(entry.layersJson)
                    val bmpPath = entry.bitmapFileName?.let { File(historyDir, it).absolutePath }
                    val loadedBmp = loadHistoryBitmap(bmpPath)
                    redoStack.addLast(
                        HistorySnapshot(
                            layers = snapshotLayers,
                            baseBitmap = loadedBmp,
                            bitmapFilePath = bmpPath
                        )
                    )
                }
            }

            updateUndoRedoState()
        } catch (e: Exception) {
            Logger.e("Error loading history from disk: ${e.message}", e)
        }
    }

    fun ensureBaseBitmapLoaded() {
        val currentBmp = baseBitmap.value
        if (currentBmp != null && !currentBmp.isRecycled) {
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val currentProj = project.value ?: repository.getProject(projectId)
                if (currentProj != null) {
                    val baseFile = currentProj.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
                        ?: File(context.filesDir, "projects/${currentProj.id}/base_image.png").takeIf { it.exists() }

                    if (baseFile != null) {
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inMutable = true
                        }
                        val decoded = android.graphics.BitmapFactory.decodeFile(baseFile.absolutePath, options)
                        if (decoded != null) {
                            val loadedBmp = if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                                decoded
                            } else {
                                val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
                                decoded.recycle()
                                copy
                            }
                            baseBitmap.value = loadedBmp
                            baseImageSuspect = false
                            setupCanvasSize(loadedBmp.width, loadedBmp.height)
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.e("Error: ${t.message}", t)
            }
        }
    }

    fun setupCanvasSize(width: Int, height: Int) {
        val safeW = width.coerceIn(1, 32768)
        val safeH = height.coerceIn(1, 32768)
        // Keep screen<->canvas mapping aligned with the real image size.
        // Without this the mapper keeps the 1080x1920 default and clamps/translates
        // taps to wrong pixels, so Magic Wand appears to select outside the target.
        canvasState.mapper.updateCanvasSize(safeW, safeH)
        if (maskSelectionTools == null || maskSelectionTools?.width != safeW || maskSelectionTools?.height != safeH) {
            maskSelectionTools = MaskSelectionTools(safeW, safeH)
        }
        invalidateFlattenedCache()
        val currentBmp = baseBitmap.value
        if (currentBmp == null || currentBmp.isRecycled || currentBmp.width != safeW || currentBmp.height != safeH) {
            try {
                val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                baseBitmap.value = bmp
            } catch (t: Throwable) {
                Logger.e("Error: ${t.message}", t)
                val fallbackW = safeW.coerceAtMost(2048)
                val fallbackH = safeH.coerceAtMost(4096)
                try {
                    val bmp = Bitmap.createBitmap(fallbackW, fallbackH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    baseBitmap.value = bmp
                } catch (t2: Throwable) {
                    Logger.e("Error: ${t2.message}", t2)
                }
            }
        }
    }

    fun setBaseImage(bitmap: Bitmap) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            isLoadingImage.value = true
            try {
                val oldBitmap = baseBitmap.value
                baseBitmap.value = bitmap
                recycleBitmapSafely(oldBitmap)
                baseImageSuspect = false

                setupCanvasSize(bitmap.width, bitmap.height)

                // Same base_image.png.tmp file as autoSave/flush: must hold the
                // mutex or concurrent writes interleave into a corrupt PNG.
                saveMutex.withLock {
                    saveBaseBitmapToDiskInternal(projectId, bitmap)

                    val currentProj = project.value
                    if (currentProj != null) {
                        val imageFile = File(context.filesDir, "projects/$projectId/base_image.png")
                        val updated = currentProj.copy(
                            width = bitmap.width,
                            height = bitmap.height,
                            thumbnailPath = if (imageFile.exists()) imageFile.absolutePath else currentProj.thumbnailPath,
                            layersJson = serializer.serialize(layers.value)
                        )
                        project.value = updated
                        repository.saveProject(updated)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Error setting base image: ${e.message}", e)
                userMessage.value = "Gagal memuat gambar: ${e.message}"
            } finally {
                isLoadingImage.value = false
            }
        }
    }

    fun setActivePanel(panel: EditorPanel) {
        if (activePanel.value == EditorPanel.ERASE || activePanel.value == EditorPanel.MASK || activePanel.value == EditorPanel.INPAINT) {
            if (panel != EditorPanel.ERASE && panel != EditorPanel.MASK && panel != EditorPanel.INPAINT) {
                maskSelectionTools?.clearMask()
            }
        }
        activePanel.value = if (activePanel.value == panel) EditorPanel.NONE else panel
    }

    fun setMaskToolMode(mode: MaskToolMode) {
        if (maskToolMode.value != mode) {
            maskSelectionTools?.clearMask()
            maskToolMode.value = mode
        }
    }

    fun setBrushSize(size: Float) {
        brushSize.value = size
    }

    fun setMagicWandTolerance(tolerance: Float) {
        magicWandTolerance.value = tolerance
    }

    fun setMagicWandExpand(expand: Float) {
        val clamped = expand.coerceIn(0f, 30f)
        magicWandExpand.value = clamped
        maskSelectionTools?.applyExpand(clamped.toInt())
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
        project.value?.id?.let { pid ->
            val sid = newLayer.id
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                persistSelectedLayerInternal(pid, sid)
            }
        }
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
            if (success) {
                userMessage.value = "Model LaMa berhasil diunduh."
            } else {
                userMessage.value = "Gagal mengunduh model LaMa. Periksa koneksi internet Anda."
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
                val status = lamaModelManager.checkModelStatus()
                if (status != com.mochits.app.imaging.LaMaModelStatus.DOWNLOADED) {
                    if (status == com.mochits.app.imaging.LaMaModelStatus.CORRUPTED_ERROR) {
                        userMessage.value = "File model LaMa rusak. Mengunduh ulang..."
                    } else {
                        userMessage.value = "Mengunduh model LaMa..."
                    }
                    isDownloadingLaMaModel.value = true
                    val downloaded = lamaModelManager.downloadModel { progress ->
                        lamaDownloadProgress.value = progress
                    }
                    isDownloadingLaMaModel.value = false
                    if (!downloaded) {
                        userMessage.value = "Gagal mengunduh model LaMa. Periksa koneksi internet atau unduh via Pengaturan."
                        isProcessingInpaint.value = false
                        return@launch
                    }
                }

                saveUndoSnapshot()
                try {
                    when (val lamaResult = lamaInpaintEngine.inpaintLaMa(currentBase, tools.maskBitmap)) {
                        is Result.Success -> {
                            baseBitmap.value = lamaResult.data
                            baseImageSuspect = false
                            tools.clearMask()
                            autoSave()
                        }
                        is Result.Error -> {
                            val msg = lamaResult.exception.message ?: "Memori rendah"
                            userMessage.value = if (msg.contains("memori rendah", ignoreCase = true) || lamaResult.exception is OutOfMemoryError) {
                                "Inpainting gagal karena memori rendah. Coba pilih area yang lebih kecil. Mengalihkan ke Telea..."
                            } else {
                                "Inference LaMa gagal: $msg. Mengalihkan ke Telea..."
                            }
                            runTeleaFallback(currentBase, tools)
                        }
                        else -> {}
                    }
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    userMessage.value = "Inpainting gagal karena memori rendah. Coba pilih area yang lebih kecil. Mengalihkan ke Telea..."
                    runTeleaFallback(currentBase, tools)
                } catch (t: Throwable) {
                    userMessage.value = "Inference LaMa gagal: ${t.message}. Mengalihkan ke Telea..."
                    runTeleaFallback(currentBase, tools)
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
                baseImageSuspect = false
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

        recycleBitmapSafely(baseBitmap.value)
        recycleBitmapSafely(compositeBitmap)
        recycleBitmapSafely(cachedFlattenedBitmap)

        layers.value.filterIsInstance<Layer.ImageLayer>().forEach { layer ->
            recycleBitmapSafely(layer.bitmap)
        }

        synchronized(undoStack) {
            undoStack.forEach { snapshot ->
                recycleBitmapSafely(snapshot.baseBitmap)
            }
            undoStack.clear()

            redoStack.forEach { snapshot ->
                recycleBitmapSafely(snapshot.baseBitmap)
            }
            redoStack.clear()
        }
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

        val proportionalFontSize = (canvasW * 0.035f).coerceIn(24f, 48f)
        val effectiveStyle = if (style.fontSize == 36f) style.copy(fontSize = proportionalFontSize) else style

        // Prefer the synchronously recorded viewport size (fresh every frame) over
        // the passed-in values, which come from an async LaunchedEffect cache that
        // can be one frame behind or still hold wrong defaults (e.g. after the
        // bottom panel resizes the content or before first layout).
        val vpW = canvasState.lastViewportWidth.takeIf { it > 0f } ?: viewportWidth
        val vpH = canvasState.lastViewportHeight.takeIf { it > 0f } ?: viewportHeight

        val (posX, posY) = if (vpW > 0f && vpH > 0f) {
            val hasTransform = canvasState.isTransformInitialized ||
                canvasState.scale != 1f || canvasState.offsetX != 0f || canvasState.offsetY != 0f
            if (!hasTransform) {
                // Cold start before the first frame: the mapper is still identity,
                // so screenToCanvas would return a wrong point. Init it now with
                // the same fit logic the draw scope uses.
                canvasState.resetTransform(vpW, vpH, canvasW.toFloat(), canvasH.toFloat())
            }
            val centerCanvas = canvasState.mapper.screenToCanvas(vpW / 2f, vpH / 2f)
            Pair(centerCanvas.x, centerCanvas.y)
        } else {
            Pair(canvasW / 2f, canvasH / 2f)
        }

        val bounds = textRenderer.getTextBounds(text, effectiveStyle, 0f, 0f)
        val textWidth = bounds.width()
        val textHeight = bounds.height()

        val finalX = posX - (textWidth / 2f)
        val finalY = posY - (textHeight / 2f)

    val newLayer = Layer.TextLayer(
    id = UUID.randomUUID().toString(),
    name = "Text ${layers.value.size + 1}",
    x = finalX,
    y = finalY,
    text = text,
    style = effectiveStyle,
    textContainerShape = defaultTextShape.value
    )
        layers.value = layers.value + newLayer
        selectedLayerId.value = newLayer.id
        project.value?.id?.let { pid ->
            val sid = newLayer.id
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                persistSelectedLayerInternal(pid, sid)
            }
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

    private var isSliderDragging = false

    fun onSliderDragStart() {
        if (!isSliderDragging) {
            saveUndoSnapshot()
            isSliderDragging = true
        }
    }

    fun onSliderDragEnd() {
        if (isSliderDragging) {
            isSliderDragging = false
            autoSave()
        }
    }

    fun updateSelectedLayerOpacity(opacity: Float, saveUndo: Boolean = true) {
        val selectedId = selectedLayerId.value ?: return
        if (saveUndo) {
            saveUndoSnapshot()
        }
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
        if (saveUndo) {
            autoSave()
        }
    }

    fun updateSelectedTextLayerPosition(newX: Float, newY: Float, saveUndo: Boolean = true) {
        if (saveUndo) {
            saveUndoSnapshot()
        }
        val selectedId = selectedLayerId.value ?: return
        val canvasW = baseBitmap.value?.width?.toFloat() ?: project.value?.width?.toFloat() ?: 1080f
        val canvasH = baseBitmap.value?.height?.toFloat() ?: project.value?.height?.toFloat() ?: 1920f

        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                val bounds = textRenderer.getTextBounds(layer)
                val bw = bounds.width().coerceAtLeast(20f)
                val bh = bounds.height().coerceAtLeast(20f)

                val minX = -bw + 20f
                val maxX = canvasW - 20f
                val minY = -bh + 20f
                val maxY = canvasH - 20f

                val clampedX = newX.coerceIn(minX, maxX)
                val clampedY = newY.coerceIn(minY, maxY)

                layer.copy(x = clampedX, y = clampedY)
            } else {
                layer
            }
        }
        if (saveUndo) {
            autoSave()
        }
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


    fun updateSelectedTextLayerContainerShape(shape: com.mochits.app.model.TextContainerShape) {
        val selectedId = selectedLayerId.value ?: return
        saveUndoSnapshot()
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                if (layer.textContainerShape == shape && layer.boxWidth != null && layer.boxHeight != null) {
                    return@map layer
                }

                val boxW = layer.boxWidth
                val boxH = layer.boxHeight

                val (currW, currH) = if (boxW != null && boxH != null) {
                    Pair(boxW, boxH)
                } else {
                    val bounds = textRenderer.getTextBounds(layer)
                    Pair(bounds.width().coerceAtLeast(30f), bounds.height().coerceAtLeast(20f))
                }

                val currCenterX = layer.x + (currW / 2f)
                val currCenterY = layer.y + (currH / 2f)

                val scaleW = 1.18f
                val scaleH = 1.15f

                val (newW, newH) = if (shape == com.mochits.app.model.TextContainerShape.OVAL) {
                    if (layer.textContainerShape == com.mochits.app.model.TextContainerShape.BOX && (boxW == null || boxH == null)) {
                        Pair((currW * 1.35f).coerceAtLeast(40f), (currH * 1.35f).coerceAtLeast(30f))
                    } else {
                        Pair((currW * scaleW).coerceAtLeast(40f), (currH * scaleH).coerceAtLeast(30f))
                    }
                } else {
                    if (layer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                        Pair((currW / scaleW).coerceAtLeast(30f), (currH / scaleH).coerceAtLeast(20f))
                    } else {
                        Pair(currW, currH)
                    }
                }

                val newX = currCenterX - (newW / 2f)
                val newY = currCenterY - (newH / 2f)

                layer.copy(
                    textContainerShape = shape,
                    boxWidth = newW,
                    boxHeight = newH,
                    x = newX,
                    y = newY
                )
            } else {
                layer
            }
        }
        autoSave()
    }

    fun updateSelectedTextLayerResize(
        fontSize: Float,
        boxWidth: Float?,
        boxHeight: Float?,
        anchorCenterX: Float? = null,
        anchorCenterY: Float? = null,
        saveUndo: Boolean = true
    ) {
        if (saveUndo) {
            saveUndoSnapshot()
        }
        val selectedId = selectedLayerId.value ?: return
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                val newStyle = layer.style.copy(fontSize = fontSize)
                var newX = layer.x
                var newY = layer.y
                if (anchorCenterX != null && anchorCenterY != null) {
                    // Ukur bounds baru lalu geser x,y supaya titik tengah visual
                    // tetap di posisi awal gesture. Tanpa ini bounds berjangkar
                    // kiri-atas (left=x, top=y) sehingga teks membesar melar
                    // ke kanan-bawah, bukan diam di tempat.
                    val measured = textRenderer.getTextBounds(
                        text = layer.text,
                        style = newStyle,
                        x = 0f,
                        y = 0f,
                        shape = layer.textContainerShape,
                        boxWidth = boxWidth,
                        boxHeight = boxHeight
                    )
                    // center(x) = x + c (c = titik tengah saat x=0), jadi ini
                    // tepat untuk box maupun ukuran natural/reflow.
                    newX = anchorCenterX - measured.centerX()
                    newY = anchorCenterY - measured.centerY()
                }
                layer.copy(
                    style = newStyle,
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                    x = newX,
                    y = newY
                )
            } else {
                layer
            }
        }
        if (saveUndo) {
            autoSave()
        }
    }

    fun updateSelectedTextLayerStretch(
        boxWidth: Float?,
        boxHeight: Float?,
        newX: Float,
        newY: Float,
        saveUndo: Boolean = false
    ) {
        if (saveUndo) {
            saveUndoSnapshot()
        }
        val selectedId = selectedLayerId.value ?: return
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                layer.copy(boxWidth = boxWidth, boxHeight = boxHeight, x = newX, y = newY)
            } else {
                layer
            }
        }
        if (saveUndo) {
            autoSave()
        }
    }

    fun updateSelectedTextLayerDimensions(boxWidth: Float?, boxHeight: Float?, saveUndo: Boolean = true) {
        if (saveUndo) {
            saveUndoSnapshot()
        }
        val selectedId = selectedLayerId.value ?: return
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                layer.copy(boxWidth = boxWidth, boxHeight = boxHeight)
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
        // Seleksi murah: tulis langsung agar tap terakhir tidak hilang walau
        // user keluar <400ms sebelum debounce autoSave jalan. Save penuh
        // (layersJson) tetap via flushBlocking saat exit.
        val projId = project.value?.id ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            persistSelectedLayerInternal(projId, id)
        }
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


    fun getDefaultExportFolderUri(): Uri? = exportSettingsRepository.getExportFolderUri()

    fun getDefaultExportFolderName(): String? =
        exportSettingsRepository.getFolderName(exportSettingsRepository.getExportFolderUri())

    fun isExportFolderValid(uri: Uri?): Boolean = exportSettingsRepository.isFolderValid(uri)

    fun saveExportFolderUri(uri: Uri): Boolean = exportSettingsRepository.saveExportFolderUri(uri)

    fun exportProject(
        outputFile: File,
        format: android.graphics.Bitmap.CompressFormat = android.graphics.Bitmap.CompressFormat.PNG,
        quality: Int = 100,
        onComplete: (Boolean) -> Unit
    ) {
        // Must always invoke onComplete: callers already created the destination
        // file and would otherwise leave a 0-byte orphan with no feedback.
        val base = baseBitmap.value ?: run {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            isExporting.value = true
            val success = exporter.exportToFile(base, layers.value, outputFile, format, quality)
            isExporting.value = false
            onComplete(success)
        }
    }
}
