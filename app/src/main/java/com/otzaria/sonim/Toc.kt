package com.otzaria.sonim

/**
 * The chapter TOC's own arithmetic: which rows the list shows, how deep each one
 * is indented, and which rows the typed chapter number picks.
 *
 * Out of the Activity for two reasons. The first is testability: this is the part
 * of the TOC that can be wrong in a way only a test can see — a number that picks
 * the wrong chapter, or a heading that sits at the wrong depth. The second is that
 * the Activity had to do all of it *after* it had already read the whole book, and
 * the reading was the expensive part: [Otzaria.headings] parses every line of a
 * 31 MB sefer, which is seconds of work and seconds of ANR on the main thread.
 *
 * The model is built once, off the main thread, and handed to the screen whole.
 */
object Toc {

    /**
     * The TOC of a book: the rows to show, and the rows the chapter number picks.
     *
     * [primaryRowByOrdinal] is ordinal-1 -> row index, so typing `7` puts the
     * highlight on the seventh chapter of the book's primary level.
     */
    data class Model(
        val headings: List<Otzaria.Heading>,
        /** The shallowest heading level in the book — the depth the rows start at. */
        val minLevel: Int,
        /** The level a chapter number counts in: the most common one. */
        val primaryLevel: Int,
        /** ordinal-1 -> row index in [rows]. */
        val primaryRowByOrdinal: List<Int>,
        /** The rows, indented by heading depth. */
        val rows: List<String>
    )

    /**
     * The model of [headings].
     *
     * The primary level is the most common one: a perek in Mishnah, a siman in
     * Er.HaShulchan, a chapter in a sefer. A book whose headings are all at the
     * same level has that level as its primary one, so the number still means
     * something.
     *
     * Ties go to the level that appears first in the book, which is the order the
     * rows are in anyway.
     */
    fun model(headings: List<Otzaria.Heading>): Model {
        if (headings.isEmpty()) {
            return Model(emptyList(), 0, 0, emptyList(), emptyList())
        }
        val minLevel = headings.minOf { it.level }
        val primaryLevel = headings.groupingBy { it.level }.eachCount()
            .maxByOrNull { it.value }!!.key
        val primaryRowByOrdinal = headings.indices.filter { headings[it].level == primaryLevel }
        val rows = headings.map { INDENT.repeat(it.level - minLevel) + it.text }
        return Model(headings, minLevel, primaryLevel, primaryRowByOrdinal, rows)
    }

    /** One em-space per level of depth: the indent, and nothing else. */
    private const val INDENT = " "

    /**
     * Reads a book off the calling thread and hands the model to the screen.
     *
     * The TOC used to parse the whole sefer on the main thread. That is seconds of
     * work — 31 MB and 26,776 lines for ערוך השולחן — and on the main thread it
     * is an ANR: the screen freezes and the system offers to close the app.
     *
     * This class exists so that "off the main thread" is a thing a test can check
     * rather than a thing a comment promises. [read] is called on a worker thread
     * and [deliver] on whatever thread [post] uses — the Activity passes
     * `runOnUiThread`, so the screen is only ever touched from the UI thread.
     */
    class Loader(
        private val read: (String) -> Model,
        private val post: ((() -> Unit) -> Unit)
    ) {
        /** Read the book at [path] in the background and deliver its model. */
        fun load(path: String, deliver: (Model) -> Unit) {
            Thread {
                val model = read(path)
                post { deliver(model) }
            }.start()
        }
    }
}
