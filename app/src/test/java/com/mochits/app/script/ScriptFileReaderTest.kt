package com.mochits.app.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ScriptFileReaderTest {

    private fun docx(xml: String, entryName: String = "word/document.xml"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun testDocx_extractsParagraphsTabsAndSymbols() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t># Nama: Blablabla</w:t></w:r></w:p>
                <w:p><w:r><w:t xml:space="preserve">  Class: Assassin</w:t></w:r></w:p>
                <w:p><w:r><w:t>- Halo</w:t></w:r><w:r><w:tab/><w:t>dunia</w:t></w:r></w:p>
                <w:p><w:r><w:t>//- lanjutan</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val text = ScriptFileReader.docxBytesToText(docx(xml))
        assertEquals(
            "# Nama: Blablabla\n  Class: Assassin\n- Halo    dunia\n//- lanjutan",
            text
        )
    }

    @Test
    fun testDocxWithLineBreak_keepsNewLineInsideParagraph() {
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:p><w:r><w:t>Awal</w:t><w:br/><w:t>akhir</w:t></w:r></w:p>
            </w:document>
        """.trimIndent()
        assertEquals("Awal\nakhir", ScriptFileReader.docxBytesToText(docx(xml)))
    }

    @Test
    fun testDocxWithoutDocumentXml_returnsNull() {
        val xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"
        assertNull(ScriptFileReader.docxBytesToText(docx(xml, "word/styles.xml")))
    }

    @Test
    fun testDecodeText_detectsUtf16Bom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Halo".toByteArray(Charsets.UTF_16LE)
        assertEquals("Halo", ScriptFileReader.decodeText(bytes))
    }

    @Test
    fun testNormalize_removesInvisiblePrefixChars() {
        assertEquals("//- lanjutan", ScriptFileReader.normalize("\uFEFF\u200B//- lanjutan"))
    }

    @Test
    fun testDocxText_parsesAsLinkedEntries() {
        val xml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:p><w:r><w:t>- Aku datang</w:t></w:r></w:p>
              <w:p><w:r><w:t>//- dan pergi</w:t></w:r></w:p>
            </w:document>
        """.trimIndent()
        val text = ScriptFileReader.docxBytesToText(docx(xml))!!
        val doc = ScriptParser.parse(
            text,
            listOf(ScriptSymbolEntry("-"), ScriptSymbolEntry("//-", linked = true)),
            "P\\d+"
        )
        val entries = doc.pages[0].entries
        assertEquals(2, entries.size)
        assertEquals("-", entries[1].linkedParent)
    }
}
