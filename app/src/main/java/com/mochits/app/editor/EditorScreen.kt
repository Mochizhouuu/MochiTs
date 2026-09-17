package com.mochits.app.editor
import com.mochits.app.util.Logger

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import com.mochits.app.font.FontItem
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochits.app.model.EditorPanel
import com.mochits.app.model.Layer
import com.mochits.app.model.MaskToolMode
import com.mochits.app.model.TextStyleConfig
import androidx.documentfile.provider.DocumentFile
import com.mochits.app.text.TextRenderer
import com.mochits.app.ui.color.ColorPickerRow
import com.mochits.app.settings.DownloadErrorDialog
import java.io.File

private enum class TextHandleType {
    RESIZE, ROTATE, DELETE, STRETCH_V, STRETCH_H, BODY_MOVE
}

private fun handleAnchors(bounds: RectF, scale: Float): List<Pair<TextHandleType, Offset>> {
    // Gap pill stretch dibuat lebih besar dari radius ikon resize (24/scale)
    // agar jangkar tidak tumpang tindih pada kotak teks kecil/baru.
    val gap = 40f / scale
    return listOf(
        TextHandleType.DELETE to Offset(bounds.left, bounds.top),
        TextHandleType.ROTATE to Offset(bounds.right, bounds.top),
        TextHandleType.RESIZE to Offset(bounds.right, bounds.bottom),
        TextHandleType.STRETCH_V to Offset(bounds.centerX(), bounds.bottom + gap),
        TextHandleType.STRETCH_H to Offset(bounds.right + gap, bounds.centerY())
    )
}

private fun hitTestTextHandles(
    touchCanvas: Offset,
    layer: Layer.TextLayer,
    bounds: RectF,
    scale: Float
): TextHandleType? {
    val cx = bounds.centerX()
    val cy = bounds.centerY()
    val rad = Math.toRadians(-layer.rotation.toDouble())
    val cosA = kotlin.math.cos(rad)
    val sinA = kotlin.math.sin(rad)
    val dx = (touchCanvas.x - cx).toDouble()
    val dy = (touchCanvas.y - cy).toDouble()
    val local = Offset(
        (cx + (dx * cosA - dy * sinA)).toFloat(),
        (cy + (dx * sinA + dy * cosA)).toFloat()
    )

    val slop = 48f / scale
    val anchors = handleAnchors(bounds, scale)

    // Handle sudut (DELETE/ROTATE/RESIZE) diberi bonus ~12 screen-px agar
    // menang saat hasil seri: pada teks baru yang kecil, radius slop mencakup
    // RESIZE sekaligus pill STRETCH, dan jarak-mentah bisa salah memilih
    // STRETCH saat sentuhan sedikit meleset (efek: teks malah stretch, bukan
    // membesar). Tap yang benar-benar tepat di pill tetap menang karena
    // jarak pill-stretch ke sudut jauh lebih besar dari bonus ini.
    val cornerBonus = 12f / scale
    val cornerTypes = setOf(TextHandleType.DELETE, TextHandleType.ROTATE, TextHandleType.RESIZE)

    return anchors
        .map { (type, pos) ->
            val dist = (local - pos).getDistance()
            val adjusted = if (type in cornerTypes) dist - cornerBonus else dist
            Triple(type, dist, adjusted)
        }
        .filter { it.second <= slop }
        .minByOrNull { it.third }
        ?.first
}

private fun isPointInsideTextLayer(
    layer: Layer.TextLayer,
    touchCanvasPt: Offset,
    textRenderer: TextRenderer
): Boolean {
    val bounds = textRenderer.getTextBounds(layer)
    val paddedBounds = RectF(bounds.left - 16f, bounds.top - 16f, bounds.right + 16f, bounds.bottom + 16f)
    if (layer.rotation == 0f) {
        return paddedBounds.contains(touchCanvasPt.x, touchCanvasPt.y)
    } else {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val rad = Math.toRadians(-layer.rotation.toDouble())
        val cosA = kotlin.math.cos(rad)
        val sinA = kotlin.math.sin(rad)
        val dx = (touchCanvasPt.x - cx).toDouble()
        val dy = (touchCanvasPt.y - cy).toDouble()
        val unrotatedX = (cx + dx * cosA - dy * sinA).toFloat()
        val unrotatedY = (cy + dx * sinA + dy * cosA).toFloat()
        return paddedBounds.contains(unrotatedX, unrotatedY)
    }
}


private fun performExportToTreeUri(
    context: android.content.Context,
    viewModel: EditorViewModel,
    treeUri: android.net.Uri,
    saveName: String,
    selectedFormat: String,
    exportQuality: Float
) {
    val ext = selectedFormat.lowercase()
    val mimeType = when (selectedFormat.uppercase()) {
        "JPEG", "JPG" -> "image/jpeg"
        "WEBP" -> "image/webp"
        else -> "image/png"
    }

    val docTree = DocumentFile.fromTreeUri(context, treeUri)
    if (docTree == null || !docTree.canWrite()) {
        android.widget.Toast.makeText(context, "Tidak dapat menulis ke folder tujuan", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    docTree.findFile("$saveName.$ext")?.delete()

    val createdFile = docTree.createFile(mimeType, "$saveName.$ext")
    if (createdFile?.uri != null) {
        val tempFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.$ext")

        try {
            val compressFormat = when (selectedFormat.uppercase()) {
                "JPEG", "JPG" -> android.graphics.Bitmap.CompressFormat.JPEG
                "WEBP" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Bitmap.CompressFormat.WEBP
                }
                else -> android.graphics.Bitmap.CompressFormat.PNG
            }

            viewModel.exportProject(tempFile, compressFormat, exportQuality.toInt()) { success ->
                if (success && tempFile.exists() && tempFile.length() > 0) {
                    try {
                        context.contentResolver.openFileDescriptor(createdFile.uri, "w")?.use { pfd ->
                            java.io.FileOutputStream(pfd.fileDescriptor).use { output ->
                                tempFile.inputStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                    output.flush()
                                }
                            }
                        }
                        android.widget.Toast.makeText(context, "Berhasil diekspor!", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        com.mochits.app.util.Logger.e("Export copy error: ${e.message}", e)
                        android.widget.Toast.makeText(context, "Gagal menyalin file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        createdFile.delete()
                    } finally {
                        tempFile.delete()
                    }
                } else {
                    android.widget.Toast.makeText(context, "Gagal membuat file export", android.widget.Toast.LENGTH_SHORT).show()
                    createdFile.delete()
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            com.mochits.app.util.Logger.e("Export error: ${e.message}", e)
            android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            tempFile.delete()
        }
    } else {
        android.widget.Toast.makeText(context, "Gagal membuat file di folder tujuan", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val project by viewModel.project.collectAsState()
    val baseBitmap by viewModel.baseBitmap.collectAsState()
    val layers by viewModel.layers.collectAsState()
    val selectedLayerId by viewModel.selectedLayerId.collectAsState()
    val activePanel by viewModel.activePanel.collectAsState()
    val maskToolMode by viewModel.maskToolMode.collectAsState()
    val brushSize by viewModel.brushSize.collectAsState()
    val magicWandTolerance by viewModel.magicWandTolerance.collectAsState()
    val magicWandExpand by viewModel.magicWandExpand.collectAsState()
    val isProcessingInpaint by viewModel.isProcessingInpaint.collectAsState()
    val selectedInpaintModel by viewModel.selectedInpaintModel.collectAsState()
    val isDownloadingLaMaModel by viewModel.isDownloadingLaMaModel.collectAsState()
    val lamaDownloadProgress by viewModel.lamaDownloadProgress.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
val defaultTextStyle by viewModel.defaultTextStyle.collectAsState()
val stylePresets by viewModel.stylePresets.collectAsState()
val pinnedPresetIds by viewModel.pinnedPresetIds.collectAsState()
val allFonts by viewModel.allFonts.collectAsState()
val favoriteFontKeys by viewModel.favoriteFontKeys.collectAsState()
val imageEffectRevision by viewModel.imageEffectRevision.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val isEyedropperActive by viewModel.isEyedropperActive.collectAsState()
    val eyedropperCanvasPt by viewModel.eyedropperCanvasPt.collectAsState()
    val sampledColorPreview by viewModel.sampledColorPreview.collectAsState()
    val isLoadingImage by viewModel.isLoadingImage.collectAsState()
    val lastDownloadError by viewModel.lamaModelManager.lastDownloadError.collectAsState()
    var showDownloadErrorDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isDownloadingLaMaModel) {
        if (!isDownloadingLaMaModel && lastDownloadError != null && !viewModel.lamaModelManager.isModelDownloaded()) {
            showDownloadErrorDialog = true
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.flushToDisk()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.ensureBaseBitmapLoaded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    val textRenderer = remember { TextRenderer(context) }
    var triggerRedraw by remember { mutableIntStateOf(0) }
    var isMaskPanelCollapsed by remember { mutableStateOf(false) }
    // Whether the erase mask currently holds a selection. Updated only on
    // mask-mutating events (never per-recompose: hasMask() scans the bitmap).
    var hasMaskState by remember { mutableStateOf(false) }
    fun refreshMaskState() {
        hasMaskState = viewModel.maskSelectionTools?.hasMask() == true
    }

    // Re-sync the erase status hint whenever the erase panel opens (mask may
    // have changed via undo/redo while another panel was active).
    LaunchedEffect(activePanel) {
        if (activePanel == EditorPanel.ERASE || activePanel == EditorPanel.MASK || activePanel == EditorPanel.INPAINT) {
            refreshMaskState()
        }
    }

    // Inpaint clears the mask on success: refresh the hint when it finishes.
    LaunchedEffect(isProcessingInpaint) {
        if (!isProcessingInpaint) {
            refreshMaskState()
        }
    }

    // Dialog & Dropdown Menu States
    var showAddMenu by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var newTextValue by remember { mutableStateOf("") }
    var currentViewportW by remember { mutableFloatStateOf(1080f) }
    var currentViewportH by remember { mutableFloatStateOf(1920f) }

    var showExportDialog by remember { mutableStateOf(false) }
    var outputFileName by remember { mutableStateOf(project?.title ?: "export") }
    var projectTitleName by remember { mutableStateOf(project?.title ?: "") }
    var selectedFormat by remember { mutableStateOf("PNG") }
    var exportQuality by remember { mutableFloatStateOf(100f) }

    LaunchedEffect(project?.title) {
        project?.title?.let { title ->
            if (outputFileName.isBlank() || outputFileName == "export") {
                outputFileName = title
            }
            projectTitleName = title
        }
    }

    var pendingExportSaveName by remember { mutableStateOf<String?>(null) }
    var pendingExportFormat by remember { mutableStateOf("PNG") }
    var pendingExportQuality by remember { mutableFloatStateOf(100f) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            // Picker cancelled: drop stale pending export or the next flow reuses it.
            pendingExportSaveName = null
            return@rememberLauncherForActivityResult
        }
        uri.let { pickedUri ->
            viewModel.saveExportFolderUri(pickedUri)
            val pendingName = pendingExportSaveName
            if (pendingName != null) {
                performExportToTreeUri(
                    context = context,
                    viewModel = viewModel,
                    treeUri = pickedUri,
                    saveName = pendingName,
                    selectedFormat = pendingExportFormat,
                    exportQuality = pendingExportQuality
                )
                pendingExportSaveName = null
            }
        }
    }

    // Image Picker Launchers
    val baseImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bmp = BitmapFactory.decodeStream(inputStream)
            bmp?.let { loadedBmp ->
                viewModel.saveUndoSnapshot()
                viewModel.setBaseImage(loadedBmp)
            }
        }
    }

    val addImageLayerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bmp = BitmapFactory.decodeStream(inputStream)
            bmp?.let { loadedBmp ->
                viewModel.addImageLayer(loadedBmp)
            }
        }
    }



        val fontImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val fileName = getFileNameFromUri(context, uri)
                val result = viewModel.fontRepository.importCustomFont(uri, fileName)
                if (result.isSuccess) {
                    Toast.makeText(context, "Font kustom berhasil diimpor", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "Gagal mengimpor font", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var shouldFocusTextField by remember { mutableStateOf(false) }
    var isNavigatingBack by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = !isNavigatingBack) {
        if (!isNavigatingBack) {
            isNavigatingBack = true
            // Tunggu save selesai dulu: navigasi langsung = teks terakhir hilang.
            coroutineScope.launch {
                try {
                    viewModel.flushBlocking()
                } finally {
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                coroutineScope.launch {
                                    try {
                                        viewModel.flushBlocking()
                                    } finally {
                                        onNavigateBack()
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo Button
                    IconButton(
                        onClick = {
                            viewModel.undo()
                            refreshMaskState()
                        },
                        enabled = canUndo
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    // Redo Button
                    IconButton(
                        onClick = {
                            viewModel.redo()
                            refreshMaskState()
                        },
                        enabled = canRedo
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    // Add Menu (+)
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Tambah Layer")
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Tambah Teks") },
                                leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    showAddTextDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Tambah Gambar") },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    addImageLayerLauncher.launch("image/*")
                                }
                            )
                        }
                    }
                    // Eraser / Mask Selection Shortcut Button
                    IconButton(onClick = { viewModel.setActivePanel(EditorPanel.ERASE) }) {
                        Icon(Icons.Default.CleaningServices, contentDescription = "Hapus / Seleksi Objek")
                    }
                    // Layers Shortcut Button
                    IconButton(onClick = { viewModel.setActivePanel(EditorPanel.LAYERS) }) {
                        Icon(Icons.Default.Layers, contentDescription = "Layers")
                    }
                    // Save As / Rename Menu
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.Save, contentDescription = "Simpan / Save As")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                when (activePanel) {
                    EditorPanel.ERASE, EditorPanel.MASK, EditorPanel.INPAINT -> EraseToolPanel(
                        mode = maskToolMode,
                        brushSize = brushSize,
                        magicWandTolerance = magicWandTolerance,
                        magicWandExpand = magicWandExpand,
                        selectedModel = selectedInpaintModel,
                        isProcessing = isProcessingInpaint,
                        isDownloading = isDownloadingLaMaModel,
                        downloadProgress = lamaDownloadProgress,
                        isCollapsed = isMaskPanelCollapsed,
                        hasMask = hasMaskState,
                        onToggleCollapse = { isMaskPanelCollapsed = !isMaskPanelCollapsed },
                        onModeSelected = {
                            // ViewModel clears the mask only when actually switching tools.
                            if (it != maskToolMode) {
                                viewModel.setMaskToolMode(it)
                                hasMaskState = false
                            }
                        },
                        onModelSelected = { viewModel.setInpaintModel(it) },
                        onSizeChange = { viewModel.setBrushSize(it) },
                        onToleranceChange = { viewModel.setMagicWandTolerance(it) },
                        onExpandChange = {
                            viewModel.setMagicWandExpand(it)
                            triggerRedraw++
                        },
                        onClear = {
                            viewModel.saveUndoSnapshot()
                            viewModel.maskSelectionTools?.clearMask()
                            hasMaskState = false
                            triggerRedraw++
                        },
                        onInvert = {
                            viewModel.saveUndoSnapshot()
                            viewModel.maskSelectionTools?.invertMask()
                            refreshMaskState()
                            triggerRedraw++
                        },
                        onRunErase = {
                            viewModel.runEraseInpaint()
                            triggerRedraw++
                        }
                    )
                    EditorPanel.TEXT -> TextToolPanel(
                        selectedLayer = layers.find { it.id == selectedLayerId } as? Layer.TextLayer,
                        defaultStyle = defaultTextStyle,
                        onAddText = { text -> viewModel.addTextLayer(text, viewportWidth = currentViewportW, viewportHeight = currentViewportH) },
                        onUpdateTextContent = { text -> viewModel.updateSelectedTextContent(text, saveUndo = false) },
                        onEditStart = { viewModel.saveUndoSnapshot() },
                        onRequestAutosave = { viewModel.autoSave() },
                        onUpdateStyle = { style, saveUndo -> viewModel.updateSelectedTextLayerStyle(style, saveUndo = saveUndo) },
                        onUpdateContainerShape = { shape -> viewModel.updateSelectedTextLayerContainerShape(shape) },
                        autoFocus = shouldFocusTextField,
                        onFocused = { shouldFocusTextField = false }
                    )
                    EditorPanel.FONT -> FontToolPanel(
                        allFonts = allFonts,
                        selectedLayer = layers.find { it.id == selectedLayerId } as? Layer.TextLayer,
                        defaultStyle = defaultTextStyle,
                        onUpdateStyle = { style, saveUndo -> viewModel.updateSelectedTextLayerStyle(style, saveUndo = saveUndo) },
                        onCapitalizationTransform = { transformType -> viewModel.applyCapitalizationTransform(transformType) },
                        onImportCustomFont = { fontImportLauncher.launch("*/*") },
                        onSliderDragStart = { viewModel.onSliderDragStart() },
                        onSliderDragEnd = { viewModel.onSliderDragEnd() },
                        favoriteKeys = favoriteFontKeys,
                        onToggleFavorite = { key -> viewModel.toggleFontFavorite(key) }
                    )
                    EditorPanel.STYLE -> StylePresetPanel(
                        presets = stylePresets,
                        selectedLayer = layers.find { it.id == selectedLayerId } as? Layer.TextLayer,
                        onApplyPreset = { preset -> viewModel.applyStylePreset(preset) },
                        onSavePreset = { name -> viewModel.saveStylePreset(name) },
                        onDeletePreset = { id -> viewModel.deleteStylePreset(id) },
                        pinnedIds = pinnedPresetIds.toSet(),
                        onTogglePin = { id -> viewModel.togglePinnedPreset(id) }
                    )
                    EditorPanel.EFFECT -> EffectToolPanel(
                        selectedLayer = layers.find { it.id == selectedLayerId },
                        onUpdateOpacity = { opacity, saveUndo -> viewModel.updateSelectedLayerOpacity(opacity, saveUndo = saveUndo) },
                        onUpdateStyle = { style, saveUndo -> viewModel.updateSelectedTextLayerStyle(style, saveUndo = saveUndo) },
                        onSliderDragStart = { viewModel.onSliderDragStart() },
                        onSliderDragEnd = { viewModel.onSliderDragEnd() },
                        onStartEyedropper = { onColorSelected -> viewModel.startEyedropper(onColorSelected) },
                        onUpdateImageLayer = { updated -> viewModel.updateImageLayer(updated, false) }
                    )
                    EditorPanel.LAYERS -> LayersToolPanel(
                        layers = layers,
                        selectedId = selectedLayerId,
                        onSelectLayer = { viewModel.selectLayer(it) },
                        onMoveLayer = { id, dir -> viewModel.moveLayer(id, dir) },
                        onToggleVisibility = { viewModel.toggleLayerVisibility(it) },
                        onDeleteLayer = { viewModel.deleteLayer(it) },
                        onLoadBaseImage = { baseImagePickerLauncher.launch("image/*") },
                        onUpdateOpacity = { opacity, saveUndo -> viewModel.updateSelectedLayerOpacity(opacity, saveUndo = saveUndo) },
                        onSliderDragStart = { viewModel.onSliderDragStart() },
                        onSliderDragEnd = { viewModel.onSliderDragEnd() }
                    )
                    else -> {}
                }

                EditorBottomBar(
                    activePanel = activePanel,
                    onPanelSelect = { viewModel.setActivePanel(it) }
                )
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.DarkGray)
        ) {
            val viewportW = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
            val viewportH = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
            LaunchedEffect(viewportW, viewportH) {
                if (viewportW > 0f && viewportH > 0f) {
                    currentViewportW = viewportW
                    currentViewportH = viewportH
                }
            }
            var lastTouchCanvasPt by remember { mutableStateOf(Offset.Zero) }
            var isMaskDrawingActive by remember { mutableStateOf(false) }
            var magicWandTouchStartPt by remember { mutableStateOf<Offset?>(null) }
            var magicWandMovedDistance by remember { mutableFloatStateOf(0f) }
            var isMagicWandPending by remember { mutableStateOf(false) }
            var isProcessingMagicWand by remember { mutableStateOf(false) }

            // NOTE: gesture handling below intentionally does NOT use a captured
            // selected-layer compose val (it would go stale inside pointerInput(Unit)).
            // It reads fresh state via viewModel.selectedLayerId/layers instead.

            var activeHandleType by remember { mutableStateOf<TextHandleType?>(null) }
            var initialDragDist by remember { mutableFloatStateOf(0f) }
            var initialFontSize by remember { mutableFloatStateOf(36f) }
            var initialTextRotation by remember { mutableFloatStateOf(0f) }
            var initialTouchAngle by remember { mutableFloatStateOf(0f) }
            var initialTextX by remember { mutableFloatStateOf(0f) }
            var initialTextY by remember { mutableFloatStateOf(0f) }
            var initialTextCenterX by remember { mutableFloatStateOf(0f) }
            var initialTextCenterY by remember { mutableFloatStateOf(0f) }
            var initialTouchCanvasPt by remember { mutableStateOf(Offset.Zero) }
            var initialBoxW by remember { mutableFloatStateOf(0f) }
            var initialBoxH by remember { mutableFloatStateOf(0f) }
            var initialMinW by remember { mutableFloatStateOf(30f) }
            var initialMinH by remember { mutableFloatStateOf(20f) }
            var initialBoundsLeft by remember { mutableFloatStateOf(0f) }
            var initialBoundsTop by remember { mutableFloatStateOf(0f) }
            var deleteHandlePressTime by remember { mutableLongStateOf(0L) }
            var pendingDeleteLayerId by remember { mutableStateOf<String?>(null) }

            var lastTapTimestamp by remember { mutableLongStateOf(0L) }
            var lastTapLayerId by remember { mutableStateOf<String?>(null) }

            var pendingBodyMoveLayer by remember { mutableStateOf<Layer.TextLayer?>(null) }
            var initialTouchScreenPt by remember { mutableStateOf(Offset.Zero) }
            var isBodyMoveDragging by remember { mutableStateOf(false) }

            var panAccumulator by remember { mutableFloatStateOf(0f) }
            // Tap-vs-pan yang tahan jitter: jumlah jarak per-event (panAccumulator)
            // bisa jebol >10px hanya dari getar jari, apalagi di layar 120Hz.
            // Sebagai jaring pengaman, lacak juga simpangan MAKSIMUM dari titik
            // tekan; ketukan asli tidak pernah jauh dari titik tekannya.
            var gestureDownPt by remember { mutableStateOf(Offset.Zero) }
            var gestureMaxDisplacement by remember { mutableFloatStateOf(0f) }
            var gestureMultiTouch by remember { mutableStateOf(false) }

            val outlinePaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; isAntiAlias = true; color = AndroidColor.RED } }
            val maskPaintCache = remember { AndroidPaint().apply { colorFilter = android.graphics.PorterDuffColorFilter(AndroidColor.argb(64, 255, 0, 0), android.graphics.PorterDuff.Mode.SRC_IN) } }
            val lassoPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.RED; isAntiAlias = true } }
            val lassoPathCache = remember { android.graphics.Path() }
            val crossPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.RED; isAntiAlias = true } }
            val fillPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.FILL; isAntiAlias = true } }
            val strokePaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.WHITE; isAntiAlias = true } }
            val alphaPaintCache = remember { AndroidPaint() }
            val boxPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.parseColor("#3F51B5") } }
            val handleFillPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.FILL; color = AndroidColor.WHITE } }
            val handleStrokePaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.parseColor("#3F51B5") } }
            val resizeFillPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.FILL; color = AndroidColor.parseColor("#3F51B5"); isAntiAlias = true } }
            val resizeIconPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.WHITE; isAntiAlias = true; strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND } }
            val deleteFillPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.FILL; color = AndroidColor.parseColor("#E53935"); isAntiAlias = true } }
            val xPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.WHITE; isAntiAlias = true; strokeCap = AndroidPaint.Cap.ROUND } }
            val rotateFillPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.FILL; color = AndroidColor.parseColor("#4CAF50"); isAntiAlias = true } }
            val rotateArcPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.WHITE; isAntiAlias = true; strokeCap = AndroidPaint.Cap.ROUND } }
            val rotateArrowPathCache = remember { android.graphics.Path() }
            val rotateArrowPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.FILL; color = AndroidColor.WHITE; isAntiAlias = true } }
            val vArrowPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.parseColor("#3F51B5"); isAntiAlias = true; strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND } }
            val hArrowPaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.parseColor("#3F51B5"); isAntiAlias = true; strokeCap = AndroidPaint.Cap.ROUND; strokeJoin = AndroidPaint.Join.ROUND } }
            val arcRectCache = remember { RectF() }
            val pillRectCache = remember { RectF() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.isEmpty()) continue

                                if (isProcessingMagicWand) {
                                    changes.forEach { it.consume() }
                                    continue
                                }

                                // 0. EYEDROPPER MODE ACTIVE: Intercept all drags/taps to move crosshair
                                if (isEyedropperActive) {
                                    val firstChange = changes.first()
                                    val touchCanvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                    viewModel.updateEyedropperPosition(touchCanvasPt)
                                    firstChange.consume()
                                    triggerRedraw++
                                    continue
                                }











                                // 1. MASK TOOL ACTIVE: Handle mask drawing strokes (1 finger) vs Pan/Zoom (2+ fingers)
                                if (activePanel == EditorPanel.ERASE || activePanel == EditorPanel.MASK) {
                                    val pressedList = changes.filter { it.pressed }
                                    val totalPointerCount = event.changes.size

                                    if (totalPointerCount >= 2 || pressedList.size >= 2) {
                                        // Multi-touch detected: Cancel any pending Magic Wand tap instantly (0ms overhead)
                                        if (isMagicWandPending) {
                                            isMagicWandPending = false
                                            magicWandTouchStartPt = null
                                            magicWandMovedDistance = 0f
                                        }
                                        // If mask stroke was started by 1st finger (Brush/Eraser/Lasso), roll it back immediately
                                        if (isMaskDrawingActive) {
                                            viewModel.rollbackUndoSnapshot()
                                            isMaskDrawingActive = false
                                        }

                                        if (pressedList.size >= 2) {
                                            // 2+ fingers: Pinch zoom / pan canvas
                                            val p0 = pressedList[0].position
                                            val p1 = pressedList[1].position
                                            val prevP0 = pressedList[0].previousPosition
                                            val prevP1 = pressedList[1].previousPosition

                                            val center = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                            val prevCenter = Offset((prevP0.x + prevP1.x) / 2f, (prevP0.y + prevP1.y) / 2f)

                                            val currentDist = kotlin.math.hypot(p0.x - p1.x, p0.y - p1.y)
                                            val prevDist = kotlin.math.hypot(prevP0.x - prevP1.x, prevP0.y - prevP1.y)

                                            val zoomFactor = if (prevDist > 0f) currentDist / prevDist else 1f
                                            val panDelta = center - prevCenter

                                            viewModel.canvasState.onGestureTransform(center, panDelta, zoomFactor)
                                            triggerRedraw++
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (pressedList.size == 1) {
                                        val firstChange = pressedList[0]
                                        val isJustDown = !firstChange.previousPressed && firstChange.pressed
                                        if (maskToolMode == MaskToolMode.MAGIC_WAND) {
                                            if (isJustDown) {
                                                magicWandTouchStartPt = firstChange.position
                                                magicWandMovedDistance = 0f
                                                isMagicWandPending = true
                                            } else if (isMagicWandPending) {
                                                val moveDelta = (firstChange.position - firstChange.previousPosition).getDistance()
                                                magicWandMovedDistance += moveDelta
                                                if (magicWandMovedDistance > 15f) {
                                                    // Drag threshold exceeded: Cancel pending Magic Wand tap and perform canvas pan
                                                    isMagicWandPending = false
                                                    val panDelta = firstChange.position - firstChange.previousPosition
                                                    viewModel.canvasState.onGestureTransform(firstChange.position, panDelta, 1f)
                                                    triggerRedraw++
                                                }
                                            }
                                        } else {
                                            if (isJustDown) {
                                                viewModel.saveUndoSnapshot()
                                                val canvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                                lastTouchCanvasPt = canvasPt
                                                viewModel.maskSelectionTools?.startStroke(canvasPt, maskToolMode, brushSize)
                                                isMaskDrawingActive = true
                                                // Brush draws immediately; eraser may clear the last bit.
                                                refreshMaskState()
                                                triggerRedraw++
                                            } else if (isMaskDrawingActive) {
                                                val canvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                                lastTouchCanvasPt = canvasPt
                                                viewModel.maskSelectionTools?.updateStroke(canvasPt, maskToolMode, brushSize)
                                                triggerRedraw++
                                            }
                                        }
                                        firstChange.consume()
                                    } else {
                                        // Finger released
                                        val releasedChange = changes.find { it.previousPressed && !it.pressed }
                                        if (releasedChange != null) {
                                            if (maskToolMode == MaskToolMode.MAGIC_WAND) {
                                                if (isMagicWandPending && magicWandMovedDistance <= 15f && magicWandTouchStartPt != null && !isProcessingMagicWand) {
                                                    val startPt = magicWandTouchStartPt!!
                                                    val startTol = magicWandTolerance
                                                    val startExp = magicWandExpand.toInt()
                                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(startPt.x, startPt.y)
                                                    // Ignore taps outside the image instead of pushing a useless
                                                    // undo step / flood-filling from a clamped edge pixel.
                                                    val base = viewModel.baseBitmap.value
                                                    val insideImage = base != null && !base.isRecycled &&
                                                        canvasPt.x >= 0f && canvasPt.y >= 0f &&
                                                        canvasPt.x < base.width.toFloat() && canvasPt.y < base.height.toFloat()
                                                    if (insideImage) {
                                                        isProcessingMagicWand = true
                                                        coroutineScope.launch(Dispatchers.Default) {
                                                            try {
                                                                viewModel.saveUndoSnapshot()
                                                                val flattened = viewModel.flattenForSelection()
                                                                val src = flattened ?: viewModel.baseBitmap.value
                                                                viewModel.maskSelectionTools?.magicWandSelect(
                                                                    srcBitmap = src,
                                                                    point = canvasPt,
                                                                    tolerance = startTol,
                                                                    expandPixels = startExp
                                                                )
                                                                withContext(Dispatchers.Main) {
                                                                    refreshMaskState()
                                                                    triggerRedraw++
                                                                }
                                                            } finally {
                                                                isProcessingMagicWand = false
                                                            }
                                                        }
                                                    }
                                                }
                                                isMagicWandPending = false
                                                magicWandTouchStartPt = null
                                                magicWandMovedDistance = 0f
                                            } else if (isMaskDrawingActive) {
                                                viewModel.maskSelectionTools?.endStroke(lastTouchCanvasPt, maskToolMode, brushSize)
                                                isMaskDrawingActive = false
                                                refreshMaskState()
                                                triggerRedraw++
                                            }
                                        }
                                    }
                                    continue
                                }

                                // 2. TEXT HANDLES INTERCEPTION: Check hit-testing on handles
                                val firstChange = changes.first()
                                val touchCanvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                // NOTE: do NOT use the captured `selectedTextLayer` / `layers` compose vals
                                // here. pointerInput(Unit) never restarts, so those would stay stale after
                                // selection changes and all drags would silently no-op (only tap-select +
                                // double-tap-to-edit, which use pendingBodyMoveLayer, would still work).
                                // Always read fresh state from the ViewModel inside the gesture loop.
                                val freshSelectedLayer = viewModel.selectedLayerId.value?.let { sid ->
                                    viewModel.layers.value.find { it.id == sid } as? Layer.TextLayer
                                }

                                if (activeHandleType == null && pendingBodyMoveLayer == null) {
                                    val isJustDown = !firstChange.previousPressed && firstChange.pressed
                                    if (isJustDown) {
                                        panAccumulator = 0f
                                        gestureDownPt = firstChange.position
                                        gestureMaxDisplacement = 0f
                                        gestureMultiTouch = false
                                        var hitHandle = false
                                        val handleLayer = freshSelectedLayer
                                        if (handleLayer != null) {
                                            val bounds = textRenderer.getTextBounds(handleLayer)
                                            val textCenterX = bounds.centerX()
                                            val textCenterY = bounds.centerY()

                                            val chosenHandle = hitTestTextHandles(
                                                touchCanvasPt,
                                                handleLayer,
                                                bounds,
                                                viewModel.canvasState.scale
                                            )

                                            val unrotatedPt = if (handleLayer.rotation != 0f) {
                                                val rad = Math.toRadians(-handleLayer.rotation.toDouble())
                                                val cosA = kotlin.math.cos(rad)
                                                val sinA = kotlin.math.sin(rad)
                                                val dx = (touchCanvasPt.x - textCenterX).toDouble()
                                                val dy = (touchCanvasPt.y - textCenterY).toDouble()
                                                Offset(
                                                    (textCenterX + dx * cosA - dy * sinA).toFloat(),
                                                    (textCenterY + dx * sinA + dy * cosA).toFloat()
                                                )
                                            } else {
                                                touchCanvasPt
                                            }

                                            when (chosenHandle) {
                                                TextHandleType.DELETE -> {
                                                    viewModel.saveUndoSnapshot()
                                                    viewModel.deleteLayer(handleLayer.id)
                                                    viewModel.selectLayer(null)
                                                    hitHandle = true
                                                    firstChange.consume()
                                                    triggerRedraw++
                                                    continue
                                                }
                                                TextHandleType.ROTATE -> {
                                                    activeHandleType = TextHandleType.ROTATE
                                                    viewModel.saveUndoSnapshot()
                                                    initialTextCenterX = textCenterX
                                                    initialTextCenterY = textCenterY
                                                    initialTouchAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - textCenterY).toDouble(), (touchCanvasPt.x - textCenterX).toDouble())).toFloat()
                                                    initialTextRotation = handleLayer.rotation
                                                    hitHandle = true
                                                    firstChange.consume()
                                                    continue
                                                }
                                                TextHandleType.RESIZE -> {
                                                    activeHandleType = TextHandleType.RESIZE
                                                    viewModel.saveUndoSnapshot()
                                                    initialTextCenterX = textCenterX
                                                    initialTextCenterY = textCenterY
                                                    initialDragDist = kotlin.math.hypot(unrotatedPt.x - textCenterX, unrotatedPt.y - textCenterY)
                                                        // Batas bawah agar resize teks mungil tidak meledak:
                                                        // sentuhan di tengah kotak kecil bikin penyebut ~0.
                                                        .coerceAtLeast(40f)
                                                    initialFontSize = handleLayer.style.fontSize
                                                    initialBoxW = handleLayer.boxWidth ?: bounds.width()
                                                    initialBoxH = handleLayer.boxHeight ?: bounds.height()
                                                    initialBoundsLeft = bounds.left
                                                    initialBoundsTop = bounds.top
                                                    hitHandle = true
                                                    firstChange.consume()
                                                    continue
                                                }
                                                TextHandleType.STRETCH_V -> {
                                                    activeHandleType = TextHandleType.STRETCH_V
                                                    viewModel.saveUndoSnapshot()
                                                    initialTextX = handleLayer.x
                                                    initialTextY = handleLayer.y
                                                    initialTextCenterX = textCenterX
                                                    initialTextCenterY = textCenterY
                                                    initialBoxW = handleLayer.boxWidth ?: bounds.width()
                                                    initialBoxH = handleLayer.boxHeight ?: bounds.height()
                                                    initialMinH = textRenderer.getMinBoxHeight(handleLayer)
                                                    initialBoundsLeft = bounds.left
                                                    initialBoundsTop = bounds.top
                                                    hitHandle = true
                                                    firstChange.consume()
                                                    continue
                                                }
                                                TextHandleType.STRETCH_H -> {
                                                    activeHandleType = TextHandleType.STRETCH_H
                                                    viewModel.saveUndoSnapshot()
                                                    initialTextX = handleLayer.x
                                                    initialTextY = handleLayer.y
                                                    initialTextCenterX = textCenterX
                                                    initialTextCenterY = textCenterY
                                                    initialBoxW = handleLayer.boxWidth ?: bounds.width()
                                                    initialBoxH = handleLayer.boxHeight ?: bounds.height()
                                                    initialMinW = textRenderer.getMinBoxWidth(handleLayer)
                                                    initialBoundsLeft = bounds.left
                                                    initialBoundsTop = bounds.top
                                                    hitHandle = true
                                                    firstChange.consume()
                                                    continue
                                                }
                                                else -> {}
                                            }
                                        }
                                        if (!hitHandle) {
                                            val hitTextLayer = viewModel.layers.value.reversed().filterIsInstance<Layer.TextLayer>().firstOrNull { layer ->
                                                layer.isVisible && isPointInsideTextLayer(layer, touchCanvasPt, textRenderer)
                                            }
                                            if (hitTextLayer != null) {
                                                pendingBodyMoveLayer = hitTextLayer
                                                initialTouchScreenPt = firstChange.position
                                                initialTouchCanvasPt = touchCanvasPt
                                                initialTextX = hitTextLayer.x
                                                initialTextY = hitTextLayer.y
                                                isBodyMoveDragging = false
                                                hitHandle = true
                                                firstChange.consume()
                                                Logger.d("Touch intercepted by text layer: ${hitTextLayer.id}")
                                            } else {
                                                activeHandleType = null
                                            }
                                        }
                                    }
                                }
                                // Check pending body move drag threshold
                                if (pendingBodyMoveLayer != null && firstChange.pressed && activeHandleType == null) {
                                    firstChange.consume()
                                    val moveDist = (firstChange.position - initialTouchScreenPt).getDistance()
                                    if (moveDist > 15f || isBodyMoveDragging) {
                                        if (!isBodyMoveDragging) {
                                            isBodyMoveDragging = true
                                            activeHandleType = TextHandleType.BODY_MOVE
                                            viewModel.selectLayer(pendingBodyMoveLayer!!.id)
                                            viewModel.saveUndoSnapshot()
                                        }
                                    }
                                }

                                // Handle active drag on text handles
                                if (activeHandleType != null && firstChange.pressed) {
                                    event.changes.forEach { it.consume() }
                                    Logger.d("Text handle drag active: type=$activeHandleType, consumed touches=${event.changes.size}")
                                    // Fresh lookup: the captured compose val would be stale here.
                                    val dragLayer = viewModel.selectedLayerId.value?.let { sid ->
                                        viewModel.layers.value.find { it.id == sid } as? Layer.TextLayer
                                    }
                                    val unrotatedCurrentPt = if (dragLayer != null && dragLayer.rotation != 0f) {
                                        val rad = Math.toRadians(-dragLayer.rotation.toDouble())
                                        val cosA = kotlin.math.cos(rad)
                                        val sinA = kotlin.math.sin(rad)
                                        val dx = (touchCanvasPt.x - initialTextCenterX).toDouble()
                                        val dy = (touchCanvasPt.y - initialTextCenterY).toDouble()
                                        Offset(
                                            (initialTextCenterX + dx * cosA - dy * sinA).toFloat(),
                                            (initialTextCenterY + dx * sinA + dy * cosA).toFloat()
                                        )
                                    } else {
                                        touchCanvasPt
                                    }
                                    when (activeHandleType) {
                                        TextHandleType.RESIZE -> {
                                            if (dragLayer != null) {
                                                val currentDist = kotlin.math.hypot(unrotatedCurrentPt.x - initialTextCenterX, unrotatedCurrentPt.y - initialTextCenterY)
                                                if (initialDragDist > 0f) {
                                                    val scaleFactor = currentDist / initialDragDist
                                                    val newSize = (initialFontSize * scaleFactor).coerceIn(10f, 300f)
                                                    val newBoxW = if (dragLayer.boxWidth != null || dragLayer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                                                        (initialBoxW * scaleFactor).coerceAtLeast(30f)
                                                    } else null
                                                    val newBoxH = if (dragLayer.boxHeight != null || dragLayer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                                                        (initialBoxH * scaleFactor).coerceAtLeast(20f)
                                                    } else null

                                                    viewModel.updateSelectedTextLayerResize(
                                                        fontSize = newSize,
                                                        boxWidth = newBoxW,
                                                        boxHeight = newBoxH,
                                                        // Jangkar tengah awal drag supaya kalimat
                                                        // membesar/mengecil di tempat, bukan melar
                                                        // ke kanan-bawah.
                                                        anchorCenterX = initialTextCenterX,
                                                        anchorCenterY = initialTextCenterY,
                                                        saveUndo = false
                                                    )
                                                    triggerRedraw++
                                                }
                                            }
                                        }
                                        TextHandleType.ROTATE -> {
                                            if (dragLayer != null) {
                                                val currentAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - initialTextCenterY).toDouble(), (touchCanvasPt.x - initialTextCenterX).toDouble())).toFloat()
                                                val deltaAngle = currentAngle - initialTouchAngle
                                                var newRotation = (initialTextRotation + deltaAngle) % 360f
                                                if (newRotation < 0f) newRotation += 360f
                                                viewModel.updateSelectedTextLayerRotation(newRotation, saveUndo = false)
                                                triggerRedraw++
                                            }
                                        }
                                        TextHandleType.STRETCH_V -> {
                                            if (dragLayer != null) {
                                                val baseH = viewModel.baseBitmap.value
                                                val maxCanvasHeight = if (baseH != null && !baseH.isRecycled) baseH.height.toFloat() else (viewModel.project.value?.height?.toFloat() ?: 1920f)
                                                val currentBoxW = dragLayer.boxWidth
                                                val rawBoxH = unrotatedCurrentPt.y - initialTextY
                                                val newBoxH = rawBoxH.coerceIn(initialMinH, maxCanvasHeight)
                                                viewModel.updateSelectedTextLayerStretch(
                                                    boxWidth = currentBoxW,
                                                    boxHeight = newBoxH,
                                                    newX = initialTextX,
                                                    newY = initialTextY,
                                                    saveUndo = false
                                                )
                                                triggerRedraw++
                                            }
                                        }
                                        TextHandleType.STRETCH_H -> {
                                            if (dragLayer != null) {
                                                val baseW = viewModel.baseBitmap.value
                                                val maxCanvasWidth = if (baseW != null && !baseW.isRecycled) baseW.width.toFloat() else (viewModel.project.value?.width?.toFloat() ?: 1080f)
                                                val currentBoxH = dragLayer.boxHeight
                                                val distFromCenter = kotlin.math.abs(unrotatedCurrentPt.x - initialTextCenterX)
                                                val rawBoxW = distFromCenter * 2f
                                                val newBoxW = rawBoxW.coerceIn(initialMinW, maxCanvasWidth)
                                                val newX = initialTextCenterX - (newBoxW / 2f)
                                                viewModel.updateSelectedTextLayerStretch(
                                                    boxWidth = newBoxW,
                                                    boxHeight = currentBoxH,
                                                    newX = newX,
                                                    newY = initialTextY,
                                                    saveUndo = false
                                                )
                                                triggerRedraw++
                                            }
                                        }
                                        TextHandleType.BODY_MOVE -> {
                                            // dragLayer null-check not needed: position update targets the
                                            // selected id; skip only if selection was cleared mid-drag.
                                            if (dragLayer != null) {
                                                val deltaX = touchCanvasPt.x - initialTouchCanvasPt.x
                                                val deltaY = touchCanvasPt.y - initialTouchCanvasPt.y
                                                viewModel.updateSelectedTextLayerPosition(
                                                    initialTextX + deltaX,
                                                    initialTextY + deltaY,
                                                    saveUndo = false
                                                )
                                                triggerRedraw++
                                            }
                                        }
                                        else -> {}
                                    }
                                    continue
                                }

                                if (changes.none { it.pressed }) {
                                    if (activeHandleType == TextHandleType.DELETE) {
                                        val pressDuration = System.currentTimeMillis() - deleteHandlePressTime
                                        if (pressDuration < 300L && pendingDeleteLayerId != null) {
                                            viewModel.deleteLayer(pendingDeleteLayerId!!)
                                            viewModel.selectLayer(null)
                                        }
                                        activeHandleType = null
                                        pendingDeleteLayerId = null
                                        deleteHandlePressTime = 0L
                                        triggerRedraw++
                                        continue
                                    } else if (activeHandleType != null || isBodyMoveDragging) {
                                        viewModel.finalizeTextTransform()
                                        activeHandleType = null
                                        isBodyMoveDragging = false
                                        pendingBodyMoveLayer = null
                                        continue
                                    } else if (pendingBodyMoveLayer != null) {
                                        val targetLayer = pendingBodyMoveLayer!!
                                        pendingBodyMoveLayer = null
                                        val now = System.currentTimeMillis()
                                        val isDoubleTap = (lastTapLayerId == targetLayer.id) && (now - lastTapTimestamp < 400L)
                                        viewModel.selectLayer(targetLayer.id)
                                        if (isDoubleTap) {
                                            shouldFocusTextField = true
                                            viewModel.setActivePanel(EditorPanel.TEXT)
                                            lastTapTimestamp = 0L
                                            lastTapLayerId = null
                                        } else {
                                            lastTapTimestamp = now
                                            lastTapLayerId = targetLayer.id
                                        }
                                        triggerRedraw++
                                        continue
                                    }
                                }

                                // 3. CANVAS PAN/ZOOM & TAP-SELECT / TAP-DESELECT
                                if (activeHandleType == null) {
                                    val pressedList = changes.filter { it.pressed }
                                    if (pressedList.size >= 2) {
                                        // Second finger down: this is a pinch, not a text tap.
                                        // Drop any pending text tap so release doesn't mis-fire select.
                                        pendingBodyMoveLayer = null
                                        isBodyMoveDragging = false
                                        // Pinch zoom / pan gesture with 2+ fingers
                                        val p0 = pressedList[0].position
                                        val p1 = pressedList[1].position
                                        val prevP0 = pressedList[0].previousPosition
                                        val prevP1 = pressedList[1].previousPosition

                                        val center = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                        val prevCenter = Offset((prevP0.x + prevP1.x) / 2f, (prevP0.y + prevP1.y) / 2f)

                                        val currentDist = kotlin.math.hypot(p0.x - p1.x, p0.y - p1.y)
                                        val prevDist = kotlin.math.hypot(prevP0.x - prevP1.x, prevP0.y - prevP1.y)

                                        val zoomFactor = if (prevDist > 0f) currentDist / prevDist else 1f
                                        val panDelta = center - prevCenter

                                        panAccumulator += panDelta.getDistance()
                                        gestureMultiTouch = true
                                        Logger.d("Canvas Multi-Finger Gesture Transform: panDelta=$panDelta, zoomFactor=$zoomFactor")
                                        viewModel.canvasState.onGestureTransform(center, panDelta, zoomFactor)
                                        triggerRedraw++
                                        pressedList.forEach { it.consume() }
                                    } else if (pressedList.size == 1) {
                                        val c = pressedList[0]
                                        // Touch started on a text body and hasn't passed the move
                                        // threshold yet: hold still, don't pan the canvas underneath.
                                        // Once it passes 15px the pending block above promotes it to
                                        // BODY_MOVE; if released first it becomes tap-select below.
                                        if (pendingBodyMoveLayer != null) {
                                            c.consume()
                                        } else {
                                            val panDelta = c.position - c.previousPosition
                                            val moved = panDelta.getDistance() > 1.5f
                                            if (moved) {
                                                panAccumulator += panDelta.getDistance()
                                                val displacement = (c.position - gestureDownPt).getDistance()
                                                if (displacement > gestureMaxDisplacement) {
                                                    gestureMaxDisplacement = displacement
                                                }
                                                Logger.d("Canvas Single-Finger Pan Transform: panDelta=$panDelta")
                                                viewModel.canvasState.onGestureTransform(c.position, panDelta, 1f)
                                                triggerRedraw++
                                                c.consume()
                                            }
                                        }
                                    } else {
                                        // Finger released
                                        val releasedChange = changes.find { it.previousPressed && !it.pressed }
                                        if (releasedChange != null) {
                                            val isTap = panAccumulator <= 10f ||
                                                (!gestureMultiTouch && gestureMaxDisplacement <= 24f)
                                            if (isTap) {
                                                val releaseCanvasPt = viewModel.canvasState.mapper.screenToCanvas(releasedChange.position.x, releasedChange.position.y)
                                                val hitTextLayer = viewModel.layers.value.reversed().filterIsInstance<Layer.TextLayer>().firstOrNull { layer ->
                                                    layer.isVisible && isPointInsideTextLayer(layer, releaseCanvasPt, textRenderer)
                                                }
                                                val now = System.currentTimeMillis()
                                                if (hitTextLayer != null) {
                                                    val isDoubleTap = (lastTapLayerId == hitTextLayer.id) && (now - lastTapTimestamp < 400L)
                                                    viewModel.selectLayer(hitTextLayer.id)
                                                    if (isDoubleTap) {
                                                        shouldFocusTextField = true
                                                        viewModel.setActivePanel(EditorPanel.TEXT)
                                                        lastTapTimestamp = 0L
                                                        lastTapLayerId = null
                                                    } else {
                                                        lastTapTimestamp = now
                                                        lastTapLayerId = hitTextLayer.id
                                                    }
                                                    triggerRedraw++
                                                } else {
                                                    val isDoubleTapCanvas = (lastTapLayerId == "CANVAS_BACKGROUND") && (now - lastTapTimestamp < 400L)
                                                    viewModel.selectLayer(null)
                                                    if (isDoubleTapCanvas) {
                                                        val bmp = viewModel.baseBitmap.value
                                                        val hasBmp = bmp != null && !bmp.isRecycled
                                                        // Kanvas transparan/proyek baru bisa tanpa bitmap;
                                                        // pakai dimensi proyek agar double-tap tetap berfungsi.
                                                        val imgW = if (hasBmp) bmp!!.width.toFloat() else project?.width?.toFloat()
                                                        val imgH = if (hasBmp) bmp!!.height.toFloat() else project?.height?.toFloat()
                                                        if (imgW != null && imgH != null && imgW > 0f && imgH > 0f) {
                                                                viewModel.canvasState.fitToWidth(
                                                                    viewportWidth = size.width.toFloat(),
                                                                    viewportHeight = size.height.toFloat(),
                                                                    imageWidth = imgW,
                                                                    imageHeight = imgH,
                                                                    focusCanvasY = releaseCanvasPt.y
                                                                )
                                                        }
                                                        lastTapTimestamp = 0L
                                                        lastTapLayerId = null
                                                    } else {
                                                        lastTapTimestamp = now
                                                        lastTapLayerId = "CANVAS_BACKGROUND"
                                                    }
                                                    triggerRedraw++
                                                }
                                            }
                                            panAccumulator = 0f
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                @Suppress("UNUSED_VARIABLE")
                val redraw = triggerRedraw
                // Redraw juga saat hasil blur gambar siap (cache di ViewModel).
                @Suppress("UNUSED_VARIABLE")
                val imageFx = imageEffectRevision
                val canvasWidth = size.width
                val canvasHeight = size.height

                // Record synchronously for addTextLayer placement (see CanvasEditorState).
                viewModel.canvasState.lastViewportWidth = canvasWidth
                viewModel.canvasState.lastViewportHeight = canvasHeight

                baseBitmap?.let { bmp ->
                    if (!viewModel.canvasState.isTransformInitialized && bmp.width > 0 && bmp.height > 0) {
                        viewModel.canvasState.resetTransform(canvasWidth, canvasHeight, bmp.width.toFloat(), bmp.height.toFloat())
                    }
                }

                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.translate(
                    viewModel.canvasState.offsetX,
                    viewModel.canvasState.offsetY
                )
                drawContext.canvas.nativeCanvas.scale(
                    viewModel.canvasState.scale,
                    viewModel.canvasState.scale
                )

                try {
                    // 1. Base Image
                    baseBitmap?.let { bmp ->
                        if (!bmp.isRecycled) {
                            drawContext.canvas.nativeCanvas.drawBitmap(bmp, 0f, 0f, null)
                        }
                    }

                    // 1b. Red Outline Overlay for Translucent Canvas (Transparent canvas or new project)
                    val showCanvasOutline = project?.isTransparent == true || project?.thumbnailPath == null
                    if (showCanvasOutline) {
                        val canvasW = baseBitmap?.width?.toFloat() ?: (project?.width?.toFloat() ?: 1080f)
                        val canvasH = baseBitmap?.height?.toFloat() ?: (project?.height?.toFloat() ?: 1920f)
                        val currentScale = viewModel.canvasState.scale.coerceAtLeast(0.1f)
                        val strokeW = 3f / currentScale
                        val cornerLen = 24f / currentScale

                        val outlinePaint = outlinePaintCache.apply { strokeWidth = strokeW }

                        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, canvasW, canvasH, outlinePaint)

                        // Corner L-shapes
                        drawContext.canvas.nativeCanvas.drawLine(0f, 0f, cornerLen, 0f, outlinePaint)
                        drawContext.canvas.nativeCanvas.drawLine(0f, 0f, 0f, cornerLen, outlinePaint)

                        drawContext.canvas.nativeCanvas.drawLine(canvasW, 0f, canvasW - cornerLen, 0f, outlinePaint)
                        drawContext.canvas.nativeCanvas.drawLine(canvasW, 0f, canvasW, cornerLen, outlinePaint)

                        drawContext.canvas.nativeCanvas.drawLine(0f, canvasH, cornerLen, canvasH, outlinePaint)
                        drawContext.canvas.nativeCanvas.drawLine(0f, canvasH, 0f, canvasH - cornerLen, outlinePaint)

                        drawContext.canvas.nativeCanvas.drawLine(canvasW, canvasH, canvasW - cornerLen, canvasH, outlinePaint)
                        drawContext.canvas.nativeCanvas.drawLine(canvasW, canvasH, canvasW, canvasH - cornerLen, outlinePaint)
                    }

                    // 2. Red Translucent Mask Selection Overlay (alpha = 0.25f)
                    viewModel.maskSelectionTools?.maskBitmap?.let { maskBmp ->
                        if (!maskBmp.isRecycled) {
                            val maskPaint = maskPaintCache
                            drawContext.canvas.nativeCanvas.drawBitmap(maskBmp, 0f, 0f, maskPaint)
                        }
                    }

                    // 2b. Real-time Lasso visual path line overlay
                    viewModel.maskSelectionTools?.currentLassoPoints?.let { pts ->
                        if (pts.size >= 2) {
                            val currentScale = viewModel.canvasState.scale.coerceAtLeast(0.1f)
                            val lassoPaint = lassoPaintCache.apply {
                                strokeWidth = 4f / currentScale
                                pathEffect = DashPathEffect(floatArrayOf(8f / currentScale, 8f / currentScale), 0f)
                            }
                            lassoPathCache.reset()
                            lassoPathCache.moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) {
                                lassoPathCache.lineTo(pts[i].x, pts[i].y)
                            }
                            drawContext.canvas.nativeCanvas.drawPath(lassoPathCache, lassoPaint)
                        }
                    }

                    // 3. Eyedropper Crosshair & Swatch Preview Overlay
                    if (isEyedropperActive && eyedropperCanvasPt != null) {
                        val pt = eyedropperCanvasPt!!
                        val currentScale = viewModel.canvasState.scale.coerceAtLeast(0.1f)
                        val strokeW = 4f / currentScale
                        val crossArm = 24f / currentScale
                        val swatchRadius = 28f / currentScale
                        val swatchOffset = 60f / currentScale

                        // Red Crosshair Paint
                        val crossPaint = crossPaintCache.apply { strokeWidth = strokeW }
                        drawContext.canvas.nativeCanvas.drawLine(pt.x - crossArm, pt.y, pt.x + crossArm, pt.y, crossPaint)
                        drawContext.canvas.nativeCanvas.drawLine(pt.x, pt.y - crossArm, pt.x, pt.y + crossArm, crossPaint)

                        // Sampled Color Swatch Bubble
                        val sampledCol = sampledColorPreview ?: AndroidColor.BLACK
                        val fillPaint = fillPaintCache.apply { color = sampledCol }
                        val strokePaint = strokePaintCache.apply { strokeWidth = 3f / currentScale }
                        val swatchCenter = Offset(pt.x, pt.y - swatchOffset)
                        drawContext.canvas.nativeCanvas.drawCircle(swatchCenter.x, swatchCenter.y, swatchRadius, fillPaint)
                        drawContext.canvas.nativeCanvas.drawCircle(swatchCenter.x, swatchCenter.y, swatchRadius, strokePaint)
                    }





                    // 3. Render Layers




                    layers.forEach { layer ->
                        if (layer.isVisible) {
                            when (layer) {
                                is Layer.TextLayer -> {
                                    val alphaPaint = alphaPaintCache.apply {
                                        alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                                    }
                                    val count = drawContext.canvas.nativeCanvas.saveLayer(null, alphaPaint)

                                    val bounds: RectF = textRenderer.getTextBounds(layer)
                                    val textCenterX = bounds.centerX()
                                    val textCenterY = bounds.centerY()

                                    drawContext.canvas.nativeCanvas.save()
                                    if (layer.rotation != 0f) {
                                        drawContext.canvas.nativeCanvas.rotate(layer.rotation, textCenterX, textCenterY)
                                    }

                                    textRenderer.drawStyledText(
                                        canvas = drawContext.canvas.nativeCanvas,
                                        layer = layer
                                    )

                                    // Render bounding box & controls (Resize, Rotate, Delete, Stretch V/H) if selected
                                    if (layer.id == selectedLayerId) {
                                        val currentScale = viewModel.canvasState.scale
                                        val strokeW = 3f / currentScale
                                        val handleRadius = 24f / currentScale

                                        val boxPaint = boxPaintCache.apply {
                                            strokeWidth = strokeW
                                            pathEffect = DashPathEffect(floatArrayOf(10f / currentScale, 10f / currentScale), 0f)
                                        }
                                        if (layer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                                            drawContext.canvas.nativeCanvas.drawOval(bounds, boxPaint)
                                        } else {
                                            drawContext.canvas.nativeCanvas.drawRect(bounds, boxPaint)
                                        }

                                        val handleFillPaint = handleFillPaintCache
                                        val handleStrokePaint = handleStrokePaintCache.apply { strokeWidth = strokeW }
                                        val anchorsMap = handleAnchors(bounds, currentScale).toMap()

                                        // 1. Bottom-Right Resize Handle (Diagonal Double-Arrow Icon ↗↙)
                                        val resizePos = anchorsMap[TextHandleType.RESIZE] ?: Offset(bounds.right, bounds.bottom)
                                        drawContext.canvas.nativeCanvas.drawCircle(resizePos.x, resizePos.y, handleRadius, resizeFillPaintCache)
                                        drawContext.canvas.nativeCanvas.drawCircle(resizePos.x, resizePos.y, handleRadius, handleStrokePaint)

                                        val resizeIconPaint = resizeIconPaintCache.apply { strokeWidth = 2.5f / currentScale }
                                        val diagOff = handleRadius * 0.45f
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            resizePos.x - diagOff, resizePos.y + diagOff,
                                            resizePos.x + diagOff, resizePos.y - diagOff,
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            resizePos.x + diagOff, resizePos.y - diagOff,
                                            resizePos.x + diagOff - (diagOff * 0.6f), resizePos.y - diagOff,
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            resizePos.x + diagOff, resizePos.y - diagOff,
                                            resizePos.x + diagOff, resizePos.y - diagOff + (diagOff * 0.6f),
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            resizePos.x - diagOff, resizePos.y + diagOff,
                                            resizePos.x - diagOff + (diagOff * 0.6f), resizePos.y + diagOff,
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            resizePos.x - diagOff, resizePos.y + diagOff,
                                            resizePos.x - diagOff, resizePos.y + diagOff - (diagOff * 0.6f),
                                            resizeIconPaint
                                        )

                                        // 2. Top-Left Delete (X) Button (Red Circle + White "X" Icon)
                                        val deletePos = anchorsMap[TextHandleType.DELETE] ?: Offset(bounds.left, bounds.top)
                                        val xPaint = xPaintCache.apply { strokeWidth = 3f / currentScale }
                                        drawContext.canvas.nativeCanvas.drawCircle(deletePos.x, deletePos.y, handleRadius, deleteFillPaintCache)
                                        val crossOffset = handleRadius * 0.45f
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            deletePos.x - crossOffset, deletePos.y - crossOffset,
                                            deletePos.x + crossOffset, deletePos.y + crossOffset, xPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            deletePos.x + crossOffset, deletePos.y - crossOffset,
                                            deletePos.x - crossOffset, deletePos.y + crossOffset, xPaint
                                        )

                                        // 3. Top-Right Rotate Handle (Green Circle + Circular Arrow Icon)
                                        val rotatePos = anchorsMap[TextHandleType.ROTATE] ?: Offset(bounds.right, bounds.top)
                                        drawContext.canvas.nativeCanvas.drawCircle(rotatePos.x, rotatePos.y, handleRadius, rotateFillPaintCache)
                                        drawContext.canvas.nativeCanvas.drawCircle(rotatePos.x, rotatePos.y, handleRadius, handleStrokePaint)

                                        val rotateArcPaint = rotateArcPaintCache.apply { strokeWidth = 2.5f / currentScale }
                                        val arcR = handleRadius * 0.5f
                                        arcRectCache.set(
                                            rotatePos.x - arcR, rotatePos.y - arcR,
                                            rotatePos.x + arcR, rotatePos.y + arcR
                                        )
                                        drawContext.canvas.nativeCanvas.drawArc(arcRectCache, 45f, 270f, false, rotateArcPaint)
                                        val tipX = rotatePos.x + arcR * kotlin.math.cos(Math.toRadians(45.0)).toFloat()
                                        val tipY = rotatePos.y + arcR * kotlin.math.sin(Math.toRadians(45.0)).toFloat()
                                        rotateArrowPathCache.reset()
                                        rotateArrowPathCache.moveTo(tipX, tipY)
                                        rotateArrowPathCache.lineTo(tipX + 4f / currentScale, tipY - 5f / currentScale)
                                        rotateArrowPathCache.lineTo(tipX + 5f / currentScale, tipY + 4f / currentScale)
                                        rotateArrowPathCache.close()
                                        val rotateArrowPaint = rotateArrowPaintCache
                                        drawContext.canvas.nativeCanvas.drawPath(rotateArrowPathCache, rotateArrowPaint)

                                        // 4. Vertical Stretch Handle (Bottom Center - Pill + ↕ Arrow Icon)
                                        val stretchVPos = anchorsMap[TextHandleType.STRETCH_V] ?: Offset(bounds.centerX(), bounds.bottom + 40f / currentScale)
                                        val pillW = handleRadius * 1.6f
                                        val pillH = handleRadius * 0.9f
                                        val vArrowPaint = vArrowPaintCache.apply { strokeWidth = 2f / currentScale }

                                        pillRectCache.set(stretchVPos.x - pillW / 2f, stretchVPos.y - pillH / 2f, stretchVPos.x + pillW / 2f, stretchVPos.y + pillH / 2f)
                                        drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleFillPaint)
                                        drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleStrokePaint)

                                        val arrowLenV = pillH * 0.35f
                                        drawContext.canvas.nativeCanvas.drawLine(stretchVPos.x, stretchVPos.y - arrowLenV, stretchVPos.x, stretchVPos.y + arrowLenV, vArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchVPos.x, stretchVPos.y - arrowLenV, stretchVPos.x - 3f / currentScale, stretchVPos.y - arrowLenV + 3f / currentScale, vArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchVPos.x, stretchVPos.y - arrowLenV, stretchVPos.x + 3f / currentScale, stretchVPos.y - arrowLenV + 3f / currentScale, vArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchVPos.x, stretchVPos.y + arrowLenV, stretchVPos.x - 3f / currentScale, stretchVPos.y + arrowLenV - 3f / currentScale, vArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchVPos.x, stretchVPos.y + arrowLenV, stretchVPos.x + 3f / currentScale, stretchVPos.y + arrowLenV - 3f / currentScale, vArrowPaint)

                                        // 5. Horizontal Stretch Handle (Right Center - Pill + ↔ Arrow Icon)
                                        val stretchHPos = anchorsMap[TextHandleType.STRETCH_H] ?: Offset(bounds.right + 40f / currentScale, bounds.centerY())
                                        val pillHW = handleRadius * 0.9f
                                        val pillHH = handleRadius * 1.6f
                                        val hArrowPaint = hArrowPaintCache.apply { strokeWidth = 2f / currentScale }

                                        pillRectCache.set(stretchHPos.x - pillHW / 2f, stretchHPos.y - pillHH / 2f, stretchHPos.x + pillHW / 2f, stretchHPos.y + pillHH / 2f)
                                        drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleFillPaint)
                                        drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleStrokePaint)

                                        val arrowLenH = pillHW * 0.35f
                                        drawContext.canvas.nativeCanvas.drawLine(stretchHPos.x - arrowLenH, stretchHPos.y, stretchHPos.x + arrowLenH, stretchHPos.y, hArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchHPos.x - arrowLenH, stretchHPos.y, stretchHPos.x - arrowLenH + 3f / currentScale, stretchHPos.y - 3f / currentScale, hArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchHPos.x - arrowLenH, stretchHPos.y, stretchHPos.x - arrowLenH + 3f / currentScale, stretchHPos.y + 3f / currentScale, hArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchHPos.x + arrowLenH, stretchHPos.y, stretchHPos.x + arrowLenH - 3f / currentScale, stretchHPos.y - 3f / currentScale, hArrowPaint)
                                        drawContext.canvas.nativeCanvas.drawLine(stretchHPos.x + arrowLenH, stretchHPos.y, stretchHPos.x + arrowLenH - 3f / currentScale, stretchHPos.y + 3f / currentScale, hArrowPaint)
                                    }

                                    drawContext.canvas.nativeCanvas.restore()
                                    drawContext.canvas.nativeCanvas.restoreToCount(count)
                                }
                                is Layer.ImageLayer -> {
                                    val imgBmp = viewModel.resolveImageBitmap(layer)
                                    if (imgBmp != null && !imgBmp.isRecycled) {
                                        val layerAlpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                                        viewModel.resolveImageGlow(layer)?.let { (glowBmp, pad) ->
                                            if (!glowBmp.isRecycled) {
                                                val glowPaint = alphaPaintCache.apply {
                                                    alpha = layerAlpha
                                                    colorFilter = null
                                                }
                                                drawContext.canvas.nativeCanvas.drawBitmap(
                                                    glowBmp, layer.x - pad, layer.y - pad, glowPaint
                                                )
                                            }
                                        }
                                        val imgPaint = alphaPaintCache.apply {
                                            alpha = layerAlpha
                                            colorFilter = com.mochits.app.imaging.ImageEffects.imageColorFilter(
                                                layer.grayscale, layer.brightness, layer.contrast
                                            )
                                        }
                                        drawContext.canvas.nativeCanvas.drawBitmap(imgBmp, layer.x, layer.y, imgPaint)
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Logger.e("Error: ${t.message}", t)
                }

                drawContext.canvas.nativeCanvas.restore()
            }

            // Floating Confirm/Cancel Action Bar for Eyedropper (Pill Container)
            // Loading Overlay for Magic Wand Processing
            if (isProcessingMagicWand) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .pointerInput(Unit) { },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = "Memproses seleksi Magic Wand...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            if (isEyedropperActive) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                        .widthIn(max = 500.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Icon + Instruction
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Colorize,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Geser crosshair ambil warna",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }

                        // Center/Right: Sample Swatch + Action Buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Sampled Color Swatch Circle Preview
                            val sampledCol = sampledColorPreview ?: AndroidColor.BLACK
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(
                                        color = Color(sampledCol),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            )

                            // Confirm Button
                            Button(
                                onClick = { viewModel.confirmEyedropper() },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "Pilih",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            // Cancel Button
                            IconButton(
                                onClick = { viewModel.cancelEyedropper() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Batal",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add Text Dialog
        if (showAddTextDialog) {
            AlertDialog(
                onDismissRequest = { showAddTextDialog = false },
                title = { Text("Tambah Layer Teks", style = MaterialTheme.typography.titleLarge) },
                text = {
                    OutlinedTextField(
                        value = newTextValue,
                        onValueChange = { newTextValue = it },
                        label = { Text("Masukkan Teks") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTextValue.isNotBlank()) {
                                viewModel.addTextLayer(newTextValue, viewportWidth = currentViewportW, viewportHeight = currentViewportH)
                                newTextValue = ""
                                showAddTextDialog = false
                            }
                        }
                    ) {
                        Text("Tambah")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddTextDialog = false }) {
                        Text("Batal")
                    }
                }
            )
        }

        if (isLoadingImage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Memproses Gambar...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // Overlay saat tombol back ditekan: save butuh waktu, tanpa ini
        // aplikasi terlihat mati (tidak merespons) selama flush berjalan.
        if (isNavigatingBack) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Menyimpan proyek...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // Save As / Export / Rename Dialog
        if (showExportDialog) {
            val defaultFolderUri = viewModel.getDefaultExportFolderUri()
            val defaultFolderName = viewModel.getDefaultExportFolderName()
            val isFolderValid = viewModel.isExportFolderValid(defaultFolderUri)

            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Save As", style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = outputFileName,
                            onValueChange = {
                                outputFileName = it
                                projectTitleName = it
                            },
                            label = { Text("Nama Output / Nama Proyek") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Format Gambar:", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("PNG", "JPEG", "WEBP").forEach { fmt ->
                                FilterChip(
                                    selected = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt },
                                    label = { Text(fmt) }
                                )
                            }
                        }
                        if (selectedFormat != "PNG") {
                            Text("Kualitas: ${exportQuality.toInt()}%")
                            Slider(
                                value = exportQuality,
                                onValueChange = { exportQuality = it },
                                valueRange = 10f..100f
                            )
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Folder Tujuan Export (Default Pengaturan):", style = MaterialTheme.typography.labelSmall)
                                Spacer(modifier = Modifier.height(2.dp))
                                if (defaultFolderUri != null && isFolderValid) {
                                    Text(
                                        text = defaultFolderName ?: defaultFolderUri.toString(),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "Belum ada folder default. Saat menekan Simpan, Anda akan diminta memilih folder.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (outputFileName.isNotBlank() && outputFileName != project?.title) {
                                viewModel.updateProjectTitle(outputFileName)
                            }
                            showExportDialog = false
                            val saveName = if (outputFileName.isNotBlank()) outputFileName else "export"
                            val defaultFolder = viewModel.getDefaultExportFolderUri()
                            if (defaultFolder != null && viewModel.isExportFolderValid(defaultFolder)) {
                                performExportToTreeUri(
                                    context = context,
                                    viewModel = viewModel,
                                    treeUri = defaultFolder,
                                    saveName = saveName,
                                    selectedFormat = selectedFormat,
                                    exportQuality = exportQuality
                                )
                            } else {
                                pendingExportSaveName = saveName
                                pendingExportFormat = selectedFormat
                                pendingExportQuality = exportQuality
                                folderPickerLauncher.launch(null)
                            }
                        }
                    ) {
                        Text("Simpan & Ekspor")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Batal")
                    }
                }
            )
        }
        if (showDownloadErrorDialog && lastDownloadError != null) {
            DownloadErrorDialog(
                errorInfo = lastDownloadError!!,
                onDismiss = { showDownloadErrorDialog = false }
            )
        }
    }
}

@Composable
fun EditorBottomBar(
    activePanel: EditorPanel,
    onPanelSelect: (EditorPanel) -> Unit
) {
    // Bar geser horizontal (seperti menu Effect): menu baru bisa ditambah
    // tanpa mengecilkan ikon yang sudah ada.
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            NavigationBarItem(
            selected = activePanel == EditorPanel.ERASE || activePanel == EditorPanel.MASK || activePanel == EditorPanel.INPAINT,
            onClick = { onPanelSelect(EditorPanel.ERASE) },
            icon = { Icon(Icons.Default.CleaningServices, contentDescription = "Erase") },
            label = { Text("Erase") },
            modifier = Modifier.widthIn(min = 72.dp)
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.TEXT,
            onClick = { onPanelSelect(EditorPanel.TEXT) },
            icon = { Icon(Icons.Default.TextFields, contentDescription = "Text") },
            label = { Text("Text") },
            modifier = Modifier.widthIn(min = 72.dp)
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.EFFECT,
            onClick = { onPanelSelect(EditorPanel.EFFECT) },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Effect") },
            label = { Text("Effect") },
            modifier = Modifier.widthIn(min = 72.dp)
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.FONT,
            onClick = { onPanelSelect(EditorPanel.FONT) },
            icon = { Icon(Icons.Default.FontDownload, contentDescription = "Font") },
            label = { Text("Font") },
            modifier = Modifier.widthIn(min = 72.dp)
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.STYLE,
            onClick = { onPanelSelect(EditorPanel.STYLE) },
            icon = { Icon(Icons.Default.Style, contentDescription = "Style") },
            label = { Text("Style") },
            modifier = Modifier.widthIn(min = 72.dp)
        )
        }
    }
}

@Composable
fun EraseToolPanel(
    mode: MaskToolMode,
    brushSize: Float,
    magicWandTolerance: Float,
    magicWandExpand: Float = 0f,
    selectedModel: EditorViewModel.InpaintModel,
    isProcessing: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    isCollapsed: Boolean,
    hasMask: Boolean = false,
    onToggleCollapse: () -> Unit,
    onModeSelected: (MaskToolMode) -> Unit,
    onModelSelected: (EditorViewModel.InpaintModel) -> Unit,
    onSizeChange: (Float) -> Unit,
    onToleranceChange: (Float) -> Unit,
    onExpandChange: ((Float) -> Unit)? = null,
    onClear: () -> Unit,
    onInvert: () -> Unit,
    onRunErase: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tool Erase (Penghapus Objek)",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onToggleCollapse) {
                    Icon(
                        if (isCollapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isCollapsed) "Expand Panel" else "Collapse Panel"
                    )
                }
            }

            if (!isCollapsed) {
                Spacer(modifier = Modifier.height(6.dp))

                // Selection status hint: tells the user whether there is
                // anything to erase yet.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (hasMask) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Text(
                        text = if (hasMask) "Ada area terpilih — siap dihapus"
                        else "Belum ada area terpilih",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text("Alat Seleksi Area:", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = mode == MaskToolMode.BRUSH,
                        onClick = { onModeSelected(MaskToolMode.BRUSH) },
                        label = { Text("Brush") }
                    )
                    FilterChip(
                        selected = mode == MaskToolMode.ERASER,
                        onClick = { onModeSelected(MaskToolMode.ERASER) },
                        label = { Text("Eraser") }
                    )
                    FilterChip(
                        selected = mode == MaskToolMode.LASSO,
                        onClick = { onModeSelected(MaskToolMode.LASSO) },
                        label = { Text("Lasso") }
                    )
                    FilterChip(
                        selected = mode == MaskToolMode.MAGIC_WAND,
                        onClick = { onModeSelected(MaskToolMode.MAGIC_WAND) },
                        label = { Text("Magic Wand") }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text("Model Inpaint:", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedModel == EditorViewModel.InpaintModel.TELEA,
                        onClick = { onModelSelected(EditorViewModel.InpaintModel.TELEA) },
                        label = { Text("Telea (Cepat)") }
                    )
                    FilterChip(
                        selected = selectedModel == EditorViewModel.InpaintModel.LAMA,
                        onClick = { onModelSelected(EditorViewModel.InpaintModel.LAMA) },
                        label = { Text("LaMa (AI)") }
                    )
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column {
                        Text(
                            text = "Mengunduh Model LaMa... ${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onClear) { Text("Clear Mask") }
                    OutlinedButton(onClick = onInvert) { Text("Invert") }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRunErase,
                    enabled = !isProcessing && !isDownloading && hasMask,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Memproses...")
                    } else {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Hapus / Inpaint")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                if (mode == MaskToolMode.MAGIC_WAND) {
                    if (!hasMask) {
                        Text(
                            text = "Ketuk objek pada gambar untuk menyeleksi area warna yang sama.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text("Toleransi Warna (Tolerance): ${magicWandTolerance.toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = magicWandTolerance,
                        onValueChange = onToleranceChange,
                        valueRange = 0f..100f
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    // Local drag state: the expensive re-dilate runs only on
                    // release, keeping the slider smooth on big masks.
                    var localExpandValue by remember(magicWandExpand) { mutableFloatStateOf(magicWandExpand) }

                    Text("Perluas Margin (Expand): ${localExpandValue.toInt()} px", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = localExpandValue,
                        onValueChange = { localExpandValue = it },
                        onValueChangeFinished = {
                            if (localExpandValue != magicWandExpand) {
                                onExpandChange?.invoke(localExpandValue)
                            }
                        },
                        valueRange = 0f..30f
                    )
                } else {
                    Text("Ukuran Kuas: ${brushSize.toInt()} px", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = brushSize,
                        onValueChange = onSizeChange,
                        valueRange = 5f..200f
                    )
                }
            }
        }
    }
}

@Composable
fun TextToolPanel(
    selectedLayer: Layer.TextLayer?,
    defaultStyle: TextStyleConfig,
    onAddText: (String) -> Unit,
    onUpdateTextContent: ((String) -> Unit)? = null,
    onUpdateStyle: (TextStyleConfig, Boolean) -> Unit,
    onUpdateContainerShape: ((com.mochits.app.model.TextContainerShape) -> Unit)? = null,
    autoFocus: Boolean = false,
    onFocused: (() -> Unit)? = null,
    // Called once when a typing session starts (first keystroke after layer
    // switch or external change). Wire to saveUndoSnapshot() so typing creates
    // a single undo step instead of one heavy snapshot per character (which
    // also wiped the redo stack on every keystroke).
    onEditStart: (() -> Unit)? = null,
    // Called on every keystroke; wire to a throttled autoSave() so typed text
    // is durable without creating undo steps.
    onRequestAutosave: (() -> Unit)? = null
) {
    var textInput by remember { mutableStateOf(selectedLayer?.text ?: "") }
    val focusRequester = remember { FocusRequester() }
    // Tracks which layer already banked its session snapshot. Reset on external
    // changes (undo, layer switch) so the next keystroke snapshots again.
    var editSnapshotLayerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedLayer?.id, selectedLayer?.text) {
        val incoming = selectedLayer?.text ?: ""
        if (incoming != textInput) {
            textInput = incoming
            editSnapshotLayerId = null
        }
    }

    LaunchedEffect(autoFocus, selectedLayer?.id) {
        if (autoFocus && selectedLayer != null) {
            kotlinx.coroutines.delay(100)
            try {
                focusRequester.requestFocus()
                onFocused?.invoke()
            } catch (e: Exception) {
                com.mochits.app.util.Logger.e("Failed to request focus: ${e.message}")
            }
        }
    }

    val currentStyle = selectedLayer?.style ?: defaultStyle

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (selectedLayer != null) "Tool Teks (Edit Layer)" else "Tool Teks (Tambah Baru)",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = textInput,
                onValueChange = { newText ->
                    textInput = newText
                    if (selectedLayer != null) {
                        if (editSnapshotLayerId != selectedLayer.id) {
                            editSnapshotLayerId = selectedLayer.id
                            onEditStart?.invoke()
                        }
                        onUpdateTextContent?.invoke(newText)
                        onRequestAutosave?.invoke()
                    }
                },
                label = { Text(if (selectedLayer != null) "Edit Teks" else "Teks Baru") },
                placeholder = { Text("Ketik dialog di sini...") },
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        if (selectedLayer != null) {
                            onUpdateTextContent?.invoke(textInput)
                        } else {
                            onAddText(textInput)
                            textInput = ""
                        }
                    }
                },
                enabled = textInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (selectedLayer != null) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (selectedLayer != null) "Simpan Perubahan" else "Tambah Teks ke Kanvas")
            }

            Text("Alignment Teks:", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentStyle.alignment == com.mochits.app.model.TextAlignment.LEFT,
                    onClick = { onUpdateStyle(currentStyle.copy(alignment = com.mochits.app.model.TextAlignment.LEFT), true) },
                    label = { Text("Rata Kiri") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, contentDescription = "Rata Kiri", modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = currentStyle.alignment == com.mochits.app.model.TextAlignment.CENTER,
                    onClick = { onUpdateStyle(currentStyle.copy(alignment = com.mochits.app.model.TextAlignment.CENTER), true) },
                    label = { Text("Rata Tengah") },
                    leadingIcon = { Icon(Icons.Default.FormatAlignCenter, contentDescription = "Rata Tengah", modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = currentStyle.alignment == com.mochits.app.model.TextAlignment.RIGHT,
                    onClick = { onUpdateStyle(currentStyle.copy(alignment = com.mochits.app.model.TextAlignment.RIGHT), true) },
                    label = { Text("Rata Kanan") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatAlignRight, contentDescription = "Rata Kanan", modifier = Modifier.size(18.dp)) }
                )
            }

            if (selectedLayer != null) {
                Text("Bentuk Kontainer Teks:", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedLayer.textContainerShape == com.mochits.app.model.TextContainerShape.BOX,
                        onClick = { onUpdateContainerShape?.invoke(com.mochits.app.model.TextContainerShape.BOX) },
                        label = { Text("Kotak") }
                    )
                    FilterChip(
                        selected = selectedLayer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL,
                        onClick = { onUpdateContainerShape?.invoke(com.mochits.app.model.TextContainerShape.OVAL) },
                        label = { Text("Oval") }
                    )
                }
            }
        }
    }
}

@Composable
fun SimpleColorPickerRow(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit
) {
    var showCustomHexDialog by remember { mutableStateOf(false) }
    var hexInput by remember { mutableStateOf("") }
    var isHexError by remember { mutableStateOf(false) }

    val colors = com.mochits.app.ui.color.ColorUtils.defaultPresetColors
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        items(colors) { c ->
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(c), shape = androidx.compose.foundation.shape.CircleShape)
                    .border(
                        width = if (selectedColor == c) 2.5.dp else 1.dp,
                        color = if (selectedColor == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable { onColorSelected(c) },
                contentAlignment = Alignment.Center
            ) {
                if (selectedColor == c) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = if (com.mochits.app.ui.color.ColorUtils.isLightColor(c)) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        item {
            IconButton(
                onClick = {
                    hexInput = String.format("#%06X", 0xFFFFFF and selectedColor)
                    isHexError = false
                    showCustomHexDialog = true
                },
                modifier = Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = "Custom Hex",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showCustomHexDialog) {
        AlertDialog(
            onDismissRequest = { showCustomHexDialog = false },
            title = { Text("Pilih Warna Custom (Hex)", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = {
                        hexInput = it
                        isHexError = try {
                            AndroidColor.parseColor(it)
                            false
                        } catch (_: Throwable) {
                            true
                        }
                    },
                    label = { Text("Hex Color (contoh: #FF5722)") },
                    supportingText = {
                        if (isHexError) {
                            Text("Format salah, contoh valid: #FF5722", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    isError = isHexError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val parsed = AndroidColor.parseColor(hexInput)
                            onColorSelected(parsed)
                            showCustomHexDialog = false
                        } catch (_: Throwable) {
                            isHexError = true
                        }
                    }
                ) {
                    Text("Terapkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomHexDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

private enum class EffectType {
    OPACITY,
    TEXT_COLOR,
    STROKE,
    DROP_SHADOW,
    MOTION_BLUR,
    GLOW,
    IMAGE_TONE,
    IMAGE_MOTION_BLUR,
    IMAGE_GLOW
}

@Composable
fun EffectToolPanel(
    selectedLayer: Layer?,
    onUpdateOpacity: (Float, Boolean) -> Unit,
    onUpdateStyle: (TextStyleConfig, Boolean) -> Unit,
    onSliderDragStart: () -> Unit = {},
    onSliderDragEnd: () -> Unit = {},
    onStartEyedropper: ((Int) -> Unit) -> Unit = {},
    onUpdateImageLayer: ((Layer.ImageLayer) -> Unit)? = null
) {
    var expandedEffect by remember(selectedLayer?.id) { mutableStateOf<EffectType?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onSliderDragEnd()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Efek Layer", style = MaterialTheme.typography.titleMedium)

            if (selectedLayer == null) {
                Text(
                    text = "Pilih layer terlebih dahulu untuk mengatur transparansi dan efek.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val availableEffects = remember(selectedLayer) {
                    if (selectedLayer is Layer.TextLayer) {
                        listOf(EffectType.OPACITY, EffectType.TEXT_COLOR, EffectType.STROKE, EffectType.DROP_SHADOW, EffectType.MOTION_BLUR, EffectType.GLOW)
                    } else if (selectedLayer is Layer.ImageLayer) {
                        listOf(EffectType.OPACITY, EffectType.IMAGE_TONE, EffectType.IMAGE_MOTION_BLUR, EffectType.IMAGE_GLOW)
                    } else {
                        listOf(EffectType.OPACITY)
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    items(availableEffects) { effect ->
                        val isSelected = expandedEffect == effect
                        val isActive = when (effect) {
                            EffectType.OPACITY -> selectedLayer.opacity < 1.0f
                            EffectType.TEXT_COLOR -> if (selectedLayer is Layer.TextLayer) {
                                selectedLayer.style.isGradientEnabled ||
                                selectedLayer.style.textColor != AndroidColor.BLACK ||
                                selectedLayer.style.textOpacity < 1.0f
                            } else false
                            EffectType.STROKE -> if (selectedLayer is Layer.TextLayer) {
                                selectedLayer.style.strokeWidth > 0f &&
                                selectedLayer.style.strokeColor != AndroidColor.TRANSPARENT
                            } else false
                            EffectType.DROP_SHADOW -> if (selectedLayer is Layer.TextLayer) {
                                selectedLayer.style.shadowColor != AndroidColor.TRANSPARENT &&
                                (selectedLayer.style.shadowRadius > 0f ||
                                 selectedLayer.style.shadowDx != 0f ||
                                 selectedLayer.style.shadowDy != 0f)
                            } else false
                            EffectType.MOTION_BLUR -> if (selectedLayer is Layer.TextLayer) {
                                selectedLayer.style.motionBlurRadius > 0f
                            } else false
                            EffectType.GLOW -> if (selectedLayer is Layer.TextLayer) {
                                selectedLayer.style.glowColor != AndroidColor.TRANSPARENT &&
                                selectedLayer.style.glowRadius > 0f
                            } else false
                            EffectType.IMAGE_TONE -> if (selectedLayer is Layer.ImageLayer) {
                                selectedLayer.grayscale > 0f ||
                                selectedLayer.brightness != 0f ||
                                selectedLayer.contrast != 1f
                            } else false
                            EffectType.IMAGE_MOTION_BLUR -> if (selectedLayer is Layer.ImageLayer) {
                                selectedLayer.motionBlurRadius > 0f
                            } else false
                            EffectType.IMAGE_GLOW -> if (selectedLayer is Layer.ImageLayer) {
                                selectedLayer.glowColor != AndroidColor.TRANSPARENT &&
                                selectedLayer.glowRadius > 0f
                            } else false
                        }

                        val (icon, title) = when (effect) {
                            EffectType.OPACITY -> Icons.Default.Opacity to "Opacity"
                            EffectType.TEXT_COLOR -> Icons.Default.Palette to "Warna Teks"
                            EffectType.STROKE -> Icons.Default.FormatPaint to "Stroke"
                            EffectType.DROP_SHADOW -> Icons.Default.WbSunny to "Drop Shadow"
                            EffectType.MOTION_BLUR -> Icons.Default.BlurLinear to "Motion Blur"
                            EffectType.GLOW -> Icons.Default.BlurCircular to "Glow"
                            EffectType.IMAGE_TONE -> Icons.Default.Tune to "Tone"
                            EffectType.IMAGE_MOTION_BLUR -> Icons.Default.BlurLinear to "Motion Blur"
                            EffectType.IMAGE_GLOW -> Icons.Default.BlurCircular to "Glow"
                        }

                        Card(
                            onClick = {
                                expandedEffect = if (isSelected) null else effect
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else if (isActive) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            border = if (isSelected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else if (isActive) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            } else null,
                            modifier = Modifier.width(105.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (isActive) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(8.dp)
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = title,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                if (expandedEffect != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (expandedEffect) {
                            EffectType.OPACITY -> {
                                Text("Transparansi Layer (Opacity): ${(selectedLayer.opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                                Slider(
                                    value = selectedLayer.opacity,
                                    onValueChange = {
                                        onSliderDragStart()
                                        onUpdateOpacity(it, false)
                                    },
                                    onValueChangeFinished = {
                                        onSliderDragEnd()
                                    },
                                    valueRange = 0f..1f
                                )
                            }
                            EffectType.TEXT_COLOR -> {
                                if (selectedLayer is Layer.TextLayer) {
                                    val currentStyle = selectedLayer.style

                                    Text("Warna Fill Teks:", style = MaterialTheme.typography.titleSmall)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilterChip(
                                            selected = !currentStyle.isGradientEnabled,
                                            onClick = { onUpdateStyle(currentStyle.copy(isGradientEnabled = false), true) },
                                            label = { Text("Solid Color") }
                                        )
                                        FilterChip(
                                            selected = currentStyle.isGradientEnabled,
                                            onClick = { onUpdateStyle(currentStyle.copy(isGradientEnabled = true), true) },
                                            label = { Text("Gradient") }
                                        )
                                    }

                                    if (!currentStyle.isGradientEnabled) {
                                        Text("Warna Teks (Solid):", style = MaterialTheme.typography.bodySmall)
                                        ColorPickerRow(
                                            selectedColor = currentStyle.textColor,
                                            onColorSelected = { col -> onUpdateStyle(currentStyle.copy(textColor = col), true) },
                                            onCustomColorChange = { col -> onUpdateStyle(currentStyle.copy(textColor = col), false) },
                                            onPickStart = { onSliderDragStart() },
                                            onPickEnd = { onSliderDragEnd() },
                                            onEyedropperClick = {
                                                onStartEyedropper { sampledCol ->
                                                    onUpdateStyle(currentStyle.copy(textColor = sampledCol), true)
                                                }
                                            }
                                        )
                                    } else {
                                        val stops = currentStyle.getEffectiveGradientStops()

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Color Stops (${stops.size}):", style = MaterialTheme.typography.bodySmall)
                                            IconButton(
                                                onClick = {
                                                    val lastPos = stops.lastOrNull()?.position ?: 1.0f
                                                    // No room left: adding another stop at 1.0 would
                                                    // duplicate the position and break the gradient.
                                                    if (lastPos >= 1f) return@IconButton
                                                    val newPos = (lastPos + 0.1f).coerceAtMost(1.0f)
                                                    val newColor = stops.lastOrNull()?.color ?: AndroidColor.WHITE
                                                    val updatedStops = stops + com.mochits.app.model.ColorStop(color = newColor, position = newPos)
                                                    onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), true)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Tambah Warna", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }

                                        stops.forEachIndexed { index, stop ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text("Stop ${index + 1}", style = MaterialTheme.typography.labelSmall)
                                                        IconButton(
                                                            onClick = {
                                                                if (stops.size > 2) {
                                                                    val updatedStops = stops.toMutableList().apply { removeAt(index) }
                                                                    onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), true)
                                                                }
                                                            },
                                                            enabled = stops.size > 2,
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Close,
                                                                contentDescription = "Hapus Stop",
                                                                tint = if (stops.size > 2) MaterialTheme.colorScheme.error else Color.Gray,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }

                                                    ColorPickerRow(
                                                        selectedColor = stop.color,
                                                        onColorSelected = { newColor ->
                                                            val updatedStops = stops.toMutableList().apply {
                                                                this[index] = stop.copy(color = newColor)
                                                            }
                                                            onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), true)
                                                        },
                                                        onCustomColorChange = { newColor ->
                                                            val updatedStops = stops.toMutableList().apply {
                                                                this[index] = stop.copy(color = newColor)
                                                            }
                                                            onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), false)
                                                        },
                                                        onPickStart = { onSliderDragStart() },
                                                        onPickEnd = { onSliderDragEnd() },
                                                        onEyedropperClick = {
                                                            onStartEyedropper { sampledCol ->
                                                                val updatedStops = stops.toMutableList().apply {
                                                                    this[index] = stop.copy(color = sampledCol)
                                                                }
                                                                onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), true)
                                                            }
                                                        }
                                                    )

                                                    val posPercent = (stop.position * 100).toInt()
                                                    Text("Posisi: $posPercent%", style = MaterialTheme.typography.bodySmall)
                                                    Slider(
                                                        value = stop.position,
                                                        onValueChange = { newPos ->
                                                            onSliderDragStart()
                                                            val updatedStops = stops.toMutableList().apply {
                                                                this[index] = stop.copy(position = newPos)
                                                            }
                                                            onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), false)
                                                        },
                                                        onValueChangeFinished = {
                                                            onSliderDragEnd()
                                                        },
                                                        valueRange = 0f..1f
                                                    )

                                                    val currentAlphaFloat = AndroidColor.alpha(stop.color) / 255f
                                                    val alphaPercent = (currentAlphaFloat * 100).toInt()
                                                    Text("Alpha (Transparansi): $alphaPercent%", style = MaterialTheme.typography.bodySmall)
                                                    Slider(
                                                        value = currentAlphaFloat,
                                                        onValueChange = { newAlpha ->
                                                            onSliderDragStart()
                                                            val alphaInt = (newAlpha * 255).toInt().coerceIn(0, 255)
                                                            val r = AndroidColor.red(stop.color)
                                                            val g = AndroidColor.green(stop.color)
                                                            val b = AndroidColor.blue(stop.color)
                                                            val combinedColor = AndroidColor.argb(alphaInt, r, g, b)
                                                            val updatedStops = stops.toMutableList().apply {
                                                                this[index] = stop.copy(color = combinedColor)
                                                            }
                                                            onUpdateStyle(currentStyle.copy(gradientStops = updatedStops), false)
                                                        },
                                                        onValueChangeFinished = {
                                                            onSliderDragEnd()
                                                        },
                                                        valueRange = 0f..1f
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        val angleDegree = currentStyle.gradientAngle.toInt()
                                        Text("Sudut Gradient (Angle): ${angleDegree}°", style = MaterialTheme.typography.bodySmall)
                                        Slider(
                                            value = currentStyle.gradientAngle,
                                            onValueChange = { newAngle ->
                                                onSliderDragStart()
                                                onUpdateStyle(currentStyle.copy(gradientAngle = newAngle), false)
                                            },
                                            onValueChangeFinished = {
                                                onSliderDragEnd()
                                            },
                                            valueRange = 0f..360f
                                        )
                                    }

                                    Text("Opacity Teks (Fill): ${(currentStyle.textOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.textOpacity,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateStyle(currentStyle.copy(textOpacity = it), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..1f
                                    )
                                }
                            }
                            EffectType.STROKE -> {
                                if (selectedLayer is Layer.TextLayer) {
                                    val currentStyle = selectedLayer.style

                                    Text("Warna Stroke/Outline Teks:", style = MaterialTheme.typography.bodySmall)
                                    ColorPickerRow(
                                        selectedColor = currentStyle.strokeColor,
                                        onColorSelected = { col -> onUpdateStyle(currentStyle.copy(strokeColor = col), true) },
                                        onCustomColorChange = { col -> onUpdateStyle(currentStyle.copy(strokeColor = col), false) },
                                        onPickStart = { onSliderDragStart() },
                                        onPickEnd = { onSliderDragEnd() },
                                        onEyedropperClick = {
                                            onStartEyedropper { sampledCol ->
                                                onUpdateStyle(currentStyle.copy(strokeColor = sampledCol), true)
                                            }
                                        }
                                    )

                                    Text("Ketebalan Stroke: ${currentStyle.strokeWidth.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.strokeWidth,
                                        onValueChange = {
                                            onSliderDragStart()
                                            val strokeCol = if (it > 0f && currentStyle.strokeColor == AndroidColor.TRANSPARENT) AndroidColor.BLACK else currentStyle.strokeColor
                                            onUpdateStyle(currentStyle.copy(strokeWidth = it, strokeColor = strokeCol), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..20f
                                    )

                                    Text("Opacity Stroke: ${(currentStyle.strokeOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.strokeOpacity,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateStyle(currentStyle.copy(strokeOpacity = it), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..1f
                                    )
                                }
                            }
                            EffectType.DROP_SHADOW -> {
                                if (selectedLayer is Layer.TextLayer) {
                                    val currentStyle = selectedLayer.style

                                    Text("Warna Bayangan:", style = MaterialTheme.typography.bodySmall)
                                    ColorPickerRow(
                                        selectedColor = currentStyle.shadowColor,
                                        onColorSelected = { col ->
                                            onUpdateStyle(currentStyle.copy(shadowColor = col), true)
                                        },
                                        onCustomColorChange = { col ->
                                            onUpdateStyle(currentStyle.copy(shadowColor = col), false)
                                        },
                                        onPickStart = { onSliderDragStart() },
                                        onPickEnd = { onSliderDragEnd() },
                                        onEyedropperClick = {
                                            onStartEyedropper { sampledCol ->
                                                onUpdateStyle(currentStyle.copy(shadowColor = sampledCol), true)
                                            }
                                        }
                                    )

                                    Text("Blur Radius: ${currentStyle.shadowRadius.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.shadowRadius,
                                        onValueChange = {
                                            onSliderDragStart()
                                            val shadowCol = if (it > 0f && currentStyle.shadowColor == AndroidColor.TRANSPARENT) AndroidColor.BLACK else currentStyle.shadowColor
                                            onUpdateStyle(currentStyle.copy(shadowRadius = it, shadowColor = shadowCol), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..30f
                                    )

                                    Text("Offset X: ${currentStyle.shadowDx.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.shadowDx,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateStyle(currentStyle.copy(shadowDx = it), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = -30f..30f
                                    )

                                    Text("Offset Y: ${currentStyle.shadowDy.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.shadowDy,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateStyle(currentStyle.copy(shadowDy = it), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = -30f..30f
                                    )
                                }
                            }
                            EffectType.MOTION_BLUR -> {
                                if (selectedLayer is Layer.TextLayer) {
                                    val currentStyle = selectedLayer.style

                                    Text("Kekuatan: ${currentStyle.motionBlurRadius.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.motionBlurRadius,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateStyle(currentStyle.copy(motionBlurRadius = it), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..40f
                                    )

                                    Text("Arah: ${currentStyle.motionBlurAngle.toInt()}°", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.motionBlurAngle,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateStyle(currentStyle.copy(motionBlurAngle = it), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..360f
                                    )
                                    Text(
                                        text = "0° horizontal, 90° vertikal — sama seperti sudut gradient.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            EffectType.GLOW -> {
                                if (selectedLayer is Layer.TextLayer) {
                                    val currentStyle = selectedLayer.style

                                    Text("Warna Glow:", style = MaterialTheme.typography.bodySmall)
                                    ColorPickerRow(
                                        selectedColor = currentStyle.glowColor,
                                        onColorSelected = { col ->
                                            onUpdateStyle(currentStyle.copy(glowColor = col), true)
                                        },
                                        onCustomColorChange = { col ->
                                            onUpdateStyle(currentStyle.copy(glowColor = col), false)
                                        },
                                        onPickStart = { onSliderDragStart() },
                                        onPickEnd = { onSliderDragEnd() },
                                        onEyedropperClick = {
                                            onStartEyedropper { sampledCol ->
                                                onUpdateStyle(currentStyle.copy(glowColor = sampledCol), true)
                                            }
                                        }
                                    )

                                    Text("Radius: ${currentStyle.glowRadius.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = currentStyle.glowRadius,
                                        onValueChange = {
                                            onSliderDragStart()
                                            val glowCol = if (it > 0f && currentStyle.glowColor == AndroidColor.TRANSPARENT) AndroidColor.WHITE else currentStyle.glowColor
                                            onUpdateStyle(currentStyle.copy(glowRadius = it, glowColor = glowCol), false)
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..30f
                                    )
                                }
                            }
                            EffectType.IMAGE_TONE -> {
                                if (selectedLayer is Layer.ImageLayer && onUpdateImageLayer != null) {
                                    Text("Grayscale: ${(selectedLayer.grayscale * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = selectedLayer.grayscale,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateImageLayer(selectedLayer.copy(grayscale = it))
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..1f
                                    )

                                    Text("Brightness: ${(selectedLayer.brightness * 100).toInt()}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = selectedLayer.brightness,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateImageLayer(selectedLayer.copy(brightness = it))
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = -1f..1f
                                    )

                                    Text("Contrast: ${(selectedLayer.contrast * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = selectedLayer.contrast,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateImageLayer(selectedLayer.copy(contrast = it))
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..2f
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            onSliderDragStart()
                                            onUpdateImageLayer(
                                                selectedLayer.copy(grayscale = 0f, brightness = 0f, contrast = 1f)
                                            )
                                            onSliderDragEnd()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Atur Ulang Tone")
                                    }
                                }
                            }
                            EffectType.IMAGE_MOTION_BLUR -> {
                                if (selectedLayer is Layer.ImageLayer && onUpdateImageLayer != null) {
                                    Text("Kekuatan: ${selectedLayer.motionBlurRadius.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = selectedLayer.motionBlurRadius,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateImageLayer(selectedLayer.copy(motionBlurRadius = it))
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..25f
                                    )

                                    Text("Arah: ${selectedLayer.motionBlurAngle.toInt()}°", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = selectedLayer.motionBlurAngle,
                                        onValueChange = {
                                            onSliderDragStart()
                                            onUpdateImageLayer(selectedLayer.copy(motionBlurAngle = it))
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..360f
                                    )
                                    Text(
                                        text = "Diproses di background; pratinjau muncul sesaat setelah slider dilepas.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            EffectType.IMAGE_GLOW -> {
                                if (selectedLayer is Layer.ImageLayer && onUpdateImageLayer != null) {
                                    Text("Warna Glow:", style = MaterialTheme.typography.bodySmall)
                                    ColorPickerRow(
                                        selectedColor = selectedLayer.glowColor,
                                        onColorSelected = { col ->
                                            onSliderDragStart()
                                            onUpdateImageLayer(selectedLayer.copy(glowColor = col))
                                            onSliderDragEnd()
                                        },
                                        onCustomColorChange = { col ->
                                            onUpdateImageLayer(selectedLayer.copy(glowColor = col))
                                        },
                                        onPickStart = { onSliderDragStart() },
                                        onPickEnd = { onSliderDragEnd() },
                                        onEyedropperClick = {
                                            onStartEyedropper { sampledCol ->
                                                onSliderDragStart()
                                                onUpdateImageLayer(selectedLayer.copy(glowColor = sampledCol))
                                                onSliderDragEnd()
                                            }
                                        }
                                    )

                                    Text("Radius: ${selectedLayer.glowRadius.toInt()} px", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = selectedLayer.glowRadius,
                                        onValueChange = {
                                            onSliderDragStart()
                                            val glowCol = if (it > 0f && selectedLayer.glowColor == AndroidColor.TRANSPARENT) AndroidColor.WHITE else selectedLayer.glowColor
                                            onUpdateImageLayer(selectedLayer.copy(glowRadius = it, glowColor = glowCol))
                                        },
                                        onValueChangeFinished = {
                                            onSliderDragEnd()
                                        },
                                        valueRange = 0f..30f
                                    )
                                    Text(
                                        text = "Diproses di background; pratinjau muncul sesaat setelah slider dilepas.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            null -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LayersToolPanel(
    layers: List<Layer>,
    selectedId: String?,
    onSelectLayer: (String?) -> Unit,
    onMoveLayer: (String, Int) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onLoadBaseImage: () -> Unit = {},
    onUpdateOpacity: ((Float, Boolean) -> Unit)? = null,
    onSliderDragStart: () -> Unit = {},
    onSliderDragEnd: () -> Unit = {}
) {
    val selectedLayer = layers.find { it.id == selectedId }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth().height(220.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedButton(
                onClick = onLoadBaseImage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ganti Gambar Latar (Base Image)")
            }

            if (selectedLayer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Opacity Layer: ${(selectedLayer.opacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = selectedLayer.opacity,
                        onValueChange = {
                            onSliderDragStart()
                            onUpdateOpacity?.invoke(it, false)
                        },
                        onValueChangeFinished = {
                            onSliderDragEnd()
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Display front-first (canvas draw order is index 0 = back, last =
                // front), so "Naik" (toward front) moves the row upward.
                items(layers.reversed(), key = { it.id }) { layer ->
                    val isSelected = layer.id == selectedId
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { onSelectLayer(layer.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onToggleVisibility(layer.id) }) {
                                    Icon(
                                        if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Visibility"
                                    )
                                }
                                Text(text = layer.name)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // List is front-first: Naik = toward front = +1 in draw order.
                                TextButton(onClick = { onMoveLayer(layer.id, 1) }) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Naik", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onMoveLayer(layer.id, -1) }) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Turun", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { onDeleteLayer(layer.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus Layer", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private fun getFileNameFromUri(context: android.content.Context, uri: android.net.Uri): String? {
    var fileName: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
    }
    return fileName ?: uri.lastPathSegment
}

@Composable
fun FontToolPanel(
    allFonts: List<FontItem>,
    selectedLayer: Layer.TextLayer?,
    defaultStyle: TextStyleConfig,
    onUpdateStyle: (TextStyleConfig, Boolean) -> Unit,
    onCapitalizationTransform: ((String) -> Unit)? = null,
    onImportCustomFont: () -> Unit,
    onSliderDragStart: () -> Unit = {},
    onSliderDragEnd: () -> Unit = {},
    favoriteKeys: Set<String> = emptySet(),
    onToggleFavorite: ((String) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    val currentStyle = selectedLayer?.style ?: defaultStyle

    val filteredFonts = remember(allFonts, searchQuery, showFavoritesOnly, favoriteKeys) {
        var list = if (searchQuery.isBlank()) {
            allFonts
        } else {
            allFonts.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        if (showFavoritesOnly) {
            list = list.filter { favoriteKeys.contains(it.fontNameKey) }
        }
        list
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pengaturan Font",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onImportCustomFont,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import Font")
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Cari font komik...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Pilih Font (${filteredFonts.size} tersedia):", style = MaterialTheme.typography.bodyMedium)
            if (onToggleFavorite != null && favoriteKeys.isNotEmpty()) {
                FilterChip(
                    selected = showFavoritesOnly,
                    onClick = { showFavoritesOnly = !showFavoritesOnly },
                    label = { Text("Favorit (${favoriteKeys.size})") },
                    leadingIcon = {
                        Icon(
                            if (showFavoritesOnly) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filteredFonts) { fontItem ->
                    val isSelected = currentStyle.fontName.equals(fontItem.name, ignoreCase = true)
                    val isFavorite = favoriteKeys.contains(fontItem.fontNameKey)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onUpdateStyle(currentStyle.copy(fontName = fontItem.name), true)
                        },
                        label = { Text(text = fontItem.name, maxLines = 1) },
                        leadingIcon = if (onToggleFavorite != null) {
                            {
                                IconButton(
                                    onClick = { onToggleFavorite.invoke(fontItem.fontNameKey) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = if (isFavorite) {
                                            "Hapus ${fontItem.name} dari favorit"
                                        } else {
                                            "Favoritkan ${fontItem.name}"
                                        },
                                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else null,
                        trailingIcon = if (fontItem.isCustom) {
                            {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        "Custom",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        } else null
                    )
                }
            }

            Text("Gaya Font:", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf("Regular", "Bold", "Italic", "BoldItalic").forEach { styleName ->
                    val isSelected = currentStyle.fontStyle.equals(styleName, ignoreCase = true)
                    val labelText = if (styleName == "BoldItalic") "Bold+Italic" else styleName
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onUpdateStyle(currentStyle.copy(fontStyle = styleName), true)
                        },
                        label = { Text(labelText) }
                    )
                }
            }

            Text("Ukuran Font: ${currentStyle.fontSize.toInt()} px", style = MaterialTheme.typography.bodyMedium)
            Slider(
                // Range must cover the canvas resize-handle clamp (10..300); a
                // narrower range crashes (M3 Slider requires value in range).
                value = currentStyle.fontSize.coerceIn(10f, 300f),
                onValueChange = {
                    onSliderDragStart()
                    onUpdateStyle(currentStyle.copy(fontSize = it), false)
                },
                onValueChangeFinished = {
                    onSliderDragEnd()
                },
                valueRange = 10f..300f,
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedLayer != null && onCapitalizationTransform != null) {
                Text("Kapitalisasi Teks:", style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { onCapitalizationTransform("uppercase") },
                        label = { Text("UPPERCASE") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onCapitalizationTransform("lowercase") },
                        label = { Text("lowercase") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onCapitalizationTransform("titlecase") },
                        label = { Text("Title Case") }
                    )
                }
            }
        }
    }
}

@Composable
fun StylePresetPanel(
    presets: List<com.mochits.app.model.TextStylePreset>,
    selectedLayer: Layer.TextLayer?,
    onApplyPreset: (com.mochits.app.model.TextStylePreset) -> Unit,
    onSavePreset: ((String) -> com.mochits.app.model.TextStylePreset?)? = null,
    onDeletePreset: ((String) -> Boolean)? = null,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: ((String) -> Boolean)? = null
) {
    var presetName by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Preset Style", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (selectedLayer != null)
                    "Ketuk preset untuk dipakai ke teks terpilih."
                else
                    "Tidak ada teks terpilih — preset dipakai sebagai style teks baru.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                label = { Text("Nama preset baru") },
                placeholder = { Text("cth. Judul Bab") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (presetName.isNotBlank()) {
                        onSavePreset?.invoke(presetName)
                        presetName = ""
                    }
                },
                enabled = presetName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (selectedLayer != null) "Simpan Style Teks Ini"
                    else "Simpan Style Default Ini"
                )
            }

            presets.forEach { preset ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onApplyPreset(preset) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${preset.fontName} • ${preset.fontStyle} • " +
                                    "${preset.alignment.name.lowercase().replaceFirstChar { it.titlecase() }} • " +
                                    (if (preset.shape == com.mochits.app.model.TextContainerShape.OVAL) "Oval" else "Kotak") +
                                    (if (preset.isBuiltIn) " • Bawaan" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onTogglePin != null) {
                            val pinned = pinnedIds.contains(preset.id)
                            IconButton(onClick = { onTogglePin.invoke(preset.id) }) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = if (pinned) "Lepas preset" else "Sematkan ke atas",
                                    tint = if (pinned) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (onDeletePreset != null) {
                            IconButton(onClick = { onDeletePreset.invoke(preset.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus preset")
                            }
                        } else if (preset.isBuiltIn) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}
