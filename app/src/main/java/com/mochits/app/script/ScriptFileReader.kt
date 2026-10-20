package com.mochits.app.script

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pembaca file script TL: `.txt` (UTF-8/UTF-16) dan `.docx`.
 *
 * DOCX dibaca tanpa library berat: ambil `word/document.xml` dari arsip ZIP,
 * ekstrak `<w:p>`/`<w:t>`, tab, dan line-break. Karakter tak terlihat
 * (BOM/zero-width space) dinormalisasi agar simbol di awal baris tetap
 * terdeteksi.
 */
object ScriptFileReader {

    const val TXT_MIME = "text/plain"
    const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val MAX_XML_BYTES = 20 * 1024 * 1024

    data class ReadResult(val text: String, val fileName: String?)

    /** Baca URI SAF. Null bila jenis file tidak didukung atau gagal dibaca. */
    fun read(context: Context, uri: Uri, displayName: String? = null): ReadResult? {
        return try {
            val name = displayName ?: queryDisplayName(context, uri)
            val lower = name?.lowercase()
            val mime = try {
                context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            val nameTxt = lower?.endsWith(".txt") == true
            val nameDocx = lower?.endsWith(".docx") == true
            val mimeTxt = mime == null || mime == TXT_MIME || mime.startsWith("text/")
            val mimeDocx = mime == DOCX_MIME
            if (lower != null && !nameTxt && !nameDocx && !mimeTxt && !mimeDocx) return null

            val bytes = context.contentResolver.openInputStream(uri)
                ?.use { it.readCapped(MAX_FILE_BYTES) }
                ?: return null
            val raw = if (isZip(bytes)) {
                docxBytesToText(bytes)
            } else {
                decodeText(bytes)
            } ?: return null
            ReadResult(normalize(raw), name)
        } catch (_: Throwable) {
            null
        }
    }

    internal fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** Ekstrak teks dari isi file .docx; null bila `word/document.xml` tidak ada. */
    internal fun docxBytesToText(bytes: ByteArray): String? {
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        entry.name.equals("word/document.xml", ignoreCase = true)
                    ) {
                        val xml = zip.readCapped(MAX_XML_BYTES) ?: return null
                        return documentXmlToText(xml)
                    }
                    entry = zip.nextEntry
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    internal fun documentXmlToText(xml: ByteArray): String? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ByteArrayInputStream(xml), null)
            val sb = StringBuilder()
            var inParagraph = false
            var inText = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.substringAfter(':').lowercase()) {
                            "p" -> {
                                inParagraph = true
                                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                            }
                            "t" -> inText = true
                            "tab" -> if (inParagraph) sb.append("    ")
                            "br", "cr" -> if (inParagraph) sb.append('\n')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inParagraph && inText) sb.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.substringAfter(':').lowercase()) {
                            "p" -> inParagraph = false
                            "t" -> inText = false
                        }
                    }
                }
                event = parser.next()
            }
            sb.toString().trim()
        } catch (_: Throwable) {
            null
        }
    }

    /** Decode teks dengan deteksi BOM UTF-8/UTF-16. */
    internal fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }

    internal fun normalize(text: String): String =
        text.replace("\uFEFF", "").replace("\u200B", "")

    private fun InputStream.readCapped(max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }
}
