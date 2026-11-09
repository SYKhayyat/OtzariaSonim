package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * הערות has two keys that are easy to break quietly: `*` opens the sefer a reference
 * points into, and a tap on MENU opens the little menu.
 *
 * Neither can be seen in a JVM test — they are key handlers in an Activity — so this
 * is the same trade [MenuContractTest] makes: the source is the contract, and the
 * test fails the build when the contract is broken.
 *
 * The failure this guards against is a real one. `*` used to cycle the order, and the
 * order now comes from the menu, so a well-meaning "put the cycling back" would
 * silently take away the only way out of the stack and into a sefer.
 */
class NotesKeyMapTest {

    private fun uiSources(): List<File> {
        var dir = File("").absoluteFile
        while (dir.parentFile != null &&
            !File(dir, "app/src/main/java/com/otzaria/sonim").isDirectory
        ) dir = dir.parentFile
        val src = File(dir, "app/src/main/java/com/otzaria/sonim")
        assertTrue("could not find the source tree from ${File("").absolutePath}", src.isDirectory)
        return src.listFiles { f -> f.name.endsWith(".kt") }!!.sorted()
    }

    private fun source(name: String) = uiSources().first { it.name == name }.readText()

    /** `*` opens the sefer, at the line the reference names. */
    @Test
    fun starOpensTheSferAtTheLineTheReferenceNames() {
        val text = source("NotesActivity.kt")
        val star = Regex("""KeyEvent\.KEYCODE_STAR\s*->\s*\{([^}]*)\}""").find(text)?.groupValues?.get(1)
            ?: throw AssertionError("NotesActivity.kt: no KEYCODE_STAR handler")
        assertTrue("`*` must open the sefer, it is: $star", star.contains("openSefar()"))
        assertTrue(
            "the reader must be told which line to land on",
            source("NotesActivity.kt").contains("""putExtra("atLine"""")
        )
    }

    /** The order is chosen from the menu, not cycled with a key. */
    @Test
    fun theOrderIsNotCycledWithAKeyAnyMore() {
        val offenders = uiSources()
            .filter { it.readText().contains("order.next()") }
            .map { it.name }
        assertEquals(
            "the order comes from the menu now — `*` opens the sefer",
            emptyList<String>(), offenders
        )
    }

    /** A tap on MENU opens the menu; it does not toast and it does not close. */
    @Test
    fun tappingMenuInsideTheNotesOpensTheMenu() {
        val text = source("NotesActivity.kt")
        val tap = Regex("""override fun onMenuTap\(\)\s*\{([^}]*)\}""").find(text)?.groupValues?.get(1)
            ?: throw AssertionError("NotesActivity.kt: no onMenuTap override")
        assertTrue("a tap on MENU must open the menu, it is: $tap", tap.contains("openMenu()"))
        assertTrue(
            "the menu must be able to close itself again",
            tap.contains("closeMenu()")
        )
    }

    /**
     * The reader that הערות opens must not move the place that book was left at —
     * unless the reader read on from the line, which is a different thing.
     *
     * The rule lives in [Settings.maySavePlace] (where it can be tested properly);
     * this test only guards that the reader actually asks it, so that a rewrite of
     * `onPause` cannot quietly drop the rule.
     */
    @Test
    fun aVisitSentToALineDoesNotMoveTheRememberedPlace() {
        val text = source("ReaderActivity.kt")
        val pause = Regex("""override fun onPause\(\)\s*\{([^}]*)\}""").find(text)?.groupValues?.get(1)
            ?: throw AssertionError("ReaderActivity.kt: no onPause override")
        assertTrue(
            "a landed visit must ask before it saves a place, it is: $pause",
            pause.contains("maySavePlace")
        )
        assertTrue(
            "the rule must be the one that knows about the landing line, it is: $pause",
            pause.contains("landedAt")
        )
    }
}
