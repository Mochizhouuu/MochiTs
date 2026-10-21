package com.mochits.app.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mochits.app.project.ProjectBundle
import com.mochits.app.project.ProjectEntity
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
    modifier: Modifier = Modifier
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
                imageVector = Icons.Default.AspectRatio,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderCard(
    summary: FolderSummary,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(172.dp)
            .height(142.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ProjectThumbnail(
                path = summary.coverPath,
                contentDescription = summary.folder.name,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .size(30.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = summary.folder.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${summary.count} halaman",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectGridCard(
    project: ProjectEntity,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
            ) {
                ProjectThumbnail(
                    path = project.thumbnailPath,
                    contentDescription = project.title,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                    ) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Aksi proyek ${project.title}",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Buka") },
                            onClick = {
                                menuExpanded = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Pindahkan ke folder") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMove()
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
                                menuExpanded = false
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Hapus") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${project.width} x ${project.height} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
        folderId?.let { id -> folders.firstOrNull { it.id == id } }
    }
    val summaries = remember(folders, projects) {
        buildFolderSummaries(folders, projects)
    }
    val visibleProjects = remember(projects, folderId) {
        if (folderId == null) projects else projects.filter { it.folderId == folderId }
    }

    var projectToMove by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectToDelete by remember { mutableStateOf<ProjectEntity?>(null) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

    val title = folder?.name ?: if (folderId == null) "Semua proyek" else "Folder"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
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
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (folderId == null && summaries.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        title = "Folder",
                        count = summaries.size
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 8.dp)
                    ) {
                        rowItems(summaries, key = { it.folder.id }) { summary ->
                            FolderCard(
                                summary = summary,
                                onClick = { onOpenFolder(summary.folder.id) }
                            )
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    title = if (folderId == null) "Semua proyek" else "Halaman",
                    count = visibleProjects.size
                )
            }

            if (visibleProjects.isEmpty()) {
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
                            text = if (folderId == null) {
                                "Belum ada proyek"
                            } else {
                                "Folder ini masih kosong"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (folderId == null) {
                                "Buat proyek atau impor gambar untuk memulai."
                            } else {
                                "Pindahkan proyek ke folder ini lewat menu proyek."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                gridItems(visibleProjects, key = { it.id }) { project ->
                    ProjectGridCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onMove = { projectToMove = project },
                        onExport = {
                            pendingExportId = project.id
                            exportLauncher.launch(ProjectBundle.fileNameFor(project.title))
                        },
                        onDelete = { projectToDelete = project }
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
            }
        )
    }

    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Hapus proyek") },
            text = { Text("Hapus proyek \"${project.title}\"? Tindakan ini tidak bisa dibatalkan.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProject(project.id)
                        projectToDelete = null
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
                TextButton(onClick = { projectToDelete = null }) {
                    Text("Batal")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
