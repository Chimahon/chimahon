<#
.SYNOPSIS
    Captures a Perfetto trace of the Compose dictionary popup, with composable function names.

.DESCRIPTION
    Runs the config in compose-trace.pbtxt on the device, which is the only capture shape that
    contains all four of: named composable recompose/measure/layout slices, the View system
    (Choreographer, draw), JIT + GC (dalvik), and FrameTimeline red/yellow classification.

    By default it records until the popup goes quiet, then stops the trace gracefully so the
    file is flushed and pulls it. That removes the tap-timing race: open the reader first, start
    the script, tap whenever you like - it stops itself QuietSecs after your last tap. Ctrl+B or
    Enter stop it sooner; Ctrl+C aborts. Ctrl+B is used instead of relying on Ctrl+C because
    Ctrl+C kills the adb client mid-flush, which is what produced the 0-byte trace.

    It also pulls framestats afterwards so the trace can be cross-checked against the same run.

.PARAMETER Package
    Application id of the build under test. Debug installs use the .dev suffix.

.PARAMETER Seconds
    Fixed recording length. Omit or pass 0 to record until stopped.

.PARAMETER QuietSecs
    Auto-stop: end the run this many seconds after the last popup log line goes quiet, once at
    least one tap has landed. This is the primary stop mechanism - it needs no keys at all.
    The slow frames happen at open/composition time, so a quiet popup has nothing left to capture.

.PARAMETER MaxMinutes
    Failsafe cap: end the run after this many minutes even if taps keep landing (or never do).

.PARAMETER OutDir
    Where to write the pulled trace and framestats. Defaults to tools/perf/out.

.EXAMPLE
    ./capture-compose-trace.ps1
    ./capture-compose-trace.ps1 -Seconds 20
    ./capture-compose-trace.ps1 -QuietSecs 30
#>
[CmdletBinding()]
param(
    [string] $Package = "app.chimahon.dev",
    [int]    $Seconds = 0,
    [int]    $QuietSecs = 12,
    [int]    $MaxMinutes = 12,
    [string] $OutDir  = "",
    [string] $ConfigPath = "",
    [string] $CaptureStamp = "",
    [ValidateSet('native', 'webview', 'any')][string] $ExpectedRenderer = 'any'
)

$ErrorActionPreference = "Stop"

# $PSScriptRoot is not reliably bound while param defaults are evaluated on Windows PowerShell 5.1,
# so resolve the script's own directory here instead of in the default value.
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $OutDir) { $OutDir = Join-Path $scriptDir "out" }

$config = Join-Path $scriptDir "compose-trace.pbtxt"
if ($ConfigPath) { $config = $ConfigPath }
$stamp  = if ($CaptureStamp) { $CaptureStamp } else { [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss") }
$remote = "/data/misc/perfetto-traces/compose-trace-$stamp.pftrace"
$remoteConfig = "/data/misc/perfetto-configs/compose-trace.pbtxt"
$logOut = Join-Path $OutDir "perfetto-$stamp.out.log"
$logErr = Join-Path $OutDir "perfetto-$stamp.err.log"
$logcat = Join-Path $OutDir "logcat-$stamp.txt"

# Note: every `adb shell` call below passes its whole command as ONE quoted string. Going through
# a function with ValueFromRemainingArguments means PowerShell tries to bind any leading -x as a
# parameter name to that function and silently drops it, so `adb shell mkdir -p DIR` loses its -p.
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Rest)
    # adb writes informational progress to stderr ("1 file pushed, 0 skipped"). With
    # $ErrorActionPreference = 'Stop' that turns a SUCCESSFUL call into a terminating error,
    # which aborts the capture mid-run. Only the exit code decides failure; stderr is
    # swallowed here and the real diagnostics come from the exit code and the logs.
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & adb @Rest 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) { throw "adb $($Rest -join ' ') failed with $LASTEXITCODE" }
}

# Reads the pulled trace back and counts the slice names that matter. A trace with Choreographer
# slices but zero composable slices is the failure mode to catch here: it means atrace worked and
# Compose tracing was not actually on screen, which looks identical to "the popup is fast".
function Get-TraceMarkers {
    param([string] $Path)
    $text = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($Path))
    $markers = [ordered]@{
        # Verified against a real capture: trace names are the composable function names, and paths
        # use dots, not slashes - "eu.kanade...dictionary.compose..." never appears in a trace.
        "composable"    = @("Recompose", "DictionaryEntry", "StructuredGlossary")
        "choreographer" = @("Choreographer", "performTraversals", "DrawFrame")
        "jit"           = @("JIT compiling", "Background concurrent copying GC")
        "viewtree"      = @("SyncAndDrawFrame", "measure", "draw")
    }
    $found = [ordered]@{}
    foreach ($group in $markers.Keys) {
        $sum = ($markers[$group] |
            ForEach-Object { ([regex]::Matches($text, [regex]::Escape($_))).Count } |
            Measure-Object -Sum).Sum
        $found[$group] = if ($null -eq $sum) { 0 } else { $sum }
    }
    return $found
}

function Stop-Perfetto {
    # perfetto flushes and writes its output file on SIGINT. Killing adb instead would leave a
    # truncated or missing trace, which is the one failure that wastes the whole capture.
    $perfettoPid = (& adb shell pidof perfetto) -join ""
    if ($perfettoPid) {
        & adb shell kill -INT $perfettoPid | Out-Null
    } else {
        & adb shell pkill -INT perfetto | Out-Null
    }
}

# --- preflight ---------------------------------------------------------------------------------

$devices = (& adb devices) -join "`n"
if ($devices -notmatch "device") { throw "no device attached:`n$devices" }

$installed = (& adb shell pm list packages) -join "`n"
# Escape only the id: running the anchor through [regex]::Escape turns "\b" into a literal backslash
# and the pattern then never matches anything.
if ($installed -notmatch ([regex]::Escape("package:$Package") + '\b')) {
    throw "$Package is not installed on the device. Install it first, or pass -Package."
}

if (-not ((& adb shell which perfetto) -join "")) {
    throw "perfetto is not on the device (needs Android 9+ / a recent build)."
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "capture-compose-trace v5 - auto-stops ${QuietSecs}s after last tap goes quiet (Ctrl+B/Enter to stop now)" -ForegroundColor DarkGray

# TracingReceiver is a runtime receiver: if the process is dead the broadcast is dropped silently
# and the capture comes back with a populated track_event buffer and zero composable slices in it,
# which looks exactly like a broken build. So require a live app rather than starting one - you
# decide what is on screen, this script only watches.
$appPid = (& adb shell pidof $Package) -join ""
if (-not $appPid) {
    throw @"
$Package is not running.

Open the app and get to the reader on a page you can tap a word on, then run this again. The tracing
SDK is enabled by a broadcast that only reaches a live process.
"@
}

# --- stage the config --------------------------------------------------------------------------

# perfetto rejects -c together with -t/--time, so the duration has to come from the config itself.
# With no fixed duration, duration_ms is removed entirely and perfetto records until it is signalled.
$staged = Join-Path $OutDir "compose-trace.staged.pbtxt"
$rewritten = Get-Content $config -Raw
$rewritten = $rewritten.Replace('atrace_apps: "*"', ('atrace_apps: "' + $Package + '"'))
if ($Seconds -gt 0) {
    $rewritten = $rewritten -replace 'duration_ms:\s*\d+', "duration_ms: $($Seconds * 1000)"
} else {
    $rewritten = $rewritten -replace '(?m)^\s*duration_ms:\s*\d+\s*$', ''
}
# No BOM: Windows PowerShell 5.1's `Set-Content -Encoding UTF8` prepends one, and perfetto's
# protobuf-text parser rejects the file outright with "Unexpected character".
[System.IO.File]::WriteAllText($staged, $rewritten, (New-Object System.Text.UTF8Encoding $false))

# perfetto runs as the shell user and cannot reliably read a file pushed to /data/local/tmp; its own
# error message names /data/misc/perfetto-configs as the directory it can always open.
Invoke-Adb shell "mkdir -p /data/misc/perfetto-configs" | Out-Null
Invoke-Adb push $staged $remoteConfig | Out-Null

# --- enable the in-app tracing SDK --------------------------------------------------------------
# runtime-tracing emits composable names through the Perfetto SDK, and the SDK only starts its data
# source when it is told to. Without this the track_event data source stays empty and the capture
# comes back with zero composables in it.

Write-Host "enabling tracing SDK for $Package ..." -ForegroundColor DarkGray
& adb shell am broadcast -a androidx.tracing.perfetto.action.ENABLE_TRACING `
    "$Package/androidx.tracing.perfetto.TracingReceiver" 2>&1 | Out-Null

# --- record -------------------------------------------------------------------------------------
Invoke-Adb shell "dumpsys gfxinfo $Package reset" | Out-Null

# Start-Process rather than Start-Job: a job's child process is killed with the parent on Ctrl+C,
# which is exactly the case where perfetto must survive long enough to flush its output file.
$proc = Start-Process -FilePath "adb" `
    -ArgumentList @("shell", "perfetto", "--txt", "-c", $remoteConfig, "-o", $remote) `
    -NoNewWindow -PassThru -RedirectStandardOutput $logOut -RedirectStandardError $logErr

# Popup traffic, so the run is self-describing: if composable is 0, the logcat file answers whether
# the tap even produced a lookup, which is a different bug than a tracing one.
Invoke-Adb logcat -c | Out-Null
$logcatProc = Start-Process -FilePath "adb" `
    -ArgumentList @("logcat", "-v", "threadtime",
        "DictPerf:V", "DictPhase:V", "DictionaryRepo:V", "DictionaryPopup:V", "DictBench:V", "DictProfile:V", "DictProfileNative:V", "*:S") `
    -NoNewWindow -PassThru -RedirectStandardOutput $logcat

Start-Sleep -Seconds 2
if ($proc.HasExited) {
    throw "perfetto exited immediately. See $logErr`n$(Get-Content $logErr -Raw)"
}

if ($Seconds -gt 0) {
    Write-Host ""
    Write-Host ">>> TAP A WORD IN THE READER NOW - recording for ${Seconds}s. Ctrl+B stops early." -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host ">>> RECORDING. Tap whenever - stops automatically ${QuietSecs}s after your last tap goes quiet." -ForegroundColor Yellow
    Write-Host "    (Ctrl+B / Enter also stop now; Ctrl+C aborts. It may stop while you are still" -ForegroundColor DarkGray
    Write-Host "    reading - that is fine, the tap is already captured. Start another run for more taps.)" -ForegroundColor DarkGray
}

$started = Get-Date
$stopRequested = $false
$stopReason = ""
$logcatShown = 0
$lastActivity = $null
$keyErrorLogged = $false
try {
    while (-not $proc.HasExited -and -not $stopRequested) {
        Start-Sleep -Milliseconds 500
        $now = Get-Date

        # Live logcat: show what the app logged since the last poll, so the run is watchable
        # instead of a silent counter. Lookup lines highlighted; the rest dimmed.
        $tapsSeen = 0
        if (Test-Path $logcat) {
            $lines = @(Get-Content $logcat)
            if ($lines.Count -gt $logcatShown) {
                # Any growth counts as activity. The logcat filter only lets our five popup tags
                # through, so growth here always means the popup did something.
                $lastActivity = $now
            }
            for ($i = $logcatShown; $i -lt $lines.Count; $i++) {
                $line = $lines[$i]
                if ($line -match "DictPerf|contentFrame|settled|lookup_ms|anki_check_ms|loadMediaAsync") {
                    Write-Host "  $line" -ForegroundColor Yellow
                } elseif ($line -notmatch "^\s*$" -and $line -notmatch "beginning of") {
                    Write-Host "  $line" -ForegroundColor DarkGray
                }
            }
            $logcatShown = $lines.Count
            $tapsSeen = @($lines | Where-Object { $_ -match 'contentFrame|"phase"\s*:\s*"request.start"' }).Count
        }

        # Stop keys: best effort only. If the host delivers no key events, auto-stop below still
        # ends the run - keys are a convenience, not the mechanism. A key-API failure is printed
        # once (not swallowed) so a dead keyboard path is visible instead of mysterious.
        try {
            while ($Host.UI.RawUI.KeyAvailable) {
                $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
                $ctrlB = ($key.Key -eq [System.ConsoleKey]::B) -and
                    (($key.Modifiers -band [System.ConsoleModifiers]::Control) -ne 0)
                if ($ctrlB -or $key.Key -eq [System.ConsoleKey]::Enter) {
                    $stopRequested = $true
                    $stopReason = "key"
                }
            }
        } catch {
            if (-not $keyErrorLogged) {
                $keyErrorLogged = $true
                Write-Warning "console key reading failed ($($_.Exception.Message)) - Ctrl+B/Enter unavailable, relying on auto-stop"
            }
        }

        # Auto-stop: the primary mechanism. After at least one tap, QuietSecs of logcat silence
        # ends the run - the slow frames happen at open/composition, a quiet popup has nothing
        # left to capture. MaxMinutes caps the run if taps never land or never stop.
        $elapsed = [int](($now - $started).TotalSeconds)
        $quietFor = if ($lastActivity) { [int](($now - $lastActivity).TotalSeconds) } else { -1 }
        if ($tapsSeen -gt 0 -and $quietFor -ge $QuietSecs) {
            $stopRequested = $true
            $stopReason = "auto"
        }
        if ($elapsed -ge ($MaxMinutes * 60)) {
            $stopRequested = $true
            if (-not $stopReason) { $stopReason = "cap" }
        }

        if ($Seconds -gt 0) {
            $left = [Math]::Max(0, $Seconds - $elapsed)
            Write-Host -NoNewline ("`r    {0,4}s elapsed, taps seen: {1}, ~{2}s left  (Ctrl+B to stop)      " -f $elapsed, $tapsSeen, $left)
        } else {
            $quietShow = [Math]::Max(0, $quietFor)
            Write-Host -NoNewline ("`r    {0,4}s elapsed - taps seen: {1} - quiet {2}s/{3}s      " -f $elapsed, $tapsSeen, $quietShow, $QuietSecs)
        }
    }
    if ($stopReason -eq "auto") {
        Write-Host ""
        Write-Host "last tap +${QuietSecs}s quiet - stopping." -ForegroundColor Cyan
    } elseif ($stopReason -eq "cap") {
        Write-Host ""
        Write-Host "max time reached - stopping." -ForegroundColor Cyan
    }
}
finally {
    Write-Host ""
    # Ctrl+C lands here, and so does perfetto dying on its own for a fixed-length run. Either way
    # make sure the on-device trace is flushed before trying to pull it.
    if (-not $proc.HasExited) {
        Write-Host "stopping the trace ..." -ForegroundColor DarkGray
        Stop-Perfetto
        if (-not $proc.WaitForExit(20000)) {
            Write-Host "trace did not stop cleanly; killing the adb process." -ForegroundColor Yellow
            $proc | Stop-Process -Force
        }
    }
    # The logcat child is ours to kill; it holds no flush semantics, just stop it.
    if (-not $logcatProc.HasExited) { $logcatProc | Stop-Process -Force }
}
Write-Host ""

# --- pull ---------------------------------------------------------------------------------------

$trace = Join-Path $OutDir "compose-$stamp.pftrace"
Write-Host "pulling trace ..." -ForegroundColor DarkGray
Invoke-Adb pull $remote $trace | Out-Null
$pulledBytes = (Get-Item $trace).Length
if ($pulledBytes -eq 0) {
    throw "Pulled trace is 0 bytes - perfetto did not flush. The on-device file was left at $remote for inspection. Re-run and stop with Ctrl+B (not Ctrl+C)."
}
Invoke-Adb shell "rm -f $remote" | Out-Null
Invoke-Adb shell "rm -f $remoteConfig" | Out-Null

# Cross-check: framestats for the same run. If the trace and framestats disagree, the trace is
# telling you something framestats cannot, not that one of them is lying.
$stats = Join-Path $OutDir "framestats-$stamp.txt"
& adb shell dumpsys gfxinfo $Package framestats > $stats 2>&1

Write-Host ""
Write-Host "trace:      $trace" -ForegroundColor Green
Write-Host "framestats: $stats" -ForegroundColor Green
Write-Host "logcat:     $logcat" -ForegroundColor Green

# Self-check. Verifying here beats opening the UI and wondering whether an empty Recompose row is
# a fast popup or a capture that never saw Compose.
Write-Host ""
Write-Host "--- raw trace string matches (not parsed slice counts) ---" -ForegroundColor Cyan
$marks = Get-TraceMarkers -Path $trace
foreach ($k in $marks.Keys) { Write-Host ("  {0,-14} {1}" -f $k, $marks[$k]) }

if ($marks["composable"] -eq 0) {
    Write-Host ""
    Write-Host "WARNING: no composable slices in this capture." -ForegroundColor Yellow
    Write-Host "  Check $logcat first: if it has no DictPerf/lookup lines, the tap never produced" -ForegroundColor DarkGray
    Write-Host "  a lookup - retap with the popup held open. If it DOES have lookup lines but the" -ForegroundColor DarkGray
    Write-Host "  trace still has zero composables, send both files: that combination means Compose" -ForegroundColor DarkGray
    Write-Host "  rendered with no trace markers, which is a different failure." -ForegroundColor DarkGray
} else {
    Write-Host ""
    Write-Host "Framework trace strings present; checking lookup diagnostics separately." -ForegroundColor Gray
}

$events = @(foreach ($line in Get-Content -LiteralPath $logcat -Encoding UTF8) {
    if ($line -match '\bDictProfile(?:Native)?\s*:\s*(\{.*\})\s*$') {
        try { $event = $Matches[1] | ConvertFrom-Json; if ($event.id -gt 0) { $event } } catch { }
    }
})
$native = @($events | Where-Object { $_.phase -eq 'popup.renderer' -and $_.detail -match 'renderer=native\b' }).Count
$web = @($events | Where-Object { $_.phase -eq 'popup.renderer' -and $_.detail -match 'renderer=webview\b' }).Count
$functions = @($events | Where-Object { $_.function }).Count
$draws = @($events | Where-Object { $_.phase -eq 'document.first_draw' }).Count
$ready = @($events | Where-Object { $_.phase -eq 'plan.ready' }).Count
$identity = @($events | Where-Object { $_.phase -eq 'capture.identity' -and $_.detail -match 'schema=native-flow-v2\b' }).Count
$detail = @($events | Where-Object { $_.function -eq 'paragraph.append' }).Count
$sourceParses = @($events | Where-Object { $_.phase -eq 'plan.source' }).Count
$documentsWithCpu = @(foreach ($event in $events) {
    if ($event.phase -eq 'plan.document') {
        try { $document = $event.detail | ConvertFrom-Json; if ($null -ne $document.cpu_ns) { $document } } catch { }
    }
}).Count
$valid = $functions -gt 0 -and $draws -gt 0 -and $ready -gt 0
if ($ExpectedRenderer -eq 'native') { $valid = $valid -and $native -gt 0 -and $web -eq 0 -and $identity -gt 0 -and $detail -gt 0 -and $documentsWithCpu -gt 0 -and $sourceParses -gt 0 }
if ($ExpectedRenderer -eq 'webview') { $valid = $web -gt 0 -and $native -eq 0 }
[ordered]@{ expectedRenderer=$ExpectedRenderer; nativeRequests=$native; webviewRequests=$web;
    functionRecords=$functions; glossaryDraws=$draws; readyPlans=$ready; identityRecords=$identity;
    detailedAppendRecords=$detail; documentsWithCpu=$documentsWithCpu; sourceParses=$sourceParses; validDiagnostics=$valid } |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $OutDir "capture-$stamp.validation.json")
if (-not $valid) { Write-Warning 'Incomplete or mixed-mode diagnostics. Do not interpret this as a native speed result.' }
if ($events.Count -gt 0 -and (Get-Command python -ErrorAction SilentlyContinue)) {
    $report = Join-Path $OutDir "lookup-report-$stamp"
    & python -X utf8 (Join-Path $scriptDir 'lookup-profile\report.py') --logcat $logcat --trace $trace --out $report
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Report generation failed; raw capture files were preserved.' }
} else {
    Write-Warning 'No profiler events or Python unavailable: detailed report was not generated.'
}

Write-Host ""
Write-Host "open https://ui.perfetto.dev and drag the .pftrace onto it." -ForegroundColor Cyan
Write-Host "Start there:" -ForegroundColor Cyan
Write-Host "  1. Select the Main thread, expand the Compose slices, sort by duration." -ForegroundColor DarkGray
Write-Host "     The longest named composable is your target." -ForegroundColor DarkGray
Write-Host "  2. FrameTimeline track: red = app missed deadline, yellow = SurfaceFlinger did." -ForegroundColor DarkGray
Write-Host "  3. Look for 'JIT compiling' / GC slices overlapping the red frames." -ForegroundColor DarkGray
