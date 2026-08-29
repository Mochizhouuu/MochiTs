package com.mochits.app.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mochits_export_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_EXPORT_FOLDER_URI = "export_folder_uri"
    }

    fun getExportFolderUri(): Uri? {
        val uriString = prefs.getString(KEY_EXPORT_FOLDER_URI, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            null
        }
    }

    fun saveExportFolderUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_EXPORT_FOLDER_URI, uri.toString()).apply()
            true
        } catch (e: Exception) {
            // Even if takePersistableUriPermission fails, we still try to save if readable
            prefs.edit().putString(KEY_EXPORT_FOLDER_URI, uri.toString()).apply()
            false
        }
    }

    fun isFolderValid(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile != null && docFile.exists() && docFile.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    fun getFolderName(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.name ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    fun clearExportFolderUri() {
        prefs.edit().remove(KEY_EXPORT_FOLDER_URI).apply()
    }
}
