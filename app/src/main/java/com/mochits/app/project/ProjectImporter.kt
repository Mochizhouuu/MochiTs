package com.mochits.app.project

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mochits.app.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Impor gambar (satu/banyak) dan ZIP berisi gambar/folder/bundel `.mts`.
 * Semua pekerjaan IO di Dispatchers.IO; folder virtual dibuat otomatis
 * sesuai keputusan produk:
 * - banyak gambar tanpa folder tujuan -> satu folder baru,
 * - ZIP: gambar root -> folder bernama ZIP, subfolder -> folder per subfolder.
 */
@Singleton
class ProjectImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository
) {
    data class ImageImportResult(
        val projectIds: List<String>,
        val folderId: String? = null,
        val folderName: String? = null,
        val failed: Int = 0
    )

    data class ZipImportResult(
        val projects: Int = 0,
        val bundles: Int = 0,
        val folderNames: List<String> = emptyList(),
        val failed: Int = 0
    ) {
        val total: Int get() = projects + bundles
    }

    suspend fun importImages(
        uris: List<Uri>,
        targetFolderId: String? = null,
        autoFolderName: String? = null
    ): ImageImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ImageImportResult(emptyList())

        var folderId = targetFolderId
        var folderName: String? = null
        if (folderId == null && uris.size > 1) {
            val name = autoFolderName?.takeIf { it.isNotBlank() }
                ?: "Impor ${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
            val folder = repository.createFolder(name)
            folderId = folder.id
            folderName = folder.name
        }

        val ids = ArrayList<String>(uris.size)
        var failed = 0
        uris.forEachIndexed { index, uri ->
            try {
                val display = queryDisplayName(uri)
                val title = ZipImportPlanner.sanitizeName(
                    display?.substringBeforeLast('.') ?: "Gambar ${index + 1}",
                    "Gambar ${index + 1}"
                )
                val project = repository.createProject(
                    title = title,
                    width = 1080,
                    height = 1920,
                    imageUri = uri,
                    folderId = folderId
                )
                ids.add(project.id)
            } catch (t: Throwable) {
                failed++
                Logger.e("Gagal mengimpor gambar: ${t.message}", t)
            }
        }
        ImageImportResult(ids, folderId, folderName, failed)
    }

    suspend fun importZip(uri: Uri, displayName: String? = null): ZipImportResult =
        withContext(Dispatchers.IO) {
            val zipBase = ZipImportPlanner.zipBaseName(displayName ?: queryDisplayName(uri))
            val paths = try {
                collectZipPaths(uri)
            } catch (t: Throwable) {
                Logger.e("Gagal membaca ZIP: ${t.message}", t)
                return@withContext ZipImportResult(failed = 1)
            }
            val plan = ZipImportPlanner.plan(paths, zipBase)
            if (plan.isEmpty()) return@withContext ZipImportResult()

            val folderIds = HashMap<String, String>()
            for (name in plan.mapNotNull { it.folderName }.distinct()) {
                try {
                    folderIds[name] = repository.createFolder(name).id
                } catch (t: Throwable) {
                    Logger.e("Gagal membuat folder $name: ${t.message}", t)
                }
            }

            val byPath = plan.associateBy { it.path }
            val consumed = HashSet<String>()
            var projects = 0
            var bundles = 0
            var failed = 0
            var totalBytes = 0L
            var itemCount = 0
            val tempDir = File(context.cacheDir, "zip_import").apply { mkdirs() }

            try {
                openZip(uri)?.use { zip ->
                    var entry = zip.nextEntry
                    var scanned = 0
                    while (entry != null && itemCount < MAX_IMPORT_ITEMS && scanned < MAX_SCAN_ENTRIES) {
                        scanned++
                        val path = entry.name.replace('\\', '/').trim().trimStart('/')
                        val planned = byPath[path]
                        if (!entry.isDirectory && planned != null && consumed.add(path)) {
                            val allowed = MAX_ENTRY_BYTES
                            val temp = File(tempDir, "entry_${UUID.randomUUID()}.${planned.fileName.substringAfterLast('.', "bin")}")
                            try {
                                val written = copyEntryCapped(zip, temp, allowed)
                                if (written < 0) {
                                    failed++
                                } else if (totalBytes + written > MAX_TOTAL_BYTES) {
                                    failed++
                                } else {
                                    totalBytes += written
                                    val folderId = planned.folderName?.let { folderIds[it] }
                                    if (planned.isBundle) {
                                        val imported = temp.inputStream().use { input ->
                                            repository.importBundle(input, folderId)
                                        }
                                        if (imported != null) bundles++ else failed++
                                    } else {
                                        repository.createProject(
                                            title = planned.title,
                                            width = 1080,
                                            height = 1920,
                                            imageUri = Uri.fromFile(temp),
                                            folderId = folderId
                                        )
                                        projects++
                                    }
                                    itemCount++
                                }
                            } catch (t: Throwable) {
                                failed++
                                Logger.e("Gagal mengimpor ${entry.name}: ${t.message}", t)
                            } finally {
                                try { temp.delete() } catch (_: Exception) {}
                            }
                        }
                        zip.closeEntry()
                        entry = try { zip.nextEntry } catch (_: Throwable) { null }
                    }
                }
            } catch (t: Throwable) {
                Logger.e("Gagal mengekstrak ZIP: ${t.message}", t)
                failed++
            }

            ZipImportResult(
                projects = projects,
                bundles = bundles,
                folderNames = folderIds.keys.toList(),
                failed = failed
            )
        }

    private fun openZip(uri: Uri): ZipInputStream? =
        context.contentResolver.openInputStream(uri)?.let { ZipInputStream(it.buffered()) }

    private fun collectZipPaths(uri: Uri): List<String> {
        val out = ArrayList<String>()
        openZip(uri)?.use { zip ->
            var entry = zip.nextEntry
            var scanned = 0
            while (entry != null && scanned < MAX_SCAN_ENTRIES) {
                scanned++
                if (!entry.isDirectory) out.add(entry.name)
                zip.closeEntry()
                entry = try { zip.nextEntry } catch (_: Throwable) { null }
            }
        }
        return out
    }

    /** @return jumlah byte tertulis, atau -1 bila melebihi [maxBytes]. */
    private fun copyEntryCapped(zip: ZipInputStream, target: File, maxBytes: Long): Long {
        var total = 0L
        FileOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = zip.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return -1L
                out.write(buf, 0, n)
            }
            out.flush()
        }
        return total
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_IMPORT_ITEMS = 300
        const val MAX_SCAN_ENTRIES = 5000
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    }
}
