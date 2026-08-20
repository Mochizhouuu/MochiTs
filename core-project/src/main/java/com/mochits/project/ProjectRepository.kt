package com.mochits.project

import android.content.Context
import android.net.Uri
import com.mochits.common.OperationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mengelola project: metadata via Room ([ProjectDao]), file gambar
 * disalin ke penyimpanan internal khusus app (app-specific storage)
 * agar tidak bergantung pada URI sumber yang bisa kedaluwarsa/dicabut.
 */
@Singleton
class ProjectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectDao: ProjectDao
) {
    private val projectsDir: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun observeProjects(): Flow<List<ProjectFile>> =
        projectDao.observeAll().map { list -> list.map { it.toProjectFile() } }

    suspend fun getProject(id: String): ProjectFile? =
        projectDao.getById(id)?.toProjectFile()

    /**
     * Membuat project baru dari gambar yang dipilih user (Uri hasil image
     * picker). Gambar disalin ke penyimpanan internal app agar aman
     * dipakai jangka panjang.
     */
    suspend fun createProject(name: String, sourceImageUri: Uri): OperationResult<ProjectFile> =
        withContext(Dispatchers.IO) {
            try {
                val id = UUID.randomUUID().toString()
                val projectFolder = File(projectsDir, id).apply { mkdirs() }
                val destImage = File(projectFolder, "base.png")

                context.contentResolver.openInputStream(sourceImageUri)?.use { input ->
                    destImage.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext OperationResult.Failure(
                    IllegalStateException("Tidak bisa membuka gambar sumber"),
                    "Gagal membaca gambar yang dipilih"
                )

                val now = System.currentTimeMillis()
                val entity = ProjectEntity(
                    id = id,
                    name = name,
                    baseImagePath = destImage.absolutePath,
                    maskPath = null,
                    thumbnailPath = destImage.absolutePath,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
                projectDao.upsert(entity)
                OperationResult.Success(entity.toProjectFile())
            } catch (e: Exception) {
                OperationResult.Failure(e, "Gagal membuat project baru")
            }
        }

    suspend fun renameProject(id: String, newName: String): OperationResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val existing = projectDao.getById(id)
                    ?: return@withContext OperationResult.Failure(
                        IllegalStateException("Project tidak ditemukan")
                    )
                projectDao.update(
                    existing.copy(name = newName, updatedAtEpochMs = System.currentTimeMillis())
                )
                OperationResult.Success(Unit)
            } catch (e: Exception) {
                OperationResult.Failure(e, "Gagal mengganti nama project")
            }
        }

    suspend fun deleteProject(id: String): OperationResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val existing = projectDao.getById(id)
                    ?: return@withContext OperationResult.Failure(
                        IllegalStateException("Project tidak ditemukan")
                    )
                File(existing.baseImagePath).parentFile?.deleteRecursively()
                projectDao.delete(existing)
                OperationResult.Success(Unit)
            } catch (e: Exception) {
                OperationResult.Failure(e, "Gagal menghapus project")
            }
        }
}
