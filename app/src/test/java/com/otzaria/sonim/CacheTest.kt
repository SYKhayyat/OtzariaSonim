package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The caches in [Otzaria] are written by the background loader and read by the UI
 * thread, so they have to survive being touched from two threads at once.
 *
 * They used to be plain `LinkedHashMap`s in ACCESS ORDER. In access order a `get`
 * is a write: it moves the entry to the front of the map. A `get` from the UI
 * thread landing in the middle of a `put` from the loader can leave the map's
 * internal links in a state where it hands back the wrong entry, or where the
 * iterator never ends.
 *
 * The symptom is a reader who sees one line of a sefer where another belongs, or a
 * phone that stops responding — not a crash with a stack trace, which is why this
 * was worth a test rather than a shrug.
 */
class CacheTest {

    /** The oldest entry goes first when the cache is full. */
    @Test
    fun theOldestEntryIsTheOneThatGoes() {
        val cache = LruCache<String, String>(maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        assertNull("the oldest entry must be gone", cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    /** A read counts as a use, so it saves an entry from the next eviction. */
    @Test
    fun readingAnEntryKeepsItAlive() {
        val cache = LruCache<String, String>(maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a")                     // a is now the newest
        cache.put("c", "3")
        assertNotNull("a was read, so it must survive", cache.get("a"))
        assertNull("b was the oldest by now", cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    /** The budget is counted in characters, and the count must not drift. */
    @Test
    fun theWeightBudgetIsCountedAndKept() {
        val cache = LruCache<String, String>(maxWeight = 10, weightOf = { it.length.toLong() })
        cache.put("a", "12345")
        cache.put("b", "12345")
        assertEquals(10, cache.heldWeight)
        cache.put("c", "1")
        assertTrue("the budget must hold, it is ${cache.heldWeight}", cache.heldWeight <= 10)
        assertNull("the oldest entry must have gone", cache.get("a"))
    }

    /** One entry over the budget is kept — a big book is better than no book. */
    @Test
    fun oneBigEntryIsKeptEvenWhenItIsOverTheBudget() {
        val cache = LruCache<String, String>(maxWeight = 4, weightOf = { it.length.toLong() })
        cache.put("big", "1234567890")
        assertEquals("1234567890", cache.get("big"))
    }

    /** The count of what is held must be the truth after an eviction, not a guess. */
    @Test
    fun theWeightCountIsRightAfterEntriesComeAndGo() {
        val cache = LruCache<String, String>(maxWeight = 6, weightOf = { it.length.toLong() })
        cache.put("a", "111")
        cache.put("b", "222")
        cache.put("c", "333")            // evicts a
        assertEquals(6, cache.heldWeight)
        cache.put("a", "1")              // replaces nothing; a was gone
        assertEquals(4, cache.heldWeight)
        cache.put("a", "22")             // replaces a; the old weight must come off
        assertEquals(5, cache.heldWeight)
    }

    /** A value is computed once and then served from the cache. */
    @Test
    fun aComputedValueIsKeptForNextTime() {
        val cache = LruCache<String, String>()
        val calls = AtomicInteger()
        val first = cache.getOrCompute("k") { calls.incrementAndGet(); "made" }
        val second = cache.getOrCompute("k") { calls.incrementAndGet(); "made again" }
        assertEquals("made", first)
        assertEquals("made", second)
        assertEquals("the factory must run once", 1, calls.get())
    }

    /**
     * The whole point: two threads hammering one cache must not corrupt it.
     *
     * The old cache was a `LinkedHashMap` in access order, and access order makes a
     * read a write. Here one thread reads and writes while another does the same,
     * and afterwards every key that was written must still be readable — or the
     * cache handed somebody the wrong line.
     */
    @Test
    fun twoThreadsHammeringOneCacheDoNotCorruptIt() {
        val keys = (0 until 200).map { "key-$it" }
        // Big enough that nothing is evicted: this test is about corruption, and a
        // key that was evicted on purpose would look like a corrupted one.
        val cache = LruCache<String, String>(maxEntries = 1024)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val workers = 8
        val threads = (0 until workers).map { worker ->
            Thread {
                try {
                    start.await()
                    repeat(2000) { i ->
                        val key = keys[(i + worker * 37) % keys.size]
                        cache.put(key, key)
                        cache.get(key)
                        cache.getOrCompute("shared-$worker") { "v$worker" }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertTrue("a worker thread hung on the cache", threads.none { it.isAlive })
        failure.get()?.let { throw AssertionError("the cache broke under two threads: $it") }

        // Every key must still read back as itself — a corrupted cache would not.
        for (key in keys) assertEquals(key, cache.get(key))
    }

    /** The same, on a cache that IS evicting: the limit must hold and the rest must be readable. */
    @Test
    fun twoThreadsHammeringOneEvictingCacheKeepItWithinItsLimit() {
        val cache = LruCache<String, String>(maxEntries = 32)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val threads = (0 until 6).map { worker ->
            Thread {
                try {
                    start.await()
                    repeat(2000) { i ->
                        val key = "key-${(i + worker * 37) % 500}"
                        cache.put(key, key)
                        cache.get(key)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertTrue("a worker thread hung on the cache", threads.none { it.isAlive })
        failure.get()?.let { throw AssertionError("the cache broke under two threads: $it") }
        assertTrue("the cache must respect its limit, it holds ${cache.size}", cache.size <= 32)
        assertEquals("the size must match the weight count", cache.size.toLong(), cache.heldWeight)
    }

    /**
     * The data layer must not go back to a plain map.
     *
     * This is the same trade [NotesKeyMapTest] makes: the race cannot be seen from a
     * JVM test on the real app, so the source is the contract, and this fails the
     * build if somebody "simplifies" the cache back into a `LinkedHashMap`.
     */
    @Test
    fun theDataLayerDoesNotKeepItsCachesInAPlainMap() {
        val src = File("").absoluteFile
        var dir: File? = src
        while (dir != null && dir.parentFile != null &&
            !File(dir, "app/src/main/java/com/otzaria/sonim").isDirectory
        ) dir = dir.parentFile
        val main = File(dir!!, "app/src/main/java/com/otzaria/sonim")
        assertTrue("could not find the source tree from ${src.path}", main.isDirectory)
        val text = File(main, "Otzaria.kt").readText()
        // Only code counts — a comment may name the old map to say why it is gone.
        val code = text.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")
        val offenders = Regex("""LinkedHashMap\s*<""").findAll(code).count()
        assertEquals(
            "Otzaria's caches must be LruCache — a plain map in access order is not " +
                "safe when the loader and the UI thread share it",
            0, offenders
        )
        assertTrue(
            "the caches must be LruCache",
            code.contains("LruCache<")
        )
    }
}
