"""Append unchanged Shogakukan 考える/物 entries in normal and narrow themes."""
import argparse
import copy
import json
from pathlib import Path

from make_cases import resolve_dict_css


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--sources', type=Path, nargs='+', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    cases = json.loads(args.baseline.read_text(encoding='utf-8'))
    for case in cases:
        case['dictCss'] = resolve_dict_css(case.get('dictCssPath') or case.get('dictCss', ''), str(args.baseline.parent))
        case.pop('dictCssPath', None)
    added = []
    for source in args.sources:
        entries = json.loads(source.read_text(encoding='utf-8'))
        for index, original in enumerate(entries):
            slug = 'kangaeru' if original['term'] == '考える' else 'mono'
            variants = [
                ('light', False, 420, 14), ('dark', True, 420, 14), ('narrow-dark', True, 280, 16),
            ]
            if slug == 'kangaeru' and index == 1:
                variants.extend([('header-wrap-dark', True, 240, 16), ('header-tight-dark', True, 220, 16),
                                 ('header-boundary-dark', True, 252, 16), ('header-boundary-tight-dark', True, 248, 16)])
            for variant, dark, width, font in variants:
                case = copy.deepcopy(original)
                case.update(id=f'shogakukan-{slug}-{index}-{variant}', isDark=dark, width=width, fontSize=font)
                case['payload']['isDark'] = dark
                case['dictCss'] = resolve_dict_css(case.get('dictCssPath') or case.get('dictCss', ''), str(source.parent))
                case.pop('dictCssPath', None)
                added.append(case)
    args.out.mkdir(parents=True, exist_ok=False)
    for name, values in (('cases.json', cases + added), ('focus.json', added)):
        (args.out / name).write_text(json.dumps(values, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'{len(added)} new cases; {len(cases) + len(added)} total')


if __name__ == '__main__':
    main()
