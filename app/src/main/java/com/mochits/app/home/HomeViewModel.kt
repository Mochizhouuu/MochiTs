package com.mochits.app.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.common.OperationResult
import com.mochits.project.ProjectFile
import com.mochits.project.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val projects: List<ProjectFile> = emptyList(),
    val isCreating: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> =
        projectRepository.observeProjects()
            .let { projectsFlow ->
                kotlinx.coroutines.flow.combine(
                    projectsFlow, _isCreating, _errorMessage
                ) { projects, isCreating, error ->
                    HomeUiState(projects = projects, isCreating = isCreating, errorMessage = error)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun createProject(name: String, imageUri: Uri, onCreated: (ProjectFile) -> Unit) {
        viewModelScope.launch {
            _isCreating.value = true
            when (val result = projectRepository.createProject(name, imageUri)) {
                is OperationResult.Success -> {
                    _isCreating.value = false
                    onCreated(result.data)
                }
                is OperationResult.Failure -> {
                    _isCreating.value = false
                    _errorMessage.value = result.message ?: "Gagal membuat project"
                }
            }
        }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch {
            projectRepository.renameProject(id, newName)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(id)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
