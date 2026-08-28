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
import com.mochits.app.text.TextRenderer
import java.io.File

private enum class TextHandleType {
    RESIZE, ROTATE, DELETE
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
    val isProcessingInpaint by viewModel.isProcessingInpaint.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val isLoadingImage by viewModel.isLoadingImage.collectAsState()

    val textRenderer = remember { TextRenderer(context) }
    var triggerRedraw by remember { mutableIntStateOf(0) }
    var isMaskPanelCollapsed by remember { mutableStateOf(false) }

    // Dialog & Dropdown Menu States
    var showAddMenu by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var newTextValue by remember { mutableStateOf("") }

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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        uri?.let {
            val pfd = context.contentResolver.openFileDescriptor(it, "w") ?: return@rememberLauncherForActivityResult
            val file = File(context.cacheDir, "temp_export.${selectedFormat.lowercase()}")
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
            viewModel.exportProject(file, compressFormat, exportQuality.toInt()) { success ->
                if (success) {
                    val input = file.inputStream()
                    val output = java.io.FileOutputStream(pfd.fileDescriptor)
                    input.copyTo(output)
                    input.close()
                    output.close()
                }
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
                    IconButton(onClick = { viewModel.setActivePanel(EditorPanel.MASK) }) {
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
                    EditorPanel.MASK -> MaskToolPanel(
                        mode = maskToolMode,
                        brushSize = brushSize,
                        isCollapsed = isMaskPanelCollapsed,
                        onToggleCollapse = { isMaskPanelCollapsed = !isMaskPanelCollapsed },
                        onModeSelected = { viewModel.setMaskToolMode(it) },
                        onSizeChange = { viewModel.setBrushSize(it) },
                        onClear = {
                            viewModel.saveUndoSnapshot()
                            viewModel.maskSelectionTools?.clearMask()
                            triggerRedraw++
                        },
                        onInvert = {
                            viewModel.saveUndoSnapshot()
                            viewModel.maskSelectionTools?.invertMask()
                            triggerRedraw++
                        }
                    )
                    EditorPanel.INPAINT -> InpaintToolPanel(
                        isProcessing = isProcessingInpaint,
                        onRunInpaint = {
                            viewModel.runTeleaInpaint()
                            triggerRedraw++
                        }
                    )
                    EditorPanel.TEXT -> TextToolPanel(
                        selectedLayer = layers.find { it.id == selectedLayerId } as? Layer.TextLayer,
                        onAddText = { text -> viewModel.addTextLayer(text) },
                        onUpdateStyle = { style -> viewModel.updateSelectedTextLayerStyle(style) },
                        onCapitalizationTransform = { newText -> viewModel.updateSelectedTextContent(newText) },
                        onUpdateOpacity = { opacity -> viewModel.updateSelectedLayerOpacity(opacity) }
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
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color.DarkGray)
        ) {
            var lastTouchCanvasPt by remember { mutableStateOf(Offset.Zero) }

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

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activePanel, selectedTextLayer, handleCanvasCenter, deleteHandleCanvasCenter, rotateHandleCanvasCenter) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.isEmpty()) continue

                                // 1. MASK TOOL ACTIVE: Handle mask drawing strokes
                                if (activePanel == EditorPanel.MASK) {
                                    val firstChange = changes.first()
                                    if (firstChange.pressed) {
                                        val isJustDown = !firstChange.previousPressed && firstChange.pressed
                                        if (isJustDown) {
                                            viewModel.saveUndoSnapshot()
                                            val canvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                            lastTouchCanvasPt = canvasPt
                                            viewModel.maskSelectionTools?.startStroke(canvasPt, maskToolMode, brushSize)
                                            triggerRedraw++
                                        } else {
                                            val canvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                            lastTouchCanvasPt = canvasPt
                                            viewModel.maskSelectionTools?.updateStroke(canvasPt, maskToolMode, brushSize)
                                            triggerRedraw++
                                        }
                                        firstChange.consume()
                                    } else if (firstChange.previousPressed && !firstChange.pressed) {
                                        viewModel.maskSelectionTools?.endStroke(lastTouchCanvasPt, maskToolMode, brushSize)
                                        triggerRedraw++
                                    }
                                    continue
                                }

                                // 2. TEXT HANDLES INTERCEPTION: Check hit-testing on handles
                                val firstChange = changes.first()
                                if (selectedTextLayer != null) {
                                    val touchCanvasPt = viewModel.canvasState.mapper.screenToCanvas(firstChange.position.x, firstChange.position.y)
                                    val handleHitRadius = (32f / viewModel.canvasState.scale)

                                    val isJustDown = !firstChange.previousPressed && firstChange.pressed
                                    if (isJustDown) {
                                        // Test Resize Handle (Bottom-Right)
                                        val distResizeSq = (touchCanvasPt.x - handleCanvasCenter.x) * (touchCanvasPt.x - handleCanvasCenter.x) +
                                                (touchCanvasPt.y - handleCanvasCenter.y) * (touchCanvasPt.y - handleCanvasCenter.y)
                                        // Test Delete Handle (Top-Left)
                                        val distDeleteSq = (touchCanvasPt.x - deleteHandleCanvasCenter.x) * (touchCanvasPt.x - deleteHandleCanvasCenter.x) +
                                                (touchCanvasPt.y - deleteHandleCanvasCenter.y) * (touchCanvasPt.y - deleteHandleCanvasCenter.y)
                                        // Test Rotate Handle (Top-Center)
                                        val distRotateSq = (touchCanvasPt.x - rotateHandleCanvasCenter.x) * (touchCanvasPt.x - rotateHandleCanvasCenter.x) +
                                                (touchCanvasPt.y - rotateHandleCanvasCenter.y) * (touchCanvasPt.y - rotateHandleCanvasCenter.y)

                                        val rSq = handleHitRadius * handleHitRadius

                                        if (distDeleteSq <= rSq) {
                                            activeHandleType = TextHandleType.DELETE
                                            viewModel.deleteLayer(selectedTextLayer.id)
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
                                            firstChange.consume()
                                            continue
                                        } else {
                                            activeHandleType = null
                                        }
                                    }

                                    // Handle active drag on text handles
                                    if (activeHandleType != null && firstChange.pressed) {
                                        firstChange.consume()
                                        when (activeHandleType) {
                                            TextHandleType.RESIZE -> {
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
                                            TextHandleType.ROTATE -> {
                                                val bounds = textRenderer.getTextBounds(selectedTextLayer.text, selectedTextLayer.style, selectedTextLayer.x, selectedTextLayer.y)
                                                val textCenterX = bounds.centerX()
                                                val textCenterY = bounds.centerY()
                                                val currentAngle = Math.toDegrees(kotlin.math.atan2((touchCanvasPt.y - textCenterY).toDouble(), (touchCanvasPt.x - textCenterX).toDouble())).toFloat()
                                                val deltaAngle = currentAngle - initialTouchAngle
                                                val newRotation = (initialTextRotation + deltaAngle) % 360f
                                                viewModel.updateSelectedTextLayerRotation(newRotation, saveUndo = false)
                                                triggerRedraw++
                                            }
                                            else -> {}
                                        }
                                        continue
                                    }

                                    if (firstChange.previousPressed && !firstChange.pressed) {
                                        if (activeHandleType != null) {
                                            viewModel.finalizeTextTransform()
                                        }
                                        activeHandleType = null
                                    }
                                }

                                // 3. TAP TO DESELECT OR GESTURE PAN/ZOOM:
                                // If touch is not consuming a text handle, handle multi-touch transform or single tap
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

                                        viewModel.canvasState.onGestureTransform(center, panDelta, zoomFactor)
                                        triggerRedraw++
                                        pressedList.forEach { it.consume() }
                                    } else if (pressedList.size == 1) {
                                        val c = pressedList[0]
                                        val panDelta = c.position - c.previousPosition
                                        val moved = panDelta.getDistance() > 2f
                                        if (moved) {
                                            viewModel.canvasState.onGestureTransform(c.position, panDelta, 1f)
                                            triggerRedraw++
                                            c.consume()
                                        }
                                    } else {
                                        // All fingers released: check single tap release to deselect text
                                        val releasedChange = changes.find { it.previousPressed && !it.pressed }
                                        if (releasedChange != null && selectedLayerId != null) {
                                            viewModel.selectLayer(null)
                                            triggerRedraw++
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
                                viewModel.addTextLayer(newTextValue)
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
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (outputFileName.isNotBlank() && outputFileName != project?.title) {
                                viewModel.updateProjectTitle(outputFileName)
                            }
                            showExportDialog = false
                            val ext = selectedFormat.lowercase()
                            val saveName = if (outputFileName.isNotBlank()) outputFileName else "export"
                            exportLauncher.launch("$saveName.$ext")
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
            selected = activePanel == EditorPanel.MASK,
            onClick = { onPanelSelect(EditorPanel.MASK) },
            icon = { Icon(Icons.Default.Brush, contentDescription = "Mask") },
            label = { Text("Mask") }
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.INPAINT,
            onClick = { onPanelSelect(EditorPanel.INPAINT) },
            icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = "Inpaint") },
            label = { Text("Inpaint") }
        )
        NavigationBarItem(
            selected = activePanel == EditorPanel.TEXT,
            onClick = { onPanelSelect(EditorPanel.TEXT) },
            icon = { Icon(Icons.Default.TextFields, contentDescription = "Text") },
            label = { Text("Text") }
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
fun MaskToolPanel(
    mode: MaskToolMode,
    brushSize: Float,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onModeSelected: (MaskToolMode) -> Unit,
    onSizeChange: (Float) -> Unit,
    onClear: () -> Unit,
    onInvert: () -> Unit
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
                    text = "Tool Mask (${mode.name})",
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        selected = mode == MaskToolMode.RECTANGLE,
                        onClick = { onModeSelected(MaskToolMode.RECTANGLE) },
                        label = { Text("Rect") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                    OutlinedButton(onClick = onInvert) { Text("Invert") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Ukuran Kuas: ${brushSize.toInt()} px")
                Slider(
                    value = brushSize,
                    onValueChange = onSizeChange,
                    valueRange = 5f..200f
                )
            }
        }
    }
}

@Composable
fun InpaintToolPanel(
    isProcessing: Boolean,
    onRunInpaint: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hapus Objek/Teks Terseleksi")
            Button(onClick = onRunInpaint, enabled = !isProcessing) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("Jalankan Inpaint")
                }
            }
        }
    }
}

@Composable
fun TextToolPanel(
    selectedLayer: Layer.TextLayer?,
    onAddText: (String) -> Unit,
    onUpdateStyle: (TextStyleConfig) -> Unit,
    onCapitalizationTransform: ((String) -> Unit)? = null,
    onUpdateOpacity: ((Float) -> Unit)? = null
) {
    var textInput by remember { mutableStateOf("") }
    val currentStyle = selectedLayer?.style ?: TextStyleConfig()

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            if (selectedLayer != null) {
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

                Text("Ukuran Font: ${currentStyle.fontSize.toInt()} px")
                Slider(
                    value = currentStyle.fontSize,
                    onValueChange = { onUpdateStyle(currentStyle.copy(fontSize = it)) },
                    valueRange = 12f..120f
                )

                // Opacity Slider for Text Layer
                Text("Transparansi (Opacity): ${(selectedLayer.opacity * 100).toInt()}%")
                Slider(
                    value = selectedLayer.opacity,
                    onValueChange = { onUpdateOpacity?.invoke(it) },
                    valueRange = 0f..1f
                )

                // Drop Shadow Controls
                Text("Bayangan (Drop Shadow): Radius ${currentStyle.shadowRadius.toInt()} px")
                Slider(
                    value = currentStyle.shadowRadius,
                    onValueChange = {
                        val shadowCol = if (it > 0f) AndroidColor.BLACK else AndroidColor.TRANSPARENT
                        onUpdateStyle(currentStyle.copy(shadowRadius = it, shadowColor = shadowCol, shadowDx = it * 0.5f, shadowDy = it * 0.5f))
                    },
                    valueRange = 0f..30f
                )
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
                            Row {
                                IconButton(onClick = { onMoveLayer(layer.id, -1) }) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                                }
                                IconButton(onClick = { onMoveLayer(layer.id, 1) }) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                                }
                                IconButton(onClick = { onDeleteLayer(layer.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Layer")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
