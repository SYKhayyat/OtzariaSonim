package com.otzaria.sonim

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Per-book commentator filter (like Otzaria's commentatorsToShow). D-pad center
 * toggles ☑/☐; Back saves the choice per book and returns to the reader.
 *
 * The list is grouped by what each linked book actually IS to this one. It used to
 * be one flat list of "everything this book's links point at", which is not the
 * same thing at all: opening משנה ברורה offered שולחן ערוך as one of its
 * commentators, and opening שולחן ערוך יורה דעה offered ויקרא. See
 * [Otzaria.KIND_MEFARESH] for why the graph is like that and where the answer
 * comes from.
 */
class CommentatorsActivity : Activity() {

    /** One line of the list: either a group heading or a togglable book. */
    private sealed class Row {
        class Header(val text: String) : Row()
        class Item(val name: String) : Row()
    }

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var book = ""
    private var rows: List<Row> = emptyList()
    private var available: List<Otzaria.Commentator> = emptyList()
    private val selected = HashSet<String>()
    private var fontSp = 20f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)
        book = intent.getStringExtra("book") ?: ""
        fontSp = Settings.fontSize(this)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 15f
            setPadding(24, 14, 24, 14)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#33691E"))
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        list = Ui.list(this, "בחירת מפרשים")
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        available = Otzaria.commentators(book)
        rows = buildRows(available)
        // Never chosen for this book => the מפרשים and the base text, not
        // everything. Chosen before => that choice, brought up to date with any
        // commentator the library has gained since.
        selected.addAll(Settings.reconcileSelection(this, book, available))

        header.text = when {
            available.isEmpty() -> "אין מפרשים בספר זה"
            else -> "בחר מפרשים · ${selected.size}/${available.size}"
        }

        render()
        list.setOnItemClickListener { _, _, pos, _ ->
            val row = rows.getOrNull(pos)
            if (row !is Row.Item) return@setOnItemClickListener   // a heading is not a control
            if (!selected.remove(row.name)) selected.add(row.name)
            header.text = "בחר מפרשים · ${selected.size}/${rows.count { it is Row.Item }}"
            render(pos)
        }
        list.requestFocus()
    }

    /**
     * Headings appear only for groups that have something in them, so a book with
     * nothing but מפרשים looks exactly as it always did — no ceremony added for
     * the common case.
     */
    private fun buildRows(available: List<Otzaria.Commentator>): List<Row> {
        val out = ArrayList<Row>(available.size + 3)
        val groups = listOf(
            Otzaria.KIND_MEFARESH to "מפרשים",
            Otzaria.KIND_BASE to "הספר שעליו זה מפרש",
            Otzaria.KIND_RELATED to "קישורים נוספים"
        )
        for ((kind, title) in groups) {
            val names = available.filter { it.kind == kind }.map { it.name }
            if (names.isEmpty()) continue
            // A single group and nothing else needs no heading to distinguish it.
            if (groups.count { g -> available.any { it.kind == g.first } } > 1) {
                out.add(Row.Header("— $title —"))
            }
            names.forEach { out.add(Row.Item(it)) }
        }
        return out
    }

    private fun render(keepPos: Int = 0) {
        val text: List<CharSequence> = rows.map { row ->
            when (row) {
                is Row.Header -> row.text
                is Row.Item -> (if (selected.contains(row.name)) "☑  " else "☐  ") + row.name
            }
        }
        // ☑ and ☐ are announced as ballot-box glyphs, which is not the same as
        // being told whether this commentator is on.
        list.adapter = RowAdapter(
            this, text, fontSp,
            describe = { i ->
                when (val row = rows[i]) {
                    is Row.Header -> row.text
                    is Row.Item -> Ui.describeChoice(row.name, selected.contains(row.name))
                }
            },
            enabled = { i -> rows[i] is Row.Item }
        )
        if (rows.isNotEmpty()) list.setSelection(keepPos.coerceIn(0, rows.size - 1))
    }

    /** Volume ± resizes here too. The reader has it; this screen not having it was
     * a gap the backlog already named. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> +2f
            KeyEvent.KEYCODE_VOLUME_DOWN -> -2f
            else -> return super.onKeyDown(keyCode, event)
        }
        fontSp = (fontSp + delta).coerceIn(12f, 40f)
        Settings.setFontSize(this, fontSp)
        render(list.selectedItemPosition.coerceAtLeast(0))
        return true
    }

    override fun onBackPressed() {
        // The roster goes in with the choice, so a later repack can tell an
        // unchecked commentator from one that did not exist yet.
        Settings.setSelectedCommentators(this, book, selected, available.map { it.name }.toSet())
        setResult(RESULT_OK)
        super.onBackPressed()
    }
}
