package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MENU means one thing in this app: tap opens הערות, hold runs whatever that screen
 * used to do with it.
 *
 * That only holds if every screen actually inherits it. The tap is inherited by
 * being an [OtzActivity], so the guard is: a screen that is not one cannot be added,
 * and no screen may quietly claim MENU for itself — which is exactly how the tap got
 * lost the first time, one screen at a time.
 *
 * This is [AccessibleNamesTest]'s lesson applied to a key: naming something once does
 * not hold, making the wrong version impossible to write does.
 */
class MenuContractTest {

    private fun uiSources(): List<File> {
        var dir = File("").absoluteFile
        while (dir.parentFile != null &&
            !File(dir, "app/src/main/java/com/otzaria/sonim").isDirectory
        ) dir = dir.parentFile
        val src = File(dir, "app/src/main/java/com/otzaria/sonim")
        assertTrue("could not find the source tree from ${File("").absolutePath}", src.isDirectory)
        return src.listFiles { f -> f.name.endsWith(".kt") }!!.sorted()
    }

    /** A screen is a class whose name ends in Activity. */
    private fun screens() = uiSources().filter { f ->
        Regex("""^class \w+Activity\b""", RegexOption.MULTILINE).containsMatchIn(f.readText())
    }

    @Test
    fun everyScreenCanReachTheStack() {
        val all = screens()
        assertTrue("expected the whole app's screens to be found, got ${all.map { it.name }}",
            all.size >= 7)
        val notInheriting = all.filter {
            !Regex("""^class \w+Activity\s*:\s*OtzActivity\(\)""", RegexOption.MULTILINE)
                .containsMatchIn(it.readText())
        }.map { it.name }
        assertEquals(
            "these screens extend Activity directly, so a tap on MENU would not open הערות",
            emptyList<String>(), notInheriting
        )
    }

    /**
     * The tap is the base class's, so the key code itself is spelled once: in
     * [MenuKey], as the number 82, which is also why the splitter runs in a JVM test
     * with no Android framework behind it. Anything else matching `KeyEvent.KEYCODE_MENU`
     * is a screen intercepting the key again.
     */
    @Test
    fun nobodyHandlesMenuByKeyCode() {
        val offenders = uiSources()
            .filter { it.readText().contains("KeyEvent.KEYCODE_MENU") }
            .map { it.name }
        assertEquals(
            "MENU must be handled once, through MenuKey — these handle it themselves",
            emptyList<String>(), offenders
        )
    }

    /** The three screens that had a MENU action keep it, on the hold half of the key. */
    @Test
    fun theOldMenuActionsMovedToTheHold() {
        val want = mapOf(
            "ReaderActivity.kt" to "openCommentators()",
            "LibraryActivity.kt" to "promptForRoot()",
            "NotesActivity.kt" to "finish()"
        )
        for ((file, call) in want) {
            val text = uiSources().first { it.name == file }.readText()
            val hold = Regex("""override fun onMenuHold\(\)\s*\{[^}]*\}""").find(text)?.value
                ?: throw AssertionError("$file: no onMenuHold override")
            assertTrue("$file: the hold should call $call, it is: $hold", hold.contains(call))
        }
    }
}
