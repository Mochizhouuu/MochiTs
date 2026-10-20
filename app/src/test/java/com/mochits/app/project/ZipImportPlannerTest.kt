package com.mochits.app.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipImportPlannerTest {

    @Test
    fun plan_mengelompokkanFolderDanMengurutkanNatural() {
        val entries = ZipImportPlanner.plan(
            paths = listOf(
                "10.png",
                "2.png",
                "Bab 2/1.jpg",
                "bundle.mts",
                "catatan.txt"
            ),
            zipBase = "Komik"
        )
        assertEquals(listOf("2.png", "10.png", "Bab 2/1.jpg", "bundle.mts"), entries.map { it.path })
        assertEquals("Komik", entries[0].folderName)
        assertEquals("Komik", entries[1].folderName)
        assertEquals("Bab 2", entries[2].folderName)
        assertTrue(entries[2].isImage)
        assertTrue(entries[3].isBundle)
        assertEquals("bundle", entries[3].title)
    }

    @Test
    fun plan_mengabaikanMacOsHiddenTraversalDanNonGambar() {
        val entries = ZipImportPlanner.plan(
            paths = listOf(
                "__MACOSX/._1.png",
                ".hidden/2.png",
                "../evil.png",
                "folder/../3.png",
                "Thumbs.db",
                "5.PNG"
            ),
            zipBase = "Z"
        )
        assertEquals(listOf("5.PNG"), entries.map { it.path })
        assertTrue(entries.single().isImage)
    }

    @Test
    fun naturalCompare_angkaDibandingkanSebagaiAngka() {
        assertTrue(ZipImportPlanner.naturalCompare("2.png", "10.png") < 0)
        assertTrue(ZipImportPlanner.naturalCompare("10.png", "2.png") > 0)
        assertTrue(ZipImportPlanner.naturalCompare("Bab 2/a.png", "bab 10/a.png") < 0)
        assertEquals(0, ZipImportPlanner.naturalCompare("A.png", "a.png"))
    }

    @Test
    fun sanitize_danZipBaseName() {
        assertEquals("evil name", ZipImportPlanner.sanitizeName("../evil:name?"))
        assertEquals("Impor", ZipImportPlanner.sanitizeName("   "))
        assertEquals("Komik Bab 1", ZipImportPlanner.zipBaseName("Komik Bab 1.zip"))
        assertEquals("Impor ZIP", ZipImportPlanner.zipBaseName(null))
    }

    @Test
    fun isImage_dan_isBundle_caseInsensitive() {
        assertTrue(ZipImportPlanner.isImage("A.JPEG"))
        assertTrue(ZipImportPlanner.isImage("b.WebP"))
        assertFalse(ZipImportPlanner.isImage("c.txt"))
        assertTrue(ZipImportPlanner.isBundle("proyek.MTS"))
        assertFalse(ZipImportPlanner.isBundle("gambar.png"))
    }
}
