"""
Pack the Otzaria corpus into a phone-ready library for OtzariaSonim.

Why this exists
---------------
The app used to answer "which lines of this sefer have meforshim?" by parsing the
whole `<Book>_links.json` on the main thread. For שולחן ערוך אורח חיים that file
is 72.4 MB of `\\u05d0`-escaped JSON against a 192 MB heap, so the app died with
an OutOfMemoryError before drawing a row.

The fix is not a smaller file. It is noticing that the reader asks two different
questions and only one of them is expensive:

  1. on open  -- "which of these 4,871 lines get a diamond?"  (every line, no text)
  2. on OK    -- "what does the Mishnah Berurah say on line 636?"  (one line)

So each book gets a `.idx` sidecar with those two questions in separate sections:
a per-commentator bitmap answers (1) from a ~49 KB read, and a line directory
lets (2) be a seek. Nothing is ever parsed whole on the phone.

Sourcing
--------
Link *data* comes from Otzaria, because Girsa's ingested graph resolves far fewer
commentators (see tools/girsa_coverage.py). Link *direction* comes from Sefaria's
own declarations by way of tools/base_texts.json, because Otzaria's graph does not
carry one -- see tools/linkkind.py for what that fixes and why the title is never
parsed to guess it.

Usage
-----
    python tools/pack_library.py --out D:\\packed --categories משנה תנך
    python tools/pack_library.py --out D:\\packed --all
    python tools/pack_library.py --out D:\\packed --books "משנה ברכות" "בראשית"
    python tools/pack_library.py --out D:\\packed --all --idx-only
"""
from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
import time

import linkkind

sys.stdout.reconfigure(encoding="utf-8")

OTZARIA = r"C:\Users\Administrator\Downloads\otzaria_latest"
TEXTS_DIRNAME = "אוצריא"

MAGIC = b"OZSI"
# v2 adds one byte per commentator to the commentator table: what that book is to
# this one (linkkind.MEFARESH / BASE / RELATED). The header is byte-identical to
# v1 -- the section offsets after the commentator table are computed, so widening
# an entry moves them without changing the layout. A v1 sidecar therefore still
# parses under a v2 reader (everything reads as MEFARESH, i.e. the old
# behaviour); a v2 sidecar under a v1 reader is rejected outright and the book
# shows no meforshim. Push the APK before the idx, not after.
VERSION = 2
# every multi-byte field is big-endian, matching the Kotlin reader in Otzaria.kt
HEADER = ">4sBIHIIIIII"          # magic, ver, nLines, nComm, 5 offsets, nEntries
HEADER_SIZE = struct.calcsize(HEADER)     # 35
ENTRY = ">HII"                   # commentator index, target line, ref offset
ENTRY_SIZE = struct.calcsize(ENTRY)
LINEDIR = ">IH"                  # first entry index, count
LINEDIR_SIZE = struct.calcsize(LINEDIR)

# Otzaria's text carries Sefaria's inline commentary anchors -- 48% of שולחן ערוך
# אורח חיים's bytes. They render as empty italic spans, and they split phrases:
# "יתגבר כארי" is not contiguous in that sefer until they are removed. Note the
# loose match: 8 anchors in SA O.C. are missing their opening quote
# (`data-commentator=Mishnah Berurah"`), an upstream defect, so a strict
# attribute match leaves those behind.
ANCHOR_RX = re.compile(r"<i\s+data-commentator=[^>]*>\s*</i>")


def title_from_path(p: str) -> str:
    """Otzaria resolves link targets by filename; path_2's directories are stale."""
    name = p.replace("\\", "/").rsplit("/", 1)[-1]
    return name[:-4] if name.lower().endswith(".txt") else name


def parse_index(v) -> int:
    """Line indices ship as 913.0, sometimes as the string "913.0"."""
    if isinstance(v, (int, float)):
        return int(v)
    if isinstance(v, str):
        head = v.split(".")[0].strip()
        return int(head) if head.lstrip("-").isdigit() else 0
    return 0


def is_commentary(kind: str) -> bool:
    return kind in ("commentary", "targum")


def scan_texts(root: str) -> dict[str, str]:
    """filename (no .txt) -> absolute path, over the whole text tree."""
    out: dict[str, str] = {}
    dupes = 0
    for dirpath, _, files in os.walk(os.path.join(root, TEXTS_DIRNAME)):
        for f in files:
            if f.lower().endswith(".txt"):
                key = f[:-4]
                if key in out:
                    dupes += 1
                else:
                    out[key] = os.path.join(dirpath, f)
    if dupes:
        print(f"  [warn] {dupes} duplicate filenames in the corpus; first wins", file=sys.stderr)
    return out


def load_links(root: str, book: str):
    """[(srcLine, targetTitle, targetLine, ref)] for commentary/targum edges."""
    p = os.path.join(root, "links", book + "_links.json")
    if not os.path.isfile(p):
        return []
    with open(p, encoding="utf-8") as fh:
        raw = json.load(fh)
    out = []
    for e in raw:
        kind = e.get("Conection Type", e.get("Connection Type", ""))
        if not is_commentary(kind):
            continue
        s = parse_index(e.get("line_index_1"))
        t = parse_index(e.get("line_index_2"))
        if s < 1 or t < 1:
            continue
        out.append((s, title_from_path(e.get("path_2", "")), t, e.get("heRef_2", "")))
    del raw
    return out


def emit_text(src: str, dst: str) -> int:
    """Copy a book, stripping inline anchors. Line COUNT is never changed -- row N
    must stay file line N+1 or every link in the corpus moves. That invariant is
    also what makes --idx-only safe against an unpacked corpus."""
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    n = 0
    # `utf-8-sig` eats a leading BOM if there is one and is a no-op otherwise. On
    # the order of 150 of the 6,618 books start with U+FEFF, which pushes their
    # `<h1>` title out of reach of a `^<h` anchor and leaves the sefer's own name
    # missing from its TOC (BUILDER.md S4). The reader tolerates it too, for an
    # unpacked corpus; this stops it reaching a packed one at all.
    with open(src, encoding="utf-8-sig") as fi, \
            open(dst, "w", encoding="utf-8", newline="\n") as fo:
        for line in fi:
            fo.write(ANCHOR_RX.sub("", line.rstrip("\n")) + "\n")
            n += 1
    return n


def count_lines(path: str) -> int:
    n = 0
    with open(path, encoding="utf-8") as fh:
        for _ in fh:
            n += 1
    return n


def build_idx(book: str, links, line_count: int, rel_of: dict[str, str],
              bases: linkkind.Bases, tally: dict[int, int] | None = None) -> bytes | None:
    """The sidecar. Sections, in order:

        header
        commentators   name + relative path + kind of each target book
        marks          nComm bitmaps of nLines bits -- answers the diamond
        linedir        per line: (first entry index, count)
        entries        (commentator, target line, ref offset)
        refs           length-prefixed UTF-8 heRef strings
    """
    # resolve targets first; a link whose target book is not on the phone is
    # dropped here rather than promising a diamond that opens to nothing
    kept = [(s, t, tl, r) for (s, t, tl, r) in links if t in rel_of]
    if not kept:
        return None

    names = sorted({t for (_, t, _, _) in kept})
    cidx = {n: i for i, n in enumerate(names)}
    n_comm = len(names)
    # What each of these books is to `book`. Computed once per name, not per link:
    # שולחן ערוך אורח חיים has 27 names and 103,406 links.
    kinds = [bases.kind(book, n) for n in names]
    if tally is not None:
        for k in kinds:
            tally[k] = tally.get(k, 0) + 1

    stride = (line_count + 7) // 8
    marks = bytearray(n_comm * stride)
    by_line: dict[int, list] = {}
    refs = bytearray()
    ref_at: dict[str, int] = {}

    for (s, t, tl, r) in kept:
        if s > line_count:
            continue                       # link past the end of the file; drop, don't guess
        ci = cidx[t]
        marks[ci * stride + (s - 1) // 8] |= 1 << ((s - 1) % 8)
        off = ref_at.get(r)
        if off is None:
            off = len(refs)
            enc = r.encode("utf-8")[:65535]
            refs += struct.pack(">H", len(enc)) + enc
            ref_at[r] = off
        by_line.setdefault(s, []).append((ci, tl, off))

    entries = bytearray()
    linedir = bytearray()
    for ln in range(1, line_count + 1):
        rows = by_line.get(ln)
        if not rows:
            linedir += struct.pack(LINEDIR, 0, 0)
            continue
        first = len(entries) // ENTRY_SIZE
        linedir += struct.pack(LINEDIR, first, min(len(rows), 65535))
        for (ci, tl, off) in rows[:65535]:
            entries += struct.pack(ENTRY, ci, tl, off)

    ctable = bytearray()
    for n, kind in zip(names, kinds):
        nb = n.encode("utf-8")
        pb = rel_of[n].encode("utf-8")
        ctable += (struct.pack(">H", len(nb)) + nb +
                   struct.pack(">H", len(pb)) + pb +
                   struct.pack(">B", kind))

    off_c = HEADER_SIZE
    off_m = off_c + len(ctable)
    off_d = off_m + len(marks)
    off_e = off_d + len(linedir)
    off_r = off_e + len(entries)
    header = struct.pack(HEADER, MAGIC, VERSION, line_count, n_comm,
                         off_c, off_m, off_d, off_e, off_r,
                         len(entries) // ENTRY_SIZE)
    return bytes(header + ctable + marks + linedir + entries + refs)


def choose(args, texts, link_files, base) -> list[str]:
    if args.all:
        return sorted(link_files & texts.keys())
    if args.books:
        for m in [b for b in args.books if b not in texts]:
            print(f"  [warn] no such book: {m}", file=sys.stderr)
        return [b for b in args.books if b in texts]
    if args.categories:
        want = {c.strip() for c in args.categories}
        out = []
        for title, p in texts.items():
            rel = os.path.relpath(p, base).replace("\\", "/")
            if rel.split("/")[0] in want and title in link_files:
                out.append(title)
        return sorted(out)
    return []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--root", default=OTZARIA)
    ap.add_argument("--categories", nargs="*", default=[])
    ap.add_argument("--books", nargs="*", default=[])
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--bases", default=linkkind.DEFAULT_TABLE,
                    help="base_texts.json — which sefer comments on which")
    ap.add_argument("--idx-only", action="store_true",
                    help="write only idx/, no text. Safe because stripping anchors never "
                         "changes a line COUNT, so the sidecar addresses an unpacked "
                         "corpus identically -- use when the phone already has the text.")
    args = ap.parse_args()

    t0 = time.time()
    root, out = args.root, args.out
    base = os.path.join(root, TEXTS_DIRNAME)

    print(f"scanning {root} ...")
    texts = scan_texts(root)
    print(f"  {len(texts)} txt files")
    link_files = {f[:-len("_links.json")] for f in os.listdir(os.path.join(root, "links"))
                  if f.endswith("_links.json")}
    print(f"  {len(link_files)} links files")

    chosen = choose(args, texts, link_files, base)
    if not chosen:
        print("nothing selected — pass --all, --categories or --books", file=sys.stderr)
        return 2
    print(f"  {len(chosen)} books selected for indexing")

    bases = linkkind.load(args.bases)
    print(f"  {len(bases)} works in the base-text table ({args.bases})")

    def rel_for(title: str):
        p = texts.get(title)
        return None if p is None else f"{TEXTS_DIRNAME}/" + os.path.relpath(p, base).replace("\\", "/")

    os.makedirs(os.path.join(out, "idx"), exist_ok=True)
    idx_bytes = src_bytes = written = 0
    worst = ("", 0, 0)
    kind_tally: dict[int, int] = {}

    # --------------------------------------------------------- idx only
    # One book at a time, nothing retained. The text path below holds every chosen
    # book's link graph at once to work out the transitive set of books to ship,
    # which for --all would be gigabytes.
    if args.idx_only:
        print("writing sidecars (idx only — text assumed already on the device) ...")
        rel_all = {t: r for t in texts if (r := rel_for(t))}
        for i, b in enumerate(chosen, 1):
            g = load_links(root, b)
            blob = build_idx(b, g, count_lines(texts[b]), rel_all, bases, kind_tally)
            del g
            if blob is not None:
                with open(os.path.join(out, "idx", b + ".idx"), "wb") as fh:
                    fh.write(blob)
                idx_bytes += len(blob)
                written += 1
                h = struct.unpack(HEADER, blob[:HEADER_SIZE])
                if h[7] > worst[2]:
                    worst = (b, h[3], h[7])
            lp = os.path.join(root, "links", b + "_links.json")
            if os.path.isfile(lp):
                src_bytes += os.path.getsize(lp)
            if i % 250 == 0 or i == len(chosen):
                print(f"    {i}/{len(chosen)}  ({written} written, {idx_bytes/2**20:.1f} MB)")
    else:
        # -------------------------------------------------- text + idx
        print("reading link graphs ...")
        graphs: dict[str, list] = {}
        needed: set[str] = set(chosen)
        for i, b in enumerate(chosen, 1):
            g = load_links(root, b)
            graphs[b] = g
            # Only מפרשים and the book's own base text earn a place in the ~1.4 GB
            # the phone has. A RELATED cross-reference is worth showing when the
            # book it points at happens to be on the shelf already, but not worth
            # dragging בן איש חי onto the device because the Shulchan Arukh cites
            # it once. The `t in rel_of` filter in build_idx does the rest.
            needed.update(t for (_, t, _, _) in g
                          if t in texts and bases.kind(b, t) != linkkind.RELATED)
            if i % 200 == 0 or i == len(chosen):
                print(f"    {i}/{len(chosen)}  ({len(needed)} books needed so far)")
        print(f"  {len(needed)} books to ship (chosen + their מפרשים + their base texts)")

        print("writing text ...")
        rel_of: dict[str, str] = {}
        lines_of: dict[str, int] = {}
        bytes_in = bytes_out = 0
        for i, title in enumerate(sorted(needed), 1):
            src = texts[title]
            rel = os.path.relpath(src, base).replace("\\", "/")
            dst = os.path.join(out, TEXTS_DIRNAME, *rel.split("/"))
            bytes_in += os.path.getsize(src)
            lines_of[title] = emit_text(src, dst)
            bytes_out += os.path.getsize(dst)
            rel_of[title] = f"{TEXTS_DIRNAME}/{rel}"
            if i % 500 == 0 or i == len(needed):
                print(f"    {i}/{len(needed)}")
        saved = 100 * (1 - bytes_out / bytes_in) if bytes_in else 0
        print(f"  text {bytes_in/2**20:.0f} MB -> {bytes_out/2**20:.0f} MB "
              f"({saved:.0f}% saved stripping anchors)")

        print("writing sidecars ...")
        for b in chosen:
            blob = build_idx(b, graphs[b], lines_of.get(b, 0), rel_of, bases, kind_tally)
            if blob is None:
                continue
            with open(os.path.join(out, "idx", b + ".idx"), "wb") as fh:
                fh.write(blob)
            idx_bytes += len(blob)
            written += 1
            # off_e is header field 7 -- field 8 is off_r and would silently
            # fold the entry table into a number meant to be the open cost
            h = struct.unpack(HEADER, blob[:HEADER_SIZE])
            if h[7] > worst[2]:
                worst = (b, h[3], h[7])
            lp = os.path.join(root, "links", b + "_links.json")
            if os.path.isfile(lp):
                src_bytes += os.path.getsize(lp)

    print(f"\n  {written} sidecars, {idx_bytes/2**20:.1f} MB")
    print(f"  worst open cost: {worst[0]} — {worst[1]} commentators, {worst[2]/1024:.0f} KB")
    # Reported, never assumed. Before base_texts.json existed every one of these
    # was offered as a מפרש, and roughly half of them were not one.
    total_names = sum(kind_tally.values())
    if total_names:
        for k in (linkkind.MEFARESH, linkkind.BASE, linkkind.RELATED):
            n = kind_tally.get(k, 0)
            print(f"  {linkkind.KIND_NAMES[k]:9} {n:7d} book-commentator pairs "
                  f"({100*n/total_names:.1f}%)")
    if src_bytes:
        print(f"  links {src_bytes/2**20:.0f} MB JSON -> {idx_bytes/2**20:.1f} MB idx "
              f"({100*(1-idx_bytes/src_bytes):.0f}% smaller)")
    print(f"done in {time.time()-t0:.0f}s -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
