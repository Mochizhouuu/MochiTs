package com.mochits.app.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mochits.canvas.CanvasEditor
import com.mochits.canvas.CanvasEditorState
import com.mochits.canvas.CanvasTextLayer
import com.mochits.common.OperationResult
import com.mochits.imaging.MaskSelectionToolsImpl
import com.mochits.imaging.TeleaInpainterImpl
import com.mochits.inpaint.LamaInpaintEngineImpl
import com.mochits.inpaint.ModelManager
import com.mochits.app.settings.AppSettings
import com.mochits.text.*
import kotlinx.coroutines.launch

/** Panel bawah yang bisa dibuka lewat quick toolbar. */
private enum class EditorPanel { STYLE, MASK, LAYERS }

enum class MaskToolMode {
    PAN_ZOOM,
    BRUSH_MASK,
    LASSO_SELECT,
    MAGIC_WAND,
    COLOR_PIPETTE
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
    val appSettings = remember { AppSettings(context) }

    val fontManager = remember { FontManager(context) }
    val presetManager = remember { TextPresetManager(context) }
    val maskSelectionTools = remember { MaskSelectionToolsImpl() }

    var installedFonts by remember { mutableStateOf(fontManager.getInstalledFonts()) }
    var availablePresets by remember { mutableStateOf(presetManager.getPresets()) }

    var currentBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var openPanel by remember { mutableStateOf<EditorPanel?>(null) }
    var activeMaskTool by remember { mutableStateOf(MaskToolMode.PAN_ZOOM) }
    var brushSizePx by remember { mutableStateOf(30f) }
    var wandTolerance by remember { mutableStateOf(15f) }

    var showAddTextDialog by remember { mutableStateOf(false) }
    var showEditTextDialog by remember { mutableStateOf<CanvasTextLayer?>(null) }
    var showSavePresetDialog by remember { mutableStateOf<TextStyleConfig?>(null) }
    var showInpaintDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }

    var addTextAtViewportCenter by remember { mutableStateOf<((String) -> Unit)?>(null) }

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

    val selectedLayer = canvasState?.textLayers?.find { it.id == canvasState.selectedLayerId }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(projectName, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        if (canvasState != null) {
                            Text(
                                "Zoom ${(canvasState.scale * 100).toInt()}% • " +
                                    "${canvasState.textLayers.size} layer" +
                                    (if (selectedLayer != null) " • teks terpilih" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { canvasState?.undo() },
                        enabled = canvasState?.canUndo == true
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Urungkan")
                    }
                    IconButton(
                        onClick = { canvasState?.redo() },
                        enabled = canvasState?.canRedo == true
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = "Ulangi")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                    IconButton(
                        onClick = {
                            val baseBmp = currentBaseBitmap
                            if (baseBmp != null && canvasState != null && !isExporting) {
                                coroutineScope.launch {
                                    isExporting = true
                                    val res = ProjectExporter.exportToGallery(
                                        context = context,
                                        baseImageBitmap = baseBmp,
                                        state = canvasState,
                                        subfolder = appSettings.exportLocation,
                                        quality = appSettings.exportQuality
                                    )
                                    isExporting = false
                                    when (res) {
                                        is OperationResult.Success ->
                                            Toast.makeText(
                                                context,
                                                "Tersimpan di galeri (${"Pictures/" + appSettings.exportLocation.trim('/')})",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        is OperationResult.Failure ->
                                            Toast.makeText(
                                                context,
                                                res.message ?: "Gagal ekspor",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                    }
                                }
                            }
                        },
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.SaveAlt, contentDescription = "Ekspor ke Galeri")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ===== Area Kanvas =====
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (canvasState == null || currentBaseBitmap == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Memuat gambar…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    CanvasEditor(
                        state = canvasState,
                        modifier = Modifier.fillMaxSize(),
                        maskBitmap = currentMaskBitmap,
                        isMaskingActive = openPanel == EditorPanel.MASK,
                        maskToolMode = activeMaskTool.name,
                        onMaskStrokeComplete = { strokePoints ->
                            val baseBmp = currentBaseBitmap ?: return@CanvasEditor
                            when (activeMaskTool) {
                                MaskToolMode.BRUSH_MASK -> {
                                    val res = maskSelectionTools.drawBrush(
                                        baseBmp.width,
                                        baseBmp.height,
                                        currentMaskBitmap,
                                        strokePoints,
                                        brushRadiusPx = brushSizePx
                                    )
                                    if (res is OperationResult.Success) {
                                        currentMaskBitmap = res.data
                                    }
                                }
                                MaskToolMode.LASSO_SELECT -> {
                                    val res = maskSelectionTools.lassoSelect(
                                        baseBmp.width,
                                        baseBmp.height,
                                        currentMaskBitmap,
                                        strokePoints
                                    )
                                    if (res is OperationResult.Success) {
                                        currentMaskBitmap = res.data
                                    }
                                }
                                else -> {}
                            }
                        },
                        onMaskTap = { xPx, yPx ->
                            val baseBmp = currentBaseBitmap ?: return@CanvasEditor
                            when (activeMaskTool) {
                                MaskToolMode.MAGIC_WAND -> {
                                    val res = maskSelectionTools.magicWandSelect(
                                        baseBmp,
                                        currentMaskBitmap,
                                        xPx.toInt(),
                                        yPx.toInt(),
                                        tolerance = wandTolerance.toInt()
                                    )
                                    if (res is OperationResult.Success) {
                                        currentMaskBitmap = res.data
                                    }
                                }
                                MaskToolMode.COLOR_PIPETTE -> {
                                    val color = maskSelectionTools.sampleColor(
                                        baseBmp,
                                        xPx.toInt(),
                                        yPx.toInt()
                                    )
                                    val hexColor = String.format("#%08X", color)
                                    showToast("Warna terpilih: $hexColor")
                                }
                                else -> {}
                            }
                        },
                        onReady = { addTextFn -> addTextAtViewportCenter = addTextFn },
                        onEditLayerRequest = { layerId ->
                            canvasState.textLayers.firstOrNull { it.id == layerId }?.let {
                                showEditTextDialog = it
                            }
                        }
                    ) { path, contentScale, imgModifier ->
                        AsyncImage(
                            model = currentBaseBitmap ?: path,
                            contentDescription = projectName,
                            contentScale = contentScale,
                            modifier = imgModifier
                        )
                    }

                    // ===== Overlay toolbar layer terpilih =====
                    AnimatedVisibility(
                        visible = selectedLayer != null && openPanel == null,
                        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                IconButton(onClick = {
                                    selectedLayer?.let { showEditTextDialog = it }
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit isi teks", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    selectedLayer?.let { canvasState.duplicateLayer(it.id) }
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplikat layer", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = {
                                    selectedLayer?.let {
                                        canvasState.updateLayerFontSize(it.id, it.fontSizeSp - 2f)
                                    }
                                }) {
                                    Icon(Icons.Default.Remove, contentDescription = "Kecilkan font")
                                }
                                IconButton(onClick = {
                                    selectedLayer?.let {
                                        canvasState.updateLayerFontSize(it.id, it.fontSizeSp + 2f)
                                    }
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = "Besarkan font")
                                }
                                IconButton(onClick = { canvasState.selectLayer(null) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Batal pilih", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    selectedLayer?.let { canvasState.deleteLayer(it.id) }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus layer", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            // ===== Quick Toolbar Bawah =====
            Surface(
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    QuickToolButton(
                        icon = Icons.Default.TextFields,
                        label = "Tambah Teks",
                        weight = 1f,
                        onClick = { showAddTextDialog = true }
                    )
                    QuickToolButton(
                        icon = Icons.Default.Palette,
                        label = "Gaya",
                        weight = 1f,
                        enabled = selectedLayer != null,
                        onClick = { openPanel = EditorPanel.STYLE }
                    )
                    QuickToolButton(
                        icon = Icons.Default.AutoFixHigh,
                        label = "Masker",
                        weight = 1f,
                        isSelected = openPanel == EditorPanel.MASK,
                        onClick = {
                            openPanel = if (openPanel == EditorPanel.MASK) null else EditorPanel.MASK
                        }
                    )
                    QuickToolButton(
                        icon = Icons.Default.Layers,
                        label = "Layer",
                        weight = 1f,
                        badgeCount = canvasState?.textLayers?.size ?: 0,
                        onClick = { openPanel = EditorPanel.LAYERS }
                    )
                }
            }
        }
    }

    // ===== Bottom Sheets =====
    if (openPanel == EditorPanel.STYLE) {
        ModalBottomSheet(
            onDismissRequest = { openPanel = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            if (selectedLayer != null && canvasState != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextTabContent(
                        selectedLayer = selectedLayer,
                        installedFonts = installedFonts,
                        availablePresets = availablePresets,
                        onAddTextClick = { showAddTextDialog = true },
                        onEditTextClick = { layer -> showEditTextDialog = layer },
                        onStyleChange = { newStyle ->
                            selectedLayer?.let { canvasState.updateLayerStyle(it.id, newStyle) }
                        },
                        onFontSizeChange = { newSize ->
                            selectedLayer?.let { canvasState.updateLayerFontSize(it.id, newSize) }
                        },
                        onApplyPreset = { preset ->
                            selectedLayer?.let { canvasState.updateLayerStyle(it.id, preset.style) }
                        },
                        onSavePresetClick = {
                            selectedLayer?.let { showSavePresetDialog = it.style }
                        },
                        onDeleteLayer = {
                            selectedLayer?.let { canvasState.deleteLayer(it.id) }
                        }
                    )
                }
            }
        }
    }

    if (openPanel == EditorPanel.MASK && canvasState != null) {
        ModalBottomSheet(
            onDismissRequest = { openPanel = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Sentuh/drag langsung di gambar untuk memakai alat aktif",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InpaintAndMaskTabContent(
                    activeMaskTool = activeMaskTool,
                    brushSizePx = brushSizePx,
                    wandTolerance = wandTolerance,
                    onToolSelect = { activeMaskTool = it },
                    onBrushSizeChange = { brushSizePx = it },
                    onWandToleranceChange = { wandTolerance = it },
                    onTriggerInpaint = { showInpaintDialog = true },
                    onClearMask = {
                        currentBaseBitmap?.let { bmp ->
                            currentMaskBitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                            showToast("Masker dibersihkan!")
                        }
                    }
                )
            }
        }
    }

    if (openPanel == EditorPanel.LAYERS && canvasState != null) {
        ModalBottomSheet(
            onDismissRequest = { openPanel = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "Daftar Layer Teks",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LayerListTabContent(
                    layers = canvasState.textLayers,
                    selectedId = canvasState.selectedLayerId,
                    onSelectLayer = { canvasState.selectLayer(it) },
                    onDeleteLayer = { canvasState.deleteLayer(it) }
                )
            }
        }
    }

    // ===== Dialogs =====
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

    showEditTextDialog?.let { layer ->
        EditTextDialog(
            initialText = layer.text,
            onConfirm = { newText ->
                showEditTextDialog = null
                if (canvasState != null && newText.isNotBlank()) {
                    canvasState.updateLayerText(layer.id, newText)
                }
            },
            onDismiss = { showEditTextDialog = null }
        )
    }

    showSavePresetDialog?.let { style ->
        SavePresetDialog(
            onConfirm = { name ->
                showSavePresetDialog = null
                val created = presetManager.savePreset(name, style)
                availablePresets = presetManager.getPresets()
                showToast("Preset '${created.name}' tersimpan!")
            },
            onDismiss = { showSavePresetDialog = null }
        )
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
                        showToast("Inpaint Telea berhasil!")
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
                            showToast("Inpaint LaMa AI berhasil!")
                        }
                        lamaEngine.release()
                    }
                }
            },
            onDismiss = { showInpaintDialog = false }
        )
    }
}

@Composable
private fun QuickToolButton(
    icon: ImageVector,
    label: String,
    weight: Float,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    badgeCount: Int = -1,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        modifier = Modifier.weight(weight)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 6.dp)
        ) {
            Box {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                    tint = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (badgeCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-2).dp)
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun InpaintAndMaskTabContent(
    activeMaskTool: MaskToolMode,
    brushSizePx: Float,
    wandTolerance: Float,
    onToolSelect: (MaskToolMode) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onWandToleranceChange: (Float) -> Unit,
    onTriggerInpaint: () -> Unit,
    onClearMask: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Alat Pembersih Balon (Inpaint)", style = MaterialTheme.typography.titleSmall)

        // Sub-toolbar Mode Alat Masker (Brush, Lasso, Magic Wand, Eyedropper, Zoom)
        Row(
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            MaskToolIconButton(
                icon = Icons.Default.PanTool,
                label = "Geser/Zoom",
                isSelected = activeMaskTool == MaskToolMode.PAN_ZOOM,
                onClick = { onToolSelect(MaskToolMode.PAN_ZOOM) }
            )
            MaskToolIconButton(
                icon = Icons.Default.Brush,
                label = "Brush",
                isSelected = activeMaskTool == MaskToolMode.BRUSH_MASK,
                onClick = { onToolSelect(MaskToolMode.BRUSH_MASK) }
            )
            MaskToolIconButton(
                icon = Icons.Default.Gesture,
                label = "Lasso",
                isSelected = activeMaskTool == MaskToolMode.LASSO_SELECT,
                onClick = { onToolSelect(MaskToolMode.LASSO_SELECT) }
            )
            MaskToolIconButton(
                icon = Icons.Default.AutoAwesome,
                label = "Wand",
                isSelected = activeMaskTool == MaskToolMode.MAGIC_WAND,
                onClick = { onToolSelect(MaskToolMode.MAGIC_WAND) }
            )
            MaskToolIconButton(
                icon = Icons.Default.Colorize,
                label = "Pipet",
                isSelected = activeMaskTool == MaskToolMode.COLOR_PIPETTE,
                onClick = { onToolSelect(MaskToolMode.COLOR_PIPETTE) }
            )
        }

        // Controls based on active tool mode
        when (activeMaskTool) {
            MaskToolMode.BRUSH_MASK -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Ukuran Brush Masker (${brushSizePx.toInt()} px)", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = brushSizePx,
                            onValueChange = onBrushSizeChange,
                            valueRange = 5f..100f
                        )
                    }
                }
            }
            MaskToolMode.MAGIC_WAND -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Toleransi Magic Wand (${wandTolerance.toInt()})", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = wandTolerance,
                            onValueChange = onWandToleranceChange,
                            valueRange = 1f..50f
                        )
                    }
                }
            }
            else -> {}
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onClearMask,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bersihkan")
            }

            Button(
                onClick = onTriggerInpaint,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Jalankan Inpaint")
            }
        }
    }
}

@Composable
private fun MaskToolIconButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val presetColors = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFE53935.toInt(),
    0xFF1E88E5.toInt(),
    0xFFFDD835.toInt(),
    0xFF43A047.toInt(),
    0xFF8E24AA.toInt(),
    0xFFFF6D00.toInt()
)

@Composable
private fun TextTabContent(
    selectedLayer: CanvasTextLayer?,
    installedFonts: List<CustomFontItem>,
    availablePresets: List<TextStylePreset>,
    onAddTextClick: () -> Unit,
    onEditTextClick: (CanvasTextLayer) -> Unit,
    onStyleChange: (TextStyleConfig) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onApplyPreset: (TextStylePreset) -> Unit,
    onSavePresetClick: () -> Unit,
    onDeleteLayer: () -> Unit
) {
    if (selectedLayer == null) return
    val style = selectedLayer.style

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Gaya Teks (${selectedLayer.fontSizeSp.toInt()} sp)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { onEditTextClick(selectedLayer) }) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ubah Isi")
            }
            IconButton(onClick = onDeleteLayer) {
                Icon(Icons.Default.Delete, contentDescription = "Hapus Layer", tint = MaterialTheme.colorScheme.error)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Slider(
                    value = selectedLayer.fontSizeSp,
                    onValueChange = onFontSizeChange,
                    valueRange = 10f..150f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FormatToggleButton(
                text = "B",
                isSelected = style.isBold,
                onClick = { onStyleChange(style.copy(isBold = !style.isBold)) }
            )
            FormatToggleButton(
                text = "I",
                isSelected = style.isItalic,
                onClick = { onStyleChange(style.copy(isItalic = !style.isItalic)) }
            )
            FormatToggleButton(
                text = "U",
                isSelected = style.isUnderline,
                onClick = { onStyleChange(style.copy(isUnderline = !style.isUnderline)) }
            )
            FormatToggleButton(
                text = "S",
                isSelected = style.isStrikethrough,
                onClick = { onStyleChange(style.copy(isStrikethrough = !style.isStrikethrough)) }
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = { onStyleChange(style.copy(alignment = TextAlignment.LEFT)) }) {
                Icon(
                    Icons.Default.FormatAlignLeft, contentDescription = null,
                    tint = if (style.alignment == TextAlignment.LEFT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onStyleChange(style.copy(alignment = TextAlignment.CENTER)) }) {
                Icon(
                    Icons.Default.FormatAlignCenter, contentDescription = null,
                    tint = if (style.alignment == TextAlignment.CENTER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onStyleChange(style.copy(alignment = TextAlignment.RIGHT)) }) {
                Icon(
                    Icons.Default.FormatAlignRight, contentDescription = null,
                    tint = if (style.alignment == TextAlignment.RIGHT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preset Style", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onSavePresetClick) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Simpan Style Ini", style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availablePresets, key = { it.id }) { preset ->
                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(preset) },
                        label = { Text(preset.name) }
                    )
                }
            }
        }

        if (installedFonts.isNotEmpty()) {
            Column {
                Text("Font Family", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = style.fontPath == null,
                            onClick = { onStyleChange(style.copy(fontPath = null)) },
                            label = { Text("System Default") }
                        )
                    }
                    items(installedFonts, key = { it.path }) { font ->
                        FilterChip(
                            selected = style.fontPath == font.path,
                            onClick = { onStyleChange(style.copy(fontPath = font.path)) },
                            label = { Text(font.name) }
                        )
                    }
                }
            }
        }

        Column {
            Text("Warna Teks", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presetColors, key = { it }) { colorArgb ->
                    ColorSwatch(
                        colorArgb = colorArgb,
                        isSelected = style.colorArgb == colorArgb,
                        onClick = { onStyleChange(style.copy(colorArgb = colorArgb)) }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Stroke (Outline)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = style.strokeEnabled,
                onCheckedChange = { onStyleChange(style.copy(strokeEnabled = it)) }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Glow (Bersinar)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = style.glowEnabled,
                onCheckedChange = { onStyleChange(style.copy(glowEnabled = it)) }
            )
        }
    }
}

@Composable
private fun FormatToggleButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LayerListTabContent(
    layers: List<CanvasTextLayer>,
    selectedId: String?,
    onSelectLayer: (String) -> Unit,
    onDeleteLayer: (String) -> Unit
) {
    if (layers.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
            Text("Belum ada layer teks pada gambar ini.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            layers.forEach { layer ->
                val isSelected = layer.id == selectedId
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLayer(layer.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.TextFields, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = layer.text.replace('\n', ' '),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                            Text(
                                text = "${layer.fontSizeSp.toInt()} sp",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

@Composable
private fun ColorSwatch(colorArgb: Int, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
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
                    Text("Telea Inpaint (Cepat)")
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
        title = { Text("Tambah Teks Baru") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Tulis teks komik...\nBisa beberapa baris") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
private fun EditTextDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        title = { Text("Ubah Isi Teks") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
private fun SavePresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "Style Custom" }) }) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        title = { Text("Simpan Preset Style") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Contoh: Style Balon Karakter A") },
                modifier = Modifier.fillMaxWidth()
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
