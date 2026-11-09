package com.otzaria.sonim

import android.content.Context

/**
 * הערות — the reference stack.
 *
 * An entry is a POINTER and nothing else: a book path, a 1-based line, and the phrase
 * that says where it came from. No text is copied — not the segment, not the
 * meforshim, not even an opening phrase. The preview shown in the list is read out of
 * the library when the row is drawn ([Otzaria.lineAt] + [preview]), so the list can
 * never disagree with the sefer it points into, and a re-packed library is reflected
 * the moment it lands instead of leaving stale copies behind.
 *
 * Order is [Entry.seq], a counter that only ever goes up, so the same reference pushed
 * twice is two entries with two seqs. That is the whole reason the stack is not a Set:
 * a reader who pushes a pasuk, wanders off, and pushes it again is doing something,
 * and a Set would silently refuse the second push.
 *
 * This object holds the pure core (encode/decode/add/remove/layout) plus the two
 * prefs calls. The pure part is unit-tested on the JVM — there is no device in CI.
 */
object Notes {

    /**
     * One saved reference.
     *
     * [path] is relative to [Otzaria.root] when the book lives under it (every link
     * target is stored that way by the packer), so nothing here breaks when the
     * library folder is moved or the phone is swapped.
     */
    data class Entry(
        val path: String,
        val line: Int,
        /** "Where it is from" — `ספר · פרק`, or a mefaresh's own heRef. */
        val where: String,
        /** Insertion order. The sort key, and the identity of one copy. */
        val seq: Long
    ) {
        /** The sefer this line belongs to. Derived, never stored twice. */
        val book: String get() = Otzaria.titleFromPath(path)
    }

    // ------------------------------------------------------------- pure core

    /** Tags that END a line of text — these become a space when flattened. */
    private val blockRx = Regex("</?(?:h[1-6]|br|p|div|li|tr|td|table)[^>]*>", RegexOption.IGNORE_CASE)
    private val tagRx = Regex("<[^>]*>")
    private val spaceRx = Regex("\\s+")

    /**
     * One line of library text, flattened for reading aloud: markup off, runs of
     * space collapsed. NOT capped — the row shows the whole segment, and the
     * description of it says the whole segment.
     *
     * The flattening is for the spoken form only. The row itself is drawn from the
     * raw line, so it looks like the reader's box rather than like this.
     */
    fun plain(text: String): String {
        // Block tags become a space; INLINE tags become nothing at all. Replacing
        // every tag with a space splits real words: this corpus wraps parts of a
        // vocalised word in tags, so "בְּרֵאשִׁ֖ית" came out as "בְּ רֵאשִׁ֖ית".
        // NBSP is not \s for Java's regex and this corpus is full of it, as is the
        // entity spelling of it — both collapse to a plain space before the run
        // collapse does the rest.
        val plain = tagRx.replace(blockRx.replace(text, " "), "")
            .replace("&nbsp;", " ").replace('\u00A0', ' ')
        return spaceRx.replace(plain, " ").trim()
    }

    /**
     * Push a reference. Duplicates are kept: the same (path, line) pushed twice is
     * two entries, which is what the key does when pressed twice.
     */
    fun add(entries: List<Entry>, path: String, line: Int, where: String): List<Entry> =
        entries + Entry(path, line, where, (entries.maxOfOrNull { it.seq } ?: 0L) + 1L)

    /** Drop one copy — the highlighted one. */
    fun removeOne(entries: List<Entry>, seq: Long): List<Entry> = entries.filterNot { it.seq == seq }

    /** Drop every copy of a reference — what D-pad Left does outside the list. */
    fun removeAll(entries: List<Entry>, path: String, line: Int): List<Entry> =
        entries.filterNot { it.path == path && it.line == line }

    fun countOf(entries: List<Entry>, path: String, line: Int): Int =
        entries.count { it.path == path && it.line == line }

    /**
     * The orders the list can be shown in, with the words the header shows.
     *
     * There are four of them and they are chosen from the little menu that opens
     * inside הערות (a tap on MENU), not cycled with a key: the key that cycled them
     * (`*`) now opens the sefer the highlighted reference points into, which is the
     * thing the stack was missing.
     */
    enum class Order(val title: String) {
        NEWEST("חדש למעלה"),
        OLDEST("ישן למעלה"),
        BY_SEFER("לפי ספר"),
        BY_SEFER_BY_LINE("לפי ספר ולפי שורה")
    }

    /**
     * One row of the little menu inside הערות.
     *
     * The menu is a pure list so that the screen cannot show one set of orders and
     * the core lay out another: the menu is built from [menu], and [menu] is built
     * from [Order] alone.
     */
    sealed class MenuItem(val label: String) {
        /** Show the list in this order. */
        data class ByOrder(val order: Order) : MenuItem(order.title)
        /** Close הערות and go back to the seforim. */
        data object BackToSefarim : MenuItem("חזרה לספרים")
    }

    /** The menu inside הערות: the four orders, then the way out. */
    fun menu(): List<MenuItem> =
        Order.entries.map { MenuItem.ByOrder(it) } + MenuItem.BackToSefarim

    /** A rendered line of the list: either a sefer heading or a reference. */
    sealed class Row {
        data class Group(val book: String, val count: Int) : Row()
        data class Item(val entry: Entry) : Row()
    }

    /**
     * Rows for [entries] in [order]. Only the grouped orders emit [Row.Group] rows;
     * callers must treat those as labels, not choices (the list is built so the
     * D-pad cannot land on them — see [NotesAdapter]).
     *
     * Groups are ordered by their newest entry, so pushing into a sefer lifts that
     * sefer to the top of the grouping. Inside a group the newest is first, the same
     * way the flat NEWEST order reads — except in [Order.BY_SEFER_BY_LINE], which
     * reads a sefer the way the sefer itself is read: from its first line to its
     * last.
     */
    fun layout(entries: List<Entry>, order: Order): List<Row> = when (order) {
        Order.NEWEST -> entries.sortedByDescending { it.seq }.map { Row.Item(it) }
        Order.OLDEST -> entries.sortedBy { it.seq }.map { Row.Item(it) }
        Order.BY_SEFER -> {
            val groups = entries.groupBy { it.book }
            val ordered = groups.entries.sortedByDescending { (_, es) -> es.maxOf { it.seq } }
            val out = ArrayList<Row>(entries.size + ordered.size)
            for ((book, es) in ordered) {
                out.add(Row.Group(book, es.size))
                es.sortedByDescending { it.seq }.forEach { out.add(Row.Item(it)) }
            }
            out
        }
        Order.BY_SEFER_BY_LINE -> {
            // Grouped by PATH, not by title: two different files can share a title,
            // and their line numbers have nothing to do with each other. The
            // heading still shows the title, which is what the reader knows the
            // book by.
            val groups = entries.groupBy { it.path }
            val ordered = groups.entries.sortedByDescending { (_, es) -> es.maxOf { it.seq } }
            val out = ArrayList<Row>(entries.size + ordered.size)
            for ((path, es) in ordered) {
                out.add(Row.Group(es.first().book, es.size))
                es.sortedWith(compareBy({ it.line }, { it.seq })).forEach { out.add(Row.Item(it)) }
            }
            out
        }
    }

    /** Index of the row holding [seq], or -1. Used to keep the highlight put. */
    fun indexOf(rows: List<Row>, seq: Long): Int =
        rows.indexOfFirst { it is Row.Item && it.entry.seq == seq }

    // ------------------------------------------------------------- storage
    //
    // One prefs string, one record per line. Fields are tab-separated because a tab
    // cannot survive inside a field unescaped, and the escaping makes that true for
    // the two characters that could break the format.

    private const val KEY = "notes"
    private const val FIELD = '\t'
    private const val FIELDS = 4

    fun encode(entries: List<Entry>): String = entries.joinToString("\n") { e ->
        listOf(esc(e.path), e.line.toString(), esc(e.where), e.seq.toString()).joinToString("\t")
    }

    /** Tolerant by construction: a record that does not parse is skipped, not fatal. */
    fun decode(blob: String?): List<Entry> {
        if (blob.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        for (rec in blob.split('\n')) {
            val f = rec.split(FIELD)
            if (f.size != FIELDS) continue
            val line = f[1].toIntOrNull() ?: continue
            val seq = f[3].toLongOrNull() ?: continue
            val path = unesc(f[0])
            if (path.isEmpty() || line < 1) continue
            out.add(Entry(path, line, unesc(f[2]), seq))
        }
        return out
    }

    private fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    private fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> b.append('\t')
                    'n' -> b.append('\n')
                    '\\' -> b.append('\\')
                    else -> b.append(s[i + 1])
                }
                i += 2
            } else {
                b.append(c); i++
            }
        }
        return b.toString()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Entry> = decode(prefs(ctx).getString(KEY, null))

    fun save(ctx: Context, entries: List<Entry>) {
        prefs(ctx).edit().putString(KEY, encode(entries)).apply()
    }
}
