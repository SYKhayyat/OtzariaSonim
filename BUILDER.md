# OtzariaSonim — work orders

Filed from Girsa, 31 July 2026, in the other direction: `OtzariaSonim/tools`
filed W32–W35 into `Girsa/BUILDER.md`, and building those turned up four things
here. Same rules as Girsa's BUILDER.md §0 — test first, watch it fail, fix the
family, and a count that is reported beats a guess that is silent.

Numbered `S` so nothing collides with Girsa's `W`.

**Severity order is deliberate.** S1 is first not because it is the biggest bug
but because it is the *measuring instrument*, and while it is wrong every other
number either repo quotes about commentary coverage is wrong too.

---

## Status, 5 August 2026

All four are done, and **S5 was found while doing them and is the biggest of the
five** — it is the one a reader actually reported.

| | | |
|---|---|---|
| **S1** | done | 12-book sample now 82% (was a reported 33%); `--check` self-test added |
| **S2** | done | fingerprinted bookmarks, ±200 scan, 8 tests in `ResumeTest` |
| **S3** | done — **premise corrected**, see below | `Ui.list` + guard test, glyphs spoken |
| **S4** | done | measured **190** BOM books, **185** losing a heading; 5 tests |
| **S5** | done | link direction read from Sefaria; **48%** of offered מפרשים were not |

**S3's premise did not survive contact.** It was filed as "`contentDescription`
appears zero times across nine UI files", with tests for `setOnClickListener` call
sites and `ImageView`s in layout XML. This app has **no** `setOnClickListener`
calls, **no** `ImageView`s and **no** layout XML at all — every screen is one
`ListView` of `TextView`s, and a `TextView` is announced by its own text, so the
rows were named all along and tests 1 and 2 would have passed vacuously. What was
genuinely unnamed: the four `ListView`s, and the three glyphs the app leans on
(`◆`, `☑`/`☐`, `📁`), none of which a screen reader can render as meaning. Fixed
those instead, keeping S3's actual lesson — an unnamed control must be impossible
to *write*, hence `Ui.list(ctx, name)` and `AccessibleNamesTest`.

---

## S5 · Half the "meforshim" are not meforshim

**The claim.** Otzaria's link graph labels an edge `commentary` and does not say
which end is the commentary — and it stores nearly every edge **twice, once each
way**. `Otzaria.availableCommentators` read it as "everything this book points
at", so the picker offered:

| open… | …offered as a commentator |
|---|---|
| שולחן ערוך, יורה דעה | **ויקרא**, טור, בן איש חי, ערוך השולחן |
| משנה ברורה | **שולחן ערוך, אורח חיים** (17,478 links) |
| רשי על בראשית | **בראשית** |
| מסכת שבת | ריף בבא בתרא, תוספות על בבא בתרא, משך חכמה |

**Measured** over all 5,819 links files, 3,567,284 commentary/targum edges whose
target is on the shelf: 51.0% are real commentaries, **43.7% are the mirror of
another edge**, 5.2% are cross-references to independent works. In book↔commentator
terms, **6,814 of 14,058 pairs (48%)** were not commentaries on the book offering
them.

**Otzaria's own app is not the ground truth** — `text_book_repository.dart`
filters on the connection type and nothing else, so it has the identical defect.
Girsa is: `girsa-link/src/orient.rs` orients an edge by *reading* Sefaria's
`dependence` / `base_text_titles` rather than guessing from the title, because
`X על Y` would attach `רשי על ברכות` to the Yerushalmi masechta of the same name.

**The fix.** `tools/build_bases.py` rewrites that declaration against Otzaria's
filenames into a checked-in `tools/base_texts.json` (5,682 of 6,615 books;
en-title → schema → heTitle, because `base_text_titles[].he` says `ספר ויקרא`
where the file is `ויקרא.txt`). `tools/linkkind.py` turns it into four rules, and
`.idx` v2 carries the answer as one byte per commentator. Nothing is deleted — a
link that is not a commentary is relabelled and shown under its own heading.

**Two things this cost, both worth writing down.** Ordering the rules wrongly
(independence checked before the mirror edge) turned every base text into
"unrelated" and lost the מקור label; and the one-hop rule that removes
`ריף בבא בתרא` from מסכת שבת emptied `בית יוסף`'s list completely until it was
guarded on actually having evidence — a sefer that declares nothing, and that
nothing declares, has no hop to test, and demoting on that is demoting on absence.

**The 933 books Sefaria has no schema for.** Filed here first as "the largest
remaining gap", which was wrong, and the correction is the interesting part.

- **891 of the 933 have no links file at all**, so they never reach a picker.
- The 42 that do are two families: 38 × `חברותא על <מסכת>` and
  4 × `הערות על שות הרשבא חלק ד–ז` (those four have no links either).
- **Every** edge in the corpus whose target Sefaria does not know — all 59,404 of
  them, 39 distinct pairs — is `הערות על חברותא על X` → `חברותא על X`, where
  "commentary" is the correct answer regardless.

Three candidate sources were checked and all three are dead:

| source | verdict |
|---|---|
| Otzaria's folder tree (`.../מפרשים/...`) | speaks for 207 of 4,799 books, 82% right when it does, and says **nothing** for all 42. `הלכה/מפרשים/` is a generic bucket, not "commentaries on הלכה" |
| `metadata.json` (both copies) | covers **0** of the 42 |
| `hebrew_books.csv`, `otzar_books.csv` | printing-house catalogues — place, year, subject tags. No base-text field |

**What was done instead of guessing:** `pack_library.py` now reports which rule
decided every book↔commentator pair, so the share resting on no declaration is a
measured number. Over the full library it is **39 pairs, 0.3%**:

```
declared 36.8% · declared mirror 36.9% · one hop 13.0% · independent target 9.4%
further off 2.1% · base unstated 1.4% · self link 0.1% · no evidence 0.3%
```

A gap that is counted every pack is a question somebody can answer later. This one
turned out not to need answering — but that is only knowable because it is counted.

---

## §0 · What was checked and found clean

Recorded because a negative result is worth as much as a finding, and because
the next person should not re-check these.

- **No search feature exists.** So W34 — Sefaria's inline commentary anchors
  being tokenised as words, which broke phrase search in Girsa — *cannot* occur
  here. There is no index over text; `BookIndex` indexes commentary attachment,
  not words. Otzaria's own `.txt` does carry those anchors (6 of 178 sampled
  files), so if search is ever added, read `girsa_corpus::anchors` first.
- **`Html.fromHtml` handles the anchors correctly by accident.**
  `<i data-commentator="…"></i>` is an empty italic span, so it renders as
  nothing rather than as visible markup. Fine. Not relied on by anything.
- **`BookIndex.on(line)` opens a `RandomAccessFile` per call**, which looked
  like a per-row cost in a scrolling list. It is not: `on()` is reached only
  from `Otzaria.commentariesFor` (Otzaria.kt:333), a tap path.
  `markedLines` — the one on the open path — is called twice per book open.
  `.use {}` closes the handle. No leak, no scroll cost. Left alone.

---

## S1 · `girsa_coverage.py` collapses half the corpus into six buckets

**The claim.** `tools/girsa_coverage.py` is the reproduction for W32, and the
numbers it printed — *"Girsa's graph resolves ~a third of the commentators
Otzaria's does"*, and Bavli Berakhot at **2%** — are substantially an artefact
of one line in the tool.

**Concretely.** Line 79, in `girsa_commentators`:

```python
out[e["from"].split("girsa:")[1].split("/")[0]] += 1
```

That takes the source work to be everything before the **first** `/`. Girsa
work slugs are paths, and 3,591 of 7,189 works have one:

| first path segment | works | all counted as |
|---|---|---|
| `bavli` | 1,232 | `bavli` |
| `yerushalmi` | 487 | `yerushalmi` |
| `mishneh-torah` | 88 | `mishneh-torah` |
| `shulchan-arukh` | 4 volumes | `shulchan-arukh` |

So every commentary on every masechta of the Bavli — Rashi, Tosafot, Rif, Rosh,
Ritva, Meiri, Shita Mekubetzet — counts as **one** commentator named `bavli`.
Berakhot's "1 of 40" is forty commentaries in one bucket.

**It found a real defect anyway**, which is why this is S1 and not a dismissal:
Girsa *was* storing 49% of its `comments-on` edges backwards (base → commentary),
and `inbound.jsonl` filed them under the commentary, so the reader's panel really
did show two commentaries on Berakhot instead of thirty. That is fixed in Girsa
by `girsa_link::orient`. But the two defects were multiplying, and only one of
them was Girsa's.

**The sample table, before and after each fix:**

| | as filed | orientation fixed | + tool fixed |
|---|---|---|---|
| ברכות | 2% | 2% | **75%** (30/40) |
| שבת | 19% | 19% | **92%** (48/52) |
| בראשית | 74% | 74% | 73% (73/99) |
| **TOTAL** | **33%** | 67% | **81%** (359/439) |

The Bavli rows do not move at all until the tool is fixed, because the bucket
collapse is total there and hides any improvement.

**The fix.** Resolve the work by longest matching slug against the works that
are actually on the shelf, rather than by string surgery:

```python
def workof(seg_id, ingested):
    """Longest ingested slug that prefixes this id. Slugs contain '/'."""
    parts = seg_id.removeprefix("girsa:").split("/")
    for k in range(len(parts), 0, -1):
        cand = "/".join(parts[:k])
        if cand in ingested:
            return cand
    return None
```

`ingested` is the set of directories under `corpus/works` containing a
`segments.jsonl`. This is the same routine `girsa-link`'s own audit uses, and it
returned 0 unresolvable targets over all 4,182,337 edges.

**Test first, and watch it fail.** A tool with no tests is how this survived.
`tools/test_coverage.py`, or the same three asserts inline behind
`if __name__ == "__main__"`:

1. `workof("girsa:bavli/rashi-on-berakhot/10a:1:1#367")` is
   `bavli/rashi-on-berakhot`, **not** `bavli`.
2. `workof("girsa:turei-zahav-on-shulchan-arukh/orach-chayim/100:1#409")` is
   `turei-zahav-on-shulchan-arukh/orach-chayim`.
3. `girsa_commentators("bavli/berakhot")` has **more than one** key. This is the
   assertion that would have caught it on day one, and it needs no fixture — one
   masechta with one commentator is not a thing that exists.

**Acceptance.** The 12-book sample reports a TOTAL within a few points of 81%,
and the Bavli rows are no longer the two worst in the table. Report the number.

**Sibling — check, do not assume.** `tools/anchor_report.py` takes slugs as
arguments rather than parsing them out of segment ids, so it does not share this
bug. `verify_idx.py` and `pack_library.py` were not read.

---

## S2 · A saved reading position is a line number, and the library is called `otzaria_latest`

**The claim.** `Settings.kt:36-43`:

```kotlin
fun lastPosition(ctx: Context, bookTitle: String): Int =
    ctx.getSharedPreferences(Otzaria.PREFS, Context.MODE_PRIVATE)
        .getInt("pos_$bookTitle", -1)
```

A raw 0-based list index, persisted, keyed by book title. The library it indexes
into is a directory the user re-downloads — the path in both tools is literally
`Downloads/otzaria_latest`. When a sefer gains or loses a line upstream, every
saved position in it silently points somewhere else, and the reader reopens to
the wrong place with nothing said.

This is the same defect Girsa's W6 exists to prevent, and the note on
`girsa_corpus::work::Work::commentary_on` gives the general form: *a line number
is not an identity*. Girsa needed permanent segment IDs and a redirect table
because it edits text. This app does not edit anything, so it does not need any
of that — T1 is conceded and correct here. It needs one thing only: to notice
when its bookmark has gone stale.

**The fix, in about fifteen lines.** Store what the line *said* beside where it
was:

```kotlin
fun setLastPosition(ctx: Context, bookTitle: String, pos: Int, line: String) {
    prefs(ctx).edit()
        .putInt("pos_$bookTitle", pos)
        .putString("pos_text_$bookTitle", line.take(40))
        .apply()
}
```

On reopen: if `lines[pos]` starts with the remembered 40 characters, use it. If
not, scan ±200 lines for a line that does and use that — the library shifted and
the bookmark is recoverable. If nothing matches, go to the top **and say so**
("this sefer changed since you were last here"), because a reader who is
silently 300 lines from where they left off will conclude the app loses their
place at random.

**Test first, and watch these fail.** `Settings` is currently untested.

1. Save at line 500, insert 3 lines above it, reopen → lands on the same text,
   not on line 500.
2. Save at line 500, replace the sefer with an unrelated one, reopen → lands at
   the top **and** reports that it moved.
3. Save and reopen with nothing changed → line 500, no message. (The one that
   stops the fix from crying wolf on every open.)

**Acceptance.** Test 1 green with a real re-pack of one sefer, not a synthetic
list. And the ±200 window is a number in a `const`, not a literal.

**Sibling.** `selectedCommentators` is keyed by book title the same way
(`sel_$bookTitle`). A retitled sefer silently loses the user's chosen
commentators and falls back to showing all of them, which reads as the app
forgetting. Same shape, lower stakes; fix it in the same pass or write down why
not.

---

## S3 · Nothing in the app has a name

**The claim.** `contentDescription` appears **zero** times across all nine UI
files:

```
CommentaryActivity.kt 0   CommentatorsActivity.kt 0   HelpActivity.kt 0
LibraryActivity.kt    0   Otzaria.kt              0   ReaderActivity.kt 0
Settings.kt           0   TocActivity.kt          0   Ui.kt             0
```

With TalkBack on, every tappable thing in this reader is announced as its class
name or as nothing. This is Girsa's B14 in Android form, and the lesson from
B14 is the part worth copying: naming the controls once does not hold, because
the next screen is written without them. What holds is making an unnamed control
**impossible to write**.

In Girsa that was a `controls.ts` where the accessible name is a required
positional argument, so an unnamed control is a compile error. Kotlin can get
most of the way there:

```kotlin
/** Every tappable in this app is created through here, so it cannot be nameless. */
fun View.tappable(name: String, onClick: (View) -> Unit) {
    contentDescription = name
    setOnClickListener(onClick)
}
```

**Test first, and watch it fail.** The only test shape that catches a defect
repeated across nine files is one that reads the source — which is exactly how
Girsa's B4b guard works, and it belongs in `app/src/test` where it runs on every
`gradlew test`:

1. Read every `app/src/main/**/*.kt`. Assert that `setOnClickListener` appears
   **nowhere** outside `Ui.kt` — every call site goes through `tappable`.
2. Assert every `ImageView`/`ImageButton` in `app/src/main/res/layout/*.xml`
   has an `android:contentDescription`.
3. One instrumented check on the reader screen: no node in the accessibility
   tree is both clickable and unnamed.

Test 1 will fail on `CommentatorsActivity`, `LibraryActivity`, `TocActivity`
and `ReaderActivity` today. That is the point.

**Acceptance.** All three green, and the app is navigable end to end with
TalkBack on and the screen off — library → sefer → commentary → back — because
that is the actual claim, not the count of attributes.

**Why this one is not cosmetic.** This is a reader for a Sonim handset. The
device choice already says the target is someone for whom a general-purpose
phone is the wrong shape.

---

## S4 · A byte-order mark hides a sefer's title from its own table of contents

**The claim.** `Otzaria.kt:142`:

```kotlin
private val headingRx = Regex("^<h([1-6])>(.*)</h[1-6]>\\s*$")
```

Run against every heading line in a 1-in-37 sample of the real library
(6,618 `.txt` files, 178 sampled, 26,719 heading lines), it matches all but
four. All four failures are the same thing:

```
אור הישר על חולין.txt          | ﻿<h1>אור הישר על חולין</h1>
משמרות כהונה על סדר נזיקין.txt | ﻿<h1>משמרות כהונה חלק ב</h1>
נימוקי יוסף על עבודה זרה.txt   | ﻿<h1>נמוקי יוסף על עבודה זרה</h1>
תורת נתנאל על התורה.txt        | ﻿<h1>תורת נתנאל</h1>
```

That leading `﻿` is a UTF-8 BOM, `U+FEFF`. Kotlin's `readLines` does not strip
it, so `^<h` cannot match, so the `<h1>` is not a heading. It is line 1 of the
file — the sefer's own title — so these books open with their root TOC entry
missing. Extrapolated from the sample, on the order of **150 of 6,618 books**.

A 0.015% regex miss rate that lands *entirely* on the first line of a file is
not a rounding error; it is a systematic bug wearing a small number.

**The fix.** Strip the BOM once, where the file is read, rather than teaching
every regex about it — `headingRx` is not the only pattern anchored at `^`:

```kotlin
private fun String.withoutBom() = removePrefix("﻿")
```

Applied in `readLines`/`useLines` to the first line only. Tolerating it in the
regex (`^﻿?<h…`) fixes this call site and leaves the next one.

**Test first, and watch these fail.**

1. `headings()` on a two-line fixture whose first line is `"﻿<h1>כותרת</h1>"`
   returns one heading, at index 0, with text `כותרת`.
2. The same fixture without the BOM returns the identical result — so the fix is
   not sensitive to which files have one.
3. Over the real library, if it is present in CI: no file whose first line
   contains an `<h1>` produces an empty heading list. This is the assertion that
   generalises, and it is three lines.

**Acceptance.** Test 3 green over the packed library, and the four named books
above show their title in the TOC.

---

## Not examined

Said plainly so it is not mistaken for a clean bill:

- `tools/verify_idx.py`, `tools/pack_library.py` — not read.
- The `.idx` binary format was read for its layout comment and its two lookup
  paths; the **writer** (`pack_library.py`) was not, so nothing here says the
  bitmaps and the line directory agree with each other. `BookIndexTest` has
  four tests and they are all on the reader.
- `CommentaryActivity`, `HelpActivity`, `TocActivity` were grepped, not read.
- No Android build was run and no test was executed. Everything above is from
  reading the source and from running the two Python tools against the real
  library. **The four counts in S4 and the slug figures in S1 were measured; the
  fixes were not.**
