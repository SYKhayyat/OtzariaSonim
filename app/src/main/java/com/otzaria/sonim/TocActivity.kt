package com.otzaria.sonim

import android.app.Activity
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
 */
class TocActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var headings: List<Otzaria.Heading> = emptyList()

    // Primary level = the most common heading level (perek in Mishnah, siman in Er.HaShulchan).
    // Typing a number jumps to the Nth heading of that level.
    private var primaryRowByOrdinal: List<Int> = emptyList()   // ordinal-1 -> row index in `headings`
    private var typed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        headings = Otzaria.headings(path)
        if (headings.isEmpty()) {
            Toast.makeText(this, "אין חלוקה לפרקים", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val minLevel = headings.minOf { it.level }
        val levelCounts = headings.groupingBy { it.level }.eachCount()
        val primaryLevel = levelCounts.maxByOrNull { it.value }!!.key
        primaryRowByOrdinal = headings.indices.filter { headings[it].level == primaryLevel }

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
        showHint()

        val rows: List<CharSequence> = headings.map { h ->
            " ".repeat(h.level - minLevel) + h.text   // em-space indent per heading depth
        }
        list.adapter = RowAdapter(this, rows, Settings.fontSize(this))
        list.setOnItemClickListener { _, _, pos, _ -> jumpTo(headings[pos].pos) }
        list.requestFocus()
    }

    private fun showHint() {
        header.text = if (typed > 0) "← $typed" else "תוכן — הקש מספר לקפיצה"
    }

    private fun jumpTo(bookPos: Int) {
        setResult(RESULT_OK, Intent().putExtra("pos", bookPos))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val digit = digitOf(keyCode)
        if (digit >= 0) {
            typed = (typed * 10 + digit).coerceAtMost(999999)
            val idx = typed - 1
            if (idx in primaryRowByOrdinal.indices) list.setSelection(primaryRowByOrdinal[idx])
            showHint()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_STAR -> { typed = 0; showHint(); return true }
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
