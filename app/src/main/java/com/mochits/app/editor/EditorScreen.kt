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
                title = { Text(project?.title ?: "Editor Canvas") },
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
                        onUpdateStyle = { style -> viewModel.updateSelectedTextLayerStyle(style) }
                    )
                    EditorPanel.LAYERS -> LayersToolPanel(
                        layers = layers,
                        selectedId = selectedLayerId,
                        onSelectLayer = { viewModel.selectLayer(it) },
                        onMoveLayer = { id, dir -> viewModel.moveLayer(id, dir) },
                        onToggleVisibility = { viewModel.toggleLayerVisibility(it) },
                        onDeleteLayer = { viewModel.deleteLayer(it) },
                        onLoadBaseImage = { baseImagePickerLauncher.launch("image/*") }
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

            var isResizingText by remember { mutableStateOf(false) }
            var initialDragDist by remember { mutableFloatStateOf(0f) }
            var initialFontSize by remember { mutableFloatStateOf(36f) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activePanel, selectedLayerId, handleCanvasCenter) {
                        if (activePanel == EditorPanel.MASK) {
                            detectDragGestures(
                                onDragStart = { screenOffset ->
                                    viewModel.saveUndoSnapshot()
                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(screenOffset.x, screenOffset.y)
                                    lastTouchCanvasPt = canvasPt
                                    viewModel.maskSelectionTools?.startStroke(canvasPt, maskToolMode, brushSize)
                                    triggerRedraw++
                                },
                                onDrag = { change, _ ->
                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(change.position.x, change.position.y)
                                    lastTouchCanvasPt = canvasPt
                                    viewModel.maskSelectionTools?.updateStroke(canvasPt, maskToolMode, brushSize)
                                    triggerRedraw++
                                },
                                onDragEnd = {
                                    viewModel.maskSelectionTools?.endStroke(lastTouchCanvasPt, maskToolMode, brushSize)
                                    triggerRedraw++
                                }
                            )
                        } else {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                viewModel.canvasState.onGestureTransform(centroid, pan, zoom)
                                triggerRedraw++
                            }
                        }
                    }
                    .pointerInput(selectedTextLayer, handleCanvasCenter) {
                        if (selectedTextLayer != null) {
                            detectDragGestures(
                                onDragStart = { screenPt ->
                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(screenPt.x, screenPt.y)
                                    val handleRadius = 32f / viewModel.canvasState.scale
                                    val dx = canvasPt.x - handleCanvasCenter.x
                                    val dy = canvasPt.y - handleCanvasCenter.y
                                    if (dx * dx + dy * dy <= handleRadius * handleRadius) {
                                        isResizingText = true
                                        viewModel.saveUndoSnapshot()
                                        val textCenterX = selectedTextLayer.x
                                        val textCenterY = selectedTextLayer.y
                                        initialDragDist = kotlin.math.hypot(canvasPt.x - textCenterX, canvasPt.y - textCenterY)
                                        initialFontSize = selectedTextLayer.style.fontSize
                                    } else {
                                        isResizingText = false
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (isResizingText) {
                                        val canvasPt = viewModel.canvasState.mapper.screenToCanvas(change.position.x, change.position.y)
                                        val textCenterX = selectedTextLayer.x
                                        val textCenterY = selectedTextLayer.y
                                        val currentDist = kotlin.math.hypot(canvasPt.x - textCenterX, canvasPt.y - textCenterY)
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
                                },
                                onDragEnd = {
                                    isResizingText = false
                                }
                            )
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

                    // 2. Mask Selection Overlay
                    viewModel.maskSelectionTools?.maskBitmap?.let { maskBmp ->
                        if (!maskBmp.isRecycled) {
                            drawContext.canvas.nativeCanvas.drawBitmap(maskBmp, 0f, 0f, null)
                        }
                    }

                    // 3. Render Layers
                    layers.forEach { layer ->
                        if (layer.isVisible) {
                            when (layer) {
                                is Layer.TextLayer -> {
                                    textRenderer.drawStyledText(
                                        canvas = drawContext.canvas.nativeCanvas,
                                        text = layer.text,
                                        style = layer.style,
                                        x = layer.x,
                                        y = layer.y
                                    )

                                    // Render bounding box & bottom-right resize handle if selected
                                    if (layer.id == selectedLayerId) {
                                        val bounds: RectF = textRenderer.getTextBounds(layer.text, layer.style, layer.x, layer.y)
                                        val boxPaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.STROKE
                                            strokeWidth = 3f / viewModel.canvasState.scale
                                            color = AndroidColor.BLUE
                                            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        }
                                        drawContext.canvas.nativeCanvas.drawRect(bounds, boxPaint)

                                        // Draw Bottom-Right Handle Circle
                                        val handleRadius = 14f / viewModel.canvasState.scale
                                        val handleFillPaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.FILL
                                            color = AndroidColor.WHITE
                                        }
                                        val handleStrokePaint = AndroidPaint().apply {
                                            style = AndroidPaint.Style.STROKE
                                            strokeWidth = 3f / viewModel.canvasState.scale
                                            color = AndroidColor.BLUE
                                        }
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.bottom, handleRadius, handleFillPaint)
                                        drawContext.canvas.nativeCanvas.drawCircle(bounds.right, bounds.bottom, handleRadius, handleStrokePaint)
                                    }
                                }
                                is Layer.ImageLayer -> {
                                    layer.bitmap?.let { imgBmp ->
                                        if (!imgBmp.isRecycled) {
                                            drawContext.canvas.nativeCanvas.drawBitmap(imgBmp, layer.x, layer.y, null)
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

        // Save As / Export / Rename Dialog
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Simpan / Save As & Ubah Nama", style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = projectTitleName,
                            onValueChange = { projectTitleName = it },
                            label = { Text("Nama Project") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = outputFileName,
                            onValueChange = { outputFileName = it },
                            label = { Text("Nama File Output") },
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
                            if (projectTitleName.isNotBlank() && projectTitleName != project?.title) {
                                viewModel.updateProjectTitle(projectTitleName)
                            }
                            showExportDialog = false
                            val ext = selectedFormat.lowercase()
                            val saveName = if (outputFileName.isNotBlank()) outputFileName else "export"
                            exportLauncher.launch("$saveName.$ext")
                        }
                    ) {
                        Text("Ekspor")
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
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
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
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
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
    onUpdateStyle: (TextStyleConfig) -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val currentStyle = selectedLayer?.style ?: TextStyleConfig()

    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text("Ukuran Font: ${currentStyle.fontSize.toInt()} px")
                Slider(
                    value = currentStyle.fontSize,
                    onValueChange = { onUpdateStyle(currentStyle.copy(fontSize = it)) },
                    valueRange = 12f..120f
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
    onLoadBaseImage: () -> Unit = {}
) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            OutlinedButton(
                onClick = onLoadBaseImage,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ganti Gambar Latar (Base Image)")
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
