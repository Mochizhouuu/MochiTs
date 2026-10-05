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
import com.mochits.app.util.atomicReplace
import com.mochits.core.imaging.InpaintEngine
import com.mochits.core.imaging.MaskSelectionTools
import com.mochits.core.imaging.Result
import com.mochits.app.imaging.ImageEffects
import com.mochits.app.imaging.Perspective
import com.mochits.app.imaging.LaMaInpaintEngine
import com.mochits.app.imaging.LaMaModelManager
import com.mochits.app.model.EditorPanel
import com.mochits.app.model.Layer
import com.mochits.app.model.MaskToolMode
import com.mochits.app.ui.color.ColorUtils
import androidx.compose.ui.geometry.Offset
import com.mochits.app.model.TextStyleConfig
import com.mochits.app.model.TextStylePreset
import com.mochits.app.model.UiMessage
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.mochits.app.util.Logger

/**
 * Hasil warp siap gambar: bitmap + offset relatif konten asal + skala
 * (ukuran gambar = bitmap / scale). Tipe publik agar bisa dipakai UI.
 */
data class WarpedLayerDraw(
    val bitmap: Bitmap,
    val offX: Float,
    val offY: Float,
    val scale: Float
)

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

    /** fontNameKey yang difavoritkan pengguna. */
    val favoriteFontKeys: StateFlow<Set<String>> = fontRepository.favoriteFontKeys

    /** Daftar font dengan favorit di urutan paling atas. */
    val allFonts: StateFlow<List<FontItem>> = kotlinx.coroutines.flow.combine(
        fontRepository.getAllFontsFlow(),
        fontRepository.favoriteFontKeys
    ) { fonts, favs ->
        val (favorites, rest) = fonts.partition { favs.contains(it.fontNameKey) }
        favorites.sortedBy { it.name } + rest
    }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Tandai/lepas favorit font. */
    fun toggleFontFavorite(fontNameKey: String): Boolean {
        val favorite = !fontRepository.favoriteFontKeys.value.contains(fontNameKey)
        return fontRepository.setFontFavorite(fontNameKey, favorite)
    }

    val projectId: String = checkNotNull(savedStateHandle["projectId"])

    val canvasState = CanvasEditorState()
    val inpaintEngine = InpaintEngine()
    val serializer = LayerSerializer()
    val exporter = ProjectExporter(context)
    val textRenderer = com.mochits.app.text.TextRenderer(context)
    /** Satu-satunya sumber path file (S2). */
    val fileStore = com.mochits.app.project.ProjectFileStore(context)

    val project = MutableStateFlow<ProjectEntity?>(null)
    val baseBitmap = MutableStateFlow<Bitmap?>(null)
    val layers = MutableStateFlow<List<Layer>>(emptyList())
    val selectedLayerId = MutableStateFlow<String?>(null)
    val activePanel = MutableStateFlow(EditorPanel.NONE)

    enum class InpaintModel { TELEA, LAMA }

    val selectedInpaintModel = MutableStateFlow(InpaintModel.TELEA)
    val isDownloadingLaMaModel = MutableStateFlow(false)
    val lamaDownloadProgress = MutableStateFlow(0f)
    val userMessage = MutableStateFlow<UiMessage?>(null)
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
    /** Job render komposit eyedropper; dibatalkan saat cancel/ganti mode. */
    private var eyedropperJob: Job? = null
    /** Serialkan flatten agar tap wand cepat tidak recycle-balapan. */
    private val flattenMutex = Mutex()

    fun invalidateFlattenedCache() {
        isFlattenedDirty = true
    }

    suspend fun flattenForSelection(): Bitmap? = flattenMutex.withLock {
        val base = baseBitmap.value ?: return@withLock null
        val cached = cachedFlattenedBitmap
        if (!isFlattenedDirty && cached != null && !cached.isRecycled &&
            cached.width == base.width && cached.height == base.height
        ) {
            return@withLock cached
        }
        cachedFlattenedBitmap?.let { if (!it.isRecycled) it.recycle() }
        prepareImageEffects(layers.value)
        val flattened = exporter.exportToBitmap(
            base,
            layers.value,
            imageBitmapFor = { resolveImageBitmap(it) },
                    imageGlowFor = { resolveImageGlow(it) },
            imageWarpFor = { resolveExportWarpedImage(it) },
            textWarpFor = { resolveExportWarpedText(it) },
            imageGlowWarpFor = { resolveExportWarpedImageGlow(it) },
            textMeshFor = { resolveExportTextMesh(it) },
            imageMeshGlowFor = { meshGlowData(it) },
            fontLookup = exportFontLookup
        )
        cachedFlattenedBitmap = flattened
        isFlattenedDirty = false
        return@withLock flattened
    }

    fun startEyedropper(onColorSelected: (Int) -> Unit) {
        // Batalkan render lama dulu (B20): tanpa ini job Default yang telat
        // selesai menimpa compositeBitmap sesudah cancel (leak + race).
        eyedropperJob?.cancel()
        eyedropperJob = null
        val base = baseBitmap.value ?: return
        compositeBitmap?.let { if (!it.isRecycled) it.recycle() }
        compositeBitmap = null
        eyedropperTargetConsumer = onColorSelected

        val initialPt = Offset(base.width / 2f, base.height / 2f)
        eyedropperCanvasPt.value = initialPt
        val initialColor = ColorUtils.samplePixelColor(base, initialPt.x, initialPt.y) ?: android.graphics.Color.BLACK
        sampledColorPreview.value = initialColor
        isEyedropperActive.value = true

        eyedropperJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                prepareImageEffects(layers.value)
                val comp = exporter.exportToBitmap(
                    base,
                    layers.value,
                    imageBitmapFor = { resolveImageBitmap(it) },
                    imageGlowFor = { resolveImageGlow(it) },
                    imageWarpFor = { resolveExportWarpedImage(it) },
                    textWarpFor = { resolveExportWarpedText(it) },
                    imageGlowWarpFor = { resolveExportWarpedImageGlow(it) },
                    textMeshFor = { resolveExportTextMesh(it) },
                    imageMeshGlowFor = { meshGlowData(it) },
                    fontLookup = exportFontLookup
                )
                // Dibatalkan saat render → buang, jangan assign (B20).
                if (!isActive) {
                    try { comp.recycle() } catch (_: Exception) {}
                    return@launch
                }
                // Tulis field bersama dari Main saja (B20).
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    compositeBitmap = comp
                    val compColor = ColorUtils.samplePixelColor(comp, initialPt.x, initialPt.y) ?: initialColor
                    sampledColorPreview.value = compColor
                }
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
        eyedropperJob?.cancel()
        eyedropperJob = null
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
                    growBoxToFit(layer.copy(text = newText))
                } else {
                    layer
                }
            }
            if (saveUndo) {
                autoSave()
            }
        }
    }

    /**
     * Besarkan box (bila eksplisit) agar konten muat — teks yang diperbesar
     * tidak boleh nglebihin kotak. Hanya membesar, tidak mengecil.
     */
    private fun growBoxToFit(layer: Layer.TextLayer): Layer.TextLayer {
        val bw = layer.boxWidth ?: return layer
        val bh = layer.boxHeight ?: return layer
        if (bw <= 0f || bh <= 0f) return layer
        val minW = textRenderer.getMinBoxWidth(layer)
        val minH = textRenderer.getMinBoxHeight(layer)
        if (minW <= bw && minH <= bh) return layer
        return layer.copy(
            boxWidth = maxOf(bw, minW),
            boxHeight = maxOf(bh, minH)
        )
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

    /** Hapus semua preset bawaan sekaligus. @return jumlah yang dihapus. */
    fun deleteAllBuiltInPresets(): Int {
        return stylePresetRepository.deleteAllBuiltIns()
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
    val rawMaskFileName: String? = null,
    /** Label aksi (F3); null = proyek lama. */
    val label: String? = null
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
        val rawMaskBytes: ByteArray? = null,
        /** Label aksi untuk panel histori (F3). */
        val label: String = "",
        val timestamp: Long = 0L
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

    /** Satu entri panel histori (F3): undo terbaru dulu, lalu redo. */
    data class HistoryEntry(
        val label: String,
        val timestamp: Long,
        val isRedo: Boolean,
        /** Langkah undo()/redo() untuk mencapai state ini. */
        val steps: Int
    )

    val historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Lompat ke entri histori dengan mengulang undo()/redo(). */
    fun jumpToHistory(entry: HistoryEntry) {
        val n = entry.steps.coerceAtLeast(1)
        if (entry.isRedo) {
            repeat(n) {
                if (!canRedo.value) return
                redo()
            }
        } else {
            repeat(n) {
                if (!canUndo.value) return
                undo()
            }
        }
    }

    init {
        // Resolver font untuk pengukuran internal (box auto-grow) agar metrik
        // font impor benar, bukan fallback default.
        textRenderer.customFontPathResolver = { name ->
            allFonts.value.find { it.name == name }?.filePath
                ?: allFonts.value.find { it.name.equals(name, ignoreCase = true) }?.filePath
        }
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
            val undo = undoStack.toList()
            val redo = redoStack.toList()
            historyEntries.value = buildList {
                undo.asReversed().forEachIndexed { i, s ->
                    add(HistoryEntry(s.label.ifBlank { "Edit" }, s.timestamp, false, i + 1))
                }
                redo.asReversed().forEachIndexed { i, s ->
                    add(HistoryEntry(s.label.ifBlank { "Edit" }, s.timestamp, true, i + 1))
                }
            }
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

    fun saveUndoSnapshot(label: String = "") {
        val snapshot = HistorySnapshot(
            layers = layers.value,
            baseBitmap = baseBitmap.value,
            maskBytes = getMaskByteArray(),
            rawMaskBytes = getRawMaskByteArray(),
            label = label,
            timestamp = System.currentTimeMillis()
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

    /** Generasi load bitmap histori; load basi dari undo cepat tidak dipakai. */
    private var bitmapLoadGen = 0L

    private fun restoreSnapshot(snapshot: HistorySnapshot) {
        layers.value = snapshot.layers
        // Jaga seleksi agar tidak dangling setelah undo/redo.
        val current = selectedLayerId.value
        if (current != null && snapshot.layers.none { it.id == current }) {
            selectedLayerId.value = snapshot.layers.lastOrNull()?.id
        }
        invalidateFlattenedCache()
        // Decode bitmap histori di IO (B2): undo tetap instan, gambar
        // menyusul. Generasi mencegah load basi menimpa yang baru.
        val immediate = snapshot.baseBitmap?.takeIf { !it.isRecycled }
        if (immediate != null) {
            bitmapLoadGen++
            baseBitmap.value = immediate
        } else if (snapshot.bitmapFilePath != null) {
            val gen = ++bitmapLoadGen
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val bmp = snapshot.getOrLoadBitmap() ?: return@launch
                if (gen == bitmapLoadGen) {
                    baseBitmap.value = bmp
                } else if (bmp !== snapshot.baseBitmap) {
                    try { bmp.recycle() } catch (_: Exception) {}
                }
            }
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
                    // Sumber tunggal: base_image.png (B8). thumbnailPath hanya
                    // untuk kartu Home, bukan sumber gambar dasar.
                    val baseFile =
                        fileStore.baseImage(proj.id).takeIf { it.exists() }

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
                        // Baru dimuat dari disk = identik dengan file: catat agar
                        // save berikutnya (tanpa edit) skip kompresi ulang PNG.
                        lastSavedBaseRef = loadedBmp
                        lastSavedBaseProj = proj.id
                        setupCanvasSize(loadedBmp.width, loadedBmp.height)

                        // Deserialize + histori di IO (B2): puluhan PNG full-res.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val deserialized = serializer.deserialize(proj.layersJson)
                            applyLoadedLayers(deserialized, proj.id)
                        }

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

                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val deserialized = serializer.deserialize(proj.layersJson)
                            applyLoadedLayers(deserialized, proj.id)
                        }
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
        if (project.value == null) return
        // Dipanggil dari coroutine yang sudah di-IO; kunci agar tidak balapan
        // dengan flush/exit yang memakai file base_image.png.tmp yang sama.
        saveMutex.withLock {
            // Baca DI DALAM lock (B10): snapshot di luar bisa menimpa judul
            // baru dengan judul lama (last-writer-wins).
            val currentProj = project.value ?: return@withLock
            val currentBmp = baseBitmap.value
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

    /**
     * Path thumbnail kanonis (B8): thumbnail.jpg bila ada, kalau tidak
     * pertahankan yang lama (proyek lama menunjuk base_image.png — tetap
     * valid dibaca dengan sampling di Home).
     */
    private fun thumbnailFor(projId: String, fallback: String?): String? {
        val thumb = fileStore.thumbnail(projId)
        return if (thumb.exists() && thumb.length() > 0) thumb.absolutePath else fallback
    }

    private suspend fun saveProjectInternal(currentProj: ProjectEntity, currentBmp: Bitmap?) {
        val layersToSave = persistImageLayersInternal(currentProj.id)
        if (!baseImageSuspect) {
            saveBaseBitmapToDiskInternal(currentProj.id, currentBmp)
        }
        persistSelectedLayerInternal(currentProj.id, selectedLayerId.value)
        val json = serializer.serialize(layersToSave)
        val updatedProj = currentProj.copy(
            layersJson = json,
            thumbnailPath = thumbnailFor(currentProj.id, currentProj.thumbnailPath)
        )
        project.value = updatedProj
        if (!repository.saveProject(updatedProj)) {
            userMessage.value = UiMessage("Perubahan gagal disimpan ke database.", UiMessage.Kind.ERROR)
        }
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
        fileStore.selectedLayerFile(projId)

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

    /** Referensi base bitmap yang terakhir tersimpan (skip kompresi ulang). */
    private var lastSavedBaseRef: Bitmap? = null
    private var lastSavedBaseProj: String? = null

    private fun saveBaseBitmapToDiskInternal(projId: String, bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        try {
            val imageFile = fileStore.baseImage(projId)
            // Bitmap yang sama (semua mutasi mengganti objek, tidak ada yang
            // mengubah in-place) tidak perlu dikompresi ulang tiap save.
            if (projId == lastSavedBaseProj && bmp === lastSavedBaseRef &&
                imageFile.exists() && imageFile.length() > 0
            ) {
                return
            }
            if (fileStore.writeBitmapAtomic(imageFile, bmp)) {
                lastSavedBaseRef = bmp
                lastSavedBaseProj = projId
                refreshThumbnail(projId, bmp)
                Logger.d("Atomic save base_image.png successful: ${imageFile.length()} bytes, dimensions=${bmp.width}x${bmp.height}")
            }
        } catch (e: Exception) {
            Logger.e("Error saving base bitmap to disk: ${e.message}", e)
        }
    }

    /**
     * Tulis ulang thumbnail JPEG kecil setiap base berubah (B8).
     * Dipanggil dengan saveMutex dipegang, di thread IO.
     */
    private fun refreshThumbnail(projId: String, bmp: Bitmap) {
        try {
            val thumbFile = fileStore.thumbnail(projId)
            com.mochits.app.util.writeThumbnail(bmp, thumbFile)
        } catch (t: Throwable) {
            Logger.e("Error refreshing thumbnail: ${t.message}", t)
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
                        val file = fileStore.layerFile(projId, layer.id)
                        if (fileStore.writeBitmapAtomic(file, bmp)) {
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
            val dir = fileStore.layersDir(projId)
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
    private fun healImagePaths(projId: String, snapshotLayers: List<Layer>): List<Layer> {
        val pathsById = layers.value
            .filterIsInstance<Layer.ImageLayer>()
            .associate { it.id to it.imagePath }
        return snapshotLayers.map { layer ->
            if (layer is Layer.ImageLayer && layer.imagePath == null) {
                // Layer hanya hidup di snapshot (sudah dihapus dari daftar):
                // cari filenya langsung di disk (B17), jangan null buta.
                val fixed = pathsById[layer.id] ?: resolveSnapshotImagePath(projId, layer.id)
                if (fixed != null) layer.copy(imagePath = fixed) else layer
            } else layer
        }
    }

    /** File layer berdasarkan id, tanpa lewat layers.value saat ini. */
    private fun resolveSnapshotImagePath(projId: String, layerId: String): String? {
        val f = fileStore.layerFile(projId, layerId)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    /**
     * Bitmap histori yang sama (objek identik — snapshot berbagi referensi
     * baseBitmap) hanya dikompresi sekali. Tanpa ini SETIAP save menulis
     * ulang semua PNG histori sehingga tombol back terasa mati.
     * Dipanggil dengan saveMutex dipegang.
     */
    private val historyBitmapFiles = java.util.IdentityHashMap<Bitmap, String>()

    private fun persistHistoryBitmap(historyDir: File, bmp: Bitmap): String? {
        val known = historyBitmapFiles[bmp]
        if (known != null) {
            val f = File(historyDir, known)
            if (f.exists() && f.length() > 0) return known
        }
        return try {
            val fName = "hist_${java.util.UUID.randomUUID()}.png"
            val tmpFile = File(historyDir, "$fName.tmp")
            java.io.FileOutputStream(tmpFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                try { out.fd.sync() } catch (_: Exception) {}
            }
            val file = File(historyDir, fName)
            if (atomicReplace(tmpFile, file)) {
                historyBitmapFiles[bmp] = fName
                fName
            } else null
        } catch (t: Throwable) {
            Logger.e("Error compressing history bitmap: ${t.message}", t)
            null
        }
    }

    private fun syncHistoryToDiskInternal(projId: String) {
        try {
            val historyDir = fileStore.historyDir(projId)
            val referencedFiles = mutableSetOf<String>()

            val undoSnapshotList = synchronized(undoStack) { undoStack.toList() }
            val redoSnapshotList = synchronized(undoStack) { redoStack.toList() }

            val undoEntries = undoSnapshotList.map { snapshot ->
                var fileName: String? = snapshot.bitmapFilePath?.let { File(it).name }
                val bmp = snapshot.baseBitmap
                if (bmp != null && !bmp.isRecycled) {
                    persistHistoryBitmap(historyDir, bmp)?.let { fileName = it }
                }
                val validName = fileName
                if (validName != null) referencedFiles.add(validName)
                HistoryStepEntry(
                    layersJson = serializer.serialize(healImagePaths(projId, snapshot.layers)),
                    bitmapFileName = fileName,
                    label = snapshot.label.ifBlank { null }
                )
            }

            val redoEntries = redoSnapshotList.map { snapshot ->
                var fileName: String? = snapshot.bitmapFilePath?.let { File(it).name }
                val bmp = snapshot.baseBitmap
                if (bmp != null && !bmp.isRecycled) {
                    persistHistoryBitmap(historyDir, bmp)?.let { fileName = it }
                }
                val validName = fileName
                if (validName != null) referencedFiles.add(validName)
                HistoryStepEntry(
                    layersJson = serializer.serialize(healImagePaths(projId, snapshot.layers)),
                    bitmapFileName = fileName,
                    label = snapshot.label.ifBlank { null }
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
            // Prune cache dedup: entri yang filenya sudah tak terpakai dibuang
            // agar map tidak tumbuh dan bitmap recycled tak tersentuh lagi.
            historyBitmapFiles.entries.removeAll { (_, name) ->
                !referencedFiles.contains(name) || !File(historyDir, name).exists()
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

    private suspend fun loadHistoryFromDisk(projId: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        loadHistoryFromDiskInternal(projId)
    }

    private fun loadHistoryFromDiskInternal(projId: String) {
        try {
            val historyDir = fileStore.historyDir(projId)
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
                    // Daftarkan bitmap hasil load ke map dedup agar save
                    // berikutnya tidak mengompresi ulang PNG histori ini.
                    if (loadedBmp != null && bmpPath != null) {
                        historyBitmapFiles[loadedBmp] = File(bmpPath).name
                    }
                    undoStack.addLast(
                        HistorySnapshot(
                            layers = snapshotLayers,
                            baseBitmap = loadedBmp,
                            bitmapFilePath = bmpPath,
                            label = entry.label ?: ""
                        )
                    )
                }

                redoStack.clear()
                manifest.redoSteps.forEach { entry ->
                    val snapshotLayers = serializer.deserialize(entry.layersJson)
                    val bmpPath = entry.bitmapFileName?.let { File(historyDir, it).absolutePath }
                    val loadedBmp = loadHistoryBitmap(bmpPath)
                    // Daftarkan bitmap hasil load ke map dedup agar save
                    // berikutnya tidak mengompresi ulang PNG histori ini.
                    if (loadedBmp != null && bmpPath != null) {
                        historyBitmapFiles[loadedBmp] = File(bmpPath).name
                    }
                    redoStack.addLast(
                        HistorySnapshot(
                            layers = snapshotLayers,
                            baseBitmap = loadedBmp,
                            bitmapFilePath = bmpPath,
                            label = entry.label ?: ""
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
                    val baseFile =
                        fileStore.baseImage(currentProj.id).takeIf { it.exists() }

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
                            // Sama: hasil decode = isi file, catat agar exit tanpa
                            // edit tidak mengompresi ulang base_image.png.
                            lastSavedBaseRef = loadedBmp
                            lastSavedBaseProj = currentProj.id
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
                        val updated = currentProj.copy(
                            width = bitmap.width,
                            height = bitmap.height,
                            thumbnailPath = thumbnailFor(projectId, currentProj.thumbnailPath),
                            layersJson = serializer.serialize(layers.value)
                        )
                        project.value = updated
                        if (!repository.saveProject(updated)) {
                            userMessage.value = UiMessage("Gagal menyimpan proyek ke database.", UiMessage.Kind.ERROR)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("Error setting base image: ${e.message}", e)
                userMessage.value = UiMessage("Gagal memuat gambar: ${e.message}", UiMessage.Kind.ERROR)
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
            // Mask dipertahankan saat ganti alat agar seleksi bisa
            // dilanjutkan/dikombinasikan (brush -> lasso, dst).
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
            if (!repository.saveProject(updated)) {
                userMessage.value = UiMessage("Gagal menyimpan judul proyek.", UiMessage.Kind.ERROR)
            }
        }
    }

        fun addImageLayer(bitmap: Bitmap) {
            saveUndoSnapshot("Tambah gambar")
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
                userMessage.value = UiMessage("Model LaMa berhasil diunduh.", UiMessage.Kind.SUCCESS)
            } else {
                userMessage.value = UiMessage(
                    "Gagal mengunduh model LaMa. Periksa koneksi internet Anda.",
                    UiMessage.Kind.ERROR,
                    lamaModelManager.lastDownloadError.value?.toFormattedString()
                )
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
                        userMessage.value = UiMessage("File model LaMa rusak. Mengunduh ulang...", UiMessage.Kind.WARNING)
                    } else {
                        userMessage.value = UiMessage("Mengunduh model LaMa...", UiMessage.Kind.INFO)
                    }
                    isDownloadingLaMaModel.value = true
                    val downloaded = lamaModelManager.downloadModel { progress ->
                        lamaDownloadProgress.value = progress
                    }
                    isDownloadingLaMaModel.value = false
                    if (!downloaded) {
                        userMessage.value = UiMessage(
                        "Gagal mengunduh model LaMa. Periksa koneksi internet atau unduh via Pengaturan.",
                        UiMessage.Kind.ERROR,
                        lamaModelManager.lastDownloadError.value?.toFormattedString()
                    )
                        isProcessingInpaint.value = false
                        return@launch
                    }
                }

                saveUndoSnapshot("Hapus objek (LaMa)")
                try {
                    when (val lamaResult = lamaInpaintEngine.inpaintLaMa(currentBase, tools.maskBitmap)) {
                        is Result.Success -> {
                            baseBitmap.value = lamaResult.data
                            baseImageSuspect = false
                            // File terbukti bisa dibuka sesinya: kalibrasi validasi.
                            lamaModelManager.confirmModelUsable()
                            tools.clearMask()
                            autoSave()
                        }
                        is Result.Error -> {
                            val msg = lamaResult.exception.message ?: "Memori rendah"
                            userMessage.value = if (msg.contains("memori rendah", ignoreCase = true) || lamaResult.exception is OutOfMemoryError) {
                                UiMessage("Inpainting gagal karena memori rendah. Coba pilih area yang lebih kecil. Mengalihkan ke Telea...", UiMessage.Kind.WARNING)
                            } else {
                                UiMessage("Inference LaMa gagal: $msg. Mengalihkan ke Telea...", UiMessage.Kind.WARNING)
                            }
                            runTeleaFallback(currentBase, tools)
                        }
                        else -> {}
                    }
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    userMessage.value = UiMessage("Inpainting gagal karena memori rendah. Coba pilih area yang lebih kecil. Mengalihkan ke Telea...", UiMessage.Kind.WARNING)
                    runTeleaFallback(currentBase, tools)
                } catch (t: Throwable) {
                    userMessage.value = UiMessage("Inference LaMa gagal: ${t.message}. Mengalihkan ke Telea...", UiMessage.Kind.WARNING)
                    runTeleaFallback(currentBase, tools)
                }
            } else {
                saveUndoSnapshot("Hapus objek (Telea)")
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
                userMessage.value = UiMessage("Gagal memproses inpaint Telea: ${result.exception.message}", UiMessage.Kind.ERROR)
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
        shape: com.mochits.app.model.TextContainerShape? = null,
            viewportWidth: Float = 0f,
            viewportHeight: Float = 0f
        ) {
            saveUndoSnapshot("Tambah teks")
        // Pilihan bentuk di dialog diingat untuk teks berikutnya.
        if (shape != null) {
            defaultTextShape.value = shape
        }
        val canvasW = baseBitmap.value?.width ?: project.value?.width ?: 1080
        val canvasH = baseBitmap.value?.height ?: project.value?.height ?: 1920

        val proportionalFontSize = (canvasW * 0.035f).coerceIn(50f, 70f)
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
        // Teks baru jangan lebih lebar dari kanvas: bungkus jadi multi-baris
        // (font TIDAK dikecilkan). Berlaku untuk teks manual maupun script.
        val useShape = shape ?: defaultTextShape.value
        var fitBoxWidth: Float? = null
        val maxW = canvasW - 64f
        if (bounds.width() > maxW && maxW > 100f) {
            fitBoxWidth = maxW
        }
        val measured = if (fitBoxWidth != null) {
            textRenderer.getTextBounds(text, effectiveStyle, 0f, 0f, useShape, fitBoxWidth, null)
        } else bounds
        val textWidth = measured.width()
        val textHeight = measured.height()

        val finalX = posX - (textWidth / 2f)
        val finalY = posY - (textHeight / 2f)

    val newLayer = Layer.TextLayer(
    id = UUID.randomUUID().toString(),
    name = "Text ${layers.value.size + 1}",
    x = finalX,
    y = finalY,
    text = text,
    style = effectiveStyle,
    textContainerShape = useShape,
    boxWidth = fitBoxWidth
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
        // Tanpa seleksi: atur default untuk teks baru (diambil saat addTextLayer).
        // Ini jalur eksplisit, bukan kebocoran: edit pada layer terpilih
        // di bawah tidak menyentuh default.
        val selectedId = selectedLayerId.value
        if (selectedId == null) {
            defaultTextStyle.value = style
            return
        }
        // Font yang dipilih menempel sebagai default teks baru berikutnya.
        defaultTextStyle.value = defaultTextStyle.value.let { cur ->
            if (cur.fontName != style.fontName || cur.fontStyle != style.fontStyle) {
                cur.copy(fontName = style.fontName, fontStyle = style.fontStyle)
            } else cur
        }
        if (saveUndo) {
            saveUndoSnapshot()
        }
        layers.value = layers.value.map { layer ->
            if (layer.id == selectedId && layer is Layer.TextLayer) {
                growBoxToFit(layer.copy(style = style))
            } else {
                layer
            }
        }
        if (saveUndo) {
            autoSave()
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

    // ---------- Efek gambar (ImageLayer) ----------

    private data class ImageBlurEntry(
        val srcBitmap: Bitmap,
        val radius: Float,
        val angle: Float,
        val result: Bitmap
    )

    private val imageBlurCache = mutableMapOf<String, ImageBlurEntry>()
    private val imageBlurJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Bertambah tiap hasil blur gambar siap; pemicu redraw kanvas. */
    val imageEffectRevision = MutableStateFlow(0)

    /**
     * Update parameter efek satu ImageLayer. Pola undo sama seperti teks:
     * snapshot di drag-start (onSliderDragStart), autosave di drag-end.
     */
    fun updateImageLayer(updated: Layer.ImageLayer, saveUndo: Boolean = true) {
        if (saveUndo) {
            saveUndoSnapshot()
        }
        layers.value = layers.value.map { layer ->
            if (layer.id == updated.id && layer is Layer.ImageLayer) updated else layer
        }
        if (saveUndo) {
            autoSave()
        }
        invalidateFlattenedCache()
        refreshImageMotionBlur(updated)
        refreshImageGlow(updated)
    }

    /**
     * Bitmap yang ditampilkan/diekspor untuk layer gambar:
     * hasil motion blur bila aktif dan siap, oreginal bila belum/tidak.
     */
    fun resolveImageBitmap(layer: Layer.ImageLayer): Bitmap? {
        val src = layer.bitmap ?: return null
        if (src.isRecycled) return null
        if (layer.motionBlurRadius <= 0f) return src
        val entry = imageBlurCache[layer.id]
        if (entry != null && entry.srcBitmap === src && !entry.result.isRecycled &&
            entry.radius == layer.motionBlurRadius && entry.angle == layer.motionBlurAngle
        ) {
            return entry.result
        }
        return src
    }

    /** Pastikan cache blur sesuai parameter; dipanggil tiap update + sebelum ekspor. */
    fun refreshImageMotionBlur(layer: Layer.ImageLayer) {
        imageBlurJobs.remove(layer.id)?.cancel()
        val src = layer.bitmap
        if (src == null || src.isRecycled || layer.motionBlurRadius <= 0f) {
            if (layer.motionBlurRadius <= 0f) {
                imageBlurCache.remove(layer.id)?.let { recycleBlurEntry(it) }
            }
            return
        }
        val cached = imageBlurCache[layer.id]
        if (cached != null && cached.srcBitmap === src && !cached.result.isRecycled &&
            cached.radius == layer.motionBlurRadius && cached.angle == layer.motionBlurAngle
        ) {
            return
        }
        val radius = layer.motionBlurRadius
        val angle = layer.motionBlurAngle
        imageBlurJobs[layer.id] = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val blurred = ImageEffects.motionBlurredBitmap(src, radius, angle) ?: return@launch
            val current = layers.value.find { it.id == layer.id } as? Layer.ImageLayer ?: run {
                try { blurred.recycle() } catch (_: Exception) {}
                return@launch
            }
            if (current.bitmap !== src || current.motionBlurRadius != radius || current.motionBlurAngle != angle) {
                try { blurred.recycle() } catch (_: Exception) {}
                return@launch
            }
            imageBlurCache[layer.id]?.let { recycleBlurEntry(it) }
            imageBlurCache[layer.id] = ImageBlurEntry(src, radius, angle, blurred)
            imageEffectRevision.value += 1
            invalidateFlattenedCache()
        }
    }

    /** Hitung semua blur/glow yang tertunda; dipanggil sebelum ekspor/flatten. */
    suspend fun prepareImageEffects(layers: List<Layer>) {
        layers.filterIsInstance<Layer.ImageLayer>()
            .filter { it.bitmap != null && !it.bitmap!!.isRecycled }
            .forEach { layer ->
                val src = layer.bitmap!!
                if (layer.motionBlurRadius > 0f) {
                    val cached = imageBlurCache[layer.id]
                    val valid = cached != null && cached.srcBitmap === src && !cached.result.isRecycled &&
                        cached.radius == layer.motionBlurRadius && cached.angle == layer.motionBlurAngle
                    if (!valid) {
                        val blurred = ImageEffects.motionBlurredBitmap(src, layer.motionBlurRadius, layer.motionBlurAngle)
                        if (blurred != null) {
                            imageBlurCache[layer.id]?.let { recycleBlurEntry(it) }
                            imageBlurCache[layer.id] = ImageBlurEntry(src, layer.motionBlurRadius, layer.motionBlurAngle, blurred)
                        }
                    }
                }
                prepareImageGlow(layer)
            }
    }

    private fun recycleBlurEntry(entry: ImageBlurEntry) {
        try {
            if (!entry.result.isRecycled) entry.result.recycle()
        } catch (_: Exception) {}
    }

    private data class ImageGlowEntry(
        val srcBitmap: Bitmap,
        val color: Int,
        val radius: Float,
        val result: Bitmap,
        val pad: Float
    )

    private val imageGlowCache = mutableMapOf<String, ImageGlowEntry>()
    private val imageGlowJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Bitmap glow + padding full-res px; null bila glow mati/belum siap. */
    fun resolveImageGlow(layer: Layer.ImageLayer): Pair<Bitmap, Float>? {
        val src = layer.bitmap ?: return null
        if (src.isRecycled) return null
        if (layer.glowRadius <= 0f || layer.glowColor == android.graphics.Color.TRANSPARENT) return null
        val entry = imageGlowCache[layer.id]
        if (entry != null && entry.srcBitmap === src && !entry.result.isRecycled &&
            entry.color == layer.glowColor && entry.radius == layer.glowRadius
        ) {
            return entry.result to entry.pad
        }
        return null
    }

    private fun refreshImageGlow(layer: Layer.ImageLayer) {
        imageGlowJobs.remove(layer.id)?.cancel()
        val src = layer.bitmap
        if (src == null || src.isRecycled ||
            layer.glowRadius <= 0f || layer.glowColor == android.graphics.Color.TRANSPARENT
        ) {
            if (layer.glowRadius <= 0f || layer.glowColor == android.graphics.Color.TRANSPARENT) {
                imageGlowCache.remove(layer.id)?.let {
                    try { if (!it.result.isRecycled) it.result.recycle() } catch (_: Exception) {}
                }
            }
            return
        }
        val cached = imageGlowCache[layer.id]
        if (cached != null && cached.srcBitmap === src && !cached.result.isRecycled &&
            cached.color == layer.glowColor && cached.radius == layer.glowRadius
        ) {
            return
        }
        val color = layer.glowColor
        val radius = layer.glowRadius
        imageGlowJobs[layer.id] = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val glow = ImageEffects.outerGlowBitmap(src, color, radius) ?: return@launch
            val current = layers.value.find { it.id == layer.id } as? Layer.ImageLayer ?: run {
                try { glow.first.recycle() } catch (_: Exception) {}
                return@launch
            }
            if (current.bitmap !== src || current.glowColor != color || current.glowRadius != radius) {
                try { glow.first.recycle() } catch (_: Exception) {}
                return@launch
            }
            imageGlowCache[layer.id]?.let {
                try { if (!it.result.isRecycled) it.result.recycle() } catch (_: Exception) {}
            }
            imageGlowCache[layer.id] = ImageGlowEntry(src, color, radius, glow.first, glow.second)
            imageEffectRevision.value += 1
            invalidateFlattenedCache()
        }
    }

    private suspend fun prepareImageGlow(layer: Layer.ImageLayer) {
        val src = layer.bitmap ?: return
        if (src.isRecycled || layer.glowRadius <= 0f ||
            layer.glowColor == android.graphics.Color.TRANSPARENT
        ) return
        val cached = imageGlowCache[layer.id]
        val valid = cached != null && cached.srcBitmap === src && !cached.result.isRecycled &&
            cached.color == layer.glowColor && cached.radius == layer.glowRadius
        if (!valid) {
            val glow = ImageEffects.outerGlowBitmap(src, layer.glowColor, layer.glowRadius)
            if (glow != null) {
                imageGlowCache[layer.id]?.let {
                    try { if (!it.result.isRecycled) it.result.recycle() } catch (_: Exception) {}
                }
                imageGlowCache[layer.id] =
                    ImageGlowEntry(src, layer.glowColor, layer.glowRadius, glow.first, glow.second)
            }
        }
    }

    private fun clearImageBlurCache(id: String) {
        imageBlurJobs.remove(id)?.cancel()
        imageBlurCache.remove(id)?.let { recycleBlurEntry(it) }
        imageGlowJobs.remove(id)?.cancel()
        imageGlowCache.remove(id)?.let {
            try { if (!it.result.isRecycled) it.result.recycle() } catch (_: Exception) {}
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
        // Ganti seleksi = keluar dari mode edit titik perspektif/warp.
        if (perspEditId.value != null && perspEditId.value != id) {
            perspEditId.value = null
        }
        if (meshEditId.value != null && meshEditId.value != id) {
            meshEditId.value = null
        }
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
                saveUndoSnapshot("Susunan layer")
            val item = list.removeAt(index)
            list.add(newIndex, item)
            layers.value = list
            autoSave()
        }
    }

        fun toggleLayerVisibility(id: String) {
            saveUndoSnapshot("Visibilitas layer")
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
            saveUndoSnapshot("Hapus layer")
        clearImageBlurCache(id)
        clearWarpCache(id)
        layers.value = layers.value.filter { it.id != id }
        if (selectedLayerId.value == id) {
            selectedLayerId.value = null
        }
        autoSave()
    }

    /**
     * Salin background jadi image layer baru (tetap di posisi, langsung aktif).
     * Bitmap dipakai bersama (aman: semua mutasi mengganti objek); file-nya
     * ditulis terpisah saat save berikutnya.
     */
    fun duplicateBackground() {
        val src = baseBitmap.value
        if (src == null || src.isRecycled) return
        saveUndoSnapshot("Duplikat background")
        val newId = UUID.randomUUID().toString()
        val copy = Layer.ImageLayer(
            id = newId,
            name = "Background copy",
            bitmap = src,
            imagePath = null
        )
        layers.value = layers.value + copy
        selectedLayerId.value = newId
        autoSave()
    }

    /**
     * Duplikat layer (teks/gambar): salinan menumpuk pas di atas aslinya
     * dan langsung aktif. File gambar ikut disalin agar salinan tidak putus
     * saat layer asli dihapus.
     */
    fun duplicateLayer(id: String) {
        val list = layers.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
            if (index == -1) return
            saveUndoSnapshot("Duplikat layer")
        val src = list[index]
        val newId = UUID.randomUUID().toString()
        val copy = when (src) {
            is Layer.TextLayer -> src.copy(
                id = newId,
                name = "${src.name} (copy)"
            )
            is Layer.ImageLayer -> {
                val newPath = duplicateImageFile(src.imagePath, newId)
                src.copy(
                    id = newId,
                    name = "${src.name} (copy)",
                    imagePath = newPath ?: src.imagePath
                )
            }
        }
        list.add(index + 1, copy)
        layers.value = list
        selectedLayerId.value = newId
        autoSave()
    }

    /** Salin file bitmap layer agar salinan punya file sendiri. */
    private fun duplicateImageFile(srcPath: String?, newId: String): String? {        if (srcPath == null) return null
        return try {
            val srcFile = File(srcPath)
            if (!srcFile.isFile || srcFile.length() <= 0) return null
            val projId = project.value?.id ?: return null
            val dst = fileStore.layerFile(projId, newId)
            srcFile.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (t: Throwable) {
            Logger.e("Error duplicating image file: ${t.message}", t)
            null
        }
    }

    // ---------- Perspektif (warp 4-titik, gambar + teks) ----------

    /** Layer yang sedang dalam mode edit titik perspektif (kanvas). */
    val perspEditId = MutableStateFlow<String?>(null)

    fun setPerspEdit(id: String?) {
        perspEditId.value = id
        if (id != null) meshEditId.value = null
    }

    /** Layer yang sedang dalam mode edit warp mesh (kanvas). */
    val meshEditId = MutableStateFlow<String?>(null)

    fun setMeshEdit(id: String?) {
        meshEditId.value = id
        if (id != null) perspEditId.value = null
    }

    /**
     * Geser satu sudut quad ternormalisasi [0..1]. Tanpa snapshot (caller
     * wajib [onSliderDragStart] sekali di awal drag) agar drag mulus.
     */
    fun updatePerspectiveCorner(layerId: String, index: Int, nx: Float, ny: Float) {
        if (index !in 0..3) return
        val cx = nx.coerceIn(-0.5f, 1.5f)
        val cy = ny.coerceIn(-0.5f, 1.5f)
        layers.value = layers.value.map { layer ->
            if (layer.id != layerId) return@map layer
            val wasNull = when (layer) {
                is Layer.TextLayer -> layer.perspQuad == null
                is Layer.ImageLayer -> layer.perspQuad == null
            }
            val base = when (layer) {
                is Layer.TextLayer -> layer.perspQuad
                is Layer.ImageLayer -> layer.perspQuad
            } ?: listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            if (base.size != 8) return@map layer
            val next = base.toMutableList()
            next[index * 2] = cx
            next[index * 2 + 1] = cy
            when (layer) {
                // Mesh & perspektif eksklusif: mengaktifkan satu mematikan satunya.
                is Layer.TextLayer -> layer.copy(perspQuad = next, meshGrid = if (wasNull) null else layer.meshGrid)
                is Layer.ImageLayer -> layer.copy(perspQuad = next, meshGrid = if (wasNull) null else layer.meshGrid)
            }
        }
        invalidateFlattenedCache()
    }
    /** Kembalikan ke persegi (matikan perspektif) untuk satu layer. */
        fun resetPerspective(layerId: String) {
            saveUndoSnapshot("Reset perspektif")
        clearWarpCache(layerId)
        layers.value = layers.value.map { layer ->
            if (layer.id != layerId) layer
            else when (layer) {
                is Layer.TextLayer -> layer.copy(perspQuad = null)
                is Layer.ImageLayer -> layer.copy(perspQuad = null)
            }
        }
        autoSave()
        invalidateFlattenedCache()
    }

    // ---------- Warp mesh 4x4 (gambar + teks) ----------

    /**
     * Geser satu titik grid ternormalisasi [0..1]. Tanpa snapshot (caller
     * wajib [onSliderDragStart] sekali di awal drag) agar drag mulus.
     */
    fun updateMeshPoint(layerId: String, index: Int, nx: Float, ny: Float) {
        val n = Perspective.MESH_N
        if (index !in 0 until n * n) return
        val cx = nx.coerceIn(-0.5f, 1.5f)
        val cy = ny.coerceIn(-0.5f, 1.5f)
        layers.value = layers.value.map { layer ->
            if (layer.id != layerId) return@map layer
            val wasNull = when (layer) {
                is Layer.TextLayer -> layer.meshGrid == null
                is Layer.ImageLayer -> layer.meshGrid == null
            }
            val base = when (layer) {
                is Layer.TextLayer -> layer.meshGrid
                is Layer.ImageLayer -> layer.meshGrid
            } ?: Perspective.identityGrid()
            if (base.size != 2 * n * n) return@map layer
            val next = base.toMutableList()
            next[index * 2] = cx
            next[index * 2 + 1] = cy
            when (layer) {
                // Mesh & perspektif eksklusif: mengaktifkan satu mematikan satunya.
                is Layer.TextLayer -> layer.copy(
                    meshGrid = next,
                    perspQuad = if (wasNull) null else layer.perspQuad
                )
                is Layer.ImageLayer -> layer.copy(
                    meshGrid = next,
                    perspQuad = if (wasNull) null else layer.perspQuad
                )
            }
        }
        invalidateFlattenedCache()
    }

    /** Kembalikan ke grid seragam (matikan warp mesh) untuk satu layer. */
        fun resetMesh(layerId: String) {
            saveUndoSnapshot("Reset warp")
        layers.value = layers.value.map { layer ->
            if (layer.id != layerId) layer
            else when (layer) {
                is Layer.TextLayer -> layer.copy(meshGrid = null)
                is Layer.ImageLayer -> layer.copy(meshGrid = null)
            }
        }
        autoSave()
        invalidateFlattenedCache()
    }

    /**
     * Data glow + grid yang dipetakan ke ruang glow untuk warp mesh gambar.
     * Bitmap glow menutupi box konten + pad di tiap sisi.
     */
    fun meshGlowData(layer: Layer.ImageLayer): ProjectExporter.MeshGlow? {
        val grid = layer.meshGrid ?: return null
        if (!Perspective.isMeshActive(grid)) return null
        val (glowBmp, pad) = resolveImageGlow(layer) ?: return null
        if (glowBmp.isRecycled) return null
        val src = resolveImageBitmap(layer) ?: return null
        if (src.isRecycled) return null
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        if (sw <= 0f || sh <= 0f) return null
        val gw = sw + 2f * pad
        val gh = sh + 2f * pad
        if (gw <= 0f || gh <= 0f) return null
        val mapped = List(grid.size) { i ->
            val q = grid[i]
            if (i % 2 == 0) ((q * sw + pad) / gw).coerceIn(-0.5f, 1.5f)
            else ((q * sh + pad) / gh).coerceIn(-0.5f, 1.5f)
        }
        return ProjectExporter.MeshGlow(glowBmp, pad, mapped)
    }

    /** Render datar teks resolusi ekspor untuk warp mesh (posisi absolut). */
    fun resolveExportTextMesh(layer: Layer.TextLayer): ProjectExporter.TextMeshDraw? {
        if (!Perspective.isMeshActive(layer.meshGrid)) return null
        exporter.textRenderer.customFontPathResolver = exportFontLookup
        val (flat, origin) = exporter.textRenderer.renderToBitmap(layer) ?: return null
        if (flat.isRecycled) return null
        return ProjectExporter.TextMeshDraw(flat, origin.x, origin.y)
    }

    /** Cache warp per layer: dihitung ulang hanya bila sumber/quad berubah. */
    private data class WarpEntry(
        val src: Bitmap,
        val srcVersion: Long,
        val quad: List<Float>,
        val result: Bitmap,
        val offX: Float,
        val offY: Float,
        val scale: Float,
        val maxDim: Int
    )

    private val warpCache = mutableMapOf<String, WarpEntry>()

    private fun recycleWarpEntry(entry: WarpEntry) {
        try { entry.result.recycle() } catch (_: Exception) {}
    }

    fun clearWarpCache(id: String) {
        // Kunci cache "$id@$maxDim" (plus namespace ":glow"): bersihkan semua.
        val it = warpCache.entries.iterator()
        while (it.hasNext()) {
            val (k, entry) = it.next()
            if (k == id || k.startsWith("$id@") || k.startsWith("$id:glow")) {
                recycleWarpEntry(entry)
                it.remove()
            }
        }
        lastWarpNote.remove(id)
    }

    /** Laporkan kegagalan warp sekali per transisi (S3): jangan spam tiap frame drag. */
    private val lastWarpNote = mutableMapOf<String, String?>()

    private fun noteWarpFailure(layerId: String, outcome: Perspective.WarpOutcome) {
        val key = when (outcome) {
            is Perspective.WarpOutcome.Ok -> null
            Perspective.WarpOutcome.Inactive -> null
            is Perspective.WarpOutcome.TooLarge -> "too-large"
            is Perspective.WarpOutcome.Degenerate -> "degenerate"
        }
        if (lastWarpNote[layerId] == key) return
        lastWarpNote[layerId] = key
        when (outcome) {
            is Perspective.WarpOutcome.TooLarge -> userMessage.value = UiMessage(
                "Area warp terlalu besar, perkecil sudut.",
                UiMessage.Kind.WARNING
            )
            is Perspective.WarpOutcome.Degenerate -> userMessage.value = UiMessage(
                "Bentuk warp tidak valid (titik bertumpuk/bersilang).",
                UiMessage.Kind.WARNING
            )
            else -> {}
        }
    }

    /**
     * Warp [src] mengikuti [quad]; null bila quad tidak aktif/invalid.
     * @param srcVersion versi konten (wajib untuk bitmap yang dipakai ulang
     * dan digambar ulang in-place, mis. render teks).
     */
    fun warpedBitmap(
        layerId: String,
        src: Bitmap,
        srcVersion: Long,
        quad: List<Float>,
        maxDim: Int = 640
    ): WarpedLayerDraw? {
        if (!Perspective.isActive(quad)) {
            clearWarpCache(layerId)
            return null
        }
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val key = "$layerId@$maxDim"
        val cached = warpCache[key]
        if (cached != null && cached.src === src && cached.srcVersion == srcVersion &&
            cached.quad == quad && cached.maxDim == maxDim && !cached.result.isRecycled
        ) {
            return WarpedLayerDraw(cached.result, cached.offX, cached.offY, cached.scale)
        }
        return try {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val outcome = Perspective.warpPixelsOutcome(px, w, h, quad, maxDim)
            val warped = (outcome as? Perspective.WarpOutcome.Ok)?.pixels ?: run {
                warpCache.remove(key)?.let { recycleWarpEntry(it) }
                noteWarpFailure(layerId, outcome)
                return null
            }
            noteWarpFailure(layerId, outcome)
            val bmp = Bitmap.createBitmap(warped.pixels, warped.width, warped.height, Bitmap.Config.ARGB_8888)
            warpCache.remove(key)?.let { recycleWarpEntry(it) }
            val entry = WarpEntry(src, srcVersion, quad.toList(), bmp, warped.offsetX, warped.offsetY, warped.scale, maxDim)
            warpCache[key] = entry
            return WarpedLayerDraw(bmp, warped.offsetX, warped.offsetY, warped.scale)
        } catch (t: Throwable) {
            Logger.e("Error warping layer: ${t.message}", t)
            null
        }
    }

    /**
     * Warp untuk layer gambar (sumber = bitmap hasil resolve efek).
     * @param maxDim <0 = otomatis (640 saat titik digeser, 1600 saat diam).
     */
    fun resolveWarpedImage(layer: Layer.ImageLayer, maxDim: Int = -1): WarpedLayerDraw? {
        val quad = layer.perspQuad ?: return null
        val src = resolveImageBitmap(layer) ?: return null
        val md = if (maxDim >= 0) maxDim
        else if (perspEditId.value == layer.id) 640 else 1600
        return warpedBitmap(layer.id, src, 0L, quad, md)
    }

    /**
     * Warp glow gambar mengikuti quad yang sama. Bitmap glow menutupi box
     * konten + pad di tiap sisi, jadi quad dipetakan ke ruang glow agar
     * selaras dengan isi yang di-warp.
     */
    fun resolveWarpedImageGlow(layer: Layer.ImageLayer, maxDim: Int = -1): WarpedLayerDraw? {
        val quad = layer.perspQuad ?: return null
        if (quad.size != 8) return null
        val (glowBmp, pad) = resolveImageGlow(layer) ?: return null
        if (glowBmp.isRecycled) return null
        val src = resolveImageBitmap(layer) ?: return null
        if (src.isRecycled) return null
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        if (sw <= 0f || sh <= 0f) return null
        val gw = sw + 2f * pad
        val gh = sh + 2f * pad
        if (gw <= 0f || gh <= 0f) return null
        val md = if (maxDim >= 0) maxDim
        else if (perspEditId.value == layer.id) 640 else 1600
        val mapped = List(8) { i ->
            val q = quad[i]
            if (i % 2 == 0) ((q * sw + pad) / gw).coerceIn(-0.5f, 1.5f)
            else ((q * sh + pad) / gh).coerceIn(-0.5f, 1.5f)
        }
        return warpedBitmap("${layer.id}:glow", glowBmp, 0L, mapped, md)
    }

    /** Lookup displayName font -> filePath untuk jalur ekspor (diisi UI). */
    var exportFontLookup: ((String) -> String?)? = null

    // ---------- Antrean script TL (F-script) ----------

    /** Satu posisi antrean: halaman + baris dalam halaman. */
    data class ScriptQueue(
        val pages: List<com.mochits.app.script.ScriptParser.ScriptPage>,
        val pageIdx: Int,
        val lineIdx: Int,
        val uriString: String,
        val fileName: String
    ) {
        val currentEntry: com.mochits.app.script.ScriptParser.ScriptEntry? get() =
            pages.getOrNull(pageIdx)?.entries?.getOrNull(lineIdx)
        val pageCount: Int get() = pages.size
        val lineCount: Int get() = pages.getOrNull(pageIdx)?.entries?.size ?: 0
        /** Label halaman (mis. "P2"); kosong bila file tanpa pemisah. */
        val pageLabel: String get() = pages.getOrNull(pageIdx)?.label ?: ""
    }

    val scriptQueue = MutableStateFlow<ScriptQueue?>(null)

    private fun scriptPrefs() = context.getSharedPreferences("mochits_script_state", Context.MODE_PRIVATE)

    private fun saveScriptPosition(projId: String, uri: String, page: Int, line: Int) {
        try {
            scriptPrefs().edit()
                .putString("uri_$projId", uri)
                .putInt("page_$projId", page)
                .putInt("line_$projId", line)
                .apply()
        } catch (_: Exception) {}
    }

    /** Muat file .txt script dari URI SAF; posisi per proyek dipulihkan. */
    fun loadScriptFile(uri: android.net.Uri, displayName: String? = null) {
        val projId = project.value?.id ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                } ?: return@launch
                val repo = com.mochits.app.script.ScriptMappingRepository(context)
                repo.reload()
                val doc = com.mochits.app.script.ScriptParser.parse(
                    raw, repo.getMappings(), repo.getSeparator()
                )
                if (doc.totalEntries == 0) {
                    userMessage.value = UiMessage("File script kosong / tak ada baris bersimbol.", UiMessage.Kind.WARNING)
                    return@launch
                }
                val savedUri = scriptPrefs().getString("uri_$projId", null)
                var page = 0
                var line = 0
                if (savedUri == uri.toString()) {
                    page = scriptPrefs().getInt("page_$projId", 0)
                    line = scriptPrefs().getInt("line_$projId", 0)
                }
                val safePage = page.coerceIn(0, doc.pages.size - 1)
                val safeLine = line.coerceIn(0, (doc.pages.getOrNull(safePage)?.entries?.size ?: 1) - 1)
                scriptQueue.value = ScriptQueue(
                    pages = doc.pages,
                    pageIdx = safePage,
                    lineIdx = safeLine,
                    uriString = uri.toString(),
                    fileName = displayName ?: uri.lastPathSegment ?: "script.txt"
                )
            } catch (t: Throwable) {
                Logger.e("Gagal memuat script: ${t.message}", t)
                userMessage.value = UiMessage("Gagal membaca file script.", UiMessage.Kind.ERROR)
            }
        }
    }

    /** Masukkan baris kini ke teks terpilih + preset pasangannya (satu undo). */
    fun applyScriptCurrent() {
        val q = scriptQueue.value ?: return
        val entry = q.currentEntry ?: return
        val repo = com.mochits.app.script.ScriptMappingRepository(context)
        val presetId = repo.getMappings().firstOrNull { it.symbol == entry.symbol }?.presetId
        val preset = presetId?.takeIf { it.isNotBlank() }?.let { stylePresetRepository.getPreset(it) }
        val selected = selectedLayerId.value?.let { sid ->
            layers.value.find { it.id == sid } as? Layer.TextLayer
        }
        if (selected == null) {
            // Belum ada teks terpilih: buatkan layer baru (tengah, style preset).
            val base = defaultTextStyle.value
            val style = if (preset != null) {
                base.copy(
                    fontName = preset.fontName,
                    fontStyle = preset.fontStyle,
                    alignment = preset.alignment
                )
            } else base
            addTextLayer(
                entry.text,
                style = style,
                shape = preset?.shape,
                viewportWidth = 0f,
                viewportHeight = 0f
            )
        } else {
            if (preset != null) {
                // Preset dulu (snapshot pra-keadaan), lalu teks tanpa snapshot.
                applyStylePreset(preset)
            } else {
                saveUndoSnapshot("Script: ${entry.text.take(24)}")
            }
            updateSelectedTextContent(entry.text, saveUndo = false)
            autoSave()
        }
        advanceScript(1)
    }

    /** Lewati baris kini tanpa mengisi. */
    fun skipScriptLine() {
        if (scriptQueue.value?.currentEntry == null) return
        advanceScript(1)
    }

    /** Pindah halaman antrean (baris kembali ke 0). */
    fun setScriptPage(page: Int) {
        val q = scriptQueue.value ?: return
        val projId = project.value?.id ?: return
        val p = page.coerceIn(0, q.pageCount - 1)
        scriptQueue.value = q.copy(pageIdx = p, lineIdx = 0)
        saveScriptPosition(projId, q.uriString, p, 0)
    }

    private fun advanceScript(by: Int) {
        val q = scriptQueue.value ?: return
        val projId = project.value?.id ?: return
        var page = q.pageIdx
        var line = q.lineIdx + by
        while (page < q.pageCount) {
            val count = q.pages[page].entries.size
            if (line < count) break
            // Habis di halaman ini: lanjut halaman berikut (atau mentok).
            if (page + 1 < q.pageCount) {
                page += 1
                line = 0
            } else {
                line = count // mentok di akhir
                break
            }
        }
        scriptQueue.value = q.copy(pageIdx = page, lineIdx = line)
        saveScriptPosition(projId, q.uriString, page, line)
    }

    /** Warp resolusi ekspor untuk layer gambar (posisi absolut). */
    fun resolveExportWarpedImage(layer: Layer.ImageLayer): ProjectExporter.WarpedDraw? {
        val quad = layer.perspQuad ?: return null
        val src = resolveImageBitmap(layer) ?: return null
        val e = warpedBitmap(layer.id, src, 0L, quad, 1600) ?: return null
        return ProjectExporter.WarpedDraw(e.bitmap, layer.x + e.offX, layer.y + e.offY, e.scale)
    }

    /** Warp resolusi ekspor untuk layer teks (posisi absolut). */
    fun resolveExportWarpedText(layer: Layer.TextLayer): ProjectExporter.WarpedDraw? {        val quad = layer.perspQuad ?: return null
        exporter.textRenderer.customFontPathResolver = exportFontLookup
        val (flat, origin) = exporter.textRenderer.renderToBitmap(layer) ?: return null
        val e = warpedBitmap(
            layer.id, flat, exporter.textRenderer.flatVersion, quad, 1600
        ) ?: return null
        return ProjectExporter.WarpedDraw(e.bitmap, origin.x + e.offX, origin.y + e.offY, e.scale)
    }

    /** Warp glow resolusi ekspor untuk layer gambar (posisi absolut). */
    fun resolveExportWarpedImageGlow(layer: Layer.ImageLayer): ProjectExporter.WarpedDraw? {
        if (layer.perspQuad == null) return null
        val pad = resolveImageGlow(layer)?.second ?: return null
        val e = resolveWarpedImageGlow(layer, 1600) ?: return null
        return ProjectExporter.WarpedDraw(e.bitmap, layer.x - pad + e.offX, layer.y - pad + e.offY, e.scale)
    }


    fun getDefaultExportFolderUri(): Uri? = exportSettingsRepository.getExportFolderUri()

    fun getDefaultExportFolderName(): String? =
        exportSettingsRepository.getFolderName(exportSettingsRepository.getExportFolderUri())

    fun isExportFolderValid(uri: Uri?): Boolean = exportSettingsRepository.isFolderValid(uri)

    fun saveExportFolderUri(uri: Uri): Boolean = exportSettingsRepository.saveExportFolderUri(uri)

    /** Ekspor bundel .mts proyek ini ke URI SAF. */
    suspend fun exportBundleToUri(uri: android.net.Uri): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                    repository.exportBundle(projectId, out)
                } ?: false
                userMessage.value = if (ok) {
                    UiMessage("Bundel tersimpan.", UiMessage.Kind.SUCCESS)
                } else {
                    UiMessage("Gagal menyimpan bundel.", UiMessage.Kind.ERROR)
                }
                ok
            } catch (t: Throwable) {
                Logger.e("Gagal ekspor bundel: ${t.message}", t)
                userMessage.value = UiMessage("Gagal menyimpan bundel.", UiMessage.Kind.ERROR)
                false
            }
        }

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
            prepareImageEffects(layers.value)
            val success = exporter.exportToFile(
                base,
                layers.value,
                outputFile,
                format,
                quality,
                imageBitmapFor = { resolveImageBitmap(it) },
                    imageGlowFor = { resolveImageGlow(it) },
                imageWarpFor = { resolveExportWarpedImage(it) },
                textWarpFor = { resolveExportWarpedText(it) },
                imageGlowWarpFor = { resolveExportWarpedImageGlow(it) },
                textMeshFor = { resolveExportTextMesh(it) },
                imageMeshGlowFor = { meshGlowData(it) },
                fontLookup = exportFontLookup
            )
            isExporting.value = false
            onComplete(success)
        }
    }
}
