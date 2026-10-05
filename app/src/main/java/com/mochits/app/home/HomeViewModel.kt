package com.mochits.app.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.app.font.FontItem
import com.mochits.app.font.FontRepository
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
import com.mochits.app.settings.ExportSettingsRepository
import com.mochits.app.ui.theme.AppThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val exportSettingsRepository: ExportSettingsRepository,
    val lamaModelManager: com.mochits.app.imaging.LaMaModelManager,
    // Optional so existing callers/tests keep compiling; Hilt always provides it.
    val fontRepository: FontRepository? = null
) : ViewModel() {

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _defaultExportFolderUri = MutableStateFlow<Uri?>(exportSettingsRepository.getExportFolderUri())
    val defaultExportFolderUri: StateFlow<Uri?> = _defaultExportFolderUri.asStateFlow()

    private val _defaultExportFolderName = MutableStateFlow<String?>(null)
    val defaultExportFolderName: StateFlow<String?> = _defaultExportFolderName.asStateFlow()

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uri = _defaultExportFolderUri.value
            if (uri != null) {
                _defaultExportFolderName.value = exportSettingsRepository.getFolderName(uri)
            }
        }
    }

    val projects: StateFlow<List<ProjectEntity>> = repository.getAllProjects()
        .catch {
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
    }

    fun updateExportFolderUri(uri: Uri) {
        // Hanya tampilkan bila benar-benar tersimpan (izin grant valid).
        if (!exportSettingsRepository.saveExportFolderUri(uri)) return
        _defaultExportFolderUri.value = uri
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _defaultExportFolderName.value = exportSettingsRepository.getFolderName(uri)
        }
    }

    fun isFolderValid(uri: Uri?): Boolean {
        return exportSettingsRepository.isFolderValid(uri)
    }

    /** Custom (user-imported) fonts for the Font Manager in Settings. */
    val customFonts: StateFlow<List<FontItem>> =
        (fontRepository?.getAllFontsFlow() ?: flowOf(emptyList()))
            .map { list -> list.filter { it.isCustom }.sortedBy { it.name.lowercase() } }
            .catch { emit(emptyList()) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    suspend fun importCustomFont(uri: Uri, fileName: String?): Result<FontItem> {
        val repo = fontRepository
            ?: return Result.failure(IllegalStateException("FontRepository tidak tersedia"))
        return repo.importCustomFont(uri, fileName)
    }

    fun deleteCustomFont(item: FontItem) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                fontRepository?.deleteCustomFont(item)
            } catch (_: Exception) {
            }
        }
    }

    val isLoading = MutableStateFlow(false)

    suspend fun createProject(
        title: String,
        width: Int,
        height: Int,
        imageUri: android.net.Uri? = null,
        isTransparent: Boolean = false,
        backgroundColor: Int = android.graphics.Color.WHITE
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val project = repository.createProject(title, width, height, imageUri, isTransparent, backgroundColor)
        project.id
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteProject(id)
            } catch (e: Exception) {
                // Log or handle error gracefully
            }
        }
    }

    /** Ekspor bundel .mts proyek ke URI SAF; @return true bila sukses. */
    suspend fun exportBundleTo(uri: Uri, projectId: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                exportSettingsRepository.openExportOutput(uri)?.use { out ->
                    repository.exportBundle(projectId, out)
                } ?: false
            } catch (_: Exception) {
                false
            }
        }

    /** Impor bundel .mts dari URI SAF; @return id proyek baru atau null. */
    suspend fun importBundleFrom(uri: Uri): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                exportSettingsRepository.openExportInput(uri)?.use { inp ->
                    repository.importBundle(inp)?.id
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Impor bundel .mts dari file cache (hasil buka file luar); hapus sesudahnya. */
    suspend fun importBundleFile(file: java.io.File): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext null
                val id = file.inputStream().use { inp ->
                    repository.importBundle(inp)?.id
                }
                try { file.delete() } catch (_: Exception) {}
                id
            } catch (_: Exception) {
                null
            }
        }
}
