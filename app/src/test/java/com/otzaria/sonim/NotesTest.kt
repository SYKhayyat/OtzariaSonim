package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of הערות is a POINTER store: the only things in it are a path, a line
 * number, the phrase saying where it came from, and a counter. These tests pin the
 * three properties that make that safe — nothing is lost in the round trip, one copy
 * can be dropped without touching its twins, and the orders the `*` key cycles
 * through are the orders shown.
 */
class NotesTest {

    private fun e(path: String, line: Int, where: String = "ספר") =
        Notes.Entry(path, line, where, 0)

    private fun seq(entries: List<Notes.Entry>) = entries.map { it.seq }

    // ------------------------------------------------------------ push and drop

    @Test
    fun pushingTheSameReferenceTwiceKeepsBothCopies() {
        val a = Notes.add(emptyList(), "אוצריא/בראשית/בראשית.txt", 5, "בראשית · בראשית א")
        val b = Notes.add(a, "אוצריא/בראשית/בראשית.txt", 5, "בראשית · בראשית א")

        assertEquals(2, b.size)
        assertEquals(2, Notes.countOf(b, "אוצריא/בראשית/בראשית.txt", 5))
        assertTrue("each copy gets its own identity", b[0].seq != b[1].seq)
    }

    @Test
    fun removeOneDropsOnlyTheCopyItNames() {
        val entries = Notes.add(
            Notes.add(emptyList(), "x.txt", 3, "א"),
            "x.txt", 3, "א"
        )
        val left = Notes.removeOne(entries, entries[0].seq)

        assertEquals(1, left.size)
        assertEquals(entries[1].seq, left[0].seq)
    }

    @Test
    fun removeAllSweepsEveryCopy() {
        val entries = Notes.add(
            Notes.add(
                Notes.add(emptyList(), "x.txt", 3, "א"),
                "x.txt", 4, "א"
            ),
            "x.txt", 3, "א"
        )
        val left = Notes.removeAll(entries, "x.txt", 3)

        assertEquals(1, left.size)
        assertEquals(4, left[0].line)
    }

    @Test
    fun sameLineInAnotherSeferIsAnotherReference() {
        val entries = Notes.add(
            Notes.add(emptyList(), "a/x.txt", 3, "x"),
            "b/x.txt", 3, "x"
        )
        assertEquals(1, Notes.removeAll(entries, "a/x.txt", 3).size)
        assertEquals(1, Notes.countOf(entries, "a/x.txt", 3))
    }

    // ------------------------------------------------------------------ orders

    @Test
    fun starCyclesThroughEveryOrderAndComesBack() {
        var o = Notes.Order.NEWEST
        val seen = mutableListOf(o)
        repeat(3) { o = o.next(); seen.add(o) }

        assertEquals(
            listOf(Notes.Order.NEWEST, Notes.Order.OLDEST, Notes.Order.BY_SEFER, Notes.Order.NEWEST),
            seen
        )
    }

    @Test
    fun newestAndOldestAreExactReverses() {
        var entries = emptyList<Notes.Entry>()
        for (i in 1..3) entries = Notes.add(entries, "x.txt", i, "x")

        val newest = Notes.layout(entries, Notes.Order.NEWEST).map { it as Notes.Row.Item }
        val oldest = Notes.layout(entries, Notes.Order.OLDEST).map { it as Notes.Row.Item }

        assertEquals(listOf(3, 2, 1), newest.map { it.entry.line })
        assertEquals(listOf(1, 2, 3), oldest.map { it.entry.line })
    }

    @Test
    fun groupingPutsEachSeferTogetherAndLiftsTheOneJustTouched() {
        val entries = Notes.add(
            Notes.add(
                Notes.add(
                    Notes.add(emptyList(), "a/בראשית.txt", 1, "בראשית"),
                    "b/שמות.txt", 2, "שמות"
                ),
                "a/בראשית.txt", 3, "בראשית"
            ),
            "b/שמות.txt", 4, "שמות"
        )

        val rows = Notes.layout(entries, Notes.Order.BY_SEFER)

        // שמות was touched last, so its group comes first; within it the newest is on top.
        assertEquals(Notes.Row.Group("שמות", 2), rows[0])
        assertEquals(listOf(4, 2), rows.subList(1, 3).map { (it as Notes.Row.Item).entry.line })
        assertEquals(Notes.Row.Group("בראשית", 2), rows[3])
        assertEquals(listOf(3, 1), rows.subList(4, 6).map { (it as Notes.Row.Item).entry.line })
    }

    @Test
    fun indexOfFindsTheRowOfACopyAndIgnoresGroupRows() {
        var entries = emptyList<Notes.Entry>()
        entries = Notes.add(entries, "a/x.txt", 1, "x")
        entries = Notes.add(entries, "b/y.txt", 2, "y")

        val rows = Notes.layout(entries, Notes.Order.BY_SEFER)

        // b/y was touched last, so its group is first: group row, its item, then a/x.
        assertEquals(3, Notes.indexOf(rows, entries[0].seq))
        assertEquals(-1, Notes.indexOf(rows, 9999))
        assertTrue(rows.any { it is Notes.Row.Group })
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun roundTripKeepsEveryField() {
        val where = "שמות · שמות · \"פרק\tעם\\טאב ושבירת\nשורה\""
        val entries = listOf(
            Notes.Entry("אוצריא/תנך/שמות.txt", 1234, where, 7),
            Notes.Entry("אוצריא/בראשית/בראשית.txt", 1, "בראשית", 8)
        )

        assertEquals(entries, Notes.decode(Notes.encode(entries)))
    }

    @Test
    fun nothingSavedMeansNothingListed() {
        assertEquals(emptyList<Notes.Entry>(), Notes.decode(null))
        assertEquals(emptyList<Notes.Entry>(), Notes.decode(""))
    }

    @Test
    fun aRecordThatDoesNotParseIsSkippedNotFatal() {
        val good = Notes.encode(listOf(Notes.Entry("x.txt", 2, "x", 5)))
        val blob = "garbage\n" + good

        val out = Notes.decode(blob)

        assertEquals(1, out.size)
        assertEquals(2, out[0].line)
        assertEquals("x.txt", out[0].path)
    }

    @Test
    fun bookNameIsDerivedFromThePath() {
        assertEquals("בראשית", e("אוצריא/תנך/בראשית.txt", 1).book)
    }

    // ---------------------------------------------------------------- preview

    @Test
    fun previewIsRenderingNotACopy() {
        // The list shows the opening of the line; it must come out as plain text.
        assertEquals("ויאמר ה׳ אל אברם", Notes.preview("<b>ויאמר</b>&nbsp;ה׳   אל <i>אברם</i>"))
    }

    @Test
    fun anInlineTagDoesNotSplitAWord() {
        // The corpus wraps part of a vocalised word in <b>/<i>; a space here would be
        // visible in every preview of a pasuk that has a commentator anchor.
        assertEquals("בְּרֵאשִׁ֖ית", Notes.preview("בְּ<b>רֵאשִׁ֖ית</b>"))
        // A block boundary is a real break, so it must stay a space.
        assertEquals("פרק א (א) בראשית", Notes.preview("<h2>פרק א</h2><b>(א)</b> בראשית"))
    }

    @Test
    fun previewIsBoundedAndHandlesEmptyLines() {
        assertEquals("", Notes.preview(""))
        assertEquals("", Notes.preview("<h2></h2>"))
        assertEquals(60, Notes.preview("א".repeat(500)).length)
    }
}
