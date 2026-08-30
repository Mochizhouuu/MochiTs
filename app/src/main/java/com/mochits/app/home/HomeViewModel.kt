package com.mochits.app.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val exportSettingsRepository: ExportSettingsRepository
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
        exportSettingsRepository.saveExportFolderUri(uri)
        _defaultExportFolderUri.value = uri
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _defaultExportFolderName.value = exportSettingsRepository.getFolderName(uri)
        }
    }

    fun isFolderValid(uri: Uri?): Boolean {
        return exportSettingsRepository.isFolderValid(uri)
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
}
