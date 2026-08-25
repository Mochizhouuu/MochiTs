package com.mochits.app.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochits.inpaint.ModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val modelManager = remember { ModelManager(context) }
    val downloadState by modelManager.downloadState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showFontNameDialog by remember { mutableStateOf<Uri?>(null) }
    var locationInput by remember(uiState.exportLocation) { mutableStateOf(uiState.exportLocation) }

    // Launcher for Font file (.ttf, .otf)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            showFontNameDialog = uri
        }
    }

    // Launcher for Exporting project backup archive (.ctproj)
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportProjects(uri)
        }
    }

    // Launcher for Importing project backup archive (.ctproj)
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importProjects(uri)
        }
    }

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
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Pengaturan Lokasi Output & Ekspor Gambar
                item {
                    SectionHeader(title = "Pengaturan Output Ekspor", icon = Icons.Default.FolderSpecial)
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Lokasi Direktori Simpan Hasil", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = locationInput,
                                onValueChange = {
                                    locationInput = it
                                    viewModel.setExportLocation(it)
                                },
                                singleLine = true,
                                label = { Text("Sub-folder Penyimpanan Galeri") },
                                placeholder = { Text("Pictures/MochiTs") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            HorizontalDivider()

                            Text("Kualitas Hasil Ekspor Gambar (${uiState.exportQuality}%)", style = MaterialTheme.typography.titleSmall)
                            Slider(
                                value = uiState.exportQuality.toFloat(),
                                onValueChange = { viewModel.setExportQuality(it.toInt()) },
                                valueRange = 50f..100f,
                                steps = 9,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Section 2: Font Manager
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader(title = "Manajemen Font (Custom Fonts)", icon = Icons.Default.FontDownload)
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Tambah Font Custom", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Impor berkas font .ttf atau .otf ke aplikasi",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(onClick = { fontPickerLauncher.launch("*/*") }) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Tambah")
                                }
                            }

                            if (uiState.fonts.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                Text("Font Terpasang (${uiState.fonts.size}):", style = MaterialTheme.typography.labelLarge)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }

                items(uiState.fonts, key = { it.path }) { fontItem ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.TextFields, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(fontItem.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.deleteFont(fontItem.path) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus Font", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // Section 3: Backup & Restore Projek
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader(title = "Backup & Restore Projek", icon = Icons.Default.Backup)
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Ekspor / Backup Projek", style = MaterialTheme.typography.titleSmall)
                                    Text("Simpan seluruh projek ke dalam file cadangan (.ctproj)", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { exportBackupLauncher.launch("mochits_backup_${System.currentTimeMillis()}.ctproj") }) {
                                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Ekspor")
                                }
                            }

                            HorizontalDivider()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Impor / Restore Projek", style = MaterialTheme.typography.titleSmall)
                                    Text("Pulihkan projek dari file cadangan", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { importBackupLauncher.launch("*/*") }) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Impor")
                                }
                            }
                        }
                    }
                }

                // Section 4: Engine & Model Inpainting AI
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader(title = "Mesin Pembersih Inpainting", icon = Icons.Default.AutoFixHigh)
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Telea Status
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("1. Telea Inpainter (OpenCV)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Mesin bawaan aplikasi. Cepat dan siap digunakan 100% tanpa perlu mengunduh file tambahan.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }

                            HorizontalDivider()

                            // LaMa Status & Download
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("2. LaMa AI Neural Network (.tflite)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = if (downloadState.isDownloaded) "Status: Terunduh & Siap Digunakan" else "Status: Belum diunduh (Model Opsional AI)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (downloadState.isDownloading) {
                                    CircularProgressIndicator(
                                        progress = { downloadState.progressPercent / 100f },
                                        modifier = Modifier.size(36.dp)
                                    )
                                } else if (downloadState.isDownloaded) {
                                    IconButton(onClick = {
                                        coroutineScope.launch { modelManager.deleteModel() }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Hapus Model", tint = MaterialTheme.colorScheme.error)
                                    }
                                } else {
                                    IconButton(onClick = {
                                        coroutineScope.launch { modelManager.downloadModel() }
                                    }) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = "Unduh Model", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            if (downloadState.isDownloading) {
                                LinearProgressIndicator(
                                    progress = { downloadState.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Mengunduh... ${downloadState.progressPercent}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    showFontNameDialog?.let { uri ->
        FontImportNameDialog(
            onConfirm = { name ->
                viewModel.importFont(uri, name)
                showFontNameDialog = null
            },
            onDismiss = { showFontNameDialog = null }
        )
    }

    uiState.message?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            confirmButton = {
                TextButton(onClick = viewModel::clearMessage) { Text("OK") }
            },
            title = { Text("Informasi") },
            text = { Text(msg) }
        )
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FontImportNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.ifBlank { "CustomFont_${System.currentTimeMillis() % 1000}" }) }
            ) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        title = { Text("Nama Font") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Contoh: AnimeComicFont") }
            )
        }
    )
}
