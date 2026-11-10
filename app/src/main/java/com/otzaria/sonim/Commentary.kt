package com.otzaria.sonim

/**
 * The meforshim panel's own arithmetic: which rows it shows, and under which heading.
 *
 * Out of the Activity for the same two reasons as [Toc]: it can be wrong in a way
 * only a test can see (a section heading in the wrong place, or a row that is not a
 * choice), and the Activity had to do it while holding the screen still — the
 * resolution itself opens the sidecar and reads the target files, which is disk I/O
 * and seconds of work for a big commentary.
 *
 * The rows are modelled, not rendered: rendering needs [Ui.html], which is the
 * Android HTML parser and cannot run in a JVM test. The model is built off the
 * main thread and the screen draws it whole.
 */
object Commentary {

    /** One row of the panel: a heading between groups of meforshim, or a meforshim. */
    sealed class Row {
        /** A heading — "מקור", "קישורים נוספים". Not a choice. */
        data class Section(val title: String) : Row()

        /** A meforshim: the reference, its text, and where it lives. */
        data class Item(val ref: String, val content: String, val meforish: Otzaria.Meforish) : Row()
    }

    /** The rows of the panel for [mefs]. */
    fun model(mefs: List<Otzaria.Meforish>): List<Row> {
        val rows = ArrayList<Row>(mefs.size + 2)
        var lastKind = -1
        for (m in mefs) {
            if (m.kind != lastKind) {
                lastKind = m.kind
                // null for מפרשים: the header already says that, and the common case
                // is a screen of nothing else, which should look exactly as it always did.
                sectionTitle(m.kind)?.let { rows.add(Row.Section(it)) }
            }
            rows.add(Row.Item(m.ref, m.content, m))
        }
        return rows
    }

    /** null for מפרשים — the header already says that, and the common case is a
     * screen of nothing else, which should look exactly as it always did. */
    fun sectionTitle(kind: Int): String? = when (kind) {
        Otzaria.KIND_BASE -> "מקור"
        Otzaria.KIND_RELATED -> "קישורים נוספים"
        else -> null
    }

    /**
     * Resolves the meforshim off the calling thread and hands the rows to the screen.
     *
     * The panel used to resolve on the main thread, in `onCreate`: it opened the
     * sidecar and read the target files — disk I/O, and for a big commentary seconds
     * of a frozen screen. [resolve] runs on a worker thread and [post] decides where
     * the rows are delivered; the Activity passes `runOnUiThread`, so the screen is
     * only ever touched from the UI thread.
     */
    class Loader(
        private val resolve: (String, Int, Set<String>?) -> List<Otzaria.Meforish>,
        private val post: ((() -> Unit) -> Unit)
    ) {
        /** Resolve the meforshim on [line] of [book] in the background and deliver the rows. */
        fun load(book: String, line: Int, selected: Set<String>?, deliver: (List<Row>) -> Unit) {
            Background({ model(resolve(book, line, selected)) }, post).run(deliver)
        }
    }
}
