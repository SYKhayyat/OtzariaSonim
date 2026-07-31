# אוצריא לסוֹנים — Otzaria for Sonim (keypad reader)

A tiny, **non-touch, D-pad-driven** Torah reader for rugged Sonim keypad phones
(**XP5s / XP5800**, Android 7). It reuses the Otzaria library **data as-is** — books
and the meforshim link graph — with a stripped, keypad-first UI. **Reading + meforshim
linking** is the whole point; full-text search is intentionally left out to save space.

It is a native Kotlin app (plain Android Views, zero AndroidX/native libs) → ~850 KB APK,
runs on Android 7+ (minSdk 24), 32-bit or 64-bit. Verified running on a **Sonim XP5800,
Android 7.1.2**.

| Library | Reader (◆ = has meforshim) | Meforshim (stacked) | Choose meforshim (`#`) |
|---|---|---|---|
| ![library](docs/01-library.png) | ![reader](docs/02-reader.png) | ![meforshim](docs/03-meforshim.png) | ![picker](docs/04-picker.png) |

---

## How to use it (controls)

Everything is the **D-pad + center (OK) + Back**, plus a couple of number/volume keys.

### Browsing the library
- **Up / Down** — move the highlight.
- **Center (OK)** — open the folder or book.
- **Back** — go up one folder (at the top it exits the app).
- The categories and folders are **exactly the Otzaria structure** — the app just mirrors
  whatever folder tree is on the phone (see *Loading the library* below).
- **MENU key** (on the library screen) — change the library folder path.
- **`#` key** (on the library screen) — an on-device **Help** cheat-sheet of all the
  controls (baked into the APK; English/LTR).

### Reading a book
- **Up / Down** — move through the segments (each pasuk / mishnah / passage is one line).
- A **◆** at the start of a line means **meforshim are available** on that segment
  (for the commentators you've chosen — see next).
- **Center (OK)** on a ◆ line — opens all your chosen meforshim on that segment,
  **stacked in one scroll** (each under its reference). Up/Down scrolls; **Back** returns.
- **Volume Up / Down** — bigger / smaller font (remembered).
- The header shows a **breadcrumb**: `book · chapter · line/total · %`, so you always
  know where you are.

### 👉 Getting around a big sefer (fast navigation)
Large books (a full tractate, ערוך השולחן, בית יוסף…) used to be slow to open and a
chore to scroll. Rows now render lazily and the file is read in the background (the
header shows `טוען…` for a moment on the biggest seforim), and there are two ways to
jump:
- **Type a number `0`–`100`** — jump to that **percent** of the book. The digits show
  **live in the header** as you type; **Center (OK)** jumps there, **Back** cancels and
  leaves you where you were. `0` = the very start, `100` = the end.
- **`*` key — chapter TOC.** Opens the book's headings (perek / siman …) as an indented,
  jump-able list; **Center (OK)** on one scrolls the reader there. Inside the TOC you can
  also **type a number** to jump straight to the **Nth perek/siman** (`#`/`*` clears it).
  Books over ~1,500 lines open **on the TOC first**.
- **Resume** — each sefer reopens **where you left off**.

Nothing on disk is split — the `.txt` files stay byte-identical, so the meforshim link
graph (keyed by filename + line number) is completely unaffected. The "split" is purely
in how the reader *presents* the book.

### 👉 Choosing WHICH meforshim appear  (this is the part that was unclear)
This is a **per-book filter**, just like Otzaria's commentator list.

1. While reading a book, press the **`#` key** (the header shows `# = מפרשים`).
   *(MENU also works. The left soft-key is handled in code but the XP5800 has no
   such key — its keylayout maps neither `SOFT_LEFT` nor `SOFT_RIGHT` — so on that
   unit `#` and MENU are the only two that do anything.)*
2. You get the **בחר מפרשים** ("choose commentators") checklist — every commentator that
   exists for **this book**, each with a ☑ / ☐ box. By default they all start **checked**.
3. **Up / Down** to move, **Center (OK)** to toggle a commentator on/off (☑ ↔ ☐).
4. **Back** — saves your choice and returns to the book.

From then on, only the **checked** commentators show up: they decide which segments get a
**◆** and what appears when you press OK on a segment. The choice is **saved per book**, so
each sefer remembers its own set.

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
python tools\verify_idx.py D:\packed      # checks it before it goes near the phone
```

Source data on the PC: `Downloads/otzaria_latest/` (the `.txt` tree + `links/`).

### Why a packer, and not the raw `links/` JSON
The app used to answer *"which lines of this sefer have meforshim?"* by parsing the
whole `<Book>_links.json`. For שולחן ערוך אורח חיים that is **72.4 MB** against this
phone's 192 MB heap, so the app died with an `OutOfMemoryError` before drawing a row —
along with בראשית, שמות, דברים, תהילים and שולחן ערוך חושן משפט.

The packer splits the two questions the reader actually asks:

| | before | after |
|---|---|---|
| open a book (draw the ◆) | parse 72.4 MB | **read 49 KB** |
| press OK on one line | the same 72.4 MB | one seek |
| links on disk | 166 MB of JSON | 12.4 MB of `.idx` |

It also drops any link whose target book is not on the phone, so a ◆ can no longer
open to `אין מפרשים`, and strips Sefaria's inline `<i data-commentator>` anchors —
48% of שולחן ערוך's bytes — which also makes phrases like `יתגבר כארי` contiguous
again.

### ⚠️ Preserve the folder structure
The categories you see are just the folders. Copy Otzaria's tree **intact** — do **not**
flatten it. There's an `adb push` gotcha: pushing a subfolder *into an existing folder*
drops a level. Push each **category** to a matching path, e.g.:

```powershell
# adb from PowerShell (Git Bash mangles the /storage/... paths — use PowerShell)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
cd C:\Users\Administrator\Downloads\otzaria_latest

# one category, structure preserved (note the explicit ...\<Category> on the remote side):
& $adb push "אוצריא\משנה"       "/storage/emulated/0/Otzaria/אוצריא/משנה"
& $adb push "אוצריא\תנך"        "/storage/emulated/0/Otzaria/אוצריא/תנך"
& $adb push "אוצריא\תלמוד בבלי" "/storage/emulated/0/Otzaria/אוצריא/תלמוד בבלי"
& $adb push "אוצריא\הלכה"       "/storage/emulated/0/Otzaria/אוצריא/הלכה"
# ...and any other categories you want (Rambam/Tur/Beis Yosef live under הלכה).
```

Or, to get the **entire** Otzaria structure in one shot (large — the full tree is ~4 GB of
text; pick a subset to stay near your ~1.4 GB budget):

```powershell
& $adb push "אוצריא" "/storage/emulated/0/Otzaria/אוצריא"
```

### Sidecars
Don't push `links/` at all — the phone no longer reads it. `pack_library.py` writes
both `אוצריא/` and `idx/` into its `--out` folder, already consistent with each other:
it ships every commentary target the chosen books reference, and indexes only links
whose target it shipped. Push the packed folder, not the raw corpus:

```powershell
& $adb push "D:\packed\אוצריא" "/storage/emulated/0/Otzaria/אוצריא"
& $adb push "D:\packed\idx"    "/storage/emulated/0/Otzaria/idx"
```

### First run
```powershell
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb shell pm grant com.otzaria.sonim android.permission.READ_EXTERNAL_STORAGE
```
Launch **אוצריא** from the phone's app menu. If it says the folder isn't found, press
**MENU** on the library screen and set the path.

---

## Building the APK

Everything needed is on this PC (Android Studio, SDK, adb, a connected device).

**Android Studio:** open the `OtzariaSonim` folder, let it sync, Run.

**Command line (PowerShell):**
```powershell
cd C:\Users\Administrator\Videos\OtzariaSonim
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
# APK -> app\build\outputs\apk\debug\app-debug.apk
```
Config: AGP 8.11.1 · Kotlin 2.0.21 · Gradle 8.14.3 · compileSdk 36 · **minSdk 24**.

---

## How it works (for future me)

Plain files, no database. See **`SPEC.md`** for the verified data format and rules.

- `Otzaria.kt` — data layer. Reads books line-by-line and opens `idx/<Book>.idx`, whose
  per-commentator bitmap answers *"which lines get a ◆"* from a small fixed read and
  whose line directory makes *"what is on line N"* a seek. Target paths are stored in
  the sidecar, so there is no `filename → path` scan at runtime (that scan used to cost
  4.7 s on the first meforshim lookup of every session). Also parses `<h1..6>` headings
  for the TOC. Caches are **bounded** (LRU by character budget) and commentary targets
  over ~2 MB are **streamed one line at a time**, so opening meforshim never loads a
  34 MB file whole. A missing or corrupt sidecar means *no meforshim*, never a crash.
- `LibraryActivity` — folder-tree browser.
- `ReaderActivity` — one segment per row (RTL), ◆ marks segments with meforshim from the
  chosen commentators; `#`/MENU opens the picker; Volume ± = font; **typing a number
  0–100 jumps to that percent** (shown live in the header, OK jumps / Back cancels),
  **`*` opens the chapter TOC**, and it **remembers your last place** per book. Rows
  are rendered **lazily** (see `ReaderAdapter` in `Ui.kt`) — row N is always file line
  N+1, so line indices (and links) never move. The book is read **off the main thread**
  (the header shows `טוען…` until it lands), because ערוך השולחן is 31 MB / 26,776 lines
  and reading that in `onCreate` is an ANR waiting for a slower card.
- `TocActivity` — the chapter TOC: `Otzaria.headings()` parses the `<h1..6>` headings into
  an indented, jump-able list (the "virtual split"). Auto-picks the primary heading level
  (perek / siman) for the type-a-number jump.
- `CommentatorsActivity` — the per-book ☑/☐ commentator filter (persisted per book title).
- `HelpActivity` — an on-device controls cheat-sheet (English/LTR), reached with `#` from
  the library. The text is baked into the class, rendered with the same `<h1>/<b>` renderer.
- `CommentaryActivity` — the chosen meforshim on one segment, stacked.
- `Ui.kt` — `RowAdapter` (the shared RTL, adjustable-size list adapter) + the minimal
  `<h1>/<h2>/<h3>/<b>` → `Html.fromHtml` renderer used by every screen.
- `Settings.kt` — persisted font size and per-book commentator selection.

### Backlog
- Optional: mount the larger **org** corpus as read-only books (no meforshim).
- A "recently read" list on the library screen (the per-book last-place is already stored).
- Font size in the commentator picker (`#`) — the reader has it, that screen doesn't.
- Swap the packer's `--source` to Girsa's corpus once its link graph is unioned; the
  `.idx` format doesn't change. See `tools/girsa_coverage.py` for why not yet.

*(Done: lazy big-book loading, chapter TOC, %-jump, resume-last-place, the binary
links index, off-main-thread book loading.)*
