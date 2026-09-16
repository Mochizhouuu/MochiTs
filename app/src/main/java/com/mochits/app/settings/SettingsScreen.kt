package com.mochits.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochits.app.home.HomeViewModel
import com.mochits.app.imaging.LaMaDownloadErrorInfo
import com.mochits.app.ui.theme.AppThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeViewModel,
    lamaModelManager: com.mochits.app.imaging.LaMaModelManager
) {
    val context = LocalContext.current
    val currentFolderUri by viewModel.defaultExportFolderUri.collectAsState()
    val formattedDetail by remember { mutableStateOf<String?>(null) }
    val currentTheme by viewModel.themeMode.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tema", "Model", "Style", "Font", "Output")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Pengaturan MochiTs",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Tema Aplikasi",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pilih tema warna aplikasi sesuai kenyamanan Anda:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ThemeOptionCard(
                                label = "Ikuti Sistem",
                                selected = currentTheme == AppThemeMode.SYSTEM,
                                onClick = { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                            )
                            ThemeOptionCard(
                                label = "Lavender Terang (Light)",
                                selected = currentTheme == AppThemeMode.LIGHT,
                                onClick = { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                            )
                            ThemeOptionCard(
                                label = "Lavender Gelap (Dark)",
                                selected = currentTheme == AppThemeMode.DARK,
                                onClick = { viewModel.setThemeMode(AppThemeMode.DARK) }
                            )
                        }
                    }
                    1 -> {
                        val modelStatus by lamaModelManager.modelStatus.collectAsState()
                        val downloadProgress by lamaModelManager.downloadProgress.collectAsState()
                        val lastDownloadError by lamaModelManager.lastDownloadError.collectAsState()
                        val coroutineScope = rememberCoroutineScope()

                        var showErrorDialog by remember { mutableStateOf(false) }

                        if (showErrorDialog && lastDownloadError != null) {
                            DownloadErrorDialog(
                                context = context,
                                formattedDetail = lastDownloadError?.toFormattedString() ?: "",
                                errorInfo = lastDownloadError!!,
                                onDismiss = { showErrorDialog = false }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Model Inpainting",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Telea (OpenCV)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text("Bawaan Sistem (Aktif)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text("Terpasang", modifier = Modifier.padding(4.dp))
                                    }
                                }
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("LaMa AI Neural Inpaint", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                            val statusText = when (modelStatus) {
                                                com.mochits.app.imaging.LaMaModelStatus.DOWNLOADED -> "Model AI Kualitas Tinggi (~196MB) — Terpasang"
                                                com.mochits.app.imaging.LaMaModelStatus.DOWNLOADING -> "Mengunduh... ${(downloadProgress * 100).toInt()}%"
                                                com.mochits.app.imaging.LaMaModelStatus.CORRUPTED_ERROR -> "File model rusak / gagal diunduh"
                                                com.mochits.app.imaging.LaMaModelStatus.NOT_DOWNLOADED -> "Model AI Kualitas Tinggi (~196MB)"
                                            }
                                            val statusColor = if (modelStatus == com.mochits.app.imaging.LaMaModelStatus.CORRUPTED_ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                            Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                                        }

                                        when (modelStatus) {
                                            com.mochits.app.imaging.LaMaModelStatus.DOWNLOADED -> {
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text("Terpasang", modifier = Modifier.padding(4.dp))
                                                }
                                            }
                                            com.mochits.app.imaging.LaMaModelStatus.DOWNLOADING -> {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            }
                                            com.mochits.app.imaging.LaMaModelStatus.CORRUPTED_ERROR -> {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    if (lastDownloadError != null) {
                                                        IconButton(onClick = { showErrorDialog = true }) {
                                                            Icon(Icons.Default.Info, contentDescription = "Detail Error", tint = MaterialTheme.colorScheme.error)
                                                        }
                                                    }
                                                    Button(
                                                        onClick = {
                                                            coroutineScope.launch {
                                                                val success = lamaModelManager.downloadModel()
                                                                if (success) {
                                                                    Toast.makeText(context, "Model LaMa berhasil diunduh!", Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    showErrorDialog = true
                                                                }
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                                    ) {
                                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Unduh Ulang")
                                                    }
                                                }
                                            }
                                            com.mochits.app.imaging.LaMaModelStatus.NOT_DOWNLOADED -> {
                                                IconButton(onClick = {
                                                    coroutineScope.launch {
                                                        val success = lamaModelManager.downloadModel()
                                                        if (success) {
                                                            Toast.makeText(context, "Model LaMa berhasil diunduh!", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            showErrorDialog = true
                                                        }
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Download, contentDescription = "Unduh Model")
                                                }
                                            }
                                        }
                                    }

                                    if (modelStatus == com.mochits.app.imaging.LaMaModelStatus.DOWNLOADING) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { downloadProgress },
                                            modifier = Modifier.fillMaxWidth().height(6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        val styleEntryPoint = remember {
                            dagger.hilt.android.EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                StylePresetEntryPoint::class.java
                            )
                        }
                        val styleRepo = remember { styleEntryPoint.stylePresetRepository() }
                        val stylePresets by styleRepo.presets.collectAsState()
                        val hiddenBuiltIns by styleRepo.hiddenBuiltInIds.collectAsState()
                        val pinnedIds by styleRepo.pinnedPresetIds.collectAsState()
                        val styleScope = rememberCoroutineScope()
                        var isImportingPresets by remember { mutableStateOf(false) }
                        var presetToDelete by remember { mutableStateOf<com.mochits.app.model.TextStylePreset?>(null) }

                        val importPresetPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent()
                        ) { uri ->
                            if (uri != null) {
                                isImportingPresets = true
                                styleScope.launch {
                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                                stream.bufferedReader().readText()
                                            } ?: throw IllegalArgumentException("File tidak bisa dibaca.")
                                        }.mapCatching { json -> styleRepo.importPresetsJson(json) }
                                    }
                                    isImportingPresets = false
                                    result
                                        .onSuccess { count ->
                                            Toast.makeText(context, "$count preset Style diimpor.", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { e ->
                                            Toast.makeText(context, e.message ?: "Gagal mengimpor preset.", Toast.LENGTH_LONG).show()
                                        }
                                }
                            }
                        }
                        val exportPresetPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.CreateDocument("application/json")
                        ) { uri ->
                            if (uri != null) {
                                styleScope.launch {
                                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                                stream.write(styleRepo.exportCustomsJson().toByteArray())
                                            } ?: throw IllegalStateException("Tujuan tidak bisa ditulis.")
                                        }.isSuccess
                                    }
                                    Toast.makeText(
                                        context,
                                        if (ok) "Preset Style diekspor." else "Gagal mengekspor preset.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Manajemen Style Presets",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Preset buatanmu di editor muncul di sini. Sematkan yang sering dipakai agar selalu paling atas, atau bagikan sebagai file JSON.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { importPresetPicker.launch("application/json") },
                                    enabled = !isImportingPresets,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    if (isImportingPresets) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Mengimpor...")
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Impor")
                                    }
                                }
                                Button(
                                    onClick = { exportPresetPicker.launch("mochits-style-presets.json") },
                                    enabled = stylePresets.any { !it.isBuiltIn },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Style, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Ekspor")
                                }
                            }
                            Text(
                                text = "Semua preset (${stylePresets.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (stylePresets.isEmpty()) {
                                Text(
                                    text = "Belum ada preset. Buat preset baru dari menu Style di editor.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        stylePresets.forEach { preset ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = preset.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = if (preset.isBuiltIn) {
                                                            "Bawaan • ${preset.fontName} ${preset.fontStyle}"
                                                        } else {
                                                            "${preset.fontName} ${preset.fontStyle} • ${preset.alignment} • ${preset.shape}"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (preset.isBuiltIn) {
                                                    Text(
                                                        text = "Bawaan",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(end = 8.dp)
                                                    )
                                                }
                                                val isPinned = pinnedIds.contains(preset.id)
                                                IconButton(
                                                    onClick = {
                                                        styleRepo.setPresetPinned(preset.id, !isPinned)
                                                    },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.PushPin,
                                                        contentDescription = if (isPinned) {
                                                            "Lepas ${preset.name} dari atas"
                                                        } else {
                                                            "Sematkan ${preset.name} ke atas"
                                                        },
                                                        tint = if (isPinned) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        }
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { presetToDelete = preset },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Hapus ${preset.name}",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (hiddenBuiltIns.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        styleRepo.restoreAllBuiltIns()
                                        Toast.makeText(context, "Preset bawaan dikembalikan.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Kembalikan preset bawaan (${hiddenBuiltIns.size})")
                                }
                            }
                        }
                        val doomedPreset = presetToDelete
                        if (doomedPreset != null) {
                            AlertDialog(
                                onDismissRequest = { presetToDelete = null },
                                title = { Text("Hapus Preset") },
                                text = {
                                    Text(
                                        if (doomedPreset.isBuiltIn) {
                                            "Sembunyikan preset bawaan \"${doomedPreset.name}\"? Bisa dikembalikan lagi lewat tombol di bawah daftar."
                                        } else {
                                            "Hapus preset \"${doomedPreset.name}\"? Layer teks yang sudah memakainya tidak berubah."
                                        }
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            styleRepo.deletePreset(doomedPreset.id)
                                            presetToDelete = null
                                            Toast.makeText(
                                                context,
                                                if (doomedPreset.isBuiltIn) {
                                                    "Preset \"${doomedPreset.name}\" disembunyikan."
                                                } else {
                                                    "Preset \"${doomedPreset.name}\" dihapus."
                                                },
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ) {
                                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { presetToDelete = null }) {
                                        Text("Batal")
                                    }
                                }
                            )
                        }
                    }
                    3 -> {
                        val coroutineScope = rememberCoroutineScope()
                        val customFonts by viewModel.customFonts.collectAsState()
                        var isImporting by remember { mutableStateOf(false) }
                        var fontError by remember { mutableStateOf<String?>(null) }
                        var fontToDelete by remember { mutableStateOf<com.mochits.app.font.FontItem?>(null) }
                        val fontPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.GetContent()
                        ) { uri ->
                            if (uri != null) {
                                isImporting = true
                                fontError = null
                                coroutineScope.launch {
                                    val name = queryDisplayName(context, uri)
                                    val result = viewModel.importCustomFont(uri, name)
                                    isImporting = false
                                    result
                                        .onSuccess {
                                            Toast.makeText(context, "Font \"${it.name}\" ditambahkan.", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { e ->
                                            fontError = e.message ?: "Gagal mengimpor font."
                                        }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Font Manager",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tambahkan font kustom (.ttf / .otf) untuk typesetting komik:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { fontPicker.launch("*/*") },
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isImporting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Mengimpor...")
                                } else {
                                    Icon(Icons.Default.FontDownload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Tambah Font TTF/OTF Baru")
                                }
                            }
                            fontError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                text = "Font kustom terpasang (${customFonts.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (customFonts.isEmpty()) {
                                Text(
                                    text = "Belum ada font kustom. Font bawaan selalu tersedia di editor.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        customFonts.forEach { font ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = font.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = { fontToDelete = font },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Hapus ${font.name}",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val doomedFont = fontToDelete
                        if (doomedFont != null) {
                            AlertDialog(
                                onDismissRequest = { fontToDelete = null },
                                title = { Text("Hapus Font") },
                                text = { Text("Hapus \"${doomedFont.name}\" dari daftar font kustom? Layer teks yang memakainya akan kembali ke font default.") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            viewModel.deleteCustomFont(doomedFont)
                                            fontToDelete = null
                                            Toast.makeText(context, "Font \"${doomedFont.name}\" dihapus.", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { fontToDelete = null }) {
                                        Text("Batal")
                                    }
                                }
                            )
                        }
                    }
                    4 -> {
                        val currentFolderName by viewModel.defaultExportFolderName.collectAsState()
                        val isFolderValid = remember(currentFolderUri) {
                            viewModel.isFolderValid(currentFolderUri)
                        }

                        val folderPicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocumentTree()
                        ) { uri ->
                            uri?.let {
                                viewModel.updateExportFolderUri(it)
                                Toast.makeText(context, "Folder output default berhasil disimpan.", Toast.LENGTH_SHORT).show()
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Folder Output Default (Export)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tentukan folder default di penyimpanan perangkat Anda untuk menyimpan hasil ekspor gambar proyek.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Folder Aktif saat ini:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (currentFolderUri != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isFolderValid) Icons.Default.Folder else Icons.Default.FolderOff,
                                                contentDescription = null,
                                                tint = if (isFolderValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                            )
                                            Column {
                                                Text(
                                                    text = currentFolderName ?: currentFolderUri.toString(),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = if (isFolderValid) "Status: Siap Digunakan & Izin Aktif" else "Status: Folder Tidak Valid / Izin Hilang",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isFolderValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "Belum ada folder default yang dipilih.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = { folderPicker.launch(null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (currentFolderUri != null) "Ganti Folder Output" else "Pilih Folder Output")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadErrorDialog(
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current,
    formattedDetail: String? = null,
    errorInfo: LaMaDownloadErrorInfo,
    onDismiss: () -> Unit
) {

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Gagal Mengunduh Model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Terjadi kesalahan saat mengunduh file model LaMa AI. Berikut detail teknis kegagalan:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = formattedDetail ?: errorInfo.toFormattedString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("LaMa Model Download Error", formattedDetail)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Detail error berhasil disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Salin Detail Error")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}

/** Akses singleton repo preset dari layar Pengaturan (tanpa ViewModel baru). */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface StylePresetEntryPoint {
    fun stylePresetRepository(): com.mochits.app.style.StylePresetRepository
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}

@Composable
fun ThemeOptionCard(    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
