"""
Build the ground truth for "which sefer is a commentary on which".

Why this exists
---------------
Otzaria's `links/` graph labels an edge `commentary` but never says which end is
the commentary. Worse, it stores nearly every edge **twice, once each way**: over
the whole corpus 39.9% of commentary/targum edges are commentary -> base and
another 39.9% are the same edges written base -> commentary. The reader built its
per-book commentator list straight from that, so:

    open שולחן ערוך, יורה דעה  ->  ויקרא is offered as one of its מפרשים
    open משנה ברורה            ->  שולחן ערוך, אורח חיים is offered as its מפרש
    open רשי על בראשית         ->  בראשית is offered as its מפרש

Otzaria's own Flutter app has the same defect (`getAvailableCommentators` in
`text_book_repository.dart` filters on the type and nothing else), so it is not a
usable ground truth. Girsa is: `girsa-link/src/orient.rs` orients an edge by
**reading Sefaria's declaration** rather than guessing from the title, because
guessing `X על Y` would attach `רשי על ברכות` to the Yerushalmi masechta of the
same name.

This tool takes that same declaration -- Sefaria's per-work `dependence` and
`base_text_titles` -- and rewrites it against Otzaria's filenames, which is the
only key the rest of this repo has. The output is checked in, so `pack_library.py`
never needs the 6,595-file Sefaria schema tree present.

What the output means
---------------------
`works[<otzaria title>]` is one of:

    {"d": 1, "b": [...]}   declared a commentary/targum, on these seforim
    {"d": 1, "b": []}      declared a commentary, but Sefaria does not say on what
                           (בית יוסף, תורה תמימה על התורה, לבושי שרד … 144 of them)
    {"d": 0}               Sefaria knows this work and it depends on nothing --
                           it is a base text. This is a positive fact, not a gap.
    (absent)               Sefaria does not know it. No information; guess nothing.

The distinction between `{"d": 0}` and absent is the whole point. "Independent
work" is what disqualifies ויקרא from being a commentary on the Shulchan Arukh;
"unknown" is not, and an unknown work must keep its links.

Usage
-----
    python tools/build_bases.py                          # -> tools/base_texts.json
    python tools/build_bases.py --check                  # self-test, writes nothing
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "base_texts.json")
SCHEMAS = r"C:\Users\Administrator\Videos\Girsa\corpus\sefaria\schemas"
OTZARIA = r"C:\Users\Administrator\Downloads\otzaria_latest"
TEXTS_DIRNAME = "אוצריא"

FORMAT = 1

# Otzaria's filenames drop the punctuation Sefaria's titles carry: Sefaria says
# `רש"י על בראשית`, the file is `רשי על בראשית.txt`. Gershayim come in five
# encodings across the two corpora (", ״, ”, ׳, ') and a title may carry stray
# whitespace at either end -- Girsa's T8 calls that "real corpus grime", and it
# is in Otzaria's filenames too (` מעדני יום טוב על ברכות.txt` has a leading
# space). Normalising both sides is what makes the two name spaces meet.
_PUNCT = dict.fromkeys(map(ord, "\"'\u05f4\u05f3\u201c\u201d\u2018\u2019.,־-"), None)


def match_key(s: str) -> str:
    """The name both corpora agree on: no markup, no punctuation, one space."""
    return " ".join(re.sub(r"<[^>]*>", "", s or "").translate(_PUNCT).split()).strip()


def otzaria_titles(root: str) -> dict[str, set[str]]:
    """match key -> the actual .txt filenames (no extension) that normalise to it."""
    out: dict[str, set[str]] = collections.defaultdict(set)
    for dirpath, _, files in os.walk(os.path.join(root, TEXTS_DIRNAME)):
        for f in files:
            if f.lower().endswith(".txt"):
                out[match_key(f[:-4])].add(f[:-4])
    return out


def load_schemas(path: str) -> list[dict]:
    out, bad = [], 0
    for fn in sorted(os.listdir(path)):
        if not fn.endswith(".json"):
            continue
        try:
            with open(os.path.join(path, fn), encoding="utf-8") as fh:
                out.append(json.load(fh))
        except Exception:
            bad += 1
    if bad:
        print(f"  [warn] {bad} unparseable schema files skipped", file=sys.stderr)
    return out


def index_by_title(schemas: list[dict]) -> tuple[dict, dict]:
    """(hebrew key -> schema, english key -> schema).

    Canonical titles are laid down first and variants only fill gaps. Doing it in
    one pass instead lets a *variant* of one sefer shadow the *canonical title* of
    another -- which it does: an earlier draft of this resolved `רשי על שמות` to
    somebody else's title variant and reported it as declaring no base at all,
    turning Rashi on Exodus into a work with no ground truth.
    """
    he: dict[str, dict] = {}
    en: dict[str, dict] = {}
    for d in schemas:
        k = match_key(d.get("heTitle", ""))
        if k:
            he.setdefault(k, d)
        k = match_key(d.get("title", ""))
        if k:
            en.setdefault(k, d)
    for d in schemas:
        for nm in d.get("heTitleVariants") or []:
            k = match_key(nm)
            if k:
                he.setdefault(k, d)
        for nm in d.get("titleVariants") or []:
            k = match_key(nm)
            if k:
                en.setdefault(k, d)
    return he, en


def build(schema_dir: str, otzaria_root: str) -> dict:
    print(f"reading schemas from {schema_dir} ...")
    schemas = load_schemas(schema_dir)
    he_idx, en_idx = index_by_title(schemas)
    print(f"  {len(schemas)} schemas, {len(he_idx)} hebrew keys, {len(en_idx)} english keys")

    print(f"scanning {otzaria_root} ...")
    by_key = otzaria_titles(otzaria_root)
    n_titles = sum(len(v) for v in by_key.values())
    ambiguous = {k: sorted(v) for k, v in by_key.items() if len(v) > 1}
    print(f"  {n_titles} txt files, {len(by_key)} distinct match keys "
          f"({len(ambiguous)} keys claimed by more than one file)")

    def to_otzaria(*names: str) -> set[str]:
        """Every shipped filename that any of these Sefaria titles names."""
        out: set[str] = set()
        for nm in names:
            if nm:
                out |= by_key.get(match_key(nm), set())
        return out

    works: dict[str, dict] = {}
    stats = collections.Counter()
    for k, files in by_key.items():
        d = he_idx.get(k)
        if d is None:
            stats["unknown_to_sefaria"] += len(files)
            continue
        bts = d.get("base_text_titles") or []
        dependent = bool(bts) or d.get("dependence") in ("Commentary", "Targum")
        bases: set[str] = set()
        for b in bts:
            if isinstance(b, str):
                en_name, he_name = b, b
            else:
                en_name, he_name = b.get("en", ""), b.get("he", "")
            # English first. `base_text_titles[].he` is Sefaria's own Hebrew title
            # and often is not the one the file is named after -- Leviticus is
            # `ספר ויקרא` there and `ויקרא.txt` on the shelf -- so going
            # en -> schema -> that schema's heTitle lands on the file and the
            # direct he lookup does not.
            hit = en_idx.get(match_key(en_name))
            if hit is not None:
                bases |= to_otzaria(hit.get("heTitle", ""), *(hit.get("heTitleVariants") or []))
            if not bases:
                bases |= to_otzaria(he_name)
        for f in files:
            bases.discard(f)          # a work is not a commentary on itself
        rec = {"d": 1, "b": sorted(bases)} if dependent else {"d": 0}
        for f in files:
            works[f] = rec if not dependent else {"d": 1, "b": sorted(bases - {f})}
        if not dependent:
            stats["independent"] += len(files)
        elif bases:
            stats["commentary_with_base"] += len(files)
        else:
            stats["commentary_base_unstated"] += len(files)

    for k, v in sorted(stats.items()):
        print(f"  {k:26} {v:6d}")
    return {
        "format": FORMAT,
        "note": "otzaria title -> Sefaria's declaration. d=1 commentary/targum, "
                "d=0 independent base text, absent = unknown to Sefaria.",
        "counts": dict(stats),
        "works": dict(sorted(works.items())),
    }


# --------------------------------------------------------------------- self-test

KNOWN = [
    # (title, dependent?, a base it must name)
    ("רשי על בראשית", True, "בראשית"),
    ("רשי על שמות", True, "שמות"),                     # via `ספר שמות`, en fallback
    ("משנה ברורה", True, "שולחן ערוך, אורח חיים"),
    ("טורי זהב על שולחן ערוך אורח חיים", True, "שולחן ערוך, אורח חיים"),
    ("ברטנורא על משנה ברכות", True, "משנה ברכות"),
    ("מלבים על ויקרא", True, "ויקרא"),
    ("שפתי חכמים", True, "רשי על בראשית"),             # a super-commentary, on Rashi
    ("תפסיר רסג", True, "בראשית"),                      # targum, not commentary
]
INDEPENDENT = ["בראשית", "ויקרא", "שולחן ערוך, אורח חיים", "שולחן ערוך, יורה דעה",
               "טור", "שבת", "ערוך השולחן", "בן איש חי", "משנה ברכות"]


def check(data: dict) -> int:
    """The assertions that would have caught the reported defect on day one."""
    works = data["works"]
    fails = 0

    def bad(msg):
        nonlocal fails
        fails += 1
        print(f"  FAIL {msg}")

    for title, dependent, base in KNOWN:
        rec = works.get(title)
        if rec is None:
            bad(f"{title!r}: not in the table at all")
        elif rec["d"] != int(dependent):
            bad(f"{title!r}: d={rec['d']}, expected {int(dependent)}")
        elif base not in rec.get("b", []):
            bad(f"{title!r}: bases {rec.get('b')} do not include {base!r}")

    for title in INDEPENDENT:
        rec = works.get(title)
        if rec is None:
            bad(f"{title!r}: not in the table at all")
        elif rec["d"] != 0:
            bad(f"{title!r}: declared dependent ({rec}) -- a base text must not be")

    # The reported bug, stated as an assertion. ויקרא is an independent work, so
    # it can never be a commentary on the Shulchan Arukh however the link is drawn.
    if works.get("ויקרא", {}).get("d") != 0:
        bad("ויקרא must be independent -- this is the bug that started all this")

    print(f"  {len(KNOWN) + len(INDEPENDENT) + 1} assertions, "
          f"{'all pass' if not fails else f'{fails} FAILED'}")
    return fails


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--schemas", default=SCHEMAS, help="Sefaria index schema json dir")
    ap.add_argument("--root", default=OTZARIA, help="otzaria_latest root")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--check", action="store_true",
                    help="rebuild and self-test without writing")
    args = ap.parse_args()

    if not os.path.isdir(args.schemas):
        print(f"no schema dir at {args.schemas}\n"
              f"It ships with Girsa (corpus/sefaria/schemas). tools/base_texts.json is\n"
              f"checked in, so you only need this to regenerate it.", file=sys.stderr)
        return 2

    data = build(args.schemas, args.root)
    print("\nself-test:")
    fails = check(data)
    if fails:
        return 1
    if not args.check:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        print(f"\nwrote {args.out} ({os.path.getsize(args.out)/1024:.0f} KB, "
              f"{len(data['works'])} works)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
