package com.otzaria.sonim

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
import android.widget.Toast

/**
 * Show every meforsih attached to one segment: reference (bold) + resolved text.
 * D-pad up/down scrolls; volume up/down changes font size; back returns to the reader;
 * MENU is the stack's key here too, like everywhere else ([OtzActivity]).
 *
 * Each mefaresh line is also a place you can push onto הערות (D-pad Right): the
 * reference pushed is that mefaresh's OWN line in its own sefer, so opening it later
 * shows the meforshim on the commentary — no text is copied, and the link the reader
 * finds is where the words actually live.
 */
class CommentaryActivity : OtzActivity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var adapter: RowAdapter? = null

    /** Row N is either a section label (null) or the mefaresh it shows. */
    private val items = ArrayList<Otzaria.Meforish?>()

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
        header.text = when {
            mefs.isEmpty() -> "אין מפרשים"
            else -> "מפרשים ($book)\nימין = הערה · MENU הערות"
        }

        // [Otzaria.meforshimFor] returns מפרשים, then the base text, then
        // cross-references. A heading goes in wherever that changes — without one,
        // a reader of משנה ברורה sees the Shulchan Arukh se'if under the word
        // "מפרשים" and is being told something untrue about it.
        val rows = ArrayList<CharSequence>(mefs.size + 2)
        var lastKind = -1
        for (m in mefs) {
            if (m.kind != lastKind) {
                lastKind = m.kind
                sectionTitle(m.kind)?.let {
                    rows.add(Ui.html("<b>— $it —</b>"))
                    items.add(null)
                }
            }
            rows.add(
                SpannableStringBuilder()
                    .append(Ui.html("<b>" + m.ref + "</b>"))
                    .append("\n")
                    .append(Ui.html(m.content))
            )
            items.add(m)
        }
        adapter = RowAdapter(
            this, rows, Settings.fontSize(this),
            // Section labels are not choices: without this the D-pad stops on them and
            // this phone has no touchscreen to skip past with (BUILDER.md S3).
            enabled = { i -> items.getOrNull(i) != null }
        )
        list.adapter = adapter
        list.requestFocus()
        val first = items.indexOfFirst { it != null }
        if (first >= 0) list.setSelection(first)
    }

    /** null for מפרשים — the header already says that, and the common case is a
     * screen of nothing else, which should look exactly as it always did. */
    private fun sectionTitle(kind: Int): String? = when (kind) {
        Otzaria.KIND_BASE -> "מקור"
        Otzaria.KIND_RELATED -> "קישורים נוספים"
        else -> null
    }

    private fun selected(): Otzaria.Meforish? =
        items.getOrNull(list.selectedItemPosition)

    /** Push this mefaresh's own line onto הערות, as a pointer into its own sefer. */
    private fun pushNote() {
        val m = selected() ?: return
        val after = Notes.add(Notes.load(this), m.path, m.line, m.ref)
        Notes.save(this, after)
        Toast.makeText(this, "נוסף להערות (${after.size})", Toast.LENGTH_SHORT).show()
    }

    /** Left here removes every copy — the list itself is where a single copy is dropped. */
    private fun removeNote() {
        val m = selected() ?: return
        val before = Notes.load(this)
        if (Notes.countOf(before, m.path, m.line) == 0) {
            Toast.makeText(this, "לא בהערות", Toast.LENGTH_SHORT).show()
            return
        }
        val after = Notes.removeAll(before, m.path, m.line)
        Notes.save(this, after)
        Toast.makeText(this, "הוסר מהערות (${after.size})", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { pushNote(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { removeNote(); return true }
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
