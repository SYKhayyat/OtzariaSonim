package com.otzaria.sonim

import android.view.KeyEvent

/**
 * The number a reader has typed ahead of an action — the pending percent in the
 * reader, the chapter number in the TOC, and (new) the count for Up/Down, which is
 * the numeric argument Emacs calls Ctrl-U.
 *
 * Keeping this out of the Activities is what makes "you can erase a digit" a
 * property of the app rather than of whichever screen remembered to implement it:
 * every screen that accepts digits gets [backspace] from the same place, and the
 * behaviour is pinned by tests that need no device.
 */
class TypeIn(private val cap: Int = 999) {

    private val digits = StringBuilder()

    val isEmpty: Boolean get() = digits.isEmpty()

    /** The number as typed; 0 while nothing is typed. */
    val value: Int get() = if (digits.isEmpty()) 0 else digits.toString().toInt()

    /**
     * Append a digit. A number that would pass [cap] stays at [cap]: typing 200 into
     * a percent field means "as far as it goes", not "stop listening" — which is also
     * how the reader already behaved at 100.
     */
    fun append(d: Int): Int {
        if (d !in 0..9) return value
        val next = (value * 10 + d)
        val shown = if (next > cap) cap else next
        digits.setLength(0)
        digits.append(shown)
        return shown
    }

    /** Erase the last digit. False when there was nothing to erase. */
    fun backspace(): Boolean {
        if (digits.isEmpty()) return false
        digits.setLength(digits.length - 1)
        return true
    }

    fun clear() {
        digits.setLength(0)
    }

    override fun toString(): String = digits.toString()
}

/**
 * The arithmetic of "go there", kept out of the Activities so the edges (first row,
 * last row, an empty list, a 0 count) are decided once and tested.
 */
object Jump {

    /** Where percent [pct] (0..100) lands in a [size]-row list: 0 = first, 100 = last. */
    fun percent(pct: Int, size: Int): Int {
        if (size <= 0) return 0
        val p = pct.coerceIn(0, 100)
        // Long maths: a 26,776-line sefer times 100 still fits, but the reader is not
        // the place to find out that it didn't.
        return ((p.toLong() * (size - 1)) / 100).toInt().coerceIn(0, size - 1)
    }

    /**
     * [delta] rows away from [from] — negative is up. Never wraps and never leaves the
     * list: a count past the end stops at the end, which is what a reader typing 500
     * near the end of a sefer means.
     */
    fun by(from: Int, delta: Int, size: Int): Int {
        if (size <= 0) return 0
        return (from + delta).coerceIn(0, size - 1)
    }
}

/**
 * What a key means while a number is typed ahead of an action.
 *
 * This is a separate table because getting it wrong is invisible: Back used to clear
 * the pending number and then fall through to the framework, which took the *same*
 * key press as "leave the book" — so a reader who typed 12 and changed their mind was
 * thrown out of the sefer. [CANCEL] exists so a caller can return on it and never
 * forward the key. The table is pure, so that behaviour is pinned by tests rather
 * than by remembering to try it on the handset.
 */
enum class TypedAction {
    /** 0-9 — another digit. */
    DIGIT,
    /** D-pad Left or DEL — take back the last digit. */
    ERASE,
    /** Up — walk up by the typed count. */
    UP,
    /** Down — walk down by the typed count. */
    DOWN,
    /** Back — drop the whole number and stay put; never also leaving the screen. */
    CANCEL,
    /**
     * OK — the list's own click handler reads the number (that is what commits a
     * percent), so this key must be forwarded *without* dropping it.
     */
    CONFIRM,
    /** Anything else — drop the number and then do that key's own job. */
    PASS
}

object TypedKeys {
    fun of(keyCode: Int): TypedAction = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DEL -> TypedAction.ERASE
        KeyEvent.KEYCODE_DPAD_UP -> TypedAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TypedAction.DOWN
        KeyEvent.KEYCODE_BACK -> TypedAction.CANCEL
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> TypedAction.CONFIRM
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9 -> TypedAction.DIGIT
        else -> TypedAction.PASS
    }
}
