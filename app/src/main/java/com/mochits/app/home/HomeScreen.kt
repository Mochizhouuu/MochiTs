package com.mochits.app.home
import com.mochits.app.util.Logger

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochits.app.project.ProjectBundle
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectFolderEntity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MochiTsLogoFont = FontFamily(Font(com.mochits.app.R.font.bebas_neue))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenEditor: (String) -> Unit,
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
    var selectedDeleteProjectId by remember { mutableStateOf<String?>(null) }
    var projectToMove by remember { mutableStateOf<ProjectEntity?>(null) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }
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
    val knownFolderIds = remember(folders) { folders.mapTo(HashSet()) { it.id } }
    val rootProjects = remember(projects, knownFolderIds) {
        projects.filter { it.folderId == null || it.folderId !in knownFolderIds }
    }
    val selectedCount = selectedProjectIds.size + selectedFolderIds.size
    val visibleItemCount = folderSummaries.size + rootProjects.size
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) {
            coroutineScope.launch {
                val saved = viewModel.exportBundleTo(uri, id)
                toast(if (saved) "Bundel tersimpan." else "Gagal menyimpan bundel.")
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
                                    selectedProjectIds = rootProjects.mapTo(LinkedHashSet()) { it.id }
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
                        Text(
                            text = "MochiTs",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = MochiTsLogoFont,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            try {
                                galleryPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            } catch (e: Exception) {
                                fallbackContentLauncher.launch(arrayOf("image/*"))
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "Impor gambar",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            zipImportLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream"
                                )
                            )
                        }) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Impor ZIP berisi gambar/folder",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
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
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "NEW PROJECT",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                        FilledTonalButton(
                            onClick = { bundleImportLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OPEN PROJECT",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "PROYEK",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = when {
                                    folders.isEmpty() -> "${projects.size} proyek"
                                    projects.isEmpty() -> "${folders.size} folder"
                                    else -> "${projects.size} proyek, ${folders.size} folder"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showFoldersDialog = true }) {
                            Text("Kelola folder")
                        }
                    }
                }

                if (folderSummaries.isEmpty() && rootProjects.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 72.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Belum ada proyek",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Buat proyek atau impor gambar untuk memulai.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                gridItems(folderSummaries, key = { "folder:${it.folder.id}" }) { summary ->
                    FolderCard(
                        summary = summary,
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

                gridItems(rootProjects, key = { "project:${it.id}" }) { project ->
                    ProjectGridCard(
                        project = project,
                        onOpen = {
                            if (selectionMode) toggleProject(project.id)
                            else onOpenEditor(project.id)
                        },
                        onMove = { projectToMove = project },
                        onExport = {
                            pendingExportId = project.id
                            exportLauncher.launch(ProjectBundle.fileNameFor(project.title))
                        },
                        onDelete = { selectedDeleteProjectId = project.id },
                        selected = project.id in selectedProjectIds,
                        selectionMode = selectionMode,
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

    if (selectedDeleteProjectId != null) {
        val projToDelete = projects.find { it.id == selectedDeleteProjectId }
        AlertDialog(
            onDismissRequest = { selectedDeleteProjectId = null },
            title = { Text("Hapus Proyek", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Apakah Anda yakin ingin menghapus proyek '${projToDelete?.title ?: ""}'? Aksi ini tidak dapat dibatalkan.") },
            confirmButton = {
                Button(
                    onClick = {
                        selectedDeleteProjectId?.let { id ->
                            viewModel.deleteProject(id)
                        }
                        selectedDeleteProjectId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedDeleteProjectId = null }) {
                    Text("Batal")
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

    projectToMove?.let { project ->
        MoveProjectDialog(
            project = project,
            folders = folders,
            onDismiss = { projectToMove = null },
            onMove = { targetFolderId ->
                viewModel.moveProjectToFolder(project.id, targetFolderId)
                projectToMove = null
            }
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

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, width: Int, height: Int, isTransparent: Boolean, backgroundColor: Int) -> Unit
) {
    var title by remember { mutableStateOf("Proyek Baru") }
    var widthText by remember { mutableStateOf("1080") }
    var heightText by remember { mutableStateOf("1920") }
    var isTransparent by remember { mutableStateOf(false) }

    val presetColors = listOf(
        Color.White,
        Color.Black,
        Color(0xFFE0E0E0), // Soft Gray
        Color(0xFFFFFDD0), // Cream
        Color(0xFFE6E6FA)  // Lavender
    )

    var selectedColor by remember { mutableStateOf(Color.White) }
    var hexColorInput by remember { mutableStateOf("FFFFFF") }

    fun parseHexColor(input: String): Color? {
        val clean = input.removePrefix("#").trim()
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

                    // Preset Color Badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        presetColors.forEach { color ->
                            val isSelected = !isTransparent && selectedColor == color
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
                                        tint = if (color == Color.White || color == Color(0xFFFFFDD0) || color == Color(0xFFE6E6FA) || color == Color(0xFFE0E0E0)) Color.Black else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
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
                                .background((if (isTransparent) Color.Transparent else selectedColor).copy(alpha = alphaContent))
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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
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
