package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saved reading position is a raw list index into a library the reader
 * re-downloads. When a sefer gains or loses a line upstream, every bookmark in it
 * points somewhere else and the reader reopens hundreds of lines from where they
 * stopped, with nothing said (BUILDER.md S2).
 *
 * This app edits nothing, so it needs none of the permanent-segment-id machinery
 * Girsa needs. It needs one thing: to notice.
 */
class ResumeTest {

    private fun sefer(n: Int, from: Int = 0) =
        (from until from + n).map { "(${it}) שורה מספר $it בספר הזה" }

    /** Nothing changed: same line, and no crying wolf about it. */
    @Test
    fun anUnchangedSeferReopensExactlyWhereItWas() {
        val lines = sefer(1000)
        val r = Settings.resolveResume(500, "(500) שורה מספר 500 בספר הזה", lines)!!
        assertEquals(500, r.pos)
        assertFalse(r.lost)
    }

    /** Three lines inserted above: the bookmark follows the text, not the number. */
    @Test
    fun aShiftedSeferLandsOnTheSameTextNotTheSameIndex() {
        val lines = ArrayList(sefer(1000))
        lines.addAll(0, listOf("<h2>הוספה</h2>", "עוד שורה", "ועוד"))
        val r = Settings.resolveResume(500, "(500) שורה מספר 500 בספר הזה", lines)!!
        assertEquals(503, r.pos)
        assertFalse("a recoverable shift is not worth a message", r.lost)
        assertTrue(lines[r.pos].startsWith("(500)"))
    }

    /** …and downwards too, so the scan is not accidentally one-sided. */
    @Test
    fun aSeferThatLostLinesAboveAlsoResolves() {
        val lines = sefer(1000).drop(7)
        val r = Settings.resolveResume(500, "(500) שורה מספר 500 בספר הזה", lines)!!
        assertEquals(493, r.pos)
        assertFalse(r.lost)
    }

    /** A different sefer entirely: back to the top, and say so. */
    @Test
    fun anUnrecognisableSeferGoesToTheTopAndReportsIt() {
        val r = Settings.resolveResume(500, "(500) שורה מספר 500 בספר הזה",
            List(1000) { "טקסט אחר לגמרי מספר $it" })!!
        assertEquals(0, r.pos)
        assertTrue("the reader must be told, not silently moved", r.lost)
    }

    /** Beyond the scan window is "lost", not a whole-book scan on every open —
     *  ערוך השולחן is 26,776 lines and this runs before the first row is drawn. */
    @Test
    fun aShiftBeyondTheWindowIsReportedRatherThanHunted() {
        val lines = ArrayList(sefer(3000))
        lines.addAll(0, List(Settings.RESUME_SCAN + 50) { "מילוי $it" })
        val r = Settings.resolveResume(500, "(500) שורה מספר 500 בספר הזה", lines)!!
        assertTrue(r.lost)
    }

    /** Never opened. Not the same thing as "lost", and must not say anything. */
    @Test
    fun aBookNeverOpenedHasNoSavedPlace() {
        assertNull(Settings.resolveResume(-1, null, sefer(10)))
        assertNull(Settings.resolveResume(5, "x", emptyList()))
    }

    /** A position saved by a build that stored no text is still honoured, so
     *  upgrading the APK does not announce a problem on every sefer. */
    @Test
    fun aPositionFromAnOlderBuildIsTrusted() {
        val r = Settings.resolveResume(42, null, sefer(100))!!
        assertEquals(42, r.pos)
        assertFalse(r.lost)
        // …but one that is now out of range is simply gone, not clamped silently.
        assertNull(Settings.resolveResume(4200, null, sefer(100)))
    }

    /** The position saved past the end of a shrunken sefer still resolves by text. */
    @Test
    fun aSavedIndexPastTheEndStillFindsItsLine() {
        val lines = sefer(600)
        val r = Settings.resolveResume(700, "(599) שורה מספר 599 בספר הזה", lines)!!
        assertEquals(599, r.pos)
        assertFalse(r.lost)
    }

    // ------------------------------------------- the per-book commentator choice

    private val yorehDeah = listOf(
        Otzaria.Commentator("שפתי כהן על שולחן ערוך יורה דעה", Otzaria.KIND_MEFARESH),
        Otzaria.Commentator("טורי זהב על שולחן ערוך יורה דעה", Otzaria.KIND_MEFARESH),
        Otzaria.Commentator("ויקרא", Otzaria.KIND_RELATED),
        Otzaria.Commentator("טור", Otzaria.KIND_RELATED)
    )

    /**
     * The upgrade path, and the one that would have left the reported bug in place
     * for anybody who had ever opened the picker: the old build checked everything
     * by default, so ויקרא being in a saved set is not evidence anyone chose it.
     */
    @Test
    fun aChoiceSavedBeforeLinkKindsDropsWhatIsNotACommentary() {
        val saved = yorehDeah.map { it.name }.toSet()   // the old "all checked"
        val got = Settings.reconcile(saved, seen = null, available = yorehDeah)
        assertEquals(
            setOf("שפתי כהן על שולחן ערוך יורה דעה", "טורי זהב על שולחן ערוך יורה דעה"),
            got
        )
    }

    /** A deliberate choice made since is respected exactly, cross-references included. */
    @Test
    fun adeliberateChoiceIsKeptAsMade() {
        val roster = yorehDeah.map { it.name }.toSet()
        val got = Settings.reconcile(
            saved = setOf("שפתי כהן על שולחן ערוך יורה דעה", "ויקרא"),
            seen = roster, available = yorehDeah
        )
        assertEquals(setOf("שפתי כהן על שולחן ערוך יורה דעה", "ויקרא"), got)
    }

    /** A commentator the library gained since arrives checked, not silently off. */
    @Test
    fun aCommentatorAddedByARepackArrivesOn() {
        val got = Settings.reconcile(
            saved = setOf("שפתי כהן על שולחן ערוך יורה דעה"),
            seen = setOf("שפתי כהן על שולחן ערוך יורה דעה", "טורי זהב על שולחן ערוך יורה דעה"),
            available = yorehDeah + Otzaria.Commentator("פתחי תשובה", Otzaria.KIND_MEFARESH)
        )
        assertTrue("a new מפרש must not be silently hidden", got.contains("פתחי תשובה"))
        assertFalse("but an unchecked one stays unchecked",
            got.contains("טורי זהב על שולחן ערוך יורה דעה"))
        assertFalse("and a new cross-reference stays off", got.contains("ויקרא"))
    }

    /**
     * A commentator the library lost simply goes; it must not linger in the set.
     * Note the roster has to be the *whole* list the reader was shown — an
     * incomplete one makes every name missing from it look newly added.
     */
    @Test
    fun aCommentatorRemovedByARepackDisappears() {
        val got = Settings.reconcile(
            saved = setOf("שפתי כהן על שולחן ערוך יורה דעה", "מפרש שכבר לא קיים"),
            seen = yorehDeah.map { it.name }.toSet() + "מפרש שכבר לא קיים",
            available = yorehDeah
        )
        assertEquals(setOf("שפתי כהן על שולחן ערוך יורה דעה"), got)
    }
}
