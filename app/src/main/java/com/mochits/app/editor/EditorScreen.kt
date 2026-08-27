package com.mochits.app.editor

import android.graphics.BitmapFactory
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
    val isExporting by viewModel.isExporting.collectAsState()

    val textRenderer = remember { TextRenderer(context) }
    var triggerRedraw by remember { mutableStateOf(0) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bmp = BitmapFactory.decodeStream(inputStream)
            bmp?.let { loadedBmp ->
                viewModel.setBaseImage(loadedBmp)
            }
        }
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf("PNG") }
    var exportQuality by remember { mutableFloatStateOf(100f) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        uri?.let {
            val pfd = context.contentResolver.openFileDescriptor(it, "w") ?: return@rememberLauncherForActivityResult
            val file = File(context.cacheDir, "temp_export.${selectedFormat.lowercase()}")
            val compressFormat = when (selectedFormat.uppercase()) {
                "JPEG", "JPG" -> android.graphics.Bitmap.CompressFormat.JPEG
                "WEBP" -> android.graphics.Bitmap.CompressFormat.WEBP
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
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.Save, contentDescription = "Export")
                    }
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.Image, contentDescription = "Load Image")
                    }
                    IconButton(onClick = { viewModel.setActivePanel(EditorPanel.LAYERS) }) {
                        Icon(Icons.Default.Layers, contentDescription = "Layers")
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
                        onModeSelected = { viewModel.setMaskToolMode(it) },
                        onSizeChange = { viewModel.setBrushSize(it) },
                        onClear = {
                            viewModel.maskSelectionTools?.clearMask()
                            triggerRedraw++
                        },
                        onInvert = {
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
                        onDeleteLayer = { viewModel.deleteLayer(it) }
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
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activePanel) {
                        if (activePanel == EditorPanel.MASK) {
                            detectDragGestures(
                                onDragStart = { screenOffset ->
                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(screenOffset.x, screenOffset.y)
                                    viewModel.maskSelectionTools?.startStroke(canvasPt, maskToolMode, brushSize)
                                    triggerRedraw++
                                },
                                onDrag = { change, _ ->
                                    val canvasPt = viewModel.canvasState.mapper.screenToCanvas(change.position.x, change.position.y)
                                    viewModel.maskSelectionTools?.updateStroke(canvasPt, maskToolMode, brushSize)
                                    triggerRedraw++
                                },
                                onDragEnd = {
                                    viewModel.maskSelectionTools?.endStroke(Offset.Zero, maskToolMode, brushSize)
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
            ) {
                // Read state to register Compose recomposition loop
                @Suppress("UNUSED_VARIABLE")
                val redraw = triggerRedraw
                val canvasWidth = size.width
                val canvasHeight = size.height

                baseBitmap?.let { bmp ->
                    if (viewModel.canvasState.scale == 1f && viewModel.canvasState.offsetX == 0f) {
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

                // 1. Base Image
                baseBitmap?.let { bmp ->
                    drawContext.canvas.nativeCanvas.drawBitmap(bmp, 0f, 0f, null)
                }

                // 2. Mask Selection Overlay
                viewModel.maskSelectionTools?.maskBitmap?.let { maskBmp ->
                    drawContext.canvas.nativeCanvas.drawBitmap(maskBmp, 0f, 0f, null)
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
                            }
                            is Layer.ImageLayer -> {
                                layer.bitmap?.let { imgBmp ->
                                    drawContext.canvas.nativeCanvas.drawBitmap(imgBmp, layer.x, layer.y, null)
                                }
                            }
                        }
                    }
                }

                drawContext.canvas.nativeCanvas.restore()
            }
        }

        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Simpan / Ekspor Gambar", style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Pilih Format Gambar:", style = MaterialTheme.typography.bodyMedium)
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
                            showExportDialog = false
                            val ext = selectedFormat.lowercase()
                            exportLauncher.launch("${project?.title ?: "export"}.$ext")
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
    onModeSelected: (MaskToolMode) -> Unit,
    onSizeChange: (Float) -> Unit,
    onClear: () -> Unit,
    onInvert: () -> Unit
) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onDeleteLayer: (String) -> Unit
) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().height(200.dp)) {
        LazyColumn(modifier = Modifier.padding(8.dp)) {
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
