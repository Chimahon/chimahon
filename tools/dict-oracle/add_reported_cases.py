"""Append reported Manga/wig entries and expanded-example states to the frozen fidelity corpus."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile

from make_cases import resolve_dict_css


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=Path(__file__).parent / "out/expanded-corpus-20261022/cases.json")
    parser.add_argument("--dict-root", type=Path, default=Path("F:/sohil/github/dicts"))
    parser.add_argument("--replay", type=Path, default=Path(__file__).parent.parent / "perf/out/paired-export-20261023/bundle.json")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    cases = json.loads(args.baseline.read_text(encoding="utf-8"))
    for case in cases:
        key = "dictCssPath" if case.get("dictCssPath") else "dictCss"
        if case.get(key):
            case[key] = resolve_dict_css(case[key], str(args.baseline.parent))
    added = []
    # Exact archive records, not rewritten dictionary content or renderer-specific fixtures.
    for slug, pattern, term in (
        ("shogakukan-manga", "*小学館*.zip", "漫画"),
        ("shinwaei-manga", "*新和英*.zip", "漫画"),
        ("jitendex-wig", "jitendex*.zip", "鬘"),
        ("jmdict-wig", "JMdict_english.zip", "鬘"),
        ("jmdict-wig-forms", "JMdict_english.zip", "鬘"),
        ("jmdict-manga", "JMdict_english.zip", "漫画"),
    ):
        archives = list(args.dict_root.glob(pattern))
        if len(archives) != 1:
            raise ValueError(f"Expected one archive for {pattern!r}, got {archives}")
        archive = archives[0]
        with zipfile.ZipFile(archive) as source:
            index = json.loads(source.read("index.json"))
            css = source.read("styles.css").decode("utf-8") if "styles.css" in source.namelist() else ""
            css_path = args.out / f"{slug}.css"
            css_path.write_text(css, encoding="utf-8")
            row = None
            for bank in sorted(n for n in source.namelist() if n.startswith("term_bank_") and n.endswith(".json")):
                rows = json.loads(source.read(bank))
                match = next(((i, r) for i, r in enumerate(rows) if r[0] == term and
                              (slug != "jmdict-wig-forms" or "forms" in r[2].split())), None)
                if match:
                    row_index, row = match
                    break
            if row is None:
                raise ValueError(f"Missing {term!r} in {archive}")
            for gloss_index, glossary in enumerate(row[5]):
                for dark in (False, True):
                    case = copy.deepcopy(cases[0])
                    text = json.dumps(glossary, ensure_ascii=False, separators=(",", ":")) if not isinstance(glossary, str) else glossary
                    case.update(id=f"reported-{slug}-{gloss_index}-{'dark' if dark else 'light'}",
                                dictName=index["title"], dictSubdir=archive.stem, term=term, glossary=text,
                                dictCss=str(css_path.resolve()), width=420, fontSize=14, isDark=dark,
                                source={"archive": archive.name, "bank": bank, "row": row_index, "glossary": gloss_index})
                    case.pop("dictCssPath", None)
                    payload = case["payload"]
                    payload.update(isDark=dark, dictNameForStyles=index["title"], mediaDataUris={},
                                   styles=[{"dictName": index["title"], "styles": css}])
                    entry = payload["results"][0]
                    entry.update(matched=term, deinflected=term, process=[])
                    entry["term"].update(expression=term, reading=row[1], rules=row[3], ruleTags=row[3].split(),
                                         glossaries=[{"dictName": index["title"], "glossary": text,
                                                      "definitionTags": row[2], "definitionTagList": row[2].split(), "termTags": row[7]}])
                    cases.append(case)
                    added.append(case["id"])
    replay = json.loads(args.replay.read_text(encoding="utf-8-sig"))
    title = "Jitendex.org [2025-07-02]"
    found = False
    for lookup in replay["cases"]:
        for result in lookup["results"]:
            glossary = next((g for g in result["term"]["glossaries"]
                             if g["dictName"] == title and '"table"' in g["glossary"]), None)
            if glossary is None:
                continue
            css = next(s["styles"] for s in lookup["styles"] if s["dictName"] == title)
            css_path = args.out / "jitendex-2025-export.css"
            css_path.write_text(css, encoding="utf-8")
            for dark in (False, True):
                case = copy.deepcopy(cases[0])
                case.update(id=f"reported-jitendex-2025-table-{'dark' if dark else 'light'}",
                            dictName=title, term=result["term"]["expression"], glossary=glossary["glossary"],
                            dictCss=str(css_path.resolve()), width=420, fontSize=14, isDark=dark,
                            source={"export": str(args.replay.resolve()), "lookup": lookup["id"]})
                case.pop("dictSubdir", None)
                case.pop("dictCssPath", None)
                case["payload"].update(isDark=dark, dictNameForStyles=title, mediaDataUris={},
                                       styles=[{"dictName": title, "styles": css}], results=[copy.deepcopy(result)])
                case["payload"]["results"][0]["term"]["glossaries"] = [copy.deepcopy(glossary)]
                cases.append(case)
                added.append(case["id"])
            found = True
            break
        if found:
            break
    if not found:
        raise ValueError("No original Jitendex 2025 table found in replay export")
    manga = next(c for c in cases if c["id"] == "reported-shogakukan-manga-0-dark")
    for variant, width, font in (("narrow", 280, 14), ("large", 360, 18)):
        case = copy.deepcopy(manga)
        case.update(id=f"reported-shogakukan-manga-{variant}-dark", width=width, fontSize=font)
        cases.append(case)
        added.append(case["id"])
    for dark in (False, True):
        case = copy.deepcopy(next(c for c in cases if c["id"] == f"reported-shinwaei-manga-0-{'dark' if dark else 'light'}"))
        case.update(id=f"reported-shinwaei-manga-expanded-{'dark' if dark else 'light'}", expandDetails=True)
        cases.append(case)
        added.append(case["id"])
    (args.out / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=1), encoding="utf-8")
    manifest = {"baseline": str(args.baseline.resolve()),
                "baseline_sha256": hashlib.sha256(args.baseline.read_bytes()).hexdigest(), "added": added, "total": len(cases)}
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
