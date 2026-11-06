param([string]$Serial = 'emulator-5580', [switch]$NoBuild, [string]$PopupApk = '')
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
if ($PopupApk) {
    $apk = (Resolve-Path -LiteralPath $PopupApk).Path
    $sdk = 'F:/Sdk/build-tools/36.1.0'
    $package = & "$sdk/aapt.exe" dump badging $apk | Select-Object -First 1
    if ($package -notmatch "package: name='app.chimahon.dev'") { throw 'Expected the x86_64 development popup APK' }
    $output = "$repo/tools/native-dictionary/out/popup-refresh"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $stripped = "$output/libdictionary_core.so"
    & 'F:/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe' --strip-unneeded -o $stripped $library
    if ($LASTEXITCODE -ne 0) { throw 'Native strip failed' }
    @'
import sys
from zipfile import ZipFile
original, library, output = sys.argv[1:]
target = 'lib/x86_64/libdictionary_core.so'
with ZipFile(original) as source, ZipFile(output, 'w') as destination:
    if target not in source.namelist():
        raise ValueError('Expected an x86_64 native-core APK')
    with open(library, 'rb') as stream:
        replacement = stream.read()
    for item in source.infolist():
        name = item.filename.upper()
        if name.startswith('META-INF/') and (name.endswith(('.SF','.RSA','.DSA','.EC')) or name == 'META-INF/MANIFEST.MF'):
            continue
        destination.writestr(item, replacement if item.filename == target else source.read(item.filename))
'@ | python -X utf8 - $apk $stripped "$output/unsigned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK library replacement failed' }
    & "$sdk/zipalign.exe" -P 16 -f 4 "$output/unsigned.apk" "$output/aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
    & "$sdk/apksigner.bat" sign --ks "$env:USERPROFILE/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/popup.apk" "$output/aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & "$sdk/apksigner.bat" verify "$output/popup.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    Invoke-Adb @('install', '--no-incremental', '-r', '-t', "$output/popup.apk")
    Write-Output "Popup native library SHA256: $((Get-FileHash -LiteralPath $stripped).Hash)"
    Write-Output 'C++ only refreshed; DEX is unchanged. Match compilation state before timing.'
    return
}
Invoke-Adb @('shell', 'am', 'force-stop', 'app.chimahon.fidelity')
Invoke-Adb @('push', $library, '/data/local/tmp/fidelity-core.so')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'mkdir', '-p', 'files/native-core')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'rm', '-f', 'files/native-core/libdictionary_core.so')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'cp', '/data/local/tmp/fidelity-core.so', 'files/native-core/libdictionary_core.so')
Invoke-Adb @('shell', 'run-as', 'app.chimahon.fidelity', 'chmod', '400', 'files/native-core/libdictionary_core.so')
Invoke-Adb @('shell', 'rm', '/data/local/tmp/fidelity-core.so')
Write-Output "Staged native library SHA256: $((Get-FileHash -LiteralPath $library).Hash)"
Write-Output 'Run run_native_fidelity.py next; the capture records the loaded library hash.'
