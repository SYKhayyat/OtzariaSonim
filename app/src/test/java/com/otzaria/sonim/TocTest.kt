package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The chapter TOC: what it shows, and — the part that was an ANR — that it works
 * out off the main thread.
 *
 * `Otzaria.headings(path)` parses every line of the book to find its `<h1..6>`
 * headings. ערוך השולחן is 31 MB and 26,776 lines; reading and regex-matching that
 * on the main thread is seconds of frozen screen, and seconds of the system saying
 * "the app is not responding". The reader had already learned this and loads its
 * book in the background; the TOC did not, and it showed.
 *
 * The arithmetic is in [Toc.model] and the threading is in [Toc.Loader], so both
 * are checked here without a device: the model in the usual way, and the loader by
 * asking which thread the book was read on.
 */
class TocTest {

    private fun h(level: Int, text: String, pos: Int) = Otzaria.Heading(level, text, pos)

    // ------------------------------------------------------------- the arithmetic

    /**
     * The rows are the headings, indented by their depth. A book whose shallowest
     * heading is `<h2>` has no `<h1>`, and its rows must still start at the left.
     */
    @Test
    fun rowsAreIndentedByHeadingDepth() {
        val model = Toc.model(listOf(h(2, "פרק א", 0), h(3, "סימן ב", 1), h(2, "פרק ב", 2)))

        assertEquals("פרק א", model.rows[0])
        assertEquals(" סימן ב", model.rows[1])
        assertEquals("פרק ב", model.rows[2])
        assertEquals(2, model.minLevel)
    }

    /**
     * The chapter number counts in the book's primary level: the most common one —
     * a perek in Mishnah, a siman in Er.HaShulchan. Here that is level 2, so the
     * number picks the two level-2 headings and skips the one in between.
     */
    @Test
    fun theChapterNumberCountsInThePrimaryLevel() {
        val model = Toc.model(listOf(h(2, "פרק א", 0), h(3, "סימן", 1), h(2, "פרק ב", 2)))

        assertEquals(2, model.primaryLevel)
        assertEquals(listOf(0, 2), model.primaryRowByOrdinal)
    }

    /** A book whose headings are all at one level: that level is the primary one. */
    @Test
    fun aFlatBookStillHasAPrimaryLevel() {
        val model = Toc.model(listOf(h(1, "א", 0), h(1, "ב", 1), h(1, "ג", 2)))

        assertEquals(1, model.primaryLevel)
        assertEquals(listOf(0, 1, 2), model.primaryRowByOrdinal)
    }

    /** A tie goes to the level that comes first in the book — the rows' own order. */
    @Test
    fun aTieGoesToTheLevelThatComesFirst() {
        val model = Toc.model(listOf(h(1, "א", 0), h(2, "ב", 1), h(1, "ג", 2), h(2, "ד", 3)))

        assertEquals(1, model.primaryLevel)
        assertEquals(listOf(0, 2), model.primaryRowByOrdinal)
    }

    /** No headings at all: no rows, and no crash on the empty book. */
    @Test
    fun aBookWithNoHeadingsHasNoToc() {
        val model = Toc.model(emptyList())

        assertTrue(model.headings.isEmpty())
        assertTrue(model.rows.isEmpty())
        assertTrue(model.primaryRowByOrdinal.isEmpty())
    }

    // ------------------------------------------------------------- the threading

    /**
     * The book is read off the calling thread. This is the ANR: the caller's thread
     * is the UI thread, and a UI thread that spends seconds parsing a 31 MB file
     * freezes the screen.
     */
    @Test
    fun theBookIsReadOffTheCallingThread() {
        val caller = Thread.currentThread()
        val reader = AtomicReference<Thread>()
        val delivered = AtomicReference<Toc.Model>()
        val done = CountDownLatch(1)

        Toc.Loader(
            read = { path ->
                reader.set(Thread.currentThread())
                Toc.model(listOf(h(1, "פרק $path", 0)))
            },
            post = { action -> action() }
        ).load("ספר") { model ->
            delivered.set(model)
            done.countDown()
        }

        assertTrue("the model never arrived", done.await(10, TimeUnit.SECONDS))
        assertFalse(
            "the book was read on the calling thread — that is the ANR",
            reader.get() === caller
        )
        assertEquals("פרק ספר", delivered.get()!!.rows[0])
    }

    /**
     * The model is delivered through the poster, not straight from the worker: the
     * screen may only be touched from the UI thread, and the worker thread is not
     * it. The poster here stands in for `runOnUiThread`.
     */
    @Test
    fun theModelIsDeliveredThroughThePoster() {
        val posted = AtomicReference<Boolean>()
        val done = CountDownLatch(1)

        Toc.Loader(
            read = { Toc.model(listOf(h(1, "פרק", 0))) },
            post = { action -> posted.set(true); action() }
        ).load("ספר") { done.countDown() }

        assertTrue("the model never arrived", done.await(10, TimeUnit.SECONDS))
        assertTrue("the model must go through the poster", posted.get())
    }
}
