package com.mochits.app.home

import com.mochits.app.project.ProjectDao
import com.mochits.app.project.ProjectEntity
import com.mochits.app.project.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

class ErrorProjectDao : ProjectDao {
    override fun getAllProjects(): Flow<List<ProjectEntity>> = flow {
        throw RuntimeException("Database stream error simulation")
    }
    override suspend fun getProjectById(id: String): ProjectEntity? = null
    override suspend fun insertProject(project: ProjectEntity) {}
    override suspend fun updateProject(project: ProjectEntity) {}
    override suspend fun deleteProject(project: ProjectEntity) {}
    override suspend fun deleteProjectById(id: String) {}
}

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @Test
    fun projects_flow_handles_repository_error_gracefully() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = ProjectRepository(context, ErrorProjectDao())
        val viewModel = HomeViewModel(repository)

        val currentProjects = viewModel.projects.value
        assertEquals(emptyList<ProjectEntity>(), currentProjects)
    }
}
