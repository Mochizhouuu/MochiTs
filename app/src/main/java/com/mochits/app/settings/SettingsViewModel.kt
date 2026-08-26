package com.mochits.app.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.common.OperationResult
import com.mochits.inpaint.ModelDownloadState
import com.mochits.inpaint.ModelManager
import com.mochits.project.ProjectRepository
import com.mochits.text.CustomFontItem
import com.mochits.text.FontManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val fonts: List<CustomFontItem> = emptyList(),
    val exportLocation: String = "Pictures/MochiTs",
    val exportQuality: Int = 100,
    val isLoading: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    // Singleton: status unduhan sinkron dengan layar lain (mis. Editor)
    private val modelManager: ModelManager,
    // Scope aplikasi: unduhan model ~40MB tidak boleh terputus saat user
    // keluar dari layar Settings (viewModelScope ikut dibatalkan).
    private val appScope: CoroutineScope
) : ViewModel() {

    private val fontManager = FontManager(context)
    private val appSettings = AppSettings(context)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Status unduhan model LaMa — passthrough dari singleton. */
    val downloadState: StateFlow<ModelDownloadState> = modelManager.downloadState

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            // Scan font adalah I/O disk — jangan di Main thread.
            val fonts = withContext(Dispatchers.IO) { fontManager.getInstalledFonts() }
            _uiState.update {
                it.copy(
                    fonts = fonts,
                    exportLocation = appSettings.exportLocation,
                    exportQuality = appSettings.exportQuality
                )
            }
        }
    }

    fun downloadModel() {
        appScope.launch { modelManager.downloadModel() }
    }

    fun deleteModel() {
        appScope.launch { modelManager.deleteModel() }
    }

    fun importFont(uri: Uri, fontName: String) {
        viewModelScope.launch {
            val result = fontManager.importFont(uri, fontName)
            if (result != null) {
                _uiState.update {
                    it.copy(
                        fonts = fontManager.getInstalledFonts(),
                        message = "Font '${result.name}' berhasil ditambahkan!"
                    )
                }
            } else {
                _uiState.update { it.copy(message = "Gagal mengimpor font") }
            }
        }
    }

    fun deleteFont(fontPath: String) {
        viewModelScope.launch {
            val deleted = fontManager.deleteFont(fontPath)
            if (deleted) {
                _uiState.update {
                    it.copy(
                        fonts = fontManager.getInstalledFonts(),
                        message = "Font berhasil dihapus"
                    )
                }
            } else {
                _uiState.update { it.copy(message = "Gagal menghapus font") }
            }
        }
    }

    fun setExportLocation(location: String) {
        appSettings.exportLocation = location
        _uiState.update { it.copy(exportLocation = location) }
    }

    fun setExportQuality(quality: Int) {
        appSettings.exportQuality = quality
        _uiState.update { it.copy(exportQuality = quality) }
    }

    fun exportProjects(targetUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = projectRepository.exportAllProjectsToUri(targetUri)) {
                is OperationResult.Success -> {
                    _uiState.update {
                        it.copy(isLoading = false, message = "Seluruh projek berhasil di-backup!")
                    }
                }
                is OperationResult.Failure -> {
                    _uiState.update {
                        it.copy(isLoading = false, message = res.message ?: "Gagal ekspor projek")
                    }
                }
            }
        }
    }

    fun importProjects(sourceUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = projectRepository.importProjectsFromUri(sourceUri)) {
                is OperationResult.Success -> {
                    _uiState.update {
                        it.copy(isLoading = false, message = "Berhasil mengimpor ${res.data} projek!")
                    }
                }
                is OperationResult.Failure -> {
                    _uiState.update {
                        it.copy(isLoading = false, message = res.message ?: "Gagal impor projek")
                    }
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
