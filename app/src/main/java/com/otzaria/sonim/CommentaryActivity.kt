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

        val selected = Settings.selectedCommentators(this, book)
        val mefs = Otzaria.meforshimFor(book, line, selected)
        header.text = if (mefs.isEmpty()) "אין מפרשים" else "מפרשים ($book)"

        val rows: List<CharSequence> = mefs.map { m ->
            SpannableStringBuilder()
                .append(Ui.html("<b>" + m.ref + "</b>"))
                .append("\n")
                .append(Ui.html(m.content))
        }
        adapter = RowAdapter(this, rows, Settings.fontSize(this))
        list.adapter = adapter
        list.requestFocus()
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
