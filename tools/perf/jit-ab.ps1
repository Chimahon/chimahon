<#
.SYNOPSIS
    A/B test whether JIT is on the critical path of the dictionary popup, without a rebuild.

.DESCRIPTION
    The logs show ART compiler threads allocating 8-27MB around the popup, but that is correlation,
    not causation: ART compiles in the background whether or not any of it blocks a frame. This
    measures the only thing that can settle it - the same lookup under different compilation
    states of the installed app.

      none      -f clear + verification only. Interpreted; JIT is running or about to.
      profile   -m speed-profile. The state a baseline-profile build produces.
      full      -m full. Fully AOT; no JIT thread can appear at all during the run.

    It forces the state with `cmd package compile`, which is what Macrobenchmark's
    CompilationMode.None/Full does internally. So this needs no Gradle, no extra build type, and it
    works on the debug build the original jank logs came from.

    Run each arm, record the numbers, compare. Only the delta between arms is meaningful - never
    compare across devices, and never trust the absolute values (a debuggable app carries JIT and
    debug overhead a release build does not).

.PARAMETER Package
    Application id of the build under test.

.PARAMETER Mode
    none | profile | full

.PARAMETER Commands
    Extra shell commands to run after entering the state, e.g. launch straight into the reader.

.EXAMPLE
    ./jit-ab.ps1 -Mode none
    ./jit-ab.ps1 -Mode full
#>
[CmdletBinding()]
param(
    [ValidateSet("none", "profile", "full")]
    [string] $Mode = "none",
    [string] $Package = "app.chimahon.dev",
    [string[]] $Commands = @()
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Rest)
    & adb @Rest
    if ($LASTEXITCODE -ne 0) { throw "adb $($Rest -join ' ') failed with $LASTEXITCODE" }
}

$devices = (& adb devices) -join "`n"
if ($devices -notmatch "device") { throw "no device attached:`n$devices" }

$installed = (& adb shell pm list packages) -join "`n"
# Escape only the id: running the anchor through [regex]::Escape turns "\b" into a literal backslash
# and the pattern then never matches anything.
if ($installed -notmatch ([regex]::Escape("package:$Package") + '\b')) {
    throw "$Package is not installed. Pass -Package."
}

Write-Host "forcing compilation state: $Mode" -ForegroundColor Cyan

# Kill first: `compile` cannot swap a package out from under a running process, and a live JIT
# thread would keep compiling the old image.
Invoke-Adb shell am force-stop $Package | Out-Null

switch ($Mode) {
    "none" {
        # Clear the existing AOT artifacts and leave it verified-only, so the first launch has to
        # interpret. Note this does NOT disable JIT - ART will still JIT in the background - which
        # is exactly the point: this arm measures "interpretation, plus whatever JIT gets done".
        Invoke-Adb shell cmd package compile -m verify -f $Package | Out-Null
        Invoke-Adb shell cmd package clear-compiled-flags $Package | Out-Null
    }
    "profile" {
        Invoke-Adb shell cmd package compile -m speed-profile -f $Package | Out-Null
    }
    "full" {
        Invoke-Adb shell cmd package compile -m full -f $Package | Out-Null
    }
}

# Report what the package manager actually recorded, so a silently-failed compile is visible
# instead of turning into a bogus "no difference between arms".
Write-Host "package compile status:" -ForegroundColor DarkGray
& adb shell dumpsys package $Package | Select-String -Pattern "compilerStatus|dexopt|pathList" |
    ForEach-Object { "  $($_.Line.Trim())" }

if ($Commands) {
    Write-Host "running setup commands ..." -ForegroundColor DarkGray
    foreach ($c in $Commands) {
        Write-Host "  $c" -ForegroundColor DarkGray
        Invoke-Adb shell $c | Out-Null
    }
}

Write-Host ""
Write-Host "state is set. Now launch the app and open a word popup the same way each time." -ForegroundColor Yellow
Write-Host "Then run:" -ForegroundColor Cyan
Write-Host "  ./capture-compose-trace.ps1 -Package $Package" -ForegroundColor Cyan
Write-Host ""
Write-Host "Record, for each arm:" -ForegroundColor DarkGray
Write-Host "  - trace: longest 'Recompose'/'Compose' slices, and any 'JIT compiling' slice at all" -ForegroundColor DarkGray
Write-Host "  - framestats: frameOverrunMs p50/p90/p99, and frames over 50ms" -ForegroundColor DarkGray
Write-Host "  - DictPerf: popup ms and the 709/2604ms Davey figures, if still present" -ForegroundColor DarkGray
Write-Host ""
Write-Host "If 'full' shows no JIT slice and no better frame times than 'none', JIT is a bystander." -ForegroundColor DarkGray