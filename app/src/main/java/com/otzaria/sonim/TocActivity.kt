package com.otzaria.sonim

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Chapter table of contents for one book — the "virtual split". Parses the book's
 * <h1..6> headings into an indented, jump-able list. Picking one (or typing its number)
 * returns the heading's list position to the reader, which scrolls there. The book file
 * itself is never touched, so meforshim line indices are unaffected.
 *
 * The typed number does both jobs the reader's does: it picks the Nth chapter as it is
 * typed, and Up/Down walk that many rows (Ctrl-U with a count). D-pad Left erases a
 * digit; `#`/`*` clear the whole number, as before.
 */
class TocActivity : OtzActivity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var headings: List<Otzaria.Heading> = emptyList()

    // Primary level = the most common heading level (perek in Mishnah, siman in Er.HaShulchan).
    // Typing a number jumps to the Nth heading of that level.
    private var primaryRowByOrdinal: List<Int> = emptyList()   // ordinal-1 -> row index in `headings`

    /** The number typed ahead of the jump — Nth chapter, or a count for Up/Down. */
    private val typed = TypeIn(cap = 999999)

    /** True while the book is still being read; the header says so until it lands. */
    private var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)

        val path = intent.getStringExtra("path") ?: run { finish(); return }

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 15f
            setPadding(24, 14, 24, 14)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4E342E"))
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        list = Ui.list(this, "תוכן העניינים")
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        // The ListView eats Up/Down (it scrolls on them), so the typed count is taken
        // here; without this, typing 20 and pressing Down would move one row.
        list.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN && handleTypedKey(keyCode)
        }

        load(path)
    }

    /**
     * Read the book's headings and build the TOC — off the main thread.
     *
     * [Otzaria.headings] parses every line of the sefer: 31 MB and 26,776 lines for
     * ערוך השולחן, which is seconds of work. On the main thread that is an ANR
     * waiting to happen — the screen freezes and the system offers to close the app
     * — and the reader, which already learned this and loads its book in the
     * background, would have been the model to copy.
     *
     * The list stays empty and the header says "טוען…" until the work lands.
     */
    private fun load(path: String) {
        header.text = "תוכן — טוען…"
        Toc.Loader(
            read = { Toc.model(Otzaria.headings(it)) },
            post = { action -> runOnUiThread { action() } }
        ).load(path) { model ->
            if (isFinishing) return@load
            if (model.headings.isEmpty()) {
                Toast.makeText(this, "אין חלוקה לפרקים", Toast.LENGTH_SHORT).show()
                finish(); return@load
            }
            headings = model.headings
            primaryRowByOrdinal = model.primaryRowByOrdinal
            list.adapter = RowAdapter(this, model.rows, Settings.fontSize(this))
            list.setOnItemClickListener { _, _, pos, _ -> jumpTo(headings[pos].pos) }
            list.requestFocus()
            loading = false
            showHint()
        }
    }

    /**
     * A press that belongs to the pending number — see the reader's copy for why this
     * is called from the list's key listener as well as from `onKeyDown`.
     */
    private fun handleTypedKey(keyCode: Int): Boolean {
        if (typed.isEmpty) return false
        when (TypedKeys.of(keyCode)) {
            TypedAction.ERASE -> { eraseDigit(); return true }
            TypedAction.UP -> { countedMove(-1); return true }
            TypedAction.DOWN -> { countedMove(+1); return true }
            // Back clears the number first; a second Back leaves the TOC.
            TypedAction.CANCEL -> { cancelTyped(); return true }
            TypedAction.DIGIT -> return false
            // OK is the list's click — it reads the number itself, so it is forwarded
            // untouched rather than dropping the number first.
            TypedAction.CONFIRM -> return false
            TypedAction.PASS -> cancelTyped()
        }
        return false
    }

    private fun cancelTyped() {
        typed.clear()
        showHint()
    }

    private fun showHint() {
        // While the book is being read there is nothing to hint about yet, and the
        // header saying "טוען…" is the honest thing to say.
        if (loading) return
        val n = typed.value
        header.text = if (typed.isEmpty) "תוכן — הקש מספר לקפיצה"
        else "$n · אישור = פרק $n\nמעלה/מטה = $n שורות · שמאל = מחיקה · # = ניקוי"
    }

    /** Put the highlight on the Nth primary heading, when there is one. */
    private fun jumpToOrdinal() {
        val idx = typed.value - 1
        if (idx in primaryRowByOrdinal.indices) list.setSelection(primaryRowByOrdinal[idx])
    }

    /**
     * Erase the last digit. The highlight follows the number back down to the chapter
     * it now names, and erasing the last digit leaves it where it is.
     */
    private fun eraseDigit() {
        if (!typed.backspace()) return
        if (!typed.isEmpty) jumpToOrdinal()
        showHint()
    }

    /**
     * Walk [direction] (+1 down, -1 up) as many rows as have been typed. The count is
     * always spent, even at 0, so a typed digit never leaves Up/Down dead.
     */
    private fun countedMove(direction: Int) {
        val count = typed.value
        typed.clear()
        if (count <= 0 || headings.isEmpty()) { showHint(); return }
        val from = list.selectedItemPosition.coerceAtLeast(0)
        val to = Jump.by(from, direction * count, headings.size)
        list.setSelection(to)
        showHint()
    }

    private fun jumpTo(bookPos: Int) {
        setResult(RESULT_OK, Intent().putExtra("pos", bookPos))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val digit = digitOf(keyCode)
        if (digit >= 0) {
            typed.append(digit)
            jumpToOrdinal()
            showHint()
            return true
        }
        if (handleTypedKey(keyCode)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_STAR -> {
                typed.clear(); showHint(); return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> { bumpFont(+2f); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { bumpFont(-2f); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun digitOf(keyCode: Int): Int =
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) keyCode - KeyEvent.KEYCODE_0 else -1

    private fun bumpFont(delta: Float) {
        val a = list.adapter as? RowAdapter ?: return
        a.sizeSp = (a.sizeSp + delta).coerceIn(12f, 40f)
        Settings.setFontSize(this, a.sizeSp)
        a.notifyDataSetChanged()
    }
}
