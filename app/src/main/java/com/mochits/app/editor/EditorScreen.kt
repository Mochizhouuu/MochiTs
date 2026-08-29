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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import java.io.File

private enum class TextHandleType {
    RESIZE, ROTATE, DELETE, BODY_MOVE
}

private fun isPointInsideTextLayer(
    layer: Layer.TextLayer,
    touchCanvasPt: Offset,
    textRenderer: TextRenderer
): Boolean {
    val bounds = textRenderer.getTextBounds(layer.text, layer.style, layer.x, layer.y)
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
    val isLoadingImage by viewModel.isLoadingImage.collectAsState()

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
                        onUpdateStyle = { style -> viewModel.updateSelectedTextLayerStyle(style) },
                        onCapitalizationTransform = { newText -> viewModel.updateSelectedTextContent(newText) }
                    )
                    EditorPanel.EFFECT -> EffectToolPanel(
                        selectedLayer = layers.find { it.id == selectedLayerId },
                        onUpdateOpacity = { opacity -> viewModel.updateSelectedLayerOpacity(opacity) },
                        onUpdateStyle = { style -> viewModel.updateSelectedTextLayerStyle(style) }
                    )
                    EditorPanel.LAYERS -> LayersToolPanel(
                        layers = layers,
                        selectedId = selectedLayerId,
                        onSelectLayer = { viewModel.selectLayer(it) },
                        onMoveLayer = { id, dir -> viewModel.moveLayer(id, dir) },
                        onToggleVisibility = { viewModel.toggleLayerVisibility(it) },
                        onDeleteLayer = { viewModel.deleteLayer(it) },
                        onLoadBaseImage = { baseImagePickerLauncher.launch("image/*") },
                        onUpdateOpacity = { opacity -> viewModel.updateSelectedLayerOpacity(opacity) }
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
            val handleCanvasCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(
                        selectedTextLayer.text,
                        selectedTextLayer.style,
                        selectedTextLayer.x,
                        selectedTextLayer.y
                    )
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
            var initialTouchCanvasPt by remember { mutableStateOf(Offset.Zero) }

            // Handle positions in canvas coordinates
            val deleteHandleCanvasCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer.text, selectedTextLayer.style, selectedTextLayer.x, selectedTextLayer.y)
                    Offset(bounds.left, bounds.top)
                } else Offset.Zero
            }

            val rotateHandleCanvasCenter = remember(selectedTextLayer, selectedTextLayer?.x, selectedTextLayer?.y, selectedTextLayer?.style?.fontSize, selectedTextLayer?.text) {
                if (selectedTextLayer != null) {
                    val bounds = textRenderer.getTextBounds(selectedTextLayer.text, selectedTextLayer.style, selectedTextLayer.x, selectedTextLayer.y)
                    val offsetDist = 36f / viewModel.canvasState.scale
                    Offset(bounds.centerX(), bounds.top - offsetDist)
                } else Offset.Zero
            }

            var panAccumulator by remember { mutableFloatStateOf(0f) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activePanel, selectedTextLayer, layers, handleCanvasCenter, deleteHandleCanvasCenter, rotateHandleCanvasCenter) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.isEmpty()) continue

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
                                val handleHitRadius = (40f / viewModel.canvasState.scale)

                                val isJustDown = !firstChange.previousPressed && firstChange.pressed
                                if (isJustDown) {
                                    panAccumulator = 0f
                                    var hitHandle = false
                                    if (selectedTextLayer != null) {
                                        val distResizeSq = (touchCanvasPt.x - handleCanvasCenter.x) * (touchCanvasPt.x - handleCanvasCenter.x) +
                                                (touchCanvasPt.y - handleCanvasCenter.y) * (touchCanvasPt.y - handleCanvasCenter.y)
                                        val distDeleteSq = (touchCanvasPt.x - deleteHandleCanvasCenter.x) * (touchCanvasPt.x - deleteHandleCanvasCenter.x) +
                                                (touchCanvasPt.y - deleteHandleCanvasCenter.y) * (touchCanvasPt.y - deleteHandleCanvasCenter.y)
                                        val distRotateSq = (touchCanvasPt.x - rotateHandleCanvasCenter.x) * (touchCanvasPt.x - rotateHandleCanvasCenter.x) +
                                                (touchCanvasPt.y - rotateHandleCanvasCenter.y) * (touchCanvasPt.y - rotateHandleCanvasCenter.y)

                                        val rSq = handleHitRadius * handleHitRadius

                                        if (distDeleteSq <= rSq) {
                                            activeHandleType = TextHandleType.DELETE
                                            viewModel.deleteLayer(selectedTextLayer.id)
                                            hitHandle = true
                                            firstChange.consume()
                                            triggerRedraw++
                                            continue
                                        } else if (distResizeSq <= rSq) {
                                            activeHandleType = TextHandleType.RESIZE
                                            viewModel.saveUndoSnapshot()
                                            val textCenterX = selectedTextLayer.x
                                            val textCenterY = selectedTextLayer.y
                                            initialDragDist = kotlin.math.hypot(touchCanvasPt.x - textCenterX, touchCanvasPt.y - textCenterY)
                                            initialFontSize = selectedTextLayer.style.fontSize
                                            hitHandle = true
                                            firstChange.consume()
                                            continue
                                        } else if (distRotateSq <= rSq) {
                                            activeHandleType = TextHandleType.ROTATE
                                            viewModel.saveUndoSnapshot()
                                            val bounds = textRenderer.getTextBounds(selectedTextLayer.text, selectedTextLayer.style, selectedTextLayer.x, selectedTextLayer.y)
                                            val textCenterX = bounds.centerX()
                                            val textCenterY = bounds.centerY()
                                            initialTouchAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - textCenterY).toDouble(), (touchCanvasPt.x - textCenterX).toDouble())).toFloat()
                                            initialTextRotation = selectedTextLayer.rotation
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
                                                val textCenterX = selectedTextLayer.x
                                                val textCenterY = selectedTextLayer.y
                                                val currentDist = kotlin.math.hypot(touchCanvasPt.x - textCenterX, touchCanvasPt.y - textCenterY)
                                                if (initialDragDist > 0f) {
                                                    val scaleFactor = currentDist / initialDragDist
                                                    val newSize = (initialFontSize * scaleFactor).coerceIn(10f, 300f)
                                                    viewModel.updateSelectedTextLayerStyle(
                                                        selectedTextLayer.style.copy(fontSize = newSize),
                                                        saveUndo = false
                                                    )
                                                    triggerRedraw++
                                                }
                                            }
                                        }
                                        TextHandleType.ROTATE -> {
                                            if (selectedTextLayer != null) {
                                                val bounds = textRenderer.getTextBounds(selectedTextLayer.text, selectedTextLayer.style, selectedTextLayer.x, selectedTextLayer.y)
                                                val textCenterX = bounds.centerX()
                                                val textCenterY = bounds.centerY()
                                                val currentAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - textCenterY).toDouble(), (touchCanvasPt.x - textCenterX).toDouble())).toFloat()
                                                val deltaAngle = currentAngle - initialTouchAngle
                                                val newRotation = (initialTextRotation + deltaAngle) % 360f
                                                viewModel.updateSelectedTextLayerRotation(newRotation, saveUndo = false)
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

                                if (firstChange.previousPressed && !firstChange.pressed) {
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
                                                    viewModel.selectLayer(hitTextLayer.id)
                                                    triggerRedraw++
                                                } else {
                                                    viewModel.selectLayer(null)
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

                        val outlinePaint = AndroidPaint().apply {
                            style = AndroidPaint.Style.STROKE
                            strokeWidth = strokeW
                            color = AndroidColor.RED
                            isAntiAlias = true
                        }

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
                            val maskPaint = AndroidPaint().apply {
                                colorFilter = android.graphics.PorterDuffColorFilter(
                                    AndroidColor.argb(64, 255, 0, 0), // Translucent Red ~25%
                                    android.graphics.PorterDuff.Mode.SRC_IN
                                )
                            }
                            drawContext.canvas.nativeCanvas.drawBitmap(maskBmp, 0f, 0f, maskPaint)
                        }
                    }

                    // 2b. Real-time Lasso visual path line overlay
                    viewModel.maskSelectionTools?.currentLassoPoints?.let { pts ->
                        if (pts.size >= 2) {
                            val currentScale = viewModel.canvasState.scale.coerceAtLeast(0.1f)
                            val lassoPaint = AndroidPaint().apply {
                                style = AndroidPaint.Style.STROKE
                                strokeWidth = 4f / currentScale
                                color = AndroidColor.RED
                                isAntiAlias = true
                                pathEffect = DashPathEffect(floatArrayOf(8f / currentScale, 8f / currentScale), 0f)
                            }
                            val path = android.graphics.Path()
                            path.moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) {
                                path.lineTo(pts[i].x, pts[i].y)
                            }
                            drawContext.canvas.nativeCanvas.drawPath(path, lassoPaint)
                        }
                    }

                    // 3. Render Layers
                    layers.forEach { layer ->
                        if (layer.isVisible) {
                            when (layer) {
                                is Layer.TextLayer -> {
                                    val alphaPaint = AndroidPaint().apply {
                                        alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                                    }
                                    val count = drawContext.canvas.nativeCanvas.saveLayer(null, alphaPaint)

                                    val bounds: RectF = textRenderer.getTextBounds(layer.text, layer.style, layer.x, layer.y)
                                    val textCenterX = bounds.centerX()
                                    val textCenterY = bounds.centerY()

                                    drawContext.canvas.nativeCanvas.save()
                                    if (layer.rotation != 0f) {
                                        drawContext.canvas.nativeCanvas.rotate(layer.rotation, textCenterX, textCenterY)
                                    }

                                    textRenderer.drawStyledText(
                                        canvas = drawContext.canvas.nativeCanvas,
                                        text = layer.text,
                                        style = layer.style,
                                        x = layer.x,
                                        y = layer.y
                                    )

                                    // Render bounding box & controls (Resize, Rotate, Delete) if selected
                                    if (layer.id == selectedLayerId) {
                                        val currentScale = viewModel.canvasState.scale
                                        val strokeW = 3f / currentScale
                                        val handleRadius = 14f / currentScale

                                        val boxPaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.STROKE
                                            strokeWidth = strokeW
                                            color = AndroidColor.parseColor("#3F51B5")
                                            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        }
                                        drawContext.canvas.nativeCanvas.drawRect(bounds, boxPaint)

                                        val handleFillPaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.FILL
                                            color = AndroidColor.WHITE
                                        }
                                        val handleStrokePaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.STROKE
                                            strokeWidth = strokeW
                                            color = AndroidColor.parseColor("#3F51B5")
                                        }

                                        // 1. Bottom-Right Resize Handle
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.bottom, handleRadius, handleFillPaint)
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.bottom, handleRadius, handleStrokePaint)

                                        // 2. Top-Left Delete (X) Button
                                        val deleteFillPaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.FILL
                                            color = AndroidColor.parseColor("#E53935") // Red
                                        }
                                        val xPaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.STROKE
                                            strokeWidth = 3f / currentScale
                                            color = AndroidColor.WHITE
                                            isAntiAlias = true
                                        }
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

                                        // 3. Top-Center Rotate Handle with connecting vertical line
                                        val rotateDist = 36f / currentScale
                                        val rotateY = bounds.top - rotateDist
                                        val linePaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.STROKE
                                            strokeWidth = 2f / currentScale
                                            color = AndroidColor.parseColor("#3F51B5")
                                        }
                                        drawContext.canvas.nativeCanvas.drawLine(bounds.centerX(), bounds.top, bounds.centerX(), rotateY, linePaint)
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.centerX(), rotateY, handleRadius, handleFillPaint)
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.centerX(), rotateY, handleRadius, handleStrokePaint)
                                    }

                                    drawContext.canvas.nativeCanvas.restore()
                                    drawContext.canvas.nativeCanvas.restoreToCount(count)
                                }
                                is Layer.ImageLayer -> {
                                    layer.bitmap?.let { imgBmp ->
                                        if (!imgBmp.isRecycled) {
                                            val imgPaint = AndroidPaint().apply {
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
    selectedLayer: Layer.TextLayer?,
    defaultStyle: TextStyleConfig,
    onAddText: (String) -> Unit,
    onUpdateStyle: (TextStyleConfig) -> Unit,
    onCapitalizationTransform: ((String) -> Unit)? = null
) {
    var textInput by remember { mutableStateOf("") }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("Teks Baru") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onAddText(textInput)
                            textInput = ""
                        }
                    }
                ) {
                    Text("Tambah")
                }
            }

            Text("Font Family:", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Default", "Sans", "Serif", "Monospace").forEach { font ->
                    FilterChip(
                        selected = currentStyle.fontName.equals(font, ignoreCase = true),
                        onClick = { onUpdateStyle(currentStyle.copy(fontName = font)) },
                        label = { Text(font) }
                    )
                }
            }

            Text("Font Style:", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Regular", "Bold", "Italic", "BoldItalic").forEach { st ->
                    val displayLabel = if (st == "BoldItalic") "Bold+Italic" else st
                    FilterChip(
                        selected = currentStyle.fontStyle.equals(st, ignoreCase = true),
                        onClick = { onUpdateStyle(currentStyle.copy(fontStyle = st)) },
                        label = { Text(displayLabel) }
                    )
                }
            }

            if (selectedLayer != null) {
                Text("Kapitalisasi Teks:", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            val upper = selectedLayer.text.uppercase()
                            onCapitalizationTransform?.invoke(upper)
                        },
                        label = { Text("UPPERCASE") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val lower = selectedLayer.text.lowercase()
                            onCapitalizationTransform?.invoke(lower)
                        },
                        label = { Text("lowercase") }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val capitalized = selectedLayer.text.split(" ").joinToString(" ") { word ->
                                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                            }
                            onCapitalizationTransform?.invoke(capitalized)
                        },
                        label = { Text("Capitalize") }
                    )
                }
            }

            Text("Ukuran Font: ${currentStyle.fontSize.toInt()} px", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = currentStyle.fontSize,
                onValueChange = { onUpdateStyle(currentStyle.copy(fontSize = it)) },
                valueRange = 12f..120f
            )
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

    val colors = listOf(
        AndroidColor.BLACK,
        AndroidColor.WHITE,
        AndroidColor.RED,
        AndroidColor.BLUE,
        AndroidColor.GREEN,
        AndroidColor.YELLOW,
        AndroidColor.MAGENTA,
        AndroidColor.CYAN
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { c ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(c), shape = androidx.compose.foundation.shape.CircleShape)
                    .padding(2.dp)
            ) {
                IconButton(
                    onClick = { onColorSelected(c) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (selectedColor == c) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = if (c == AndroidColor.WHITE || c == AndroidColor.YELLOW || c == AndroidColor.CYAN) Color.Black else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = {
                hexInput = String.format("#%06X", 0xFFFFFF and selectedColor)
                showCustomHexDialog = true
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.Palette,
                contentDescription = "Custom Hex",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showCustomHexDialog) {
        AlertDialog(
            onDismissRequest = { showCustomHexDialog = false },
            title = { Text("Pilih Warna Custom (Hex)", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    label = { Text("Hex Color (contoh: #FF5722)") },
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

@Composable
fun EffectToolPanel(
    selectedLayer: Layer?,
    onUpdateOpacity: (Float) -> Unit,
    onUpdateStyle: (TextStyleConfig) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Efek Layer", style = MaterialTheme.typography.titleMedium)

            if (selectedLayer == null) {
                Text("Pilih layer terlebih dahulu untuk mengatur transparansi dan efek.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Transparansi Layer (Opacity): ${(selectedLayer.opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = selectedLayer.opacity,
                    onValueChange = { onUpdateOpacity(it) },
                    valueRange = 0f..1f
                )

                if (selectedLayer is Layer.TextLayer) {
                    val currentStyle = selectedLayer.style

                    Text("Warna Fill Teks:", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !currentStyle.isGradientEnabled,
                            onClick = { onUpdateStyle(currentStyle.copy(isGradientEnabled = false)) },
                            label = { Text("Solid Color") }
                        )
                        FilterChip(
                            selected = currentStyle.isGradientEnabled,
                            onClick = { onUpdateStyle(currentStyle.copy(isGradientEnabled = true)) },
                            label = { Text("Gradient") }
                        )
                    }

                    if (!currentStyle.isGradientEnabled) {
                        Text("Warna Teks (Solid):", style = MaterialTheme.typography.bodySmall)
                        SimpleColorPickerRow(
                            selectedColor = currentStyle.textColor,
                            onColorSelected = { col -> onUpdateStyle(currentStyle.copy(textColor = col)) }
                        )
                    } else {
                        Text("Warna Gradient Start:", style = MaterialTheme.typography.bodySmall)
                        SimpleColorPickerRow(
                            selectedColor = currentStyle.gradientStartColor,
                            onColorSelected = { col -> onUpdateStyle(currentStyle.copy(gradientStartColor = col)) }
                        )

                        Text("Warna Gradient End:", style = MaterialTheme.typography.bodySmall)
                        SimpleColorPickerRow(
                            selectedColor = currentStyle.gradientEndColor,
                            onColorSelected = { col -> onUpdateStyle(currentStyle.copy(gradientEndColor = col)) }
                        )

                        Text("Arah Gradient:", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = currentStyle.gradientDirection.equals("HORIZONTAL", ignoreCase = true),
                                onClick = { onUpdateStyle(currentStyle.copy(gradientDirection = "HORIZONTAL")) },
                                label = { Text("Horizontal") }
                            )
                            FilterChip(
                                selected = currentStyle.gradientDirection.equals("VERTICAL", ignoreCase = true),
                                onClick = { onUpdateStyle(currentStyle.copy(gradientDirection = "VERTICAL")) },
                                label = { Text("Vertikal") }
                            )
                        }
                    }

                    Text("Opacity Teks (Fill): ${(currentStyle.textOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = currentStyle.textOpacity,
                        onValueChange = { onUpdateStyle(currentStyle.copy(textOpacity = it)) },
                        valueRange = 0f..1f
                    )

                    Text("Warna Stroke/Outline Teks:", style = MaterialTheme.typography.bodySmall)
                    SimpleColorPickerRow(
                        selectedColor = currentStyle.strokeColor,
                        onColorSelected = { col -> onUpdateStyle(currentStyle.copy(strokeColor = col)) }
                    )

                    Text("Ketebalan Stroke: ${currentStyle.strokeWidth.toInt()} px", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = currentStyle.strokeWidth,
                        onValueChange = {
                            val strokeCol = if (it > 0f && currentStyle.strokeColor == AndroidColor.TRANSPARENT) AndroidColor.BLACK else currentStyle.strokeColor
                            onUpdateStyle(currentStyle.copy(strokeWidth = it, strokeColor = strokeCol))
                        },
                        valueRange = 0f..20f
                    )

                    Text("Opacity Stroke: ${(currentStyle.strokeOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = currentStyle.strokeOpacity,
                        onValueChange = { onUpdateStyle(currentStyle.copy(strokeOpacity = it)) },
                        valueRange = 0f..1f
                    )

                    Text("Drop Shadow Details:", style = MaterialTheme.typography.titleSmall)

                    Text("Warna Bayangan:", style = MaterialTheme.typography.bodySmall)
                    SimpleColorPickerRow(
                        selectedColor = currentStyle.shadowColor,
                        onColorSelected = { col ->
                            onUpdateStyle(currentStyle.copy(shadowColor = col))
                        }
                    )

                    Text("Blur Radius: ${currentStyle.shadowRadius.toInt()} px", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = currentStyle.shadowRadius,
                        onValueChange = {
                            val shadowCol = if (it > 0f && currentStyle.shadowColor == AndroidColor.TRANSPARENT) AndroidColor.BLACK else currentStyle.shadowColor
                            onUpdateStyle(currentStyle.copy(shadowRadius = it, shadowColor = shadowCol))
                        },
                        valueRange = 0f..30f
                    )

                    Text("Offset X: ${currentStyle.shadowDx.toInt()} px", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = currentStyle.shadowDx,
                        onValueChange = { onUpdateStyle(currentStyle.copy(shadowDx = it)) },
                        valueRange = -30f..30f
                    )

                    Text("Offset Y: ${currentStyle.shadowDy.toInt()} px", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = currentStyle.shadowDy,
                        onValueChange = { onUpdateStyle(currentStyle.copy(shadowDy = it)) },
                        valueRange = -30f..30f
                    )
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
    onUpdateOpacity: ((Float) -> Unit)? = null
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
                        onValueChange = { onUpdateOpacity?.invoke(it) },
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
}
