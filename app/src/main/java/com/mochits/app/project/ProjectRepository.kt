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

    suspend fun createProject(
        title: String,
        width: Int,
        height: Int,
        imageUri: android.net.Uri? = null,
        isTransparent: Boolean = false,
        backgroundColor: Int = android.graphics.Color.WHITE
    ): ProjectEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        var thumbnailPath: String? = null

        val projectDir = File(context.filesDir, "projects/$id").apply { mkdirs() }
        val imageFile = File(projectDir, "base_image.png")

        var finalWidth = width.coerceIn(1, 32768)
        var finalHeight = height.coerceIn(1, 32768)

        if (imageUri != null) {
            try {
                // Decode bounds first to check image dimensions
                val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(imageUri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input, null, boundsOptions)
                }

                val originalWidth = boundsOptions.outWidth
                val originalHeight = boundsOptions.outHeight

                if (originalWidth > 0 && originalHeight > 0) {
                    finalWidth = originalWidth
                    finalHeight = originalHeight

                    var sampleSize = 1
                    // Max total pixels limit before downsampling for memory safety (e.g. 50 MP)
                    val maxPixelCount = 50_000_000L
                    while ((originalWidth.toLong() / sampleSize) * (originalHeight.toLong() / sampleSize) > maxPixelCount) {
                        sampleSize *= 2
                    }

                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inMutable = true
                    }

                    val decodedBmp = context.contentResolver.openInputStream(imageUri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions)
                    }

                    if (decodedBmp != null) {
                        finalWidth = decodedBmp.width
                        finalHeight = decodedBmp.height
                        FileOutputStream(imageFile).use { output ->
                            decodedBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                        }
                        decodedBmp.recycle()
                        if (imageFile.exists() && imageFile.length() > 0) {
                            thumbnailPath = imageFile.absolutePath
                        }
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        if (thumbnailPath == null) {
            // Create canvas bitmap with specified width/height and background color
            try {
                val bmp = android.graphics.Bitmap.createBitmap(finalWidth, finalHeight, android.graphics.Bitmap.Config.ARGB_8888)
                if (!isTransparent) {
                    bmp.eraseColor(backgroundColor)
                } else {
                    bmp.eraseColor(android.graphics.Color.TRANSPARENT)
                }
                FileOutputStream(imageFile).use { output ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }
                if (imageFile.exists()) {
                    thumbnailPath = imageFile.absolutePath
                }
                bmp.recycle()
            } catch (t: Throwable) {
                t.printStackTrace()
                // If direct creation failed (e.g. OOM for extreme resolution), fallback to safe downscaled canvas
                try {
                    val safeW = finalWidth.coerceAtMost(2048)
                    val safeH = finalHeight.coerceAtMost(4096)
                    val bmp = android.graphics.Bitmap.createBitmap(safeW, safeH, android.graphics.Bitmap.Config.ARGB_8888)
                    if (!isTransparent) {
                        bmp.eraseColor(backgroundColor)
                    } else {
                        bmp.eraseColor(android.graphics.Color.TRANSPARENT)
                    }
                    FileOutputStream(imageFile).use { output ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                    }
                    if (imageFile.exists()) {
                        thumbnailPath = imageFile.absolutePath
                    }
                    bmp.recycle()
                    finalWidth = safeW
                    finalHeight = safeH
                } catch (t2: Throwable) {
                    t2.printStackTrace()
                }
            }
        }

        val entity = ProjectEntity(
            id = id,
            title = title,
            width = finalWidth,
            height = finalHeight,
            createdAt = now,
            updatedAt = now,
            thumbnailPath = thumbnailPath,
            isTransparent = isTransparent,
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
