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


def ingested_slugs():
    """Every work slug on Girsa's shelf. Slugs contain '/', so a segment id cannot
    be cut into (work, rest) without knowing the set."""
    root = os.path.join(GIRSA, "works")
    out = set()
    for dirpath, _, files in os.walk(root):
        if "segments.jsonl" in files:
            out.add(os.path.relpath(dirpath, root).replace("\\", "/"))
    return out


def work_of(seg_id, ingested):
    """The work a segment id belongs to: the longest ingested slug that prefixes it.

    This function is the whole of BUILDER.md S1. The line it replaces was

        e["from"].split("girsa:")[1].split("/")[0]

    which takes the work to be everything before the FIRST '/'. 3,591 of Girsa's
    7,189 work slugs are paths, so that folded every commentary on every masechta
    of the Bavli -- Rashi, Tosafot, Rif, Rosh, Ritva, Meiri, Shita Mekubetzet --
    into one bucket named `bavli`, and this tool reported Berakhot as having *one*
    commentator. It does not. It has forty.

    The numbers that bug produced ("Girsa resolves ~33% of Otzaria's
    commentators", "Berakhot 2%") were quoted in two repos as a reason not to
    switch link sources. They were substantially an artefact of this line.
    """
    parts = seg_id.removeprefix("girsa:").split("/")
    for k in range(len(parts), 0, -1):
        cand = "/".join(parts[:k])
        if cand in ingested:
            return cand
    return None


def girsa_commentators(slug, ingested, kinds=("comments-on",)):
    """{source work slug: edge count} for inbound edges onto `slug`."""
    p = os.path.join(GIRSA, "links", *slug.split("/"), "inbound.jsonl")
    out = collections.Counter()
    if not os.path.exists(p):
        return out
    unresolved = 0
    for line in open(p, encoding="utf-8"):
        e = json.loads(line)
        if kinds is None or e["type"] in kinds:
            w = work_of(e["from"], ingested)
            if w is None:
                unresolved += 1
            else:
                out[w] += 1
    if unresolved:
        # Reported, not swallowed. A silently dropped edge looks exactly like an
        # edge that was never there, which is how the old number stayed believable.
        print(f"[note] {slug}: {unresolved} inbound edges resolve to no ingested work",
              file=sys.stderr)
    return out


def one_book(book, idx, ingested):
    oz = otzaria_commentators(book)
    if oz is None:
        print(f"{book}: no Otzaria links file")
        return
    slug = idx.get(norm(book))
    if slug is None:
        print(f"{book}: no Girsa work with this he_title")
        return
    gi = girsa_commentators(slug, ingested)
    any_type = girsa_commentators(slug, ingested, kinds=None)
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


def sample_table(idx, ingested):
    print(f"{'book':<14} {'otzaria':>9} {'girsa':>7} {'kept':>7}")
    tot_o = tot_g = 0
    for b in SAMPLE:
        oz, slug = otzaria_commentators(b), idx.get(norm(b))
        if oz is None or slug is None:
            print(f"{b:<14}   -- not present in both --")
            continue
        gi = girsa_commentators(slug, ingested)
        tot_o += len(oz)
        tot_g += len(gi)
        pct = 100 * len(gi) / len(oz) if oz else 0
        print(f"{b:<14} {len(oz):>9} {len(gi):>7} {pct:>6.0f}%")
    pct = 100 * tot_g / tot_o if tot_o else 0
    print(f"{'TOTAL':<14} {tot_o:>9} {tot_g:>7} {pct:>6.0f}%")


def selftest(ingested):
    """The three asserts that would have caught S1 on day one. The third needs no
    fixture at all: one masechta with one commentator is not a thing that exists."""
    fails = 0

    def check(got, want, why):
        nonlocal fails
        if got != want:
            fails += 1
            print(f"  FAIL {why}: got {got!r}, want {want!r}")

    check(work_of("girsa:bavli/rashi-on-berakhot/10a:1:1#367", ingested),
          "bavli/rashi-on-berakhot", "a path slug is not its first segment")
    check(work_of("girsa:turei-zahav-on-shulchan-arukh/orach-chayim/100:1#409", ingested),
          "turei-zahav-on-shulchan-arukh/orach-chayim", "two-segment slug")

    idx = he_title_index()
    slug = idx.get(norm("ברכות"))
    if slug:
        n = len(girsa_commentators(slug, ingested))
        if n <= 1:
            fails += 1
            print(f"  FAIL בברכות has {n} commentator(s) — a masechta with one "
                  f"commentator is not a thing that exists")
        else:
            print(f"  בברכות: {n} commentators")
    print(f"  {'all pass' if not fails else f'{fails} FAILED'}")
    return fails


if __name__ == "__main__":
    if not os.path.isdir(os.path.join(GIRSA, "works")):
        print(f"no Girsa corpus at {GIRSA}", file=sys.stderr)
        raise SystemExit(2)
    slugs = ingested_slugs()
    print(f"[{len(slugs)} ingested work slugs]", file=sys.stderr)
    if len(sys.argv) > 1 and sys.argv[1] == "--check":
        raise SystemExit(1 if selftest(slugs) else 0)
    index = he_title_index()
    if len(sys.argv) > 1:
        one_book(sys.argv[1], index, slugs)
    else:
        sample_table(index, slugs)
