package com.mochits.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
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
    private val repository: ProjectRepository
) : ViewModel() {

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

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

    fun createProject(title: String, width: Int, height: Int, imageUri: android.net.Uri? = null, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val project = repository.createProject(title, width, height, imageUri)
                onCreated(project.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
