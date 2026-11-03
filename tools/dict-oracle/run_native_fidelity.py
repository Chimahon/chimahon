"""Paired Android WebView/native BODY fidelity. Never builds, installs, or uses the legacy renderer."""
import argparse
import copy
import hashlib
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time
import uuid
import zipfile

import numpy as np
from PIL import Image

from diff import align_and_compare, content_similarity, profile_similarity
from make_cases import resolve_dict_css

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ACTIVITY = "eu.kanade.tachiyomi.ui.dictionary.bench.DictionaryFidelityActivity"

# Extracted dictionary trees. Image cases only score if BOTH sides can load the bytes: the reference
# through an intercepted origin and the native side by decoding them. The emulator cannot read the
# host filesystem, so media is copied into the bundle and served from there.
DICTS_ROOTS = (Path(r"F:\sohil\github\dicts\extracted"), Path(r"F:\sohil\dicts\extracted"))
MEDIA_PREFIX = "media"


def image_paths(glossary_json):
    """Every `img` `path` in a glossary, in stable order.

    Walks the whole tree rather than only `content`: several dictionaries nest an img under a node
    whose `content` is a plain STRING (sankoku8 accents, jitenon markers), so a content-only walk
    iterates characters and misses them entirely.
    """
    found = set()

    def walk(node):
        if isinstance(node, dict):
            if node.get("tag") == "img":
                value = node.get("path")
                if isinstance(value, str) and value.strip():
                    found.add(value.strip())
            for child in node.values():
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    try:
        walk(json.loads(glossary_json))
    except Exception:
        return []
    return sorted(found)


def resolve_media_file(subdir, relative):
    """Locate one media file, falling back to a tree-wide basename search.

    `dictSubdir` is recorded when the corpus is built and can go stale when a dictionary is
    re-extracted under a different folder name (shougakukan became its Japanese title), so a direct
    join failing must not silently drop the image.
    """
    for root in DICTS_ROOTS:
        direct = root / subdir / relative.replace("/", "\\")
        if direct.is_file():
            return direct
        break
    basename = relative.replace("/", "\\").rsplit("\\", 1)[-1]
    for root in DICTS_ROOTS:
        if not root.is_dir():
            continue
        for hit in root.rglob(basename):
            if hit.is_file():
                return hit
    return None


def stage_media(case, prefix):
    """Resolve each image against the dictionary tree and return {bundle entries, mediaDataUris}.

    The key must stay `<dictName>\\0<path>`; `resolveMediaSrc` in renderer.js builds exactly that
    and `resolveMediaUri2` looks it up. A plain path key resolves to nothing on BOTH sides.
    URIs are rewritten to the intercepted origin because the harness sets allowFileAccess=false,
    so the WebView could never load a file:// URL.
    """
    sub = case.get("dictSubdir")
    dict_name = case.get("dictName", "")
    if not sub:
        return [], {}
    entries, uris = [], {}
    for relative in image_paths(case.get("glossary") or ""):
        source = resolve_media_file(sub, relative)
        if source is None:
            continue
        entry = f"{MEDIA_PREFIX}/{prefix}/{relative}"
        entries.append((entry, source.read_bytes()))
        uris[f"{dict_name}\x00{relative}"] = f"https://fidelity.invalid/{entry}"
    return entries, uris


def categories(glossary):
    tags = set()

    def visit(value):
        if isinstance(value, dict):
            if isinstance(value.get("tag"), str):
                tags.add(value["tag"].lower())
            if "ruby" in value:
                tags.add("ruby")
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    try:
        visit(json.loads(glossary))
    except ValueError:
        class Tags(HTMLParser):
            def handle_starttag(self, tag, attrs):
                tags.add(tag)
        Tags().feed(glossary)
    groups = []
    for name, candidates in (("ruby", {"ruby", "rt"}), ("tables", {"table", "td", "th"}),
                             ("lists", {"ul", "ol", "li"}), ("paragraphs", {"div", "p"})):
        if tags & candidates:
            groups.append(name)
    return groups or ["plain"]


def checked_case(case):
    value = copy.deepcopy(case)
    glossaries = [g for result in value["payload"]["results"] for g in result["term"]["glossaries"]]
    if len(glossaries) != 1 or glossaries[0]["glossary"] != value["glossary"]:
        raise ValueError(f"{value['id']}: expected exactly one matching glossary in reference payload")
    if not 1 <= float(value["width"]) <= 4096 or not 1 <= int(value.get("fontSize", 14)) <= 128:
        raise ValueError(f"{value['id']}: invalid width/fontSize")
    value["payload"]["dictNameForStyles"] = glossaries[0]["dictName"]
    value["payload"]["isDark"] = bool(value.get("isDark", False))
    return value


def prepare(cases_file, font_file, out, only=None, limit=None):
    source = json.loads(cases_file.read_text(encoding="utf-8"))
    if only:
        pattern = re.compile(only, re.I)
        source = [c for c in source if pattern.search(" ".join(str(c.get(k, "")) for k in ("id", "dictName", "term")))]
    if limit:
        source = source[:limit]
    if not source or len({c["id"] for c in source}) != len(source):
        raise ValueError("Need a nonempty selection with unique case IDs")
    font_sha = hashlib.sha256(font_file.read_bytes()).hexdigest()
    prepared = []
    payloads = []
    media_files = []
    work = out / "input"
    work.mkdir(parents=True, exist_ok=False)
    for index, original in enumerate(source):
        case = checked_case(original)
        css_path = resolve_dict_css(case.get("dictCssPath") or case.get("dictCss", ""), str(cases_file.parent))
        css = Path(css_path).read_text(encoding="utf-8") if css_path else ""
        prefix = f"case-{index:03d}"
        # Must happen before the payload is written: build_page.mjs inlines it into the reference HTML.
        media_entries, media_uris = stage_media(case, prefix)
        if media_uris:
            case["payload"]["mediaDataUris"] = media_uris
        payload = work / f"{prefix}.payload.json"
        payload.write_text(json.dumps(case["payload"], ensure_ascii=False), encoding="utf-8")
        page = work / f"{prefix}.html"
        command = ["node", str(HERE / "build_page.mjs"), f"--payload={payload}", f"--out={page}",
                   f"--font-file={font_file}", f"--width={case['width']}", f"--font-size={case.get('fontSize', 14)}"]
        if css_path:
            command.append(f"--dict-css={css_path}")
        subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8")
        document = page.read_text(encoding="utf-8")
        font_url = "file:///" + str(font_file).replace("\\", "/")
        if font_url not in document:
            raise ValueError("Reference builder did not expose the expected font URL")
        document = document.replace(font_url, "https://fidelity.invalid/font.otf")
        # The staged font is registered as 'RefCJK', but a browser's font FALLBACK never consults
        # unrelated @font-face rules: a stylesheet asking for "MS PGothic" or a macOS family gets the
        # system default, not the staged file. The native renderer uses the app-level font for
        # everything, so the reference must be pinned to the same one or the two sides are compared
        # in different typefaces and every glyph advance differs.
        #
        # `::marker` has to be pinned too. The `*` selector matches elements, never a pseudo-element,
        # so the list marker was rendered in the browser's default font while the native drew it in
        # the staged one: the reference's circle marker was 16 device pixels wide and the native's
        # was 5. Every list case paid for that in the marker alone.
        font_override = (
            "<style>*, *::before, *::after, ::marker { font-family: 'RefCJK', sans-serif !important; }</style>"
        )
        if "</head>" in document:
            document = document.replace("</head>", font_override + "</head>", 1)
        else:
            document = font_override + document
        page.write_text(document, encoding="utf-8")
        # Fingerprint canonical UTF-8/LF HTML, including shipping assets and fixture overrides.
        prepared.append({"id": case["id"], "dictName": case.get("dictName", ""), "term": case.get("term", ""),
                         "glossary": case["glossary"], "css": css, "width": case["width"],
                         "fontSize": case.get("fontSize", 14), "isDark": bool(case.get("isDark", False)),
                         "expandDetails": bool(case.get("expandDetails", False)),
                         "categories": categories(case["glossary"]), "html_file": page.name,
                         "html_sha256": hashlib.sha256(document.encode("utf-8")).hexdigest()})
        payloads.append(page)
        media_files.extend(media_entries)
    fingerprint = hashlib.sha256(json.dumps(prepared, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    bundle = {"protocol": 1, "font_sha256": font_sha, "input_sha256": fingerprint, "cases": prepared}
    (out / "native_cases.json").write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
    with zipfile.ZipFile(out / "bundle.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("input.json", json.dumps(bundle, ensure_ascii=False))
        for page in payloads:
            archive.write(page, page.name)
        for entry, blob in media_files:
            archive.writestr(entry, blob)
    return bundle


class Adb:
    def __init__(self, executable, package, serial=None):
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]+", package):
            raise ValueError("Invalid Android package")
        self.command = [str(executable)] + (["-s", serial] if serial else [])
        self.package = package

    def run(self, *args, check=True):
        result = subprocess.run(self.command + list(args), capture_output=True, timeout=90)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace") + result.stdout.decode("utf-8", errors="replace"))
        return result

    def read(self, path, check=True):
        return self.run("exec-out", "run-as", self.package, "cat", path, check=check)


def capture(adb, out, bundle, font_file, timeout, hit_check=False, backend="native-flow-v1", reference=None):
    # Android freezes a backgrounded cached app, which suspends the activity's coroutines:
    # a corpus run then sits at 0 cases for as long as the display stays off. Keep the
    # device awake for the whole capture, not just while the window is visible.
    adb.run("shell", "cmd", "settings", "put", "global", "stay_on_while_plugged_in", "7", check=False)
    adb.run("shell", "settings", "put", "system", "screen_off_timeout", "2147483647", check=False)
    adb.run("shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    adb.run("shell", "wm", "dismiss-keyguard", check=False)
    run_id = "native-" + uuid.uuid4().hex[:16]
    remote = f"files/native-fidelity/{run_id}"
    adb.run("shell", "run-as", adb.package, "mkdir", "-p", remote)
    for filename, local in (("bundle.zip", out / "bundle.zip"), ("font.otf", font_file)):
        temporary = f"/data/local/tmp/{run_id}-{filename}"
        adb.run("push", str(local), temporary)
        adb.run("shell", "run-as", adb.package, "cp", temporary, f"{remote}/{filename}")
        adb.run("shell", "rm", "-f", temporary)
    launched = adb.run("shell", "am", "start", "-W", "-n", f"{adb.package}/{ACTIVITY}", "--es", "run_id", run_id,
                       "--ez", "hit_geometry_check", str(hit_check).lower())
    if b"Error" in launched.stdout or b"Error" in launched.stderr:
        raise RuntimeError("Fidelity activity unavailable. Install a new debug APK containing DictionaryFidelityActivity.\n" + launched.stdout.decode(errors="replace"))
    deadline = time.monotonic() + timeout
    previous = -1
    while time.monotonic() < deadline:
        raw = adb.read(f"{remote}/status.json", check=False)
        try:
            status = json.loads(raw.stdout) if raw.returncode == 0 else {}
        except ValueError:
            status = {}
        if status.get("run_id") and status["run_id"] != run_id:
            raise RuntimeError("Stale capture run ID")
        completed = len(status.get("results", []))
        if completed != previous:
            print(f"Captured {completed}/{len(bundle['cases'])}", flush=True)
            previous = completed
        if status.get("done"):
            break
        time.sleep(2)
    else:
        raise TimeoutError("Android fidelity run timed out. Check logcat tag DictFidelity; no scores were generated.")
    return collect_capture(adb, out, bundle, status, remote, backend, reference)


def collect_capture(adb, out, bundle, status, remote, backend="native-flow-v1", reference=None):
    if status.get("capture_geometry") != "css-dpr-v2":
        raise RuntimeError("Outdated capture geometry; rebuild/install the fidelity harness before testing")
    if status.get("backend") != backend or status.get("input_sha256") != bundle["input_sha256"] or status.get("font_sha256") != bundle["font_sha256"]:
        raise RuntimeError("Backend, input or font fingerprint mismatch; refusing stale results")
    (out / "capture.json").write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding="utf-8")
    if status.get("error"):
        raise RuntimeError(status["error"])
    if bool(status.get("reused_reference")) != (reference is not None):
        raise RuntimeError("Harness reference-reuse mismatch; install the updated harness")
    if [r["id"] for r in status["results"]] != [c["id"] for c in bundle["cases"]]:
        raise RuntimeError("Incomplete or reordered case results")
    for result in status["results"]:
        if not result.get("ok"):
            continue
        prefix = result["file"]
        if not re.fullmatch(r"case-[0-9]+", prefix):
            raise ValueError("Unsafe capture filename")
        for engine in ("webview", "native"):
            filename = f"{prefix}-{engine}.png"
            if reference is not None and engine == "webview":
                shutil.copyfile(reference / filename, out / filename)
            else:
                (out / filename).write_bytes(adb.read(f"{remote}/{filename}").stdout)
    # Only this generated run is removed, after every capture has reached the PC.
    # Failed/incomplete runs remain available on the target for diagnosis.
    adb.run("shell", "run-as", adb.package, "rm", "-rf", remote)
    return status


def ink_pixels(path, background):
    pixels = np.asarray(Image.open(path).convert("RGB")).astype(np.int16)
    return int((np.abs(pixels - np.array(background)).max(axis=2) > 16).sum())


def score(out, bundle, capture_report, reference=None):
    if [r.get("id") for r in capture_report["results"]] != [c["id"] for c in bundle["cases"]]:
        raise ValueError("Incomplete or reordered case results; refusing a partial score")
    previous = {}
    if reference and (reference / "summary.json").is_file():
        cached = json.loads((reference / "summary.json").read_text(encoding="utf-8"))
        if all(cached.get(key) == bundle.get(key) for key in ("input_sha256", "font_sha256")):
            previous = {row["id"]: row for row in cached["results"] if row.get("ok")}
    reused_scores = 0
    results = []
    for case, captured in zip(bundle["cases"], capture_report["results"]):
        result = {"id": case["id"], "dictName": case["dictName"], "categories": case["categories"], "ok": False}
        try:
            if not captured.get("ok"):
                raise ValueError(captured.get("error", "Android capture failed"))
            prefix = captured["file"]
            webview, native = out / f"{prefix}-webview.png", out / f"{prefix}-native.png"
            prior = previous.get(case["id"])
            if prior and all((reference / f"{prior['file']}-{kind}.png").is_file() and
                hashlib.sha256((out / f"{prefix}-{kind}.png").read_bytes()).digest() ==
                hashlib.sha256((reference / f"{prior['file']}-{kind}.png").read_bytes()).digest()
                for kind in ("webview", "native")):
                results.append(dict(prior, file=prefix))
                diff = reference / f"{prior['file']}_diff.png"
                if diff.is_file(): shutil.copyfile(diff, out / f"{prefix}_diff.png")
                reused_scores += 1
                continue
            background = (0, 0, 0) if case["isDark"] else (255, 255, 255)
            reference_ink, native_ink = ink_pixels(webview, background), ink_pixels(native, background)
            if reference_ink == 0 or native_ink == 0:
                raise ValueError("Blank reference or native image; refusing a misleading similarity score")
            metrics = align_and_compare(str(webview), str(native), str(out / prefix), candidate_label="native", background=background)
            metrics["webview_size"] = metrics.pop("js_size")
            # The padded-canvas score dilutes its own mismatches: a padded pixel is background on
            # both sides, so it MATCHES, and a native render that is a whole paragraph too tall
            # scores higher than one that is correct while a single pixel of text drift is charged
            # in full. The headline is therefore the INK PROFILE score - how much ink sits on each
            # row and each column - which barely moves for a small shift and moves completely when
            # content is missing or in the wrong place. The two pixel scores are kept beside it.
            metrics["fuzzy_padded"] = metrics["fuzzy"]
            content = content_similarity(str(webview), str(native), background=background)
            if content is not None:
                metrics["fuzzy_content"] = content
            profile = profile_similarity(str(webview), str(native), background=background)
            if profile is not None:
                metrics["fuzzy"] = profile
            metrics["native_size"] = metrics.pop("compose_size")
            result.update(metrics, ok=True, file=prefix, density=captured["density"], reference_ink=reference_ink,
                          native_ink=native_ink, height_delta=metrics["native_size"][1] - metrics["webview_size"][1])
        except (ValueError, OSError) as error:
            result["error"] = str(error)
        results.append(result)
    valid = [r for r in results if r["ok"]]
    grouped = {}
    for key in sorted({c for r in results for c in r["categories"]} | {"all", "non-table"}):
        group = [r for r in valid if key == "all" or key == "non-table" and "tables" not in r["categories"] or key in r["categories"]]
        grouped[key] = {"count": len(group), "fuzzy": statistics.mean(r["fuzzy"] for r in group) if group else None}
    summary = {"backend": capture_report["backend"], "scope": "glossary-body/software-canvas", "font_sha256": bundle["font_sha256"],
               "input_sha256": bundle["input_sha256"],
               "capture_geometry": capture_report.get("capture_geometry"),
               "device": capture_report.get("device"), "webview": capture_report.get("webview"), "groups": grouped,
               "total": len(results), "scored": len(valid), "failed": len(results) - len(valid), "reused_scores": reused_scores, "results": results,
               "note": "Android body-only baseline: compare only the same capture geometry, reference, corpus and font. Not comparable to desktop/headword scores. Not a timing benchmark."}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    rows = []
    for row in sorted(results, key=lambda r: r.get("fuzzy", -1)):
        name = html.escape(row["id"])
        if not row["ok"]:
            rows.append(f"<h2>{name}</h2><pre>{html.escape(row['error'])}</pre>")
            continue
        prefix = row["file"]
        rows.append(f"<h2>{name}: {row['fuzzy']:.2f}% fuzzy; height delta {row['height_delta']}px</h2>" +
                    "<div class='pair'>" + "".join(f"<figure><figcaption>{label}</figcaption><img src='{prefix}{suffix}.png'></figure>"
                    for label, suffix in (("WebView", "-webview"), ("Native", "-native"), ("Difference", "_diff"))) + "</div>")
    report_html = "<!doctype html><meta charset='utf-8'><title>Native fidelity</title><style>body{font:14px system-ui;margin:24px}h2{font-size:17px}.pair{display:flex;gap:16px;overflow:auto}figure{margin:0}img{display:block;max-width:500px;height:auto}pre{white-space:pre-wrap}</style>"
    report_html += f"<h1>Native Body Fidelity</h1><p>{html.escape(summary['note'])}</p><p>Capture geometry: {html.escape(str(summary['capture_geometry']))}</p><pre>{html.escape(json.dumps(grouped, indent=2))}</pre>"
    (out / "report.html").write_text(report_html + "".join(rows), encoding="utf-8")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", type=Path)
    parser.add_argument("--out", type=Path, required=True, help="New output directory; existing runs are never overwritten")
    parser.add_argument("--font", type=Path, default=Path("F:/sohil/NotoSansCJKjp-Regular.otf"))
    parser.add_argument("--only", help="Regex over case ID, dictionary name and term")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--prepare-only", action="store_true", help="Validate/build inputs without accessing a phone")
    parser.add_argument("--hit-check", action="store_true", help="Check recursive lookup geometry and compare ruby tap CPU cost")
    parser.add_argument("--reference", type=Path, help="Reuse an identical prior WebView capture for native-only iterations")
    parser.add_argument("--package", default="app.chimahon.dev")
    parser.add_argument("--backend", choices=("native-flow-v1", "native-cpp-v1", "native-cpp-draw-v1"), default="native-flow-v1")
    parser.add_argument("--serial", help="Explicit Android target, required unless --prepare-only")
    parser.add_argument("--adb", default=str(Path("F:/Sdk/platform-tools/adb.exe")) if Path("F:/Sdk/platform-tools/adb.exe").exists() else shutil.which("adb"))
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--threshold", type=float, help="Optional mean fuzzy gate; capture failures always fail")
    args = parser.parse_args()
    if args.limit is not None and args.limit <= 0:
        parser.error("--limit must be positive")
    if not args.prepare_only and not args.serial:
        parser.error("Supply --serial explicitly; the runner never selects a connected device automatically")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    bundle = prepare(args.cases.resolve(), args.font.resolve(), out, args.only, args.limit)
    reference = args.reference.resolve() if args.reference else None
    if reference is not None:
        cached = json.loads((reference / "capture.json").read_text(encoding="utf-8"))
        for key in ("input_sha256", "font_sha256"):
            if cached.get(key) != bundle[key]:
                raise ValueError(f"Reference {key} mismatch; rerender WebView instead")
        if not cached.get("done") or any(not case.get("ok") for case in cached["results"]):
            raise ValueError("Reference capture is incomplete or failed")
        if [case["id"] for case in cached["results"]] != [case["id"] for case in bundle["cases"]]:
            raise ValueError("Reference case order mismatch")
        with zipfile.ZipFile(out / "bundle.zip", "a", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("reference.json", json.dumps(cached, ensure_ascii=False))
    print(f"Prepared {len(bundle['cases'])} cases in {out}; font={bundle['font_sha256'][:16]}", flush=True)
    if args.prepare_only:
        return 0
    if not args.adb:
        parser.error("ADB not found; supply --adb")
    captured = capture(Adb(args.adb, args.package, args.serial), out, bundle, args.font.resolve(), args.timeout, args.hit_check, args.backend, reference)
    summary = score(out, bundle, captured, reference)
    print(json.dumps(summary["groups"], indent=2))
    print(f"Scored {summary['scored']}/{summary['total']}; report: {out / 'report.html'}")
    hit_failures = 0
    if args.hit_check:
        for result in captured["results"]:
            check = result.get("hit_check", {})
            benchmark = check.get("ruby_tap_benchmark", {})
            if benchmark.get("result_mismatches", 1) or check.get("moved_result_mismatches", 1):
                hit_failures += 1
                print(f"Hit check failed or missing: {result['id']}")
        print(f"Hit-check failures: {hit_failures}")
    mean = summary["groups"]["all"]["fuzzy"]
    return int(summary["failed"] > 0 or hit_failures > 0 or args.threshold is not None and (mean is None or mean < args.threshold))


if __name__ == "__main__":
    raise SystemExit(main())
