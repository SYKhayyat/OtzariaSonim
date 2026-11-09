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
import java.io.File

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
 *  - `*`           open the SEFER that reference points into, at that line — the
 *                  thing the stack existed for and the thing it could not do,
 *  - D-pad Right   push the highlighted reference again (a second copy — this is a
 *                  stack, not a set),
 *  - D-pad Left    drop the one copy under the highlight,
 *  - MENU (tap)    the little menu: the four orders, and the way back to the seforim,
 *  - Back          return to where you came from,
 *  - MENU (hold)   also return — the other way out of the list,
 *  - Volume ±      font size.
 */
class NotesActivity : OtzActivity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var menuList: ListView
    private var adapter: NotesAdapter? = null

    private var entries: List<Notes.Entry> = emptyList()
    private var rows: List<Notes.Row> = emptyList()
    private var order = Notes.Order.NEWEST

    /** True while the little menu (the four orders, and the way out) is showing. */
    private var menuOpen = false

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

        // The little menu: the four orders and the way out. It is a panel of this
        // screen and not a dialog, because this phone has no touchscreen to tap it
        // with — the D-pad has to walk it, and a dialog would take the focus with it.
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val menuHeader = TextView(this).apply {
            textSize = 15f
            setPadding(24, 14, 24, 14)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#311B92"))
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            text = "תפריט הערות"
        }
        menuList = Ui.list(this, "תפריט הערות")
        menuPanel.addView(
            menuHeader,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        menuPanel.addView(
            menuList,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        rootView.addView(
            menuPanel,
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
        // While the little menu is up, the list must not take the focus back: the
        // D-pad belongs to the menu, and a list that grabs it mid-menu leaves the
        // reader walking a menu they cannot see.
        if (!menuOpen) list.requestFocus()
        if (!entries.isEmpty()) {
            // The highlight has to be on a reference, not on a group heading — the
            // headings are labels, and the D-pad skips them, so a selection that
            // landed on one would be a place where no key does anything.
            val first = rows.indexOfFirst { it is Notes.Row.Item }
            val want = select.coerceIn(0, maxOf(0, rows.size - 1))
            val at = if (rows.getOrNull(want) is Notes.Row.Item) want else first
            if (at >= 0) list.setSelection(at)
        }
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

    /**
     * Open the SEFER this reference points into, at the line it names.
     *
     * A reference pushed from the reader points into the sefer being read; one pushed
     * from the meforshim panel points into that commentary's own sefer, at the line
     * the commentary's text is on. Both are the same thing here: open the file, open
     * it at that line, and let the reader show it.
     *
     * The path a reference stores is relative to the library root (so the library can
     * move house), so it has to be made absolute again before the reader gets it.
     */
    private fun openSefar() {
        val e = itemAt(list.selectedItemPosition) ?: return
        val abs = Otzaria.absolute(e.path)
        if (!File(abs).isFile) {
            Toast.makeText(this, "הספר לא נמצא בספרייה", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra("path", abs)
                .putExtra("atLine", e.line)
        )
    }

    /**
     * The little menu: the four orders, and the way out.
     *
     * It is a panel of this screen rather than a dialog, because this phone has no
     * touchscreen: the D-pad has to walk the menu, and a dialog would take the
     * highlight with it.
     */
    private fun openMenu() {
        val items = Notes.menu()
        val labels = items.map { item ->
            when (item) {
                // The order in force is marked, so the reader can see which one they
                // are looking at without going back to the list to read the header.
                is Notes.MenuItem.ByOrder ->
                    if (item.order == order) "• ${item.label}" else item.label
                Notes.MenuItem.BackToSefarim -> item.label
            }
        }
        menuList.adapter = RowAdapter(this, labels, Settings.fontSize(this))
        menuList.setOnItemClickListener { _, _, pos, _ ->
            when (val item = items.getOrNull(pos)) {
                is Notes.MenuItem.ByOrder -> {
                    order = item.order
                    closeMenu()
                    reload(keepSeq = itemAt(list.selectedItemPosition)?.seq)
                }
                Notes.MenuItem.BackToSefarim -> finish()
                null -> closeMenu()
            }
        }
        menuOpen = true
        menuPanel.visibility = View.VISIBLE
        list.visibility = View.GONE
        menuList.requestFocus()
    }

    /** Put the list back and give the highlight back to it. */
    private fun closeMenu() {
        menuOpen = false
        menuPanel.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.requestFocus()
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
     * Tap MENU here and the little menu opens: the four orders, and the way out.
     *
     * This screen is the one screen MENU used to point nowhere — tapping it said
     * "already in the notes" — and a screen that is a second reading position needs
     * to say where it is and in what order.
     */
    override fun onMenuTap() {
        if (menuOpen) closeMenu() else openMenu()
    }

    /** Held MENU: the close that MENU used to do from inside this screen. */
    override fun onMenuHold() {
        if (menuOpen) closeMenu() else finish()
    }

    override fun onBackPressed() {
        // Back closes the menu first, and only then the screen: the menu is the
        // nearer thing, and a Back that jumped past it would skip a choice.
        if (menuOpen) { closeMenu(); return }
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // While the menu is up, the D-pad belongs to the menu.
        if (menuOpen) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { pushCopy(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { removeSelected(); return true }
            KeyEvent.KEYCODE_STAR -> { openSefar(); return true }
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
