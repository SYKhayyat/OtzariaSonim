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
chore to scroll. They now open **instantly** (rows render lazily) and there are two ways
to jump:
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
   *(The left soft-key and MENU also work, in case `#` differs on your unit.)*
2. You get the **בחר מפרשים** ("choose commentators") checklist — every commentator that
   exists for **this book**, each with a ☑ / ☐ box. By default they all start **checked**.
3. **Up / Down** to move, **Center (OK)** to toggle a commentator on/off (☑ ↔ ☐).
4. **Back** — saves your choice and returns to the book.

From then on, only the **checked** commentators show up: they decide which segments get a
**◆** and what appears when you press OK on a segment. The choice is **saved per book**, so
each sefer remembers its own set. Volume Up/Down changes font size here too.

---

## Loading the library onto the phone

The app reads plain files from a folder on the phone (default
`/storage/emulated/0/Otzaria`, changeable via the library screen's MENU key):

```
/storage/emulated/0/Otzaria/
├─ אוצריא/       ← the Otzaria text tree (categories → books .txt).  KEEP THE STRUCTURE.
└─ links/        ← the meforshim link files: <BookName>_links.json
```

Source data on the PC: `Downloads/otzaria_latest/` (the `.txt` tree + `links/`).

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

### Links
The `links/` files are named `<BookName>_links.json`. Push the ones for the books you
loaded (a commentary is resolved **by filename**, so the target commentary book just needs
to be present somewhere under `אוצריא/`). To push all links for a category's base books,
e.g. Mishnah:

```powershell
Get-ChildItem "links" -Filter "משנה *_links.json" | ForEach-Object {
  & $adb push $_.FullName "/storage/emulated/0/Otzaria/links/$($_.Name)"
}
```

For simplicity you can also push the **entire** `links/` folder (~2.3 GB) once:
`& $adb push "links" "/storage/emulated/0/Otzaria/links"`.

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

- `Otzaria.kt` — data layer. Scans `אוצריא/` into a `filename → path` index; reads books
  line-by-line; loads `<Book>_links.json`; resolves each commentary **by filename** (the
  paths stored in the JSON are stale) to the right line of the target file. Also parses
  `<h1..6>` headings for the TOC. Caches are **bounded** (LRU by character budget) and
  commentary targets over ~2 MB are **streamed one line at a time**, so opening meforshim
  never loads a 34 MB file whole.
- `LibraryActivity` — folder-tree browser.
- `ReaderActivity` — one segment per row (RTL), ◆ marks segments with meforshim from the
  chosen commentators; `#`/MENU opens the picker; Volume ± = font; **typing a number
  0–100 jumps to that percent** (shown live in the header, OK jumps / Back cancels),
  **`*` opens the chapter TOC**, and it **remembers your last place** per book. Rows
  are rendered **lazily** (see `ReaderAdapter` in `Ui.kt`) so even a 34 MB sefer opens
  instantly — row N is always file line N+1, so line indices (and links) never move.
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
- Optional preprocessing to shrink the `links/` JSON to a compact binary index.
- Optional: mount the larger **org** corpus as read-only books (no meforshim).
- A "recently read" list on the library screen (the per-book last-place is already stored).

*(Done: lazy big-book loading, chapter TOC, %-jump, resume-last-place.)*
