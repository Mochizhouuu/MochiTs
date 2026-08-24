package com.mochits.text

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class CustomFontItem(
    val name: String,
    val path: String
)

class FontManager(private val context: Context) {
    private val fontsDir = File(context.filesDir, "custom_fonts").apply {
        if (!exists()) mkdirs()
    }

    fun getInstalledFonts(): List<CustomFontItem> {
        val files = fontsDir.listFiles() ?: return emptyList()
        return files.filter { it.extension.lowercase() in listOf("ttf", "otf") }
            .map { file ->
                CustomFontItem(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath
                )
            }.sortedBy { it.name }
    }

    fun importFont(uri: Uri, fontName: String): CustomFontItem? {
        return try {
            val extension = context.contentResolver.getType(uri)?.let { type ->
                if (type.contains("opentype") || uri.toString().lowercase().endsWith(".otf")) "otf" else "ttf"
            } ?: if (uri.toString().lowercase().endsWith(".otf")) "otf" else "ttf"

            val cleanName = fontName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val targetFile = File(fontsDir, "$cleanName.$extension")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                CustomFontItem(name = cleanName, path = targetFile.absolutePath)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteFont(fontPath: String): Boolean {
        val file = File(fontPath)
        if (file.exists() && file.parentFile?.absolutePath == fontsDir.absolutePath) {
            return file.delete()
        }
        return false
    }
}
