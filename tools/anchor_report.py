"""
Measure Sefaria's inline commentary anchors in both corpora.

Reproduction for BUILDER.md W33-A and W34 in the Girsa repo:
  - how much of a sefer's bytes are anchor markup
  - how many segments have an anchor sitting mid-phrase
  - whether spec.md 9.5's own example query survives it

Read-only.

    python tools/anchor_report.py
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

GIRSA = r"C:\Users\Administrator\Videos\Girsa\corpus"
OTZARIA = r"C:\Users\Administrator\Downloads\otzaria_latest"

# The alternation matters: 8 anchors in Shulchan Arukh O.C. are missing their
# opening quote (`data-commentator=Mishnah Berurah"`), an upstream defect, so a
# strict attribute match leaves those behind. See W33-B.
ANCHOR = re.compile(r"<i\s+data-commentator=[^>]*>\s*</i>")
# an anchor with a Hebrew letter on both sides -- i.e. one that splits a phrase
SPLITS = re.compile(r"[\u05d0-\u05ea]\s*<i\s+data-commentator=[^>]*>\s*</i>\s*[\u05d0-\u05ea]")

PHRASE = "יתגבר כארי"          # the query in spec.md 9.5's own mockup


def find_txt(name: str) -> str | None:
    for root, _, files in os.walk(os.path.join(OTZARIA, "אוצריא")):
        if name in files:
            return os.path.join(root, name)
    return None


def report_girsa(slug: str) -> None:
    p = os.path.join(GIRSA, "works", *slug.split("/"), "segments.jsonl")
    if not os.path.isfile(p):
        print(f"  [skip] no girsa segments for {slug}")
        return
    n = split = 0
    raw = clean = 0
    first = None
    for line in open(p, encoding="utf-8"):
        o = json.loads(line)
        t = o["text"]
        if first is None:
            first = t
        n += 1
        raw += len(t.encode("utf-8"))
        clean += len(ANCHOR.sub("", t).encode("utf-8"))
        if SPLITS.search(t):
            split += 1
    pct_bytes = 100 * (raw - clean) / raw if raw else 0
    print(f"  segments            {n}")
    print(f"  text bytes          {raw:,} -> {clean:,}  ({pct_bytes:.0f}% is anchor markup)")
    print(f"  anchor mid-phrase   {split}/{n} segments ({100*split/n:.0f}%)")
    if first is not None:
        print(f"  '{PHRASE}' contiguous?      {PHRASE in first}")
        print(f"  ... after stripping?         {PHRASE in ANCHOR.sub('', first)}")


def report_otzaria(fname: str) -> None:
    p = find_txt(fname)
    if not p:
        print(f"  [skip] no otzaria txt {fname}")
        return
    txt = open(p, encoding="utf-8").read()
    raw = len(txt.encode("utf-8"))
    clean = len(ANCHOR.sub("", txt).encode("utf-8"))
    print(f"  bytes               {raw:,} -> {clean:,}  ({100*(raw-clean)/raw:.0f}% anchors)")
    print(f"  carries anchors?    {'data-commentator' in txt}")
    print(f"  '{PHRASE}' contiguous?      {PHRASE in txt}")
    print(f"  ... after stripping?         {PHRASE in ANCHOR.sub('', txt)}")


if __name__ == "__main__":
    for slug, fname in [("shulchan-arukh/orach-chayim", "שולחן ערוך, אורח חיים.txt"),
                        ("mishnah-berakhot", "משנה ברכות.txt"),
                        ("bavli/berakhot", "ברכות.txt")]:
        print(f"\n=== {slug} ===")
        print(" girsa segments.jsonl:")
        report_girsa(slug)
        print(" otzaria .txt:")
        report_otzaria(fname)
