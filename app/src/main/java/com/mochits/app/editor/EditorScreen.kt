package com.mochits.app.editor

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
    val createdFile = docTree?.createFile(mimeType, "$saveName.$ext")
    if (createdFile?.uri != null) {
        val pfd = context.contentResolver.openFileDescriptor(createdFile.uri, "w")
        if (pfd != null) {
            val tempFile = File(context.cacheDir, "temp_export.$ext")
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
                if (success) {
                    try {
                        tempFile.inputStream().use { input ->
                            java.io.FileOutputStream(pfd.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        android.widget.Toast.makeText(context, "Berhasil diekspor ke folder output!", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    pfd.close()
                } else {
                    pfd.close()
                    android.widget.Toast.makeText(context, "Gagal meng-ekspor gambar.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    } else {
        android.widget.Toast.makeText(context, "Gagal membuat file di folder tujuan.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
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

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    val textRenderer = remember { TextRenderer(context) }
    var triggerRedraw by remember { mutableIntStateOf(0) }
    var isMaskPanelCollapsed by remember { mutableStateOf(false) }

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
        uri?.let { pickedUri ->
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



    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo Button
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = canUndo
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    // Redo Button
                    IconButton(
                        onClick = { viewModel.redo() },
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
                        onToggleCollapse = { isMaskPanelCollapsed = !isMaskPanelCollapsed },
                        onModeSelected = { viewModel.setMaskToolMode(it) },
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
                            triggerRedraw++
                        },
                        onInvert = {
                            viewModel.saveUndoSnapshot()
                            viewModel.maskSelectionTools?.invertMask()
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
                        onUpdateTextContent = { text -> viewModel.updateSelectedTextContent(text) },
                        onUpdateStyle = { style, saveUndo -> viewModel.updateSelectedTextLayerStyle(style, saveUndo = saveUndo) },
                        onUpdateContainerShape = { shape -> viewModel.updateSelectedTextLayerContainerShape(shape) },
                        onCapitalizationTransform = { newText -> viewModel.updateSelectedTextContent(newText) },
                        onSliderDragStart = { viewModel.onSliderDragStart() },
                        onSliderDragEnd = { viewModel.onSliderDragEnd() }
                    )
                    EditorPanel.EFFECT -> EffectToolPanel(
                        selectedLayer = layers.find { it.id == selectedLayerId },
                        onUpdateOpacity = { opacity, saveUndo -> viewModel.updateSelectedLayerOpacity(opacity, saveUndo = saveUndo) },
                        onUpdateStyle = { style, saveUndo -> viewModel.updateSelectedTextLayerStyle(style, saveUndo = saveUndo) },
                        onSliderDragStart = { viewModel.onSliderDragStart() },
                        onSliderDragEnd = { viewModel.onSliderDragEnd() },
                        onStartEyedropper = { onColorSelected -> viewModel.startEyedropper(onColorSelected) }
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

            // Selected text layer & handle bounds calculation
            val selectedTextLayer = layers.find { it.id == selectedLayerId } as? Layer.TextLayer
            val handleCanvasCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text, selectedTextLayer?.boxWidth, selectedTextLayer?.boxHeight) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer)
                    Offset(bounds.right, bounds.bottom)
                } else {
                    Offset.Zero
                }
            }

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

            var lastTapTimestamp by remember { mutableLongStateOf(0L) }
            var lastTapLayerId by remember { mutableStateOf<String?>(null) }

            // Handle positions in unrotated text bounds space
            val deleteHandleCanvasCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer)
                    Offset(bounds.left, bounds.top)
                } else Offset.Zero
            }

            val rotateHandleCanvasCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text, selectedTextLayer?.boxWidth, selectedTextLayer?.boxHeight) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer)
                    Offset(bounds.right, bounds.top)
                } else Offset.Zero
            }

            val stretchVBottomCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text, selectedTextLayer?.boxWidth, selectedTextLayer?.boxHeight) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer)
                    Offset(bounds.centerX(), bounds.bottom)
                } else Offset.Zero
            }

            val stretchHRightCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text, selectedTextLayer?.boxWidth, selectedTextLayer?.boxHeight) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer)
                    Offset(bounds.right, bounds.centerY())
                } else Offset.Zero
            }

            var panAccumulator by remember { mutableFloatStateOf(0f) }

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
            val linePaintCache = remember { AndroidPaint().apply { style = AndroidPaint.Style.STROKE; color = AndroidColor.parseColor("#3F51B5") } }
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
                    .pointerInput(isEyedropperActive, activePanel, selectedTextLayer, layers, handleCanvasCenter, deleteHandleCanvasCenter, rotateHandleCanvasCenter, stretchVBottomCenter, stretchHRightCenter) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.isEmpty()) continue

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
                                                if (isMagicWandPending && magicWandMovedDistance <= 15f && magicWandTouchStartPt != null) {
                                                    viewModel.saveUndoSnapshot()
                                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(magicWandTouchStartPt!!.x, magicWandTouchStartPt!!.y)
                                                    viewModel.maskSelectionTools?.magicWandSelect(
                                                        srcBitmap = baseBitmap,
                                                        point = canvasPt,
                                                        tolerance = magicWandTolerance,
                                                        expandPixels = magicWandExpand.toInt()
                                                    )
                                                    triggerRedraw++
                                                }
                                                isMagicWandPending = false
                                                magicWandTouchStartPt = null
                                                magicWandMovedDistance = 0f
                                            } else if (isMaskDrawingActive) {
                                                viewModel.maskSelectionTools?.endStroke(lastTouchCanvasPt, maskToolMode, brushSize)
                                                isMaskDrawingActive = false
                                                triggerRedraw++
                                            }
                                        }
                                    }
                                    continue
                                }

                                // 2. TEXT HANDLES INTERCEPTION: Check hit-testing on handles
                                val firstChange = changes.first()
                                val touchCanvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                val handleHitRadius = (36f / viewModel.canvasState.scale)
                                val stretchHitRadius = (28f / viewModel.canvasState.scale)

                                val isJustDown = !firstChange.previousPressed && firstChange.pressed
                                if (isJustDown) {
                                    panAccumulator = 0f
                                    var hitHandle = false
                                    if (selectedTextLayer != null) {
                                        val bounds = textRenderer.getTextBounds(selectedTextLayer)
                                        val textCenterX = bounds.centerX()
                                        val textCenterY = bounds.centerY()

                                        // Transform touch point to unrotated coordinate space around text center
                                        val unrotatedPt = if (selectedTextLayer.rotation != 0f) {
                                            val rad = Math.toRadians(-selectedTextLayer.rotation.toDouble())
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

                                        val distResizeSq = (unrotatedPt.x - handleCanvasCenter.x) * (unrotatedPt.x - handleCanvasCenter.x) +
                                                (unrotatedPt.y - handleCanvasCenter.y) * (unrotatedPt.y - handleCanvasCenter.y)
                                        val distDeleteSq = (unrotatedPt.x - deleteHandleCanvasCenter.x) * (unrotatedPt.x - deleteHandleCanvasCenter.x) +
                                                (unrotatedPt.y - deleteHandleCanvasCenter.y) * (unrotatedPt.y - deleteHandleCanvasCenter.y)
                                        val distRotateSq = (unrotatedPt.x - rotateHandleCanvasCenter.x) * (unrotatedPt.x - rotateHandleCanvasCenter.x) +
                                                (unrotatedPt.y - rotateHandleCanvasCenter.y) * (unrotatedPt.y - rotateHandleCanvasCenter.y)

                                        val distStretchVSq = (unrotatedPt.x - stretchVBottomCenter.x) * (unrotatedPt.x - stretchVBottomCenter.x) +
                                                (unrotatedPt.y - stretchVBottomCenter.y) * (unrotatedPt.y - stretchVBottomCenter.y)

                                        val distStretchHSq = (unrotatedPt.x - stretchHRightCenter.x) * (unrotatedPt.x - stretchHRightCenter.x) +
                                                (unrotatedPt.y - stretchHRightCenter.y) * (unrotatedPt.y - stretchHRightCenter.y)

                                        val rSq = handleHitRadius * handleHitRadius
                                        val stretchRSq = stretchHitRadius * stretchHitRadius

                                        if (distDeleteSq <= rSq) {
                                            activeHandleType = TextHandleType.DELETE
                                            viewModel.deleteLayer(selectedTextLayer.id)
                                            viewModel.selectLayer(null)
                                            activeHandleType = null
                                            hitHandle = true
                                            firstChange.consume()
                                            triggerRedraw++
                                            continue
                                        } else if (distRotateSq <= rSq) {
                                            activeHandleType = TextHandleType.ROTATE
                                            viewModel.saveUndoSnapshot()
                                            initialTextCenterX = textCenterX
                                            initialTextCenterY = textCenterY
                                            initialTouchAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - textCenterY).toDouble(), (touchCanvasPt.x - textCenterX).toDouble())).toFloat()
                                            initialTextRotation = selectedTextLayer.rotation
                                            hitHandle = true
                                            firstChange.consume()
                                            continue
                                        } else if (distResizeSq <= rSq) {
                                            activeHandleType = TextHandleType.RESIZE
                                            viewModel.saveUndoSnapshot()
                                            initialTextCenterX = textCenterX
                                            initialTextCenterY = textCenterY
                                            initialDragDist = kotlin.math.hypot(touchCanvasPt.x - textCenterX, touchCanvasPt.y - textCenterY)
                                            initialFontSize = selectedTextLayer.style.fontSize
                                            initialBoxW = selectedTextLayer.boxWidth ?: bounds.width()
                                            initialBoxH = selectedTextLayer.boxHeight ?: bounds.height()
                                            hitHandle = true
                                            firstChange.consume()
                                            continue
                                        } else if (distStretchVSq <= stretchRSq) {
                                            activeHandleType = TextHandleType.STRETCH_V
                                            viewModel.saveUndoSnapshot()
                                            initialTextX = selectedTextLayer.x
                                            initialTextY = selectedTextLayer.y
                                            initialTextCenterX = textCenterX
                                            initialTextCenterY = textCenterY
                                            initialBoxW = selectedTextLayer.boxWidth ?: bounds.width()
                                            initialBoxH = selectedTextLayer.boxHeight ?: bounds.height()
                                            hitHandle = true
                                            firstChange.consume()
                                            continue
                                        } else if (distStretchHSq <= stretchRSq) {
                                            activeHandleType = TextHandleType.STRETCH_H
                                            viewModel.saveUndoSnapshot()
                                            initialTextX = selectedTextLayer.x
                                            initialTextY = selectedTextLayer.y
                                            initialTextCenterX = textCenterX
                                            initialTextCenterY = textCenterY
                                            initialBoxW = selectedTextLayer.boxWidth ?: bounds.width()
                                            initialBoxH = selectedTextLayer.boxHeight ?: bounds.height()
                                            hitHandle = true
                                            firstChange.consume()
                                            continue
                                        } else if (isPointInsideTextLayer(selectedTextLayer, touchCanvasPt, textRenderer)) {
                                            activeHandleType = TextHandleType.BODY_MOVE
                                            viewModel.saveUndoSnapshot()
                                            initialTextX = selectedTextLayer.x
                                            initialTextY = selectedTextLayer.y
                                            initialTouchCanvasPt = touchCanvasPt
                                            hitHandle = true
                                            firstChange.consume()
                                            continue
                                        }
                                    }
                                    if (!hitHandle) {
                                        activeHandleType = null
                                    }
                                }

                                // Handle active drag on text handles
                                if (activeHandleType != null && firstChange.pressed) {
                                    firstChange.consume()
                                    when (activeHandleType) {
                                        TextHandleType.RESIZE -> {
                                            if (selectedTextLayer != null) {
                                                val currentDist = kotlin.math.hypot(touchCanvasPt.x - initialTextCenterX, touchCanvasPt.y - initialTextCenterY)
                                                if (initialDragDist > 0f) {
                                                    val scaleFactor = currentDist / initialDragDist
                                                    val newSize = (initialFontSize * scaleFactor).coerceIn(10f, 300f)
                                                    val newBoxW = if (selectedTextLayer.boxWidth != null || selectedTextLayer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                                                        (initialBoxW * scaleFactor).coerceAtLeast(30f)
                                                    } else null
                                                    val newBoxH = if (selectedTextLayer.boxHeight != null || selectedTextLayer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                                                        (initialBoxH * scaleFactor).coerceAtLeast(20f)
                                                    } else null

                                                    viewModel.updateSelectedTextLayerResize(
                                                        fontSize = newSize,
                                                        boxWidth = newBoxW,
                                                        boxHeight = newBoxH,
                                                        saveUndo = false
                                                    )
                                                    triggerRedraw++
                                                }
                                            }
                                        }
                                        TextHandleType.ROTATE -> {
                                            if (selectedTextLayer != null) {
                                                val currentAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - initialTextCenterY).toDouble(), (touchCanvasPt.x - initialTextCenterX).toDouble())).toFloat()
                                                val deltaAngle = currentAngle - initialTouchAngle
                                                val newRotation = (initialTextRotation + deltaAngle) % 360f
                                                viewModel.updateSelectedTextLayerRotation(newRotation, saveUndo = false)
                                                triggerRedraw++
                                            }
                                        }
                                        TextHandleType.STRETCH_V -> {
                                            if (selectedTextLayer != null) {
                                                val unrotatedPt = if (selectedTextLayer.rotation != 0f) {
                                                    val rad = Math.toRadians(-selectedTextLayer.rotation.toDouble())
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
                                                val currentBoxW = selectedTextLayer.boxWidth
                                                val newBoxH = (unrotatedPt.y - initialTextY).coerceAtLeast(20f)
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
                                            if (selectedTextLayer != null) {
                                                val unrotatedPt = if (selectedTextLayer.rotation != 0f) {
                                                    val rad = Math.toRadians(-selectedTextLayer.rotation.toDouble())
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
                                                val currentBoxH = selectedTextLayer.boxHeight
                                                val newBoxW = (unrotatedPt.x - initialTextX).coerceAtLeast(30f)
                                                viewModel.updateSelectedTextLayerStretch(
                                                    boxWidth = newBoxW,
                                                    boxHeight = currentBoxH,
                                                    newX = initialTextX,
                                                    newY = initialTextY,
                                                    saveUndo = false
                                                )
                                                triggerRedraw++
                                            }
                                        }
                                        TextHandleType.BODY_MOVE -> {
                                            if (selectedTextLayer != null) {
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
                                    if (activeHandleType != null) {
                                        viewModel.finalizeTextTransform()
                                        activeHandleType = null
                                        continue
                                    }
                                }

                                // 3. CANVAS PAN/ZOOM & TAP-SELECT / TAP-DESELECT
                                if (activeHandleType == null) {
                                    val pressedList = changes.filter { it.pressed }
                                    if (pressedList.size >= 2) {
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
                                        viewModel.canvasState.onGestureTransform(center, panDelta, zoomFactor)
                                        triggerRedraw++
                                        pressedList.forEach { it.consume() }
                                    } else if (pressedList.size == 1) {
                                        val c = pressedList[0]
                                        val panDelta = c.position - c.previousPosition
                                        val moved = panDelta.getDistance() > 1.5f
                                        if (moved) {
                                            panAccumulator += panDelta.getDistance()
                                            viewModel.canvasState.onGestureTransform(c.position, panDelta, 1f)
                                            triggerRedraw++
                                            c.consume()
                                        }
                                    } else {
                                        // Finger released
                                        val releasedChange = changes.find { it.previousPressed && !it.pressed }
                                        if (releasedChange != null) {
                                            if (panAccumulator <= 10f) {
                                                val releaseCanvasPt = viewModel.canvasState.mapper.screenToCanvas(releasedChange.position.x, releasedChange.position.y)
                                                val hitTextLayer = layers.reversed().filterIsInstance<Layer.TextLayer>().firstOrNull { layer ->
                                                    layer.isVisible && isPointInsideTextLayer(layer, releaseCanvasPt, textRenderer)
                                                }
                                                if (hitTextLayer != null) {
                                                    val now = System.currentTimeMillis()
                                                    val isDoubleTap = (lastTapLayerId == hitTextLayer.id) && (now - lastTapTimestamp < 400L)
                                                    viewModel.selectLayer(hitTextLayer.id)
                                                    if (isDoubleTap) {
                                                        viewModel.setActivePanel(EditorPanel.TEXT)
                                                        lastTapTimestamp = 0L
                                                        lastTapLayerId = null
                                                    } else {
                                                        lastTapTimestamp = now
                                                        lastTapLayerId = hitTextLayer.id
                                                    }
                                                    triggerRedraw++
                                                } else {
                                                    viewModel.selectLayer(null)
                                                    lastTapTimestamp = 0L
                                                    lastTapLayerId = null
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
                val canvasWidth = size.width
                val canvasHeight = size.height

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

                                    // Render bounding box & controls (Resize, Rotate, Delete) if selected
                                    if (layer.id == selectedLayerId) {
                                        val currentScale = viewModel.canvasState.scale
                                        val strokeW = 3f / currentScale
                                        val handleRadius = 14f / currentScale

                                        val boxPaint = boxPaintCache.apply {
                                            strokeWidth = strokeW
                                            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        }
                                        if (layer.textContainerShape == com.mochits.app.model.TextContainerShape.OVAL) {
                                            drawContext.canvas.nativeCanvas.drawOval(bounds, boxPaint)
                                        } else {
                                            drawContext.canvas.nativeCanvas.drawRect(bounds, boxPaint)
                                        }

                                        val handleFillPaint = handleFillPaintCache
                                        val handleStrokePaint = handleStrokePaintCache.apply { strokeWidth = strokeW }

                                        // 1. Bottom-Right Resize Handle (Diagonal Double-Arrow Icon ↗↙)
                                        val resizeFillPaint = resizeFillPaintCache
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.bottom, handleRadius, resizeFillPaint)
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.bottom, handleRadius, handleStrokePaint)

                                        val resizeIconPaint = resizeIconPaintCache.apply { strokeWidth = 2.5f / currentScale }
                                        val diagOff = handleRadius * 0.45f
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.right - diagOff, bounds.bottom + diagOff,
                                            bounds.right + diagOff, bounds.bottom - diagOff,
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.right + diagOff, bounds.bottom - diagOff,
                                            bounds.right + diagOff - (diagOff * 0.6f), bounds.bottom - diagOff,
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.right + diagOff, bounds.bottom - diagOff,
                                            bounds.right + diagOff, bounds.bottom - diagOff + (diagOff * 0.6f),
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.right - diagOff, bounds.bottom + diagOff,
                                            bounds.right - diagOff + (diagOff * 0.6f), bounds.bottom + diagOff,
                                            resizeIconPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.right - diagOff, bounds.bottom + diagOff,
                                            bounds.right - diagOff, bounds.bottom + diagOff - (diagOff * 0.6f),
                                            resizeIconPaint
                                        )

                                        // 2. Top-Left Delete (X) Button (Red Circle + White "X" Icon)
                                        val deleteFillPaint = deleteFillPaintCache
                                        val xPaint = xPaintCache.apply { strokeWidth = 3f / currentScale }
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.left, bounds.top, handleRadius, deleteFillPaint)
                                        val crossOffset = handleRadius * 0.45f
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.left - crossOffset, bounds.top - crossOffset,
                                            bounds.left + crossOffset, bounds.top + crossOffset, xPaint
                                        )
                                        drawContext.canvas.nativeCanvas.drawLine(
                                            bounds.left + crossOffset, bounds.top - crossOffset,
                                            bounds.left - crossOffset, bounds.top + crossOffset, xPaint
                                        )

                                        // 3. Top-Right Rotate Handle (Green Circle + Circular Arrow Icon)
                                        val rotateFillPaint = rotateFillPaintCache
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.top, handleRadius, rotateFillPaint)
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.top, handleRadius, handleStrokePaint)

                                        val rotateArcPaint = rotateArcPaintCache.apply { strokeWidth = 2.5f / currentScale }
                                        val arcR = handleRadius * 0.5f
                                        arcRectCache.set(
                                            bounds.right - arcR, bounds.top - arcR,
                                            bounds.right + arcR, bounds.top + arcR
                                        )
                                        drawContext.canvas.nativeCanvas.drawArc(arcRectCache, 45f, 270f, false, rotateArcPaint)
                                        val tipX = bounds.right + arcR * kotlin.math.cos(Math.toRadians(45.0)).toFloat()
                                        val tipY = bounds.top + arcR * kotlin.math.sin(Math.toRadians(45.0)).toFloat()
                                        rotateArrowPathCache.reset()
                                        rotateArrowPathCache.moveTo(tipX, tipY)
                                        rotateArrowPathCache.lineTo(tipX + 4f / currentScale, tipY - 5f / currentScale)
                                        rotateArrowPathCache.lineTo(tipX + 5f / currentScale, tipY + 4f / currentScale)
                                        rotateArrowPathCache.close()
                                        val rotateArrowPaint = rotateArrowPaintCache
                                        drawContext.canvas.nativeCanvas.drawPath(rotateArrowPathCache, rotateArrowPaint)

                                        // 4. Vertical Stretch Handle (Bottom Center - Pill + ↕ Arrow Icon)
                                        val pillW = handleRadius * 1.6f
                                        val pillH = handleRadius * 0.9f
                                        val vArrowPaint = vArrowPaintCache.apply { strokeWidth = 2f / currentScale }

                                        fun drawVStretchHandle(cx: Float, cy: Float) {
                                            pillRectCache.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)
                                            drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleFillPaint)
                                            drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleStrokePaint)

                                            val arrowLen = pillH * 0.35f
                                            drawContext.canvas.nativeCanvas.drawLine(cx, cy - arrowLen, cx, cy + arrowLen, vArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx, cy - arrowLen, cx - 3f / currentScale, cy - arrowLen + 3f / currentScale, vArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx, cy - arrowLen, cx + 3f / currentScale, cy - arrowLen + 3f / currentScale, vArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx, cy + arrowLen, cx - 3f / currentScale, cy + arrowLen - 3f / currentScale, vArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx, cy + arrowLen, cx + 3f / currentScale, cy + arrowLen - 3f / currentScale, vArrowPaint)
                                        }

                                        drawVStretchHandle(bounds.centerX(), bounds.bottom)

                                        // 5. Horizontal Stretch Handle (Right Center - Pill + ↔ Arrow Icon)
                                        val pillHW = handleRadius * 0.9f
                                        val pillHH = handleRadius * 1.6f
                                        val hArrowPaint = hArrowPaintCache.apply { strokeWidth = 2f / currentScale }

                                        fun drawHStretchHandle(cx: Float, cy: Float) {
                                            pillRectCache.set(cx - pillHW / 2f, cy - pillHH / 2f, cx + pillHW / 2f, cy + pillHH / 2f)
                                            drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleFillPaint)
                                            drawContext.canvas.nativeCanvas.drawRoundRect(pillRectCache, 6f, 6f, handleStrokePaint)

                                            val arrowLen = pillHW * 0.35f
                                            drawContext.canvas.nativeCanvas.drawLine(cx - arrowLen, cy, cx + arrowLen, cy, hArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx - arrowLen, cy, cx - arrowLen + 3f / currentScale, cy - 3f / currentScale, hArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx - arrowLen, cy, cx - arrowLen + 3f / currentScale, cy + 3f / currentScale, hArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx + arrowLen, cy, cx + arrowLen - 3f / currentScale, cy - 3f / currentScale, hArrowPaint)
                                            drawContext.canvas.nativeCanvas.drawLine(cx + arrowLen, cy, cx + arrowLen - 3f / currentScale, cy + 3f / currentScale, hArrowPaint)
                                        }

                                        drawHStretchHandle(bounds.right, bounds.centerY())
                                    }

                                    drawContext.canvas.nativeCanvas.restore()
                                    drawContext.canvas.nativeCanvas.restoreToCount(count)
                                }
                                is Layer.ImageLayer -> {
                                    layer.bitmap?.let { imgBmp ->
                                        if (!imgBmp.isRecycled) {
                                            val imgPaint = alphaPaintCache.apply {
                                                alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                                            }
                                            drawContext.canvas.nativeCanvas.drawBitmap(imgBmp, layer.x, layer.y, imgPaint)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                drawContext.canvas.nativeCanvas.restore()
            }

            // Floating Confirm/Cancel Action Bar for Eyedropper (Pill Container)
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
    NavigationBar {
        NavigationBarItem(
            selected = activePanel == EditorPanel.ERASE || activePanel == EditorPanel.MASK || activePanel == EditorPanel.INPAINT,
            onClick = { onPanelSelect(EditorPanel.ERASE) },
            icon = { Icon(Icons.Default.CleaningServices, contentDescription = "Erase") },
            label = { Text("Erase") }
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.TEXT,
            onClick = { onPanelSelect(EditorPanel.TEXT) },
            icon = { Icon(Icons.Default.TextFields, contentDescription = "Text") },
            label = { Text("Text") }
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.EFFECT,
            onClick = { onPanelSelect(EditorPanel.EFFECT) },
            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Effect") },
            label = { Text("Effect") }
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.LAYERS,
            onClick = { onPanelSelect(EditorPanel.LAYERS) },
            icon = { Icon(Icons.Default.Layers, contentDescription = "Layers") },
            label = { Text("Layers") }
        )
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

                Text("Alat Seleksi Area:", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedModel == EditorViewModel.InpaintModel.TELEA,
                        onClick = { onModelSelected(EditorViewModel.InpaintModel.TELEA) },
                        label = { Text("Telea (OpenCV)") }
                    )
                    FilterChip(
                        selected = selectedModel == EditorViewModel.InpaintModel.LAMA,
                        onClick = { onModelSelected(EditorViewModel.InpaintModel.LAMA) },
                        label = { Text("LaMa (TFLite AI)") }
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClear) { Text("Clear Mask") }
                        OutlinedButton(onClick = onInvert) { Text("Invert") }
                    }

                    Button(
                        onClick = onRunErase,
                        enabled = !isProcessing && !isDownloading
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
                }

                Spacer(modifier = Modifier.height(6.dp))
                if (mode == MaskToolMode.MAGIC_WAND) {
                    Text("Toleransi Warna (Tolerance): ${magicWandTolerance.toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = magicWandTolerance,
                        onValueChange = onToleranceChange,
                        valueRange = 0f..100f
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    var localExpandValue by remember(magicWandExpand) { mutableFloatStateOf(magicWandExpand) }

                    DisposableEffect(Unit) {
                        onDispose {
                            if (localExpandValue != magicWandExpand) {
                                onExpandChange?.invoke(localExpandValue)
                            }
                        }
                    }

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
    viewModel: EditorViewModel
) {
    val selectedLayer = viewModel.selectedLayer
    val textLayer = selectedLayer as? Layer.TextLayer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (textLayer != null) "Edit Teks" else "Tambah Teks Baru",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    if (textLayer == null) {
                        viewModel.addTextLayer("Teks Baru")
                    }
                }
            ) {
                Text(if (textLayer == null) "Tambah Teks" else "Selesai")
            }
        }

        OutlinedTextField(
            value = textLayer?.text ?: "",
            onValueChange = { newText ->
                if (textLayer != null) {
                    viewModel.updateSelectedText(newText)
                }
            },
            label = { Text("Teks") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Alignment", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = (textLayer?.style?.alignment ?: viewModel.defaultTextStyle.alignment) == TextAlignment.LEFT,
                    onClick = { viewModel.updateSelectedTextStyle({ it.copy(alignment = TextAlignment.LEFT) }, saveUndo = true) },
                    label = { Text("Kiri") }
                )
                FilterChip(
                    selected = (textLayer?.style?.alignment ?: viewModel.defaultTextStyle.alignment) == TextAlignment.CENTER,
                    onClick = { viewModel.updateSelectedTextStyle({ it.copy(alignment = TextAlignment.CENTER) }, saveUndo = true) },
                    label = { Text("Tengah") }
                )
                FilterChip(
                    selected = (textLayer?.style?.alignment ?: viewModel.defaultTextStyle.alignment) == TextAlignment.RIGHT,
                    onClick = { viewModel.updateSelectedTextStyle({ it.copy(alignment = TextAlignment.RIGHT) }, saveUndo = true) },
                    label = { Text("Kanan") }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bentuk Kontainer", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = (textLayer?.textContainerShape ?: TextContainerShape.BOX) == TextContainerShape.BOX,
                    onClick = { viewModel.updateSelectedTextContainerShape(TextContainerShape.BOX) },
                    label = { Text("Kotak") }
                )
                FilterChip(
                    selected = textLayer?.textContainerShape == TextContainerShape.OVAL,
                    onClick = { viewModel.updateSelectedTextContainerShape(TextContainerShape.OVAL) },
                    label = { Text("Oval") }
                )
            }
        }
    }
}

@Composable
fun FontToolPanel(
    viewModel: EditorViewModel,
    fontRepository: FontRepository,
    onImportFontClick: () -> Unit
) {
    val selectedLayer = viewModel.selectedLayer
    val textLayer = selectedLayer as? Layer.TextLayer
    val currentStyle = textLayer?.style ?: viewModel.defaultTextStyle

    val allFonts by fontRepository.getAllFontsFlow().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }

    val filteredFonts = remember(allFonts, searchQuery) {
        if (searchQuery.isBlank()) {
            allFonts
        } else {
            allFonts.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    var sliderFontSize by remember(currentStyle.fontSize) { mutableFloatStateOf(currentStyle.fontSize) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Button(onClick = onImportFontClick) {
                Text("Import Font")
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Cari Font") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filteredFonts) { fontItem ->
                val isSelected = currentStyle.fontName.equals(fontItem.name, ignoreCase = true) ||
                        currentStyle.fontName.equals(fontItem.fontNameKey, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        viewModel.saveUndoSnapshot()
                        viewModel.updateSelectedTextStyle({ it.copy(fontName = fontItem.name) }, saveUndo = false)
                    },
                    label = { Text(fontItem.name) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Style Font", style = MaterialTheme.typography.bodyMedium)
            val styles = listOf("Regular", "Bold", "Italic", "BoldItalic")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(styles) { styleName ->
                    FilterChip(
                        selected = currentStyle.fontStyle.equals(styleName, ignoreCase = true),
                        onClick = {
                            viewModel.saveUndoSnapshot()
                            viewModel.updateSelectedTextStyle({ it.copy(fontStyle = styleName) }, saveUndo = false)
                        },
                        label = { Text(styleName) }
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Ukuran Font", style = MaterialTheme.typography.bodyMedium)
                Text("${sliderFontSize.toInt()} pt", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = sliderFontSize,
                onValueChange = { newValue ->
                    sliderFontSize = newValue
                    viewModel.updateSelectedTextStyle({ it.copy(fontSize = newValue) }, saveUndo = false)
                },
                onValueChangeFinished = {
                    viewModel.saveUndoSnapshot()
                },
                valueRange = 12f..120f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Kapitalisasi", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (textLayer != null) {
                        val currentText = textLayer.text
                        val newText = when {
                            currentText == currentText.uppercase() -> currentText.lowercase()
                            currentText == currentText.lowercase() -> currentText.split(" ")
                                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                            else -> currentText.uppercase()
                        }
                        viewModel.updateSelectedText(newText)
                    }
                },
                enabled = textLayer != null
            ) {
                Text("Ubah Kapitalisasi")
            }
        }
    }
}

fun EffectToolPanel(
    selectedLayer: Layer?,
    onUpdateOpacity: (Float, Boolean) -> Unit,
    onUpdateStyle: (TextStyleConfig, Boolean) -> Unit,
    onSliderDragStart: () -> Unit = {},
    onSliderDragEnd: () -> Unit = {},
    onStartEyedropper: ((Int) -> Unit) -> Unit = {}
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
                        listOf(EffectType.OPACITY, EffectType.TEXT_COLOR, EffectType.STROKE, EffectType.DROP_SHADOW)
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
                        }

                        val (icon, title) = when (effect) {
                            EffectType.OPACITY -> Icons.Default.Opacity to "Opacity"
                            EffectType.TEXT_COLOR -> Icons.Default.Palette to "Warna Teks"
                            EffectType.STROKE -> Icons.Default.FormatPaint to "Stroke"
                            EffectType.DROP_SHADOW -> Icons.Default.WbSunny to "Drop Shadow"
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
                items(layers) { layer ->
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
                                TextButton(onClick = { onMoveLayer(layer.id, -1) }) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Naik", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onMoveLayer(layer.id, 1) }) {
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
}    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                var rawFileName: String? = null
                cursor?.use {
                    if (it.moveToFirst() && nameIndex != null && nameIndex >= 0) {
                        rawFileName = it.getString(nameIndex)
                    }
                }
                val result = fontRepository.importCustomFont(uri, rawFileName)
                if (result.isSuccess) {
                    val item = result.getOrNull()
                    if (item != null) {
                        viewModel.updateSelectedTextStyle({ it.copy(fontName = item.name) }, saveUndo = true)
                    }
                }
            }
        }
    }
