<#
.SYNOPSIS
    Measures the watch app where it runs: cold start, scroll frames and what is actually installed.

.DESCRIPTION
    The measurement protocol of docs/reports/2026-09-30-watch-performance-plan.md, as a script, so
    that the next performance report starts from numbers rather than from a feeling. Two watch
    reports before it ended with "not verified on hardware"; this exists so that no third one can.

    Everything here is read-only against the device. It runs, in order:

      0. What is installed: version, install time, whether the package is DEBUGGABLE, and the
         dexopt status. The dexopt line decides everything else — a debuggable package is capped
         at `verify` by ART and can never be compiled ahead of time, and a freshly installed one
         sits at `verify` until background dexopt reaches it (or `-Compile` forces it).
      1. Device state that would throttle the app: battery saver, animator scale, battery level,
         CPU governor, Bluetooth. A number taken with battery saver on is not the app's number.
      2. Cold start, three times, through `am start -W`. TotalTime is the one to read.
      3. Scroll frames: reset gfxinfo, script twenty swipes, read the histogram. The MEDIAN frame
         time is the swipe script's pacing (`input swipe` injects motion events roughly every
         32 ms and a frame is produced per event), so it reads 32-34 ms on every build; the app is
         in the janky count and the tail percentiles. The raw framestats are saved beside the
         summary.

    Wireless adb to the watch dies within a minute or two (see .claude/project-profile.md), so each
    step is one short batch and the port is re-read from `adb devices` before each one. If the watch
    drops mid-run, reconnect and rerun with -Skip to resume at the step that failed.

    A round-trip measurement (tap to button change) is not here: it needs two temporary log lines
    in the source and both devices' logcats at epoch time. The report says how.

.PARAMETER Watch
    The watch's adb serial (`IP:port` on wireless debugging). Optional when it is the only device
    that reports `ro.build.characteristics` containing "watch".

.PARAMETER Out
    Where to write the summary and the framestats. Default: a dated folder under docs/reports/perf/,
    which is git-ignored by nothing — commit the summary if it belongs in a report.

.PARAMETER Compile
    Also force the installed package to `speed-profile` before measuring (what background dexopt
    would reach on its own within a day, using the baseline profile the shrunk APK carries). Not
    for a debuggable package, where it is a no-op; the script says so rather than pretending.

.PARAMETER Skip
    Steps to skip, by number (0-3), to resume after the link dropped.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\watch-perf.ps1 -Watch 192.168.1.23:41234

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\watch-perf.ps1 -Compile -Skip 0,1
#>
[CmdletBinding()]
param(
    [string] $Watch,
    [string] $Out,
    [switch] $Compile,
    [int[]] $Skip = @()
)

$ErrorActionPreference = 'Stop'

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$pkg = 'md.borisveriga.megapodcastplayer'
$activity = "$pkg/md.borisveriga.megapodcastplayer.wear.MainActivity"

if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Out) {
    $Out = Join-Path $repoRoot ("docs\reports\perf\" + (Get-Date -Format 'yyyy-MM-dd-HHmm') + '-watch')
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$summary = Join-Path $Out 'summary.txt'

<#
.SYNOPSIS
    Writes a line to the console and to the summary file.
#>
function Note([string] $Text) {
    Write-Host $Text
    Add-Content -Path $summary -Value $Text -Encoding utf8
}

<#
.SYNOPSIS
    Runs one adb command against the watch and returns its output lines.

.DESCRIPTION
    The serial is resolved on every call, because on wireless debugging it changes with every
    reconnect and the connection rarely survives a whole run.
#>
function Wadb {
    $serial = Resolve-Watch
    & $adb -s $serial @args
}

<#
.SYNOPSIS
    Finds the watch's current adb serial.

.DESCRIPTION
    Uses -Watch when given and still listed; otherwise the one connected device whose build
    characteristics say "watch". Fails loudly when the watch is not there: a measurement against
    the phone by accident would be a worse outcome than no measurement.
#>
function Resolve-Watch {
    $devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } |
        ForEach-Object { ($_ -split '\t')[0] }
    if ($Watch -and ($devices -contains $Watch)) { return $Watch }
    $watches = @($devices | Where-Object {
        (& $adb -s $_ shell getprop ro.build.characteristics) -match 'watch'
    })
    if ($watches.Count -eq 1) { return $watches[0] }
    if ($watches.Count -eq 0) {
        throw "No watch is connected. 'adb devices' lists: $($devices -join ', '). See .claude/project-profile.md for reconnecting."
    }
    throw "More than one watch is connected ($($watches -join ', ')); pass -Watch."
}

Note "# Watch performance, $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
Note "Package $pkg on $(Resolve-Watch); git $(git -C $repoRoot rev-parse --short HEAD)"
Note ''

# ---- 0. What is installed ----------------------------------------------------------------------
if ($Skip -notcontains 0) {
    Note '## 0. Installed package'
    $dump = Wadb shell dumpsys package $pkg
    $dump | Select-String 'versionName=|lastUpdateTime=|flags=\[|status=|\[arm|primaryCpuAbi' |
        ForEach-Object { Note "  $($_.Line.Trim())" }
    $debuggable = ($dump | Select-String 'flags=\[.*DEBUGGABLE') -ne $null
    Note "  debuggable: $debuggable"
    Wadb shell pm path $pkg | ForEach-Object { Note "  $_" }
    $apk = (Wadb shell pm path $pkg | Select-Object -First 1) -replace '^package:', ''
    if ($apk) {
        $size = Wadb shell stat -c %s $apk
        Note ("  apk size: {0:N1} MB" -f ([double]$size / 1MB))
    }
    Wadb shell dumpsys meminfo $pkg | Select-String 'TOTAL PSS|\.dex|\.oat|\.art|Dalvik Heap' |
        ForEach-Object { Note "  $($_.Line.Trim())" }

    if ($Compile) {
        if ($debuggable) {
            Note '  -Compile skipped: ART caps a debuggable package at verify; nothing would change.'
        } else {
            # The baseline profile ships inside the APK and is copied into ART's profile store by
            # the app's own profile installer, which runs on a launch. Compiling before that has
            # happened finds no profile and leaves the package at `verify` (seen 2026-10-01), so
            # the app is started once and the installer told to run now rather than when it likes.
            Note '  installing the baseline profile, then compiling speed-profile...'
            Wadb shell am start -n $activity | Out-Null
            Start-Sleep -Seconds 4
            Wadb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE `
                -p "$pkg/androidx.profileinstaller.ProfileInstallReceiver" | Out-Null
            Start-Sleep -Seconds 3
            Wadb shell cmd package compile -m speed-profile -f $pkg | ForEach-Object { Note "  $_" }
            Wadb shell dumpsys package $pkg | Select-String 'status=' | ForEach-Object { Note "  $($_.Line.Trim())" }
        }
    }
    Note ''
}

# ---- 1. Device state ---------------------------------------------------------------------------
if ($Skip -notcontains 1) {
    Note '## 1. Device state'
    Note "  low_power: $(Wadb shell settings get global low_power)"
    Note "  animator_duration_scale: $(Wadb shell settings get global animator_duration_scale)"
    Wadb shell dumpsys battery | Select-String 'level|status|plugged' | ForEach-Object { Note "  $($_.Line.Trim())" }
    Note "  cpu0 governor: $(Wadb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
    Note "  cpu0 cur/max kHz: $(Wadb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq) / $(Wadb shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq)"
    Wadb shell dumpsys bluetooth_manager | Select-String '^\s*state:|Connected' | Select-Object -First 3 |
        ForEach-Object { Note "  $($_.Line.Trim())" }
    Note ''
}

# ---- 2. Cold start -----------------------------------------------------------------------------
if ($Skip -notcontains 2) {
    Note '## 2. Cold start (am start -W), three runs'
    1..3 | ForEach-Object {
        # No pause between the stop and the start, deliberately: Play Services re-binds the
        # watch's chip service within a second or two of the process dying, and a start that
        # waits finds the process already back and reports a WARM launch (seen 2026-10-01).
        Wadb shell am force-stop $pkg
        $start = Wadb shell am start -W -n $activity
        $line = ($start | Select-String 'TotalTime|WaitTime|LaunchState') -join ' '
        Note "  run ${_}: $line"
    }
    Note ''
}

# ---- 3. Scroll frames --------------------------------------------------------------------------
if ($Skip -notcontains 3) {
    Note '## 3. Scroll frames: 10 swipes down and back'
    # Foreground and settled before the histogram is reset, so the frames counted are the scroll's.
    Wadb shell am start -n $activity | Out-Null
    Start-Sleep -Seconds 3
    Wadb shell dumpsys gfxinfo $pkg reset | Out-Null
    1..10 | ForEach-Object {
        Wadb shell input swipe 225 360 225 120 300
        Wadb shell input swipe 225 120 225 360 300
    }
    Start-Sleep -Seconds 1
    $gfx = Wadb shell dumpsys gfxinfo $pkg
    $gfx | Select-String 'Total frames|Janky frames|percentile|Number ' | ForEach-Object { Note "  $($_.Line.Trim())" }
    Wadb shell dumpsys gfxinfo $pkg framestats | Set-Content -Path (Join-Path $Out 'framestats.txt') -Encoding utf8
    Note "  framestats: $(Join-Path $Out 'framestats.txt')"
    Note ''
    Note 'The median frame is the swipe script, not the app; read the janky count and the 95th/99th percentile.'
}

Write-Host ''
Write-Host "Summary written to $summary"
