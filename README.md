# אוצריא לסוֹנים — Otzaria for Sonim (keypad reader)

A tiny, **non-touch, D-pad-driven** Torah reader for rugged Sonim keypad phones
(**XP5s / XP5800**, Android 7). It reuses the Otzaria library **data as-is** — the
books and the meforshim (commentary) link graph — behind a stripped, keypad-first UI.
**Reading and meforshim linking** is the whole point; full-text search is intentionally
left out to save space.

It is a native Kotlin app (plain Android Views, zero AndroidX / native libraries),
producing a ~850 KB APK that runs on Android 7+ (minSdk 24), 32- or 64-bit. Verified
running on a **Sonim XP5800, Android 7.1.2**.

| Library | Reader (◆ = has meforshim) | Meforshim (stacked) | Choose meforshim (`#`) |
|---|---|---|---|
| ![library](docs/01-library.png) | ![reader](docs/02-reader.png) | ![meforshim](docs/03-meforshim.png) | ![picker](docs/04-picker.png) |

**More docs:** `SPEC.md` (verified data format and rules) · `BUILDER.md` (build/packing
notes) · `CHANGELOG.md` (delivered features and backlog).

---

## Building the APK

Requires Android Studio (or the Android SDK + build tools), a JDK 17, and — to install
onto a phone — `adb` and a connected device.

**Android Studio:** open the `OtzariaSonim` folder, let it sync, and Run.

**Command line (PowerShell):**
```powershell
cd OtzariaSonim
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
# APK -> app\build\outputs\apk\debug\app-debug.apk
```
(On macOS/Linux use `./gradlew assembleDebug`.)

Toolchain: AGP 8.11.1 · Kotlin 2.0.21 · Gradle 8.14.3 · compileSdk 36 · **minSdk 24** ·
JDK 17. Run the unit tests with `.\gradlew.bat test`.

### Install and first run
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb shell pm grant com.otzaria.sonim android.permission.READ_EXTERNAL_STORAGE
```
Then load a library (below), launch **אוצריא** from the phone's app menu, and — if it
reports the folder is not found — press **MENU** on the library screen and set the path.

---

## How to use it (controls)

Everything is the **D-pad + center (OK) + Back**, plus a couple of number/volume keys.

### Browsing the library
- **Up / Down** — move the highlight.
- **Center (OK)** — open the folder or book.
- **Back** — go up one folder (at the top it exits the app).
- The categories and folders mirror **exactly the Otzaria structure** — the app reflects
  whatever folder tree is on the phone (see *Loading the library*).
- **MENU key** — change the library folder path.
- **`#` key** — an on-device **Help** cheat-sheet of all the controls (baked into the APK;
  English/LTR).

### Reading a book
- **Up / Down** — move through segments (each pasuk / mishnah / passage is one line).
- A **◆** at the start of a line means **meforshim are available** on that segment, for
  the commentators you have chosen (see below).
- **Center (OK)** on a ◆ line — opens all chosen meforshim on that segment, **stacked in
  one scroll** (each under its reference). Up/Down scrolls; **Back** returns.
- **Volume Up / Down** — larger / smaller font (remembered).
- The header shows a **breadcrumb**: `book · chapter · line/total · %`.

### Navigating a large sefer
Rows render lazily and the file is read in the background (the header shows `טוען…` on the
biggest seforim). Two ways to jump:
- **Type a number `0`–`100`** — jump to that **percent** of the book. The digits show live
  in the header; **Center (OK)** jumps, **Back** cancels. `0` = start, `100` = end.
- **`*` key — chapter TOC.** Opens the book's headings (perek / siman …) as an indented,
  jump-able list; **Center (OK)** scrolls the reader there. Inside the TOC you can also
  **type a number** to jump to the **Nth perek/siman** (`#`/`*` clears it). Books over
  ~1,500 lines open on the TOC first.
- **Resume** — each sefer reopens **where you left off**.

Nothing on disk is split — the `.txt` files stay byte-identical, so the meforshim link
graph (keyed by filename + line number) is unaffected. The "split" is purely in how the
reader *presents* the book.

### Choosing which meforshim appear
This is a **per-book filter**, like Otzaria's commentator list.

1. While reading, press the **`#` key** (the header shows `# = מפרשים`). *(MENU also works.
   The left soft-key is handled in code, but the XP5800 has no such key — its keylayout maps
   neither `SOFT_LEFT` nor `SOFT_RIGHT` — so on that unit `#` and MENU are the only two that
   act.)*
2. You get the **בחר מפרשים** ("choose commentators") checklist, in up to three groups:
   - **מפרשים** — the commentaries on this sefer. **On** by default.
   - **הספר שעליו זה מפרש** — if you are reading a commentary, the sefer it comments on. **On**
     by default, so pressing OK on a line of משנה ברורה shows the סעיף it discusses.
   - **קישורים נוספים** — independent works this sefer merely cross-references. **Off** by
     default.
3. **Up / Down** to move, **Center (OK)** to toggle (☑ ↔ ☐), **Volume ±** resizes.
4. **Back** — saves your choice and returns to the book.

Only **checked** entries then show up: they decide which segments get a **◆** and what
appears when you press OK. The choice is **saved per book**, and if a later re-pack adds a
commentator to that sefer, the new one appears checked rather than silently off.

### Why the commentator groups exist
Otzaria's link graph labels an edge `commentary` but **does not say which end is the
commentary**, and it stores nearly every edge **twice, once each way**. Reading the graph
naively (as Otzaria's own Flutter app does — `getAvailableCommentators` filters on the type
and nothing else) offers a book's own base text as its "commentator": e.g. opening שולחן ערוך
יורה דעה would list **ויקרא** among the commentators, and opening משנה ברורה would list
**שולחן ערוך אורח חיים**. Across the library, 6,814 of 14,058 book↔commentator pairs (48%)
were not commentaries on the book offering them.

Direction is not guessed from the title (`X על Y` would wrongly attach `רשי על ברכות` to the
Yerushalmi masechta of the same name). It is **read** from Sefaria's own per-work
`dependence` / `base_text_titles` declarations, rewritten against Otzaria's filenames into
**`tools/base_texts.json`** (checked in; regenerate with `tools/build_bases.py`). See
`tools/linkkind.py` for the four rules and its 30-case self-test. Nothing is deleted — a link
that is not a commentary is **relabelled** and shown under its own heading.

---

## Loading the library onto the phone

The phone reads a **packed** library, built on the PC by `tools/pack_library.py`:

```
/storage/emulated/0/Otzaria/
├─ אוצריא/       ← the text tree (categories → books .txt).  KEEP THE STRUCTURE.
└─ idx/          ← one <BookName>.idx per book: the meforshim sidecar
```

```powershell
python tools\pack_library.py --out D:\packed --categories תנך משנה הלכה
python tools\verify_idx.py D:\packed      # validates it before it goes near the phone
```

`verify_idx.py` re-derives everything from the bytes on disk (it does not reuse the packer's
structures) and asserts what each link *is* — e.g. that ויקרא is not a מפרש on שולחן ערוך
יורה דעה. Source data on the PC lives under `Downloads/otzaria_latest/` (the `.txt` tree +
`links/`).

### Why a packer, and not the raw `links/` JSON
Answering *"which lines of this sefer have meforshim?"* by parsing the whole `<Book>_links.json`
does not fit the phone. For שולחן ערוך אורח חיים that file is **72.4 MB** against this phone's
192 MB heap, which caused an `OutOfMemoryError` before the first row could draw (along with
בראשית, שמות, דברים, תהילים and שולחן ערוך חושן משפט).

The packer splits the two questions the reader asks:

| | raw JSON | packed `.idx` |
|---|---|---|
| open a book (draw the ◆) | parse 72.4 MB | **read 49 KB** |
| press OK on one line | the same 72.4 MB | one seek |
| links on disk | 166 MB of JSON | 12.4 MB of `.idx` |

It also drops any link whose target book is not on the phone (so a ◆ can never open to
`אין מפרשים`), and strips Sefaria's inline `<i data-commentator>` anchors — 48% of שולחן ערוך's
bytes — which also makes phrases like `יתגבר כארי` contiguous again. Because `ReaderActivity`
loads a book **whole** into the heap, this text strip meaningfully shrinks the seforim most
likely to be open:

| sefer | before | after |
|---|---|---|
| שולחן ערוך, חושן משפט | 5.45 MB | **2.19 MB** |
| שולחן ערוך, יורה דעה | 3.79 MB | **1.88 MB** |
| שולחן ערוך, אורח חיים | 3.36 MB | **1.76 MB** |
| טור | 9.13 MB | **6.32 MB** |

Use `--text-all` to re-pack an existing full library; plain `--all` ships only the linked books
and their targets (on this corpus, 981 books fewer than the phone already has).

### Pushing with adb — mind which side you name
`adb push SRC DST` behaves differently depending on whether **DST already exists**:

| | |
|---|---|
| DST does **not** exist | it is created *as* SRC. Name the full path: `push "אוצריא\משנה" ".../אוצריא/משנה"` |
| DST **does** exist | SRC is placed *inside* it → `.../אוצריא/משנה/משנה`. Name the **parent**: `push "אוצריא\משנה" ".../אוצריא"` |

Getting this wrong is silent — the push reports success, the files land one level too deep, and
the app keeps reading the old copies. Verify afterwards:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "/storage/emulated/0/Otzaria/אוצריא"

# FIRST time (nothing on the phone yet) — name the full remote path:
& $adb push "אוצריא\משנה" "$dev/משנה"

# UPDATING (the folder is already there) — name the PARENT:
& $adb push "אוצריא\משנה" $dev

# then prove it did not nest:
& $adb shell "[ -d '$dev/משנה/משנה' ] && echo NESTED-BAD || echo ok"
```

Push the packed folder, not the raw corpus. Do **not** push `links/` — the phone no longer
reads it. `pack_library.py` writes both `אוצריא/` and `idx/` into `--out`, already consistent:

```powershell
& $adb push "D:\packed\אוצריא" "/storage/emulated/0/Otzaria/אוצריא"
& $adb push "D:\packed\idx"    "/storage/emulated/0/Otzaria/idx"
```

### Sidecar version — install the APK first
The `.idx` format is at **v2** (one byte per commentator: what that book *is* to this one).
A **v1** sidecar still opens under the new APK — everything reads as a commentary, exactly what
v1 claimed — so an out-of-date library degrades to the old behaviour rather than breaking. The
reverse does not: a **v2** sidecar under an old APK is refused and the book shows no meforshim.
So **install the APK, then push `idx/`.**

---

## Project layout

Plain files, no database. See **`SPEC.md`** for the verified data format and rules.

- `tools/build_bases.py` → `tools/base_texts.json` — which sefer is a commentary on which, read
  from Sefaria's `dependence` / `base_text_titles` and rewritten against Otzaria's filenames.
  Checked in; only needed to regenerate. `tools/linkkind.py` turns that into the per-link answer
  and self-tests with `python tools/linkkind.py`.
- `tools/pack_library.py` / `verify_idx.py` — build and validate the packed `אוצריא/` + `idx/`
  library the phone reads.
- `Otzaria.kt` — data layer. Reads books line-by-line and opens `idx/<Book>.idx`, whose
  per-commentator bitmap answers *"which lines get a ◆"* from a small fixed read and whose line
  directory makes *"what is on line N"* a seek. Target paths are stored in the sidecar (no
  `filename → path` scan at runtime). Also parses `<h1..6>` headings for the TOC. Caches are
  bounded (LRU by character budget); commentary targets over ~2 MB are streamed one line at a
  time. A missing or corrupt sidecar means *no meforshim*, never a crash.
- `LibraryActivity` — folder-tree browser.
- `ReaderActivity` — one segment per row (RTL); ◆ marks segments with meforshim from the chosen
  commentators; `#`/MENU opens the picker; Volume ± sets font; typing `0`–`100` jumps by percent;
  `*` opens the chapter TOC; remembers the last place per book. Rows render lazily
  (`ReaderAdapter` in `Ui.kt`) — row N is always file line N+1, so line indices (and links) never
  move. The book is read off the main thread (header shows `טוען…`), since some seforim are tens
  of MB and reading them in `onCreate` would ANR.
- `TocActivity` — the chapter TOC from `Otzaria.headings()`; auto-picks the primary heading level
  (perek / siman) for the type-a-number jump.
- `CommentatorsActivity` — the per-book ☑/☐ filter, grouped into commentaries / the sefer this one
  comments on / other cross-references, from the sidecar's per-commentator kind byte. Persisted
  per book title together with the roster it was made against.
- `CommentaryActivity` — the chosen meforshim on one segment, stacked.
- `HelpActivity` — the on-device controls cheat-sheet (English/LTR), reached with `#` from the
  library.
- `Ui.kt` — `RowAdapter` (the shared RTL, adjustable-size list adapter) + the minimal
  `<h1>/<h2>/<h3>/<b>` → `Html.fromHtml` renderer used by every screen.
- `Settings.kt` — persisted font size, per-book commentator selection, and last place. A saved
  place is a line number into a library you may re-download, so it is stored with the first 40
  characters of its line; on reopen the text is checked, and if it moved, the reader scans ±200
  lines to find it again. Only when that fails does it go to the top — and it says so.

Delivered features and backlog live in **`CHANGELOG.md`**.
