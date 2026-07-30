package com.otzaria.sonim

import android.app.Activity
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
class HelpActivity : Activity() {

    private lateinit var list: ListView

    // Kept in sync with the reader key map (see README "controls").
    private val sections = listOf(
        "<h1>Otzaria for Sonim — Help</h1>",
        "<h2>Reading</h2>Up / Down — move through the segments.<br>" +
            "A <b>&#9670;</b> at the start of a line = meforshim are available on that segment.<br>" +
            "<b>OK</b> (center) on a &#9670; line — opens all your chosen meforshim, stacked.",
        "<h2>Choosing which meforshim appear</h2><b>#</b> (or MENU) — the per-book commentator " +
            "list, each with a checkbox.<br>OK toggles, Back saves. Saved separately per book.",
        "<h2>Getting around a big sefer</h2><b>Type a number</b> (0&#8211;100) to enter a percent " +
            "of the book — it shows live in the header. <b>OK</b> jumps there, <b>Back</b> cancels " +
            "(stays put). 0 = the very start, 100 = the end.<br>" +
            "<b>*</b> — chapter table of contents (perek / siman). Inside it, type a number to jump " +
            "to the Nth chapter.<br>Each book reopens where you left off.",
        "<h2>Font size</h2>Volume Up / Down — bigger / smaller. Remembered.",
        "<h2>In the library</h2>Up / Down — move, <b>OK</b> — open, <b>Back</b> — up one folder.<br>" +
            "<b>MENU</b> — change the library folder path. <b>#</b> — this help."
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
        list = ListView(this)
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
