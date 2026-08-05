"""
What a link between two seforim actually is.

Otzaria's `links/` graph says `commentary` and stops there. It does not say which
end is the commentary, and it stores nearly every edge twice -- once each way. So
"everything this book's links file points at" is not the list of a book's
מפרשים; it is that list plus the book's own base text plus whatever else Sefaria
happened to cross-reference.

This module answers the one question the reader needs -- *given a link from book
`src` to book `tgt`, what is `tgt` to `src`?* -- from `base_texts.json`, i.e. from
Sefaria's own declaration. Nothing here parses a title. Guessing `X על Y` is
forbidden for the reason Girsa's BUILDER.md rule 6 gives: it attaches
`רשי על ברכות` to the Yerushalmi masechta of the same name.

Nothing is thrown away. A link that is not a commentary is demoted to [RELATED],
which the reader shows in its own section, off by default -- so the ◆ and the
מפרשים list mean what they say, and a curious reader can still reach the rest.
"""
from __future__ import annotations

import json
import os

MEFARESH = 0   # tgt is a commentary on src. The ◆, and the מפרשים list.
BASE = 1       # the other way round: tgt is the sefer src comments on.
RELATED = 2    # tgt is an independent work. Cross-referenced, not a commentary.

KIND_NAMES = {MEFARESH: "MEFARESH", BASE: "BASE", RELATED: "RELATED"}

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TABLE = os.path.join(_HERE, "base_texts.json")


class Bases:
    """Sefaria's declarations, keyed by Otzaria filename. Built by build_bases.py."""

    def __init__(self, works: dict[str, dict]):
        self._w = works
        # Reverse of `b`: sefer -> the works that declare it as their base. Needed
        # for [near]; built once because the forward table alone cannot answer
        # "what comments on this?".
        self._commentaries: dict[str, set[str]] = {}
        for title, rec in works.items():
            for base in rec.get("b", ()):
                self._commentaries.setdefault(base, set()).add(title)

    @classmethod
    def load(cls, path: str = DEFAULT_TABLE) -> "Bases":
        with open(path, encoding="utf-8") as fh:
            return cls(json.load(fh)["works"])

    def __len__(self) -> int:
        return len(self._w)

    def knows(self, title: str) -> bool:
        return title in self._w

    def is_dependent(self, title: str) -> bool | None:
        """True commentary/targum, False independent work, None if unknown."""
        rec = self._w.get(title)
        return None if rec is None else bool(rec["d"])

    def bases_of(self, title: str) -> list[str]:
        return self._w.get(title, {}).get("b", [])

    def commentaries_on(self, title: str) -> set[str]:
        """The works that declare `title` as one of their base texts."""
        return self._commentaries.get(title, frozenset())

    def near(self, title: str) -> set[str]:
        """`title`, the seforim it comments on, and the seforim that comment on it.

        One hop, and one hop only. It is what separates a super-commentary that
        belongs on the line from a work that merely got cross-referenced onto it:

            שפתי חכמים declares רשי על בראשית, which comments on בראשית — so on a
            pasuk of בראשית it is one hop away, and it is a מפרש there.
            ריף בבא בתרא declares בבא בתרא, which has nothing to do with שבת — so
            on a daf of שבת it is not a מפרש, whatever the link says.

        Both are `dependence: Commentary` works whose declared base is not the
        sefer being read, so no test on the target alone can tell them apart.
        """
        out = {title}
        out.update(self.bases_of(title))
        out.update(self.commentaries_on(title))
        return out

    def kind(self, src: str, tgt: str) -> int:
        """What `tgt` is to `src`, for an edge Otzaria labelled commentary/targum."""
        return self.classify(src, tgt)[0]

    def why(self, src: str, tgt: str) -> str:
        """The rule that decided it. Reported by the packer so the share of links
        resting on no evidence at all is a number somebody can look at, rather than
        a thing everyone assumes is small."""
        return self.classify(src, tgt)[1]

    def classify(self, src: str, tgt: str) -> tuple[int, str]:
        """(kind, the rule that decided it). The rules are ordered, and the order
        is load-bearing — see the comment on each branch for what breaks without it."""
        if tgt == src:
            # A book is not its own commentary; the corpus has a handful of these
            # and they would put a sefer in its own מפרשים list.
            return RELATED, "self link"

        # The two declared answers come first, and in this order. Testing
        # independence before them is wrong and was the first version of this
        # function: a base text *is* independent, so `משנה ברורה -> שולחן ערוך`
        # came back RELATED and the reader lost the one label that says
        # "this is the sefer you are reading a commentary on".
        if src in self.bases_of(tgt):
            return MEFARESH, "declared"         # both ends agree

        # The mirror edge. `משנה ברורה_links.json` points at
        # `שולחן ערוך, אורח חיים` 17,478 times; src declares tgt as its base, so
        # this row is the base text, not a commentary. The same edge is already
        # in the Shulchan Arukh's own file the right way round, so nothing is
        # lost by not calling it a מפרש here.
        if tgt in self.bases_of(src):
            return BASE, "declared mirror"

        dep = self.is_dependent(tgt)

        # Unknown to Sefaria -- 933 of Otzaria's 6,615 books are its own, with no
        # schema anywhere. There is no evidence to demote on, and demoting on
        # absence of evidence would silently empty those books' panels. Girsa
        # leaves undeclared edges alone for the same reason; so do we.
        #
        # This branch is the one worth watching, so the packer counts it by name.
        # Measured 5 Aug 2026: of the 933, only 42 appear in the link graph at all,
        # and every edge touching one is `הערות על חברותא על X` -> `חברותא על X`,
        # where "commentary" is the right answer anyway. The branch is a real gap
        # in principle and an empty one in this corpus -- which is only knowable
        # because it is counted.
        if dep is None:
            return MEFARESH, "no evidence"

        # Sefaria knows this work and says it depends on nothing. That is a fact
        # about the work, not a gap in the data: an independent sefer cannot be a
        # commentary on anything, whichever way the link was written. This is the
        # branch that stops ויקרא being offered as a מפרש on the Shulchan Arukh.
        if not dep:
            return RELATED, "target is an independent work"

        # Declared a commentary, but on something else. Sefaria pins a work to one
        # level and Otzaria's links reach both, so this bucket holds real
        # super-commentaries *and* plain cross-references, mixed. [near] is what
        # tells them apart -- and it has to, because leaving the whole bucket as
        # מפרש offered ריף בבא בתרא and תוספות על בבא בתרא as commentaries on
        # מסכת שבת, while demoting the whole bucket would have thrown away
        # שפתי חכמים on a pasuk and מזרחי on Rashi.
        bases = self.bases_of(tgt)
        near = self.near(src)
        # `near` is only evidence when it holds something besides `src` itself.
        # For a sefer Sefaria declares nothing about in either direction -- no
        # bases, nothing declaring it as a base -- the one-hop test degenerates
        # into "src must be a declared base of tgt", which the first branch
        # already asked and which nothing here can answer. Demoting on that is
        # demoting on absence of evidence: it emptied בית יוסף's מפרשים list
        # completely, דרישה, פרישה and דרכי משה included.
        if bases and len(near) > 1 and not (set(bases) & near):
            return RELATED, "declared on something further off"

        # Either Sefaria never said what it comments on (בית יוסף, תורה תמימה --
        # 36 such works), or it comments on something one hop from here.
        return MEFARESH, ("base unstated" if not bases else "one hop")


def load(path: str = DEFAULT_TABLE) -> Bases:
    return Bases.load(path)


# --------------------------------------------------------------------- self-test

def _selftest() -> int:
    """Run with `python tools/linkkind.py`. Uses the checked-in table."""
    b = Bases.load()
    cases = [
        # src, tgt, expected, why
        ("שולחן ערוך, יורה דעה", "ויקרא", RELATED, "the reported bug"),
        ("שולחן ערוך, אורח חיים", "שבת", RELATED, "a masechta is not a מפרש on SA"),
        ("שולחן ערוך, אורח חיים", "טור", RELATED, "the Tur is an independent work"),
        ("שולחן ערוך, אורח חיים", "משנה ברורה", MEFARESH, "declared, the right way"),
        ("שולחן ערוך, אורח חיים", "מגן אברהם", MEFARESH, "declared"),
        ("משנה ברורה", "שולחן ערוך, אורח חיים", BASE, "the mirror edge"),
        ("רשי על בראשית", "בראשית", BASE, "the mirror edge"),
        ("בראשית", "רשי על בראשית", MEFARESH, "declared"),
        ("רשי על בראשית", "מזרחי", MEFARESH, "a super-commentary on Rashi"),
        ("רשי על בראשית", "שפתי חכמים", MEFARESH, "declared on Rashi specifically"),
        ("בראשית", "שפתי חכמים", MEFARESH, "pinned one level up, still a מפרש"),
        ("בראשית", "מדרש שכל טוב", RELATED, "a midrash is its own work"),
        # The one-hop rule. Every one of these is `dependence: Commentary` whose
        # declared base is not the sefer being read, so only [near] separates them.
        ("שבת", "רשי על שבת", MEFARESH, "declared"),
        ("שבת", "ריף בבא בתרא", RELATED, "the Rif on a different masechta"),
        ("שבת", "תוספות על בבא בתרא", RELATED, "Tosafot on a different masechta"),
        ("שבת", "משך חכמה", RELATED, "a commentary on the Chumash"),
        ("שבת", "מזרחי", RELATED, "a super-commentary on Rashi on the Chumash"),
        ("בראשית", "ישע אלהים על אסתר", RELATED, "a commentary on Esther"),
        ("ויקרא", "רשי על ויקרא", MEFARESH, "declared"),
        ("שבת", "מהרם שיף על שבת", MEFARESH, "on this masechta"),
        ("בראשית", "רשי על שמות", RELATED, "Rashi on a different chumash"),
        # בית יוסף declares no base and nothing declares it as one, so there is no
        # hop to test. Not demoting on that absence is the whole point of the
        # len(near) > 1 guard -- without it these three vanish.
        ("בית יוסף", "דרישה", MEFARESH, "no evidence either way; keep"),
        ("בית יוסף", "פרישה", MEFARESH, "no evidence either way; keep"),
        ("בית יוסף", "דרכי משה", MEFARESH, "no evidence either way; keep"),
        ("טור", "בית יוסף", MEFARESH, "commentary, base unstated"),
        ("בית יוסף", "טור", RELATED, "the Tur is independent"),
        ("ויקרא", "תפסיר רסג", MEFARESH, "targum counts"),
        ("בראשית", "בראשית", RELATED, "a self link"),
        ("משנה ברכות", "ברטנורא על משנה ברכות", MEFARESH, "declared"),
        ("ברטנורא על משנה ברכות", "משנה ברכות", BASE, "the mirror edge"),
    ]
    fails = 0
    for src, tgt, want, why in cases:
        got = b.kind(src, tgt)
        if got != want:
            fails += 1
            print(f"  FAIL {src!r} <- {tgt!r}: got {KIND_NAMES[got]}, "
                  f"want {KIND_NAMES[want]} ({why})")

    # The rule each answer came from, because the counts the packer prints are
    # only worth reading if the labels on them are right. The last pair is the
    # whole of the "no evidence" branch in this corpus: 38 books of חברותא and
    # their notes, neither of which Sefaria has ever heard of.
    reasons = [
        ("שולחן ערוך, אורח חיים", "משנה ברורה", "declared"),
        ("משנה ברורה", "שולחן ערוך, אורח חיים", "declared mirror"),
        ("שולחן ערוך, יורה דעה", "ויקרא", "target is an independent work"),
        ("בראשית", "שפתי חכמים", "one hop"),
        ("שבת", "ריף בבא בתרא", "declared on something further off"),
        ("טור", "בית יוסף", "base unstated"),
        ("חברותא על בבא קמא", "הערות על חברותא על בבא קמא", "no evidence"),
    ]
    for src, tgt, want in reasons:
        got = b.why(src, tgt)
        if got != want:
            fails += 1
            print(f"  FAIL reason {src!r} <- {tgt!r}: got {got!r}, want {want!r}")

    print(f"  {len(cases)} kinds + {len(reasons)} reasons over {len(b)} works, "
          f"{'all pass' if not fails else f'{fails} FAILED'}")
    return fails


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(1 if _selftest() else 0)
