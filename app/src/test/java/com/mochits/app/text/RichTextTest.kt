package com.mochits.app.text

import com.mochits.app.model.TextSpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {

    @Test
    fun splitToRuns_plainText_singleBaseRun() {
        val runs = splitToRuns("halo dunia", emptyList())
        assertEquals(1, runs.size)
        assertEquals("halo dunia", runs[0].text)
        assertEquals(0, runs[0].start)
        assertEquals(null, runs[0].textColor)
    }

    @Test
    fun splitToRuns_singleSpan_splitsThree() {
        val spans = listOf(TextSpanStyle(5, 10, textColor = 123))
        val runs = splitToRuns("halo dunia!", spans)
        assertEquals(3, runs.size)
        assertEquals("halo ", runs[0].text)
        assertEquals(null, runs[0].textColor)
        assertEquals("dunia", runs[1].text)
        assertEquals(123, runs[1].textColor)
        assertEquals("!", runs[2].text)
        assertEquals(null, runs[2].textColor)
    }

    @Test
    fun applySpanPatch_trimsOverlapping() {
        val old = listOf(TextSpanStyle(0, 10, textColor = 1))
        val out = applySpanPatch(old, 12, 4, 8, textColor = 2)
        assertEquals(3, out.size)
        assertEquals(0, out[0].start)
        assertEquals(4, out[0].end)
        assertEquals(1, out[0].textColor)
        assertEquals(4, out[1].start)
        assertEquals(8, out[1].end)
        assertEquals(2, out[1].textColor)
        assertEquals(8, out[2].start)
        assertEquals(10, out[2].end)
        assertEquals(1, out[2].textColor)
    }

    @Test
    fun clearSpanRange_restoresBase() {
        val old = listOf(TextSpanStyle(0, 10, textColor = 1))
        val out = clearSpanRange(old, 12, 3, 7)
        assertEquals(2, out.size)
        assertEquals(0, out[0].start)
        assertEquals(3, out[0].end)
        assertEquals(7, out[1].start)
        assertEquals(10, out[1].end)
    }

    @Test
    fun shiftSpansOnEdit_typingInsideSpan_splitsAroundTypedChar() {
        // "halo dunia" -> ketik "X" di offset 5: "halo Xdunia".
        // Kirikeep gaya, X polos, kanan keep gaya.
        val old = listOf(TextSpanStyle(0, 10, textColor = 1))
        val out = shiftSpansOnEdit("halo dunia", old, "halo Xdunia")
        assertEquals(2, out.size)
        assertEquals(0, out[0].start)
        assertEquals(5, out[0].end)
        assertEquals(1, out[0].textColor)
        assertEquals(6, out[1].start)
        assertEquals(11, out[1].end)
        assertEquals(1, out[1].textColor)
    }

    @Test
    fun shiftSpansOnEdit_append_keepsSpans() {
        val old = listOf(TextSpanStyle(0, 4, textColor = 1))
        val out = shiftSpansOnEdit("halo", old, "halo!")
        assertEquals(1, out.size)
        assertEquals(0, out[0].start)
        assertEquals(4, out[0].end)
    }

    @Test
    fun snapToWords_snapsToWordBounds() {
        val r = snapToWords("halo dunia", 6, 8)
        assertEquals(5, r?.first)
        assertEquals(10, r?.last?.plus(1))
    }

    @Test
    fun snapToWords_collapsed_null() {
        assertNull(snapToWords("halo dunia", 3, 3))
        assertNull(snapToWords("", 0, 0))
    }

    @Test
    fun normalizeSpans_clampsAndMergesNothing() {
        val out = normalizeSpans(
            listOf(TextSpanStyle(-5, 3), TextSpanStyle(4, 4), TextSpanStyle(5, 99)),
            10
        )
        assertEquals(2, out.size)
        assertEquals(0, out[0].start)
        assertEquals(3, out[0].end)
        assertEquals(5, out[1].start)
        assertEquals(10, out[1].end)
    }

    @Test
    fun splitToRuns_emptyText_empty() {
        assertTrue(splitToRuns("", listOf(TextSpanStyle(0, 5))).isEmpty())
    }
}
