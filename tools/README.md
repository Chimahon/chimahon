# Dictionary Renderer: Start Here

This is the single current handoff. Run commands from the repository root.
Historical output folders are evidence, not plans. Do not follow old generated docs.

## Current State

- Candidate: `native-cpp-draw-v1`, C++ layout and drawing commands, Android text
  measurement and Canvas. Compose hosts the popup and header, not glossary layout.
- WebView is an independent supported mode. Never change its dictionary content
  or reference styling to make native scores look better.
- Full fidelity: **89.898848846846**, 230/230 scored, zero capture/hit failures.
  Tables **88.15162**, ruby **86.77354**.
- Verified output: `tools/dict-oracle/out/native-css-borders-generated-all-20261103`.
  Before this batch: **89.19029**, tables **84.62003**, ruby **86.50408**.
- Original lowest cases now: 162=79.04, 168=84.45, 169=85.61, 218=72.25, 220=74.81.
- Core SHA256: `19b49aae888618a0c112d8a51581ff35df74a44b92935b963fec7cb7bf21b906`.
  Scored harness APK SHA256:
  `dfd9124e5c3d744671338cf060dc596092a542f1198d7d0f90c332dd4032839d`.
- No recent production-phone speed improvement is verified. An ARM64 candidate
  build was started before cleanup; log: `F:/sohil/builds/native-arm64-20261103-precleanup.log`.
  Do not mistake body fidelity or isolated PC timings for popup appearance time.

## Pipeline And Ownership

1. Hoshidicts supplies the original glossary JSON. Do not modify the lookup engine.
2. App `DictionaryRenderPreparation.kt` / `DictionaryEntryCompose.kt` select styles,
   theme, assigned width, group cards, and schedule worker batches.
3. `dictionary-core/src/styled_document.cpp`: JSON/DOM, Lexbor selector matching,
   cascade, computed styles, generated text, and width-independent lowering.
4. `document_layout.cpp`: block/table intrinsic sizing and allocated placement.
5. `drawing_plan.cpp`: bulk measurement requests, wrapping, ruby, commands and hits.
6. `NativeCoreCompiler.kt`: one bulk Android font/advance/bidi/break phase, native
   finish, then buffer transfer. No per-paragraph JNI callbacks.
7. `NativeDrawingCommands.kt` / `NativeGlossaryView.kt`: paint finished commands,
   viewport clipping, recursive lookup and images. No parsing/CSS/layout in draw.

Main text and ruby bases support lookup. Annotation text and tables are static.
Header furigana still uses shared Kotlin drawing; it is not a legacy glossary backend.
Keep WebView mode, popup controls/audio, SVG loading, font fallback, cancellation,
and API compatibility. No cross-term content/layout caches or dropped documents.

## Quick Fidelity Iteration

Use **emulator-5580** only. Never select an unspecified ADB target or touch a phone
unless asked. SDK: `F:/Sdk`; font: `F:/sohil/NotoSansCJKjp-Regular.otf`.
Corpus: `tools/dict-oracle/out/paragraph-runs-header-corpus-20261026/cases.json`.

1. Inspect the exact glossary JSON/CSS and paired native/WebView images.
2. Read the relevant CSS specification and Blink behavior. Fix the general rule,
   not dictionary names, term text, case dimensions, or screenshot-specific offsets.
3. For C++ changes, **reload the library, do not rebuild an APK**:

```powershell
./tools/dict-oracle/reload_native_core.ps1
```

4. Capture a small group sharing the defect, with a fresh output directory:

```powershell
python tools/dict-oracle/run_native_fidelity.py tools/dict-oracle/out/paragraph-runs-header-corpus-20261026/cases.json --out tools/dict-oracle/out/NEW_FOCUSED --serial emulator-5580 --package app.chimahon.fidelity --backend native-cpp-draw-v1 --only 'CASE_ID|OTHER_CASE_ID' --hit-check
```

5. Inspect images, not just the number. After a meaningful batch, run the full gate:

```powershell
python tools/dict-oracle/run_native_fidelity.py tools/dict-oracle/out/paragraph-runs-header-corpus-20261026/cases.json --out tools/dict-oracle/out/NEW_FULL --serial emulator-5580 --package app.chimahon.fidelity --backend native-cpp-draw-v1 --hit-check --reference tools/dict-oracle/out/native-css-borders-generated-all-20261103
```

Reference reuse requires identical corpus/order/font/geometry. Omit `--reference`
for a subset or changed input; the runner captures both engines. Never bypass a
backend/hash mismatch. `summary.json` holds scores; `capture.json` identifies the
actually loaded library. PNGs and `report.html` provide paired visual evidence.
Score reuse is allowed only for byte-identical native and reference images.
Do not add unrelated test suites to this edit/capture loop.

## When A Harness Build Is Necessary

Only rebuild for Kotlin/JNI/command-consumer changes. Properties are mandatory:

```powershell
./gradlew.bat :dictionary-fidelity:assembleDebug "-Pchimahon.debugAbi=x86_64" "-Pdictionary.nativeCore=true" --offline --console=plain
F:/Sdk/platform-tools/adb.exe -s emulator-5580 install -r -t dictionary-fidelity/build/outputs/apk/debug/dictionary-fidelity-debug.apk
./tools/dict-oracle/reload_native_core.ps1 -NoBuild
```

The flag prevents silently running the retired Kotlin backend in older APKs.
Current command records are64 bytes, fonts68 bytes, run metrics16 bytes. Kind3 is
a radial fill; rectangle flag bits8..11 select a rounded single border edge.
Do not pair a new command producer with an older consumer. Source-only library
reloads force-stop the harness, stage a read-only debug library, and record its hash.

## Performance And Phone Comparison

For an ARM64 app candidate (separate from the small fidelity APK):

```powershell
./gradlew.bat :app:assembleDebug "-Pchimahon.debugAbi=arm64-v8a" "-Pdictionary.nativeCore=true" --offline --console=plain
```

Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Never install without
being asked. Keep the pre-cleanup APK/baseline evidence for old-vs-new comparisons.
With the requested phone serial, `tools/perf/lookup-profile/capture.ps1` captures
trace, framestats and logcat. `report.py` and `analyze_popup_trace.py` analyze them.
`tools/perf/lookup-replay/replay.py --help` documents production-popup export/replay.
For a saved bundle, use `replay --serial emulator-5580 --storage emulator
--bundle PATH --out NEW_OUTPUT --regime warm-process --warmups 3 --iterations 5
--compilation verify`. Export with `capture --serial PHONE_SERIAL --queries
tools/perf/lookup-replay/queries.json --out NEW_EXPORT` only when phone access is
requested. Replay does not require imported dictionaries; export does.
Compare the same corpus, font, widths, compilation mode, warmups and device state.
Report preparation, ready-to-draw, and frame-commit separately; CPU sums across
workers are not elapsed time. Do not mix cold startup, dictionary I/O and popup work.

Portable C++ inspection uses `tools/native-dictionary/inspect_styles.py` after
building its Windows DLL. `run.ps1 -Drawing` measures the C++ path on PC; Windows
font shaping does not predict Android timing or establish Android fidelity.
`run_prepare_benchmark.py` measures isolated Android preparation, not popup latency.

## Known Remaining Work

- Narrow Shogakukan headings/vertical spacing: inspect paragraph struts, baselines,
  and supported `vertical-align` keywords. Do not globally squeeze ruby.
- A ruby leading-reuse formula improved the five targets but regressed full230
  to88.78893. It was reverted; it is not an accepted optimization.
- Current fixes include computed-vs-painted font sizes, root-relative units,
  table wrappers/fit-content, collapsed edges, generated string pseudo-content,
  radial symbol opacity, and asymmetric rounded border geometry.
- Lexbor rejects generated pseudo-elements. Selector-token normalization preserves
  them as internal AST markers; owner matching does not insert fake DOM children.
- For rounded borders, the inner radius is the outer radius minus the border width
  on that axis, clamped at zero. A left-only border uses asymmetric inner ellipses,
  not a clipped uniform stroke. See CSS Backgrounds3 sections4.2 and4.4.

Keep `.deps`, active corpora, their referenced CSS/media, accepted WebView references,
current captures, and comparison timing evidence. Do not bulk-delete `out/` trees:
corpora can reference CSS in older output folders. Retired desktop Compose/Skiko
tools and one-off scratch probes are not part of this workflow.
