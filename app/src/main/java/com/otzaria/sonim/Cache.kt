package com.otzaria.sonim

import java.util.LinkedHashMap

/**
 * A cache that more than one thread may touch.
 *
 * WHY THIS EXISTS. The reader loads a book on a background thread while the notes
 * list and the meforshim panel read the same books on the UI thread. The caches
 * they shared were plain `LinkedHashMap`s in ACCESS ORDER — and in access order a
 * `get` is a write: it moves the entry to the front of the map. Two threads doing
 * that to one map corrupt its internal links, and the symptom is not a crash but a
 * cache that hands back the wrong lines, stops evicting, or hangs.
 *
 * So every operation here takes one lock, and the accounting that goes with it —
 * how many characters the cache holds — is under the same lock as the map it
 * describes. Otherwise the count and the map disagree, and the budget is a lie.
 *
 * The lock is deliberately coarse. Every operation here is a few map steps and a
 * few arithmetic ops; the slow work (reading a book off the disk) happens OUTSIDE
 * the lock, in the caller or in [getOrCompute]'s factory.
 */
class LruCache<K, V>(
    /** How many entries may be held at once. */
    private val maxEntries: Int = Int.MAX_VALUE,
    /** How much weight may be held at once (see [weightOf]). */
    private val maxWeight: Long = Long.MAX_VALUE,
    /** What one entry costs against [maxWeight]. */
    private val weightOf: (V) -> Long = { 1L }
) {

    /** A value and what it costs, kept together so the budget needs no recomputing. */
    private class Slot<V>(val value: V, val weight: Long)

    private val lock = Any()
    private var weight = 0L

    /**
     * Access order: a read moves an entry to the front, so eviction takes the
     * oldest. The map is never touched outside the lock.
     */
    private val slots = LinkedHashMap<K, Slot<V>>(16, 0.75f, true)

    /** The value for [key], or null if there is none. Counts as a use. */
    fun get(key: K): V? = synchronized(lock) { slots[key]?.value }

    /**
     * Store [value] under [key], evicting the oldest entries to stay inside the limits.
     * [weight] is what it costs against the budget; it defaults to [weightOf].
     */
    fun put(key: K, value: V, weight: Long = weightOf(value)) = synchronized(lock) {
        slots.remove(key)?.let { this.weight -= it.weight }
        slots[key] = Slot(value, weight)
        this.weight += weight
        trim()
    }

    /**
     * The value for [key], computing and storing it if it is not there yet.
     *
     * The factory runs OUTSIDE the lock: it opens files, and holding the lock
     * through that would make every other thread wait for the disk.
     */
    fun getOrCompute(key: K, create: () -> V): V {
        synchronized(lock) {
            val hit = slots[key]
            if (hit != null) return hit.value
        }
        val made = create()
        synchronized(lock) {
            val existing = slots[key]
            if (existing != null) return existing.value
            val w = weightOf(made)
            slots[key] = Slot(made, w)
            weight += w
            trim()
            return made
        }
    }

    /** Forget everything. */
    fun clear() = synchronized(lock) {
        slots.clear()
        weight = 0L
    }

    /** How many entries are held. */
    val size: Int get() = synchronized(lock) { slots.size }

    /** How much weight is held, against [maxWeight]. */
    val heldWeight: Long get() = synchronized(lock) { weight }

    /**
     * Evict the oldest entries until the limits hold.
     *
     * The weight limit never takes the LAST entry: one oversized book is better
     * than none. The caller decides separately what is too big to keep at all —
     * this cache only decides what to drop first.
     */
    private fun trim() {
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val tooMany = slots.size > maxEntries
            val tooHeavy = weight > maxWeight && slots.size > 1
            if (!tooMany && !tooHeavy) break
            val eldest = it.next()
            it.remove()
            weight -= eldest.value.weight
        }
    }
}
