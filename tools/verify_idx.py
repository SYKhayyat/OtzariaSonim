"""
Read a packed library back the way the phone will, and check it against ground truth.

This is a reference implementation of the .idx reader -- the Kotlin in Otzaria.kt
must produce identical answers. It deliberately does NOT reuse pack_library.py's
structures: it re-derives everything from the bytes on disk, so a packer bug that
round-trips through a shared in-memory object cannot hide here.

    python tools/verify_idx.py <packed-root>
    python tools/verify_idx.py <packed-root> <texts-root>

The second form is for an `--idx-only` pack, where the sidecars and the text they
address live in different places. On the phone they do not -- but that is exactly
the arrangement that otherwise never gets checked before it ships.
"""
from __future__ import annotations

import os
import struct
import sys

import linkkind

sys.stdout.reconfigure(encoding="utf-8")

MAGIC = b"OZSI"
HEADER = ">4sBIHIIIIII"
HEADER_SIZE = struct.calcsize(HEADER)
ENTRY = ">HII"
ENTRY_SIZE = struct.calcsize(ENTRY)
LINEDIR = ">IH"
LINEDIR_SIZE = struct.calcsize(LINEDIR)


class Idx:
    """Opens a sidecar the way the phone does: header + commentators + marks +
    linedir up front, entries and refs by seek only."""

    def __init__(self, path: str):
        self.fh = open(path, "rb")
        magic, ver, self.n_lines, self.n_comm, oc, om, od, oe, orf, self.n_entries = \
            struct.unpack(HEADER, self.fh.read(HEADER_SIZE))
        if magic != MAGIC:
            raise ValueError(f"not a sidecar: {path}")
        self.ver, self.off_marks, self.off_dir, self.off_ent, self.off_ref = ver, om, od, oe, orf

        self.fh.seek(oc)
        self.names: list[str] = []
        self.paths: list[str] = []
        self.kinds: list[int] = []
        for _ in range(self.n_comm):
            n = struct.unpack(">H", self.fh.read(2))[0]
            self.names.append(self.fh.read(n).decode("utf-8"))
            n = struct.unpack(">H", self.fh.read(2))[0]
            self.paths.append(self.fh.read(n).decode("utf-8"))
            # v1 carried no kind byte and asserted that everything in it was a
            # commentary -- which is exactly the claim v2 exists to stop making.
            self.kinds.append(self.fh.read(1)[0] if ver >= 2 else linkkind.MEFARESH)

        self.stride = (self.n_lines + 7) // 8
        self.fh.seek(om)
        self.marks = self.fh.read(self.n_comm * self.stride)
        self.fh.seek(od)
        self.dir = self.fh.read(self.n_lines * LINEDIR_SIZE)
        self.open_cost = oe  # everything before entries is read on open

    def named(self, kind: int) -> list[str]:
        return sorted(n for n, k in zip(self.names, self.kinds) if k == kind)

    def kind_of(self, name: str) -> int | None:
        return self.kinds[self.names.index(name)] if name in self.names else None

    def marked_lines(self, selected: set[str] | None = None) -> set[int]:
        """1-based lines carrying a commentary from `selected` (None = all)."""
        want = [i for i, n in enumerate(self.names) if selected is None or n in selected]
        out: set[int] = set()
        for ci in want:
            base = ci * self.stride
            for byte_i in range(self.stride):
                b = self.marks[base + byte_i]
                if not b:
                    continue
                for bit in range(8):
                    if b & (1 << bit):
                        ln = byte_i * 8 + bit + 1
                        if ln <= self.n_lines:
                            out.add(ln)
        return out

    def on_line(self, line: int):
        """[(commentator, relative path, target line, ref)] for one 1-based line."""
        if not 1 <= line <= self.n_lines:
            return []
        first, count = struct.unpack_from(LINEDIR, self.dir, (line - 1) * LINEDIR_SIZE)
        if not count:
            return []
        self.fh.seek(self.off_ent + first * ENTRY_SIZE)
        raw = self.fh.read(count * ENTRY_SIZE)
        out = []
        for i in range(count):
            ci, tl, ro = struct.unpack_from(ENTRY, raw, i * ENTRY_SIZE)
            self.fh.seek(self.off_ref + ro)
            n = struct.unpack(">H", self.fh.read(2))[0]
            out.append((self.names[ci], self.paths[ci], tl, self.fh.read(n).decode("utf-8")))
        return out


def line_of(texts: str, rel: str, n: int) -> str:
    """Stream a target file for one 1-based line -- never read it whole."""
    p = os.path.join(texts, *rel.split("/"))
    with open(p, encoding="utf-8") as fh:
        for i, line in enumerate(fh, 1):
            if i == n:
                return line.rstrip("\n")
    return ""


def main(root: str, texts: str | None = None) -> int:
    texts = texts or root
    idx_dir = os.path.join(root, "idx")
    files = sorted(f for f in os.listdir(idx_dir) if f.endswith(".idx"))
    print(f"{len(files)} sidecars in {idx_dir}\n")

    fails = 0

    # ---- ground truth: SPEC.md's verified end-to-end lookup ----
    book = "משנה ברכות"
    p = os.path.join(idx_dir, book + ".idx")
    if os.path.isfile(p):
        ix = Idx(p)
        print(f"=== {book} ===")
        print(f"  lines={ix.n_lines} commentators={ix.n_comm} "
              f"open cost={ix.open_cost/1024:.1f} KB")
        got = ix.on_line(3)
        print(f"  line 3 carries {len(got)} meforshim")
        ram = [g for g in got if "רמבם" in g[0]]
        if not ram:
            print("  FAIL: no Rambam on line 3"); fails += 1
        else:
            name, rel, tl, ref = ram[0]
            text = line_of(texts, rel, tl)
            ok = tl == 5 and text.startswith("<b>מאימתי קורין את שמע בערבין וכו':")
            print(f"  {name} -> {rel} line {tl}")
            print(f"     ref : {ref}")
            print(f"     text: {text[:72]}")
            print(f"  SPEC known-good (line 3 -> Rambam line 5): {'PASS' if ok else 'FAIL'}")
            fails += 0 if ok else 1

        # the diamond, filtered -- the query that used to OOM
        allm = ix.marked_lines()
        one = ix.marked_lines({"ברטנורא על משנה ברכות"})
        print(f"  diamonds: {len(allm)} lines all-commentators, {len(one)} filtered to Bartenura")
        if not one <= allm:
            print("  FAIL: filtered marks are not a subset of unfiltered"); fails += 1
    else:
        print(f"[skip] {book} not in this pack")

    # ---- what a link IS, not merely that it resolves ----
    # These are the assertions the reported defect would have failed. The reader
    # offered ויקרא as one of the מפרשים on שולחן ערוך יורה דעה, and offered a
    # commentary's own base text as a commentary on it.
    print("\n=== link kinds ===")
    KIND_TRUTH = [
        # book, linked name, expected kind, why
        ("שולחן ערוך, יורה דעה", "ויקרא", linkkind.RELATED,
         "a chumash is not a commentary on the Shulchan Arukh"),
        ("שולחן ערוך, אורח חיים", "משנה ברורה", linkkind.MEFARESH, "the real thing"),
        ("שולחן ערוך, אורח חיים", "טור", linkkind.RELATED, "an independent work"),
        ("משנה ברורה", "שולחן ערוך, אורח חיים", linkkind.BASE, "the sefer it comments on"),
        ("רשי על בראשית", "בראשית", linkkind.BASE, "the sefer it comments on"),
        ("רשי על בראשית", "שפתי חכמים", linkkind.MEFARESH, "a super-commentary on Rashi"),
        ("בראשית", "רשי על בראשית", linkkind.MEFARESH, "the real thing"),
        ("שבת", "רשי על שבת", linkkind.MEFARESH, "the real thing"),
        ("שבת", "ריף בבא בתרא", linkkind.RELATED, "the Rif on a different masechta"),
    ]
    checked = 0
    for book, name, want, why in KIND_TRUTH:
        p = os.path.join(idx_dir, book + ".idx")
        if not os.path.isfile(p):
            continue
        got = Idx(p).kind_of(name)
        if got is None:
            continue                       # target not shipped in this subset
        checked += 1
        if got != want:
            print(f"  FAIL {book}: {name} is {linkkind.KIND_NAMES[got]}, "
                  f"expected {linkkind.KIND_NAMES[want]} — {why}")
            fails += 1
    print(f"  {checked} of {len(KIND_TRUTH)} kind assertions apply to this pack")
    for book in ("שולחן ערוך, אורח חיים", "רשי על בראשית", "משנה ברורה", "שבת"):
        p = os.path.join(idx_dir, book + ".idx")
        if os.path.isfile(p):
            ix = Idx(p)
            print(f"  {book}: {len(ix.named(linkkind.MEFARESH))} מפרשים, "
                  f"{len(ix.named(linkkind.BASE))} מקור, "
                  f"{len(ix.named(linkkind.RELATED))} קשור")

    # ---- invariants over everything packed ----
    print("\n=== invariants over all sidecars ===")
    worst = ("", 0)
    bad_kind = 0
    for f in files:
        ix = Idx(os.path.join(idx_dir, f))
        if ix.open_cost > worst[1]:
            worst = (f, ix.open_cost)
        if any(k not in linkkind.KIND_NAMES for k in ix.kinds):
            bad_kind += 1
        # every commentator path must exist, or the app promises a diamond it
        # cannot honour -- the exact defect this pack is meant to remove
        for rel in ix.paths:
            if not os.path.isfile(os.path.join(texts, *rel.split("/"))):
                print(f"  FAIL {f}: missing target {rel}"); fails += 1
                break
        # marks and the line directory must agree about which lines have content
        marked = ix.marked_lines()
        dir_lines = {i + 1 for i in range(ix.n_lines)
                     if struct.unpack_from(LINEDIR, ix.dir, i * LINEDIR_SIZE)[1]}
        if marked != dir_lines:
            print(f"  FAIL {f}: marks {len(marked)} != linedir {len(dir_lines)}"); fails += 1
    if bad_kind:
        print(f"  FAIL {bad_kind} sidecars carry an unknown kind byte"); fails += bad_kind
    print(f"  worst open cost: {worst[0]} at {worst[1]/1024:.1f} KB")
    print(f"\n{'ALL CHECKS PASSED' if not fails else f'{fails} FAILURES'}")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main(*sys.argv[1:3]))
