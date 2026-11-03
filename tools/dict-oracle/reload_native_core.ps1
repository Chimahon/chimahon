param([string]$Serial = 'emulator-5580', [switch]$NoBuild)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This development loop is emulator-only' }
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$build = "$repo/chimahon/.cxx/Debug/3u3n4z3j/x86_64"
$adb = 'F:/Sdk/platform-tools/adb.exe'
function Invoke-Adb([string[]]$Arguments) {
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $Arguments" }
}
if (-not $NoBuild) {
    & 'F:/Sdk/cmake/3.22.1/bin/cmake.exe' --build $build --target dictionary_core --parallel 4
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
}
$library = "$repo/chimahon/build/intermediates/cxx/Debug/3u3n4z3j/obj/x86_64/libdictionary_core.so"
if (-not (Test-Path -LiteralPath $library)) { throw "Missing $library" }
Invoke-Adb @('shell', 'am', 'force-stop', 'app.chimahon.fidelity')
Invoke-Adb @('push', $library, '/data/local/tmp/fidelity-core.so')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'mkdir', '-p', 'files/native-core')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'rm', '-f', 'files/native-core/libdictionary_core.so')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'cp', '/data/local/tmp/fidelity-core.so', 'files/native-core/libdictionary_core.so')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'chmod', '400', 'files/native-core/libdictionary_core.so')
Invoke-Adb @('shell', 'rm', '/data/local/tmp/fidelity-core.so')
Write-Output "Staged native library SHA256: $((Get-FileHash -LiteralPath $library).Hash)"
Write-Output 'Run run_native_fidelity.py next; the capture records the loaded library hash.'
