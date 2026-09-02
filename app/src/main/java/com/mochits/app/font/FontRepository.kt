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

class FontRepository(
    private val context: Context,
    private val customFontDao: CustomFontDao
) {
    private val customFontsDir: File by lazy {
        File(context.filesDir, "custom_fonts").apply {
            if (!exists()) mkdirs()
        }
    }

    private val builtInFontsCache = mutableListOf<FontItem>()

    suspend fun getBuiltInFonts(): List<FontItem> = withContext(Dispatchers.IO) {
        if (builtInFontsCache.isNotEmpty()) return@withContext builtInFontsCache

        val defaultFonts = listOf(
            FontItem(name = "Default", fontNameKey = "Default"),
            FontItem(name = "Sans-Serif", fontNameKey = "Sans"),
            FontItem(name = "Serif", fontNameKey = "Serif"),
            FontItem(name = "Monospace", fontNameKey = "Monospace")
        )

        val assetFonts = mutableListOf<FontItem>()
        val assets = context.assets
        try {
            scanAssetDirectory(assets, "fonts", assetFonts)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val allBuiltIn = defaultFonts + assetFonts.sortedBy { it.name }
        builtInFontsCache.clear()
        builtInFontsCache.addAll(allBuiltIn)
        allBuiltIn
    }

    private fun scanAssetDirectory(assets: android.content.res.AssetManager, dirPath: String, result: MutableList<FontItem>) {
        val list = assets.list(dirPath) ?: return
        for (item in list) {
            val subPath = if (dirPath.isEmpty()) item else "$dirPath/$item"
            if (item.lowercase().endsWith(".ttf") || item.lowercase().endsWith(".otf")) {
                val cleanName = cleanFontName(item)
                result.add(
                    FontItem(
                        name = cleanName,
                        fontNameKey = cleanName,
                        isCustom = false,
                        assetPath = subPath
                    )
                )
            } else {
                scanAssetDirectory(assets, subPath, result)
            }
        }
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
