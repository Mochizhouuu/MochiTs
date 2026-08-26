package com.mochits.app.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mochits.app.project.ProjectEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenEditor: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MochiTs - Manga Typesetting") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New Project")
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (projects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Belum ada proyek. Ketuk + untuk membuat proyek baru.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectItemCard(
                            project = project,
                            onClick = { onOpenEditor(project.id) },
                            onDelete = { viewModel.deleteProject(project.id) }
                        )
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreateProjectDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { title, width, height ->
                    showCreateDialog = false
                    viewModel.createProject(title, width, height) { newId ->
                        onOpenEditor(newId)
                    }
                }
            )
        }
    }
}

@Composable
fun ProjectItemCard(
    project: ProjectEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = project.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Ukuran: ${project.width} x ${project.height}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Project")
            }
        }
    }
}

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, width: Int, height: Int) -> Unit
) {
    var title by remember { mutableStateOf("Proyek Baru") }
    var widthText by remember { mutableStateOf("1080") }
    var heightText by remember { mutableStateOf("1920") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buat Proyek Baru") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nama Proyek") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it },
                    label = { Text("Lebar (px)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("Tinggi (px)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val w = widthText.toIntOrNull() ?: 1080
                    val h = heightText.toIntOrNull() ?: 1920
                    onCreate(title, w, h)
                }
            ) {
                Text("Buat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
