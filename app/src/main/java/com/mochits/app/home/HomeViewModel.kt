package com.mochits.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {

    val projects: StateFlow<List<ProjectEntity>> = repository.getAllProjects()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createProject(title: String, width: Int, height: Int, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val project = repository.createProject(title, width, height)
            onCreated(project.id)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
        }
    }
}
