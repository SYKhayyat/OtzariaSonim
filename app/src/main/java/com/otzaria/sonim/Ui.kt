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
import android.widget.TextView

object Ui {
    /** Render the library's minimal HTML (h1/h2/h3/b) to styled text. */
    fun html(s: String): Spanned =
        if (Build.VERSION.SDK_INT >= 24)
            Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
        else
            @Suppress("DEPRECATION") Html.fromHtml(s)
}

/**
 * A ListView adapter of Hebrew rows, rendered RTL at an adjustable sp size.
 * Rows are CharSequence so callers can pass plain strings (menus) or Spanned (text).
 */
class RowAdapter(
    ctx: Context,
    private val rows: List<CharSequence>,
    var sizeSp: Float = 20f
) : ArrayAdapter<CharSequence>(ctx, 0, rows) {

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
            if (commented.contains(position + 1)) SpannableStringBuilder("◆  ").append(body)
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
        return tv
    }
}
