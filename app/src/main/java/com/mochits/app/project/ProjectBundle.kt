package com.mochits.app.project

import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bundel proyek `.mts` (F8): arsip ZIP berisi seluruh folder proyek +
 * snapshot baris database, sehingga bisa dipindah antar device dan tetap
 * bisa diedit penuh (mirip peran PSD, secara teknis seperti .kra).
 *
 * Isi: project.json + base_image.png + thumbnail.jpg + layers/* +
 * history/* + selected_layer.txt.
 */
object ProjectBundle {

    const val EXTENSION = "mts"
    const val MIME_TYPE = "application/zip"
    const val META_NAME = "project.json"
    const val FORMAT_VERSION = 1

    /** Snapshot baris database (thumbnailPath TIDAK ikut: dibangun ulang). */
    data class BundleMeta(
        val formatVersion: Int = FORMAT_VERSION,
        val title: String,
        val width: Int,
        val height: Int,
        val createdAt: Long,
        val updatedAt: Long,
        val isTransparent: Boolean = false,
        val layersJson: String = "[]"
    )

    private val gson = Gson()
    private const val BUFFER_SIZE = 64 * 1024

    fun fileNameFor(title: String): String {
        val clean = title.trim().take(80)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "proyek" }
        return "$clean.$EXTENSION"
    }

    /**
     * Tulis bundel dari folder proyek + entity. @return true bila sukses.
     * Berjalan di thread IO pemanggil.
     */
    fun exportTo(output: OutputStream, projectDir: File, entity: ProjectEntity): Boolean {
        if (!projectDir.isDirectory) return false
        return try {
            val meta = BundleMeta(
                title = entity.title,
                width = entity.width,
                height = entity.height,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                isTransparent = entity.isTransparent,
                layersJson = entity.layersJson
            )
            ZipOutputStream(output.buffered(BUFFER_SIZE)).use { zip ->
                zip.putNextEntry(ZipEntry(META_NAME))
                zip.write(gson.toJson(meta).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val baseLen = projectDir.canonicalPath.length + 1
                projectDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = file.canonicalPath.substring(baseLen).replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry(rel))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            true
        } catch (t: Throwable) {
            com.mochits.app.util.Logger.e("Gagal menulis bundel: ${t.message}", t)
            false
        }
    }

    /**
     * Impor bundel ke filesDir dengan id BARU (anti-tabrakan); path absolut
     * di layersJson di-remap ke filesDir kini; zip-slip dijaga.
     * @return entity baru yang sudah diinsert, atau null bila gagal.
     */
    fun importFrom(
        input: InputStream,
        filesDir: File,
        readLayers: (String) -> List<com.mochits.app.model.Layer>,
        writeLayers: (List<com.mochits.app.model.Layer>) -> String,
        insert: (ProjectEntity) -> Unit
    ): ProjectEntity? {
        val staging = File(filesDir, "cache/bundle_import_${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            val stagingBase = staging.canonicalPath + File.separator
            ZipInputStream(input.buffered(BUFFER_SIZE)).use { zip ->
                var entry = zip.nextEntry
                var count = 0
                while (entry != null) {
                    if (++count > 2000) return null
                    val dest = File(staging, entry.name).canonicalFile
                    // Zip-slip guard: tolak path absolut / .. / di luar staging.
                    if (!dest.canonicalPath.startsWith(stagingBase)) return null
                    if (!entry.isDirectory) {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val metaFile = File(staging, META_NAME)
            if (!metaFile.isFile) return null
            val meta = try {
                gson.fromJson(metaFile.readText(Charsets.UTF_8), BundleMeta::class.java)
            } catch (_: Exception) {
                null
            } ?: return null
            if (meta.formatVersion != FORMAT_VERSION) return null
            if (meta.width <= 0 || meta.height <= 0) return null

            val newId = UUID.randomUUID().toString()
            val destDir = File(filesDir, "projects/$newId").apply { mkdirs() }
            // Pindahkan isi (kecuali meta) ke folder proyek baru.
            staging.listFiles()?.forEach { child ->
                if (child.name == META_NAME) return@forEach
                val target = File(destDir, child.name)
                if (target.exists()) target.deleteRecursively()
                child.copyRecursively(target)
            }
            // Remap path absolut layers -> filesDir kini, hanya bila file ada.
            val remappedJson = try {
                val layers = readLayers(meta.layersJson)
                val fixed = layers.map { layer ->
                    if (layer is com.mochits.app.model.Layer.ImageLayer && layer.imagePath != null) {
                        val name = File(layer.imagePath).name
                        val candidate = File(destDir, "layers/$name")
                        if (candidate.exists() && candidate.length() > 0) {
                            layer.copy(imagePath = candidate.absolutePath)
                        } else {
                            layer.copy(imagePath = null)
                        }
                    } else layer
                }
                writeLayers(fixed)
            } catch (_: Exception) {
                meta.layersJson
            }
            val thumb = File(destDir, "thumbnail.jpg").takeIf { it.exists() && it.length() > 0 }
            val entity = ProjectEntity(
                id = newId,
                title = meta.title,
                width = meta.width.coerceIn(1, 32768),
                height = meta.height.coerceIn(1, 32768),
                createdAt = meta.createdAt,
                updatedAt = System.currentTimeMillis(),
                thumbnailPath = thumb?.absolutePath,
                isTransparent = meta.isTransparent,
                layersJson = remappedJson
            )
            insert(entity)
            return entity
        } catch (t: Throwable) {
            com.mochits.app.util.Logger.e("Gagal mengimpor bundel: ${t.message}", t)
            return null
        } finally {
            try { staging.deleteRecursively() } catch (_: Exception) {}
        }
    }
}
