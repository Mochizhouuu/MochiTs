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

    suspend fun exportAllProjectsToUri(destUri: Uri): OperationResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val allProjects = projectDao.getAll()
                if (allProjects.isEmpty()) {
                    return@withContext OperationResult.Failure(
                        IllegalStateException("Tidak ada projek untuk diekspor")
                    )
                }

                context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                    java.util.zip.ZipOutputStream(outStream).use { zipOut ->
                        // 1. Write metadata JSON
                        val jsonArray = org.json.JSONArray()
                        for (proj in allProjects) {
                            val obj = org.json.JSONObject()
                            obj.put("id", proj.id)
                            obj.put("name", proj.name)
                            obj.put("createdAtEpochMs", proj.createdAtEpochMs)
                            obj.put("updatedAtEpochMs", proj.updatedAtEpochMs)
                            jsonArray.put(obj)
                        }
                        val metadataBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
                        zipOut.putNextEntry(java.util.zip.ZipEntry("metadata.json"))
                        zipOut.write(metadataBytes)
                        zipOut.closeEntry()

                        // 2. Write project files
                        for (proj in allProjects) {
                            val projFolder = File(projectsDir, proj.id)
                            if (projFolder.exists() && projFolder.isDirectory) {
                                val files = projFolder.listFiles() ?: emptyArray()
                                for (file in files) {
                                    if (file.isFile) {
                                        val entryName = "${proj.id}/${file.name}"
                                        zipOut.putNextEntry(java.util.zip.ZipEntry(entryName))
                                        file.inputStream().use { it.copyTo(zipOut) }
                                        zipOut.closeEntry()
                                    }
                                }
                            }
                        }
                    }
                } ?: return@withContext OperationResult.Failure(
                    IllegalStateException("Tidak dapat membuka lokasi tujuan ekspor")
                )

                OperationResult.Success(Unit)
            } catch (e: Exception) {
                OperationResult.Failure(e, "Gagal meng-ekspor projek")
            }
        }

    suspend fun importProjectsFromUri(sourceUri: Uri): OperationResult<Int> =
        withContext(Dispatchers.IO) {
            try {
                var importedCount = 0
                val tempDir = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}").apply { mkdirs() }

                context.contentResolver.openInputStream(sourceUri)?.use { inStream ->
                    java.util.zip.ZipInputStream(inStream).use { zipIn ->
                        var entry = zipIn.nextEntry
                        while (entry != null) {
                            val destFile = File(tempDir, entry.name)
                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else {
                                destFile.parentFile?.mkdirs()
                                destFile.outputStream().use { zipIn.copyTo(it) }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                } ?: return@withContext OperationResult.Failure(
                    IllegalStateException("Tidak dapat membaca berkas impor")
                )

                val metaFile = File(tempDir, "metadata.json")
                if (metaFile.exists()) {
                    val jsonStr = metaFile.readText(Charsets.UTF_8)
                    val jsonArray = org.json.JSONArray(jsonStr)

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val id = obj.getString("id")
                        val name = obj.getString("name")
                        val createdAt = obj.optLong("createdAtEpochMs", System.currentTimeMillis())
                        val updatedAt = obj.optLong("updatedAtEpochMs", System.currentTimeMillis())

                        val projFolderInTemp = File(tempDir, id)
                        if (projFolderInTemp.exists() && projFolderInTemp.isDirectory) {
                            val targetProjDir = File(projectsDir, id).apply { mkdirs() }
                            projFolderInTemp.listFiles()?.forEach { file ->
                                file.copyTo(File(targetProjDir, file.name), overwrite = true)
                            }

                            val baseImg = File(targetProjDir, "base.png")
                            if (baseImg.exists()) {
                                val entity = ProjectEntity(
                                    id = id,
                                    name = name,
                                    baseImagePath = baseImg.absolutePath,
                                    maskPath = null,
                                    thumbnailPath = baseImg.absolutePath,
                                    createdAtEpochMs = createdAt,
                                    updatedAtEpochMs = updatedAt
                                )
                                projectDao.upsert(entity)
                                importedCount++
                            }
                        }
                    }
                }

                tempDir.deleteRecursively()
                OperationResult.Success(importedCount)
            } catch (e: Exception) {
                OperationResult.Failure(e, "Gagal mengimpor projek: ${e.message}")
            }
        }
}
