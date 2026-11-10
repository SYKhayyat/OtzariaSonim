package com.otzaria.sonim

/**
 * Runs a piece of work off the calling thread and delivers its result on another.
 *
 * WHY THIS IS ITS OWN CLASS. The app has two places that must not do slow work on
 * the main thread — the reader loading a book, and the meforshim panel resolving
 * its links — and each of them needs the same three things: start a thread, do the
 * work there, and hand the result back to the screen's thread. Written inline
 * twice, that is two chances to forget the last step and touch a View from a
 * worker thread.
 *
 * [work] runs on the worker thread. [post] decides where the result is delivered:
 * the Activities pass `runOnUiThread`, so the screen is only ever touched from
 * the UI thread. [deliver] runs inside [post].
 */
class Background<T>(
    private val work: () -> T,
    private val post: ((() -> Unit) -> Unit)
) {

    /** Do the work in the background and deliver the result. */
    fun run(deliver: (T) -> Unit) {
        Thread {
            val result = work()
            post { deliver(result) }
        }.start()
    }
}
