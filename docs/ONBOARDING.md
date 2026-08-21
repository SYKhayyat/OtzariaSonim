# Onboarding

Getting from a clone to a working reader on a handset, and then finding your way
around the code.

The app is the easy half. **The library pipeline is where the time goes**, and
§3 is the section to read carefully — every failure mode in it is silent.

---

## Contents

- [1. What this is](#1-what-this-is)
- [2. Build and install the APK](#2-build-and-install-the-apk)
- [3. Build and push the library](#3-build-and-push-the-library)
- [4. Use it](#4-use-it)
- [5. The data model](#5-the-data-model)
- [6. The code](#6-the-code)
- [7. Making a change](#7-making-a-change)

---

## 1. What this is

A tiny, **non-touch, D-pad-driven** Torah reader for rugged Sonim keypad phones
(XP5s / XP5800, Android 7). It reuses the Otzaria library **data as-is** — the
books and the meforshim link graph — behind a stripped, keypad-first UI.

Native Kotlin, plain Android Views, **zero AndroidX and no native libraries**,
producing a ~850 KB APK that runs on Android 7+ (minSdk 24), 32- or 64-bit.
Verified on a **Sonim XP5800, Android 7.1.2**.

**Reading and meforshim linking is the whole point.** Full-text search is
deliberately left out to save space. If you are about to add it, that is a
decision to reopen rather than an omission to fix.

Four documents sit alongside this one:

| | |
|---|---|
| [`../README.md`](../README.md) | Controls, building, the library pipeline, the project layout |
| [`../SPEC.md`](../SPEC.md) | The verified data format and the resolution rules |
| [`../BUILDER.md`](../BUILDER.md) | Work orders, and what was measured while doing them |
| [`../CHANGELOG.md`](../CHANGELOG.md) | Delivered features and the backlog |
| [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) | Symptom-first |

## 2. Build and install the APK

**Requirements:** Android Studio or the Android SDK plus build tools, **JDK
17**, and `adb`.

Toolchain: AGP 8.11.1 · Kotlin 2.0.21 · Gradle 8.14.3 · compileSdk 36 ·
minSdk 24.

**Android Studio:** open the `OtzariaSonim` folder, let it sync, Run.

**Command line:**

```powershell
cd OtzariaSonim
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

macOS and Linux: `./gradlew assembleDebug`.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb shell pm grant com.otzaria.sonim android.permission.READ_EXTERNAL_STORAGE
```

**Install the APK before you push the library.** The reason is in §3 and it is
not cosmetic.

## 3. Build and push the library

The phone reads a **packed** library, built on the PC. Source data lives under
`Downloads/otzaria_latest/` — the `.txt` tree plus `links/`.

```
/storage/emulated/0/Otzaria/
├─ אוצריא/    the text tree (categories -> books .txt).  KEEP THE STRUCTURE.
└─ idx/       one <BookName>.idx per book: the meforshim sidecar
```

### Pack

```powershell
python tools\pack_library.py --out D:\packed --categories תנך משנה הלכה
python tools\verify_idx.py D:\packed
```

**Always run `verify_idx.py` before anything goes near the phone.** It
re-derives everything from the bytes on disk — it does not reuse the packer's
structures — and asserts what each link actually *is*, for example that ויקרא is
not a מפרש on שולחן ערוך יורה דעה.

Three ways to choose scope:

| Flag | Ships |
|---|---|
| `--categories <names>` | only those categories |
| `--all` | the linked books and their targets only — **981 books fewer** than the phone already has, on this corpus |
| `--text-all` | re-pack an existing full library, keeping every book |

### Push

`pack_library.py` writes both `אוצריא/` and `idx/` into `--out`, already
consistent with each other.

```powershell
& $adb push "D:\packed\אוצריא" "/storage/emulated/0/Otzaria/אוצריא"
& $adb push "D:\packed\idx"    "/storage/emulated/0/Otzaria/idx"
```

Do **not** push `links/`. The phone no longer reads it.

### The two silent failures

Both of these report success and then do the wrong thing. Learn them now.

**1. `adb push` nests folders.** Its behaviour depends on whether the
destination already exists:

| | |
|---|---|
| DST does **not** exist | it is created *as* SRC — name the full remote path |
| DST **does** exist | SRC lands *inside* it, giving `.../אוצריא/משנה/משנה` — name the **parent** |

The push says it worked, the files sit one level too deep, and the app keeps
reading the old copies. Verify every time:

```powershell
$dev = "/storage/emulated/0/Otzaria/אוצריא"
& $adb shell "[ -d '$dev/משנה/משנה' ] && echo NESTED-BAD || echo ok"
```

**2. Sidecar version versus APK.** The `.idx` format is at **v2** — one byte per
commentator recording what that book *is* to this one.

- A **v1 sidecar under the new APK** opens fine and everything reads as a
  commentary, which is exactly what v1 claimed. An out-of-date library degrades
  rather than breaking.
- A **v2 sidecar under an old APK is refused**, and the book shows no meforshim
  at all — with no error.

**So: install the APK, then push `idx/`.**

### First run

Launch **אוצריא** from the phone's app menu. If it reports the folder is not
found, press **MENU** on the library screen and set the path.

## 4. Use it

Everything is the **D-pad + Center (OK) + Back**, plus a few number and volume
keys. The on-device cheat-sheet is `#` from the library screen.

**Library:** Up/Down moves, OK opens, Back goes up one folder and exits at the
top. The categories mirror the Otzaria structure exactly.

**Reader:** one segment per row, right-to-left. **◆ marks segments that have
meforshim** from the commentators you have chosen.

| Key | Action |
|---|---|
| OK | Open the meforshim on this segment, stacked |
| `#` or MENU | Choose which meforshim appear, for this book |
| `*` | Chapter TOC |
| `0`–`100` typed | Jump to that percent of the book |
| Volume ± | Font size, persisted |

Each sefer reopens where you left off.

### Try this on your first run

Open שולחן ערוך אורח חיים, press `#`, and look at the picker. The commentators
are in **three groups**: commentaries on this book, the sefer this one comments
on, and other cross-references.

That grouping is the single most consequential thing in the app, and §5 explains
why.

## 5. The data model

### Why a packer, and not the raw JSON

The reader asks two questions. Answering either from `<Book>_links.json` does
not fit the phone — for שולחן ערוך אורח חיים that file is **72.4 MB** against a
**192 MB heap**, which produced an `OutOfMemoryError` before the first row could
draw, along with בראשית, שמות, דברים, תהילים and שולחן ערוך חושן משפט.

| | raw JSON | packed `.idx` |
|---|---|---|
| open a book (draw the ◆) | parse 72.4 MB | **read 49 KB** |
| press OK on one line | the same 72.4 MB | one seek |
| links on disk | 166 MB of JSON | **12.4 MB** of `.idx` |

The packer does three more things:

- **Drops links whose target book is not on the phone**, so a ◆ can never open
  to `אין מפרשים`.
- **Strips Sefaria's inline `<i data-commentator>` anchors** — 48% of שולחן
  ערוך's bytes. This matters because `ReaderActivity` loads a book *whole*, and
  it also makes phrases like `יתגבר כארי` contiguous again.
- **Stores target paths in the sidecar**, so there is no `filename -> path` scan
  at runtime.

### The link-direction problem

This is the one to understand.

Otzaria's link graph labels an edge `commentary` **and does not say which end is
the commentary** — and it stores nearly every edge **twice, once in each
direction**.

Taken at face value, that offers every book as a commentator on everything it is
connected to. Measured: **6,814 of 14,058 book-commentator pairs — 48% — were
mislabelled.** Half the "meforshim" on offer were not meforshim.

Links are now oriented from Sefaria's `dependence` / `base_text_titles`
declarations, rewritten against Otzaria's filenames:

```
tools/build_bases.py  ->  tools/base_texts.json    which sefer comments on which
tools/linkkind.py                                   the per-link answer, self-testing
```

`base_texts.json` is checked in and only needs regenerating if the corpus
changes. `python tools/linkkind.py` runs its own tests.

That classification is what produces the sidecar's per-commentator **kind byte**,
which is what produces the three groups in the picker. A v1 sidecar has no kind
byte, which is why everything in it reads as a commentary.

### Bookmarks that verify themselves

A saved place is a line number into a library you may re-download. So it is
stored with **the first 40 characters of its line**; on reopen the text is
checked, and if it moved the reader scans ±200 lines to find it again.

Only when that fails does it go to the top — **and it says so**, rather than
silently losing your place.

`ResumeTest` covers this.

## 6. The code

Plain files, no database. [`../SPEC.md`](../SPEC.md) has the verified formats.

| File | What it owns |
|---|---|
| `Otzaria.kt` | The data layer. Reads books line by line, opens `idx/<Book>.idx`, parses `<h1..6>` headings for the TOC. |
| `LibraryActivity` | The folder-tree browser. |
| `ReaderActivity` | One segment per row (RTL), the ◆, the pickers, percent jump, last place. |
| `TocActivity` | The chapter TOC, auto-picking the primary heading level. |
| `CommentatorsActivity` | The per-book ☑/☐ filter and its three groups. |
| `CommentaryActivity` | The chosen meforshim on one segment, stacked. |
| `HelpActivity` | The on-device controls cheat-sheet (English/LTR). |
| `Ui.kt` | `RowAdapter` (shared RTL, adjustable-size list adapter) and the minimal `<h1>/<h2>/<h3>/<b>` renderer every screen uses. |
| `Settings.kt` | Persisted font size, per-book commentator selection, last place. |

### Five invariants worth not breaking

1. **Row N is always file line N+1.** Rows render lazily, and this is what keeps
   line indices — and therefore links — from ever moving.
2. **The book is read off the main thread.** Some seforim are tens of megabytes;
   reading one in `onCreate` would ANR. The header shows `טוען…`.
3. **A missing or corrupt sidecar means no meforshim, never a crash.**
4. **Caches are bounded** — LRU by character budget — and commentary targets
   over about 2 MB are streamed one line at a time.
5. **A commentator selection is persisted with the roster it was made against**,
   so a later re-pack that adds a commentator shows it *checked* rather than
   silently off.

### Heading parsing is BOM-tolerant, deliberately

**190** books in the corpus carry a byte-order mark, and **185** of them were
losing a heading to it — the sefer's own title, missing from its own table of
contents. `HeadingsTest` covers it.

## 7. Making a change

```powershell
.\gradlew.bat test
```

Four test classes: `AccessibleNamesTest`, `BookIndexTest`, `HeadingsTest`,
`ResumeTest`.

### The lesson in `AccessibleNamesTest`

Worth reading before you write a UI test here, because it is a good example of a
test that would have passed vacuously.

An accessibility work order was filed as "`contentDescription` appears zero
times across nine UI files", with tests planned for `setOnClickListener` call
sites and `ImageView`s in layout XML.

This app has **no** `setOnClickListener` calls, **no** `ImageView`s and **no
layout XML at all**. Every screen is one `ListView` of `TextView`s, and a
`TextView` is announced by its own text — so the rows were named all along and
both planned tests would have passed while checking nothing.

What was genuinely unnamed: the four `ListView`s, and the three glyphs the app
leans on (`◆`, `☑`/`☐`, `📁`), none of which a screen reader can render as
meaning.

The fix kept the lesson rather than the test: **an unnamed control must be
impossible to write.** Hence `Ui.list(ctx, name)` and `AccessibleNamesTest`. If
that test fails, you added a list without a name.

### House conventions

- **No AndroidX, no native libraries.** The APK is ~850 KB and runs on Android
  7. Adding a dependency needs a reason that survives that.
- **Every screen is a `ListView` of `TextView`s** via `Ui.list` and
  `RowAdapter`. There is no layout XML; do not introduce any without a reason.
- **The packer and the app must agree about the `.idx` format.** A format change
  means a version bump and the install-APK-then-push ordering from §3.
- Changes to what a link *means* go through `tools/linkkind.py`, which
  self-tests, and are validated by `verify_idx.py`.

### Where the open work is

[`../CHANGELOG.md`](../CHANGELOG.md) has the backlog. The largest item is
swapping the packer's link *source* to Girsa's corpus once its graph is unioned
— the `.idx` format does not change, and `tools/girsa_coverage.py` reports 82%
commentator coverage on a 12-book sample.

[`../BUILDER.md`](../BUILDER.md) records the five work orders that produced most
of the current behaviour, with the measurements attached.

---

## Where to go next

- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — symptom-first, starting with the
  three checks that resolve most library problems.
- [`../SPEC.md`](../SPEC.md) — the verified data format and resolution rules,
  including why step 1 of meforshim resolution is necessary and **not
  sufficient**.
