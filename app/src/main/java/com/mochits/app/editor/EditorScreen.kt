package com.mochits.app.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.mochits.canvas.CanvasEditor
import com.mochits.canvas.CanvasEditorState

/**
 * Editor screen: menampilkan base image project (long-strip/webtoon) di
 * dalam [CanvasEditor] — mendukung scroll vertikal natural, pinch-zoom,
 * dan text layer sederhana yang bisa di-drag. Efek teks & fitur
 * seleksi/inpainting akan ditambahkan pada tahap berikutnya.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectName: String,
    baseImagePath: String,
    onBack: () -> Unit
) {
    // Ukuran asli gambar WAJIB diketahui lebih dulu — posisi text layer
    // di CanvasEditorState disimpan dalam piksel gambar asli, bukan
    // piksel layar (lihat komentar di CanvasEditorState/CanvasEditor).
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
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
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
    }

    if (showAddTextDialog && canvasState != null) {
        AddTextDialog(
            onConfirm = { text ->
                showAddTextDialog = false
                if (text.isNotBlank()) {
                    // Tambah di tengah area yang sedang terlihat, bukan
                    // tengah gambar keseluruhan (lihat CanvasEditor.onReady).
                    addTextAtViewportCenter?.invoke(text)
                }
            },
            onDismiss = { showAddTextDialog = false }
        )
    }
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
