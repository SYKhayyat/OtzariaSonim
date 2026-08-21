# Troubleshooting

Most problems with this app are not in the app. They are in the **library on the
phone** — packed wrong, pushed to the wrong path, or out of step with the APK.
Start there.

Three checks, in this order:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = "/storage/emulated/0/Otzaria"

# 1. Is the library where the app looks?
& $adb shell "ls '$root'"                  # expect: אוצריא  idx

# 2. Did a push nest a folder inside itself?
& $adb shell "[ -d '$root/אוצריא/אוצריא' ] && echo NESTED-BAD || echo ok"

# 3. Are there sidecars at all?
& $adb shell "ls '$root/idx' | head"
```

On the PC, before anything reaches the phone:

```powershell
python tools\verify_idx.py D:\packed
```

It re-derives everything from the bytes on disk rather than reusing the packer's
structures, and asserts what each link actually *is*.

---

## Contents

- [The library](#the-library)
- [Meforshim](#meforshim)
- [Reading](#reading)
- [Packing the library](#packing-the-library)
- [Pushing with adb](#pushing-with-adb)
- [Building the APK](#building-the-apk)
- [Controls](#controls)

---

## The library

### "The folder is not found" on first launch

Two causes, and both are normal on a fresh install:

1. **Nothing has been pushed yet.** See
   [Pushing with adb](#pushing-with-adb).
2. **The path is not what the app expects.** Press **MENU** on the library
   screen and set it.

The default is:

```
/storage/emulated/0/Otzaria/
├─ אוצריא/    the text tree (categories -> books .txt)
└─ idx/       one <BookName>.idx per book: the meforshim sidecar
```

### The library screen is empty, or shows only some categories

**Structure was not preserved.** The categories and folders mirror the Otzaria
structure exactly, so a flattened or re-arranged tree does not browse.

Push the packer's output as-is. `pack_library.py` writes both `אוצריא/` and
`idx/` into `--out`, already consistent with each other.

Also check whether you limited the pack:

```powershell
python tools\pack_library.py --out D:\packed --categories תנך משנה הלכה
```

That ships three categories and nothing else.

### Storage permission

```powershell
& $adb shell pm grant com.otzaria.sonim android.permission.READ_EXTERNAL_STORAGE
```

Without it the app cannot read the library regardless of where it is.

### I pushed an update and the app still shows the old text

Almost certainly a **nested folder** — see
[Pushing with adb](#a-push-succeeded-and-the-app-did-not-change). The push
reported success, the files landed one level too deep, and the app kept reading
the old copies.

## Meforshim

### A book shows no ◆ at all

Work down this list:

1. **Is there a sidecar for it?**
   ```powershell
   & $adb shell "ls '/storage/emulated/0/Otzaria/idx' | grep <BookName>"
   ```
   A **missing or corrupt sidecar means no meforshim, never a crash** — that is
   deliberate, and it is why this failure is silent.

2. **Is the sidecar newer than the APK?** The `.idx` format is at **v2**. A
   **v2 sidecar under an old APK is refused** and the book shows no meforshim.
   The reverse is fine — a v1 sidecar under the new APK opens and everything
   reads as a commentary, exactly what v1 claimed.

   **So: install the APK first, then push `idx/`.** That ordering is the whole
   rule.

3. **Are all the commentators unchecked?** Press `#` or MENU in the reader and
   look at the picker.

4. **Does the book genuinely have no links?** Many do not. 891 of the 933
   Otzaria-only books have no links at all.

### A commentator I expected is missing from the picker

Two likely reasons, and the second is a feature:

**The target book is not on the phone.** The packer drops any link whose target
book is not present, so a ◆ can never open to `אין מפרשים`. If you packed a
subset with `--categories`, you dropped links into everything outside it.

**The link was pointing the wrong way.** Otzaria's link graph labels an edge
`commentary` and does not say which end is the commentary — and it stores nearly
every edge **twice, once in each direction**. Links are now oriented from
Sefaria's `dependence` / `base_text_titles` declarations, so a book is only
offered as a commentator where it actually is one.

Previously 6,814 of 14,058 book-commentator pairs — **48%** — were mislabelled.
If you remember seeing a "commentator" that made no sense, that is why it is
gone.

### The picker groups things oddly

Three groups, from the sidecar's per-commentator kind byte:

- **commentaries** — works that comment on this one
- **the sefer this one comments on** — the base text
- **other cross-references**

If everything is in the first group, you are running a **v1 sidecar**: v1 had no
kind byte, so everything reads as a commentary. Re-pack.

### A commentator appeared unchecked after I re-packed

It should not. The per-book selection is persisted **together with the roster it
was chosen against**, so a later re-pack that adds a commentator shows it
*checked* rather than silently off.

If a newly added commentator is off, that is worth reporting.

### `verify_idx.py` fails

Good — that is the point. It asserts what each link *is*, for example that ויקרא
is not a מפרש on שולחן ערוך יורה דעה.

Read the assertion it printed. Almost always the source corpus under
`Downloads/otzaria_latest/` is stale or partial, or `tools/base_texts.json` is
out of date for a book that was renamed.

```powershell
python tools\linkkind.py        # self-tests the per-link answer
python tools\build_bases.py     # regenerate base_texts.json (rarely needed)
```

## Reading

### A large sefer takes a while to open

Expected, and the header says `טוען…` while it happens. The book is read **off
the main thread** deliberately — some seforim are tens of megabytes, and reading
one in `onCreate` would ANR.

Rows render lazily. Row N is always file line N+1, so line indices and links
never move.

### The app ran out of memory

This is what the packed sidecars exist to prevent. Answering "which lines of
this sefer have meforshim?" by parsing the whole `<Book>_links.json` does not
fit the phone — for שולחן ערוך אורח חיים that file is **72.4 MB** against a
**192 MB heap**.

If you are hitting OOM today, check that you pushed the packed library and
**not** the raw corpus:

- **Do not push `links/`.** The phone no longer reads it.
- Push `D:\packed\אוצריא` and `D:\packed\idx`, nothing else.

The packer also strips Sefaria's inline `<i data-commentator>` anchors — 48% of
שולחן ערוך's bytes — which matters because `ReaderActivity` loads a book whole:

| sefer | before | after |
|---|---|---|
| שולחן ערוך, חושן משפט | 5.45 MB | **2.19 MB** |
| שולחן ערוך, יורה דעה | 3.79 MB | **1.88 MB** |
| שולחן ערוך, אורח חיים | 3.36 MB | **1.76 MB** |
| טור | 9.13 MB | **6.32 MB** |

Commentary targets over about 2 MB are streamed one line at a time, and caches
are bounded LRU by character budget.

### It reopened at the top and said so

Working as designed. A saved place is a line number into a library you may
re-download, so it is stored with **the first 40 characters of its line**. On
reopen the text is checked; if it moved, the reader scans ±200 lines to find it
again.

Only when that fails does it go to the top — **and it tells you**, rather than
silently losing your place.

If it happens constantly, your library is changing between sessions by more than
200 lines. Re-pack once and stop.

### A sefer's title is missing from its own table of contents

Fixed — heading parsing is BOM-tolerant. **190** books in the corpus carry a
byte-order mark and **185** of them were losing a heading to it.

If you still see it, you may be running an older APK against a current library.

### The chapter TOC jump does not work the way I expect

`*` opens the TOC, parsed from `<h1..6>` headings. It **auto-picks the primary
heading level** — perek or siman — for the type-a-number jump. A sefer whose
headings are nested unusually may pick a level you did not want.

Separately, typing `0`–`100` in the reader jumps by **percent**, which is the
fallback for a book with no useful headings.

## Packing the library

Source data on the PC lives under `Downloads/otzaria_latest/` — the `.txt` tree
plus `links/`.

```powershell
python tools\pack_library.py --out D:\packed --categories תנך משנה הלכה
python tools\verify_idx.py D:\packed
```

### Which flag do I want?

| Flag | Ships |
|---|---|
| `--categories <names>` | only those categories |
| `--all` | the linked books and their targets only — on this corpus, **981 books fewer** than the phone already has |
| `--text-all` | re-pack an existing full library, keeping every book |

`--all` is the one people mean when they say "everything", and it is smaller
than they expect, because a book with no links and no incoming links is not
useful to this app.

### The pack is much bigger than expected

You probably used `--text-all`. Also check you are not copying `links/` — 166 MB
of JSON becomes 12.4 MB of `.idx`.

### Packing is slow

It parses the whole link graph once. That is the cost that buys a 49 KB read
when opening a book instead of a 72.4 MB parse.

## Pushing with adb

### A push succeeded and the app did not change

**This is the most common failure in the whole project, and it is silent.**

`adb push SRC DST` behaves differently depending on whether **DST already
exists**:

| | |
|---|---|
| DST does **not** exist | it is created *as* SRC. Name the full remote path. |
| DST **does** exist | SRC is placed *inside* it, giving `.../אוצריא/משנה/משנה`. Name the **parent**. |

The push reports success either way. The files land one level too deep and the
app keeps reading the old copies.

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

**Always run the verification line.** It costs nothing and it is the only way to
see this failure.

### Pushing the whole packed library

```powershell
& $adb push "D:\packed\אוצריא" "/storage/emulated/0/Otzaria/אוצריא"
& $adb push "D:\packed\idx"    "/storage/emulated/0/Otzaria/idx"
```

Install the APK **before** pushing `idx/`. See
[sidecar version](#a-book-shows-no--at-all).

### Hebrew paths are mangled

Use PowerShell with the quoting shown above. If a path arrives corrupted, check
your console code page — the tools and the app both expect UTF-8 filenames.

### `adb devices` is empty or `unauthorized`

Enable Developer Options and USB debugging on the handset, then accept the
authorisation prompt.

## Building the APK

### Requirements

Android Studio (or the Android SDK plus build tools), **JDK 17**, and `adb` to
install.

Toolchain: AGP 8.11.1 · Kotlin 2.0.21 · Gradle 8.14.3 · compileSdk 36 ·
**minSdk 24** · JDK 17.

```powershell
cd OtzariaSonim
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
# APK -> app\build\outputs\apk\debug\app-debug.apk
```

macOS and Linux: `./gradlew assembleDebug`.

### The wrong JDK

`JAVA_HOME` must point at a JDK 17. Android Studio's bundled JBR works and is
the path shown above.

### Install fails

```powershell
& $adb install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb shell pm grant com.otzaria.sonim android.permission.READ_EXTERNAL_STORAGE
```

`-r` reinstalls over an existing copy. A signature mismatch between debug and
release builds needs an uninstall first, which loses saved font size,
commentator selections and last places.

### Tests

```powershell
.\gradlew.bat test
```

Four test classes: `AccessibleNamesTest`, `BookIndexTest`, `HeadingsTest`,
`ResumeTest`.

`AccessibleNamesTest` exists for a specific reason worth knowing — this app has
**no** `setOnClickListener` calls, **no** `ImageView`s and **no** layout XML at
all. Every screen is one `ListView` of `TextView`s, and a `TextView` is
announced by its own text. What was genuinely unnamed were the four `ListView`s
and the three glyphs the app leans on (`◆`, `☑`/`☐`, `📁`), none of which a
screen reader can render as meaning.

The fix was `Ui.list(ctx, name)`, which makes an unnamed list impossible to
*write*. If that test fails, you added a list without a name.

## Controls

Everything is the **D-pad + Center (OK) + Back**, plus a few number and volume
keys. The on-device cheat-sheet is `#` from the library screen.

### Library

| Key | Action |
|---|---|
| Up / Down | Move the highlight |
| Center (OK) | Open the folder or book |
| Back | Up one folder; at the top, exit |
| MENU | Set the library path |
| `#` | Help |

### Reader

| Key | Action |
|---|---|
| Center (OK) | Open the meforshim on this segment |
| `#` or MENU | Choose which meforshim appear |
| `*` | Chapter TOC |
| `0`–`100` typed | Jump to that percent of the book |
| Volume ± | Font size (persisted) |

### A key does nothing

The help screen (`#` from the library) is the on-device reference and is
English/LTR deliberately. If a key genuinely does nothing on a handset other
than an XP5s/XP5800, its key code may differ — this app is verified on a **Sonim
XP5800, Android 7.1.2**.

---

## Reporting something not on this page

Include the handset and Android version, the APK build, and:

```powershell
& $adb shell "ls /storage/emulated/0/Otzaria"
& $adb shell "ls /storage/emulated/0/Otzaria/idx | wc -l"
python tools\verify_idx.py <your packed dir>
```

For a meforshim problem, name the sefer and the commentator you expected.
