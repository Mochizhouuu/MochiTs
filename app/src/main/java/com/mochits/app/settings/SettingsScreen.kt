package com.mochits.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TextFields
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
    val tabs = listOf("Tampilan", "Hapus Objek", "Gaya", "Huruf", "Simpan", "Naskah")
    val tabIcons = listOf(
        Icons.Default.Palette,
        Icons.Default.CleaningServices,
        Icons.Default.Style,
        Icons.Default.TextFields,
        Icons.Default.Folder,
        Icons.Default.Description
    )
    val settingsScope = rememberCoroutineScope()
    var scriptTestSummary by remember { mutableStateOf<com.mochits.app.script.ScriptTestSummary?>(null) }
    var showScriptTest by remember { mutableStateOf(false) }
    val scriptTestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            settingsScope.launch {
                val summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val repo = com.mochits.app.script.ScriptMappingRepository(context)
                        .also { it.reload() }
                    com.mochits.app.script.ScriptTestSummary.build(
                        context, uri, repo.getMappings(), repo.getSeparator()
                    )
                }
                if (summary == null) {
                    Toast.makeText(
                        context,
                        "Gagal membaca file. Pilih .txt atau .docx.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    scriptTestSummary = summary
                    showScriptTest = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "MochiTs",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = "Pengaturan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            Icon(
                                imageVector = tabIcons[index],
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        text = { Text(title, fontSize = 12.sp) }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsPageHeader(
                                icon = Icons.Default.Palette,
                                title = "Tampilan",
                                subtitle = "Atur warna aplikasi dan buka panduan."
                            )
                            Text(
                                text = "Tema aplikasi",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            ThemeOptionCard(
                                label = "Ikuti Sistem",
                                description = "Terang atau gelap mengikuti pengaturan HP.",
                                selected = currentTheme == AppThemeMode.SYSTEM,
                                onClick = { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                            )
                            ThemeOptionCard(
                                label = "Terang",
                                description = "Latar putih dengan aksen biru.",
                                selected = currentTheme == AppThemeMode.LIGHT,
                                onClick = { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                            )
                            ThemeOptionCard(
                                label = "Gelap",
                                description = "Nyaman dipakai di ruangan redup.",
                                selected = currentTheme == AppThemeMode.DARK,
                                onClick = { viewModel.setThemeMode(AppThemeMode.DARK) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Bantuan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Cara memakai fitur, membaca naskah, dan mengekspor.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { openDocs(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buka Panduan")
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            "MochiTs 1.0.0",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "Editor teks & gambar untuk komik.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
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
                            SettingsPageHeader(
                                icon = Icons.Default.CleaningServices,
                                title = "Hapus Objek",
                                subtitle = "Pilih cara mengisi area yang sudah diseleksi."
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
                                        Text("Telea", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                        Text("Cepat dan tanpa unduhan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text("Siap", modifier = Modifier.padding(4.dp))
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
                                            Text("LaMa AI", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                            val statusText = when (modelStatus) {
                                                com.mochits.app.imaging.LaMaModelStatus.DOWNLOADED -> "Hasil lebih halus • sudah tersimpan"
                                                com.mochits.app.imaging.LaMaModelStatus.DOWNLOADING -> "Mengunduh model ${(downloadProgress * 100).toInt()}%"
                                                com.mochits.app.imaging.LaMaModelStatus.CORRUPTED_ERROR -> "Unduhan gagal • ulangi"
                                                com.mochits.app.imaging.LaMaModelStatus.NOT_DOWNLOADED -> "Untuk latar rumit • unduh ~196 MB"
                                            }
                                            val statusColor = if (modelStatus == com.mochits.app.imaging.LaMaModelStatus.CORRUPTED_ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                            Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                                        }

                                        when (modelStatus) {
                                            com.mochits.app.imaging.LaMaModelStatus.DOWNLOADED -> {
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text("Siap", modifier = Modifier.padding(4.dp))
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
                                                                    Toast.makeText(context, "Model LaMa siap dipakai.", Toast.LENGTH_SHORT).show()
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
                                                Button(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            val success = lamaModelManager.downloadModel()
                                                            if (success) {
                                                                Toast.makeText(context, "Model LaMa siap dipakai.", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                showErrorDialog = true
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Unduh")
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
                                        if (ok) "Gaya diekspor." else "Gagal mengekspor gaya.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsPageHeader(
                                icon = Icons.Default.Style,
                                title = "Gaya Tersimpan",
                                subtitle = "Simpan kombinasi huruf dan warna untuk dipakai lagi."
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
                                text = "Daftar gaya (${stylePresets.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (stylePresets.isEmpty()) {
                                Text(
                                    text = "Belum ada gaya tersimpan. Buat dari panel Gaya di editor.",
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
                                                    val styleLabel = when (preset.fontStyle) {
                                                        "Bold" -> "Tebal"
                                                        "Italic" -> "Miring"
                                                        "BoldItalic" -> "Tebal + Miring"
                                                        else -> "Biasa"
                                                    }
                                                    Text(
                                                        text = if (preset.isBuiltIn) {
                                                            "Bawaan • ${preset.fontName} • $styleLabel"
                                                        } else {
                                                            "${preset.fontName} • $styleLabel"
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
                                        Toast.makeText(context, "Gaya bawaan dikembalikan.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Kembalikan gaya bawaan (${hiddenBuiltIns.size})")
                                }
                            }
                        }
                        val doomedPreset = presetToDelete
                        if (doomedPreset != null) {
                            AlertDialog(
                                onDismissRequest = { presetToDelete = null },
                                title = { Text("Hapus Gaya") },
                                text = {
                                    Text(
                                        if (doomedPreset.isBuiltIn) {
                                            "Sembunyikan gaya bawaan \"${doomedPreset.name}\"? Bisa dikembalikan lagi."
                                        } else {
                                            "Hapus gaya \"${doomedPreset.name}\"? Teks yang sudah memakainya tidak berubah."
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
                                                    "Gaya \"${doomedPreset.name}\" disembunyikan."
                                                } else {
                                                    "Gaya \"${doomedPreset.name}\" dihapus."
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
                                            Toast.makeText(context, "Huruf \"${it.name}\" ditambahkan.", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { e ->
                                            fontError = e.message ?: "Gagal mengimpor huruf."
                                        }
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsPageHeader(
                                icon = Icons.Default.TextFields,
                                title = "Huruf",
                                subtitle = "Tambah huruf dari file .ttf atau .otf."
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
                                    Text("Tambah Huruf")
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
                                text = "Huruf terpasang (${customFonts.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (customFonts.isEmpty()) {
                                Text(
                                    text = "Belum ada huruf kustom. Huruf bawaan tetap bisa dipakai.",
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
                                title = { Text("Hapus Huruf") },
                                text = { Text("Hapus \"${doomedFont.name}\"? Teks yang memakainya akan kembali ke huruf bawaan.") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            viewModel.deleteCustomFont(doomedFont)
                                            fontToDelete = null
                                            Toast.makeText(context, "Huruf \"${doomedFont.name}\" dihapus.", Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(context, "Folder simpan disimpan.", Toast.LENGTH_SHORT).show()
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsPageHeader(
                                icon = Icons.Default.Folder,
                                title = "Folder Simpan",
                                subtitle = "Tentukan tempat hasil ekspor disimpan."
                            )

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Folder aktif",
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
                                                    text = if (isFolderValid) "Siap digunakan" else "Izin folder hilang",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isFolderValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "Belum ada folder yang dipilih.",
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
                                Text(if (currentFolderUri != null) "Ganti Folder" else "Pilih Folder")
                            }
                        }
                    }
                    5 -> {
                        val scriptEntryPoint = remember {
                            dagger.hilt.android.EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                ScriptMappingEntryPoint::class.java
                            )
                        }
                        val scriptRepo = remember { scriptEntryPoint.scriptMappingRepository() }
                        val mappings by scriptRepo.mappings.collectAsState()
                        val separator by scriptRepo.separator.collectAsState()
                        val scriptSettingsEntryPoint = remember {
                            dagger.hilt.android.EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                ScriptSettingsEntryPoint::class.java
                            )
                        }
                        val scriptSettingsRepo = remember { scriptSettingsEntryPoint.scriptSettingsRepository() }
                        val autoRestore by scriptSettingsRepo.autoRestore.collectAsState()
                        val styleEntryPoint5 = remember {
                            dagger.hilt.android.EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                StylePresetEntryPoint::class.java
                            )
                        }
                        val scriptStyleRepo = remember { styleEntryPoint5.stylePresetRepository() }
                        val stylePresets by scriptStyleRepo.presets.collectAsState()
                        var newSymbol by remember { mutableStateOf("") }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsPageHeader(
                                icon = Icons.Default.Description,
                                title = "Naskah",
                                subtitle = "Atur cara membaca file naskah terjemahan."
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Buka naskah terakhir",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Lanjut dari halaman dan baris terakhir.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = autoRestore,
                                        onCheckedChange = { scriptSettingsRepo.setAutoRestore(it) }
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    scriptTestLauncher.launch(
                                        arrayOf(
                                            com.mochits.app.script.ScriptFileReader.TXT_MIME,
                                            com.mochits.app.script.ScriptFileReader.DOCX_MIME
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Uji File Naskah")
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Pemisah Halaman",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Penanda awal halaman baru.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = separator,
                                        onValueChange = { scriptRepo.setSeparator(it) },
                                        label = { Text("Pola") },
                                        placeholder = { Text("P\\d+") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Text(
                                text = "Gaya per Simbol",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Awalan baris menentukan gaya teksnya.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            mappings.forEach { entry ->
                                ScriptMappingRow(
                                    entry = entry,
                                    presets = stylePresets,
                                    onUpdate = { updated ->
                                        scriptRepo.setMappings(
                                            mappings.map { if (it.symbol == entry.symbol) updated else it }
                                        )
                                    },
                                    onDelete = {
                                        scriptRepo.setMappings(
                                            mappings.filter { it.symbol != entry.symbol }
                                        )
                                    }
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newSymbol,
                                    onValueChange = { newSymbol = it },
                                    label = { Text("Simbol") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    if (newSymbol.isNotBlank()) {
                                        scriptRepo.setMappings(
                                            mappings + com.mochits.app.script.ScriptSymbolEntry(newSymbol.trim())
                                        )
                                        newSymbol = ""
                                    }
                                }) {
                                    Text("Tambah")
                                }
                            }
                            OutlinedButton(onClick = {
                                scriptRepo.setMappings(
                                    com.mochits.app.script.ScriptMappingRepository.defaultMappings()
                                )
                                scriptRepo.setSeparator(
                                    com.mochits.app.script.ScriptMappingRepository.DEFAULT_SEPARATOR
                                )
                            }) {
                                Text("Kembalikan Bawaan")
                            }
                        }
                    }
                }
            }
        }
    }

    val testSummary = scriptTestSummary
    if (showScriptTest && testSummary != null) {
        AlertDialog(
            onDismissRequest = { showScriptTest = false },
            title = { Text("Uji Naskah") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = testSummary.fileName ?: "File",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${testSummary.pages} halaman • ${testSummary.entries} baris • ${testSummary.linkedEntries} tersambung",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (testSummary.symbolCounts.isNotEmpty()) {
                        Text(
                            "Simbol: " + testSummary.symbolCounts.joinToString(", ") {
                                "${it.first} ×${it.second}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    testSummary.warnings.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScriptTest = false }) { Text("Tutup") }
            }
        )
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
                    text = "Unduhan model gagal. Detail teknis:",
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

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ScriptMappingEntryPoint {
    fun scriptMappingRepository(): com.mochits.app.script.ScriptMappingRepository
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ScriptSettingsEntryPoint {
    fun scriptSettingsRepository(): com.mochits.app.script.ScriptSettingsRepository
}

/** Satu baris pemetaan simbol -> preset + flag sambung + hapus. */
@Composable
private fun ScriptMappingRow(
    entry: com.mochits.app.script.ScriptSymbolEntry,
    presets: List<com.mochits.app.model.TextStylePreset>,
    onUpdate: (com.mochits.app.script.ScriptSymbolEntry) -> Unit,
    onDelete: () -> Unit
) {
    var symText by remember(entry.symbol) { mutableStateOf(entry.symbol) }
    var menuOpen by remember { mutableStateOf(false) }
    val currentPreset = presets.find { it.id == entry.presetId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = symText,
                    onValueChange = {
                        symText = it
                        if (it.isNotBlank()) onUpdate(entry.copy(symbol = it.trim()))
                    },
                    label = { Text("Simbol") },
                    singleLine = true,
                    modifier = Modifier.weight(0.9f)
                )
                Box(modifier = Modifier.weight(1.6f)) {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            currentPreset?.name ?: "Tanpa gaya",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tanpa gaya") },
                            onClick = {
                                menuOpen = false
                                onUpdate(entry.copy(presetId = ""))
                            }
                        )
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (if (preset.id == entry.presetId) "✓ " else "") + preset.name,
                                        maxLines = 1
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onUpdate(entry.copy(presetId = preset.id))
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Hapus simbol",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = entry.append,
                    onCheckedChange = {
                        onUpdate(entry.copy(append = it, linked = if (it) false else entry.linked))
                    }
                )
                Text(
                    "Gabung ke baris sebelumnya",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = entry.linked,
                    onCheckedChange = {
                        onUpdate(entry.copy(linked = it, append = if (it) false else entry.append))
                    }
                )
                Text(
                    "Balon baru, tersambung (//)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
private fun SettingsPageHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ThemeOptionCard(
    label: String,
    description: String = "",
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
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun openDocs(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://mochits.my.id/"))
        )
    } catch (_: Exception) {
        Toast.makeText(context, "Tidak bisa membuka panduan. Browser tidak tersedia.", Toast.LENGTH_SHORT).show()
    }
}
