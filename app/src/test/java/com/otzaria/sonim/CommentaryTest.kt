package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The meforshim panel: what it shows, and — the part that was an ANR — that it
 * resolves its links off the main thread.
 *
 * `Otzaria.meforshimFor` opens the sidecar and reads the target files: disk I/O,
 * and for a big commentary seconds of it. It used to run in `onCreate`, on the UI
 * thread, so the screen froze while it worked. The reader had already learned this
 * and loads its book in the background; the panel did not.
 *
 * The arithmetic is in [Commentary.model] and the threading in [Commentary.Loader],
 * so both are checked here without a device.
 */
class CommentaryTest {

    private fun mef(
        ref: String,
        kind: Int = Otzaria.KIND_MEFARESH,
        path: String = "אוצריא/פרש.json",
        line: Int = 1
    ) = Otzaria.Meforish(ref, "text of $ref", "מפרש", kind, path, line)

    // ------------------------------------------------------------- the arithmetic

    /** A meforshim is a row, and it is not under any heading. */
    @Test
    fun aMeforshimIsARowAndHasNoHeadingOfItsOwn() {
        val rows = Commentary.model(listOf(mef("רמז א")))

        assertEquals(1, rows.size)
        val item = rows[0] as Commentary.Row.Item
        assertEquals("רמז א", item.ref)
        assertEquals("text of רמז א", item.content)
    }

    /**
     * A heading goes in wherever the kind changes — without one, a reader of משנה
     * ברורה sees the Shulchan Arukh se'if under the word "מפרשים" and is being told
     * something untrue about it.
     */
    @Test
    fun aHeadingIsPlacedWhereTheKindChanges() {
        val rows = Commentary.model(
            listOf(
                mef("רמז א"),
                mef("רמז ב"),
                mef("סעיף ג", kind = Otzaria.KIND_BASE),
                mef("רמז ד")
            )
        )

        val titles = rows.filterIsInstance<Commentary.Row.Section>().map { it.title }
        assertEquals(listOf("מקור"), titles)
        assertTrue(
            "the heading must come before the base text",
            rows.indexOfFirst { it is Commentary.Row.Section } <
                rows.indexOfFirst { it is Commentary.Row.Item && it.ref == "סעיף ג" }
        )
    }

    /** Cross-references get their own heading. */
    @Test
    fun crossReferencesGetTheirOwnHeading() {
        val rows = Commentary.model(
            listOf(mef("רמז א"), mef("קישור", kind = Otzaria.KIND_RELATED))
        )

        val titles = rows.filterIsInstance<Commentary.Row.Section>().map { it.title }
        assertEquals(listOf("קישורים נוספים"), titles)
    }

    /** No meforshim: no rows, and no crash on an empty segment. */
    @Test
    fun anEmptySegmentHasNoRows() {
        assertTrue(Commentary.model(emptyList()).isEmpty())
    }

    /** The row keeps the pointer, so a row can be pushed onto הערות. */
    @Test
    fun theRowKeepsThePointerToTheSegment() {
        val m = mef("רמז א", path = "אוצריא/פרש.json", line = 42)
        val row = Commentary.model(listOf(m))[0] as Commentary.Row.Item

        assertEquals("אוצריא/פרש.json", row.meforish.path)
        assertEquals(42, row.meforish.line)
    }

    // ------------------------------------------------------------- the threading

    /**
     * The links are resolved off the calling thread. This is the ANR: the caller's
     * thread is the UI thread, and a UI thread that spends seconds reading a
     * commentary off the disk freezes the screen.
     */
    @Test
    fun theMeforshimIsResolvedOffTheCallingThread() {
        val caller = Thread.currentThread()
        val resolver = AtomicReference<Thread>()
        val delivered = AtomicReference<List<Commentary.Row>>()
        val done = CountDownLatch(1)

        Commentary.Loader(
            resolve = { book, line, _ ->
                resolver.set(Thread.currentThread())
                listOf(mef("רמז $book:$line"))
            },
            post = { action -> action() }
        ).load("ספר", 7, null) { rows ->
            delivered.set(rows)
            done.countDown()
        }

        assertTrue("the rows never arrived", done.await(10, TimeUnit.SECONDS))
        assertFalse(
            "the meforshim was resolved on the calling thread — that is the ANR",
            resolver.get() === caller
        )
        val item = delivered.get()!![0] as Commentary.Row.Item
        assertEquals("רמז ספר:7", item.ref)
    }

    /**
     * The rows are delivered through the poster, not straight from the worker: the
     * screen may only be touched from the UI thread, and the worker thread is not
     * it. The poster here stands in for `runOnUiThread`.
     */
    @Test
    fun theRowsAreDeliveredThroughThePoster() {
        val posted = AtomicReference<Boolean>()
        val done = CountDownLatch(1)

        Commentary.Loader(
            resolve = { _, _, _ -> listOf(mef("רמז")) },
            post = { action -> posted.set(true); action() }
        ).load("ספר", 1, null) { done.countDown() }

        assertTrue("the rows never arrived", done.await(10, TimeUnit.SECONDS))
        assertTrue("the rows must go through the poster", posted.get())
    }

    /** The chosen commentators are the ones the resolver is given. */
    @Test
    fun theChosenCommentatorsArePassedToTheResolver() {
        val seen = AtomicReference<Set<String>?>()
        val done = CountDownLatch(1)

        Commentary.Loader(
            resolve = { _, _, selected ->
                seen.set(selected)
                emptyList()
            },
            post = { action -> action() }
        ).load("ספר", 1, setOf("מפרש א")) { done.countDown() }

        assertTrue("the rows never arrived", done.await(10, TimeUnit.SECONDS))
        assertEquals(setOf("מפרש א"), seen.get())
    }
}
