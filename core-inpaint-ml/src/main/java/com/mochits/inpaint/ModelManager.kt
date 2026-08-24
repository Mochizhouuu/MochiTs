package com.mochits.inpaint

import android.content.Context
import com.mochits.common.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelDownloadState(
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val errorMessage: String? = null
)

/**
 * Pengelola status dan unduhan model LaMa TFLite (`lama_int8.tflite`).
 */
class ModelManager(private val context: Context) {

    private val modelFile: File
        get() = File(context.filesDir, "models/lama_int8.tflite").apply {
            parentFile?.mkdirs()
        }

    private val _downloadState = MutableStateFlow(
        ModelDownloadState(isDownloaded = modelFile.exists())
    )
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    fun isModelAvailable(): Boolean {
        return modelFile.exists() || checkAssetModelExists()
    }

    fun getModelFilePath(): String {
        return if (modelFile.exists()) {
            modelFile.absolutePath
        } else {
            "models/lama_int8.tflite"
        }
    }

    private fun checkAssetModelExists(): Boolean {
        return try {
            context.assets.openFd("models/lama_int8.tflite").close()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun downloadModel(downloadUrl: String = DEFAULT_MODEL_URL): OperationResult<Unit> = withContext(Dispatchers.IO) {
        if (modelFile.exists()) {
            _downloadState.value = ModelDownloadState(isDownloaded = true)
            return@withContext OperationResult.Success(Unit)
        }

        _downloadState.value = ModelDownloadState(isDownloading = true, progressPercent = 0)

        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorMsg = "Gagal mengunduh model: HTTP ${connection.responseCode}"
                _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
                return@withContext OperationResult.Failure(IllegalStateException(errorMsg), errorMsg)
            }

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(modelFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            while (inputStream.read(data).also { count = it } != -1) {
                total += count.toLong()
                if (fileLength > 0) {
                    val percent = ((total * 100) / fileLength).toInt()
                    _downloadState.value = ModelDownloadState(isDownloading = true, progressPercent = percent)
                }
                outputStream.write(data, 0, count)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            _downloadState.value = ModelDownloadState(isDownloaded = true, isDownloading = false, progressPercent = 100)
            OperationResult.Success(Unit)
        } catch (e: Exception) {
            if (modelFile.exists()) modelFile.delete()
            val errorMsg = "Error unduh model: ${e.localizedMessage}"
            _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
            OperationResult.Failure(e, errorMsg)
        }
    }

    suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        val deleted = if (modelFile.exists()) modelFile.delete() else true
        _downloadState.value = ModelDownloadState(isDownloaded = false)
        deleted
    }

    companion object {
        const val DEFAULT_MODEL_URL = "https://github.com/advimman/lama/raw/main/lama_int8.tflite"
    }
}
