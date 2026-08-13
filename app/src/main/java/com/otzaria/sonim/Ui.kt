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
        // The ◆ is the one thing this screen exists to tell you and the one thing
        // a screen reader cannot get from the glyph.
        tv.contentDescription =
            Ui.describeRow(Ui.html(lines[position]), commented.contains(position + 1))
        return tv
    }
}
