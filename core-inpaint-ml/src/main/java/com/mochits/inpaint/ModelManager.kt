package com.mochits.inpaint

import android.content.Context
import com.mochits.common.OperationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Guard unduhan konkuren: dua download paralel menulis file tmp sama → korup. */
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()

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

    suspend fun downloadModel(downloadUrl: String = DEFAULT_MODEL_URL): OperationResult<Unit> =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                if (modelFile.exists()) {
                    _downloadState.value = ModelDownloadState(isDownloaded = true)
                    return@withContext OperationResult.Success(Unit)
                }

                _downloadState.value = ModelDownloadState(isDownloading = true, progressPercent = 0)

                // Nama tmp unik agar unduhan paralel tidak saling menimpa
                val tempFile = File(
                    modelFile.parentFile,
                    "lama_int8.tflite.${System.currentTimeMillis()}.tmp"
                )
                try {
                    val url = URL(downloadUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.connect()

                    // Redirect sudah diikuti otomatis; hanya 200 yang valid.
                    // Menerima 301/302 berisiko menyimpan halaman redirect
                    // sebagai "model".
                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        val errorMsg = "Gagal mengunduh model: HTTP $responseCode"
                        _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
                        return@withContext OperationResult.Failure(IllegalStateException(errorMsg), errorMsg)
                    }

                    val fileLength = connection.contentLengthLong
                    val inputStream = connection.inputStream
                    val outputStream = FileOutputStream(tempFile)

                    val data = ByteArray(8192)
                    var total: Long = 0
                    var lastProgress = -1
                    var count: Int
                    while (inputStream.read(data).also { count = it } != -1) {
                        total += count.toLong()
                        outputStream.write(data, 0, count)
                        if (fileLength > 0) {
                            val percent = ((total * 100) / fileLength).toInt()
                            // Throttle: update StateFlow hanya saat persen berubah
                            if (percent != lastProgress) {
                                lastProgress = percent
                                _downloadState.value =
                                    ModelDownloadState(isDownloading = true, progressPercent = percent)
                            }
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    connection.disconnect()

                    // Validasi minimum: file non-truncate & rename atomik.
                    // Hasil renameTo TIDAK boleh diabaikan — kalau gagal dan
                    // tetap dianggap sukses, engine akan load file kosong.
                    val minValidSize = 1L shl 20 // <1 MB jelas bukan model LaMa
                    if (!tempFile.exists() || tempFile.length() < minValidSize) {
                        tempFile.delete()
                        val errorMsg = "Berkas model hasil unduhan tidak lengkap"
                        _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
                        return@withContext OperationResult.Failure(IllegalStateException(errorMsg), errorMsg)
                    }
                    if (modelFile.exists() && !modelFile.delete()) {
                        tempFile.delete()
                        val errorMsg = "Gagal mengganti model lama"
                        _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
                        return@withContext OperationResult.Failure(IllegalStateException(errorMsg), errorMsg)
                    }
                    if (!tempFile.renameTo(modelFile)) {
                        tempFile.delete()
                        val errorMsg = "Gagal memindahkan model hasil unduhan"
                        _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
                        return@withContext OperationResult.Failure(IllegalStateException(errorMsg), errorMsg)
                    }

                    _downloadState.value = ModelDownloadState(isDownloaded = true, isDownloading = false, progressPercent = 100)
                    OperationResult.Success(Unit)
                } catch (e: Exception) {
                    if (tempFile.exists()) tempFile.delete()
                    val errorMsg = "Error unduh model: ${e.localizedMessage}"
                    _downloadState.value = ModelDownloadState(errorMessage = errorMsg)
                    OperationResult.Failure(e, errorMsg)
                }
            }
        }

    suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        val deleted = if (modelFile.exists()) modelFile.delete() else true
        _downloadState.value = ModelDownloadState(isDownloaded = false)
        deleted
    }

    companion object {
        const val DEFAULT_MODEL_URL = "https://github.com/Mochizhouuu/MochiTs/releases/download/v1.0.0/lama_int8.tflite"
    }
}
