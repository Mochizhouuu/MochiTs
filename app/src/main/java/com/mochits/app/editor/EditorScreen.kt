package com.mochits.app.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Editor screen — saat ini baru menampilkan base image project sebagai
 * placeholder. Canvas Editor (layer, seleksi, inpainting, teks) akan
 * dibangun di tahap selanjutnya pada module :core-canvas dkk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectName: String,
    baseImagePath: String,
    onBack: () -> Unit
) {
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
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = baseImagePath,
                contentDescription = projectName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
