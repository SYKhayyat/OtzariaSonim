package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The library's folder tree: what it lists, and — the part that was jank — that it
 * lists it off the main thread.
 *
 * The אוצריא folder holds every book on the phone: 6,618 of them on an SD card.
 * `File.listFiles()` on it reads every name off the disk, and `LibraryActivity`
 * used to do that on the UI thread. Worse, "is the library still there?" was
 * answered by listing it — so every Back press listed 6,618 books again.
 *
 * The listing is in [Library.listing] and the threading in [Library.Loader], so
 * both are checked here without a device.
 */
class LibraryTest {

    private fun tempDir(): File = Files.createTempDirectory("otz-library").toFile()

    // ------------------------------------------------------------- the listing

    /** Folders come first, then the books, each by name. */
    @Test
    fun foldersComeFirstAndThenTheBooksByName() {
        val dir = tempDir()
        File(dir, "ביאור.txt").writeText("x")
        File(dir, "אבג.txt").writeText("x")
        File(dir, "תיקייה").mkdirs()

        val listing = Library.listing(dir)

        assertEquals(Library.State.READY, listing.state)
        assertEquals(
            listOf("תיקייה", "אבג.txt", "ביאור.txt"),
            listing.entries.map { it.file.name }
        )
    }

    /** A file that is not a `.txt` is not a book, and is not shown. */
    @Test
    fun aFileThatIsNotABookIsNotShown() {
        val dir = tempDir()
        File(dir, "ספר.txt").writeText("x")
        File(dir, "notes.md").writeText("x")
        File(dir, "index.json").writeText("x")

        val listing = Library.listing(dir)

        assertEquals(listOf("ספר.txt"), listing.entries.map { it.file.name })
    }

    /** The row shows the book's name, without the `.txt` the library stores it under. */
    @Test
    fun theRowShowsTheBookWithoutItsExtension() {
        val dir = tempDir()
        File(dir, "בראשית.txt").writeText("x")
        File(dir, "תיקייה").mkdirs()

        val listing = Library.listing(dir)

        assertEquals("בראשית", listing.entries.first { !it.isDirectory }.label)
        assertTrue(
            "a folder row is marked with the folder glyph",
            listing.entries.first { it.isDirectory }.label.startsWith("📁")
        )
    }

    /** The spoken row says what kind of thing it is, in words. */
    @Test
    fun theSpokenRowSaysWhatKindOfThingItIs() {
        val dir = tempDir()
        File(dir, "בראשית.txt").writeText("x")
        File(dir, "תיקייה").mkdirs()

        val listing = Library.listing(dir)

        val book = listing.entries.first { !it.isDirectory }
        val folder = listing.entries.first { it.isDirectory }
        assertTrue("a book says it is a book: ${book.spoken}", book.spoken.startsWith("ספר,"))
        assertTrue(
            "a folder says it is a folder: ${folder.spoken}",
            folder.spoken.startsWith("תיקייה,")
        )
        assertFalse(
            "the folder emoji must not be what gets read out",
            folder.spoken.contains("📁")
        )
    }

    /** A folder that is not there is not there — not an empty library. */
    @Test
    fun aFolderThatIsNotThereIsMissing() {
        val listing = Library.listing(File(tempDir(), "אין כזה"))

        assertEquals(Library.State.MISSING, listing.state)
        assertTrue(listing.entries.isEmpty())
    }

    /** An empty folder is a library with nothing in it, not a missing one. */
    @Test
    fun anEmptyFolderIsReadyAndEmpty() {
        val listing = Library.listing(tempDir())

        assertEquals(Library.State.READY, listing.state)
        assertTrue(listing.entries.isEmpty())
    }

    /**
     * A folder that exists but cannot be read is a *denied permission*, and the app
     * has to say so — the reader sees an empty screen with no explanation otherwise.
     *
     * `isDirectory` alone cannot tell this from a missing folder: with
     * READ_EXTERNAL_STORAGE denied the directory still exists.
     */
    @Test
    fun aFolderThatCannotBeReadIsUnreadableAndNotMissing() {
        val dir = tempDir()
        File(dir, "ספר.txt").writeText("x")
        dir.setReadable(false, false)
        dir.setExecutable(false, false)
        try {
            if (dir.canRead()) return          // running as root: nothing to prove
            val listing = Library.listing(dir)
            assertEquals(Library.State.UNREADABLE, listing.state)
        } finally {
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }
    }

    // ------------------------------------------------------------- the threading

    /**
     * The folder is listed off the calling thread. This is the jank: the caller's
     * thread is the UI thread, and a UI thread that spends seconds reading 6,618
     * names off an SD card freezes the screen.
     */
    @Test
    fun theFolderIsListedOffTheCallingThread() {
        val caller = Thread.currentThread()
        val lister = AtomicReference<Thread>()
        val delivered = AtomicReference<Library.Listing>()
        val done = CountDownLatch(1)

        Library.Loader(
            list = { dir ->
                lister.set(Thread.currentThread())
                Library.listing(dir)
            },
            post = { action -> action() }
        ).load(tempDir()) { listing ->
            delivered.set(listing)
            done.countDown()
        }

        assertTrue("the listing never arrived", done.await(10, TimeUnit.SECONDS))
        assertFalse(
            "the folder was listed on the calling thread — that is the jank",
            lister.get() === caller
        )
        assertEquals(Library.State.READY, delivered.get()!!.state)
    }

    /**
     * The listing is delivered through the poster, not straight from the worker:
     * the screen may only be touched from the UI thread, and the worker thread is
     * not it.
     */
    @Test
    fun theListingIsDeliveredThroughThePoster() {
        val posted = AtomicReference<Boolean>()
        val done = CountDownLatch(1)

        Library.Loader(
            list = { Library.listing(it) },
            post = { action -> posted.set(true); action() }
        ).load(tempDir()) { done.countDown() }

        assertTrue("the listing never arrived", done.await(10, TimeUnit.SECONDS))
        assertTrue("the listing must go through the poster", posted.get())
    }

    /**
     * The library screen must not list a folder on the UI thread.
     *
     * The listing is the disk I/O, so a call to it in the Activity's own code is a
     * call on the UI thread. This is the source-level guard, the same trade
     * [NotesKeyMapTest] makes.
     */
    @Test
    fun theLibraryScreenDoesNotListAFolderOnTheUiThread() {
        var dir: File? = File("").absoluteFile
        while (dir != null && dir.parentFile != null &&
            !File(dir, "app/src/main/java/com/otzaria/sonim").isDirectory
        ) dir = dir.parentFile
        val main = File(dir!!, "app/src/main/java/com/otzaria/sonim")
        assertTrue("could not find the source tree from ${File("").absolutePath}", main.isDirectory)

        val text = File(main, "LibraryActivity.kt").readText()
        // Only code counts — a comment may name the call to say why it is gone.
        val code = text.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "the screen must not list a folder itself — that is the disk I/O",
            !code.contains("listFiles(")
        )
        assertTrue(
            "the screen must list through the Library loader",
            code.contains("Library.Loader(")
        )
        assertTrue(
            "the screen must not ask whether the library is ready by listing it",
            !code.contains("Otzaria.isReady(")
        )
    }
}
