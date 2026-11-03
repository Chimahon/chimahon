param(
    [string]$Package = 'app.chimahon.dev',
    [int]$Seconds = 0,
    [int]$QuietSecs = 12,
    [string]$OutDir = (Join-Path $PSScriptRoot '..\out'),
    [string]$Serial = '',
    [switch]$Baseline,
    [ValidateSet('native', 'webview', 'any')][string]$ExpectedRenderer = 'native'
)
$ErrorActionPreference = 'Stop'
# adb selects the target from ANDROID_SERIAL, so the whole capture - including the separate
# capture-compose-trace.ps1, which calls adb directly in ~20 places - targets one explicit
# device without threading a serial through every call. Never capture from an unspecified
# target: a phone and the fidelity emulator can both be attached at once.
$previousSerial = $env:ANDROID_SERIAL
if ($Serial) { $env:ANDROID_SERIAL = $Serial }
# Prefer the SDK that owns the emulator. Two adb installs on PATH (C:\Program Files\platform-tools
# and F:\Sdk\platform-tools) can disagree on version, and the older server gets killed and
# restarted mid-capture, which loses the trace.
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $null }
$previousPath = $env:PATH
if ($sdkRoot) {
    $sdkTools = Join-Path $sdkRoot 'platform-tools'
    if (Test-Path $sdkTools) { $env:PATH = "$sdkTools;$env:PATH" }
}
function Invoke-ProfileAdb([string[]]$Arguments, [switch]$AllowFailure) {
    $adbCommand = Get-Command adb.exe -ErrorAction Stop
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = & $adbCommand.Source @Arguments 2>&1
        $script:ProfileAdbExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($script:ProfileAdbExitCode -and -not $AllowFailure) { throw "adb failed: $($result -join ' ')" }
    return $result
}
if (-not ((Invoke-ProfileAdb @('devices')) -match '\sdevice$')) { throw 'No authorized device attached.' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$metadata = [ordered]@{ package = $Package; baseline = [bool]$Baseline; serial = $Serial }
$metadata.captureStartedUtc = [DateTime]::UtcNow.ToString('o')
# The renderer is native-flow-v1 now. The old v2 marker described the Compose renderer and its
# probe families, which no longer exist.
$metadata.expectedRendererDiagnostics = 'native-flow-v2'
$metadata.expectedRenderer = $ExpectedRenderer
$metadata.installedPackage = ((Invoke-ProfileAdb @('shell', "dumpsys package $Package") -AllowFailure) |
    Select-String 'versionCode=|versionName=|lastUpdateTime=|firstInstallTime=|codePath=|flags=') -join "`n"
$metadata.deviceUptime = (Invoke-ProfileAdb @('shell', 'cat /proc/uptime') -AllowFailure) -join ''
$metadata.cpuOnline = (Invoke-ProfileAdb @('shell', 'cat /sys/devices/system/cpu/online') -AllowFailure) -join ''
$metadata.thermalBeforeCapture = (Invoke-ProfileAdb @('shell', 'dumpsys thermalservice') -AllowFailure) -join "`n"
$metadata.fingerprint = (Invoke-ProfileAdb @('shell', 'getprop ro.build.fingerprint')) -join ''
$metadata.pid = (Invoke-ProfileAdb @('shell', "pidof $Package")) -join ''
$metadata.memory = (Invoke-ProfileAdb @('shell', 'cat /proc/meminfo')) -join "`n"
$metadata.vmstat = (Invoke-ProfileAdb @('shell', 'cat /proc/vmstat')) -join "`n"
# User builds may prohibit tracefs reads. Retain that fact instead of assuming event support.
$available = Invoke-ProfileAdb @('shell', 'cat /sys/kernel/tracing/available_events') -AllowFailure
$tracefsExitCode = $script:ProfileAdbExitCode
if ($tracefsExitCode) {
    $available = Invoke-ProfileAdb @('shell', 'cat /sys/kernel/debug/tracing/available_events') -AllowFailure
    $tracefsExitCode = $script:ProfileAdbExitCode
}
$canInspect = $tracefsExitCode -eq 0
$metadata.tracefsReadable = $canInspect
$metadata.tracefsResponse = $available -join "`n"
$wanted = @('sched/sched_waking', 'sched/sched_wakeup_new', 'sched/sched_process_free',
    'task/task_newtask', 'task/task_rename', 'block/block_rq_issue', 'block/block_rq_complete',
    'filemap/mm_filemap_add_to_page_cache', 'exceptions/page_fault_user')
$selected = @()
$missing = @()
foreach ($event in $wanted) {
    if ($canInspect -and (($available -join "`n") -match ('(?m)^' + [regex]::Escape($event.Replace('/', ':')) + '$'))) {
        $selected += $event
    } else { $missing += $event }
}
$metadata.enabledExtraEvents = $selected
$metadata.unavailableOrUnverifiedEvents = $missing
$config = Get-Content -Encoding utf8 (Join-Path $PSScriptRoot '..\compose-trace.pbtxt') -Raw
$extra = ($selected | ForEach-Object { '      ftrace_events: "' + $_ + '"' }) -join "`n"
$config = $config.Replace('      drain_period_ms: 250', "      drain_period_ms: 250`n$extra")
$config += @'

data_sources { config {
  name: "linux.sys_stats"
  target_buffer: 1
  sys_stats_config { meminfo_period_ms: 250 vmstat_period_ms: 250 }
} }
'@
$configPath = Join-Path $OutDir "lookup-$stamp.pbtxt"
[IO.File]::WriteAllText($configPath, $config, (New-Object Text.UTF8Encoding $false))
$tags = @('DictProfile', 'DictProfileNative')
$old = @{}
foreach ($tag in $tags) { $old[$tag] = (Invoke-ProfileAdb @('shell', "getprop log.tag.$tag")) -join '' }
try {
    foreach ($tag in $tags) {
        $level = if ($Baseline) { 'SUPPRESS' } else { 'DEBUG' }
        Invoke-ProfileAdb @('shell', "setprop log.tag.$tag $level") | Out-Null
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $OutDir "lookup-$stamp.metadata.json")
    Write-Host "Extra kernel events: $($selected -join ', ')"
    Write-Warning "Unavailable/unverified events: $($missing -join ', '). Native fault counts remain available."
    & (Join-Path $PSScriptRoot '..\capture-compose-trace.ps1') -Package $Package -Seconds $Seconds -QuietSecs $QuietSecs -OutDir $OutDir -ConfigPath $configPath -CaptureStamp $stamp -ExpectedRenderer $ExpectedRenderer
} finally {
    foreach ($tag in $tags) {
        Invoke-ProfileAdb @('shell', "setprop log.tag.$tag '$($old[$tag])'") | Out-Null
        if ($LASTEXITCODE) { Write-Warning "Could not restore log.tag.$tag; reconnect and reset it manually." }
    }
    $env:ANDROID_SERIAL = $previousSerial
    $env:PATH = $previousPath
}
