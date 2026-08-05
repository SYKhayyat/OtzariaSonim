package com.otzaria.sonim

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Show every meforish attached to one segment: reference (bold) + resolved text.
 * D-pad up/down scrolls; volume up/down changes font size; back returns to the reader.
 */
class CommentaryActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var adapter: RowAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)

        val book = intent.getStringExtra("book") ?: ""
        val line = intent.getIntExtra("line", -1)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 15f
            setPadding(24, 14, 24, 14)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A237E"))
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        list = Ui.list(this, "מפרשים על הקטע")
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        val mefs = Otzaria.meforshimFor(book, line, Settings.activeSelection(this, book))
        header.text = if (mefs.isEmpty()) "אין מפרשים" else "מפרשים ($book)"

        // [Otzaria.meforshimFor] returns מפרשים, then the base text, then
        // cross-references. A heading goes in wherever that changes — without one,
        // a reader of משנה ברורה sees the Shulchan Arukh se'if under the word
        // "מפרשים" and is being told something untrue about it.
        val rows = ArrayList<CharSequence>(mefs.size + 2)
        var lastKind = -1
        for (m in mefs) {
            if (m.kind != lastKind) {
                lastKind = m.kind
                sectionTitle(m.kind)?.let { rows.add(Ui.html("<b>— $it —</b>")) }
            }
            rows.add(
                SpannableStringBuilder()
                    .append(Ui.html("<b>" + m.ref + "</b>"))
                    .append("\n")
                    .append(Ui.html(m.content))
            )
        }
        adapter = RowAdapter(this, rows, Settings.fontSize(this))
        list.adapter = adapter
        list.requestFocus()
    }

    /** null for מפרשים — the header already says that, and the common case is a
     * screen of nothing else, which should look exactly as it always did. */
    private fun sectionTitle(kind: Int): String? = when (kind) {
        Otzaria.KIND_BASE -> "מקור"
        Otzaria.KIND_RELATED -> "קישורים נוספים"
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { bumpFont(+2f); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { bumpFont(-2f); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun bumpFont(delta: Float) {
        val a = adapter ?: return
        a.sizeSp = (a.sizeSp + delta).coerceIn(12f, 40f)
        Settings.setFontSize(this, a.sizeSp)
        a.notifyDataSetChanged()
    }
}
