package com.otzaria.sonim

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Data layer for the Otzaria txt library. No database, no native libs — just files.
 *
 * On-disk layout under [root]:
 *   root/אוצריא/<categories.../>*.txt   (books, one segment per line, minimal HTML)
 *   root/idx/<BookTitle>.idx             (the meforshim sidecar, built by tools/pack_library.py)
 *
 * WHY A SIDECAR AND NOT THE RAW JSON. The reader asks two questions with wildly
 * different shapes, and the old code answered both by parsing a whole
 * `<Book>_links.json`:
 *
 *   opening a book  -> "which of these N lines get a ◆?"   — every line, no text
 *   pressing OK     -> "what is on line 636?"              — one line, with text
 *
 * For שולחן ערוך אורח חיים that JSON is 72.4 MB against this phone's 192 MB heap, so
 * the first question killed the app before it drew a row. The sidecar keeps the two
 * apart: a per-commentator bitmap answers the first from a ~49 KB read, and a line
 * directory makes the second a seek. Nothing here is ever parsed whole.
 */
object Otzaria {

    const val PREFS = "otzaria"
    const val KEY_ROOT = "root"
    const val DEFAULT_ROOT = "/storage/emulated/0/Otzaria"

    @Volatile var root: String = DEFAULT_ROOT

    val textsDir: File get() = File(root, "אוצריא")
    val idxDir: File get() = File(root, "idx")

    /** One heading (<h1..6>) in a book: its level, plain text, and 0-based list position. */
    data class Heading(val level: Int, val text: String, val pos: Int)

    /**
     * What a linked book IS to the book you are reading. Otzaria's link graph says
     * `commentary` and stops there, and it stores nearly every edge twice — once
     * each way — so "everything this book links to" was never the list of its
     * מפרשים. It was that list, plus the book's own base text, plus whatever else
     * Sefaria cross-referenced. The picker offered ויקרא as a commentator on
     * שולחן ערוך יורה דעה, and offered שולחן ערוך to a reader of משנה ברורה.
     *
     * The sidecar now carries one byte per commentator saying which of these it
     * is, decided at pack time from Sefaria's own declarations
     * (tools/linkkind.py). Nothing is dropped — a link that is not a commentary
     * is shown under its own heading instead of lying about what it is.
     */
    const val KIND_MEFARESH = 0   // a commentary on this book
    const val KIND_BASE = 1       // the sefer THIS book is a commentary on
    const val KIND_RELATED = 2    // an independent work, cross-referenced

    /** A linked book and what it is to the book being read. */
    data class Commentator(val name: String, val kind: Int)

    // A single 34 MB commentary would blow up an unbounded cache on a 2 GB phone, so the
    // line cache is bounded by total characters (LRU) and files above [BIG_FILE_BYTES] are
    // streamed a line at a time instead of retained whole.
    private const val CACHE_CHAR_BUDGET = 4_000_000   // ~8 MB of Java chars
    private const val BIG_FILE_BYTES = 2_000_000L     // stream, don't cache, above this
    private const val IDX_CACHE_MAX = 4

    private var cachedChars = 0
    private val lineCache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {}
    private val idxCache = object : LinkedHashMap<String, BookIndex?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookIndex?>) =
            size > IDX_CACHE_MAX
    }
    private val headingCache = object : LinkedHashMap<String, List<Heading>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Heading>>) =
            size > IDX_CACHE_MAX
    }

    fun loadRoot(ctx: Context) {
        root = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT
    }

    fun saveRoot(ctx: Context, newRoot: String) {
        root = newRoot.trimEnd('/', '\\')
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROOT, root).apply()
        lineCache.clear(); cachedChars = 0
        idxCache.clear()
        headingCache.clear()
    }

    /**
     * Is the library actually usable? `isDirectory` alone is not enough: with
     * READ_EXTERNAL_STORAGE denied the directory still *exists*, so the old check
     * passed and the app drew an empty screen with no explanation. Listing it is the
     * only thing that distinguishes "not there" from "there but not readable".
     */
    fun isReady(): Boolean = textsDir.isDirectory && textsDir.list() != null

    /** True when the library is present but unreadable — i.e. the permission was denied. */
    fun isUnreadable(): Boolean = textsDir.isDirectory && textsDir.list() == null

    /** filename without the trailing `.txt`, from a Windows- or unix-style path. */
    fun titleFromPath(path: String): String {
        val name = path.replace('\\', '/').substringAfterLast('/')
        return if (name.endsWith(".txt", ignoreCase = true)) name.dropLast(4) else name
    }

    /**
     * A UTF-8 byte-order mark, which Kotlin's `readLines` hands back as the first
     * character of the first line. Roughly 150 of the library's 6,618 books start
     * with one, and every regex here is anchored at `^` — so `<h1>` on line 1 is
     * not recognised as a heading and those books open with their own title
     * missing from the TOC (BUILDER.md S4). Stripped once, here, where the file is
     * read: teaching [headingRx] about it would fix this call site and leave the
     * next one.
     */
    private const val BOM = '﻿'

    private fun String.withoutBom() = if (isNotEmpty() && this[0] == BOM) substring(1) else this

    /** Read every line of a file, uncached. Callers that hold the whole book use this. */
    fun readLines(path: String): List<String> {
        val f = File(path)
        if (!f.isFile) return emptyList()
        val lines = f.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return lines
        val first = lines[0].withoutBom()
        if (first === lines[0]) return lines          // the common case, untouched
        return ArrayList<String>(lines.size).apply { add(first); addAll(lines.subList(1, lines.size)) }
    }

    /**
     * All lines of a file, cached under a character budget (LRU). Monster files
     * (> [BIG_FILE_BYTES]) are read fresh but never retained, so they can't pin memory.
     */
    fun linesOf(path: String): List<String> {
        lineCache[path]?.let { return it }        // access-order bump
        val lines = readLines(path)
        val chars = lines.sumOf { it.length }
        if (chars <= CACHE_CHAR_BUDGET) {
            lineCache[path] = lines
            cachedChars += chars
            trimCache()
        }
        return lines
    }

    private fun trimCache() {
        val it = lineCache.entries.iterator()
        while (cachedChars > CACHE_CHAR_BUDGET && lineCache.size > 1) {
            val e = it.next()
            cachedChars -= e.value.sumOf { s -> s.length }
            it.remove()
        }
    }

    /** Stream a file once, returning only the requested 1-based lines (for big targets). */
    private fun collectLines(path: String, wanted: Set<Int>): Map<Int, String> {
        val res = HashMap<Int, String>(wanted.size * 2)
        val f = File(path)
        if (!f.isFile || wanted.isEmpty()) return res
        f.bufferedReader(Charsets.UTF_8).useLines { seq ->
            var i = 0
            for (line in seq) {
                i++
                if (i in wanted) {
                    res[i] = if (i == 1) line.withoutBom() else line
                    if (res.size == wanted.size) return@useLines
                }
            }
        }
        return res
    }

    /**
     * Headings (<h1..6>) of a book as a flat, in-order list — the source for the chapter TOC.
     * Pass [preloaded] lines when the caller already has the book in memory to avoid re-reading.
     */
    private val headingRx = Regex("^<h([1-6])>(.*)</h[1-6]>\\s*$")
    fun headings(path: String, preloaded: List<String>? = null): List<Heading> {
        headingCache[path]?.let { return it }
        val src = preloaded ?: readLines(path)
        val out = ArrayList<Heading>()
        src.forEachIndexed { i, raw ->
            val m = headingRx.find(raw.trim()) ?: return@forEachIndexed
            val text = m.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            out.add(Heading(m.groupValues[1].toInt(), text, i))
        }
        headingCache[path] = out
        return out
    }

    // ---------------------------------------------------------------- sidecar

    /** One resolved commentary on a segment: which commentator, where its text lives. */
    data class LinkRef(
        val commentator: String,
        val relPath: String,     // path of the target book, relative to [root]
        val targetLine: Int,     // 1-based line in that file
        val ref: String,         // heRef — the human reference
        val kind: Int            // KIND_MEFARESH / KIND_BASE / KIND_RELATED
    )

    /**
     * A `<Book>.idx` sidecar. Construction reads only the front of the file — header,
     * commentator table, mark bitmaps, line directory — and closes it. Entries and the
     * ref blob are reached by seek, one line's worth at a time.
     *
     * Layout (all multi-byte fields big-endian, so DataInputStream order):
     *   "OZSI" u8 ver | u32 lines | u16 commentators | 5×u32 section offsets | u32 nEntries
     *   commentators : (u16 len, name)(u16 len, relative path)(u8 kind, v2 only) ×n
     *   marks        : n bitmaps of `lines` bits, bit L-1 set => that commentator is on line L
     *   linedir      : (u32 first entry, u16 count) × lines
     *   entries      : (u16 commentator, u32 target line, u32 ref offset)
     *   refs         : (u16 len, UTF-8) blob
     *
     * v1 and v2 differ only in that one byte, and the header is byte-identical
     * because every section offset is stored rather than computed. So a v1 sidecar
     * still opens here — every link reads as [KIND_MEFARESH], which is exactly what
     * v1 claimed and exactly what v2 exists to stop claiming. A v2 sidecar under an
     * older APK is refused outright, so push the APK first.
     */
    class BookIndex private constructor(
        private val file: File,
        val lineCount: Int,
        val names: List<String>,
        private val paths: List<String>,
        val kinds: IntArray,
        private val marks: ByteArray,
        private val stride: Int,
        private val lineDir: ByteArray,
        private val offEntries: Long,
        private val offRefs: Long
    ) {

        /** The linked books of one kind, in the sidecar's (sorted) order. */
        fun named(kind: Int): List<String> =
            names.filterIndexed { i, _ -> kinds[i] == kind }


        /** 1-based lines carrying a commentary from [selected] (null = every commentator). */
        fun markedLines(selected: Set<String>?): Set<Int> {
            val out = HashSet<Int>()
            for (ci in names.indices) {
                if (selected != null && !selected.contains(names[ci])) continue
                val base = ci * stride
                for (b in 0 until stride) {
                    val v = marks[base + b].toInt() and 0xFF
                    if (v == 0) continue
                    for (bit in 0 until 8) {
                        if (v and (1 shl bit) != 0) {
                            val line = b * 8 + bit + 1
                            if (line <= lineCount) out.add(line)
                        }
                    }
                }
            }
            return out
        }

        /** Everything attached to one 1-based line. A seek, never a scan. */
        fun on(line: Int): List<LinkRef> {
            if (line < 1 || line > lineCount) return emptyList()
            val p = (line - 1) * 6
            val first = be32(lineDir, p)
            val count = be16(lineDir, p + 4)
            if (count == 0) return emptyList()
            val out = ArrayList<LinkRef>(count)
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(count * 10)
                raf.seek(offEntries + first.toLong() * 10L)
                raf.readFully(buf)
                for (i in 0 until count) {
                    val ci = be16(buf, i * 10)
                    val target = be32(buf, i * 10 + 2)
                    val refOff = be32(buf, i * 10 + 6)
                    if (ci >= names.size) continue
                    raf.seek(offRefs + refOff.toLong())
                    val len = raf.readUnsignedShort()
                    val sb = ByteArray(len)
                    raf.readFully(sb)
                    out.add(LinkRef(names[ci], paths[ci], target,
                        String(sb, Charsets.UTF_8), kinds[ci]))
                }
            }
            return out
        }

        companion object {
            private const val MAGIC = 0x4F5A5349   // "OZSI"
            private const val VERSION = 2          // the newest this reader understands

            // magic 0..3 | ver 4 | lines 5..8 | nComm 9..10 | offC 11..14 |
            // offM 15..18 | offD 19..22 | offE 23..26 | offR 27..30 | nEntries 31..34
            // Reading fewer than this happens to work today because nEntries is the
            // only field the reader ignores — which is precisely the kind of accident
            // that stops being true the next time a field is added.
            private const val HEADER_SIZE = 35

            private fun be16(a: ByteArray, i: Int) =
                ((a[i].toInt() and 0xFF) shl 8) or (a[i + 1].toInt() and 0xFF)

            private fun be32(a: ByteArray, i: Int) =
                ((a[i].toInt() and 0xFF) shl 24) or ((a[i + 1].toInt() and 0xFF) shl 16) or
                    ((a[i + 2].toInt() and 0xFF) shl 8) or (a[i + 3].toInt() and 0xFF)

            /** null if absent or unreadable — a missing sidecar means "no meforshim", not a crash. */
            fun open(f: File): BookIndex? {
                if (!f.isFile) return null
                try {
                    RandomAccessFile(f, "r").use { raf ->
                        val head = ByteArray(HEADER_SIZE)
                        if (raf.length() < head.size) return null
                        raf.readFully(head)
                        if (be32(head, 0) != MAGIC) return null
                        val ver = head[4].toInt() and 0xFF
                        if (ver !in 1..VERSION) return null
                        val lines = be32(head, 5)
                        val nComm = be16(head, 9)
                        val offC = be32(head, 11).toLong()
                        val offM = be32(head, 15).toLong()
                        val offD = be32(head, 19).toLong()
                        val offE = be32(head, 23).toLong()
                        val offR = be32(head, 27).toLong()
                        if (lines <= 0 || nComm <= 0) return null

                        raf.seek(offC)
                        val names = ArrayList<String>(nComm)
                        val paths = ArrayList<String>(nComm)
                        val kinds = IntArray(nComm)
                        repeat(nComm) { i ->
                            var n = raf.readUnsignedShort()
                            var b = ByteArray(n); raf.readFully(b)
                            names.add(String(b, Charsets.UTF_8))
                            n = raf.readUnsignedShort()
                            b = ByteArray(n); raf.readFully(b)
                            paths.add(String(b, Charsets.UTF_8))
                            // A v1 sidecar has no kind byte; everything in it was
                            // asserted to be a commentary, so that is what it reads as.
                            val k = if (ver >= 2) raf.readUnsignedByte() else KIND_MEFARESH
                            kinds[i] = if (k in KIND_MEFARESH..KIND_RELATED) k else KIND_RELATED
                        }
                        val stride = (lines + 7) / 8
                        val marks = ByteArray(nComm * stride)
                        raf.seek(offM); raf.readFully(marks)
                        val dir = ByteArray(lines * 6)
                        raf.seek(offD); raf.readFully(dir)
                        return BookIndex(f, lines, names, paths, kinds, marks, stride, dir, offE, offR)
                    }
                } catch (t: Throwable) {
                    // Throwable, not Exception: OutOfMemoryError is an Error, and catching
                    // only Exception is exactly how the old loadLinks turned a big book
                    // into a crash instead of a book without meforshim.
                    return null
                }
            }
        }
    }

    /** The sidecar for a book, cached. null when the book has no meforshim on this phone. */
    fun indexFor(bookTitle: String): BookIndex? {
        if (idxCache.containsKey(bookTitle)) return idxCache[bookTitle]
        val ix = BookIndex.open(File(idxDir, "$bookTitle.idx"))
        idxCache[bookTitle] = ix
        return ix
    }

    /**
     * Every book linked from this one, in a stable order, each saying what it IS to
     * this one. This is the pool the per-book filter picks from — it is the
     * sidecar's commentator table, so it costs nothing beyond opening the file.
     *
     * The name-only version this replaced is what let the picker call all three
     * kinds "commentators".
     */
    fun commentators(bookTitle: String): List<Commentator> {
        val ix = indexFor(bookTitle) ?: return emptyList()
        return ix.names.mapIndexed { i, n -> Commentator(n, ix.kinds[i]) }
    }

    /**
     * What to show a reader who has never chosen for this book: the מפרשים, plus
     * the sefer it is itself a commentary on. Cross-references stay off — they are
     * the bucket that used to make the picker unusable, and a reader who wants
     * them is one keypress away.
     */
    fun defaultSelection(bookTitle: String): Set<String> {
        val ix = indexFor(bookTitle) ?: return emptySet()
        return ix.names.filterIndexed { i, _ -> ix.kinds[i] != KIND_RELATED }.toSet()
    }

    /** [selected] if the reader has chosen, otherwise [defaultSelection]. */
    fun effectiveSelection(bookTitle: String, selected: Set<String>?): Set<String> =
        selected ?: defaultSelection(bookTitle)

    /**
     * Which 1-based source lines carry a commentary from a selected commentator.
     * [selected] == null means the reader has never chosen — see [defaultSelection].
     * It used to mean "every commentator", which is how a ◆ came to promise
     * meforshim on lines whose only link was a cross-reference.
     */
    fun commentedLines(bookTitle: String, selected: Set<String>?): Set<Int> =
        indexFor(bookTitle)?.markedLines(effectiveSelection(bookTitle, selected)) ?: emptySet()

    /** Resolved meforshim on a given 1-based source line: ref + rendered content text. */
    data class Meforish(val ref: String, val content: String, val commentator: String, val kind: Int)

    /**
     * Meforshim on [sourceLine], keeping only [selected] commentators (null = the
     * default choice for this book). Each target file is opened once; giant
     * commentaries are streamed a line at a time, so opening meforshim can't pin
     * memory however big the target is. The result is ordered מפרשים, then the
     * base text, then cross-references — so the panel reads top-down as
     * "commentary first, source second".
     */
    fun meforshimFor(bookTitle: String, sourceLine: Int, selected: Set<String>?): List<Meforish> {
        val keep = effectiveSelection(bookTitle, selected)
        val relevant = (indexFor(bookTitle)?.on(sourceLine) ?: emptyList())
            .filter { keep.contains(it.commentator) }
        if (relevant.isEmpty()) return emptyList()

        val content = HashMap<String, String>()            // "relPath#line" -> text
        for ((rel, entries) in relevant.groupBy { it.relPath }) {
            val path = File(root, rel).absolutePath
            val wanted = entries.map { it.targetLine }.filter { it >= 1 }.toHashSet()
            val byLine = if (File(path).length() > BIG_FILE_BYTES)
                collectLines(path, wanted)
            else {
                val ls = linesOf(path)
                wanted.mapNotNull { n -> ls.getOrNull(n - 1)?.let { n to it } }.toMap()
            }
            for ((n, text) in byLine) content["$rel#$n"] = text
        }

        val result = ArrayList<Meforish>(relevant.size)
        for (l in relevant) {
            val text = content["${l.relPath}#${l.targetLine}"] ?: continue
            result.add(Meforish(l.ref, text, l.commentator, l.kind))
        }
        result.sortBy { it.kind }        // stable: מפרשים, then מקור, then קשור
        return result
    }
}
