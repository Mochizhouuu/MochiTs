package com.mochits.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mochits.inpaint.ModelManager
import kotlinx.coroutines.launch

/**
 * Halaman Pengaturan (Settings Screen): Memungkinkan user mengunduh / mengelola model AI LaMa Inpaint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val downloadState by modelManager.downloadState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = "Model Inpainting AI (LaMa)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Model LaMa membutuhkan berkas tflite opsional untuk pembersihan background tingkat lanjut.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Model LaMa INT8 (.tflite)", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (downloadState.isDownloaded) "Status: Terunduh & Siap" else "Status: Belum diunduh",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (downloadState.isDownloading) {
                            CircularProgressIndicator(
                                progress = { downloadState.progressPercent / 100f },
                                modifier = Modifier.size(36.dp)
                            )
                        } else if (downloadState.isDownloaded) {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    modelManager.deleteModel()
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus Model")
                            }
                        } else {
                            IconButton(onClick = {
                                coroutineScope.launch {
                                    modelManager.downloadModel()
                                }
                            }) {
                                Icon(Icons.Default.CloudDownload, contentDescription = "Unduh Model")
                            }
                        }
                    }

                    if (downloadState.isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Mengunduh... ${downloadState.progressPercent}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    downloadState.errorMessage?.let { errorMsg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
