package com.otzaria.sonim

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * On-device controls cheat-sheet, reached with `#` from the library. The text is baked
 * into the APK (no file on the phone), rendered through the same minimal <h1>/<b> HTML
 * renderer every other screen uses. English + LTR (unlike the Hebrew reader screens), so
 * it gets its own left-aligned adapter rather than the RTL [RowAdapter]. One section per row.
 */
class HelpActivity : OtzActivity() {

    private lateinit var list: ListView

    // Kept in sync with the reader key map (see README "controls").
    private val sections = listOf(
        "<h1>Otzaria for Sonim — Help</h1>",
        "<h2>Reading</h2>Up / Down — move through the segments.<br>" +
            "A <b>&#9670;</b> at the start of a line = meforshim are available on that segment.<br>" +
            "<b>OK</b> (center) on a &#9670; line — opens all your chosen meforshim, stacked.",
        "<h2>Choosing which meforshim appear</h2><b>#</b> — the per-book commentator " +
            "list, each with a checkbox.<br>OK toggles, Back saves. Saved separately per book.",
        "<h2>הערות &#8212; the reference stack</h2>" +
            "<b>D-pad Right</b> on a segment pushes a REFERENCE to it (a sefer + line, never a " +
            "copy of the text). It works on a line inside meforshim too &#8212; that pushes the " +
            "commentary's own line. Pushing the same thing again adds a second entry.<br>" +
            "<b>D-pad Left</b> removes it: one copy inside <b>הערות</b>, every copy everywhere " +
            "else.<br><b>MENU &#8212; tap it and you are in הערות</b>, from every screen there " +
            "is: the library, the reader, the meforshim panel, the contents, the picker, this " +
            "page.<br><b>Hold MENU</b> (about half a second) and you get what MENU used to do " +
            "on that screen instead: the library folder path, the commentator picker in the " +
            "reader, and closing the list from inside <b>הערות</b>.<br>" +
            "Up/Down moves inside the list, <b>OK</b> opens the meforshim on that reference, " +
            "<b>*</b> cycles the order (newest / oldest / grouped by sefer).",
        "<h2>Getting around a big sefer</h2><b>Type a number</b> (0&#8211;100) to enter a percent " +
            "of the book &#8212; it shows live in the header. <b>OK</b> jumps there, <b>Back</b> " +
            "cancels (stays put). 0 = the very start, 100 = the end.<br>" +
            "<b>Type a number, then Up or Down</b> &#8212; moves that many segments at once, the " +
            "way Ctrl-U works in Emacs. It stops at either end of the sefer rather than wrapping. " +
            "The header shows the number and both of its meanings while it is typed.<br>" +
            "<b>D-pad Left</b> erases the last digit typed, so a mistyped number is fixed where it " +
            "stands instead of retyped.<br>" +
            "<b>*</b> &#8212; chapter table of contents (perek / siman). Inside it, type a number to " +
            "jump to the Nth chapter; Up/Down then walk that many headings, D-pad Left erases a " +
            "digit and <b>#</b> clears the number.<br>Each book reopens where you left off.",
        "<h2>Font size</h2>Volume Up / Down — bigger / smaller. Remembered.",
        "<h2>In the library</h2>Up / Down &#8212; move, <b>OK</b> &#8212; open, <b>Back</b> " +
            "&#8212; up one folder.<br><b>Hold MENU</b> &#8212; change the library folder path " +
            "(a tap opens <b>הערות</b>, as everywhere). <b>#</b> &#8212; this help."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = TextView(this).apply {
            textSize = 16f
            setPadding(24, 16, 24, 16)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#33691E"))
            gravity = Gravity.LEFT
            textDirection = View.TEXT_DIRECTION_LTR
            text = "Help"
        }
        list = Ui.list(this, "Controls help")
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        list.adapter = LtrHelpAdapter(this, sections.map { Ui.html(it) }, Settings.fontSize(this))
        list.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val a = list.adapter as? LtrHelpAdapter ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { a.bump(+2f); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { a.bump(-2f); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Left-aligned, LTR variant of [RowAdapter] for the English help text. */
private class LtrHelpAdapter(
    private val ctx: Context,
    rows: List<CharSequence>,
    var sizeSp: Float
) : ArrayAdapter<CharSequence>(ctx, 0, rows) {

    fun bump(delta: Float) {
        sizeSp = (sizeSp + delta).coerceIn(12f, 40f)
        Settings.setFontSize(ctx, sizeSp)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tv = (convertView as? TextView) ?: TextView(context).apply {
            setPadding(28, 22, 28, 22)
            setTextColor(Color.BLACK)
            gravity = Gravity.LEFT
            textDirection = View.TEXT_DIRECTION_LTR
            setLineSpacing(0f, 1.15f)
        }
        tv.textSize = sizeSp
        tv.text = getItem(position)
        return tv
    }
}
