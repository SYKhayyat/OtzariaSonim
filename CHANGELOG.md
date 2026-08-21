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
