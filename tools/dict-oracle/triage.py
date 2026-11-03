"""Splits each case's disagreement into the two things that need completely different fixes.

The oracle's fuzzy score cannot tell them apart: a whole extra line caused by a collapsed margin
scores about the same as a one-character wrap difference caused by sub-pixel glyph advances. So
compare line COUNTS first. A different count means a block-layout bug (margins, padding, or a
missing block break). An equal count with shifted line offsets means the ink is on the right lines
and only sub-pixel measurement differs, which is a text-engine limit rather than a renderer bug.

Usage: python triage.py [out_dir]
"""

import json
import os
import re
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out")


def scores(log_name):
    path = os.path.join(OUT, log_name)
    if not os.path.exists(path):
        return {}
    raw = open(path, "rb").read()
    for enc in ("utf-16", "utf-8", "cp1252"):
        try:
            txt = raw.decode(enc)
        except Exception:
            continue
        found = {}
        for line in txt.splitlines():
            m = re.search(r"(?:FAIL|PASS)\s+(\S+?):\s+fuzzy=([\d.]+)", line)
            if m:
                found[m.group(1)] = float(m.group(2))
        if found:
            return found
    return {}


def bands(path):
    """Row bands of ink, as (x0, x1) per band."""
    if not os.path.exists(path):
        return []
    a = np.asarray(Image.open(path).convert("L")) < 200
    rows = np.where(a.any(axis=1))[0]
    if rows.size == 0:
        return []
    out = []
    start = prev = rows[0]
    for y in rows[1:]:
        if y - prev > 1:
            out.append((start, prev))
            start = y
        prev = y
    out.append((start, prev))
    res = []
    for y0, y1 in out:
        cols = np.where(a[y0 : y1 + 1].any(axis=0))[0]
        res.append((int(cols.min()), int(cols.max())))
    return res


def main():
    score = scores(sys.argv[2] if len(sys.argv) > 2 else "sweep10.log")
    breaks = {}
    bp = os.path.join(OUT, "line_breaks_js.json")
    if os.path.exists(bp):
        breaks = json.load(open(bp, encoding="utf-8"))

    rows = []
    for cid, sc in sorted(score.items(), key=lambda kv: kv[1]):
        js = bands(os.path.join(OUT, f"{cid}_js.png"))
        cp = bands(os.path.join(OUT, f"{cid}_compose.png"))
        jb = breaks.get(cid) or []
        # The browser reports one line list for the whole gloss, so the count is directly comparable
        # with the number of ink bands Compose produced.
        jlines = sum(len(b["lines"]) for b in jb) if jb else len(js)
        dcount = len(cp) - jlines
        # First line whose left edge is off by more than 3 CSS px: below that threshold the
        # difference is glyph side bearing, above it the box is placed differently.
        shift = None
        for i in range(min(len(js), len(cp))):
            if abs(cp[i][0] - js[i][0]) > 3:
                shift = i
                break
        kind = "STRUCT" if dcount != 0 else ("shift" if shift is not None else "wrap-only")
        rows.append((sc, cid, jlines, len(cp), dcount, shift, kind))

    print("%-32s %6s %6s %6s %6s %5s  %s" % ("case", "fuzzy", "js", "cmp", "dLines", "shift", "kind"))
    for sc, cid, jl, cl, dc, sh, kind in rows:
        print(
            "%-32s %6.2f %6d %6d %+6d %5s  %s"
            % (cid, sc, jl, cl, dc, "-" if sh is None else sh, kind)
        )

    n_struct = sum(1 for r in rows if r[6] == "STRUCT")
    n_shift = sum(1 for r in rows if r[6] == "shift")
    print(
        "\n%d structural (line count differs), %d shifted, %d wrap-only"
        % (n_struct, n_shift, len(rows) - n_struct - n_shift)
    )
    print("structurals worst first: " + ", ".join(r[1] for r in rows if r[6] == "STRUCT"[:8]))


if __name__ == "__main__":
    main()
