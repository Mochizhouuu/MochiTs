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

    suspend fun getProject(id: String): ProjectEntity? {
        return try {
            projectDao.getProjectById(id)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun createProject(title: String, width: Int, height: Int, imageUri: android.net.Uri? = null): ProjectEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        var thumbnailPath: String? = null

        if (imageUri != null) {
            try {
                val projectDir = File(context.filesDir, "projects/$id").apply { mkdirs() }
                val imageFile = File(projectDir, "base_image.png")
                context.contentResolver.openInputStream(imageUri)?.use { input ->
                    FileOutputStream(imageFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (imageFile.exists() && imageFile.length() > 0) {
                    thumbnailPath = imageFile.absolutePath
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val entity = ProjectEntity(
            id = id,
            title = title,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now,
            thumbnailPath = thumbnailPath,
            layersJson = "[]"
        )
        projectDao.insertProject(entity)
        entity
    }

    suspend fun saveProject(project: ProjectEntity) {
        try {
            projectDao.updateProject(project.copy(updatedAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            // Handle error safely
        }
    }

    suspend fun deleteProject(id: String) {
        withContext(Dispatchers.IO) {
            try {
                val projectDir = File(context.filesDir, "projects/$id")
                if (projectDir.exists()) {
                    projectDir.deleteRecursively()
                }
            } catch (e: Exception) {
                // Ignore file deletion error
            }
            try {
                projectDao.deleteProjectById(id)
            } catch (e: Exception) {
                // Handle database deletion error safely
            }
        }
    }
}
