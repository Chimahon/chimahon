"""Check that the reported Shogakukan badge fragments never become paragraph-wide bars."""
import argparse
import json
from pathlib import Path


def check(capture):
    wanted = {f'shogakukan-{term}-1-{theme}' for term in ('kangaeru', 'mono')
              for theme in ('light', 'dark', 'narrow-dark')}
    found = set()
    failures = []
    for result in capture['results']:
        if result['id'] not in wanted:
            continue
        found.add(result['id'])
        if not result.get('ok'):
            failures.append((result['id'], result.get('error', 'capture failed')))
            continue
        native = result['native']
        # These entries contain short inline labels, not full-width painted panels.
        # This deliberately loose bound catches the entire-line selection union.
        for rect in native['rects']:
            if rect['w'] > native['width'] / 2:
                failures.append((result['id'], rect))
    if found != wanted:
        failures.append(('missing cases', sorted(wanted - found)))
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    args = parser.parse_args()
    failures = check(json.loads(args.capture.read_text(encoding='utf-8')))
    print(json.dumps({'checked': 6, 'failures': failures}, ensure_ascii=False, indent=2))
    raise SystemExit(bool(failures))


if __name__ == '__main__':
    main()
