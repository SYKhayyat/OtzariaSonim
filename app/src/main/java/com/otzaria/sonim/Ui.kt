package com.otzaria.sonim

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

object Ui {
    /** Render the library's minimal HTML (h1/h2/h3/b) to styled text. */
    fun html(s: String): Spanned =
        if (Build.VERSION.SDK_INT >= 24)
            Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
        else
            @Suppress("DEPRECATION") Html.fromHtml(s)

    // ------------------------------------------------------------ naming things
    //
    // Every screen in this app is one ListView of TextViews, so the rows do carry
    // an accessible name already: their own text. What they do not carry is the
    // meaning of the two glyphs this app leans on. `◆` is announced as a lozenge
    // or skipped entirely, and `☑`/`☐` as ballot boxes — so with TalkBack on, the
    // one thing the reader screen exists to tell you (this pasuk has meforshim) and
    // the one thing the picker screen exists to tell you (this one is on) are the
    // two things it does not say.
    //
    // The lists themselves were nameless too: four screens, four ListViews,
    // announced as "list". [list] is the only way to make one here, and it takes
    // the name as a required argument, so a nameless list cannot be written —
    // which is the part that holds when the next screen is added (BUILDER.md S3).

    const val HAS_MEFORSHIM = "◆"

    /** A ListView that cannot exist without being named. */
    fun list(ctx: Context, name: String): ListView =
        ListView(ctx).apply { contentDescription = name }

    /** What a screen reader should say for a reader row. */
    fun describeRow(text: CharSequence, hasMeforshim: Boolean): String =
        if (hasMeforshim) "יש מפרשים. $text" else text.toString()

    /** What a screen reader should say for one line of the commentator picker. */
    fun describeChoice(name: String, checked: Boolean): String =
        if (checked) "$name, נבחר" else "$name, לא נבחר"
}

/**
 * A ListView adapter of Hebrew rows, rendered RTL at an adjustable sp size.
 * Rows are CharSequence so callers can pass plain strings (menus) or Spanned (text).
 */
class RowAdapter(
    ctx: Context,
    private val rows: List<CharSequence>,
    var sizeSp: Float = 20f,
    /** Spoken form of row N, when it differs from the row's own text. */
    private val describe: ((Int) -> String)? = null,
    /** False for rows that are labels rather than choices. */
    private val enabled: ((Int) -> Boolean)? = null
) : ArrayAdapter<CharSequence>(ctx, 0, rows) {

    // ListView uses these to decide what the D-pad may land on. Without them a
    // group heading is a stop on the way down, and this phone has no touchscreen
    // to skip past it with — every heading would cost the reader a keypress.
    override fun areAllItemsEnabled() = enabled == null
    override fun isEnabled(position: Int) = enabled?.invoke(position) ?: true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tv = (convertView as? TextView) ?: TextView(context).apply {
            setPadding(28, 22, 28, 22)
            setTextColor(Color.BLACK)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.15f)
        }
        tv.textSize = sizeSp
        tv.text = rows[position]
        // Views are recycled, so this must be assigned on every pass — leaving the
        // previous row's description behind is worse than having none.
        tv.contentDescription = describe?.invoke(position)
        return tv
    }
}

/**
 * The rows of הערות: a bold "where from" line and then the segment the entry points
 * at, drawn the way the reader draws a segment — the same HTML, so a bold word is
 * bold and a heading is a heading here too.
 *
 * Rendering is lazy (as in [ReaderAdapter]) and cached, because a stack can hold as
 * many references as the reader has segments and every one of them is a line of
 * markup to parse. The text itself is not held here: [segments] is the map of
 * pointers' segments, filled by the screen as it reads them out of the library, and
 * the map is read at draw time.
 *
 * Group rows (the `— sefer · N —` headings) are labels, not choices, so the D-pad
 * skips them: this phone has no touchscreen to skip past one with.
 */
class NotesAdapter(
    private val ctx: Context,
    private var rows: List<Notes.Row>,
    /** seq -> the raw segment that entry points at. */
    private val segments: Map<Long, String>,
    var sizeSp: Float = 20f
) : BaseAdapter() {

    // Bounded, and smaller than the reader's: a row here is a WHOLE segment, so a
    // cached row is a whole segment — the reader's rows are segments too, but the
    // stack can hold as many of them at once as the reader has lines.
    private val cache = LruCache<Int, CharSequence>(16)

    /** New rows: the list changed, so every drawn row is stale. */
    fun setRows(newRows: List<Notes.Row>) {
        rows = newRows
        cache.evictAll()
        notifyDataSetChanged()
    }

    override fun getCount() = if (rows.isEmpty()) 1 else rows.size

    override fun getItem(position: Int): Any = rows.getOrNull(position) ?: ""

    override fun getItemId(position: Int): Long = position.toLong()

    /** The empty list has one row, and it says so; it is not a choice either. */
    override fun areAllItemsEnabled() = false

    override fun isEnabled(position: Int) =
        rows.isNotEmpty() && rows.getOrNull(position) is Notes.Row.Item

    private fun row(position: Int): CharSequence {
        cache.get(position)?.let { return it }
        val out: CharSequence = when (val r = rows.getOrNull(position)) {
            null -> Ui.html("הערות ריקות.<br>לחצו ימינה על קטע בספר, או על שורה במפרשים, כדי להוסיף.")
            is Notes.Row.Group -> Ui.html("<b>— ${r.book} · ${r.count} —</b>")
            is Notes.Row.Item -> {
                val e = r.entry
                val s = SpannableStringBuilder().append(Ui.html("<b>${e.where}</b>"))
                val segment = segments[e.seq]
                if (!segment.isNullOrEmpty()) s.append("\n").append(Ui.html(segment))
                s
            }
        }
        cache.put(position, out)
        return out
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tv = (convertView as? TextView) ?: TextView(ctx).apply {
            setPadding(28, 22, 28, 22)
            setTextColor(Color.BLACK)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.15f)
        }
        tv.textSize = sizeSp
        tv.text = row(position)
        // The spoken form is the whole segment in plain words: the markup is for the
        // eye, and a screen reader would otherwise read it out as punctuation.
        tv.contentDescription = when (val r = rows.getOrNull(position)) {
            is Notes.Row.Item ->
                segments[r.entry.seq]?.let { "${r.entry.where}. ${Notes.plain(it)}" }
                    ?: r.entry.where
            is Notes.Row.Group -> "${r.book}, ${r.count}"
            else -> null
        }
        return tv
    }
}

/**
 * The reader's segment list. Unlike [RowAdapter] it does NOT pre-render the whole book:
 * each row's HTML is parsed lazily in [getView] (and cached) as it scrolls into view, so
 * even a 34 MB sefer opens instantly. A ◆ prefix marks segments with chosen meforshim.
 *
 * Rows map 1:1 to file lines: row [position] is file line `position + 1`, so nothing here
 * disturbs the line indices the meforshim link graph depends on.
 */
class ReaderAdapter(
    private val ctx: Context,
    private val lines: List<String>,
    var commented: Set<Int>,
    var sizeSp: Float = 20f
) : BaseAdapter() {

    // Bounded so a huge book can't pin unbounded rendered spans.
    private val cache = LruCache<Int, CharSequence>(512)

    /**
     * The row the reader was SENT to, when it was sent to one — from הערות, which
     * opens a sefer at the line a reference names.
     *
     * The D-pad highlight alone cannot say this: the reader opens the book and the
     * row under the crosshair is the one they were sent to, but a reader who has not
     * touched a key yet cannot tell "sent here" from "opened here". So the row is
     * drawn with a band of its own.
     */
    var landedAt: Int = -1

    fun refresh(newCommented: Set<Int>) {
        commented = newCommented
        cache.evictAll()
        notifyDataSetChanged()
    }

    override fun getCount() = lines.size
    override fun getItem(position: Int): Any = lines[position]
    override fun getItemId(position: Int) = position.toLong()

    private fun row(position: Int): CharSequence {
        cache.get(position)?.let { return it }
        val body = Ui.html(lines[position])
        val out: CharSequence =
            if (commented.contains(position + 1))
                SpannableStringBuilder("${Ui.HAS_MEFORSHIM}  ").append(body)
            else body
        cache.put(position, out)
        return out
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tv = (convertView as? TextView) ?: TextView(ctx).apply {
            setPadding(28, 22, 28, 22)
            setTextColor(Color.BLACK)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(0f, 1.15f)
        }
        tv.textSize = sizeSp
        tv.text = row(position)
        // The row the reader was sent to carries a band of its own, so "you were
        // brought here" is visible and not merely implied by the crosshair.
        tv.setBackgroundColor(if (position == landedAt) LANDED_BAND else Color.TRANSPARENT)
        // The ◆ is the one thing this screen exists to tell you and the one thing
        // a screen reader cannot get from the glyph.
        tv.contentDescription =
            Ui.describeRow(Ui.html(lines[position]), commented.contains(position + 1))
        return tv
    }

    private companion object {
        /** The band behind the row a visit was sent to. */
        const val LANDED_BAND = 0xFFFFF3C4.toInt()
    }
}
