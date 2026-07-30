package com.otzaria.sonim

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Per-book commentator filter (like Otzaria's commentatorsToShow). Lists every
 * commentator that appears in this book; D-pad center toggles ☑/☐. Back saves the
 * choice (per book) and returns to the reader, which re-renders with the new filter.
 */
class CommentatorsActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var book = ""
    private var available: List<String> = emptyList()
    private val selected = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)
        book = intent.getStringExtra("book") ?: ""

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 15f
            setPadding(24, 14, 24, 14)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#33691E"))
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

        available = Otzaria.availableCommentators(book)
        // default: everything checked when the user has never chosen for this book
        val saved = Settings.selectedCommentators(this, book) ?: available.toSet()
        selected.addAll(saved.filter { available.contains(it) })

        header.text = if (available.isEmpty()) "אין מפרשים בספר זה" else "בחר מפרשים"

        render()
        list.setOnItemClickListener { _, _, pos, _ ->
            val name = available[pos]
            if (!selected.remove(name)) selected.add(name)
            render(pos)
        }
        list.requestFocus()
    }

    private fun render(keepPos: Int = 0) {
        val rows: List<CharSequence> = available.map { name ->
            (if (selected.contains(name)) "☑  " else "☐  ") + name
        }
        list.adapter = RowAdapter(this, rows, Settings.fontSize(this))
        if (available.isNotEmpty()) list.setSelection(keepPos.coerceIn(0, available.size - 1))
    }

    override fun onBackPressed() {
        Settings.setSelectedCommentators(this, book, selected)
        setResult(RESULT_OK)
        super.onBackPressed()
    }
}
