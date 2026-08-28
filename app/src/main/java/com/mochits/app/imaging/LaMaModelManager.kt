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

    private val modelFileName = "lama_fp16.tflite"

    // Primary & Mirror URLs for LaMa TFLite model (~40MB)
    private val modelUrls = listOf(
        "https://huggingface.co/MochiTs/LaMa-TFLite/resolve/main/lama_fp16.tflite",
        "https://raw.githubusercontent.com/Mochizhouuu/models/main/lama_fp16.tflite"
    )

    fun getModelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return File(dir, modelFileName)
    }

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 5_000_000L // Valid model is ~40MB
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
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    continue
                }

                val fileLength = connection.contentLength
                input = connection.inputStream
                output = FileOutputStream(tempFile)

                val data = ByteArray(8192)
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

                if (tempFile.length() > 1000L) { // Successfully downloaded
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
