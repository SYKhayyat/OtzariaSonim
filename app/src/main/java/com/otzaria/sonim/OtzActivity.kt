package com.otzaria.sonim

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast

/**
 * The base every screen in this app extends, and it exists for one key: MENU.
 *
 * הערות is a second reading position — the pasuk or mefaresh you want to come back
 * to while you keep reading somewhere else — and a second reading position that can
 * only be reached from *one* screen is not one. So a tap on MENU opens it from
 * everywhere, and what MENU used to do on a given screen moves to holding the key
 * down (the split lives in [MenuKey], which is unit-tested):
 *
 * | screen | tap MENU | hold MENU |
 * |---|---|---|
 * | library | הערות | change the library folder |
 * | reader | הערות | the per-book commentator picker |
 * | meforshim panel | הערות | — |
 * | תוכן (TOC) | הערות | — |
 * | picker, help | הערות | — |
 * | הערות itself | its little menu (the orders, and the way out) | close it |
 *
 * This is a base class rather than a helper each screen calls because the contract
 * is then structural: a new screen inherits the tap by being a screen, and cannot
 * forget it. Holding with nothing behind it says so out loud instead of leaving the
 * key silent, because a key that does nothing when held is a key the reader stops
 * trying.
 */
abstract class OtzActivity : Activity() {

    private val menu = MenuKey(
        now = { SystemClock.elapsedRealtime() },
        onTap = { onMenuTap() },
        onHold = { onMenuHold() }
    )

    /** Where a tap on MENU goes. Only הערות itself has a reason to override this. */
    protected open fun onMenuTap() {
        openNotes()
    }

    /** The action this screen used to put on MENU, run when the key is held. */
    protected open fun onMenuHold() {
        Toast.makeText(this, "אין פעולה נוספת במסך זה", Toast.LENGTH_SHORT).show()
    }

    /**
     * Open הערות. Reordered to the front when it is already in the task: a reader who
     * taps MENU from the meforshim panel wants the list they already have, not a
     * second copy of it stacked on top of a third.
     */
    protected fun openNotes() {
        startActivity(
            Intent(this, NotesActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    // The key reaches this class last: a screen's own onKeyDown runs first and calls
    // super, so anything it does not claim lands here.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        menu.down(keyCode, event?.repeatCount ?: 0) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        menu.up(keyCode) || super.onKeyUp(keyCode, event)

    /** A press in flight belongs to this screen's focus; losing it drops the press. */
    override fun onPause() {
        super.onPause()
        menu.cancel()
    }
}
