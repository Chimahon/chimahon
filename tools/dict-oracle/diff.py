"""Pixel comparison between the JS reference render and the Compose render.

Two metrics are reported, because a browser text layout and a Compose text layout cannot be made
bit-identical (different rasterisers and hinting), and a single strict number would measure the
rasteriser rather than the renderer:

  strict  - fraction of pixels whose max per-channel difference is <= TOLERANCE
  fuzzy   - same, but on images blurred with a 1px box filter first, which absorbs sub-pixel and
            antialiased-edge differences while preserving glyph shape, position and colour blocks

A glyph that moved by a whole pixel, a wrong colour, a missing ruby reading or a stray rule all
survive blurring, so `fuzzy` is the honest "does it look the same" number. Both are always printed.
"""
import json
import sys
import numpy as np
from PIL import Image, ImageFilter

TOLERANCE = 8


def load(path):
    return Image.open(path).convert("RGB")


def similarity(a, b, tolerance=TOLERANCE, blur=False):
    # Accepts either a path or an already-loaded Image.
    ia = a if isinstance(a, Image.Image) else load(a)
    ib = b if isinstance(b, Image.Image) else load(b)
    if blur:
        ia = ia.filter(ImageFilter.BoxBlur(1))
        ib = ib.filter(ImageFilter.BoxBlur(1))
    A = np.asarray(ia).astype(np.int16)
    B = np.asarray(ib).astype(np.int16)
    if A.shape != B.shape:
        return None
    d = np.abs(A - B).max(axis=2)
    return float((d <= tolerance).mean() * 100.0)


def profile_similarity(a, b, background=(255, 255, 255)):
    """How much ink sits on each row and each column, compared as distributions.

    The padded-canvas pixel score cannot tell a small shift from missing content: a padded
    pixel is background on both sides, so it MATCHES, and a native render that is a whole
    paragraph too tall scores HIGHER than one that is correct, while a single pixel of text
    drift is charged in full. The content-region pixel score fixes the dilution but then
    over-corrects - a glyph core is one or two pixels wide, so a fraction of a pixel of drift
    misses every core.

    The ink profile has neither problem. How much ink is on each row and each column barely
    changes when the text shifts by a pixel, and changes completely when content is missing or
    in the wrong place. It is the quantity the eye actually reads off a page: the text is in
    the right place, or it is not.

    The row and column scores are averaged: either axis alone would let a layout error on the
    other axis pass.
    """
    ia = a if isinstance(a, Image.Image) else load(a)
    ib = b if isinstance(b, Image.Image) else load(b)
    width = max(ia.width, ib.width)
    height = max(ia.height, ib.height)
    canvas = Image.new("RGB", (width, height), background)
    canvas.paste(ia, (0, 0))
    pa = canvas
    canvas = Image.new("RGB", (width, height), background)
    canvas.paste(ib, (0, 0))
    pb = canvas
    bg = np.asarray(background, dtype=np.int16)
    ink_a = np.abs(np.asarray(pa).astype(np.int16) - bg).max(axis=2)
    ink_b = np.abs(np.asarray(pb).astype(np.int16) - bg).max(axis=2)
    # Coverage, not mass: the ink is divided by the strongest contrast in its own image, so a
    # CORRECTION to the text colour (the theme's foreground is rgb(31,31,31), not black) does not
    # change the profile, while content that is missing still does - the rows that carried it now
    # read as uncovered.
    peak_a = max(1, int(ink_a.max()))
    peak_b = max(1, int(ink_b.max()))
    ink_a = ink_a.astype(np.float64) / peak_a
    ink_b = ink_b.astype(np.float64) / peak_b
    def axis_score(x, y):
        total = x.sum() + y.sum()
        if total <= 0:
            return None
        return 100.0 * (1.0 - np.abs(x - y).sum() / total)
    rows = axis_score(ink_a.sum(axis=1).astype(np.float64), ink_b.sum(axis=1).astype(np.float64))
    cols = axis_score(ink_a.sum(axis=0).astype(np.float64), ink_b.sum(axis=0).astype(np.float64))
    if rows is None and cols is None:
        return None
    if rows is None:
        return cols
    if cols is None:
        return rows
    return (rows + cols) / 2.0


def content_similarity(a, b, background=(255, 255, 255), tolerance=24, blur=4, solid=0.5):
    """Similarity over the region where EITHER image has ink, instead of the padded canvas.

    `align_and_compare` pads both images with the background to the larger of the two sizes so
    that extra or missing content stays visible. But a padded pixel is background on BOTH sides,
    so it MATCHES, and the score - which is a pixel count over the canvas area - is diluted by
    exactly the difference the padding was meant to expose. A native render that is a whole
    paragraph too tall therefore scores HIGHER than one that is correct, while a single pixel of
    text drift is charged in full.

    Scoring the union of the two ink regions removes that: the denominator is the area that
    either side actually drew on, so extra content adds area that does not match and a shifted
    glyph mismatches exactly the ink it covers.

    The comparison itself stays tolerant - a 4px box blur and a tolerance of 24 absorb the
    sub-pixel offset between two independently rasterised text layouts, which would otherwise
    be charged for every glyph core. What survives the blur is missing content, a displaced
    block or a wrong colour, which is what the score should be about.

    Calibrated against the oxford-zh-en cases, where the padded metric ranked the WRONG layout
    (an extra line of text) 14 points above the correct one:

        blur/tolerance    correct layout    wrong layout
        1/8                       4.2              1.9
        3/24                     36.8             21.6
        4/24                     55.9             34.5
    """
    ia = a if isinstance(a, Image.Image) else load(a)
    ib = b if isinstance(b, Image.Image) else load(b)
    if ia.size != ib.size:
        width = max(ia.width, ib.width)
        height = max(ia.height, ib.height)
        canvas = Image.new("RGB", (width, height), background)
        canvas.paste(ia, (0, 0))
        ia = canvas
        canvas = Image.new("RGB", (width, height), background)
        canvas.paste(ib, (0, 0))
        ib = canvas
    bg = np.asarray(background, dtype=np.int16)
    raw_a = np.asarray(ia).astype(np.int16)
    raw_b = np.asarray(ib).astype(np.int16)
    # "Solid" ink: more than half of this image's own maximum contrast, so the antialiased halo
    # around a glyph is not counted as content.
    delta_a = np.abs(raw_a - bg).max(axis=2)
    delta_b = np.abs(raw_b - bg).max(axis=2)
    region = (delta_a > solid * max(1, delta_a.max())) | (delta_b > solid * max(1, delta_b.max()))
    total = int(region.sum())
    if total == 0:
        return None
    filt = ImageFilter.BoxBlur(blur)
    A = np.asarray(ia.filter(filt)).astype(np.int16)
    B = np.asarray(ib.filter(filt)).astype(np.int16)
    d = np.abs(A - B).max(axis=2)
    return float(((d <= tolerance) & region).sum() / total * 100.0)


def align_and_compare(js_png, compose_png, out_prefix, js_rect=None, compose_rect=None,
                      candidate_label="compose", background=(255, 255, 255)):
    """Crops both to their reported content rects, then compares.

    rects are (x, y, w, h) in that image's own pixels.
    """
    J, C = load(js_png), load(compose_png)

    def crop(im, r):
        if not r:
            return im
        x, y, w, h = r
        # None means "extend to the edge of the image" - used for the Compose side, whose content
        # height is whatever the renderer produced and we do not know up front.
        x = 0 if x is None else max(0, min(int(round(x)), im.width - 1))
        y = 0 if y is None else max(0, min(int(round(y)), im.height - 1))
        w = im.width - x if w is None else max(1, min(int(round(w)), im.width - x))
        h = im.height - y if h is None else max(1, min(int(round(h)), im.height - y))
        return im.crop((x, y, x + w, y + h))

    J, C = crop(J, js_rect), crop(C, compose_rect)
    # Pad the shorter side with the background so shape differences are visible as size, not crash.
    w = max(J.width, C.width)
    h = max(J.height, C.height)
    bg = background

    def pad(im):
        out = Image.new("RGB", (w, h), bg)
        out.paste(im, (0, 0))
        return out

    Jp, Cp = pad(J), pad(C)
    Jp.save(out_prefix + "_js.png")
    Cp.save(out_prefix + "_" + candidate_label + ".png")

    # Diff visualisation: matching pixels washed out, differing pixels in magenta.
    A = np.asarray(Jp).astype(np.int16)
    B = np.asarray(Cp).astype(np.int16)
    d = np.abs(A - B).max(axis=2)
    mask = d > TOLERANCE
    vis = (A * 0.25 + 255 * 0.75).astype(np.uint8)
    vis[mask] = [255, 0, 200]
    Image.fromarray(vis).save(out_prefix + "_diff.png")

    return {
        "js_size": [J.width, J.height],
        "compose_size": [C.width, C.height],
        "canvas": [w, h],
        "size_match": [J.width, J.height] == [C.width, C.height],
        "strict": similarity(Jp, Cp, TOLERANCE, blur=False),
        "fuzzy": similarity(Jp, Cp, TOLERANCE, blur=True),
        "diff_pixels": int(mask.sum()),
        "diff_ratio_pct": round(100.0 * mask.sum() / mask.size, 3),
        "max_channel_diff": int(d.max()),
    }


if __name__ == "__main__":
    js, comp, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
    jr = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None
    cr = json.loads(sys.argv[5]) if len(sys.argv) > 5 else None
    print(json.dumps(align_and_compare(js, comp, prefix, jr, cr), ensure_ascii=False))
