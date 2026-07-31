"""
Compare the commentary graph in Otzaria's raw links against Girsa's ingested corpus.

Read-only. Touches nothing but:
  C:\\Users\\Administrator\\Downloads\\otzaria_latest\\links\\*_links.json
  C:\\Users\\Administrator\\Videos\\Girsa\\corpus\\{works,links}\\

This is the reproduction for BUILDER.md W32 in the Girsa repo: Girsa's graph
resolves ~a third of the commentators Otzaria's does on books present in both.

    python tools/girsa_coverage.py                 # the 12-book sample
    python tools/girsa_coverage.py "משנה ברכות"     # one book, commentator by commentator
"""
import json, os, sys, collections

sys.stdout.reconfigure(encoding="utf-8")

GIRSA = r"C:\Users\Administrator\Videos\Girsa\corpus"
OTZARIA = r"C:\Users\Administrator\Downloads\otzaria_latest"

SAMPLE = ["משנה ברכות", "משנה שבת", "בראשית", "שמות", "תהילים", "משלי",
          "ברכות", "שבת", "משנה פאה", "ישעיהו", "רות", "אסתר"]


def title_of(path):
    """Otzaria resolves link targets by filename, never by the stale path_2 dir."""
    return path.replace("\\", "/").rsplit("/", 1)[-1].removesuffix(".txt")


def norm(s):
    """Otzaria filenames drop gershayim; Girsa's he_titles keep them. Fold both so
    רמב"ם and רמבם are one key — otherwise the comparison invents missing works."""
    for ch in "\u05f4\u05f3\"'\u201c\u201d\u2018\u2019":
        s = s.replace(ch, "")
    return " ".join(s.split())


def otzaria_commentators(book):
    """{commentator title: edge count} for commentary/targum edges onto `book`."""
    p = os.path.join(OTZARIA, "links", book + "_links.json")
    if not os.path.exists(p):
        return None
    out = collections.Counter()
    for e in json.load(open(p, encoding="utf-8")):
        # the source data misspells the key; accept both
        kind = e.get("Conection Type", e.get("Connection Type", ""))
        if kind in ("commentary", "targum"):
            out[title_of(e.get("path_2", ""))] += 1
    return out


def he_title_index():
    """normalized he_title -> slug. Collisions are reported, not silently dropped."""
    idx, clash = {}, collections.Counter()
    for line in open(os.path.join(GIRSA, "works", "index.jsonl"), encoding="utf-8"):
        o = json.loads(line)
        k = norm(o["he_title"])
        if k in idx:
            clash[k] += 1
        else:
            idx[k] = o["slug"]
    if clash:
        print(f"[note] {len(clash)} he_titles collide after normalization; "
              f"first-wins, e.g. {list(clash)[:2]}", file=sys.stderr)
    return idx


def girsa_commentators(slug, kinds=("comments-on",)):
    """{source work slug: edge count} for inbound edges onto `slug`."""
    p = os.path.join(GIRSA, "links", *slug.split("/"), "inbound.jsonl")
    out = collections.Counter()
    if not os.path.exists(p):
        return out
    for line in open(p, encoding="utf-8"):
        e = json.loads(line)
        if kinds is None or e["type"] in kinds:
            out[e["from"].split("girsa:")[1].split("/")[0]] += 1
    return out


def one_book(book, idx):
    oz = otzaria_commentators(book)
    if oz is None:
        print(f"{book}: no Otzaria links file")
        return
    slug = idx.get(norm(book))
    if slug is None:
        print(f"{book}: no Girsa work with this he_title")
        return
    gi = girsa_commentators(slug)
    any_type = girsa_commentators(slug, kinds=None)
    print(f"\n=== {book}  ->  girsa:{slug} ===")
    print(f"Otzaria: {sum(oz.values()):>6} edges from {len(oz)} commentators")
    print(f"Girsa:   {sum(gi.values()):>6} edges from {len(gi)} works\n")
    print(f"  {'Otzaria commentator':<44} {'edges':>6}   in Girsa?")
    for name, n in oz.most_common():
        # absent means the edge is gone, not merely mistyped — the two are
        # distinguished because they need different fixes upstream
        gslug = idx.get(norm(name))
        if gslug is None:
            state = "no Girsa work with this he_title"
        elif gslug in gi:
            state = f"yes ({gi[gslug]} comments-on)"
        elif gslug in any_type:
            state = f"MISTYPED ({any_type[gslug]} edges, none comments-on)"
        else:
            state = "NO EDGES (work ingested, unlinked)"
        print(f"  {name:<44} {n:>6}   {state}")


def sample_table(idx):
    print(f"{'book':<14} {'otzaria':>9} {'girsa':>7} {'kept':>7}")
    tot_o = tot_g = 0
    for b in SAMPLE:
        oz, slug = otzaria_commentators(b), idx.get(norm(b))
        if oz is None or slug is None:
            print(f"{b:<14}   -- not present in both --")
            continue
        gi = girsa_commentators(slug)
        tot_o += len(oz)
        tot_g += len(gi)
        pct = 100 * len(gi) / len(oz) if oz else 0
        print(f"{b:<14} {len(oz):>9} {len(gi):>7} {pct:>6.0f}%")
    pct = 100 * tot_g / tot_o if tot_o else 0
    print(f"{'TOTAL':<14} {tot_o:>9} {tot_g:>7} {pct:>6.0f}%")


if __name__ == "__main__":
    index = he_title_index()
    if len(sys.argv) > 1:
        one_book(sys.argv[1], index)
    else:
        sample_table(index)
