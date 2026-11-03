"""Reproduce dictionary headwords corresponding to the latest popup trace queries."""
import copy
import json
from pathlib import Path
from make_cases import DICTS_ROOT, gloss_to_payload_string, media_uris_for, style_json_for, term_banks, title_of

ROOT = Path(__file__).resolve().parent
DESTINATION = ROOT / 'in' / 'trace-ruby-20261010'
TERMS = {'変わる', '最近', '気付く', '気が付く', '様変わり', '綺麗', '山並み', '夕焼け', '感覚', '欠ける'}


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    prototypes = json.loads((ROOT / 'out' / 'cases.json').read_text(encoding='utf-8'))
    result = []
    for sub in ('[JA-JA] 小学館例解学習国語 第十二版', 'jitendex-yomitan'):
        prototype = next(c for c in prototypes if c['dictSubdir'] == sub)
        title = title_of(sub)
        css = DESTINATION / ('shogakukan.css' if '小学館' in sub else 'jitendex.css')
        css.write_text(style_json_for(sub) or '', encoding='utf-8')
        found = set()
        for bank in term_banks(sub):
            for row in json.loads(Path(bank).read_text(encoding='utf-8')):
                expression = row[0]
                if expression not in TERMS or expression in found or not row[5]:
                    continue
                found.add(expression)
                case = copy.deepcopy(prototype)
                case.update(id=f'trace_{len(result):02d}_{expression}', term=expression,
                            glossary=gloss_to_payload_string(row[5][0]), dictCss=str(css),
                            fontFile='F:/sohil/NotoSansCJKjp-Regular.otf')
                case['payload']['mediaDataUris'] = media_uris_for(case['glossary'], sub, title)
                case['payload']['styles'] = [{'dictName': title, 'styles': css.read_text(encoding='utf-8')}]
                entry = case['payload']['results'][0]
                entry.update(matched=expression, deinflected=expression)
                entry['term'].update(expression=expression, reading=row[1])
                entry['term']['glossaries'] = [{'dictName': title, 'glossary': case['glossary'],
                                              'definitionTags': row[2], 'definitionTagList': [], 'termTags': row[7]}]
                result.append(case)
            if found == TERMS:
                break
        print(title, 'found:', sorted(found), 'missing:', sorted(TERMS-found))
    output = DESTINATION / 'cases.json'
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(output, len(result))


if __name__ == '__main__':
    main()
