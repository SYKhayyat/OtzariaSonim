package com.otzaria.sonim

/**
 * One reader row: the segment's markup, parsed once.
 *
 * WHY A ROW IS PARSED AT ALL. The reader draws a segment the way the sefer writes
 * it — a bold word is bold, a heading is a heading — and the markup has to be parsed
 * to do that. The parse is the expensive part: the app's HTML is minimal, but
 * `Html.fromHtml` is a real parser, and a reader scrolls through thousands of rows.
 *
 * WHY THE ROW IS A CLASS. A drawn row and a spoken row are the same text, and the
 * old code parsed the markup twice for every row: once for the text on the screen,
 * and again for the content description — the text a screen reader says. Two parses
 * of the same string, for every row the reader scrolls past, to make a description
 * out of the parse that had already been done.
 *
 * So: one parse, and the row keeps both what is drawn and what is spoken. The
 * spoken text is the body WITHOUT the meforshim mark — a screen reader cannot read
 * a lozenge as meaning, which is what the mark is for.
 *
 * The types here are plain [CharSequence], not Android's `Spanned`, so this file
 * can be tested without a device. The caller parses into whatever it draws with.
 */
object ReaderRow {

    /** What is drawn on the row, and what is said for it. */
    data class Row(val drawn: CharSequence, val spoken: CharSequence)

    /**
     * The row of one segment.
     *
     * [parse] turns the markup into whatever the caller draws with; it is called
     * exactly once. [join] puts the meforshim mark in front of the parsed text.
     */
    fun of(
        line: String,
        hasMeforshim: Boolean,
        parse: (String) -> CharSequence,
        join: (CharSequence, String) -> CharSequence
    ): Row {
        val body = parse(line)
        val drawn = if (hasMeforshim) join(body, MARK) else body
        return Row(drawn, body)
    }

    /**
     * The mark that says "there is meforshim here" — in front of the text, not in it.
     *
     * The same glyph as [Ui.HAS_MEFORSHIM], with the spacing the reader draws it
     * with.
     */
    const val MARK = "◆  "
}
