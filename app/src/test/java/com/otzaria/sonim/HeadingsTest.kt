package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The chapter TOC, and the byte that used to hide a sefer's own name from it.
 *
 * Four of a 178-file sample of the real library start with a UTF-8 byte-order
 * mark, U+FEFF. Kotlin's `readLines` hands it back as the first character of line
 * 1, and every pattern here is anchored at `^`, so `<h1>אור הישר על חולין</h1>`
 * was not a heading and that book opened with its title missing from the TOC.
 * Extrapolated, on the order of 150 of 6,618 books (BUILDER.md S4).
 *
 * A 0.015% miss rate that lands *entirely* on line 1 of a file is not a rounding
 * error. It is a systematic bug wearing a small number.
 */
class HeadingsTest {

    private fun bookOf(vararg lines: String): String =
        File.createTempFile("sefer", ".txt").apply {
            deleteOnExit()
            writeText(lines.joinToString("\n"), Charsets.UTF_8)
        }.absolutePath

    private val BOM = "﻿"

    /** The failing case: a real file from the library, in miniature. */
    @Test
    fun aByteOrderMarkDoesNotHideTheTitle() {
        val p = bookOf("$BOM<h1>אור הישר על חולין</h1>", "<h2>פרק א</h2>", "טקסט")
        val hs = Otzaria.headings(p)
        assertEquals(2, hs.size)
        assertEquals(0, hs[0].pos)
        assertEquals(1, hs[0].level)
        assertEquals("אור הישר על חולין", hs[0].text)
    }

    /** …and the identical file without one must give the identical answer, so the
     *  fix is not sensitive to which books happen to carry a BOM. */
    @Test
    fun theSameBookWithoutOneReadsIdentically() {
        val withBom = Otzaria.headings(
            bookOf("$BOM<h1>אור הישר על חולין</h1>", "<h2>פרק א</h2>", "טקסט")
        )
        val without = Otzaria.headings(
            bookOf("<h1>אור הישר על חולין</h1>", "<h2>פרק א</h2>", "טקסט")
        )
        assertEquals(without, withBom)
    }

    /** The mark must come off the text too, not only off the heading match —
     *  otherwise the TOC entry reads right and the rendered first line does not. */
    @Test
    fun theMarkIsStrippedFromTheLineItself() {
        val p = bookOf("$BOM(א) בְּרֵאשִׁית", "(ב) וְהָאָרֶץ")
        assertEquals("(א) בְּרֵאשִׁית", Otzaria.readLines(p).first())
    }

    /** No book, no crash, no headings. */
    @Test
    fun anAbsentBookHasNoHeadings() {
        assertEquals(emptyList<Otzaria.Heading>(), Otzaria.headings("no-such-sefer.txt"))
        assertEquals(emptyList<String>(), Otzaria.readLines("no-such-sefer.txt"))
    }

    /** Nested markup inside a heading is stripped, and a heading that is not the
     *  whole line is not a heading — both were true before and must stay true. */
    @Test
    fun headingTextIsPlain() {
        val p = bookOf("<h2>פרק <b>א</b></h2>", "ראה <h3>כאן</h3> באמצע")
        val hs = Otzaria.headings(p)
        assertEquals(1, hs.size)
        assertEquals("פרק א", hs[0].text)
    }
}
