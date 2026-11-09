package com.otzaria.sonim

/**
 * MENU, split into the two keys it now has: a tap and a hold.
 *
 * The stack of הערות is a second reading position, and it is only worth having if it
 * is one keypress away from wherever you are — so a tap always opens it. What MENU
 * used to do on a given screen (change the library folder, open the commentator
 * picker) moves to holding the key down, which costs nothing and collides with
 * nothing.
 *
 * Decided on RELEASE, by how long the key was down, rather than by
 * `onKeyLongPress`: the framework only delivers a long press if tracking was
 * started on the down event, the phone is not here to try it on, and a duration is
 * trivial to test without a device — which is the whole point of this class being
 * separate from the Activity.
 *
 * `KEYCODE_MENU` is spelled out as 82 so this file does not depend on the Android
 * framework at all: the JVM unit tests run against a stubbed `android.jar`, and a
 * constant that is inlined anyway is one more thing that can drift silently.
 */
class MenuKey(
    private val now: () -> Long,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit
) {
    companion object {
        /** The MENU key code. */
        const val KEYCODE = 82

        /**
         * How long the key must stay down to count as a hold. Long enough that a
         * deliberate press is not read as a hold, short enough that a hold does not
         * feel like the key stopped working. Android's own long-press timeout is
         * 500 ms; a hair under it means the gesture is reachable either way.
         */
        const val HOLD_MS = 400L
    }

    /** When the current press began, or -1 when no press is in flight. */
    private var downAt = -1L

    /** The hold action has already run for the press in flight. */
    private var held = false

    /**
     * Consume the down event (and its auto-repeats). True if this was MENU, so the
     * activity never passes it on to the framework's options menu.
     */
    fun down(keyCode: Int, repeatCount: Int = 0): Boolean {
        if (keyCode != KEYCODE) return false
        // A fresh press always starts the clock — even if the release of the previous
        // one never arrived, because a lost "up" must not leave MENU dead. Repeats
        // (repeatCount > 0) keep the clock they started, or a hold would keep
        // restarting and never come due.
        if (downAt < 0 || repeatCount == 0) {
            downAt = now()
            held = false
            return true
        }
        // Auto-repeats prove the key is still down, so the hold can fire the moment it
        // is due instead of waiting for a release. Worth having: whether this keypad
        // reports MENU *up* at all is not something that can be checked from here, and
        // a hold that quietly never happens is worse than one that happens early.
        if (!held && now() - downAt >= HOLD_MS) {
            held = true
            onHold()
        }
        return true
    }

    /** Act on the release; true if this was MENU (and therefore already handled). */
    fun up(keyCode: Int): Boolean {
        if (keyCode != KEYCODE) return false
        // A release with no press before it means the press belonged to another
        // screen (the key was already down when this one came forward). Acting on it
        // would open הערות out of nowhere.
        if (downAt < 0) return true
        val pressed = now() - downAt
        val alreadyHeld = held
        downAt = -1L
        held = false
        if (alreadyHeld) return true          // the hold ran during the press
        if (pressed >= HOLD_MS) onHold() else onTap()
        return true
    }

    /** Drop a press in flight — called when the screen goes away mid-press. */
    fun cancel() {
        downAt = -1L
    }
}
