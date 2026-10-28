package com.mochits.app.home
import com.mochits.app.util.Logger

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectFolderEntity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenEditor: (String) -> Unit,
    onOpenProjects: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    incomingRevision: Int = 0
) {
    val projects by viewModel.projects.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFoldersDialog by remember { mutableStateOf(false) }
    var showHomeMenu by remember { mutableStateOf(false) }
    var pendingZip by remember { mutableStateOf<Pair<File, String>?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDelete by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val folderSummaries = remember(folders, projects) {
        buildFolderSummaries(folders, projects)
    }
    val recentProjects = remember(projects) { projects.take(5) }
    val selectedCount = selectedProjectIds.size + selectedFolderIds.size
    val visibleItemCount = folderSummaries.size + recentProjects.size
    val allVisibleSelected = selectedCount == visibleItemCount

    fun leaveSelection() {
        selectionMode = false
        selectedProjectIds = emptySet()
        selectedFolderIds = emptySet()
    }

    fun toggleProject(id: String) {
        selectedProjectIds = if (id in selectedProjectIds) selectedProjectIds - id else selectedProjectIds + id
    }

    fun toggleFolder(id: String) {
        selectedFolderIds = if (id in selectedFolderIds) selectedFolderIds - id else selectedFolderIds + id
    }

    BackHandler(enabled = selectionMode) {
        leaveSelection()
    }

    // File .mts / ZIP dari luar (buka atau bagikan): proses sekali saat tampil.
    LaunchedEffect(incomingRevision) {
        try {
            val prefs = context.getSharedPreferences("mochits_bundle_in", android.content.Context.MODE_PRIVATE)
            val path = prefs.getString("pending_bundle_path", null)
            if (path != null) {
                prefs.edit().remove("pending_bundle_path").apply()
                val id = viewModel.importBundleFile(java.io.File(path))
                if (id != null) {
                    android.widget.Toast.makeText(context, "Bundel diimpor.", android.widget.Toast.LENGTH_SHORT).show()
                    onOpenEditor(id)
                } else {
                    android.widget.Toast.makeText(context, "Gagal mengimpor bundel.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            val zipPath = prefs.getString("pending_zip_path", null)
            if (zipPath != null) {
                val zipName = prefs.getString("pending_zip_name", null) ?: "Impor.zip"
                prefs.edit()
                    .remove("pending_zip_path")
                    .remove("pending_zip_name")
                    .apply()
                val zipFile = File(zipPath)
                val previous = pendingZip?.first
                if (previous != null && previous.absolutePath != zipFile.absolutePath) {
                    try { previous.delete() } catch (_: Exception) {}
                }
                pendingZip = zipFile to zipName
            }
        } catch (_: Exception) {
        }
    }
    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    val bundleImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                viewModel.isLoading.value = true
                try {
                    val newId = viewModel.importBundleFrom(uri)
                    if (newId != null) {
                        toast("Bundel diimpor.")
                        onOpenEditor(newId)
                    } else {
                        toast("Gagal mengimpor bundel.")
                    }
                } finally {
                    viewModel.isLoading.value = false
                }
            }
        }
    }

    fun importPickedImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        coroutineScope.launch {
            viewModel.isLoading.value = true
            try {
                val result = viewModel.importImages(
                    uris = uris,
                    targetFolderId = null,
                    autoFolderName = "Impor ${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
                )
                if (result == null || result.projectIds.isEmpty()) {
                    toast("Gagal mengimpor gambar.")
                } else if (uris.size == 1 && result.folderId == null) {
                    onOpenEditor(result.projectIds.first())
                } else {
                    toast(
                        "${result.projectIds.size} proyek diimpor" +
                            (result.folderName?.let { " ke folder \"$it\"" } ?: "")
                    )
                    if (result.failed > 0) toast("${result.failed} gambar gagal.")
                }
            } catch (e: Exception) {
                Logger.e("Error: ${e.message}", e)
            } finally {
                viewModel.isLoading.value = false
            }
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris: List<Uri> -> importPickedImages(uris) }

    val fallbackContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> importPickedImages(uris) }

    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                viewModel.isLoading.value = true
                try {
                    val result = viewModel.importZip(uri)
                    when {
                        result == null -> toast("Gagal mengimpor ZIP.")
                        result.total == 0 -> toast("Tidak ada gambar/.mts yang bisa diimpor.")
                        else -> {
                            toast("ZIP diimpor: ${result.projects} gambar, ${result.bundles} proyek.")
                            if (result.failed > 0) toast("${result.failed} entri gagal.")
                        }
                    }
                } finally {
                    viewModel.isLoading.value = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "$selectedCount dipilih",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { leaveSelection() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Batal memilih"
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                if (allVisibleSelected) {
                                    selectedProjectIds = emptySet()
                                    selectedFolderIds = emptySet()
                                } else {
                                    selectedProjectIds = recentProjects.mapTo(LinkedHashSet()) { it.id }
                                    selectedFolderIds = folderSummaries.mapTo(LinkedHashSet()) { it.folder.id }
                                }
                            }
                        ) {
                            Text(if (allVisibleSelected) "Batal" else "Semua")
                        }
                        IconButton(
                            enabled = selectedCount > 0,
                            onClick = { showBulkDelete = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Hapus item terpilih",
                                tint = if (selectedCount == 0) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "MochiTs",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.8).sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = "Editor teks & gambar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showHomeMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Menu lainnya",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = showHomeMenu,
                                onDismissRequest = { showHomeMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Impor ZIP") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showHomeMenu = false
                                        zipImportLauncher.launch(
                                            arrayOf(
                                                "application/zip",
                                                "application/x-zip-compressed",
                                                "application/octet-stream"
                                            )
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Kelola folder") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showHomeMenu = false
                                        showFoldersDialog = true
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Pengaturan") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showHomeMenu = false
                                        onOpenSettings()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        bottomBar = {
            if (!selectionMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            text = "NEW PROJECT",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            bundleImportLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream"
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            text = "OPEN PROJECT",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                    }
                    Button(
                        onClick = {
                            try {
                                galleryPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } catch (e: Exception) {
                                fallbackContentLauncher.launch(arrayOf("image/*"))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 8.dp)
                    ) {
                        Text(
                            text = "IMPORT IMAGE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Projects",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    IconButton(onClick = onOpenProjects) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Lihat semua proyek",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (folderSummaries.isEmpty() && recentProjects.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Belum Ada Proyek",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 8.dp)
                    ) {
                        items(folderSummaries, key = { "folder:${it.folder.id}" }) { summary ->
                            FolderCard(
                                summary = summary,
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(120.dp),
                                selected = summary.folder.id in selectedFolderIds,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) toggleFolder(summary.folder.id)
                                    else onOpenFolder(summary.folder.id)
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedFolderIds = setOf(summary.folder.id)
                                    } else {
                                        toggleFolder(summary.folder.id)
                                    }
                                }
                            )
                        }
                        items(recentProjects, key = { "project:${it.id}" }) { project ->
                            RecentProjectCard(
                                project = project,
                                selected = project.id in selectedProjectIds,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) toggleProject(project.id)
                                    else onOpenEditor(project.id)
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedProjectIds = setOf(project.id)
                                    } else {
                                        toggleProject(project.id)
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Memuat & Mengimpor Gambar...",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, width, height, isTransparent, backgroundColor ->
                showCreateDialog = false
                coroutineScope.launch {
                    try {
                        val newId = viewModel.createProject(
                            title = title,
                            width = width,
                            height = height,
                            imageUri = null,
                            isTransparent = isTransparent,
                            backgroundColor = backgroundColor,
                            folderId = null
                        )
                        if (newId.isNotBlank()) {
                            onOpenEditor(newId)
                        }
                    } catch (e: Exception) {
                        Logger.e("Error: ${e.message}", e)
                    }
                }
            }
        )
    }

    if (showBulkDelete && selectedCount > 0) {
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text("Hapus item terpilih") },
            text = {
                val projectCount = selectedProjectIds.size
                val folderCount = selectedFolderIds.size
                Text(
                    when {
                        projectCount > 0 && folderCount > 0 ->
                            "Hapus $projectCount proyek dan $folderCount folder? Proyek di dalam folder yang dihapus tidak ikut terhapus."
                        folderCount > 0 ->
                            "Hapus $folderCount folder? Proyek di dalamnya tidak ikut terhapus."
                        else ->
                            "Hapus $projectCount proyek? Tindakan ini tidak bisa dibatalkan."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val projectIds = selectedProjectIds
                        val folderIds = selectedFolderIds
                        viewModel.deleteProjects(projectIds)
                        viewModel.deleteFolders(folderIds)
                        toast("${projectIds.size + folderIds.size} item dihapus.")
                        showBulkDelete = false
                        leaveSelection()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDelete = false }) {
                    Text("Batal")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showFoldersDialog) {
        FoldersDialog(
            folders = folders,
            projects = projects,
            onDismiss = { showFoldersDialog = false },
            onCreate = { name -> viewModel.createFolder(name) },
            onRename = { id, name -> viewModel.renameFolder(id, name) },
            onDelete = { id -> viewModel.deleteFolder(id) }
        )
    }

    pendingZip?.let { (file, name) ->
        AlertDialog(
            onDismissRequest = {
                try { file.delete() } catch (_: Exception) {}
                pendingZip = null
            },
            title = { Text("Impor ZIP", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Text(
                    "Impor \"$name\"?\n\n" +
                        "Gambar di root ZIP menjadi folder bernama ZIP; subfolder menjadi folder sendiri. " +
                        "File .mts di dalamnya ikut diimpor sebagai proyek."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingZip = null
                        coroutineScope.launch {
                            viewModel.isLoading.value = true
                            try {
                                val result = viewModel.importZip(Uri.fromFile(file), name)
                                when {
                                    result == null -> toast("Gagal mengimpor ZIP.")
                                    result.total == 0 -> toast("Tidak ada gambar/.mts yang bisa diimpor.")
                                    else -> {
                                        toast("ZIP diimpor: ${result.projects} gambar, ${result.bundles} proyek.")
                                        if (result.failed > 0) toast("${result.failed} entri gagal.")
                                    }
                                }
                            } finally {
                                viewModel.isLoading.value = false
                            }
                        }
                    }
                ) {
                    Text("Impor")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    try { file.delete() } catch (_: Exception) {}
                    pendingZip = null
                }) {
                    Text("Batal")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentProjectCard(
    project: ProjectEntity,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = Modifier
            .width(120.dp)
            .height(120.dp)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ProjectThumbnail(
                path = project.thumbnailPath,
                contentDescription = project.title,
                modifier = Modifier.fillMaxSize()
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                        )
                    )
                    .padding(start = 10.dp, end = 10.dp, top = 24.dp, bottom = 8.dp)
            ) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selectionMode) {
                SelectionMark(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}

/**
 * Baris slider satu kanal warna (R/G/B) untuk color picker New Project.
 * Baris gradien dari 0 -> nilai kanal penuh memberi konteks; nilai
 * ditulis 3 digit sehingga tetap sejajar.
 */
@Composable
private fun ChannelSliderRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    val (r, g, b) = when (label) {
        "R" -> Triple(255, 0, 0)
        "G" -> Triple(0, 255, 0)
        else -> Triple(0, 0, 255)
    }
    val brush = Brush.linearGradient(
        listOf(
            Color(0.0f, 0.0f, 0.0f),
            Color(r / 255f, g / 255f, b / 255f)
        )
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = Color(r / 255f, g / 255f, b / 255f),
                activeTrackColor = Color(r / 255f, g / 255f, b / 255f)
            ),
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
        )
        Text(
            text = "%03d".format(value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
    }
}

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, width: Int, height: Int, isTransparent: Boolean, backgroundColor: Int) -> Unit
) {
    var title by remember { mutableStateOf("Proyek Baru") }
    var widthText by remember { mutableStateOf("1080") }
    var heightText by remember { mutableStateOf("1920") }
    var isTransparent by remember { mutableStateOf(false) }

    // Palet latar: netral untuk konten + warna lembut untuk latangan
    // komik berwarna. Enam warna per baris agar tetap muat di dialog.
    val presetColors = listOf(
        Color.White,
        Color(0xFFE0E0E0), // abu muda
        Color(0xFF9E9E9E), // abu
        Color.Black,
        Color(0xFFFFFDD0), // cream
        Color(0xFFF5E3C3), // beige kertas
        Color(0xFFFFF3B1), // kuning muda
        Color(0xFFFFD9C7), // peach
        Color(0xFFFBD5E0), // pink pastel
        Color(0xFFE6E6FA), // lavender
        Color(0xFFE3F2FD), // biru muda
        Color(0xFFDFF3E4)  // mint
    )

    var selectedColor by remember { mutableStateOf(Color.White) }
    var hexColorInput by remember { mutableStateOf("FFFFFF") }

    fun parseHexColor(input: String): Color? {
        val clean = input.removePrefix("#").trim().uppercase()
        if (clean.length == 6 || clean.length == 8) {
            return try {
                val parsed = clean.toLong(16)
                if (clean.length == 6) Color(parsed or 0xFF000000) else Color(parsed)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Buat Proyek Baru",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nama Proyek") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { widthText = it },
                        label = { Text("Lebar (px)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it },
                        label = { Text("Tinggi (px)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Preset rasio kanvas: sisi terpanjang selalu 1920 px,
                // angka di kolom Lebar/Tinggi tetap bisa diubah manual.
                val ratioPresets = listOf(
                    "1:1" to (1920 to 1920),
                    "9:16" to (1080 to 1920),
                    "16:9" to (1920 to 1080),
                    "3:4" to (1440 to 1920)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Rasio Kanvas:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ratioPresets.forEach { (ratio, size) ->
                            FilterChip(
                                selected = widthText.toIntOrNull() == size.first &&
                                    heightText.toIntOrNull() == size.second,
                                onClick = {
                                    widthText = size.first.toString()
                                    heightText = size.second.toString()
                                },
                                label = { Text(ratio) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Checkbox: Kanvas Transparan
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isTransparent = !isTransparent }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = isTransparent,
                        onCheckedChange = { isTransparent = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Kanvas Transparan",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Section: Color Selector (Disabled if isTransparent is true)
                val alphaContent = if (isTransparent) 0.38f else 1.0f
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Warna Latar Belakang Kanvas:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alphaContent)
                    )

                    // Palet: baris potongan (chunk) 6 warna; tanda centang
                    // otomatis gelap di warna terang, terang di warna gelap
                    // (berdasar luminance, bukan daftar warna manual).
                    presetColors.chunked(6).forEach { paletteRow ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            paletteRow.forEach { color ->
                                val isSelected = !isTransparent && selectedColor == color
                                val swatchArgb = color.toArgb()
                                val swatchLum = (
                                    0.299f * ((swatchArgb shr 16) and 0xFF) +
                                        0.587f * ((swatchArgb shr 8) and 0xFF) +
                                        0.114f * (swatchArgb and 0xFF)
                                    ) / 255f
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color.copy(alpha = alphaContent))
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = alphaContent),
                                            shape = CircleShape
                                        )
                                        .clickable(enabled = !isTransparent) {
                                            selectedColor = color
                                            val argb = color.toArgb()
                                            hexColorInput = String.format("%06X", (argb and 0xFFFFFF))
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (swatchLum > 0.55f) Color.Black else Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    // Pengisi transparan: kotak grid abu tipis
                                    // supaya swatchnya tetap terlihat jelas.
                                    if (color == Color.Transparent) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .background(Color(0x33000000))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Custom Hex Color Input with live preview
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isTransparent) Color(0x1F000000)
                                    else selectedColor.copy(alpha = alphaContent)
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = alphaContent), RoundedCornerShape(8.dp))
                        )
                        OutlinedTextField(
                            value = hexColorInput,
                            onValueChange = { newHex ->
                                hexColorInput = newHex
                                parseHexColor(newHex)?.let { color ->
                                    selectedColor = color
                                }
                            },
                            enabled = !isTransparent,
                            label = { Text("Kode Warna Hex (mis. FFFFFF)") },
                            singleLine = true,
                            isError = !isTransparent && parseHexColor(hexColorInput) == null,
                            supportingText = {
                                if (!isTransparent && parseHexColor(hexColorInput) == null) {
                                    Text("Hex harus 6 digit (mis. 3A7BD9)", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    // Color Picker manual: slider R/G/B menyusun warna apa
                    // pun tanpa mengetik kode hex; kotak hex tetap ikut
                    // (dua arah: hex -> swatch -> slider).
                    if (!isTransparent) {
                        val curArgb = selectedColor.toArgb()
                        val curR = (curArgb shr 16) and 0xFF
                        val curG = (curArgb shr 8) and 0xFF
                        val curB = curArgb and 0xFF
                        val makeArgb = { r: Int, g: Int, b: Int ->
                            0xFF000000 or (r shl 8) or (g shl 16) or b
                        }
                        ChannelSliderRow(label = "R", value = curR, onValueChange = { r ->
                            selectedColor = Color(r / 255f, curG / 255f, curB / 255f)
                            hexColorInput = String.format("%06X", (makeArgb(r, curG, curB) and 0xFFFFFF))
                        })
                        ChannelSliderRow(label = "G", value = curG, onValueChange = { g ->
                            selectedColor = Color(curR / 255f, g / 255f, curB / 255f)
                            hexColorInput = String.format("%06X", (makeArgb(curR, g, curB) and 0xFFFFFF))
                        })
                        ChannelSliderRow(label = "B", value = curB, onValueChange = { b ->
                            selectedColor = Color(curR / 255f, curG / 255f, b / 255f)
                            hexColorInput = String.format("%06X", (makeArgb(curR, curG, b) and 0xFFFFFF))
                        })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = widthText.toIntOrNull() ?: 1080
                    val h = heightText.toIntOrNull() ?: 1920
                    onCreate(
                        title.ifBlank { "Proyek Tanpa Nama" },
                        w,
                        h,
                        isTransparent,
                        selectedColor.toArgb()
                    )
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Buat Proyek")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun FoldersDialog(
    folders: List<ProjectFolderEntity>,
    projects: List<ProjectEntity>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ProjectFolderEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectFolderEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kelola Folder", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nama folder baru") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onCreate(newName.trim())
                                newName = ""
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Tambah") }
                }

                if (folders.isEmpty()) {
                    Text(
                        "Belum ada folder. Buat folder untuk mengelompokkan proyek.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            val count = projects.count { it.folderId == folder.id }
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            folder.name,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "$count proyek",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { renameTarget = folder }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Ganti nama")
                                    }
                                    IconButton(onClick = { deleteTarget = folder }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Hapus folder",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        },
        shape = RoundedCornerShape(20.dp)
    )

    renameTarget?.let { folder ->
        RenameFolderDialog(
            initialName = folder.name,
            onDismiss = { renameTarget = null },
            onRename = { name ->
                onRename(folder.id, name)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus Folder") },
            text = {
                Text(
                    "Hapus folder \"${folder.name}\"? Proyek di dalamnya tidak dihapus, " +
                        "hanya dipindah ke \"Tanpa folder\"."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(folder.id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Batal") }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun RenameFolderDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti Nama Folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama folder") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onRename(name.trim()) },
                shape = RoundedCornerShape(10.dp)
            ) { Text("Simpan") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun MoveProjectDialog(
    project: ProjectEntity?,
    folders: List<ProjectFolderEntity>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pindahkan ke Folder") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                FolderChoiceRow(
                    label = "Tanpa folder",
                    selected = project?.folderId == null,
                    onClick = { onMove(null) }
                )
                folders.forEach { folder ->
                    FolderChoiceRow(
                        label = folder.name,
                        selected = project?.folderId == folder.id,
                        onClick = { onMove(folder.id) }
                    )
                }
                if (folders.isEmpty()) {
                    Text(
                        "Belum ada folder. Buat folder lewat tombol Kelola.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun FolderChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Dipilih",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
