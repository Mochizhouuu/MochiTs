package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mochits.canvas.CanvasEditor
import com.mochits.canvas.CanvasEditorState
import com.mochits.canvas.CanvasTextLayer
import com.mochits.common.OperationResult
import com.mochits.imaging.TeleaInpainterImpl
import com.mochits.inpaint.LamaInpaintEngineImpl
import com.mochits.inpaint.ModelManager
import com.mochits.text.TextStyleConfig
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

enum class EditorToolMode {
    VIEW_PAN,
    MASK_BRUSH,
    MASK_LASSO,
    MASK_MAGIC_WAND,
    COLOR_EYEDROPPER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectName: String,
    baseImagePath: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeToolMode by remember { mutableStateOf(EditorToolMode.VIEW_PAN) }
    var currentBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedEyedropperColor by remember { mutableStateOf<Int?>(null) }

    var intrinsicSize by remember(baseImagePath) {
        mutableStateOf<Pair<Int, Int>?>(null)
    }

    LaunchedEffect(baseImagePath) {
        val size = readImageIntrinsicSize(baseImagePath)
        intrinsicSize = size
        val bmp = BitmapFactory.decodeFile(baseImagePath)
        currentBaseBitmap = bmp
        if (bmp != null) {
            currentMaskBitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        }
    }

    val size = intrinsicSize
    val canvasState = if (size != null) {
        remember(baseImagePath) {
            CanvasEditorState(
                baseImagePath = baseImagePath,
                intrinsicWidthPx = size.first,
                intrinsicHeightPx = size.second
            )
        }
    } else null

    var showAddTextDialog by remember { mutableStateOf(false) }
    var showInpaintDialog by remember { mutableStateOf(false) }
    var addTextAtViewportCenter by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val selectedLayer = canvasState?.textLayers?.find { it.id == canvasState.selectedLayerId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                    IconButton(onClick = {
                        val baseBmp = currentBaseBitmap
                        if (baseBmp != null && canvasState != null) {
                            coroutineScope.launch {
                                val exportFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.png")
                                val res = ProjectExporter.exportProjectToGallery(baseBmp, canvasState, exportFile)
                                when (res) {
                                    is OperationResult.Success -> Toast.makeText(context, "Berhasil di-ekspor: ${res.data.name}", Toast.LENGTH_LONG).show()
                                    is OperationResult.Failure -> Toast.makeText(context, res.message ?: "Gagal ekspor", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Ekspor Gambar")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canvasState != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(
                        onClick = { showInpaintDialog = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "Inpaint Mask")
                    }
                    FloatingActionButton(onClick = { showAddTextDialog = true }) {
                        Icon(Icons.Default.TextFields, contentDescription = "Tambah teks")
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Mode Selector Bar (Photoshop Compact Mobile Style)
            EditorToolBar(
                activeMode = activeToolMode,
                onModeSelected = { activeToolMode = it }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (canvasState == null || currentBaseBitmap == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    CanvasEditor(
                        state = canvasState,
                        modifier = Modifier.fillMaxSize(),
                        onReady = { addTextFn -> addTextAtViewportCenter = addTextFn }
                    ) { path, contentScale, imgModifier ->
                        AsyncImage(
                            model = currentBaseBitmap ?: path,
                            contentDescription = projectName,
                            contentScale = contentScale,
                            modifier = imgModifier
                        )
                    }
                }
            }

            if (canvasState != null && selectedLayer != null) {
                TextStylePanel(
                    layer = selectedLayer,
                    onStyleChange = { newStyle ->
                        canvasState.updateLayerStyle(selectedLayer.id, newStyle)
                    },
                    onDelete = {
                        canvasState.deleteLayer(selectedLayer.id)
                    },
                    onDone = {
                        canvasState.selectLayer(null)
                    }
                )
            }
        }
    }

    if (showInpaintDialog && currentBaseBitmap != null && currentMaskBitmap != null) {
        InpaintOptionDialog(
            onSelectTelea = {
                showInpaintDialog = false
                coroutineScope.launch {
                    val telea = TeleaInpainterImpl()
                    val res = telea.inpaint(currentBaseBitmap!!, currentMaskBitmap!!)
                    if (res is OperationResult.Success) {
                        currentBaseBitmap = res.data
                        Toast.makeText(context, "Inpaint Telea berhasil!", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSelectLama = {
                showInpaintDialog = false
                coroutineScope.launch {
                    val modelMgr = ModelManager(context)
                    if (!modelMgr.isModelAvailable()) {
                        Toast.makeText(context, "Model LaMa belum diunduh, otomatis fallback ke Telea", Toast.LENGTH_LONG).show()
                        val telea = TeleaInpainterImpl()
                        val res = telea.inpaint(currentBaseBitmap!!, currentMaskBitmap!!)
                        if (res is OperationResult.Success) currentBaseBitmap = res.data
                    } else {
                        val lamaEngine = LamaInpaintEngineImpl(context)
                        lamaEngine.loadModel(modelMgr.getModelFilePath())
                        val res = lamaEngine.infer(currentBaseBitmap!!, currentMaskBitmap!!)
                        if (res is OperationResult.Success) {
                            currentBaseBitmap = res.data
                            Toast.makeText(context, "Inpaint LaMa AI berhasil!", Toast.LENGTH_SHORT).show()
                        }
                        lamaEngine.release()
                    }
                }
            },
            onDismiss = { showInpaintDialog = false }
        )
    }

    if (showAddTextDialog && canvasState != null) {
        AddTextDialog(
            onConfirm = { text ->
                showAddTextDialog = false
                if (text.isNotBlank()) {
                    addTextAtViewportCenter?.invoke(text)
                }
            },
            onDismiss = { showAddTextDialog = false }
        )
    }
}

@Composable
private fun EditorToolBar(
    activeMode: EditorToolMode,
    onModeSelected: (EditorToolMode) -> Unit
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onModeSelected(EditorToolMode.VIEW_PAN) }) {
                Icon(
                    Icons.Default.PanTool,
                    contentDescription = "Pan/Zoom",
                    tint = if (activeMode == EditorToolMode.VIEW_PAN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { onModeSelected(EditorToolMode.MASK_BRUSH) }) {
                Icon(
                    Icons.Default.Brush,
                    contentDescription = "Brush Mask",
                    tint = if (activeMode == EditorToolMode.MASK_BRUSH) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { onModeSelected(EditorToolMode.MASK_LASSO) }) {
                Icon(
                    Icons.Default.Gesture,
                    contentDescription = "Lasso Select",
                    tint = if (activeMode == EditorToolMode.MASK_LASSO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { onModeSelected(EditorToolMode.MASK_MAGIC_WAND) }) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "Magic Wand",
                    tint = if (activeMode == EditorToolMode.MASK_MAGIC_WAND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = { onModeSelected(EditorToolMode.COLOR_EYEDROPPER) }) {
                Icon(
                    Icons.Default.Colorize,
                    contentDescription = "Pipet Warna",
                    tint = if (activeMode == EditorToolMode.COLOR_EYEDROPPER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun InpaintOptionDialog(
    onSelectTelea: () -> Unit,
    onSelectLama: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pilih Algoritma Inpaint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSelectTelea, modifier = Modifier.fillMaxWidth()) {
                    Text("Telea Inpaint (OpenCV - Cepat)")
                }
                Button(onClick = onSelectLama, modifier = Modifier.fillMaxWidth()) {
                    Text("LaMa Inpaint (AI Neural Network)")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

private val presetColors = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFE53935.toInt(),
    0xFF1E88E5.toInt(),
    0xFFFDD835.toInt(),
    0xFF43A047.toInt()
)

@Composable
private fun TextStylePanel(
    layer: CanvasTextLayer,
    onStyleChange: (TextStyleConfig) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit
) {
    val style = layer.style

    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Warna Teks", modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Hapus teks")
                }
                TextButton(onClick = onDone) { Text("Selesai") }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presetColors.forEach { colorArgb ->
                    ColorSwatch(
                        colorArgb = colorArgb,
                        isSelected = style.colorArgb == colorArgb,
                        onClick = { onStyleChange(style.copy(colorArgb = colorArgb)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Options: Stroke, Glow, Motion Blur, Gradient
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stroke", modifier = Modifier.weight(1f))
                Switch(
                    checked = style.strokeEnabled,
                    onCheckedChange = { onStyleChange(style.copy(strokeEnabled = it)) }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Glow (Bersinar)", modifier = Modifier.weight(1f))
                Switch(
                    checked = style.glowEnabled,
                    onCheckedChange = { onStyleChange(style.copy(glowEnabled = it)) }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Motion Blur", modifier = Modifier.weight(1f))
                Switch(
                    checked = style.motionBlurEnabled,
                    onCheckedChange = { onStyleChange(style.copy(motionBlurEnabled = it)) }
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(colorArgb: Int, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .then(
                if (isSelected) {
                    Modifier.background(Color.Gray.copy(alpha = 0.3f))
                } else Modifier
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun AddTextDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Tambah") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        title = { Text("Teks baru") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Tulis teks...") }
            )
        }
    )
}

private fun readImageIntrinsicSize(path: String): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    val width = options.outWidth.coerceAtLeast(1)
    val height = options.outHeight.coerceAtLeast(1)
    return width to height
}
