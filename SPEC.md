# Otzaria Sonim — spec & verified facts

A minimal, D-pad-driven, non-touch Hebrew sefer reader for the **Sonim XP5s**
(Android 7 / API 24-25, ~240x320 non-touch screen, D-pad + center + back,
Snapdragon 210 = 32-bit armeabi-v7a only, ~2GB RAM).

Goal: **reading + meforshim linking**. Search intentionally dropped.

## Why native Android (not Flutter, not Zayit)
- Zayit (`Downloads/Zayit-master`) is a **desktop-only** Compose-Multiplatform app
  (Android target commented out) backed by a **SQLite DB** — wrong base for the phone.
- Otzaria (the Flutter app) is touch-first, but its **data** is trivially reusable.
- A weak, non-touch, 32-bit, tiny-screen device is exactly what **classic native
  Android Views** were built for: D-pad focus traversal is free and battle-tested.
  No native libs needed for a text reader, so 32-bit is a non-issue.

## Data source (VERIFIED)
Root = `Downloads/otzaria_latest/` (the txt tree), NOT the org tree (`Downloads/seforim/`).
Reason: the meforshim **link graph only exists for the txt tree** and is line-index +
filename coupled to those txt files. The org files are restructured (TOC, `#+TITLE`,
`[[#sec-0001]]` anchors) so line indices don't align; org has links for only a handful
of modern books. Org = broader *reading* corpus (22.5k files) but wrong linking substrate.

Layout on disk:
```
<root>/
  אוצריא/            # texts: category folders -> .txt books (6618 files, ~4.0 GB full)
  links/             # <BookTitle>_links.json (5819 files, ~2.3 GB full)
  (metadata.json)    # optional, ABSENT in this export — not required
```

### Book text format (.txt), one segment per line
```
<h1>משנה ברכות</h1>          # line 1  book title
<h2>פרק א</h2>               # line 2  chapter heading
(א) מֵאֵימָתַי קוֹרִין ...    # line 3  segment (mishnah / pasuk), with nikud
...
```
- Minimal HTML inline: `<h1> <h2> <h3> <b>`. Render with `Html.fromHtml`, RTL, big font.
- UTF-8. Line N (1-based) == list position N-1 (0-based).

### Links format (`links/<BookTitle>_links.json`)
JSON array; each entry (note the misspelled key "Conection Type"):
```json
{ "line_index_1": 3.0,                         // 1-based line in THIS book
  "heRef_2": "רמב\"ם על משנה ברכות א, א, א,",   // human ref of the target
  "path_2": "אוצריא\\משנה\\...\\רמבם על משנה ברכות.txt",  // STALE dir, Windows backslashes
  "line_index_2": 5.0,                          // 1-based line in target file
  "Conection Type": "commentary" }              // commentary | targum | ... 
```
Values may be `3.0` numeric OR string "3.0" — parse as `substringBefore('.').toInt()`.

### CRITICAL resolution rule (matches original app)
`path_2` directory parts are **STALE** (folder was renamed `ראשונים על המשנה` -> `ראשונים`).
The original app resolves links **by filename/title, not by full path**:
`bookPath = titleIndex[ titleFromTitle(basename(path_2) without ".txt") ]`.
So: build a `filename(no .txt) -> absolute path` index by scanning `אוצריא/`, and
resolve every commentary target through that index. `title = last path segment minus .txt`.

### Meforshim on a segment (VERIFIED end-to-end)
To show commentaries on the segment at book line N:
1. filter links where `line_index_1 == N` and `Conection Type` in {commentary, targum}
2. for each, `target = titleIndex[ titleFromPath(path_2) ]`; content = line `line_index_2`
   of that target file (1-based).
Verified: Mishnah Berakhot line 3 ("מאימתי קורין את שמע בערבית") ->
Rambam file line 5 ("מאימתי קורין את שמע בערבין וכו': כבר בארנו...") = correct.

## v1 library scope (user pick)
Tanach, Mishnah, Talmud Bavli, Halacha (Shulchan Aruch), Rambam, Tur, Beis Yosef +
their meforshim. Budget ~1.4 GB acceptable. Subset the shipped `אוצריא/` + `links/`
accordingly; size = ~622 KB/book avg. Full corpus is 6.3 GB (don't ship all).

## Device / storage
- Library on **internal storage**, path **configurable** (SharedPreferences).
  Default root: `/storage/emulated/0/Otzaria` (contains `אוצריא/` and `links/`).
- Push via adb:  `adb push otzaria_latest/. /storage/emulated/0/Otzaria/`  (subset first).
- API 24 => request `READ_EXTERNAL_STORAGE` at runtime.

## App structure (native Kotlin, Views, zero AndroidX)
- `Otzaria.kt`      data layer: title index, read book lines, load+resolve links.
- `LibraryActivity` browse `אוצריא/` folder tree; D-pad up/down, center=open, back=up.
- `ReaderActivity`  list of segments (Html, RTL, big); ◆ marks segments with meforshim;
                    center on a segment -> CommentaryActivity; back=library.
- `CommentaryActivity` all meforshim on the chosen segment (ref + resolved text), scrollable.
- Plain `android.app.Activity` + `ListView` (default D-pad focus). Theme = Material.Light.NoActionBar.

## Build (this machine)
- Android Studio installed: `C:\Program Files\Android\Android Studio` (JBR bundled).
- SDK: `C:/Users/Administrator/AppData/Local/Android/Sdk` (platforms 33/36/36.1, build-tools 36.0.0).
- AGP 8.11.1 / Kotlin 2.0.21 / Gradle wrapper 8.14.3 / compileSdk 36 / minSdk 24.
- Device already connected (adb id `37b8264c`).
- Open in Android Studio (regenerates the Gradle wrapper) OR run once:
  `gradle wrapper --gradle-version 8.14.3` then `gradlew assembleDebug`.
- Install:  `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Backlog (post-v1)
- Font-size control; jump-to-chapter (parse `<h2>` TOC); bookmarks/history.
- Optional preprocessing: flatten each book + its meforshim into a compact per-book
  bundle and shrink the 2.3 GB of JSON links to a small binary index.
- Optional: org-only books as read-only (no meforshim) for extra breadth.
