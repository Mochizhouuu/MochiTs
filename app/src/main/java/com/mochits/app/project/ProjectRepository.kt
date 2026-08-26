package com.mochits.app.project

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mochits.app.model.Layer
import com.mochits.app.model.TextStyleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao
) {
    private val gson = Gson()

    fun getAllProjects(): Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProject(id: String): ProjectEntity? = projectDao.getProjectById(id)

    suspend fun createProject(title: String, width: Int, height: Int): ProjectEntity {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = id,
            title = title,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now,
            layersJson = "[]"
        )
        projectDao.insertProject(entity)
        return entity
    }

    suspend fun saveProject(project: ProjectEntity) {
        projectDao.updateProject(project.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProject(id: String) {
        withContext(Dispatchers.IO) {
            val projectDir = File(context.filesDir, "projects/$id")
            if (projectDir.exists()) {
                projectDir.deleteRecursively()
            }
            projectDao.deleteProjectById(id)
        }
    }
}
