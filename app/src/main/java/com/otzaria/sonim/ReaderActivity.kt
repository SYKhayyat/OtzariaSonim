package com.otzaria.sonim

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Read one book. Each row is a segment (one file line); row N shows file line N+1, so the
 * meforshim link graph (which is keyed by line index) is untouched by anything here.
 *
 * Navigation for big seforim without ever splitting the file on disk:
 *  - lazy rendering (see [ReaderAdapter]) so even a 34 MB book opens instantly,
 *  - `*` opens the chapter TOC ([TocActivity]),
 *  - typing a number (0..100) enters a percent to jump to — it shows live in the header,
 *    OK jumps, Back cancels (and stays put, without leaving the book),
 *  - the last-read position is remembered per book.
 * ◆ marks segments with meforshim; center opens them; # picks commentators; volume ± = font.
 */
class ReaderActivity : Activity() {

    private val REQ_COMMENTATORS = 10
    private val REQ_TOC = 11
    private val BIG_BOOK_LINES = 1500   // above this, open on the chapter TOC first

    private lateinit var list: ListView
    private lateinit var header: TextView
    private var path = ""
    private var bookTitle = ""
    private var lines: List<String> = emptyList()
    private var headings: List<Otzaria.Heading> = emptyList()
    private var commented: Set<Int> = emptySet()
    private var adapter: ReaderAdapter? = null

    // Percent-jump entry: while a number is being typed, pctEntry is true and pctValue holds
    // the running 0..100 value. OK commits, Back cancels (see onBackPressed).
    private var pctEntry = false
    private var pctValue = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)

        path = intent.getStringExtra("path") ?: run { finish(); return }
        bookTitle = Otzaria.titleFromPath(path)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 13f
            setPadding(24, 12, 24, 12)
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

        lines = Otzaria.readLines(path)
        headings = Otzaria.headings(path, lines)

        list.setOnItemClickListener { _, _, pos, _ ->
            if (pctEntry) { commitPct(); return@setOnItemClickListener }
            val lineNo = pos + 1
            if (commented.contains(lineNo)) {
                startActivity(
                    Intent(this, CommentaryActivity::class.java)
                        .putExtra("book", bookTitle)
                        .putExtra("line", lineNo)
                )
            } else {
                Toast.makeText(this, "אין מפרשים נבחרים על קטע זה", Toast.LENGTH_SHORT).show()
            }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                if (!pctEntry) updateHeader(first)   // don't clobber the percent prompt
            }
        })

        render()

        // Reopen where we left off; otherwise big books open on the chapter picker.
        val saved = Settings.lastPosition(this, bookTitle)
        when {
            saved in lines.indices -> list.setSelection(saved)
            lines.size > BIG_BOOK_LINES && headings.isNotEmpty() -> openToc()
        }
    }

    /** (Re)build the segment list using the current per-book commentator filter. */
    private fun render() {
        val selected = Settings.selectedCommentators(this, bookTitle)
        commented = Otzaria.commentedLines(bookTitle, selected)
        val a = adapter
        if (a == null) {
            adapter = ReaderAdapter(this, lines, commented, Settings.fontSize(this))
            list.adapter = adapter
        } else {
            a.refresh(commented)
        }
        list.requestFocus()
        updateHeader(list.firstVisiblePosition)
    }

    /** Breadcrumb: current chapter + position + percent, plus the key hints. */
    private fun updateHeader(first: Int) {
        val n = lines.size
        if (n == 0) { header.text = bookTitle; return }
        val pct = ((first + 1) * 100) / n
        val chapter = chapterAt(first)
        val place = if (chapter.isNotEmpty()) "$bookTitle · $chapter" else bookTitle
        header.text = "$place\n${first + 1}/$n · $pct%   # מפרשים · * תוכן · מספר→אחוז"
    }

    /** Text of the nearest heading at or above list position [pos] (empty if none yet). */
    private fun chapterAt(pos: Int): String {
        if (headings.isEmpty()) return ""
        var lo = 0; var hi = headings.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (headings[mid].pos <= pos) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (found >= 0) headings[found].text else ""
    }

    private fun openToc() {
        startActivityForResult(
            Intent(this, TocActivity::class.java).putExtra("path", path), REQ_TOC
        )
    }

    /** Type a digit into the pending percent (0..100, clamped); shows live in the header. */
    private fun appendPctDigit(d: Int) {
        if (lines.isEmpty()) return
        pctEntry = true
        pctValue = (pctValue * 10 + d).coerceAtMost(100)
        header.text = "קפיצה ל־$pctValue%\nאישור לקפיצה · חזרה לביטול"
    }

    /** Jump to the typed percent: 0 => very start, 100 => end. */
    private fun commitPct() {
        val pct = pctValue
        pctEntry = false; pctValue = 0
        if (lines.isEmpty()) return
        val target = ((pct * (lines.size - 1)) / 100).coerceIn(0, lines.size - 1)
        list.setSelection(target)
        updateHeader(target)
    }

    /** Abandon the pending percent and stay exactly where we are. */
    private fun cancelPct() {
        pctEntry = false; pctValue = 0
        updateHeader(list.firstVisiblePosition)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_COMMENTATORS -> render()
            REQ_TOC -> if (resultCode == RESULT_OK) {
                val pos = data?.getIntExtra("pos", -1) ?: -1
                if (pos in lines.indices) { list.setSelection(pos); updateHeader(pos) }
            }
        }
    }

    /** Back cancels a pending percent (staying put); otherwise it leaves the book. */
    override fun onBackPressed() {
        if (pctEntry) { cancelPct(); return }
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (lines.isNotEmpty()) Settings.setLastPosition(this, bookTitle, list.firstVisiblePosition)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            appendPctDigit(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        // Any other actionable key interrupts a pending percent (Back is handled separately).
        if (pctEntry) cancelPct()
        when (keyCode) {
            // Reachable whatever the Sonim exposes: MENU, left soft-key, and #.
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SOFT_LEFT,
            KeyEvent.KEYCODE_POUND -> {
                startActivityForResult(
                    Intent(this, CommentatorsActivity::class.java).putExtra("book", bookTitle),
                    REQ_COMMENTATORS
                )
                return true
            }
            KeyEvent.KEYCODE_STAR -> { if (headings.isNotEmpty()) openToc(); return true }
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
