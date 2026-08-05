package com.otzaria.sonim

import android.content.Context

/** Tiny persisted preferences (reading font size, per-book choices, last place). */
object Settings {
    private const val KEY_FONT = "font_sp"
    private const val DEFAULT_FONT = 20f

    /**
     * How far either side of a saved position to look for the remembered text.
     * A named constant because it is a judgement about how much a sefer plausibly
     * shifts between two downloads of the library, not a magic number.
     */
    const val RESUME_SCAN = 200

    /** How much of the line to remember. Long enough to be unique in a sefer,
     *  short enough that thousands of them cost nothing. */
    private const val FINGERPRINT = 40

    fun fontSize(ctx: Context): Float =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_FONT, DEFAULT_FONT)

    fun setFontSize(ctx: Context, sp: Float) {
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FONT, sp).apply()
    }

    /**
     * The chosen commentators for a book, or null if the user never chose
     * (=> [Otzaria.defaultSelection]).
     */
    fun selectedCommentators(ctx: Context, bookTitle: String): Set<String>? =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getStringSet("sel_$bookTitle", null)

    /**
     * Saves the choice, and with it the roster it was made against.
     *
     * Without the roster there is no way to tell "the reader unchecked this" from
     * "this did not exist when the reader chose", so a re-packed library that adds
     * a commentator to a sefer would leave it silently off forever — which reads
     * as the app hiding a meforish for no reason. See [reconcileSelection].
     */
    fun setSelectedCommentators(ctx: Context, bookTitle: String, sel: Set<String>,
                                roster: Set<String>) {
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("sel_$bookTitle", HashSet(sel))
            .putStringSet("seen_$bookTitle", HashSet(roster))
            .apply()
    }

    private fun seenCommentators(ctx: Context, bookTitle: String): Set<String>? =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getStringSet("seen_$bookTitle", null)

    /**
     * The saved choice brought up to date with the book as it is now: names that
     * are gone drop out, and names that appeared since the reader last looked are
     * on if they are worth defaulting on.
     */
    fun reconcileSelection(ctx: Context, bookTitle: String,
                           available: List<Otzaria.Commentator>): Set<String> {
        val saved = selectedCommentators(ctx, bookTitle)
            ?: return Otzaria.defaultSelection(bookTitle)
        return reconcile(saved, seenCommentators(ctx, bookTitle), available)
    }

    /**
     * Pure, so the upgrade path can be tested without a device.
     *
     * [seen] is null for a choice saved by a build that predates link kinds. Those
     * are not carried over as-is: that build checked *everything* by default, so a
     * saved name is not evidence anybody wanted it — and one of those names is
     * ויקרא on שולחן ערוך יורה דעה, which is the whole reason kinds exist. Anything
     * now known not to be a commentary is dropped, and the reader can put it back
     * from the קישורים נוספים group in one keypress.
     */
    fun reconcile(saved: Set<String>, seen: Set<String>?,
                  available: List<Otzaria.Commentator>): Set<String> {
        val out = HashSet<String>()
        for (c in available) {
            val chosen = saved.contains(c.name)
            val keep = when {
                seen == null -> chosen && c.kind != Otzaria.KIND_RELATED
                // Never offered before => exactly what a first-time reader would see.
                !seen.contains(c.name) -> c.kind != Otzaria.KIND_RELATED
                else -> chosen
            }
            if (keep) out.add(c.name)
        }
        return out
    }

    /** The commentators actually in force for a book — what the ◆ and the panel use. */
    fun activeSelection(ctx: Context, bookTitle: String): Set<String> =
        reconcileSelection(ctx, bookTitle, Otzaria.commentators(bookTitle))

    // ------------------------------------------------------------- last place

    /**
     * Where the reader was, and whether it is still where it was.
     *
     * A saved position is a raw list index into a library the reader re-downloads.
     * When a sefer gains or loses a line upstream every bookmark in it silently
     * points somewhere else, and the reader reopens hundreds of lines from where
     * they left off with nothing said — which reads as the app losing their place
     * at random (BUILDER.md S2). So the line's opening text is stored beside the
     * index, and the index is only believed if the text still agrees.
     */
    data class Resume(
        val pos: Int,
        /** True when the sefer changed enough that the place could not be found. */
        val lost: Boolean
    )

    fun lastPosition(ctx: Context, bookTitle: String): Int =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getInt("pos_$bookTitle", -1)

    private fun lastPositionText(ctx: Context, bookTitle: String): String? =
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
            .getString("postext_$bookTitle", null)

    fun setLastPosition(ctx: Context, bookTitle: String, pos: Int, line: String) {
        ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE).edit()
            .putInt("pos_$bookTitle", pos)
            .putString("postext_$bookTitle", fingerprint(line))
            .apply()
    }

    fun resume(ctx: Context, bookTitle: String, lines: List<String>): Resume? =
        resolveResume(lastPosition(ctx, bookTitle), lastPositionText(ctx, bookTitle), lines)

    private fun fingerprint(line: String) = line.trim().take(FINGERPRINT)

    /**
     * Pure so it can be tested without a device. null means "no saved place" —
     * which is not the same as "the place was lost", and only one of the two is
     * worth telling the reader about.
     */
    fun resolveResume(saved: Int, savedText: String?, lines: List<String>): Resume? {
        if (saved < 0 || lines.isEmpty()) return null
        // Written by a build that stored no text. Trust it rather than announce a
        // problem on every book the first time the reader upgrades.
        if (savedText.isNullOrEmpty()) {
            return if (saved in lines.indices) Resume(saved, false) else null
        }
        if (saved in lines.indices && fingerprint(lines[saved]) == savedText) {
            return Resume(saved, false)
        }
        // The library shifted. The place is usually a handful of lines away, so
        // walk outwards from where it was rather than scanning the whole sefer —
        // ערוך השולחן is 26,776 lines and this runs on every open.
        for (d in 1..RESUME_SCAN) {
            for (p in intArrayOf(saved - d, saved + d)) {
                if (p in lines.indices && fingerprint(lines[p]) == savedText) {
                    return Resume(p, false)
                }
            }
        }
        return Resume(0, true)
    }
}
