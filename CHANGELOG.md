# Changelog

This file records notable changes and design decisions. For how the app works
today, see `README.md` and `SPEC.md`.

## Delivered

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
