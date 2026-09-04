package com.mochits.app.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

class FontRepository(
    private val context: Context,
    private val customFontDao: CustomFontDao
) {
    private val customFontsDir: File by lazy {
        File(context.filesDir, "custom_fonts").apply {
            if (!exists()) mkdirs()
        }
    }

    private val builtInFontsDir: File by lazy {
        File(context.filesDir, "fonts").apply {
            if (!exists()) mkdirs()
        }
    }

    private val builtInFontsCache = mutableListOf<FontItem>()

    private fun extractBuiltInFontsIfNeeded() {
        val existingFontFiles = builtInFontsDir.listFiles()?.filter {
            it.isFile && (it.name.lowercase().endsWith(".ttf") || it.name.lowercase().endsWith(".otf"))
        }
        if (!existingFontFiles.isNullOrEmpty()) {
            return
        }

        builtInFontsDir.mkdirs()
        try {
            context.assets.open("fonts.zip").use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    val buffer = ByteArray(8192)
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val lowerName = entry.name.lowercase()
                            if (lowerName.endsWith(".ttf") || lowerName.endsWith(".otf")) {
                                val fileName = entry.name.substringAfterLast('/')
                                if (fileName.isNotEmpty()) {
                                    val outFile = File(builtInFontsDir, fileName)
                                    FileOutputStream(outFile).use { output ->
                                        var len: Int
                                        while (zipStream.read(buffer).also { len = it } > 0) {
                                            output.write(buffer, 0, len)
                                        }
                                    }
                                }
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getBuiltInFonts(): List<FontItem> = withContext(Dispatchers.IO) {
        if (builtInFontsCache.isNotEmpty()) return@withContext builtInFontsCache

        extractBuiltInFontsIfNeeded()

        val defaultFonts = listOf(
            FontItem(name = "Default", fontNameKey = "Default"),
            FontItem(name = "Sans-Serif", fontNameKey = "Sans"),
            FontItem(name = "Serif", fontNameKey = "Serif"),
            FontItem(name = "Monospace", fontNameKey = "Monospace")
        )

        val fontFiles = builtInFontsDir.listFiles()?.filter {
            it.isFile && (it.name.lowercase().endsWith(".ttf") || it.name.lowercase().endsWith(".otf"))
        } ?: emptyList()

        val extractedFonts = fontFiles.map { file ->
            val cleanName = cleanFontName(file.name)
            FontItem(
                name = cleanName,
                fontNameKey = cleanName,
                isCustom = false,
                filePath = file.absolutePath
            )
        }

        val allBuiltIn = defaultFonts + extractedFonts.sortedBy { it.name }
        builtInFontsCache.clear()
        builtInFontsCache.addAll(allBuiltIn)
        allBuiltIn
    }

    private fun cleanFontName(filename: String): String {
        var name = filename.substringBeforeLast(".")
        name = name.replace("_", " ")
        return name
    }

    fun getAllFontsFlow(): Flow<List<FontItem>> = flow {
        val builtIn = getBuiltInFonts()
        customFontDao.getAllCustomFonts().collect { customEntities ->
            val customItems = customEntities.map { entity ->
                FontItem(
                    name = entity.displayName,
                    fontNameKey = entity.displayName,
                    isCustom = true,
                    filePath = entity.filePath
                )
            }
            emit(builtIn + customItems)
        }
    }

    suspend fun importCustomFont(uri: Uri, rawFileName: String?): Result<FontItem> = withContext(Dispatchers.IO) {
        try {
            val fileId = UUID.randomUUID().toString()
            val extension = when {
                rawFileName?.lowercase()?.endsWith(".otf") == true -> ".otf"
                else -> ".ttf"
            }
            val targetFile = File(customFontsDir, "$fileId$extension")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Gagal membaca file dari penyimpanan"))

            val typeface = try {
                Typeface.createFromFile(targetFile)
            } catch (e: Exception) {
                null
            }

            if (typeface == null) {
                targetFile.delete()
                return@withContext Result.failure(IllegalArgumentException("File yang dipilih bukan file font valid atau corrupt"))
            }

            val displayName = rawFileName?.substringBeforeLast(".") ?: targetFile.nameWithoutExtension
            val entity = CustomFontEntity(
                id = fileId,
                displayName = displayName,
                filePath = targetFile.absolutePath
            )

            customFontDao.insertCustomFont(entity)

            val item = FontItem(
                name = displayName,
                fontNameKey = displayName,
                isCustom = true,
                filePath = targetFile.absolutePath
            )
            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCustomFont(fontItem: FontItem) = withContext(Dispatchers.IO) {
        if (!fontItem.isCustom || fontItem.filePath == null) return@withContext
        val file = File(fontItem.filePath)
        if (file.exists()) {
            file.delete()
        }
        val customList = customFontDao.getAllCustomFontsList()
        val match = customList.find { it.filePath == fontItem.filePath }
        if (match != null) {
            customFontDao.deleteCustomFont(match.id)
        }
    }
}
