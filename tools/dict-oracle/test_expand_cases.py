import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import expand_cases as corpus


class ExpandedCorpusTest(unittest.TestCase):
    def test_content_identity_ignores_json_format(self):
        self.assertEqual(corpus.content_key('{"content":"word", "tag":"p"}'),
                         corpus.content_key('{"tag":"p","content":"word"}'))

    def test_features_include_nested_lists_and_ruby(self):
        text = json.dumps({"tag": "ul", "content": [{"tag": "li", "content": [
            {"tag": "ol", "content": []}, {"tag": "ruby", "content": "base"}]}]})
        self.assertEqual(corpus.features(text), {"list", "nested-list", "ruby"})
        self.assertEqual(corpus.features('<table><tr><td>text</td></tr></table>'), {"table"})

    def test_selection_covers_features_and_distinct_terms(self):
        candidates = [(["same"], (), "table text", {"table"}),
                      (["same"], (), "ruby text", {"ruby"}),
                      (["different"], (), "plain text", {"plain"})]
        selected = corpus.choose(candidates, 3)
        self.assertEqual(len(selected), 2)
        self.assertEqual(len({item[0][0] for item in selected}), 2)

    def test_preserves_gate_and_generates_repeatable_valid_variants(self):
        with tempfile.TemporaryDirectory(dir=corpus.HERE / "out") as folder:
            root = Path(folder)
            dictionary = root / "dictionary"
            dictionary.mkdir()
            (dictionary / "index.json").write_text('{"title":"Test dictionary"}', encoding="utf-8")
            old_gloss = '{"tag":"p","content":"Existing dictionary definition"}'
            rows = [["old", "", "", "", 0, [json.loads(old_gloss)], 0, ""],
                    ["ruby", "reading", "tag", "rule", 0, [{"tag": "ruby", "content": "New ruby definition"}], 1, ""],
                    ["table", "", None, None, 0, [{"tag": "table", "content": "New table definition"}], 2, ""],
                    ["plain", "", "", "", 0, ["Another plain dictionary definition"], 3, ""]]
            (dictionary / "term_bank_1.json").write_text(json.dumps(rows), encoding="utf-8")
            baseline = [{"id": "original", "dictName": "Test dictionary", "term": "old",
                         "dictSubdir": "dictionary", "dictCss": "", "glossary": old_gloss,
                         "width": 420, "fontSize": 14, "isDark": False, "payload": {"isDark": False,
                             "results": [{"term": {"expression": "old", "reading": "", "glossaries": [
                                 {"dictName": "Test dictionary", "glossary": old_gloss}]}}]}}]
            source = root / "baseline.json"
            source.write_text(json.dumps(baseline), encoding="utf-8")
            before = source.read_bytes()
            destination = root / "expanded"
            destination.mkdir()
            with patch.object(corpus, "DICTS_ROOT", str(root)), \
                    patch("make_cases.DICTS_ROOT", str(root)), \
                    patch.object(corpus, "style_json_for", return_value="p { color: blue; }"):
                cases, manifest = corpus.expand(source, destination)
                again, metadata = corpus.expand(source, destination)
            self.assertEqual(cases, again)
            self.assertEqual(manifest, metadata)
            self.assertEqual(source.read_bytes(), before)
            self.assertEqual(cases[0], baseline[0])
            self.assertEqual(len(manifest["new_term_sources"]), 3)
            self.assertEqual(len({case["id"] for case in cases}), len(cases))
            self.assertTrue(all(case["id"].count("__") <= 1 for case in cases))
            for case in cases:
                glossaries = case["payload"]["results"][0]["term"]["glossaries"]
                self.assertEqual(len(glossaries), 1)
                self.assertEqual(case["glossary"], glossaries[0]["glossary"])
                if case["id"] != "original":
                    self.assertEqual(Path(case["dictCss"]).name, f"dict-{corpus.digest('dictionary')[:12]}.css")
                    self.assertEqual(Path(case["dictCss"]).read_text(encoding="utf-8"),
                                     case["payload"]["styles"][0]["styles"])
                if case["id"].endswith("narrow-dark"):
                    self.assertEqual(case["width"], 280)
                    self.assertTrue(case["payload"]["isDark"])


if __name__ == "__main__":
    unittest.main()
