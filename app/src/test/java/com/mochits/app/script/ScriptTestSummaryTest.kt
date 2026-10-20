package com.mochits.app.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTestSummaryTest {

    private val mappings = listOf(
        ScriptSymbolEntry("[]"),
        ScriptSymbolEntry("-"),
        ScriptSymbolEntry("//-", linked = true),
        ScriptSymbolEntry("//[]", linked = true),
        ScriptSymbolEntry("#")
    )

    @Test
    fun testSummary_countsPagesEntriesAndLinked() {
        val text = """
            P1
            - Halo
            //- lanjutan
            # Nama: A
              Class: B
            P2
            [] Kotak
        """.trimIndent()
        val summary = ScriptTestSummary.fromText(text, "tes.txt", mappings, "P\\d+")
        assertEquals(2, summary.pages)
        assertEquals(4, summary.entries)
        assertEquals(1, summary.linkedEntries)
        assertEquals(1, summary.symbolCounts.toMap()["//-"])
        assertTrue(summary.warnings.isEmpty())
    }

    @Test
    fun testSummary_warnsWhenLinkedSymbolMissingFromMapping() {
        val text = "- Halo\n//- lanjutan"
        val withoutLinked = listOf(ScriptSymbolEntry("-"))
        val summary = ScriptTestSummary.fromText(text, "tes.txt", withoutLinked, "P\\d+")
        assertEquals(1, summary.entries)
        assertTrue(summary.warnings.any { it.contains("//-") })
    }

    @Test
    fun testSummary_warnsUnknownDoubleSlashLines() {
        val text = "- Halo\n//+ tidak dikenal"
        val summary = ScriptTestSummary.fromText(text, "tes.txt", mappings, "P\\d+")
        assertTrue(summary.warnings.any { it.contains("tidak cocok") })
    }
}
