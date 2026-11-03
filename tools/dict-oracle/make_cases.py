"""Generates oracle case files: one gloss per case, drawn from the real Yomitan dictionaries in
F:/sohil/github/dicts, wrapped in the exact schema DictionaryPayloadBuilder.kt emits.

A case pairs a gloss with its dictionary's own style.json so both renderers get identical CSS.

Usage:
  python make_cases.py --out ../out/cases.json [--per-dict 1] [--seed 7] [--only-dict "JMdict"]
"""
import argparse
import hashlib
import json
import os
import random
import re
import sys
from pathlib import Path

DICTS_ROOT = r"F:\sohil\github\dicts\extracted"
REPO = r"F:\sohil\github\mihon-worktree\mihon-worktree-compose"
ORACLE_OUT = os.path.join(REPO, "tools", "dict-oracle", "out")

# base.css default; kept in sync with StructuredGlossaryCompose.DEFAULT_LINE_HEIGHT_FACTOR.
BASE_FONT_PX = 14
LAYOUT_WIDTH = 420
# Device pixel density for both engines. See the note on the case's "density" field.
ORACLE_DENSITY = 2


def resolve_dict_css(name, outdir):
    """Resolve corpus CSS without depending on the retired desktop renderer."""
    if not name:
        return ""
    here = Path(__file__).resolve().parent
    path = Path(name)
    if path.is_absolute() and path.is_file():
        return str(path)
    for base in (Path(outdir), here, here / "out", here / "in" / "shou"):
        candidate = base / path
        if candidate.is_file():
            return str(candidate.resolve())
    raise RuntimeError(f"dictCss not found: {name!r}")


def title_of(sub):
    idx = os.path.join(DICTS_ROOT, sub, "index.json")
    if os.path.isfile(idx):
        try:
            return json.load(open(idx, encoding="utf-8")).get("title", sub)
        except Exception:
            pass
    return sub


def term_banks(sub):
    d = os.path.join(DICTS_ROOT, sub)
    if not os.path.isdir(d):
        return []
    fs = [f for f in os.listdir(d) if re.match(r"term_bank_\d+\.json$", f)]
    return [os.path.join(d, f) for f in sorted(fs, key=lambda n: int(re.search(r"(\d+)", n).group(1)))]


_ZIP_CACHE = {}


def _read_from_zip(sub, names):
    """Read the first matching entry out of the dictionary's zip.

    The `extracted/` folders only contain the JSON banks; the stylesheet is still in the archive
    (Jitendex ships `styles.css`, standard Yomitan ships `style.json`).
    """
    if sub in _ZIP_CACHE:
        return _ZIP_CACHE[sub]
    result = None
    zip_path = os.path.join(os.path.dirname(DICTS_ROOT), sub + ".zip")
    if os.path.isfile(zip_path):
        try:
            import zipfile

            with zipfile.ZipFile(zip_path) as z:
                entries = [n for n in z.namelist() if n.count("/") <= 1]
                for want in names:
                    for n in entries:
                        if n.lower().endswith(want.lower()):
                            data = z.read(n).decode("utf-8", "replace")
                            # style.json wraps the css: {"1": "<css>"} or {"3": "<css>"}
                            if want.lower().endswith(".json"):
                                try:
                                    j = json.loads(data)
                                    if isinstance(j, str):
                                        data = j
                                    elif isinstance(j, dict):
                                        for v in j.values():
                                            if isinstance(v, str):
                                                data = v
                                                break
                                except Exception:
                                    pass
                            result = data
                            break
                    if result:
                        break
        except Exception as exc:  # a corrupt/absent archive must not abort the whole run
            print(f"  ! could not read styles from {zip_path}: {exc}", file=sys.stderr)
    _ZIP_CACHE[sub] = result
    return result


def style_json_for(sub):
    """The dictionary's own stylesheet, as the app reads it into DictionaryStyle.styles."""
    d = os.path.join(DICTS_ROOT, sub)
    for name in ("styles.css", "style.json", "style.css"):
        p = os.path.join(d, name)
        if os.path.isfile(p):
            raw = open(p, encoding="utf-8").read()
            if name.endswith(".json"):
                try:
                    j = json.loads(raw)
                    if isinstance(j, str):
                        return j
                    if isinstance(j, dict):
                        for v in j.values():
                            if isinstance(v, str):
                                return v
                except Exception:
                    return raw
            else:
                return raw
    return _read_from_zip(sub, ["styles.css", "style.json", "style.css"])


def gloss_to_payload_string(g):
    if isinstance(g, str):
        return g
    return json.dumps(g, ensure_ascii=False, separators=(",", ":"))


def media_uris_for(gloss_json, dict_root, dict_name):
    """Maps every `img` `path` in the gloss to a file:// URI so the media actually loads.

    With an empty map the image can never resolve: the reference renderer paints its own
    broken-image box and the Compose side paints its placeholder, so the comparison measures two
    different failure UIs instead of the same picture. Sankoku8's `svg-accent/*.svg` accents
    (0.7em x 1.0em) are the only images in the sampled set, but the mapping is generic.

    The key must be `<dictName>\\0<path>`: `resolveMediaSrc` in renderer.js builds
    `` `${dictName}\\u0000${candidate}` `` and `resolveMediaUri2` looks up
    `map["$dictName\\u0000$path"]`. A plain path key silently resolves to nothing on BOTH sides.
    """
    paths = set()

    def walk(n):
        if isinstance(n, list):
            for x in n:
                walk(x)
            return
        if not isinstance(n, dict):
            return
        if n.get("tag") == "img":
            p = n.get("path")
            if isinstance(p, str) and p.strip():
                paths.add(p.strip())
        for ch in (n.get("content") or []):
            walk(ch)

    try:
        walk(json.loads(gloss_json))
    except Exception:
        return {}
    out = {}
    for p in sorted(paths):
        f = os.path.join(dict_root, p.replace("/", os.sep))
        if os.path.exists(f):
            out["%s\x00%s" % (dict_name, p)] = Path(f).as_uri()
    return out


def pick_gloss(rows, rng, prefer_rich=True):
    """Choose a gloss that is worth comparing: prefer structured content with ruby/lists/tables."""
    best, best_score = None, -1
    for r in rows:
        for g in r[5]:
            s = g if isinstance(g, str) else json.dumps(g, ensure_ascii=False)
            if prefer_rich:
                score = sum(
                    40 if k in s else 0
                    for k in ('"tag": "ruby"', '"tag":"ruby"', '"tag": "table"', '"tag": "img"',
                              '"type": "image"', '"tag": "details"', '"tag": "ol"', '"tag": "ul"',
                              'verticalAlign', 'color-mix', 'backgroundColor', 'fontSize')
                )
                score += min(len(s), 6000) / 6000.0
            else:
                score = rng.random()
            if score > best_score:
                best, best_score = (r, g), score
    return best


def make_cases_for_dict(sub, rng, per_dict, outdir, used_ids, case_index):
    """Returns one case per randomly chosen term (see [per_dict]), or [] if the bank is unusable."""
    banks = term_banks(sub)
    if not banks:
        return []
    title = title_of(sub)
    # Sample a few banks for speed; a random term from each dictionary is what we want.
    bank = rng.choice(banks)
    try:
        rows = json.load(open(bank, encoding="utf-8"))
    except Exception:
        return []
    if not rows:
        return []

    # Pick `per_dict` DISTINCT random terms and emit one case each. The previous loop assigned
    # `chosen` repeatedly and kept only the last, so `--per-dict 2` could never produce 2 cases -
    # asking for more than one term per dictionary silently gave one.
    picked_terms = []
    seen_rows = set()
    for _ in range(per_dict):
        rows_shuffled = rows[:]
        rng.shuffle(rows_shuffled)
        for r in rows_shuffled:
            if not r[5] or id(r) in seen_rows:
                continue
            picked = pick_gloss([r], rng)
            if picked:
                seen_rows.add(id(r))
                picked_terms.append(picked)
                break
    if not picked_terms:
        return []

    css = style_json_for(sub)
    css_name = None
    if css:
        css_name = f"dictcss_{re.sub(r'[^A-Za-z0-9_.-]', '_', title)}.css"
        with open(os.path.join(outdir, css_name), "w", encoding="utf-8") as fh:
            fh.write(css)

    # Artifact id: a stable readable index plus an ASCII slug. Most Yomitan titles AND directory
    # names are CJK, and an id made of underscores makes the score table impossible to act on, so
    # fall back to a short digest of the title when the slug comes out empty.
    slug = re.sub(r"[^A-Za-z0-9]+", "-", title).strip("-").lower()[:28]
    if not slug:
        slug = hashlib.sha1(title.encode("utf-8")).hexdigest()[:8]

    out_cases = []
    for chosen in picked_terms:
        row, g = chosen
        expr, reading, def_tags, rules, _score, _glosses, _seq, term_tags = row
        base_cid = f"{case_index:02d}_{slug}"
        cid = base_cid
        dup = 1
        while cid in used_ids:
            dup += 1
            cid = f"{base_cid}-{dup}"
        used_ids.add(cid)
        case_index += 1
        payload = {
            "isDark": False,
            "groupTerms": True,
            "showPitchDiagram": False,
            "showPitchNumber": False,
            "showPitchText": False,
            "showFrequencyHarmonic": False,
            "showFrequencyAverage": False,
            "wordAudioEnabled": False,
            "renderRecursiveChrome": False,
            "showNavigationButtons": False,
            "dictionaryOrder": [],
            "tabs": [],
            "existingExpressions": [],
            "mediaDataUris": media_uris_for(gloss_to_payload_string(g), sub, title),
            "dictionaryCollapseMode": "expand_all",
            "dictionaryDisplayModes": {},
            "styles": [{"dictName": title, "styles": css or ""}],
            "results": [{
                "index": 0,
                "matched": expr,
                "deinflected": expr,
                "process": [],
                "term": {
                    "expression": expr,
                    "reading": reading,
                    "rules": rules,
                    "ruleTags": [t for t in re.split(r"\s+", rules or "") if t],
                    "glossaries": [{
                        "dictName": title,
                        "glossary": gloss_to_payload_string(g),
                        "definitionTags": def_tags,
                        "definitionTagList": [t for t in re.split(r"\s+", def_tags or "") if t],
                        "termTags": term_tags,
                    }],
                    "frequencies": [],
                    "pitches": [],
                },
            }],
        }
        out_cases.append({
            "id": cid,
            "dictName": title,
            "dictSubdir": sub,
            "term": expr,
            "glossary": payload["results"][0]["term"]["glossaries"][0]["glossary"],
            "dictCss": css_name or "",
            "width": LAYOUT_WIDTH,
            "fontSize": BASE_FONT_PX,
            "isDark": False,
            "font": "MS PGothic",
            # Device pixel density for BOTH engines. Skia snaps glyph advances and line boxes to whole
            # device pixels, so at density 1 a 19.6px line box becomes 20 and a CJK advance is quantised
            # to 1 CSS px: the vertical rhythm and the wrap points then drift from the browser for
            # reasons that have nothing to do with the renderer. Real devices run at 2-3, and the
            # browser is rendered at a matching deviceScaleFactor.
            "density": ORACLE_DENSITY,
            "payload": payload,
        })
    return out_cases


def collect_curated(outdir, used_ids, start_ordinal):
    """Appends every tools/dict-oracle/in/<name>/cases.json to the corpus.

    A curated case may name a dictCss file that already sits in out/. That copy is
    regenerated from the extracted dictionary when the dictionary is present, because a
    hand-maintained copy in out/ is exactly how a stylesheet fix silently stops being tested.
    """
    in_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "in")
    if not os.path.isdir(in_dir):
        return []
    out = []
    for name in sorted(os.listdir(in_dir)):
        path = os.path.join(in_dir, name, "cases.json")
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as fh:
            curated = json.load(fh)
        for case in curated:
            sub = case.get("dictSubdir")
            css_name = case.get("dictCss") or ""
            if sub and css_name:
                sheet = style_json_for(sub)
                if sheet:
                    dest = os.path.join(outdir, css_name)
                    # Normalise line endings: the dictionary ships CRLF and the committed copy is
                    # LF, so a byte comparison would always report a spurious difference.
                    body = sheet.replace("\r\n", "\n")
                    existing = None
                    if os.path.isfile(dest):
                        with open(dest, encoding="utf-8") as fh:
                            existing = fh.read()
                    if existing != body:
                        with open(dest, "w", encoding="utf-8", newline="\n") as fh:
                            fh.write(body)
                        print(f"  refreshed {css_name} from {sub}")
                    case["dictCss"] = css_name
            out.append(case)
            used_ids.add(case.get("id"))
        print(f"  + curated {name}: {len(curated)} case(s)")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ORACLE_OUT, "cases.json"))
    ap.add_argument("--per-dict", type=int, default=1)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--only-dict", default=None)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    outdir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(outdir, exist_ok=True)
    rng = random.Random(args.seed)

    subs = sorted(os.listdir(DICTS_ROOT))
    cases = []
    skipped = []
    used_ids = set()
    for sub in subs:
        if args.only_dict and args.only_dict.lower() not in title_of(sub).lower():
            continue
        made = make_cases_for_dict(sub, rng, args.per_dict, outdir, used_ids, len(cases) + 1)
        if made:
            cases.extend(made)
        else:
            skipped.append(title_of(sub))
        if args.limit and len(cases) >= args.limit:
            break

    # Curated cases: hand-picked glosses that exercise constructs a random sample will not
    # reliably hit (display: grid tables, display: contents rows, CJK data-sc-* keys).
    # They live in tools/dict-oracle/in/<name>/cases.json and are appended to the SAME corpus so
    # one run - and one mean - tracks everything. Each curated case's dictCss is refreshed from
    # the extracted dictionary here, so the stylesheet under test can never be a stale copy.
    curated = collect_curated(outdir, used_ids, len(cases) + 1)
    cases.extend(curated)

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(cases, fh, ensure_ascii=False, indent=1)

    print(f"wrote {args.out}: {len(cases)} case(s) "
          f"({len(cases) - len(curated)} sampled + {len(curated)} curated), "
          f"skipped {len(skipped)} dictionary(ies)")
    if skipped:
        print("skipped:", ", ".join(skipped))
    for c in cases:
        print(f"  {c['id']}: gloss={len(c['glossary'])}B css={'yes' if c['dictCss'] else 'no'}")


if __name__ == "__main__":
    main()
