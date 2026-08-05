package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every control in this app has a name, and the two glyphs it leans on say what
 * they mean out loud.
 *
 * BUILDER.md S3 filed this as "contentDescription appears zero times across all
 * nine UI files", with tests for `setOnClickListener` call sites and for
 * `ImageView`s in layout XML. Checked, and the premise does not hold here: this
 * app has no `setOnClickListener` calls, no `ImageView`s, and no layout XML at
 * all. Every screen is one ListView of TextViews, and a TextView is announced by
 * its own text — so the rows were named all along.
 *
 * What genuinely was not named:
 *
 *   - the four ListViews, announced as "list" and nothing else;
 *   - `◆`, which is the entire point of the reader screen, announced as a lozenge
 *     or skipped;
 *   - `☑` / `☐`, which are the entire point of the picker screen, announced as
 *     ballot boxes;
 *   - `📁`, announced as "file folder" in English, mid-Hebrew.
 *
 * S3's real lesson is the one worth keeping: naming the controls once does not
 * hold, because the next screen is written without them. What holds is making an
 * unnamed control impossible to write — hence [Ui.list], which takes the name as a
 * required argument, and [aNamelessListCannotBeWritten], which is what stops the
 * next screen going around it.
 */
class AccessibleNamesTest {

    private fun uiSources(): List<File> {
        // Walk up from the module dir the JVM test runner starts in.
        var dir = File("").absoluteFile
        while (dir.parentFile != null &&
            !File(dir, "app/src/main/java/com/otzaria/sonim").isDirectory
        ) dir = dir.parentFile
        val src = File(dir, "app/src/main/java/com/otzaria/sonim")
        assertTrue("could not find the source tree from ${File("").absolutePath}", src.isDirectory)
        return src.listFiles { f -> f.name.endsWith(".kt") }!!.sorted()
    }

    /**
     * The guard. `ListView(` may appear only in Ui.kt, so every list on every
     * screen goes through [Ui.list] and carries a name. This is the assertion that
     * survives the next screen being added.
     */
    @Test
    fun aNamelessListCannotBeWritten() {
        val offenders = uiSources()
            .filter { it.name != "Ui.kt" }
            .filter { f ->
                f.readLines().any { line ->
                    // the import is not a construction
                    Regex("""(^|[^.\w])ListView\(""").containsMatchIn(line)
                }
            }
            .map { it.name }
        assertEquals(
            "these construct a ListView directly instead of using Ui.list(ctx, name)",
            emptyList<String>(), offenders
        )
    }

    /** Every screen that shows a list must be able to say what the list holds. */
    @Test
    fun everyScreenNamesItsList() {
        val screens = uiSources().filter { it.readText().contains("Ui.list(") }
        assertTrue("expected the screens to use Ui.list", screens.size >= 5)
        for (f in screens) {
            val names = Regex("""Ui\.list\([^,]+,\s*"([^"]*)"""").findAll(f.readText())
                .map { it.groupValues[1] }.toList()
            assertTrue("${f.name}: Ui.list called with no name", names.isNotEmpty())
            for (n in names) {
                assertTrue("${f.name}: empty accessible name", n.isNotBlank())
            }
        }
    }

    /** The ◆ means "there are meforshim here". Said, not drawn. */
    @Test
    fun theDiamondIsSpokenAsWords() {
        val spoken = Ui.describeRow("מאימתי קורין את שמע", hasMeforshim = true)
        assertTrue("the glyph must not be what gets read out", !spoken.contains(Ui.HAS_MEFORSHIM))
        assertTrue(spoken.contains("מפרשים"))
        assertTrue(spoken.contains("מאימתי קורין את שמע"))
        // and a plain segment is just itself — no ceremony where there is no mark
        assertEquals("מאימתי קורין את שמע", Ui.describeRow("מאימתי קורין את שמע", false))
    }

    /** ☑/☐ are state, and state has to be announced as state. */
    @Test
    fun theCheckboxStateIsSpokenAsWords() {
        val on = Ui.describeChoice("משנה ברורה", checked = true)
        val off = Ui.describeChoice("משנה ברורה", checked = false)
        assertTrue(on.contains("משנה ברורה") && off.contains("משנה ברורה"))
        assertTrue("checked and unchecked must not sound the same", on != off)
        for (glyph in listOf("☑", "☐")) {
            assertTrue(!on.contains(glyph) && !off.contains(glyph))
        }
    }
}
