"""Stage untouched raw glossary bodies from a saved replay for isolated preparation timing.

This is not production popup replay: it includes all raw glossary records before
card grouping/deduplication, uses one worker and a fixed staged font/body width.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("replay", type=Path)
    parser.add_argument("--case", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--font", type=Path, default=Path("F:/sohil/NotoSansCJKjp-Regular.otf"))
    parser.add_argument("--width", type=int, default=256, help="Fixed body width in CSS px; not popup geometry")
    args = parser.parse_args()
    if args.out.exists():
        parser.error("Output exists; preserve prior evidence")
    source = args.replay.read_bytes()
    replay = json.loads(source)
    case = next(c for c in replay["cases"] if c["id"] == args.case)
    styles = {s["dictName"]: s["styles"] for s in case["styles"]}
    bodies = []
    for result in case["results"]:
        for glossary in result["term"]["glossaries"]:
            bodies.append(dict(id=f"{args.case}-raw-{len(bodies):03d}",
                               glossary=glossary["glossary"], css=styles.get(glossary["dictName"], ""),
                               width=args.width, fontSize=case["fontSize"]))
    metadata = dict(protocol=1, cases=bodies,
                    font_sha256=hashlib.sha256(args.font.read_bytes()).hexdigest(),
                    input_sha256=hashlib.sha256(source).hexdigest(),
                    source_case=args.case, scope="ungrouped-raw-bodies/fixed-font-and-width/not-popup")
    with zipfile.ZipFile(args.out, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("input.json", json.dumps(metadata, ensure_ascii=False))
    print(f"Staged {len(bodies)} untouched raw bodies; scope={metadata['scope']}; width={args.width}")


if __name__ == "__main__":
    main()
