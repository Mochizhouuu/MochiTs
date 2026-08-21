package com.mochits.app.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mochits.canvas.CanvasEditor
import com.mochits.canvas.CanvasEditorState
import com.mochits.canvas.CanvasTextLayer
import com.mochits.text.TextStyleConfig

/**
 * Editor screen: menampilkan base image project (long-strip/webtoon) di
 * dalam [CanvasEditor] — mendukung scroll vertikal natural, pinch-zoom,
 * text layer yang bisa di-drag, dan panel style (warna, stroke, shadow)
 * yang muncul saat sebuah teks dipilih.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectName: String,
    baseImagePath: String,
    onBack: () -> Unit
) {
    var intrinsicSize by remember(baseImagePath) {
        mutableStateOf<Pair<Int, Int>?>(null)
    }

    LaunchedEffect(baseImagePath) {
        intrinsicSize = readImageIntrinsicSize(baseImagePath)
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
    var addTextAtViewportCenter by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val selectedLayer = canvasState?.textLayers?.find { it.id == canvasState.selectedLayerId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canvasState != null) {
                FloatingActionButton(onClick = { showAddTextDialog = true }) {
                    Icon(Icons.Default.TextFields, contentDescription = "Tambah teks")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (canvasState == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    CanvasEditor(
                        state = canvasState,
                        modifier = Modifier.fillMaxSize(),
                        onReady = { addTextFn -> addTextAtViewportCenter = addTextFn }
                    ) { path, contentScale, imgModifier ->
                        AsyncImage(
                            model = path,
                            contentDescription = projectName,
                            contentScale = contentScale,
                            modifier = imgModifier
                        )
                    }
                }
            }

            // Panel style muncul di bawah canvas saat ada teks terpilih —
            // tidak menutupi gambar, dan langsung terlihat efeknya di atas
            // karena mengubah state layer secara langsung.
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

private val presetColors = listOf(
    0xFF000000.toInt(), // hitam
    0xFFFFFFFF.toInt(), // putih
    0xFFE53935.toInt(), // merah
    0xFF1E88E5.toInt(), // biru
    0xFFFDD835.toInt(), // kuning
    0xFF43A047.toInt()  // hijau
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
                Text("Warna teks", modifier = Modifier.weight(1f))
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

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stroke (outline)", modifier = Modifier.weight(1f))
                Switch(
                    checked = style.strokeEnabled,
                    onCheckedChange = { onStyleChange(style.copy(strokeEnabled = it)) }
                )
            }
            if (style.strokeEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetColors.forEach { colorArgb ->
                        ColorSwatch(
                            colorArgb = colorArgb,
                            isSelected = style.strokeColorArgb == colorArgb,
                            onClick = { onStyleChange(style.copy(strokeColorArgb = colorArgb)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Shadow", modifier = Modifier.weight(1f))
                Switch(
                    checked = style.shadowEnabled,
                    onCheckedChange = { onStyleChange(style.copy(shadowEnabled = it)) }
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
