package com.mochits.app.imaging

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class LaMaModelManager(private val context: Context) {

    private val modelFileName = "lama_manga.onnx"

    // Primary & Mirror URLs for LaMa Manga ONNX model (~196MB) on MochiTs GitHub Releases
    private val modelUrls = listOf(
        "https://github.com/Mochizhouuu/MochiTs/releases/download/v1.0.0-models/lama_manga.onnx",
        "https://github.com/Mochizhouuu/models/releases/download/v1.0.0/lama_manga.onnx"
    )

    fun getModelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return File(dir, modelFileName)
    }

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 50_000_000L // Valid model is ~196MB
    }

    suspend fun downloadModel(
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelFile()
        if (isModelDownloaded()) {
            onProgress(1.0f)
            return@withContext true
        }

        val tempFile = File(targetFile.parentFile, "$modelFileName.tmp")

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
                    if (fileLength > 0) {
                        onProgress(total.toFloat() / fileLength.toFloat())
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()
                connection.disconnect()

                if (tempFile.length() > 50_000_000L) { // Successfully downloaded
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (renamed || targetFile.exists()) {
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
        false
    }
}
