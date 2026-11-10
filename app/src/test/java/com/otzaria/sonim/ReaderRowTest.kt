package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A reader row is parsed once, not twice.
 *
 * The reader draws a segment the way the sefer writes it, so the markup has to be
 * parsed for every row it shows. It used to be parsed twice: once for the text on
 * the screen, and again in `getView` for the content description — the text a
 * screen reader says. `Html.fromHtml` is a real parser, and a reader scrolls
 * through thousands of rows, so every row paid for two parses to make a
 * description out of the parse that had already been done.
 *
 * The row is modelled in [ReaderRow], which is pure: the parse is passed in, so
 * this test can count the parses without a device.
 */
class ReaderRowTest {

    /** A parse that counts how many times it was called. */
    private class CountingParse {
        val calls = AtomicInteger()
        fun parse(line: String): String = "<b>$line</b>".also { calls.incrementAndGet() }
    }

    /** A row without meforshim: the text is the text, spoken and drawn. */
    @Test
    fun aPlainRowIsParsedOnceAndSaysWhatItDraws() {
        val p = CountingParse()

        val row = ReaderRow.of(
            line = "פסוק",
            hasMeforshim = false,
            parse = { p.parse(it) },
            join = { body, mark -> "$mark$body" }
        )

        assertEquals("the markup must be parsed exactly once", 1, p.calls.get())
        assertEquals("<b>פסוק</b>", row.drawn)
        assertEquals("<b>פסוק</b>", row.spoken)
    }

    /** A row with meforshim: the mark is in front of the text, and only on screen. */
    @Test
    fun theMeforshimMarkIsDrawnButNotSpoken() {
        val p = CountingParse()

        val row = ReaderRow.of(
            line = "פסוק",
            hasMeforshim = true,
            parse = { p.parse(it) },
            join = { body, mark -> "$mark$body" }
        )

        assertEquals("the markup must be parsed exactly once", 1, p.calls.get())
        assertTrue("the mark must be drawn", row.drawn.startsWith(ReaderRow.MARK))
        assertTrue(
            "the mark must not be spoken — a screen reader reads a lozenge as nothing",
            !row.spoken.contains(ReaderRow.MARK.trim())
        )
        assertEquals("<b>פסוק</b>", row.spoken)
    }

    /** The mark is the same glyph the rest of the app uses. */
    @Test
    fun theMarkIsTheGlyphTheAppUses() {
        assertTrue(
            "the mark must be the ◆ the reader uses",
            ReaderRow.MARK.startsWith(Ui.HAS_MEFORSHIM)
        )
    }

    /**
     * The reader's adapter must not parse the line a second time.
     *
     * This is the source-level guard, the same trade [NotesKeyMapTest] makes: the
     * double parse was in a `getView`, and a JVM test cannot run one. So the
     * source is the contract — the adapter's row method parses, and the method
     * that draws it does not.
     */
    @Test
    fun theReaderAdapterParsesEachRowOnlyOnce() {
        val src = File("").absoluteFile
        var dir: File? = src
        while (dir != null && dir.parentFile != null &&
            !File(dir, "app/src/main/java/com/otzaria/sonim").isDirectory
        ) dir = dir.parentFile
        val main = File(dir!!, "app/src/main/java/com/otzaria/sonim")
        assertTrue("could not find the source tree from ${src.path}", main.isDirectory)
        val text = File(main, "Ui.kt").readText()

        // The reader's adapter is the LAST one in the file; the notes list has a
        // getView of its own with the same signature.
        val getViews = Regex(
            """override fun getView\(position: Int, convertView: View\?, parent: ViewGroup\): View \{"""
        ).findAll(text).toList()
        val getView = getViews.lastOrNull()?.range?.first
            ?: throw AssertionError("Ui.kt: no ReaderAdapter.getView")
        val body = text.substring(getView).substringBefore("\n    }")
        assertTrue(
            "getView must not parse the markup again — the row it draws is already parsed",
            !body.contains("Ui.html(")
        )
        assertTrue(
            "getView must draw the row it parsed",
            body.contains("row(position)")
        )
        assertTrue(
            "getView must speak the row it parsed",
            body.contains("row.spoken")
        )
    }
}
