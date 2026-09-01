package com.mochits.app.imaging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class LaMaModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    CORRUPTED_ERROR
}

class LaMaModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val modelFileName = "lama_manga.onnx"
    private val minValidSizeBytes = 50_000_000L // Valid model is ~196MB

    // Primary & Mirror URLs for LaMa Manga ONNX model (~196MB) on MochiTs GitHub Releases
    private val modelUrls = listOf(
        "https://github.com/Mochizhouuu/MochiTs/releases/download/v1.0.0-models/lama_manga.onnx",
        "https://github.com/Mochizhouuu/models/releases/download/v1.0.0/lama_manga.onnx"
    )

    private val _modelStatus = MutableStateFlow(LaMaModelStatus.NOT_DOWNLOADED)
    val modelStatus: StateFlow<LaMaModelStatus> = _modelStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    init {
        checkModelStatus()
    }

    fun getModelFile(): File {
        val dir = File(appContext.filesDir, "models").apply { mkdirs() }
        return File(dir, modelFileName)
    }

    private fun getTempFile(): File {
        val targetFile = getModelFile()
        return File(targetFile.parentFile, "$modelFileName.tmp")
    }

    fun checkModelStatus(): LaMaModelStatus {
        val targetFile = getModelFile()
        val tempFile = getTempFile()

        if (tempFile.exists() && _modelStatus.value != LaMaModelStatus.DOWNLOADING) {
            tempFile.delete()
        }

        val status = when {
            targetFile.exists() && targetFile.length() > minValidSizeBytes -> LaMaModelStatus.DOWNLOADED
            targetFile.exists() && targetFile.length() <= minValidSizeBytes -> LaMaModelStatus.CORRUPTED_ERROR
            else -> LaMaModelStatus.NOT_DOWNLOADED
        }
        if (_modelStatus.value != LaMaModelStatus.DOWNLOADING) {
            _modelStatus.value = status
        }
        return status
    }

    fun isModelDownloaded(): Boolean {
        return checkModelStatus() == LaMaModelStatus.DOWNLOADED
    }

    fun deleteModel(): Boolean {
        val targetFile = getModelFile()
        val tempFile = getTempFile()
        if (tempFile.exists()) tempFile.delete()
        val deleted = if (targetFile.exists()) targetFile.delete() else true
        _modelStatus.value = LaMaModelStatus.NOT_DOWNLOADED
        _downloadProgress.value = 0f
        return deleted
    }

    suspend fun downloadModel(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            _downloadProgress.value = 1.0f
            onProgress(1.0f)
            return@withContext true
        }

        _modelStatus.value = LaMaModelStatus.DOWNLOADING
        _downloadProgress.value = 0f

        val targetFile = getModelFile()
        val tempFile = getTempFile()

        if (tempFile.exists()) {
            tempFile.delete()
        }

        for (urlString in modelUrls) {
            var connection: HttpURLConnection? = null
            var input: InputStream? = null
            var output: FileOutputStream? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_MOVED_TEMP && responseCode != HttpURLConnection.HTTP_MOVED_PERM) {
                    continue
                }

                val fileLength = connection.contentLength
                input = connection.inputStream
                output = FileOutputStream(tempFile)

                val data = ByteArray(16384)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    val progress = if (fileLength > 0) total.toFloat() / fileLength.toFloat() else 0f
                    _downloadProgress.value = progress
                    onProgress(progress)
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()
                connection.disconnect()

                if (tempFile.exists() && tempFile.length() > minValidSizeBytes) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (renamed || (targetFile.exists() && targetFile.length() > minValidSizeBytes)) {
                        _downloadProgress.value = 1.0f
                        _modelStatus.value = LaMaModelStatus.DOWNLOADED
                        onProgress(1.0f)
                        return@withContext true
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                if (tempFile.exists()) tempFile.delete()
            } finally {
                try {
                    output?.close()
                    input?.close()
                    connection?.disconnect()
                } catch (_: Throwable) {}
            }
        }

        if (tempFile.exists()) tempFile.delete()
        _modelStatus.value = LaMaModelStatus.CORRUPTED_ERROR
        false
    }

    companion object {
        @Volatile
        private var INSTANCE: LaMaModelManager? = null

        fun getInstance(context: Context): LaMaModelManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LaMaModelManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetInstanceForTesting() {
            INSTANCE = null
        }
    }
}
