"""Select exact real dictionary terms as separate fidelity cases without changing the gate."""
import argparse
import copy
import json
from pathlib import Path

from make_cases import DICTS_ROOT, gloss_to_payload_string, term_banks
from make_cases import resolve_dict_css


def select_cases(baseline, dictionary, term):
    prototypes = json.loads(baseline.read_text(encoding="utf-8"))
    matches = {c["dictName"] for c in prototypes if dictionary in c["dictName"]}
    if len(matches) != 1:
        raise ValueError(f"Expected one dictionary title, found {sorted(matches)}")
    prototype = copy.deepcopy(next(c for c in prototypes if c["dictName"] in matches))
    prototype["dictCss"] = resolve_dict_css(prototype.get("dictCssPath") or prototype.get("dictCss", ""),
                                            str(baseline.parent))
    prototype.pop("dictCssPath", None)
    folder = prototype["dictSubdir"]
    found = []
    for bank in term_banks(folder):
        with open(bank, encoding="utf-8") as stream:
            rows = json.load(stream)
        for row_index, row in enumerate(rows):
            if row[0] != term:
                continue
            for gloss_index, glossary in enumerate(row[5]):
                case = copy.deepcopy(prototype)
                text = gloss_to_payload_string(glossary)
                case.update(id=f"selected-{len(found):03d}", term=term, glossary=text,
                            source={"bank": Path(bank).name, "row": row_index, "glossary": gloss_index})
                payload = case["payload"]
                payload["mediaDataUris"] = {}
                entry = payload["results"][0]
                entry.update(matched=term, deinflected=term, process=[])
                entry["term"].update(expression=term, reading=row[1], rules=row[3], ruleTags=(row[3] or "").split(),
                                     glossaries=[{"dictName": case["dictName"], "glossary": text,
                                                 "definitionTags": row[2], "definitionTagList": (row[2] or "").split(),
                                                 "termTags": row[7]}])
                found.append(case)
        del rows
    if not found:
        raise ValueError(f"No exact term {term!r} in {Path(DICTS_ROOT) / folder}")
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--dictionary", required=True)
    parser.add_argument("--term", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    cases = select_cases(args.baseline.resolve(), args.dictionary, args.term)
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Selected {len(cases)} glossaries for {args.term!a}: {args.out / 'cases.json'}")


if __name__ == "__main__":
    main()
