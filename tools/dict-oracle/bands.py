"""Reports the vertical bands of ink in an image, so two renders can be compared line by line.

Used to tell "the line box is taller" (uniform gap difference) from "the text sits lower within its
line" (offset) from "a line is missing" (band count difference).
"""
import sys
import numpy as np
from PIL import Image


def bands(path, thresh=200, min_gap=1):
    im = Image.open(path).convert("L")
    a = np.asarray(im)
    ink = (a < thresh).any(axis=1)
    out = []
    start = None
    for y, v in enumerate(ink):
        if v and start is None:
            start = y
        elif not v and start is not None:
            out.append((start, y - start))
            start = None
    if start is not None:
        out.append((start, len(ink) - start))
    # merge bands separated by less than min_gap (accents vs base glyphs of the same line)
    merged = []
    for b in out:
        if merged and b[0] - (merged[-1][0] + merged[-1][1]) < min_gap:
            merged[-1] = (merged[-1][0], b[0] + b[1] - merged[-1][0])
        else:
            merged.append(b)
    return im.size, merged


if __name__ == "__main__":
    for p in sys.argv[1:]:
        size, bs = bands(p)
        print(f"{p}  size={size}  bands={len(bs)}")
        for y, h in bs:
            print(f"    y={y:4d} h={h:3d} bottom={y + h}")
