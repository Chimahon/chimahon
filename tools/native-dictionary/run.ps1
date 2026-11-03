param(
    [string]$Corpus = "tools/perf/out/paired-export-20261023/bundle.json",
    [int]$Iterations = 31,
    [switch]$Styled,
    [switch]$Layout,
    [switch]$Drawing,
    [string]$Font = 'F:/sohil/NotoSansCJKjp-Regular.otf'
)
$ErrorActionPreference = "Stop"
if ($Drawing) { $Layout = $true }
if ($Layout) { $Styled = $true }
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$build = "$PSScriptRoot/out"
$compiler = (Get-Command g++.exe -ErrorAction Stop).Source.Replace('\', '/')
$cmake = "F:/Sdk/cmake/3.22.1/bin/cmake.exe"
$rapid = "$PSScriptRoot/.deps/rapidjson/include"
if (-not (Test-Path "$rapid/rapidjson/reader.h")) { throw "Download RapidJSON into tools/native-dictionary/.deps/rapidjson first" }
& $cmake -S "$repo/dictionary-core" -B "$build/release" -G "MinGW Makefiles" `
    "-DCMAKE_CXX_COMPILER=$compiler" -DCMAKE_BUILD_TYPE=Release `
    "-DRAPIDJSON_INCLUDE_DIR=$rapid" `
    "-DLEXBOR_SOURCE_DIR=$PSScriptRoot/.deps/lexbor" `
    "-DHOSHIDICTS_INCLUDE_DIR=$repo/chimahon/src/main/cpp/hoshidicts/include" `
    "-DGLAZE_INCLUDE_DIR=$repo/chimahon/src/main/cpp/hoshidicts/external/glaze/include"
if ($LASTEXITCODE -ne 0) { throw "CMake configure failed" }
$target = if ($Styled) { 'dictionary_styled_bench' } else { 'dictionary_core_bench' }
& $cmake --build "$build/release" --target $target --parallel 2
if ($LASTEXITCODE -ne 0) { throw "Native build failed" }
Push-Location $repo
try {
    if ($Drawing) {
        $result = & "$build/release/dictionary_styled_bench.exe" $Corpus "$repo/chimahon/src/main/assets/dictionary/base.css" $Iterations $Font commands
    } elseif ($Layout) {
        $result = & "$build/release/dictionary_styled_bench.exe" $Corpus "$repo/chimahon/src/main/assets/dictionary/base.css" $Iterations $Font
    } elseif ($Styled) {
        $result = & "$build/release/dictionary_styled_bench.exe" $Corpus "$repo/chimahon/src/main/assets/dictionary/base.css" $Iterations
    } else {
        $result = & "$build/release/dictionary_core_bench.exe" $Corpus $Iterations
    }
    if ($LASTEXITCODE -ne 0) { throw "Benchmark/structural comparison failed" }
    $report = $result | ConvertFrom-Json
    $report | Add-Member -NotePropertyName input_sha256 -NotePropertyValue (Get-FileHash -LiteralPath $Corpus -Algorithm SHA256).Hash
    $report | Add-Member -NotePropertyName build_type -NotePropertyValue 'Release'
    $report | Add-Member -NotePropertyName rapidjson_revision -NotePropertyValue ((& git -C "$PSScriptRoot/.deps/rapidjson" rev-parse HEAD) -join '')
    $report | Add-Member -NotePropertyName lexbor_revision -NotePropertyValue ((& git -C "$PSScriptRoot/.deps/lexbor" rev-parse HEAD) -join '')
    if ($Layout) { $report | Add-Member -NotePropertyName font_sha256 -NotePropertyValue (Get-FileHash -LiteralPath $Font -Algorithm SHA256).Hash }
    $reportName = if ($Drawing) { 'drawing-results.json' } elseif ($Layout) { 'layout-results.json' } elseif ($Styled) { 'styled-results.json' } else { 'results.json' }
    $report | ConvertTo-Json -Depth 10 | Set-Content "$build/$reportName" -Encoding utf8
    if ($Styled) {
        $report.cases | Format-Table id,parse_ms,style_lower_ms,text_layout_ms,total_ms,unresolved_values,unsupported_features
    } else { $report.cases | ForEach-Object {
        [PSCustomObject]@{ Case=$_.id; Sources=$_.sources; SAX_ms=$_.timings.rapidjson_sax_arena.median_ms;
            DOM_ms=$_.timings.rapidjson_dom_arena.median_ms; Glaze_ms=$_.timings.glaze_dom_arena.median_ms }
    } | Format-Table }
    Write-Host "Report: $build/$reportName"
} finally { Pop-Location }
