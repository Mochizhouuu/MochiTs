package com.mochits.app.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mochits.app.project.ProjectBundle
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ZipImportPlanner
import com.mochits.app.project.ProjectFolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class FolderSummary(
    val folder: ProjectFolderEntity,
    val count: Int,
    val coverPath: String?
)

internal fun buildFolderSummaries(
    folders: List<ProjectFolderEntity>,
    projects: List<ProjectEntity>
): List<FolderSummary> {
    val grouped = projects.groupBy { it.folderId }
    return folders.map { folder ->
        val folderProjects = grouped[folder.id].orEmpty()
        FolderSummary(
            folder = folder,
            count = folderProjects.size,
            coverPath = folderProjects.firstNotNullOfOrNull { it.thumbnailPath }
        )
    }
}

@Composable
internal fun rememberProjectThumbnail(path: String?, maxEdge: Int = 512): State<Bitmap?> =
    produceState<Bitmap?>(
        initialValue = null,
        key1 = path,
        key2 = maxEdge
    ) {
        value = withContext(Dispatchers.IO) {
            val thumbnailPath = path ?: return@withContext null
            val file = File(thumbnailPath)
            if (!file.exists()) return@withContext null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(file.absolutePath, options)
            } catch (_: Throwable) {
                null
            }
        }
    }

@Composable
internal fun ProjectThumbnail(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Default.AspectRatio
) {
    val bitmap by rememberProjectThumbnail(path)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.42f)
            )
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    summary: FolderSummary,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = modifier
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ProjectThumbnail(
                path = summary.coverPath,
                contentDescription = summary.folder.name,
                modifier = Modifier.fillMaxSize(),
                fallbackIcon = Icons.Default.FolderOpen
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
                    .padding(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 8.dp)
            ) {
                Column {
                    Text(
                        text = summary.folder.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${summary.count} proyek",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderGridCard(
    summary: FolderSummary,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                ProjectThumbnail(
                    path = summary.coverPath,
                    contentDescription = summary.folder.name,
                    modifier = Modifier.fillMaxSize(),
                    fallbackIcon = Icons.Default.FolderOpen
                )
                if (selected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.28f))
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
            Text(
                text = summary.folder.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectGridCard(
    project: ProjectEntity,
    label: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val shape = RoundedCornerShape(14.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
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
                if (selectionMode) {
                    SelectionMark(
                        selected = selected,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
            }
            Text(
                text = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectBrowserScreen(
    viewModel: HomeViewModel,
    folderId: String? = null,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenFolder: (String) -> Unit = {}
) {
    val projects by viewModel.projects.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val folder = remember(folders, folderId) {
        folders.firstOrNull { it.id == folderId }
    }
    val folderSummaries = remember(folders, projects) {
        buildFolderSummaries(folders, projects)
    }
    val looseProjects = remember(projects) {
        projects.filter { it.folderId == null }
    }
    val visibleFolders = remember(folderSummaries, folderId) {
        if (folderId == null) folderSummaries else emptyList()
    }
    val visibleProjects = remember(projects, folderId) {
        if (folderId == null) {
            looseProjects
        } else {
            projects
                .filter { it.folderId == folderId }
                .sortedWith { a, b -> ZipImportPlanner.naturalCompare(a.title, b.title) }
        }
    }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBulkDelete by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var projectToMove by remember { mutableStateOf<ProjectEntity?>(null) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    val selectedProject = if (selectedIds.size == 1 && selectedFolderIds.isEmpty()) {
        visibleProjects.firstOrNull { it.id in selectedIds }
    } else {
        null
    }
    val selectedCount = selectedIds.size + selectedFolderIds.size
    val visibleItemCount = visibleProjects.size + visibleFolders.size
    val allVisibleSelected = visibleItemCount > 0 && selectedCount == visibleItemCount

    LaunchedEffect(selectedProject?.id) {
        if (selectedProject == null) showSelectionMenu = false
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun toggleFolderSelection(id: String) {
        selectedFolderIds =
            if (id in selectedFolderIds) selectedFolderIds - id else selectedFolderIds + id
    }

    fun leaveSelection() {
        selectionMode = false
        selectedIds = emptySet()
        selectedFolderIds = emptySet()
    }

    BackHandler(enabled = selectionMode) {
        leaveSelection()
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
                            text = "${selectedCount} dipilih",
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
                                    selectedIds = emptySet()
                                    selectedFolderIds = emptySet()
                                } else {
                                    selectedIds = visibleProjects.mapTo(LinkedHashSet()) { it.id }
                                    selectedFolderIds =
                                        visibleFolders.mapTo(LinkedHashSet()) { it.folder.id }
                                }
                            }
                        ) {
                            Text(if (allVisibleSelected) "Batal" else "Semua")
                        }
                        if (selectedProject != null) {
                            Box {
                                IconButton(onClick = { showSelectionMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Aksi proyek terpilih"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSelectionMenu,
                                    onDismissRequest = { showSelectionMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Pindahkan ke folder") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showSelectionMenu = false
                                            projectToMove = selectedProject
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ekspor bundel") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Save,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showSelectionMenu = false
                                            pendingExportId = selectedProject.id
                                            exportLauncher.launch(
                                                ProjectBundle.fileNameFor(selectedProject.title)
                                            )
                                            leaveSelection()
                                        }
                                    )
                                }
                            }
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
                            text = folder?.name ?: "All Project",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kembali"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (folderId == null) 2 else 3),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (visibleProjects.isEmpty() && visibleFolders.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (folderId == null) "Belum ada proyek atau folder" else "Folder ini masih kosong",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (folderId == null) {
                                "Buat proyek, impor ZIP, atau impor gambar untuk memulai."
                            } else {
                                "Pindahkan proyek ke folder ini lewat menu proyek."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                gridItems(visibleFolders, key = { "folder:${it.folder.id}" }) { summary ->
                    FolderGridCard(
                        summary = summary,
                        onOpen = {
                            if (selectionMode) toggleFolderSelection(summary.folder.id)
                            else onOpenFolder(summary.folder.id)
                        },
                        selected = summary.folder.id in selectedFolderIds,
                        selectionMode = selectionMode,
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedFolderIds = setOf(summary.folder.id)
                            } else {
                                toggleFolderSelection(summary.folder.id)
                            }
                        }
                    )
                }
                gridItems(visibleProjects, key = { "project:${it.id}" }) { project ->
                    ProjectGridCard(
                        project = project,
                        label = project.title,
                        onOpen = {
                            if (selectionMode) toggleSelection(project.id)
                            else onOpenProject(project.id)
                        },
                        selected = project.id in selectedIds,
                        selectionMode = selectionMode,
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedIds = setOf(project.id)
                            } else {
                                toggleSelection(project.id)
                            }
                        }
                    )
                }
            }
        }
    }

    projectToMove?.let { project ->
        MoveProjectDialog(
            project = project,
            folders = folders,
            onDismiss = { projectToMove = null },
            onMove = { targetFolderId ->
                viewModel.moveProjectToFolder(project.id, targetFolderId)
                projectToMove = null
                leaveSelection()
            }
        )
    }

    if (showBulkDelete && selectedCount > 0) {
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text("Hapus $selectedCount item") },
            text = {
                Text(
                    "Proyek yang dipilih akan dihapus permanen. Folder yang dipilih " +
                        "hanya dilepas; proyek di dalamnya tidak dihapus."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val projectIds = selectedIds
                        val folderIds = selectedFolderIds
                        viewModel.deleteProjects(projectIds)
                        viewModel.deleteFolders(folderIds)
                        toast("${projectIds.size + folderIds.size} item dihapus.")
                        showBulkDelete = false
                        leaveSelection()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(10.dp)
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
}
