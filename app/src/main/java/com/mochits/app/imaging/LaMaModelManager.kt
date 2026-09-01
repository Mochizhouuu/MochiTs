package com.mochits.app.imaging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // HuggingFace primary and fallback URLs for LaMa Manga ONNX model (~196MB)
    private val modelUrls = listOf(
        "https://huggingface.co/mayocream/lama-manga-onnx/resolve/main/lama-manga.onnx",
        "https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/main/lama-manga_fp16.onnx"
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

        for (urlString in modelUrls) {
            val maxRetries = 3
            var retryCount = 0

            while (retryCount < maxRetries) {
                var connection: HttpURLConnection? = null
                var input: InputStream? = null
                var output: FileOutputStream? = null
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                try {
                    Log.d(TAG, "Starting download attempt ${retryCount + 1}/$maxRetries from $urlString (existingBytes: $existingBytes)")

                    var currentUrl = urlString
                    var redirectCount = 0
                    val maxRedirects = 10
                    var requestRangeBytes = existingBytes

                    while (redirectCount < maxRedirects) {
                        val url = URL(currentUrl)
                        connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 15000
                        connection.readTimeout = 60000
                        connection.instanceFollowRedirects = false

                        if (requestRangeBytes > 0) {
                            connection.setRequestProperty("Range", "bytes=$requestRangeBytes-")
                        }

                        connection.connect()
                        val resCode = connection.responseCode
                        Log.d(TAG, "HTTP response code: $resCode for $currentUrl (redirect #$redirectCount)")

                        if (resCode == HttpURLConnection.HTTP_MOVED_PERM ||
                            resCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                            resCode == HttpURLConnection.HTTP_SEE_OTHER ||
                            resCode == 307 ||
                            resCode == 308
                        ) {
                            val location = connection.getHeaderField("Location")
                            connection.disconnect()
                            if (location.isNullOrEmpty()) {
                                Log.w(TAG, "Redirect location header missing at $currentUrl")
                                break
                            }
                            currentUrl = URL(url, location).toString()
                            redirectCount++
                            Log.d(TAG, "Following redirect to $currentUrl")
                            continue
                        }
                        break
                    }

                    val responseCode = connection?.responseCode ?: -1
                    if (responseCode == 416) { // HTTP 416 Range Not Satisfiable
                        Log.w(TAG, "HTTP 416 Range Not Satisfiable from $currentUrl. Deleting temp file and retrying from byte 0.")
                        if (tempFile.exists()) tempFile.delete()
                        retryCount++
                        if (retryCount < maxRetries) delay(1000L * retryCount)
                        continue
                    }

                    val isPartialContent = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                    val isOK = (responseCode == HttpURLConnection.HTTP_OK)

                    if (!isPartialContent && !isOK) {
                        Log.w(TAG, "Download failed with unexpected response code $responseCode from $currentUrl")
                        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                            // Don't retry on 404, break retry loop to try next URL
                            break
                        }
                        retryCount++
                        if (retryCount < maxRetries) delay(1000L * retryCount)
                        continue
                    }

                    val append = isPartialContent && requestRangeBytes > 0
                    val contentLength = connection?.contentLengthLong ?: -1L
                    val totalExpectedLength = if (append) requestRangeBytes + contentLength else contentLength

                    val stream = connection?.inputStream
                    if (stream == null) {
                        Log.w(TAG, "InputStream is null for $currentUrl")
                        retryCount++
                        if (retryCount < maxRetries) delay(1000L * retryCount)
                        continue
                    }
                    input = stream
                    output = FileOutputStream(tempFile, append)

                    val data = ByteArray(16384)
                    var currentDownloadedBytes = if (append) requestRangeBytes else 0L
                    var count: Int

                    while (stream.read(data).also { count = it } != -1) {
                        output.write(data, 0, count)
                        currentDownloadedBytes += count.toLong()

                        val progress = if (totalExpectedLength > 0) {
                            currentDownloadedBytes.toFloat() / totalExpectedLength.toFloat()
                        } else 0f
                        _downloadProgress.value = progress
                        onProgress(progress)
                    }

                    output.flush()
                    output.close()
                    stream.close()
                    connection?.disconnect()

                    Log.d(TAG, "Download stream completed for $urlString. Temp file size: ${tempFile.length()} bytes")

                    if (tempFile.exists() && tempFile.length() > minValidSizeBytes) {
                        if (targetFile.exists()) targetFile.delete()
                        val renamed = tempFile.renameTo(targetFile)
                        if (renamed || (targetFile.exists() && targetFile.length() > minValidSizeBytes)) {
                            Log.i(TAG, "Model successfully downloaded and saved to ${targetFile.absolutePath}")
                            _downloadProgress.value = 1.0f
                            _modelStatus.value = LaMaModelStatus.DOWNLOADED
                            onProgress(1.0f)
                            return@withContext true
                        } else {
                            Log.e(TAG, "Failed to rename temp file ${tempFile.absolutePath} to ${targetFile.absolutePath}")
                        }
                    } else {
                        Log.w(TAG, "Downloaded file size (${tempFile.length()}) is smaller than min valid size ($minValidSizeBytes)")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Exception during download attempt ${retryCount + 1}/$maxRetries from $urlString: ${t.message}", t)
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(1000L * retryCount)
                    }
                } finally {
                    try {
                        output?.close()
                        input?.close()
                        connection?.disconnect()
                    } catch (_: Throwable) {}
                }
            }
        }

        if (tempFile.exists()) tempFile.delete()
        Log.e(TAG, "All download attempts failed across all configured URLs.")
        _modelStatus.value = LaMaModelStatus.CORRUPTED_ERROR
        false
    }

    companion object {
        private const val TAG = "LaMaModelManager"

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
