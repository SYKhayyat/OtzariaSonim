package com.otzaria.sonim

import android.content.Context

/** Tiny persisted preferences (reading font size). */
object Settings {
    private const val KEY_FONT = "font_sp"
    private const val DEFAULT_FONT = 20f

    fun fontSize(ctx: Context): Float =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_FONT, DEFAULT_FONT)

    fun setFontSize(ctx: Context, sp: Float) {
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FONT, sp).apply()
    }

    /**
     * The chosen commentators for a book, or null if the user never chose (=> show all).
     * Persisted as a string set keyed by book title.
     */
    fun selectedCommentators(ctx: Context, bookTitle: String): Set<String>? =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getStringSet("sel_$bookTitle", null)

    fun setSelectedCommentators(ctx: Context, bookTitle: String, sel: Set<String>) {
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet("sel_$bookTitle", HashSet(sel)).apply()
    }

    /**
     * The 0-based list position the reader was last at for a book, or -1 if never opened.
     * Lets us reopen a sefer where the user left off instead of at the top.
     */
    fun lastPosition(ctx: Context, bookTitle: String): Int =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getInt("pos_$bookTitle", -1)

    fun setLastPosition(ctx: Context, bookTitle: String, pos: Int) {
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .edit().putInt("pos_$bookTitle", pos).apply()
    }
}
