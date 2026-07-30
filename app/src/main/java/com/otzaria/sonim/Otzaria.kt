package com.otzaria.sonim

import android.content.Context
import org.json.JSONArray
import java.io.File

/** One resolved commentary/link entry from a `<Book>_links.json` file. */
data class LinkEntry(
    val ref: String,          // heRef_2 — human reference of the target
    val lineIndex1: Int,      // 1-based line in the SOURCE book this attaches to
    val targetTitle: String,  // filename (no .txt) of the target book — the key we resolve by
    val lineIndex2: Int,      // 1-based line in the target book
    val connectionType: String
)

/**
 * Data layer for the Otzaria txt library. No database, no native libs — just files.
 *
 * On-disk layout under [root]:
 *   root/אוצריא/<categories.../>*.txt   (books, one segment per line, minimal HTML)
 *   root/links/<BookTitle>_links.json    (meforshim graph)
 *
 * Links are resolved BY FILENAME, not by the (stale) path stored in the JSON.
 */
object Otzaria {

    const val PREFS = "otzaria"
    const val KEY_ROOT = "root"
    const val DEFAULT_ROOT = "/storage/emulated/0/Otzaria"

    @Volatile var root: String = DEFAULT_ROOT

    val textsDir: File get() = File(root, "אוצריא")
    val linksDir: File get() = File(root, "links")

    /** One heading (<h1..6>) in a book: its level, plain text, and 0-based list position. */
    data class Heading(val level: Int, val text: String, val pos: Int)

    // A single 34 MB commentary would blow up an unbounded cache on a 2 GB phone, so the
    // line cache is bounded by total characters (LRU) and files above [BIG_FILE_BYTES] are
    // streamed a line at a time instead of retained whole. linksCache is bounded by count.
    private const val CACHE_CHAR_BUDGET = 4_000_000   // ~8 MB of Java chars
    private const val BIG_FILE_BYTES = 2_000_000L     // stream, don't cache, above this
    private const val LINKS_CACHE_MAX = 8

    private var titleIndex: Map<String, String>? = null
    private var cachedChars = 0
    private val lineCache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {}
    private val linksCache = object : LinkedHashMap<String, List<LinkEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LinkEntry>>) =
            size > LINKS_CACHE_MAX
    }
    private val headingCache = object : LinkedHashMap<String, List<Heading>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Heading>>) =
            size > LINKS_CACHE_MAX
    }

    fun loadRoot(ctx: Context) {
        root = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOT, DEFAULT_ROOT) ?: DEFAULT_ROOT
    }

    fun saveRoot(ctx: Context, newRoot: String) {
        root = newRoot.trimEnd('/', '\\')
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROOT, root).apply()
        titleIndex = null
        lineCache.clear(); cachedChars = 0
        linksCache.clear()
        headingCache.clear()
    }

    fun isReady(): Boolean = textsDir.isDirectory

    /** filename without the trailing `.txt`, from a Windows- or unix-style path. */
    fun titleFromPath(path: String): String {
        val name = path.replace('\\', '/').substringAfterLast('/')
        return if (name.endsWith(".txt", ignoreCase = true)) name.dropLast(4) else name
    }

    /** Build (and cache) a `bookTitle -> absolute path` index over the whole texts tree. */
    fun index(): Map<String, String> {
        titleIndex?.let { return it }
        val map = HashMap<String, String>()
        val dir = textsDir
        if (dir.isDirectory) {
            dir.walkTopDown().forEach { f ->
                if (f.isFile && f.name.endsWith(".txt", ignoreCase = true)) {
                    map[f.name.dropLast(4)] = f.absolutePath
                }
            }
        }
        titleIndex = map
        return map
    }

    fun pathForTitle(title: String): String? = index()[title]

    /** Read every line of a file, uncached. Callers that hold the whole book use this. */
    fun readLines(path: String): List<String> {
        val f = File(path)
        return if (f.isFile) f.readLines(Charsets.UTF_8) else emptyList()
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
                    res[i] = line
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

    private fun parseIndex(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.substringBefore('.').trim().toIntOrNull() ?: 0
        else -> 0
    }

    private fun isCommentary(l: LinkEntry) =
        l.connectionType == "commentary" || l.connectionType == "targum"

    /** Load and normalise the link graph for a book (by its title). Cached. */
    fun loadLinks(bookTitle: String): List<LinkEntry> {
        linksCache[bookTitle]?.let { return it }
        val f = File(linksDir, bookTitle + "_links.json")
        if (!f.isFile) { linksCache[bookTitle] = emptyList(); return emptyList() }
        val arr = try {
            JSONArray(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            linksCache[bookTitle] = emptyList(); return emptyList()
        }
        val out = ArrayList<LinkEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                LinkEntry(
                    ref = o.optString("heRef_2", ""),
                    lineIndex1 = parseIndex(o.opt("line_index_1")),
                    targetTitle = titleFromPath(o.optString("path_2", "")),
                    lineIndex2 = parseIndex(o.opt("line_index_2")),
                    // note the source data's misspelling; accept both
                    connectionType = o.optString(
                        "Conection Type",
                        o.optString("Connection Type", "")
                    )
                )
            )
        }
        linksCache[bookTitle] = out
        return out
    }

    /**
     * Commentators (target book titles) that have any commentary/targum link in this book,
     * in first-appearance order. This is the pool the per-book filter picks from.
     */
    fun availableCommentators(bookTitle: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (l in loadLinks(bookTitle)) if (isCommentary(l)) seen.add(l.targetTitle)
        return seen.toList()
    }

    /**
     * Which 1-based source lines carry a commentary from a selected commentator.
     * [selected] == null means "no filter" (all commentators count).
     */
    fun commentedLines(bookTitle: String, selected: Set<String>?): Set<Int> {
        val s = HashSet<Int>()
        for (l in loadLinks(bookTitle)) {
            if (!isCommentary(l)) continue
            if (selected != null && !selected.contains(l.targetTitle)) continue
            s.add(l.lineIndex1)
        }
        return s
    }

    /** Resolved meforshim on a given 1-based source line: ref + rendered content text. */
    data class Meforish(val ref: String, val content: String, val commentator: String)

    /**
     * Meforshim on [sourceLine], keeping only [selected] commentators (null = all),
     * ordered by the commentator order in [selected] when provided.
     */
    fun meforshimFor(bookTitle: String, sourceLine: Int, selected: Set<String>?): List<Meforish> {
        val relevant = loadLinks(bookTitle).filter {
            it.lineIndex1 == sourceLine && isCommentary(it) &&
                (selected == null || selected.contains(it.targetTitle))
        }
        if (relevant.isEmpty()) return emptyList()

        // Resolve each target file at most once, reading only the wanted lines. A giant
        // commentary is streamed (never loaded whole) so opening meforshim can't OOM.
        val content = HashMap<String, String>()            // "targetTitle#line2" -> text
        for ((title, entries) in relevant.groupBy { it.targetTitle }) {
            val path = pathForTitle(title) ?: continue
            val wanted = entries.map { it.lineIndex2 }.filter { it >= 1 }.toHashSet()
            val byLine = if (File(path).length() > BIG_FILE_BYTES)
                collectLines(path, wanted)
            else {
                val ls = linesOf(path)
                wanted.mapNotNull { n -> ls.getOrNull(n - 1)?.let { n to it } }.toMap()
            }
            for ((n, text) in byLine) content["$title#$n"] = text
        }

        // Emit in the original link order.
        val result = ArrayList<Meforish>(relevant.size)
        for (l in relevant) {
            val text = content["${l.targetTitle}#${l.lineIndex2}"] ?: continue
            result.add(Meforish(l.ref, text, l.targetTitle))
        }
        return result
    }
}
