package com.otzaria.sonim

import java.io.File

/**
 * The library's folder tree: what a folder holds, and how each row is named.
 *
 * Out of the Activity because listing a folder is the app's one big I/O job: the
 * אוצריא folder holds 6,618 books on an SD card, and `File.listFiles()` on it reads
 * every name off the disk. The Activity used to do that on the UI thread, and to do
 * it *again* on every Back press, because "is the library still there?" was answered
 * by listing it.
 *
 * So the state of the library and its contents come from one listing, and it comes
 * from the background.
 */
object Library {

    /** What a folder is: readable, present but unreadable, or not there at all. */
    enum class State {
        /** The folder is there and could be read. */
        READY,

        /**
         * The folder is there but cannot be read — the permission was denied.
         *
         * `isDirectory` alone cannot say this: with READ_EXTERNAL_STORAGE denied the
         * directory still exists, so the old check passed and the app drew an empty
         * screen with no explanation. Listing it is the only thing that tells
         * "not there" from "there but not readable".
         */
        UNREADABLE,

        /** There is no such folder. */
        MISSING
    }

    /** One row of the library: a folder or a book. */
    data class Entry(
        val file: File,
        val isDirectory: Boolean
    ) {
        /** The name of the book, without the `.txt` the library stores it under. */
        val title: String get() = if (isDirectory) file.name else file.name.removeSuffix(".txt")

        /** What the row says. */
        val label: String get() = if (isDirectory) FOLDER_MARK + file.name else title

        /**
         * What the row says to a screen reader.
         *
         * The folder emoji is announced as "file folder" in English, mid-Hebrew, so
         * the kind of the row is spelled out instead.
         */
        val spoken: String get() = if (isDirectory) "תיקייה, ${file.name}" else "ספר, $title"
    }

    /** The folder emoji, as the library draws it. */
    private const val FOLDER_MARK = "📁  "

    /**
     * One listing of a folder: what state it is in, and what it holds.
     *
     * The state and the contents come from ONE listing of the disk, because listing
     * it is the expensive part and asking twice is asking twice.
     */
    data class Listing(val state: State, val entries: List<Entry>)

    /**
     * List [dir]: the folders and books it holds.
     *
     * Folders come first, then the books, each by name — the order a reader looks
     * for a sefer in. A file that is not a `.txt` is not a book and is not shown.
     */
    fun listing(dir: File): Listing {
        if (!dir.isDirectory) return Listing(State.MISSING, emptyList())
        val files = dir.listFiles() ?: return Listing(State.UNREADABLE, emptyList())
        val entries = files
            .filter { it.isDirectory || it.name.endsWith(".txt", ignoreCase = true) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            .map { Entry(it, it.isDirectory) }
        return Listing(State.READY, entries)
    }

    /**
     * Reads a folder off the calling thread and hands the listing to the screen.
     *
     * [list] runs on a worker thread and [post] decides where the listing is
     * delivered; the Activity passes `runOnUiThread`, so the screen is only ever
     * touched from the UI thread.
     */
    class Loader(
        private val list: (File) -> Listing,
        private val post: ((() -> Unit) -> Unit)
    ) {
        /** List [dir] in the background and deliver the listing. */
        fun load(dir: File, deliver: (Listing) -> Unit) {
            Background({ list(dir) }, post).run(deliver)
        }
    }
}
