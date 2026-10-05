package com.mochits.app.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptParserTest {

    private fun m(vararg symbols: String): List<ScriptSymbolEntry> =
        symbols.map { ScriptSymbolEntry(it) }

    @Test
    fun `simbol jadi entri, tanpa simbol jadi sambungan`() {
        val doc = ScriptParser.parse(
            "- Halo\nlanjutan balon\n\n# Narasi kotak",
            m("-", "#"), "==="
        )
        assertEquals(1, doc.pages.size)
        val e = doc.pages[0].entries
        assertEquals(2, e.size)
        assertEquals("-", e[0].symbol)
        assertEquals("Halo\nlanjutan balon", e[0].text)
        assertEquals("#", e[1].symbol)
        assertEquals("Narasi kotak", e[1].text)
    }

    @Test
    fun `awalan terpanjang menang dan separator regex memotong halaman`() {
        val doc = ScriptParser.parse(
            "-- seru\n- biasa\nP2\n- halaman dua",
            m("-", "--"), "P\\d+"
        )
        assertEquals(2, doc.pages.size)
        assertEquals("--", doc.pages[0].entries[0].symbol)
        assertEquals("-", doc.pages[0].entries[1].symbol)
        assertEquals("P2", doc.pages[1].label)
        assertEquals(1, doc.pages[1].entries.size)
        assertEquals("halaman dua", doc.pages[1].entries[0].text)
    }

    @Test
    fun `simbol sambung menempel ke entri sebelumnya`() {
        val doc = ScriptParser.parse(
            "- Aku datang\n//- dan langsung pergi\n- Baris baru",
            listOf(
                ScriptSymbolEntry("-"),
                ScriptSymbolEntry("//-", append = true)
            ),
            "==="
        )
        val e = doc.pages[0].entries
        assertEquals(2, e.size)
        assertEquals("Aku datang\ndan langsung pergi", e[0].text)
        assertEquals("Baris baru", e[1].text)
    }

    @Test
    fun `kepala file dibuang bila ada pemisah halaman`() {
        val doc = ScriptParser.parse(
            "Judul Chapter\nNote tl:\n[]: Kotak dialognya\n-: Balon dialog\nP1\n- Halo",
            m("[]", "-"), "P\\d+"
        )
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.pages[0].entries.size)
        assertEquals("Halo", doc.pages[0].entries[0].text)
        assertEquals("P1", doc.pages[0].label)
    }

    @Test
    fun `tanpa separator dan teks nyasar aman`() {
        val empty = ScriptParser.parse("", m("-"), "===")
        assertEquals(1, empty.pages.size)
        assertTrue(empty.pages[0].entries.isEmpty())
        val stray = ScriptParser.parse("tanpa simbol sama sekali", m("-"), "===")
        assertTrue(stray.pages[0].entries.isEmpty())
    }
}
