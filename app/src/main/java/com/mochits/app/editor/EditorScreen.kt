package com.mochits.app.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mochits.canvas.CanvasEditor
import com.mochits.canvas.CanvasEditorState

/**
 * Editor screen: menampilkan base image project di dalam [CanvasEditor]
 * (mendukung pinch-zoom, pan, dan text layer sederhana yang bisa
 * di-drag). Efek teks & fitur seleksi/inpainting akan ditambahkan pada
 * tahap pengembangan berikutnya.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectName: String,
    baseImagePath: String,
    onBack: () -> Unit
) {
    val canvasState = remember(baseImagePath) { CanvasEditorState(baseImagePath) }
    var showAddTextDialog by remember { mutableStateOf(false) }

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
            FloatingActionButton(onClick = { showAddTextDialog = true }) {
                Icon(Icons.Default.TextFields, contentDescription = "Tambah teks")
            }
        }
    ) { padding ->
        CanvasEditor(
            state = canvasState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) { path, contentScale, imgModifier ->
            AsyncImage(
                model = path,
                contentDescription = projectName,
                contentScale = contentScale,
                modifier = imgModifier
            )
        }
    }

    if (showAddTextDialog) {
        AddTextDialog(
            onConfirm = { text ->
                showAddTextDialog = false
                if (text.isNotBlank()) canvasState.addTextLayer(text)
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
