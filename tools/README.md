# Dictionary Renderer: Start Here

This is the single current handoff. Run commands from the repository root.
Historical output folders are evidence, not plans. Do not follow old generated docs.

## Current State

- Candidate: `native-cpp-draw-v1`, C++ layout and drawing commands, Android text
  measurement and Canvas. The new term-card path also prepares headwords, tags,
  dictionary headings, inflection and pitch content through this pipeline. Compose
  hosts one drawing view per term card, image overlays, audio/Anki controls and navigation.
- WebView is an independent supported mode. Never change its dictionary content
  or reference styling to make native scores look better.
- Current-source body fidelity: **89.898848846846**, 230/230 scored, zero capture failures.
  Tables **88.15162**, ruby **86.77354**.
- Verified output: `tools/dict-oracle/out/native-typed-preparation-all-20261104`.
  All 230 native PNGs are byte-identical to `native-current-state-all-20261103`.
  Hit checks are restored: 19,730 probes, six Shogakukan cases flag seven points.
  `native-hit-control-20261104` reproduces identical failures using unbatched
  measurement and the previous property/numeric evaluation. These are not new
  batching/numeric regressions; do not describe all interaction checks as passing.
  Before this batch: **89.19029**, tables **84.62003**, ruby **86.50408**.
- Original lowest cases now: 162=79.04, 168=84.45, 169=85.61, 218=72.25, 220=74.81.
- Full-run core SHA256: `4bf3d64e8aa3e297d04f501c3e7028b40751369e92e1961d0abf0f57fdd112b8`.
  Current signature-qualified JNI build: `cb9d54c7d0af303224cc50e0bcaf3363b8e9d6e6d75d54876b99290e6fd29b41`;
  verified in `native-typed-jni-verified-20261104` (one body plus typed-header check).
  Scored harness APK SHA256:
  `6b74f772130fa3f5509d23ea7bd63b7eee5aa3719a06bb9a048d9cca7845dff2`.
- Latest ARM64 candidate built and installed on the phone on 2026-11-04. Build log:
  `F:/sohil/builds/typed-preparation-arm64-20261104.log` (successful, 29m9s).
  APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`, 145,807,509 bytes.
  Latest phone evidence is summarized below: matching popup callbacks improved,
  but a controlled causal gain from the preparation changes is not established.
  Do not mistake body fidelity or isolated PC timings for popup appearance time.

### Latest Phone Capture And Next Work (2026-11-04)

Evidence under `tools/perf/out/`, stamp **20261103-235744 (UTC)**:
`compose-*.pftrace`, `framestats-*.txt`, `logcat-*.txt`,
`lookup-report-*.json` / `.md`, and `capture-*.validation.json`.
Additional parsed framework analysis: `phone-typed-preparation-20261104-analysis.json`.
Phone serial `4pcajzd6ae85ifpv`; 31 drawn requests, 18 coalesced requests.
All 31 completed requests identify `native-cpp-draw-v1`; none identifies WebView.

| Completed-request measurement | Median ms | P90 ms | Worst ms |
| --- | ---: | ---: | ---: |
| Request start -> first glossary draw | 489.66 | 1047.36 | 1408.05 |
| Request start -> matched content presentation | 512.13 | 1074.93 | 1429.33 |
| Popup -> ready-container callback | 350 | 550 | 727 |
| Popup -> settled callback | 394 | 623 | 783 |
| Repository started -> ready | 118.88 | 369.17 | 609.12 |
| Repository ready -> first preparation queued | 56.45 | 85.65 | 474.45 |
| First preparation queued -> first plan ready | 275.94 | 474.43 | 640.83 |
| First plan ready -> first glossary draw | 70.88 | 148.92 | 213.74 |
| First glossary draw -> matched presentation | 21.37 | 38.11 | 58.19 |

Independent stage medians are not additive. Popup callbacks start later than the
full request and are not presentation. Presentation is the report's matched app
FrameTimeline frame, not a separately instrumented physical-display measurement.
Removing the first two completed requests still leaves a 505.98ms presentation
median: this is not solely cold startup.

Compared with `20261103-172202`, 11 matching query/result-count keys have nine
faster callbacks, two slower, median paired delta -75ms. Examples: `どうかとか`
1381->727ms, `メイクした方がいいとか` 764->690ms,
`大人って分かんない` 545->365ms. These are callback comparisons, not full-request
latency comparisons or a controlled A/B attribution. Overall callback medians
323->350ms use different workloads and must not be called a regression.

Measured costs and limitations:
- First popup: host publish -> preparation queue 463.78ms; overlapping UI frame
  407.25ms, 402.59ms running CPU. Class verification/theme/popup composition are
  visible; nested slice durations must not be added together.
- `Recomposer:animation`: 7,831 calls, 11,173.55ms aggregate main-thread self wall
  time across the capture. This remains active frame-clock overhead, not an 11s
  delay per lookup. Identify its owner; do not assume the old spinner is responsible.
- Glossary parse median self CPU 2.15ms/request; source diagnostics 8.99ms;
  native card placement 0.32ms; document paint 20.60ms. Function totals can include
  later redraws/repreparation and are not exclusive first-content latency.
- Framestats: 1,048 frames, 162 janky (15.46%), frame P50/P90 24/85ms;
  GPU P50/P90 4/9ms. Whole-session frame statistics are not paired lookup results.
- Nine concurrent GC cycles total 1839.43ms inclusive; not stop-the-world pause
  time. DictTimerLog used 856.97ms CPU over the capture. Observer overhead is real
  but its effect on individual lookup latency is not quantified.
- Diagnostics contain 974 function records and 2,266 source parses, but no detailed
  C++ append/document-CPU records. Validation therefore fails despite native mode.
  Do not attribute the 276ms preparation interval specifically to CSS or shaping.
- Trace reports 141 `track_descriptor_conflicting_reservation` errors. Timing
  interpretation must retain this caveat; it does not automatically invalidate all
  scheduler/FrameTimeline data. Phone disconnected during final property cleanup,
  after all three capture files had already been pulled.
- 22 completed requests have multiple preparation stages. Several additional queues
  occur after first draw. Summed `plan.prepare` (342.33ms median) is not first-plan
  latency (275.94ms median); do not label every later plan a first-render retry.

Next work, in priority order (not yet implemented):
1. Trace the persistent frame-clock producer through reader/popup animation and
   snapshot subscriptions. Remove continuous ticking for static content without
   breaking gestures or actual transitions. Use existing PC replay, not phone loops.
2. Reduce shell work before queueing preparation and after plan readiness: reuse
   invariant theme/appearance/control configuration, keep request-varying state
   narrowly scoped, and avoid rebuilding the whole shell for a headword update.
   Treat first-use class verification separately from recurring shell cost.
3. Surface existing `NativeDrawingTiming` input/measure/finish/transfer/configuration
   fields as one aggregate per batch. Carry request/batch identity across workers;
   distinguish cancellation, later expansion, and initial content. No per-run log flood.
   Then optimize the dominant measured phase, not a guessed CSS/shaping bottleneck.
4. Reduce temporary arrays/buffer copies and repeated context/font/break setup only
   where that phase breakdown supports it. Preserve font fallback, bidi, Japanese
   breaks, ruby geometry, hit offsets and independent WebView behavior.

No new unit suite or full APK rebuild per C++ edit. Use native-library reload plus
existing replay/fidelity workflow below; batch Kotlin/host changes into one build.
Do not change Hoshidicts, drop entries, or add cross-term layout caches. No claim
that sub-200ms Android full-request latency is guaranteed by this plan.

### Preparation Restructure (2026-11-04)

- `NativeCardSource.kt` packs host-created header trees directly into a native
  token tape. `dc_prepare_mixed` accepts typed chrome alongside untouched glossary
  JSON in the same batch; no JSONObject/JSON encode/decode round-trip for chrome.
- `resolved_style.hpp` indexes computed properties and compiles numeric expressions
  once per style/value. Percentage bases remain dynamic; evaluation preserves the
  existing operation order. Records live with the prepared document, not a term cache.
- C++ emits shaping groups based on font/spacing/context, not paint color or box
  decoration. Android measures groups while retaining original paint requests,
  UTF-16 offsets, ruby roles and recursive hit metadata. No audio/Anki handler changes.
- Typed-header fixture passes identical pixels, 17 hit probes and image placement
  against its JSON equivalent. This is not full popup-chrome vs WebView fidelity.
- Both `:app:compileDebugKotlin` and `:dictionary-fidelity:assembleDebug` passed.
  The typed-input JNI signature/counts/section15 require rebuilding the host APK
  once before returning to native-only reloads. Old hosts fail JNI linkage safely.
- Matched Windows before/after (15 iterations, 3 warmups, no Gradle): totals
  95.455->96.516, 25.398->25.632, 310.302->313.393, 206.346->205.996 ms.
  All drawing digests match; no meaningful Windows speed gain. Largest-case
  shaping groups 4,187->2,537 (39.4% fewer). DirectWrite already batches contexts;
  no Android latency improvement is claimed until measured.
  Evidence: `F:/sohil/builds/preparation-before-20261104/results.json` and
  `F:/sohil/builds/preparation-current-20261104.json`. Temporary control source edits
  were restored; the emulator again has the optimized candidate, not the control.

### Card Restructure (2026-11-03)

- Header follow-up (included in the 2026-11-04 ARM APK): process summaries
  now show names only, expanded steps retain descriptions, rules/tags are distinct,
  and the disclosure arrow follows the open state. Initial preparation includes
  only the collapsed summary; expanded summary/details prepare on demand.
- Reported Jitendex 絶望 extracted from the actual 2026-01-04 dictionary. Paired
  body captures: `tools/dict-oracle/out/jitendex-zetsubou-pair-retry-20261103`
  (420px/light, 88.99746) and `jitendex-zetsubou-dark-20261103`
  (280px/dark, 88.57384). Both show the intended colored keyword and ruby without
  a major overlap; remaining visible differences are list/vertical spacing and
  intrinsic box width. These body scores do not verify the native header changes.

- Text preparation: `styled_document.cpp` resolves JSON/CSS and lowers runs;
  `drawing_plan.cpp` creates batched measurement requests; `NativeCoreCompiler.kt`
  obtains Android font metrics, bidi, line boundaries and advances; C++ finishes
  wrapping/positioning before Android draws the commands.
- Follow-up: C++ punctuation spacing settings resolve once per style; Android em
  extents resolve lazily once per font per batch rather than once per run. No
  cross-term cache or API requirement added. Incremental phone gain is unmeasured.
  A binary-search property lookup was tried and removed: PC timing did not show
  an improvement. Four-case drawing and geometry digests matched during that trial.
- Exact punctuation-settings A/B evidence: `F:/sohil/builds/text-spacing-{0..3}-*.json`,
  baseline/candidate/candidate/baseline, 15 iterations each. All four output digests
  match. No speed gain established: worst-case input+finish averaged 13.038 ms
  baseline vs 16.706 ms candidate, but the unchanged platform-measure phase also
  slowed while the ARM Gradle build ran. Do not attribute that drift to the patch
  or cite this as an optimization win. Font-extents reuse is Android-only and was
  not exercised by the DirectWrite PC benchmark.
- Earlier ARM64 chrome build log: `F:/sohil/builds/native-cards-arm64-20261103.log`.
  Superseded by the successful typed-preparation ARM build documented above.

- `NativeTermCard.kt` builds small chrome sources and retains original glossary
  sources. They share the existing bulk preparation batch; dictionary CSS scopes
  are not flattened or rewritten. `dc_card_place` places the finished fragments
  into one card document. Collapse/selection reuse measured fragments; expanding
  previously unopened content prepares only the missing fragments.
- Old term-card Compose rows, glossary rows, collapse state and plain-text renderer
  are removed. The separate kanji-result screen remains Compose. WebView is unchanged.
- Header kanji retain kanji lookup; body text retains term lookup. Ruby annotation
  and tables remain static. Native group hit regions handle collapse/long-press selection.
- Fixed the diagnostic frame observer's duplicate callback chains, missing quiet-frame
  increment and failure to stop after settling. Removed indeterminate popup spinners.
- The 230-case score above belongs to the last verified glossary-body build, not
  a fidelity claim about these new card headers. The x86 APK built successfully and
  all four replay cases passed (one warmup, two measured iterations). No new unit suite.
- Fixed an exposed C++ bug: Lexbor returns WRONG_ARGS for an empty style tree.
  Unmatched elements have an empty cascade; they must inherit/default normally,
  not fail preparation. Desktop reproduction and emulator replay passed after this fix.
- Desktop placement only (including ctypes overhead): 8/32/128 fragments take
  0.00118/0.00124/0.00140 ms per call. Not text preparation or popup latency.

| Replay | Old first draw | New first draw | New preparation | New ready-to-draw |
|---|---:|---:|---:|---:|
| lookup-0 | 581.9 ms | 204.6 ms | 134.1 ms | 45.6 ms |
| lookup-1 | 326.2 ms | 145.1 ms | 56.0 ms | 56.1 ms |
| lookup-2 | 1322.4 ms | 352.5 ms | 281.6 ms | 39.5 ms |
| lookup-3 | 1012.3 ms | 311.5 ms | 243.7 ms | 40.5 ms |

Before: `tools/perf/out/native-card-before-idle-20261103/results.json`, backend
`native-flow-v2`. After: `tools/perf/out/native-card-after-fixed-20261103/results.json`,
backend `native-cpp-draw-v1`. These compare the full old/new pipeline, not the
isolated chrome change. Both are emulator-5580, warm-process, compilation state
verify, same saved bundle. Largest case: 20 cards / 173 glossaries. Frame commit
totals after: 218.6 / 159.6 / 367.4 / 326.1 ms; not display presentation.
Native placement JNI calls total less than 1 ms per lookup. Card assembly is
separate from text preparation. Timed C++ library SHA256:
`a7c2924add48f5699d01c2f2608ba17f9c4bec698f640463ae6b7bd51da44b7e`.
Corrected x86 APK: `F:/sohil/builds/Chimahon-native-cards-fixed-20261103-x86_64.apk`.

The first attempts ANR'd in ART startup with the build competing for RAM. The
valid runs were taken after stopping Gradle and restarting the emulator. Do not
include setup failures in renderer timing statistics. Stop builds before benchmarking.

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
The fidelity harness still uses shared Kotlin header geometry helpers; production
term-card furigana now uses the C++ document path.
Keep WebView mode, popup controls/audio, SVG loading, font fallback, cancellation,
and API compatibility. No cross-term content/layout caches or dropped documents.

## Quick Fidelity Iteration

Use **emulator-5580** only. Never select an unspecified ADB target or touch a phone
unless asked. SDK: `F:/Sdk`; font: `F:/sohil/NotoSansCJKjp-Regular.otf`.
Corpus: `tools/dict-oracle/out/paragraph-runs-header-corpus-20261026/cases.json`.

For C++-only production-popup changes, refresh the library in an existing x86 debug
APK without Gradle (replaces the native library, aligns/signs/installs, preserves DEX):
`./tools/dict-oracle/reload_native_core.ps1 -PopupApk F:/sohil/builds/Chimahon-native-cards-fixed-20261103-x86_64.apk`.
Default mode still stages the library for the fidelity harness. Kotlin/JNI API
changes require rebuilding the APK. Match compilation state before timing.

1. Inspect the exact glossary JSON/CSS and paired native/WebView images.
2. Read the relevant CSS specification and Blink behavior. Fix the general rule,
   not dictionary names, term text, case dimensions, or screenshot-specific offsets.
3. For C++ changes, **reload the library, do not rebuild an APK**:

```powershell
./tools/dict-oracle/reload_native_core.ps1
```

4. Capture a small group sharing the defect, with a fresh output directory:

```powershell
python tools/dict-oracle/run_native_fidelity.py tools/dict-oracle/out/paragraph-runs-header-corpus-20261026/cases.json --out tools/dict-oracle/out/NEW_FOCUSED --serial emulator-5580 --package app.chimahon.fidelity --backend native-cpp-draw-v1 --only 'CASE_ID|OTHER_CASE_ID'
```

5. Inspect images, not just the number. After a meaningful batch, run the full gate:

```powershell
python tools/dict-oracle/run_native_fidelity.py tools/dict-oracle/out/paragraph-runs-header-corpus-20261026/cases.json --out tools/dict-oracle/out/NEW_FULL --serial emulator-5580 --package app.chimahon.fidelity --backend native-cpp-draw-v1 --reference tools/dict-oracle/out/native-current-state-all-20261103
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

Historical Windows Release run (2026-11-03): `tools/native-dictionary/out/drawing-results.json`,
31 measured iterations, 3 warmups, saved four-lookup corpus, 256px/16px Noto CJK.
Historical report preserved at `F:/sohil/builds/windows-drawing-before-latest-20261103.json`.

| Lookup | Prior Total | Current Total | Current C++ Plan* | DirectWrite |
| --- | ---: | ---: | ---: | ---: |
| 0 | 95.815 | 108.231 | 32.600 | 74.910 |
| 1 | 24.588 | 26.940 | 8.920 | 17.924 |
| 2 | 301.772 | 361.111 | 95.871 | 264.784 |
| 3 | 200.712 | 221.934 | 63.827 | 157.317 |

Milliseconds. *Sum of phase medians: JSON + CSS/lowering + drawing inputs + finish;
not an independently measured combined median. CSS setup is measured separately
and excluded from Total. Both input/font hashes and all drawing/geometry digests
match the prior report. Historical machine state is uncontrolled: no causal
speedup/regression claim. This tool benchmarks glossary bodies (deduplicated exact
dictionary/body pairs), not new production-card headers or popup presentation.

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
