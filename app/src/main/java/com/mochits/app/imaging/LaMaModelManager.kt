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

data class LaMaDownloadErrorInfo(
    val stage: String,
    val httpCode: Int = -1,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val url: String = ""
) {
    fun toFormattedString(): String {
        val sb = StringBuilder()
        sb.append("Tahap Kegagalan: ").append(stage).append("\n")
        if (httpCode != -1) {
            sb.append("HTTP Response Code: ").append(httpCode).append("\n")
        }
        if (!exceptionType.isNullOrEmpty()) {
            sb.append("Jenis Exception: ").append(exceptionType).append("\n")
        }
        if (!exceptionMessage.isNullOrEmpty()) {
            sb.append("Pesan Exception: ").append(exceptionMessage).append("\n")
        }
        if (url.isNotEmpty()) {
            sb.append("URL Terakhir: ").append(url)
        }
        return sb.toString().trim()
    }
}

class LaMaModelManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val modelFileName = "lama_manga.onnx"
    private val minValidSizeBytes = 50_000_000L // Valid model is ~196MB

    // HuggingFace primary and fallback URLs for LaMa Manga ONNX model (~196MB)
    private val modelUrls = listOf(
        "https://huggingface.co/mayocream/lama-manga-onnx/resolve/main/lama-manga.onnx"
    )

    private val _modelStatus = MutableStateFlow(LaMaModelStatus.NOT_DOWNLOADED)
    val modelStatus: StateFlow<LaMaModelStatus> = _modelStatus.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _lastDownloadError = MutableStateFlow<LaMaDownloadErrorInfo?>(null)
    val lastDownloadError: StateFlow<LaMaDownloadErrorInfo?> = _lastDownloadError.asStateFlow()

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
        _lastDownloadError.value = null
        return deleted
    }

    fun clearLastDownloadError() {
        _lastDownloadError.value = null
    }

    suspend fun downloadModel(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            _downloadProgress.value = 1.0f
            onProgress(1.0f)
            _lastDownloadError.value = null
            return@withContext true
        }

        _modelStatus.value = LaMaModelStatus.DOWNLOADING
        _downloadProgress.value = 0f
        _lastDownloadError.value = null

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

                        // Set standard User-Agent header to avoid HF/CDN blocking
                        connection.setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0"
                        )

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
                                _lastDownloadError.value = LaMaDownloadErrorInfo(
                                    stage = "Proses Redirect (Redirect #$redirectCount)",
                                    httpCode = resCode,
                                    exceptionMessage = "Header 'Location' hilang saat redirect",
                                    url = currentUrl
                                )
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
                        _lastDownloadError.value = LaMaDownloadErrorInfo(
                            stage = "Evaluasi Header Range (416 Range Not Satisfiable)",
                            httpCode = 416,
                            url = currentUrl
                        )
                        retryCount++
                        if (retryCount < maxRetries) delay(1000L * retryCount)
                        continue
                    }

                    val isPartialContent = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                    val isOK = (responseCode == HttpURLConnection.HTTP_OK)

                    if (!isPartialContent && !isOK) {
                        Log.w(TAG, "Download failed with unexpected response code $responseCode from $currentUrl")
                        _lastDownloadError.value = LaMaDownloadErrorInfo(
                            stage = "Evaluasi Status HTTP Response",
                            httpCode = responseCode,
                            exceptionMessage = "Menerima HTTP $responseCode dari server CDN",
                            url = currentUrl
                        )
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
                        _lastDownloadError.value = LaMaDownloadErrorInfo(
                            stage = "Membuka InputStream Koneksi",
                            httpCode = responseCode,
                            exceptionMessage = "InputStream bernilai null",
                            url = currentUrl
                        )
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
                            _lastDownloadError.value = null
                            onProgress(1.0f)
                            return@withContext true
                        } else {
                            Log.e(TAG, "Failed to rename temp file ${tempFile.absolutePath} to ${targetFile.absolutePath}")
                            _lastDownloadError.value = LaMaDownloadErrorInfo(
                                stage = "Pemindahan File Sementara (.tmp -> .onnx)",
                                exceptionMessage = "Gagal mengubah nama file sementara ke file tujuan",
                                url = currentUrl
                            )
                        }
                    } else {
                        Log.w(TAG, "Downloaded file size (${tempFile.length()}) is smaller than min valid size ($minValidSizeBytes)")
                        _lastDownloadError.value = LaMaDownloadErrorInfo(
                            stage = "Verifikasi Ukuran File Model",
                            exceptionMessage = "Ukuran file (${tempFile.length()} bytes) kurang dari batas minimum ($minValidSizeBytes bytes)",
                            url = currentUrl
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Exception during download attempt ${retryCount + 1}/$maxRetries from $urlString: ${t.message}", t)
                    val safeHttpCode = try { connection?.responseCode ?: -1 } catch (_: Throwable) { -1 }
                    _lastDownloadError.value = LaMaDownloadErrorInfo(
                        stage = "Proses Download / Stream Data (Percobaan ${retryCount + 1}/$maxRetries)",
                        httpCode = safeHttpCode,
                        exceptionType = t.javaClass.simpleName,
                        exceptionMessage = t.message ?: t.toString(),
                        url = urlString
                    )
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
        if (_lastDownloadError.value == null) {
            _lastDownloadError.value = LaMaDownloadErrorInfo(
                stage = "Semua URL Percobaan Gagal",
                exceptionMessage = "Seluruh percobaan mengunduh dari semua URL mirror gagal",
                url = modelUrls.last()
            )
        }
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
