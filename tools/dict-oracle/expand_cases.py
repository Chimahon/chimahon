"""Extend the frozen fidelity gate with distinct real terms and display variants."""
import argparse
import copy
from collections import Counter
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import random

from make_cases import DICTS_ROOT, gloss_to_payload_string, style_json_for, term_banks, title_of
from make_cases import resolve_dict_css

HERE = Path(__file__).resolve().parent


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def content_key(glossary):
    try:
        glossary = json.dumps(json.loads(glossary), sort_keys=True, ensure_ascii=False)
    except ValueError:
        pass
    return digest(glossary)


def features(glossary):
    found = set()

    def tag(name, depth):
        group = {"rt": "ruby", "ruby": "ruby", "table": "table", "img": "media",
                 "ol": "list", "ul": "list", "a": "link", "p": "paragraph", "div": "paragraph"}.get(name)
        if group:
            found.add(group)
        if name in ("ol", "ul") and depth:
            found.add("nested-list")

    def walk(node, depth=0):
        if isinstance(node, list):
            for child in node:
                walk(child, depth)
        elif isinstance(node, dict):
            name = node.get("tag", "")
            tag(name, depth)
            style = node.get("style", {})
            if isinstance(style, dict) and "vertical" in str(style.get("writingMode", "")):
                found.add("vertical")
            for child in node.values():
                walk(child, depth + (name in ("ol", "ul")))

    try:
        walk(json.loads(glossary))
    except ValueError:
        class Tags(HTMLParser):
            def handle_starttag(self, name, attrs):
                tag(name, 0)
        Tags().feed(glossary)
    return found or {"plain"}


def choose(candidates, count):
    """Prefer feature coverage, then different lengths; hashes resolve ties reproducibly."""
    selected, covered = [], Counter()
    while candidates and len(selected) < count:
        def rank(candidate):
            groups = candidate[3]
            rare = sum((4 if g in {"ruby", "table", "media", "vertical", "nested-list"} else 1)
                       / (1 + covered[g]) for g in groups)
            length = min(3, len(candidate[2]) // 1000)
            return (rare + 2 / (1 + covered[f"length-{length}"]), digest(candidate[2]))
        picked = max(candidates, key=rank)
        selected.append(picked)
        covered.update(picked[3])
        covered.update([f"length-{min(3, len(picked[2]) // 1000)}"])
        candidates = [c for c in candidates if c[0][0] != picked[0][0]]
    return selected


def expand(baseline, destination, per_dict=3):
    original = json.loads(baseline.read_text(encoding="utf-8"))
    cases = copy.deepcopy(original)
    for case in cases:
        for key in ("dictCss", "dictCssPath"):
            if case.get(key):
                case[key] = resolve_dict_css(case[key], str(baseline.parent))
    known = {(c["dictName"], content_key(c["glossary"])) for c in original}
    terms = {(c["dictName"], c["term"]) for c in original}
    sources, skipped = {}, []
    prototypes = {c["dictName"]: c for c in original}
    for folder in sorted(Path(DICTS_ROOT).iterdir()):
        title = title_of(folder.name)
        if title not in prototypes:
            continue
        banks = term_banks(folder.name)
        if not banks:
            continue
        # Spread over the dictionary rather than repeatedly sampling its first bank.
        indexes = sorted({i * (len(banks) - 1) // 3 for i in range(4)})
        candidates = []
        for index in indexes:
            rows = json.loads(Path(banks[index]).read_text(encoding="utf-8"))
            sample = random.Random(digest(folder.name + Path(banks[index]).name))
            for row_index in sorted(sample.sample(range(len(rows)), min(1024, len(rows)))):
                row = rows[row_index]
                if len(row) < 8 or (title, row[0]) in terms:
                    continue
                for gloss_index, gloss in enumerate(row[5]):
                    text = gloss_to_payload_string(gloss)
                    key = (title, content_key(text))
                    if not 20 <= len(text) <= 4000 or key in known:
                        continue
                    known.add(key)
                    candidates.append((row, (Path(banks[index]).name, row_index, gloss_index), text, features(text)))
        selected = choose(candidates, per_dict)
        if not selected:
            skipped.append(title)
            continue
        css = style_json_for(folder.name) or ""
        css_file = destination / f"dict-{digest(folder.name)[:12]}.css"
        if css:
            css_file.write_text(css, encoding="utf-8", newline="\n")
        for row, source, text, groups in selected:
            case = copy.deepcopy(prototypes[title])
            case.pop("dictCssPath", None)
            case.update(id=f"extra_{digest(title + text)[:16]}", term=row[0], glossary=text,
                        dictSubdir=folder.name, dictCss=str(css_file.resolve()) if css else "")
            payload = case["payload"]
            payload["styles"] = [{"dictName": title, "styles": css}]
            payload["mediaDataUris"] = {}  # The fidelity runner stages actual media bytes.
            entry = payload["results"][0]
            entry.update(matched=row[0], deinflected=row[0], process=[])
            entry["term"].update(expression=row[0], reading=row[1], rules=row[3],
                                 ruleTags=(row[3] or "").split(), glossaries=[{
                                     "dictName": title, "glossary": text, "definitionTags": row[2],
                                     "definitionTagList": (row[2] or "").split(), "termTags": row[7]}])
            cases.append(case)
            terms.add((title, row[0]))
            sources[case["id"]] = {"bank": source[0], "row": source[1], "glossary": source[2],
                                   "features": sorted(groups)}
        print(f"Added {len(selected)} terms: {title}", flush=True)

    # Small width/theme/font matrix on compact examples; original JSON and CSS stay intact.
    variant_ids, selected_ids = [], set()
    base_cases = list(cases)
    for group in ("ruby", "table", "media", "vertical", "nested-list", "list", "plain"):
        pool = [c for c in base_cases if group in features(c["glossary"]) and len(c["glossary"]) < 1500]
        for case in sorted(pool, key=lambda c: digest(c["id"])):
            if case["id"] in selected_ids:
                continue
            selected_ids.add(case["id"])
            for mode, width, font, dark in (("narrow-dark", 280, 14, True), ("large-font", 360, 18, False)):
                variant = copy.deepcopy(case)
                variant.update(id=f"{case['id']}__{mode}", width=width, fontSize=font, isDark=dark)
                variant["payload"]["isDark"] = dark
                cases.append(variant)
                variant_ids.append(variant["id"])
            break
    coverage = Counter(g for case in cases for g in features(case["glossary"]))
    manifest = {"baseline": str(baseline.resolve()), "baseline_sha256": hashlib.sha256(baseline.read_bytes()).hexdigest(),
                "baseline_ids": [c["id"] for c in original], "new_term_sources": sources,
                "variant_ids": variant_ids, "skipped": skipped, "coverage": dict(sorted(coverage.items())),
                "total": len(cases)}
    return cases, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=HERE / "out" / "cases.json")
    parser.add_argument("--out", type=Path, required=True, help="New directory; never overwrites the frozen gate")
    parser.add_argument("--per-dict", type=int, default=3)
    args = parser.parse_args()
    if args.per_dict < 1:
        parser.error("--per-dict must be positive")
    args.out.mkdir(parents=True, exist_ok=False)
    cases, manifest = expand(args.baseline, args.out, args.per_dict)
    for name, value in (("cases.json", cases), ("manifest.json", manifest)):
        (args.out / name).write_text(json.dumps(value, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({"total": len(cases), "new_terms": len(manifest["new_term_sources"]),
                      "variants": len(manifest["variant_ids"]), "coverage": manifest["coverage"]}, indent=2))


if __name__ == "__main__":
    main()
