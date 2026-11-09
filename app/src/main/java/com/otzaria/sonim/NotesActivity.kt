package com.otzaria.sonim

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * הערות — the list of saved references.
 *
 * This screen holds no text of its own: every row is drawn from a pointer
 * ([Notes.Entry]) by reading the line it names out of the library. So a row says what
 * the sefer says now, and nothing here can drift from the book it points into.
 *
 * Keys:
 *  - Up / Down     move the highlight; the header says where that item is from,
 *  - OK            open the meforshim on that reference, exactly as everywhere else,
 *  - D-pad Right   push the highlighted reference again (a second copy — this is a
 *                  stack, not a set),
 *  - D-pad Left    drop the one copy under the highlight,
 *  - `*`           cycle the order: newest / oldest / grouped by sefer,
 *  - Back          return to where you came from,
 *  - MENU (hold)   also return — tapping MENU is what OPENS this screen everywhere
 *                  else, so here it only says you are already looking at it,
 *  - Volume ±      font size.
 */
class NotesActivity : OtzActivity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var adapter: NotesAdapter? = null

    private var entries: List<Notes.Entry> = emptyList()
    private var rows: List<Notes.Row> = emptyList()
    private var order = Notes.Order.NEWEST

    /** seq -> the raw line of that reference, read from the library when the screen opens. */
    private val previews = HashMap<Long, String>()
    private var previewToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 15f
            setPadding(24, 14, 24, 14)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4A148C"))
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        list = Ui.list(this, "הערות")
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        list.setOnItemClickListener { _, _, pos, _ ->
            val e = itemAt(pos) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, CommentaryActivity::class.java)
                    .putExtra("book", e.book)
                    .putExtra("line", e.line)
            )
        }
        // The header's second line follows the highlight, and the D-pad does not fire
        // onScroll while it moves inside the visible rows — so this must be a
        // selection listener, not a scroll one.
        list.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                updateHeader()

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        })

        reload()
    }

    private fun itemAt(pos: Int): Notes.Entry? = (rows.getOrNull(pos) as? Notes.Row.Item)?.entry

    /** Re-read the store and rebuild the list, keeping the highlight on [keepSeq]. */
    private fun reload(keepSeq: Long? = null, fallbackIndex: Int = 0) {
        entries = Notes.load(this)
        rows = Notes.layout(entries, order)
        val idx = keepSeq?.let { Notes.indexOf(rows, it) } ?: -1
        showRows(if (idx >= 0) idx else fallbackIndex)
        updateHeader()
        resolvePreviews()
    }

    /** Rebuild the adapter rows only — the entries and their order stay as they are. */
    private fun showRows(select: Int) {
        val a = adapter
        if (a != null) {
            a.setRows(rows)
        } else {
            adapter = NotesAdapter(this, rows, previews, Settings.fontSize(this))
            list.adapter = adapter
        }
        list.requestFocus()
        if (!entries.isEmpty()) list.setSelection(select.coerceIn(0, maxOf(0, rows.size - 1)))
    }

    /**
     * Read the segment of every reference in the background, so the rows carry a
     * live preview without the list ever owning a copy of anyone's text.
     *
     * The text is kept RAW — with its markup — because the row is drawn the way the
     * reader draws it. The plain words are made from it when the row is spoken.
     */
    private fun resolvePreviews() {
        val todo = entries.filter { !previews.containsKey(it.seq) }
        if (todo.isEmpty()) return
        val token = ++previewToken
        Thread {
            val got = HashMap<Long, String>()
            for (e in todo) {
                val text = Otzaria.lineAt(e.path, e.line) ?: continue
                got[e.seq] = text
            }
            if (got.isEmpty()) return@Thread
            runOnUiThread {
                if (isFinishing || token != previewToken) return@runOnUiThread
                previews.putAll(got)
                val keep = list.selectedItemPosition
                showRows(keep)
                updateHeader()
            }
        }.start()
    }

    private fun updateHeader() {
        if (entries.isEmpty()) {
            header.text = "הערות · 0"
            return
        }
        val item = itemAt(list.selectedItemPosition)
        val where = item?.let { "\nמ: ${it.where}" } ?: ""
        header.text = "הערות · ${entries.size} · ${order.title}$where"
    }

    override fun onResume() {
        super.onResume()
        // A push or a removal may have happened on the meforshim screen, which sits
        // above this one. Only re-render when the store really changed, so returning
        // to this screen does not move the highlight.
        if (Notes.load(this) != entries) reload(keepSeq = itemAt(list.selectedItemPosition)?.seq)
    }

    private fun pushCopy() {
        val e = itemAt(list.selectedItemPosition) ?: return
        val after = Notes.add(entries, e.path, e.line, e.where)
        Notes.save(this, after)
        reload(keepSeq = after.last().seq)
        Toast.makeText(this, "נוסף להערות (${after.size})", Toast.LENGTH_SHORT).show()
    }

    private fun removeSelected() {
        val e = itemAt(list.selectedItemPosition) ?: return
        val at = list.selectedItemPosition
        val after = Notes.removeOne(entries, e.seq)
        Notes.save(this, after)
        reload(fallbackIndex = at)
        Toast.makeText(this, "הוסר מהערות (${after.size})", Toast.LENGTH_SHORT).show()
    }

    /**
     * Tap MENU here and nothing should happen — you are already where the key goes.
     * Saying so is better than a key that seems dead, and better still than closing
     * the list, because tapping MENU elsewhere opens it.
     */
    override fun onMenuTap() {
        Toast.makeText(this, "כבר בהערות · החזקה = יציאה", Toast.LENGTH_SHORT).show()
    }

    /** Held MENU: the close that MENU used to do from inside this screen. */
    override fun onMenuHold() {
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { pushCopy(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { removeSelected(); return true }
            KeyEvent.KEYCODE_STAR -> {
                order = order.next()
                reload(keepSeq = itemAt(list.selectedItemPosition)?.seq)
                return true
            }
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
