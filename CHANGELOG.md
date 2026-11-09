# Changelog

This file records notable changes and design decisions. For how the app works
today, see `README.md` and `SPEC.md`.

## Delivered

- **The chapter TOC no longer freezes the app on a big sefer.** Opening the TOC parsed the
  whole book — every line, to find its `<h1..6>` headings — on the main thread: 31 MB and
  26,776 lines for ערוך השולחן, which is seconds of frozen screen and an ANR the system
  offers to "close". The parse now happens in the background, the header says `טוען…` until
  the chapters land, and the TOC's own arithmetic (the indent per heading depth, the primary
  level a chapter number counts in) moved into `Toc.kt`, so it is unit-tested without a
  device — including a test that the book is read off the calling thread, which is the
  ANR itself.
- **From הערות you can now get back to the sefer, and the list has a menu.** The stack held
  pointers into seforim and there was no key that opened one: `OK` opened the meforshim on the
  reference, `*` cycled the order, and that was all. Now `*` opens **the sefer that reference
  points into, at the line it names** — the reader lands there with the line banded, and the
  visit does **not** overwrite the place that book was left at (a visitor does not move the
  reader's place — unless you read on from there, which is a different thing). A **tap on MENU
  inside הערות** opens a small menu instead of saying "you are
  already here": the four orders (newest first, oldest first, grouped by sefer, and grouped by
  sefer **in book order** — the last one is new) and **חזרה לספרים**, which closes the list. The
  order is chosen from the menu, so `*` is free for the sefer. The menu is a panel of the
  screen and not a dialog, because this phone has no touchscreen: the D-pad has to walk it.
  The opening logic is pure (`Settings.resolveOpen`), so it is unit-tested without a device.
- **הערות shows the whole segment, drawn the way the reader draws it.** The row used to show
  a 60-character clip of the line, flattened to plain text — so a stack of references read as
  one clipped line per entry while the reader showed the whole box. The row is now drawn from
  the raw line with the same HTML the reader uses (bold is bold, a heading is a heading) and
  the whole of it, wrapping over as many screen lines as the text needs. The entry is still a
  pointer: the segment is read out of the library when the row is drawn, so the list cannot
  drift from the book it points into. `Notes.plain` (the spoken form) keeps the whole line too.
- **MENU is two keys: tap = הערות, hold = what MENU used to do.** The stack could only be
  reached from the reader (and the meforshim panel), which makes it a second reading position
  you have to leave the book to use. A tap on MENU now opens it from every screen — library,
  reader, meforshim panel, contents, picker, help — and the per-screen MENU action moved to
  holding the key: library = the folder path, reader = the commentator picker, and inside
  הערות = close it. The split is decided on key-up by how long the key was down
  (`MenuKey.kt`, unit-tested on a fake clock), and it is inherited from a new `OtzActivity`
  base class rather than wired per screen, so a new screen cannot lose the tap —
  `MenuContractTest` fails the build if one tries. Opening the list reorders an existing
  instance to the front instead of stacking copies.
- **A number typed ahead now counts segments as well as naming a percent.** Type `20` and
  press Down and the highlight moves twenty segments (the numeric argument Emacs puts on
  Ctrl-U); press OK and it is still `20%`. Clamped to the sefer at both ends, never wrapping,
  and the move spends the number. The same works in the TOC, where the typed number already
  named the Nth chapter and now also counts rows for Up/Down. The header prompt states both
  readings while digits are pending.
- **D-pad Left erases a typed digit.** Every screen that takes a number — the reader's percent
  and count, the TOC's chapter — gets backspace from one place (`TypeIn`), so a mistyped `12`
  meant to be `15` is fixed in place instead of retyped. `Back` still cancels the whole entry
  in the reader, `#`/`*` still clear the TOC. Outside entry mode D-pad Left keeps its usual
  meaning (remove from הערות). Back was also fixed while doing this: it cleared the pending
  number and then *fell through* to the framework, which read the same press as "leave the
  book" — so pressing Back to abandon a typo threw you out of the sefer. It now cancels and
  stops there (`TypedKeys`/`TypedAction.CANCEL`, unit-tested), and Up/Down reach the count
  through an `OnKeyListener` because the ListView consumes them before the Activity sees them.
- Lazy loading for large books (rows render on demand; the file is read off the
  main thread, so a 31 MB / 26,776-line sefer no longer blocks `onCreate`).
- Chapter table of contents (`*` key), parsed from `<h1..6>` headings.
- Percent jump: type `0`–`100` to jump to that percent of a book.
- Resume: each sefer reopens where you left off. Bookmarks are self-verifying —
  a saved place stores the first 40 characters of its line, and on reopen the
  reader re-locates the line (scanning ±200 lines) if the library was re-downloaded
  and the line moved. Only on failure does it return to the top, and it says so.
- Binary meforshim links index (`.idx` sidecars) replacing runtime parsing of the
  raw `links/` JSON. This removed `OutOfMemoryError` crashes on the largest books
  (e.g. שולחן ערוך אורח חיים, whose `_links.json` is 72.4 MB against a 192 MB heap).
- Meforshim link-kind classification: links are oriented from Sefaria's
  `dependence` / `base_text_titles` declarations, so a book is only offered as a
  commentator where it actually is one. Previously 6,814 of 14,058 book↔commentator
  pairs (48%) were mis-labelled.
- Per-book commentator selection, grouped into commentaries / the base text this
  work comments on / other cross-references. Persisted per book together with the
  roster it was chosen against, so a later re-pack that adds a commentator shows it
  checked rather than silently off.
- Opening a sefer no longer jumps into the chapter TOC. The reader opened the TOC first for
  any book over 1,500 lines that had no saved place — בראשית is 1,584, so the most ordinary
  sefer there is did it — and a keypress that asked for a book returning a table of contents
  reads as the app ignoring you. `*` still opens the TOC, and nothing else changed.
- **הערות — a stack of references.** D-pad Right pushes the highlighted segment, or the
  highlighted line inside the meforshim panel (which pushes that commentary's own line in its
  own sefer). D-pad Left removes it — the one copy under the highlight inside הערות, every
  copy of that reference outside it. MENU opens the list, replacing the second route to the
  commentator picker that existed only because MENU and `#` both opened it; `#` remains the
  picker. Center (OK) opens the meforshim on the reference, `*` cycles the order (newest /
  oldest / grouped by sefer, with per-sefer headings the D-pad skips), and the header says
  `הערות · N · <order>` plus where the highlighted item is from.
  An entry stores a path, a line number, a "where from" label and an insertion counter —
  **no text**. The preview under each row is read out of the sefer in the background when the
  list is drawn, so the list cannot drift from the book it points into, and a re-packed
  library is reflected immediately. The list persists in preferences.
- BOM-tolerant heading parsing.
- Adjustable font size (Volume ±), persisted.
- Spoken/accessible names for the ◆ / ☑ / 📁 glyphs.
- On-device help cheat-sheet (`#` from the library).

## Backlog

- Optional: mount the larger **org** corpus as read-only books (no meforshim).
- A "recently read" list on the library screen (the per-book last-place is already
  stored).
- Swap the packer's link *source* to Girsa's corpus once its graph is unioned; the
  `.idx` format does not change. `tools/girsa_coverage.py` reports 82% commentator
  coverage on a 12-book sample.

### Investigated and closed

- `base_texts.json` misses 933 Otzaria-only books — not a gap worth work. 891 of
  the 933 have no links at all, so they never reach a picker. The 42 that do are two
  families (38 × `חברותא על <מסכת>`, 4 × `הערות על שות הרשבא`), where "commentary" is
  the correct answer anyway. `pack_library.py` prints the share of links decided on
  no evidence: 39 pairs, 0.3%. Otzaria's `metadata.json` covers 0 of the 42, and the
  `hebrew_books.csv` / `otzar_books.csv` catalogues have no base-text field.
