<#
.SYNOPSIS
    Runs Mindlayer's single-device, Play-oriented memory benchmark.

.DESCRIPTION
    Builds and installs the debug app/test APKs without clearing app data, starts
    PlayMemoryBenchmarkInstrumentedTest, and samples both the main process and
    the `:ml` service process through cold start, default/max-context inference,
    multimodal and engine-coexistence work, then a TOP -> FGS -> background ->
    cached lifecycle transition.

    Primary metric:
      /proc/<pid>/status RssAnon + VmSwap

    Companion metrics:
      RssFile, RssShmem, VmRSS, smaps_rollup PSS, dumpsys native heap,
      Graphics/GL/EGL/Other mtrack, Android 17 native Bitmap allocation totals,
      and dmabuf_dump per-process RSS/PSS when the device exposes it.

    The script never uninstalls the app or clears package data, so sideloaded
    development models remain in externalFilesDir. It does force-stop the debug
    package before the run unless -SkipForceStop is supplied.

.EXAMPLE
    .\scripts\benchmark-play-memory.ps1 -Device 192.168.1.50:5555

.EXAMPLE
    .\scripts\benchmark-play-memory.ps1 -Device SERIAL -MaxContextTokens 16384 `
      -BitmapWidth 4096 -BitmapHeight 4096

.EXAMPLE
    .\scripts\benchmark-play-memory.ps1 -SelfTest
#>
[CmdletBinding()]
param(
    [string]$Device,
    [string]$AdbPath,
    [ValidateRange(128, 32768)]
    [int]$DefaultContextTokens = 8192,
    [Alias('MaxTokens')]
    [ValidateRange(128, 32768)]
    [int]$MaxContextTokens = 32768,
    [ValidateSet('CPU', 'GPU', 'NPU')]
    [string]$RequestedBackend = 'GPU',
    [ValidateRange(256, 50000)]
    [int]$PromptChars = 12000,
    [ValidateRange(1, 8192)]
    [int]$BitmapWidth = 2048,
    [ValidateRange(1, 8192)]
    [int]$BitmapHeight = 2048,
    [ValidateRange(1500, 30000)]
    [int]$PhaseHoldMs = 4000,
    [ValidateRange(100, 5000)]
    [int]$SampleIntervalMs = 250,
    [ValidateRange(1000, 30000)]
    [int]$DetailedSampleIntervalMs = 2000,
    [ValidateRange(33, 99)]
    [int]$MinimumApiLevel = 37,
    [ValidateRange(3200, 65536)]
    [int]$MinimumDeviceRamMiB = 6800,
    [ValidateRange(5000, 120000)]
    [int]$CachedWaitMs = 30000,
    [ValidateRange(1000, 30000)]
    [int]$CachedHoldMs = 4000,
    [string]$OutputDirectory,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipForceStop,
    [switch]$AllowEmulator,
    [switch]$AllowBackendFallback,
    [switch]$RequireDmaBuf,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PackageName = 'com.adsamcik.mindlayer.debug'
$TestPackageName = 'com.adsamcik.mindlayer.debug.test'
$Runner = "$TestPackageName/androidx.test.runner.AndroidJUnitRunner"
$TestClass = 'com.adsamcik.mindlayer.service.engine.PlayMemoryBenchmarkInstrumentedTest#representative_service_workload'
$MarkerPath = 'files/play-memory-benchmark/current-phase.tsv'
$MarkerJournalPath = 'files/play-memory-benchmark/phase-events.tsv'

function Convert-KeyValueKb {
    param([AllowEmptyString()][string]$Text)
    $values = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^\s*(?<key>[A-Za-z_()]+):\s+(?<value>\d+)\s+kB\s*$') {
            $values[$Matches.key] = [long]$Matches.value
        }
    }
    return $values
}

function Get-ActiveBackendObservations {
    param([object[]]$Events)
    $observations = [System.Collections.Generic.List[object]]::new()
    foreach ($event in $Events) {
        if ($event.note -match '(?:^|\s)active_backend=(?<backend>[A-Za-z0-9_-]+)(?:\s|$)') {
            $observations.Add([pscustomobject][ordered]@{
                phase = $event.phase
                backend = $Matches.backend.ToUpperInvariant()
            })
        }
    }
    return $observations
}

function Get-MapValue {
    param([hashtable]$Map, [string]$Key)
    if ($Map.ContainsKey($Key)) { return [long]$Map[$Key] }
    return $null
}

function Get-MeminfoRow {
    param([AllowEmptyString()][string]$Text, [string]$Label)
    $escaped = [regex]::Escape($Label)
    $match = [regex]::Match(
        $Text,
        "(?im)^\s*$escaped\s+(?<values>-?\d+(?:\s+-?\d+){3,})\s*$"
    )
    if (-not $match.Success) { return $null }
    $numbers = @($match.Groups['values'].Value -split '\s+' | ForEach-Object { [long]$_ })
    return [pscustomobject]@{
        PssKb = if ($numbers.Count -ge 1) { $numbers[0] } else { $null }
        RssKb = if ($numbers.Count -ge 5) { $numbers[4] } else { $null }
        HeapSizeKb = if ($numbers.Count -ge 6) { $numbers[5] } else { $null }
        HeapAllocKb = if ($numbers.Count -ge 7) { $numbers[6] } else { $null }
        HeapFreeKb = if ($numbers.Count -ge 8) { $numbers[7] } else { $null }
    }
}

function Get-MeminfoSummaryRow {
    param([AllowEmptyString()][string]$Text, [string]$Label)
    $escaped = [regex]::Escape($Label)
    $match = [regex]::Match(
        $Text,
        "(?im)^\s*$escaped`:\s+(?<pss>\d+)(?:\s+(?<rss>\d+))?\s*$"
    )
    if (-not $match.Success) { return $null }
    return [pscustomobject]@{
        PssKb = [long]$match.Groups['pss'].Value
        RssKb = if ($match.Groups['rss'].Success) { [long]$match.Groups['rss'].Value } else { $null }
    }
}

function Convert-DumpsysMeminfo {
    param([AllowEmptyString()][string]$Text)
    $native = Get-MeminfoRow -Text $Text -Label 'Native Heap'
    $nativeSummary = Get-MeminfoSummaryRow -Text $Text -Label 'Native Heap'
    $graphics = Get-MeminfoSummaryRow -Text $Text -Label 'Graphics'
    $gfxDev = Get-MeminfoRow -Text $Text -Label 'Gfx dev'
    $gl = Get-MeminfoRow -Text $Text -Label 'GL mtrack'
    $egl = Get-MeminfoRow -Text $Text -Label 'EGL mtrack'
    $other = Get-MeminfoRow -Text $Text -Label 'Other mtrack'

    $bitmapKb = 0L
    $bitmapCount = 0L
    $bitmapMatches = [regex]::Matches(
        $Text,
        '(?im)^\s*Bitmap\s+\((?:malloced|nonmalloced)\):\s*(?<count>\d+)\s+(?<kb>\d+)\s*$'
    )
    foreach ($match in $bitmapMatches) {
        $bitmapCount += [long]$match.Groups['count'].Value
        $bitmapKb += [long]$match.Groups['kb'].Value
    }
    $bitmapAvailable = $Text -match '(?im)^\s*Native Allocations\s*$'

    $hardwareBufferKb = 0L
    $hardwareBufferMatches = [regex]::Matches(
        $Text,
        '(?im)^\s*HardwareBuffer\s+\((?:malloced|nonmalloced)\):\s*\d+\s+(?<kb>\d+)\s*$'
    )
    foreach ($match in $hardwareBufferMatches) {
        $hardwareBufferKb += [long]$match.Groups['kb'].Value
    }

    return [pscustomobject]@{
        NativeHeapPssKb = if ($null -ne $native) { $native.PssKb } elseif ($null -ne $nativeSummary) { $nativeSummary.PssKb } else { $null }
        NativeHeapRssKb = if ($null -ne $native) { $native.RssKb } elseif ($null -ne $nativeSummary) { $nativeSummary.RssKb } else { $null }
        NativeHeapAllocKb = if ($null -ne $native) { $native.HeapAllocKb } else { $null }
        GraphicsPssKb = if ($null -ne $graphics) { $graphics.PssKb } else { $null }
        GraphicsRssKb = if ($null -ne $graphics) { $graphics.RssKb } else { $null }
        GfxDevPssKb = if ($null -ne $gfxDev) { $gfxDev.PssKb } else { $null }
        GlMtrackPssKb = if ($null -ne $gl) { $gl.PssKb } else { $null }
        EglMtrackPssKb = if ($null -ne $egl) { $egl.PssKb } else { $null }
        OtherMtrackPssKb = if ($null -ne $other) { $other.PssKb } else { $null }
        BitmapCounterAvailable = $bitmapAvailable
        BitmapCount = if ($bitmapAvailable) { $bitmapCount } else { $null }
        BitmapKb = if ($bitmapAvailable) { $bitmapKb } else { $null }
        HardwareBufferKb = if ($bitmapAvailable) { $hardwareBufferKb } else { $null }
    }
}

function Convert-DmaBufDump {
    param([AllowEmptyString()][string]$Text)
    $match = [regex]::Match(
        $Text,
        '(?im)^\s*PROCESS TOTAL\s+(?<rss>\d+)\s+kB\s+(?<pss>\d+)\s+kB\s*$'
    )
    if (-not $match.Success) {
        return [pscustomobject]@{ Available = $false; RssKb = $null; PssKb = $null }
    }
    return [pscustomobject]@{
        Available = $true
        RssKb = [long]$match.Groups['rss'].Value
        PssKb = [long]$match.Groups['pss'].Value
    }
}

function Convert-UidState {
    param([AllowEmptyString()][string]$Text)
    $match = [regex]::Match($Text, '(?im)(?<code>-?\d+)\s+\((?<name>[A-Z0-9_]+)\)')
    if (-not $match.Success) {
        return [pscustomobject]@{ Available = $false; Code = $null; Name = $null }
    }
    return [pscustomobject]@{
        Available = $true
        Code = [int]$match.Groups['code'].Value
        Name = $match.Groups['name'].Value
    }
}

function Convert-ActivityEvidence {
    param(
        [AllowEmptyString()][string]$ActivitiesText,
        [AllowEmptyString()][string]$ServicesText,
        [string]$Package
    )
    $escaped = [regex]::Escape($Package)
    $top = $ActivitiesText -match "(?im)(?:topResumedActivity|mResumedActivity|mFocusedApp|mCurrentFocus).*?$escaped(?:/|\.)"
    $foregroundService =
        $ServicesText -match '(?im)\bisForeground=true\b' -or
        $ServicesText -match '(?im)\bforegroundId=[1-9]\d*\b'
    return [pscustomobject]@{
        TopActivity = $top
        ForegroundService = $foregroundService
    }
}

function Get-LifecycleBucket {
    param(
        [string]$ProcessName,
        [string]$MainProcessName,
        [string]$ServiceProcessName,
        $OomScoreAdj,
        $UidStateCode,
        $TopActivity,
        $ForegroundService,
        [string]$Phase
    )
    if (($null -ne $OomScoreAdj -and [int]$OomScoreAdj -ge 900) -or
        ($null -ne $UidStateCode -and [int]$UidStateCode -in 16..19)) {
        return 'CACHED'
    }
    if ($ProcessName -eq $MainProcessName -and $TopActivity -eq $true) { return 'TOP' }
    if ($ProcessName -eq $ServiceProcessName -and $ForegroundService -eq $true) { return 'FGS' }
    if ($ProcessName -eq $MainProcessName -and $UidStateCode -eq 2) { return 'TOP' }
    if ($ProcessName -eq $ServiceProcessName -and $UidStateCode -in @(4, 5)) { return 'FGS' }
    if (($null -ne $UidStateCode -and [int]$UidStateCode -in 6..15) -or
        ($Phase -like 'lifecycle_background*' -and
            $TopActivity -eq $false -and
            $ForegroundService -eq $false)) {
        return 'BACKGROUND'
    }
    return 'ACTIVE_OTHER'
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Label)
    if ($Expected -ne $Actual) {
        throw "Self-test failed for ${Label}: expected '$Expected', got '$Actual'."
    }
}

function Invoke-ParserSelfTest {
    $status = @'
Name: mindlayer
VmRSS: 900000 kB
RssAnon: 700000 kB
RssFile: 180000 kB
RssShmem: 20000 kB
VmSwap: 12500 kB
'@
    $statusValues = Convert-KeyValueKb $status
    Assert-Equal 700000 (Get-MapValue $statusValues 'RssAnon') 'RssAnon'
    Assert-Equal 12500 (Get-MapValue $statusValues 'VmSwap') 'VmSwap'

    $meminfo = @'
                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free
  Native Heap   101000    99000      100      200   111000   120000   105000    15000
      Graphics    22000    21000        0        0    24000
      GL mtrack     8000     8000        0        0     8000
     EGL mtrack     2000     2000        0        0     2000
   Other mtrack     1000     1000        0        0     1000

 App Summary
                       Pss(KB)                        Rss(KB)
                        ------                         ------
         Native Heap:   101000                         111000
            Graphics:    22000                          24000

 Native Allocations
                                Count             Total(kB)
                               ------                ------
  Bitmap (malloced):                2                 16384
Bitmap (nonmalloced):               1                  4096
HardwareBuffer (nonmalloced):       3                  8192
'@
    $parsed = Convert-DumpsysMeminfo $meminfo
    Assert-Equal 105000 $parsed.NativeHeapAllocKb 'native heap allocated'
    Assert-Equal 22000 $parsed.GraphicsPssKb 'graphics PSS'
    Assert-Equal 20480 $parsed.BitmapKb 'bitmap total'
    Assert-Equal 3 $parsed.BitmapCount 'bitmap count'
    Assert-Equal 8192 $parsed.HardwareBufferKb 'hardware buffer total'

    $dma = Convert-DmaBufDump @'
some.process:123
                 Name              Rss              Pss         nr_procs            Inode
         PROCESS TOTAL           396 kB           220 kB
'@
    Assert-Equal $true $dma.Available 'DMA-BUF availability'
    Assert-Equal 396 $dma.RssKb 'DMA-BUF RSS'
    Assert-Equal 220 $dma.PssKb 'DMA-BUF PSS'

    $uidState = Convert-UidState '4 (FGS)'
    Assert-Equal $true $uidState.Available 'UID state availability'
    Assert-Equal 4 $uidState.Code 'UID state code'
    Assert-Equal 'FGS' $uidState.Name 'UID state name'

    $activity = Convert-ActivityEvidence `
        -Package 'com.adsamcik.mindlayer.debug' `
        -ActivitiesText 'topResumedActivity=ActivityRecord{42 com.adsamcik.mindlayer.debug/.ui.MainActivity}' `
        -ServicesText 'isForeground=true foregroundId=17'
    Assert-Equal $true $activity.TopActivity 'top activity evidence'
    Assert-Equal $true $activity.ForegroundService 'foreground service evidence'
    Assert-Equal 'TOP' (Get-LifecycleBucket `
        -ProcessName 'com.adsamcik.mindlayer.debug' `
        -MainProcessName 'com.adsamcik.mindlayer.debug' `
        -ServiceProcessName 'com.adsamcik.mindlayer.debug:ml' `
        -OomScoreAdj 0 -UidStateCode 2 -TopActivity $true `
        -ForegroundService $true -Phase 'lifecycle_top') 'top lifecycle bucket'
    Assert-Equal 'FGS' (Get-LifecycleBucket `
        -ProcessName 'com.adsamcik.mindlayer.debug:ml' `
        -MainProcessName 'com.adsamcik.mindlayer.debug' `
        -ServiceProcessName 'com.adsamcik.mindlayer.debug:ml' `
        -OomScoreAdj 200 -UidStateCode 4 -TopActivity $false `
        -ForegroundService $true -Phase 'lifecycle_fgs_background') 'FGS lifecycle bucket'
    Assert-Equal 'BACKGROUND' (Get-LifecycleBucket `
        -ProcessName 'com.adsamcik.mindlayer.debug' `
        -MainProcessName 'com.adsamcik.mindlayer.debug' `
        -ServiceProcessName 'com.adsamcik.mindlayer.debug:ml' `
        -OomScoreAdj 250 -UidStateCode 10 -TopActivity $false `
        -ForegroundService $false -Phase 'lifecycle_background') 'background lifecycle bucket'
    Assert-Equal 'ACTIVE_OTHER' (Get-LifecycleBucket `
        -ProcessName 'com.adsamcik.mindlayer.debug' `
        -MainProcessName 'com.adsamcik.mindlayer.debug' `
        -ServiceProcessName 'com.adsamcik.mindlayer.debug:ml' `
        -OomScoreAdj 0 -UidStateCode $null -TopActivity $null `
        -ForegroundService $null -Phase 'lifecycle_background') 'phase label is not lifecycle evidence'
    Assert-Equal 'CACHED' (Get-LifecycleBucket `
        -ProcessName 'com.adsamcik.mindlayer.debug' `
        -MainProcessName 'com.adsamcik.mindlayer.debug' `
        -ServiceProcessName 'com.adsamcik.mindlayer.debug:ml' `
        -OomScoreAdj 950 -UidStateCode 19 -TopActivity $false `
        -ForegroundService $false -Phase 'post_instrumentation') 'cached lifecycle bucket'

    $backendObservations = @(Get-ActiveBackendObservations @(
        [pscustomobject]@{ phase = 'default_prewarm_ready'; note = 'model=gemma active_tokens=8192 active_backend=GPU' },
        [pscustomobject]@{ phase = 'max_context_prewarm_ready'; note = 'model=gemma active_tokens=32768 active_backend=CPU' }
    ))
    Assert-Equal 2 $backendObservations.Count 'backend observation count'
    Assert-Equal 'GPU' $backendObservations[0].backend 'default active backend'
    Assert-Equal 'CPU' $backendObservations[1].backend 'fallback active backend'
    Write-Host 'Play memory benchmark parser self-test passed.' -ForegroundColor Green
}

if ($SelfTest) {
    Invoke-ParserSelfTest
    exit 0
}

if ($BitmapWidth * [long]$BitmapHeight -gt 64L * 1024L * 1024L) {
    throw 'BitmapWidth * BitmapHeight must not exceed 64 megapixels.'
}
if ($MaxContextTokens -lt $DefaultContextTokens) {
    throw 'MaxContextTokens must be greater than or equal to DefaultContextTokens.'
}
if ($DetailedSampleIntervalMs -lt $SampleIntervalMs) {
    throw 'DetailedSampleIntervalMs must be greater than or equal to SampleIntervalMs.'
}
if ($CachedHoldMs -gt $CachedWaitMs) {
    throw 'CachedHoldMs must not exceed CachedWaitMs.'
}

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptDirectory '..')).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $repoRoot "artifacts\play-memory\$stamp"
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$rawDirectory = Join-Path $OutputDirectory 'raw'
New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null

function Resolve-AdbExecutable {
    $names = if ($env:OS -eq 'Windows_NT') { @('adb.exe', 'adb') } else { @('adb', 'adb.exe') }
    if (-not [string]::IsNullOrWhiteSpace($AdbPath)) {
        if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
            throw "adb was not found at -AdbPath '$AdbPath'."
        }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }

    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $onPath) { return $onPath.Source }

    $sdkRoots = [System.Collections.Generic.List[string]]::new()
    foreach ($root in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($root)) { $sdkRoots.Add($root) }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $sdkRoots.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    }
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $sdkRoot = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '')
            $sdkRoot = $sdkRoot.Replace('\:', ':').Replace('\\', '\')
            if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoots.Add($sdkRoot) }
        }
    }

    foreach ($root in ($sdkRoots | Select-Object -Unique)) {
        foreach ($name in $names) {
            $candidate = Join-Path $root "platform-tools\$name"
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }
    throw 'adb was not found on PATH or in the configured Android SDK. Pass -AdbPath explicitly.'
}

$AdbExe = Resolve-AdbExecutable
$script:DeviceSerial = $null

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $allArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($script:DeviceSerial)) {
        $allArguments += @('-s', $script:DeviceSerial)
    }
    $allArguments += $Arguments
    $outputLines = & $AdbExe @allArguments 2>&1
    $exitCode = $LASTEXITCODE
    $output = ($outputLines | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed with exit $exitCode`: $output"
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

function Start-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $AdbExe
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $allArguments = @()
    if (-not [string]::IsNullOrWhiteSpace($script:DeviceSerial)) {
        $allArguments += @('-s', $script:DeviceSerial)
    }
    $allArguments += $Arguments
    # Windows PowerShell 5.1's .NET ProcessStartInfo has no ArgumentList.
    # Every argument used here is an adb token with no whitespace/quotes, so a
    # checked join is unambiguous and works in both Desktop and PowerShell 7.
    foreach ($argument in $allArguments) {
        if ($argument -match '[\s"]') {
            throw "Internal error: background adb argument needs quoting: '$argument'"
        }
    }
    $info.Arguments = $allArguments -join ' '
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Unable to start adb instrumentation process.' }
    return $process
}

$devicesRaw = & $AdbExe devices 2>&1
$attachedDevices = @(
    $devicesRaw | ForEach-Object {
        if ($_.ToString() -match '^(?<serial>\S+)\s+device$') { $Matches.serial }
    }
)
if ([string]::IsNullOrWhiteSpace($Device)) {
    if ($attachedDevices.Count -ne 1) {
        throw "Expected exactly one authorised adb device, found $($attachedDevices.Count). Pass -Device explicitly."
    }
    $Device = $attachedDevices[0]
} elseif ($Device -notin $attachedDevices) {
    throw "Device '$Device' is not in adb's authorised device list: $($attachedDevices -join ', ')"
}
$script:DeviceSerial = $Device

function Get-DeviceProperty {
    param([string]$Name)
    return (Invoke-Adb -Arguments @('shell', 'getprop', $Name)).Output.Trim()
}

$apiLevel = [int](Get-DeviceProperty 'ro.build.version.sdk')
if ($apiLevel -lt $MinimumApiLevel) {
    throw "Device API $apiLevel is below required API $MinimumApiLevel. Use an Android 17/API 37 device for complete bitmap diagnostics, or explicitly lower -MinimumApiLevel."
}
$isEmulator = (Get-DeviceProperty 'ro.kernel.qemu') -eq '1'
if ($isEmulator -and -not $AllowEmulator) {
    throw 'The selected target is an emulator. Use one physical device, or pass -AllowEmulator for a diagnostic-only run.'
}

$meminfoText = (Invoke-Adb -Arguments @('shell', 'cat', '/proc/meminfo')).Output
$deviceMem = Convert-KeyValueKb $meminfoText
$memTotalKb = Get-MapValue $deviceMem 'MemTotal'
$memAvailableKb = Get-MapValue $deviceMem 'MemAvailable'
if ($null -eq $memTotalKb) { throw 'Unable to read MemTotal from the device.' }
$memTotalMiB = [math]::Floor($memTotalKb / 1024.0)
if ($memTotalMiB -lt $MinimumDeviceRamMiB) {
    throw "Device exposes only $memTotalMiB MiB MemTotal; benchmark minimum is $MinimumDeviceRamMiB MiB."
}

$gitHead = (& git -C $repoRoot rev-parse HEAD 2>$null | Out-String).Trim()
$gitDirty = -not [string]::IsNullOrWhiteSpace((& git -C $repoRoot status --porcelain 2>$null | Out-String))
$memoryLimiter = (Invoke-Adb -Arguments @('shell', 'am', 'memory-limiter', 'status') -AllowFailure).Output
$metadata = [ordered]@{
    schemaVersion = 1
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    deviceSerial = $Device
    manufacturer = Get-DeviceProperty 'ro.product.manufacturer'
    model = Get-DeviceProperty 'ro.product.model'
    buildFingerprint = Get-DeviceProperty 'ro.build.fingerprint'
    apiLevel = $apiLevel
    isEmulator = $isEmulator
    appUid = $null
    memTotalKb = $memTotalKb
    memAvailableAtStartKb = $memAvailableKb
    gitHead = $gitHead
    gitDirty = $gitDirty
    packageName = $PackageName
    processes = @($PackageName, "$PackageName`:ml")
    workload = [ordered]@{
        requestedBackend = $RequestedBackend
        defaultContextTokens = $DefaultContextTokens
        maxContextTokens = $MaxContextTokens
        promptChars = $PromptChars
        bitmapWidth = $BitmapWidth
        bitmapHeight = $BitmapHeight
        phaseHoldMs = $PhaseHoldMs
        sampleIntervalMs = $SampleIntervalMs
        detailedSampleIntervalMs = $DetailedSampleIntervalMs
        cachedWaitMs = $CachedWaitMs
        cachedHoldMs = $CachedHoldMs
    }
    memoryLimiterStatus = $memoryLimiter
}
$metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'metadata.json') -Encoding utf8
$meminfoText | Set-Content -LiteralPath (Join-Path $rawDirectory 'device-meminfo.txt') -Encoding utf8
$memoryLimiter | Set-Content -LiteralPath (Join-Path $rawDirectory 'memory-limiter-status.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'processes') -AllowFailure).Output |
    Set-Content -LiteralPath (Join-Path $rawDirectory 'activity-processes-before.txt') -Encoding utf8

Write-Host "== Device: $($metadata.manufacturer) $($metadata.model), API $apiLevel, MemTotal $memTotalMiB MiB ==" -ForegroundColor Cyan
Write-Host "== Output: $OutputDirectory ==" -ForegroundColor Cyan

if (-not $SkipBuild) {
    Write-Host '== Building code-only app and benchmark APKs ==' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        $gradle = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
        & $gradle ':app:assembleDebug' ':app:assembleDebugAndroidTest' `
            '-Pmindlayer.bundleGemma=false' `
            '-Pmindlayer.bundleEmbeddings=false' `
            '-Pmindlayer.bundlePaddleocr=false' `
            '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

$appApk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (-not $SkipInstall) {
    foreach ($apk in @($appApk, $testApk)) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }
        Write-Host "Installing $(Split-Path -Leaf $apk) with adb install -r (app data preserved)..."
        [void](Invoke-Adb -Arguments @('install', '-r', '-t', $apk))
    }
}

$packageDump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $PackageName)).Output
$packageDump | Set-Content -LiteralPath (Join-Path $rawDirectory 'package-before.txt') -Encoding utf8
if ($packageDump -notmatch '(?im)^\s*userId=(?<uid>\d+)\s*$') {
    throw "Unable to determine userId for installed package $PackageName."
}
$appUid = [int]$Matches.uid
$metadata['appUid'] = $appUid
$metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'metadata.json') -Encoding utf8

[void](Invoke-Adb -Arguments @(
    'shell', 'run-as', $PackageName, 'rm', '-f', $MarkerPath, $MarkerJournalPath
) -AllowFailure)

if (-not $SkipForceStop) {
    Write-Host 'Force-stopping the debug package for a clean process baseline (models/data preserved)...'
    [void](Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $PackageName))
}

function Read-ProcessFile {
    param([int]$ProcessId, [string]$FileName)
    $path = "/proc/$ProcessId/$FileName"
    $result = Invoke-Adb -Arguments @('shell', 'run-as', $PackageName, 'cat', $path) -AllowFailure
    if ($result.ExitCode -eq 0 -and $result.Output -notmatch '(?i)permission denied|not found') { return $result }
    return Invoke-Adb -Arguments @('shell', 'cat', $path) -AllowFailure
}

function Get-PhaseMarker {
    $result = Invoke-Adb -Arguments @('shell', 'run-as', $PackageName, 'cat', $MarkerPath) -AllowFailure
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.Output)) {
        return [pscustomobject]@{ Sequence = 0; UptimeMs = 0L; Phase = 'instrumentation_startup'; BitmapBytes = 0L; Note = '' }
    }
    $parts = $result.Output.Trim() -split "`t", 5
    if ($parts.Count -lt 4) {
        return [pscustomobject]@{ Sequence = 0; UptimeMs = 0L; Phase = 'invalid_marker'; BitmapBytes = 0L; Note = $result.Output.Trim() }
    }
    return [pscustomobject]@{
        Sequence = [int]$parts[0]
        UptimeMs = [long]$parts[1]
        Phase = $parts[2]
        BitmapBytes = [long]$parts[3]
        Note = if ($parts.Count -ge 5) { $parts[4] } else { '' }
    }
}

function Get-UidStateSnapshot {
    $result = Invoke-Adb -Arguments @('shell', 'am', 'get-uid-state', $appUid.ToString()) -AllowFailure
    $parsed = Convert-UidState $result.Output
    return [pscustomobject]@{
        Available = $result.ExitCode -eq 0 -and $parsed.Available
        Code = $parsed.Code
        Name = $parsed.Name
        Raw = $result.Output
    }
}

function Get-ActivityEvidenceSnapshot {
    param([int]$Sequence, [int]$PhaseSequence, [string]$Phase)
    $activities = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities', $PackageName) -AllowFailure
    $services = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'services', $PackageName) -AllowFailure
    $evidence = Convert-ActivityEvidence `
        -ActivitiesText $activities.Output `
        -ServicesText $services.Output `
        -Package $PackageName
    $safePhase = $Phase -replace '[^A-Za-z0-9_.-]', '_'
    $rawBase = '{0:D5}-{1:D2}-{2}' -f $Sequence, $PhaseSequence, $safePhase
    $activities.Output | Set-Content -LiteralPath (Join-Path $rawDirectory "$rawBase-activities.txt") -Encoding utf8
    $services.Output | Set-Content -LiteralPath (Join-Path $rawDirectory "$rawBase-services.txt") -Encoding utf8
    return [pscustomobject]@{
        TopActivity = if ($activities.ExitCode -eq 0) { $evidence.TopActivity } else { $null }
        ForegroundService = if ($services.ExitCode -eq 0) { $evidence.ForegroundService } else { $null }
        ActivitiesOk = $activities.ExitCode -eq 0
        ServicesOk = $services.ExitCode -eq 0
    }
}

function Get-PhaseEvents {
    $result = Invoke-Adb -Arguments @('shell', 'run-as', $PackageName, 'cat', $MarkerJournalPath) -AllowFailure
    $result.Output | Set-Content -LiteralPath (Join-Path $OutputDirectory 'phase-events.tsv') -Encoding utf8
    $events = [System.Collections.Generic.List[object]]::new()
    if ($result.ExitCode -ne 0) { return $events }
    foreach ($line in ($result.Output -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line -split "`t", 5
        if ($parts.Count -lt 4) { continue }
        $events.Add([pscustomobject][ordered]@{
            sequence = [int]$parts[0]
            device_uptime_ms = [long]$parts[1]
            phase = $parts[2]
            tracked_bitmap_bytes = [long]$parts[3]
            note = if ($parts.Count -ge 5) { $parts[4] } else { '' }
        })
    }
    return $events
}

function Get-ProcessIdByName {
    param([string]$ProcessName)
    $result = Invoke-Adb -Arguments @('shell', 'pidof', $ProcessName) -AllowFailure
    if ($result.ExitCode -ne 0 -or $result.Output -notmatch '(?<pid>\d+)') { return $null }
    return [int]$Matches.pid
}

function Get-Percentile90 {
    param([object[]]$Values)
    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [long]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { return $null }
    $index = [math]::Ceiling(0.90 * $numbers.Count) - 1
    return $numbers[[math]::Max(0, $index)]
}

function Get-Maximum {
    param([object[]]$Values)
    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [long]$_ })
    if ($numbers.Count -eq 0) { return $null }
    return ($numbers | Measure-Object -Maximum).Maximum
}

$instrumentArguments = @(
    'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', $TestClass,
    '-e', 'defaultContextTokens', $DefaultContextTokens.ToString(),
    '-e', 'maxContextTokens', $MaxContextTokens.ToString(),
    '-e', 'backend', $RequestedBackend,
    '-e', 'promptChars', $PromptChars.ToString(),
    '-e', 'bitmapWidth', $BitmapWidth.ToString(),
    '-e', 'bitmapHeight', $BitmapHeight.ToString(),
    '-e', 'phaseHoldMs', $PhaseHoldMs.ToString(),
    $Runner
)

Write-Host '== Starting real-service workload and memory sampler ==' -ForegroundColor Cyan
$instrument = Start-Adb -Arguments $instrumentArguments
$stdoutTask = $instrument.StandardOutput.ReadToEndAsync()
$stderrTask = $instrument.StandardError.ReadToEndAsync()
$clock = [System.Diagnostics.Stopwatch]::StartNew()
$samples = [System.Collections.Generic.List[object]]::new()
$pidTransitions = [System.Collections.Generic.List[object]]::new()
$firstSeenProcessMs = @{}
$lastPid = @{}
$lastDetailAt = @{}
$lastPhaseSequence = -1
$sampleSequence = 0
$processNames = @($PackageName, "$PackageName`:ml")
$activityEvidence = [pscustomobject]@{
    TopActivity = $null
    ForegroundService = $null
    ActivitiesOk = $false
    ServicesOk = $false
}
$lastActivityEvidenceAt = -1L
$instrumentExitElapsedMs = $null
$cachedReachedElapsedMs = $null
$cachedTailTimedOut = $false
$idleServiceExitObserved = $false
$idleServiceExitElapsedMs = $null
$idleServiceExitPhase = $null

while ($true) {
    $iterationStart = $clock.ElapsedMilliseconds
    $sampleSequence++
    $marker = Get-PhaseMarker
    $phaseChanged = $marker.Sequence -ne $lastPhaseSequence
    if ($phaseChanged) {
        Write-Host ("  phase {0}: {1} {2}" -f $marker.Sequence, $marker.Phase, $marker.Note)
        $lastPhaseSequence = $marker.Sequence
    }

    if ($instrument.HasExited -and $null -eq $instrumentExitElapsedMs) {
        $instrumentExitElapsedMs = $clock.ElapsedMilliseconds
        Write-Host '  instrumentation exited; sampling the cached-state tail...'
    }

    $uidState = Get-UidStateSnapshot
    $activityDue = $phaseChanged -or $lastActivityEvidenceAt -lt 0 -or
        ($clock.ElapsedMilliseconds - $lastActivityEvidenceAt) -ge $DetailedSampleIntervalMs
    if ($activityDue) {
        $activityEvidence = Get-ActivityEvidenceSnapshot `
            -Sequence $sampleSequence `
            -PhaseSequence $marker.Sequence `
            -Phase $marker.Phase
        $safePhase = $marker.Phase -replace '[^A-Za-z0-9_.-]', '_'
        $uidRawBase = '{0:D5}-{1:D2}-{2}' -f $sampleSequence, $marker.Sequence, $safePhase
        $uidState.Raw | Set-Content -LiteralPath (Join-Path $rawDirectory "$uidRawBase-uid-state.txt") -Encoding utf8
        $lastActivityEvidenceAt = $clock.ElapsedMilliseconds
    }

    $iterationRows = [System.Collections.Generic.List[object]]::new()

    foreach ($processName in $processNames) {
        $processId = Get-ProcessIdByName $processName
        if ($null -eq $processId) {
            $isIdleExitPhase = $marker.Phase -eq 'lifecycle_background_unbound' -or
                $marker.Phase -eq 'instrumentation_complete' -or
                $instrument.HasExited
            if (
                -not $idleServiceExitObserved -and
                $processName -eq "$PackageName`:ml" -and
                $firstSeenProcessMs.ContainsKey($processName) -and
                $isIdleExitPhase
            ) {
                $idleServiceExitObserved = $true
                $idleServiceExitElapsedMs = $clock.ElapsedMilliseconds
                $idleServiceExitPhase = $marker.Phase
                Write-Host '  isolated :ml process exited after final idle unbind'
            }
            continue
        }
        if (-not $firstSeenProcessMs.ContainsKey($processName)) {
            $firstSeenProcessMs[$processName] = $clock.ElapsedMilliseconds
        }
        if (-not $lastPid.ContainsKey($processName) -or [int]$lastPid[$processName] -ne $processId) {
            $pidTransitions.Add([pscustomobject][ordered]@{
                elapsed_ms = $clock.ElapsedMilliseconds
                phase = $marker.Phase
                process_name = $processName
                previous_pid = if ($lastPid.ContainsKey($processName)) { [int]$lastPid[$processName] } else { $null }
                pid = $processId
            })
            $lastPid[$processName] = $processId
        }

        $statusResult = Read-ProcessFile -ProcessId $processId -FileName 'status'
        $smapsResult = Read-ProcessFile -ProcessId $processId -FileName 'smaps_rollup'
        $oomResult = Read-ProcessFile -ProcessId $processId -FileName 'oom_score_adj'
        $status = Convert-KeyValueKb $statusResult.Output
        $smaps = Convert-KeyValueKb $smapsResult.Output
        $oomScoreAdj = if ($oomResult.Output.Trim() -match '^-?\d+$') { [int]$oomResult.Output.Trim() } else { $null }

        $detailKey = "$processName|$($marker.Sequence)"
        $detailDue = -not $lastDetailAt.ContainsKey($detailKey) -or
            ($clock.ElapsedMilliseconds - [long]$lastDetailAt[$detailKey]) -ge $DetailedSampleIntervalMs
        $detail = $null
        $dma = $null
        $meminfoOk = $null
        $dmaOk = $null
        if ($detailDue) {
            $lastDetailAt[$detailKey] = $clock.ElapsedMilliseconds
            $meminfoResult = Invoke-Adb -Arguments @('shell', 'dumpsys', 'meminfo', '-a', $processId.ToString()) -AllowFailure
            $detail = Convert-DumpsysMeminfo $meminfoResult.Output
            $meminfoOk = $meminfoResult.ExitCode -eq 0 -and $meminfoResult.Output -match 'MEMINFO in pid'

            $dmaResult = Invoke-Adb -Arguments @('shell', 'dmabuf_dump', $processId.ToString()) -AllowFailure
            $dma = Convert-DmaBufDump $dmaResult.Output
            $dmaOk = $dma.Available

            $safeProcess = $processName.Replace(':', '_').Replace('.', '_')
            $rawBase = '{0:D5}-{1:D2}-{2}-{3}' -f $sampleSequence, $marker.Sequence, $marker.Phase, $safeProcess
            $meminfoResult.Output | Set-Content -LiteralPath (Join-Path $rawDirectory "$rawBase-meminfo.txt") -Encoding utf8
            $dmaResult.Output | Set-Content -LiteralPath (Join-Path $rawDirectory "$rawBase-dmabuf.txt") -Encoding utf8
        }

        $rssAnon = Get-MapValue $status 'RssAnon'
        $vmSwap = Get-MapValue $status 'VmSwap'
        $playMetric = if ($null -ne $rssAnon -and $null -ne $vmSwap) { $rssAnon + $vmSwap } else { $null }
        $rssFile = Get-MapValue $status 'RssFile'
        $rssShmem = Get-MapValue $status 'RssShmem'
        $fileShared = if ($null -ne $rssFile -and $null -ne $rssShmem) { $rssFile + $rssShmem } else { $null }

        $lifecycleBucket = Get-LifecycleBucket `
            -ProcessName $processName `
            -MainProcessName $PackageName `
            -ServiceProcessName "$PackageName`:ml" `
            -OomScoreAdj $oomScoreAdj `
            -UidStateCode $uidState.Code `
            -TopActivity $activityEvidence.TopActivity `
            -ForegroundService $activityEvidence.ForegroundService `
            -Phase $marker.Phase

        $row = [pscustomobject][ordered]@{
            sample_utc = [DateTime]::UtcNow.ToString('o')
            elapsed_ms = $clock.ElapsedMilliseconds
            sequence = $sampleSequence
            phase_sequence = $marker.Sequence
            phase = $marker.Phase
            process_name = $processName
            pid = $processId
            uid_state_ok = $uidState.Available
            uid_state_code = $uidState.Code
            uid_state_name = $uidState.Name
            activities_ok = $activityEvidence.ActivitiesOk
            services_ok = $activityEvidence.ServicesOk
            top_activity = $activityEvidence.TopActivity
            foreground_service = $activityEvidence.ForegroundService
            lifecycle_bucket = $lifecycleBucket
            oom_score_adj = $oomScoreAdj
            rss_anon_kb = $rssAnon
            vm_swap_kb = $vmSwap
            play_anon_rss_swap_kb = $playMetric
            rss_file_kb = $rssFile
            rss_shmem_kb = $rssShmem
            file_shared_rss_kb = $fileShared
            vm_rss_kb = Get-MapValue $status 'VmRSS'
            vm_hwm_kb = Get-MapValue $status 'VmHWM'
            pss_kb = Get-MapValue $smaps 'Pss'
            pss_anon_kb = Get-MapValue $smaps 'Pss_Anon'
            pss_file_kb = Get-MapValue $smaps 'Pss_File'
            pss_shmem_kb = Get-MapValue $smaps 'Pss_Shmem'
            private_dirty_kb = Get-MapValue $smaps 'Private_Dirty'
            shared_clean_kb = Get-MapValue $smaps 'Shared_Clean'
            native_heap_pss_kb = if ($null -ne $detail) { $detail.NativeHeapPssKb } else { $null }
            native_heap_rss_kb = if ($null -ne $detail) { $detail.NativeHeapRssKb } else { $null }
            native_heap_alloc_kb = if ($null -ne $detail) { $detail.NativeHeapAllocKb } else { $null }
            graphics_pss_kb = if ($null -ne $detail) { $detail.GraphicsPssKb } else { $null }
            graphics_rss_kb = if ($null -ne $detail) { $detail.GraphicsRssKb } else { $null }
            gfx_dev_pss_kb = if ($null -ne $detail) { $detail.GfxDevPssKb } else { $null }
            gl_mtrack_pss_kb = if ($null -ne $detail) { $detail.GlMtrackPssKb } else { $null }
            egl_mtrack_pss_kb = if ($null -ne $detail) { $detail.EglMtrackPssKb } else { $null }
            other_mtrack_pss_kb = if ($null -ne $detail) { $detail.OtherMtrackPssKb } else { $null }
            bitmap_counter_available = if ($null -ne $detail) { $detail.BitmapCounterAvailable } else { $null }
            bitmap_count = if ($null -ne $detail) { $detail.BitmapCount } else { $null }
            bitmap_kb = if ($null -ne $detail) { $detail.BitmapKb } else { $null }
            tracked_workload_bitmap_kb = [math]::Ceiling($marker.BitmapBytes / 1024.0)
            hardware_buffer_kb = if ($null -ne $detail) { $detail.HardwareBufferKb } else { $null }
            dmabuf_rss_kb = if ($null -ne $dma) { $dma.RssKb } else { $null }
            dmabuf_pss_kb = if ($null -ne $dma) { $dma.PssKb } else { $null }
            status_ok = $statusResult.ExitCode -eq 0 -and $null -ne $rssAnon
            smaps_ok = $smapsResult.ExitCode -eq 0 -and $null -ne (Get-MapValue $smaps 'Pss')
            meminfo_ok = $meminfoOk
            dmabuf_ok = $dmaOk
        }
        $samples.Add($row)
        $iterationRows.Add($row)
    }

    if ($null -ne $instrumentExitElapsedMs) {
        $mainCached = @($iterationRows | Where-Object {
            $_.process_name -eq $PackageName -and $_.lifecycle_bucket -eq 'CACHED'
        }).Count -gt 0
        if ($mainCached -and $null -eq $cachedReachedElapsedMs) {
            $cachedReachedElapsedMs = $clock.ElapsedMilliseconds
            Write-Host "  cached state observed at $cachedReachedElapsedMs ms; holding for $CachedHoldMs ms..."
        }
        if ($null -ne $cachedReachedElapsedMs -and
            ($clock.ElapsedMilliseconds - $cachedReachedElapsedMs) -ge $CachedHoldMs) {
            break
        }
        if (($clock.ElapsedMilliseconds - $instrumentExitElapsedMs) -ge $CachedWaitMs) {
            $cachedTailTimedOut = $true
            break
        }
    }

    $spent = $clock.ElapsedMilliseconds - $iterationStart
    $remaining = $SampleIntervalMs - $spent
    if ($remaining -gt 0) { Start-Sleep -Milliseconds $remaining }
}

$instrument.WaitForExit()
$instrumentOutput = $stdoutTask.GetAwaiter().GetResult()
$instrumentError = $stderrTask.GetAwaiter().GetResult()
$instrumentTranscript = $instrumentOutput
if (-not [string]::IsNullOrWhiteSpace($instrumentError)) {
    $instrumentTranscript += "`n--- stderr ---`n$instrumentError"
}
$instrumentTranscript | Set-Content -LiteralPath (Join-Path $OutputDirectory 'instrumentation.txt') -Encoding utf8
$phaseEvents = @(Get-PhaseEvents)
$phaseTimings = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $phaseEvents.Count; $index++) {
    $event = $phaseEvents[$index]
    $next = if ($index + 1 -lt $phaseEvents.Count) { $phaseEvents[$index + 1] } else { $null }
    $phaseTimings.Add([pscustomobject][ordered]@{
        sequence = $event.sequence
        phase = $event.phase
        device_uptime_ms = $event.device_uptime_ms
        duration_ms = if ($null -ne $next) { $next.device_uptime_ms - $event.device_uptime_ms } else { $null }
        tracked_bitmap_bytes = $event.tracked_bitmap_bytes
        note = $event.note
    })
}
$phaseTimings | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'phase-timings.csv') -NoTypeInformation -Encoding utf8
$pidTransitions | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'pid-transitions.csv') -NoTypeInformation -Encoding utf8
(Invoke-Adb -Arguments @('logcat', '-d', '-v', 'threadtime', '-s', 'PlayMemoryBench:I', '*:S') -AllowFailure).Output |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'phase-logcat.txt') -Encoding utf8
(Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'processes') -AllowFailure).Output |
    Set-Content -LiteralPath (Join-Path $rawDirectory 'activity-processes-after.txt') -Encoding utf8

$samplesPath = Join-Path $OutputDirectory 'samples.csv'
$samples | Export-Csv -LiteralPath $samplesPath -NoTypeInformation -Encoding utf8

$aggregateSamples = [System.Collections.Generic.List[object]]::new()
foreach ($group in ($samples | Group-Object sequence)) {
    $rows = @($group.Group | Where-Object { $null -ne $_.play_anon_rss_swap_kb })
    if ($rows.Count -eq 0) { continue }
    $aggregateSamples.Add([pscustomobject][ordered]@{
        sequence = [int]$group.Name
        sample_utc = $rows[0].sample_utc
        elapsed_ms = ($rows | Measure-Object elapsed_ms -Minimum).Minimum
        phase = $rows[0].phase
        process_count = $rows.Count
        package_anon_rss_swap_kb = ($rows | Measure-Object play_anon_rss_swap_kb -Sum).Sum
        package_vm_rss_kb = ($rows | Where-Object { $null -ne $_.vm_rss_kb } | Measure-Object vm_rss_kb -Sum).Sum
        package_pss_kb = ($rows | Where-Object { $null -ne $_.pss_kb } | Measure-Object pss_kb -Sum).Sum
    })
}
$aggregateSamples | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'aggregate-samples.csv') -NoTypeInformation -Encoding utf8

$phaseSummaries = [System.Collections.Generic.List[object]]::new()
foreach ($group in ($samples | Group-Object process_name, phase)) {
    $rows = @($group.Group)
    $phaseSummaries.Add([pscustomobject][ordered]@{
        processName = $rows[0].process_name
        phase = $rows[0].phase
        samples = $rows.Count
        rssAnonPeakKb = Get-Maximum @($rows.rss_anon_kb)
        vmSwapPeakKb = Get-Maximum @($rows.vm_swap_kb)
        anonRssSwapPeakKb = Get-Maximum @($rows.play_anon_rss_swap_kb)
        anonRssSwapLocalP90Kb = Get-Percentile90 @($rows.play_anon_rss_swap_kb)
        rssFilePeakKb = Get-Maximum @($rows.rss_file_kb)
        rssShmemPeakKb = Get-Maximum @($rows.rss_shmem_kb)
        fileSharedRssPeakKb = Get-Maximum @($rows.file_shared_rss_kb)
        pssPeakKb = Get-Maximum @($rows.pss_kb)
        nativeHeapPssPeakKb = Get-Maximum @($rows.native_heap_pss_kb)
        nativeHeapRssPeakKb = Get-Maximum @($rows.native_heap_rss_kb)
        nativeHeapAllocatedPeakKb = Get-Maximum @($rows.native_heap_alloc_kb)
        graphicsPssPeakKb = Get-Maximum @($rows.graphics_pss_kb)
        graphicsRssPeakKb = Get-Maximum @($rows.graphics_rss_kb)
        gfxDevPssPeakKb = Get-Maximum @($rows.gfx_dev_pss_kb)
        glMtrackPssPeakKb = Get-Maximum @($rows.gl_mtrack_pss_kb)
        eglMtrackPssPeakKb = Get-Maximum @($rows.egl_mtrack_pss_kb)
        otherMtrackPssPeakKb = Get-Maximum @($rows.other_mtrack_pss_kb)
        bitmapPeakKb = Get-Maximum @($rows.bitmap_kb)
        trackedWorkloadBitmapPeakKb = Get-Maximum @($rows.tracked_workload_bitmap_kb)
        hardwareBufferPeakKb = Get-Maximum @($rows.hardware_buffer_kb)
        dmaBufRssPeakKb = Get-Maximum @($rows.dmabuf_rss_kb)
        dmaBufPssPeakKb = Get-Maximum @($rows.dmabuf_pss_kb)
    })
}

$processSummaries = [System.Collections.Generic.List[object]]::new()
foreach ($group in ($samples | Group-Object process_name)) {
    $rows = @($group.Group)
    $processSummaries.Add([pscustomobject][ordered]@{
        processName = $rows[0].process_name
        samples = $rows.Count
        rssAnonPeakKb = Get-Maximum @($rows.rss_anon_kb)
        vmSwapPeakKb = Get-Maximum @($rows.vm_swap_kb)
        anonRssSwapPeakKb = Get-Maximum @($rows.play_anon_rss_swap_kb)
        anonRssSwapLocalP90Kb = Get-Percentile90 @($rows.play_anon_rss_swap_kb)
        rssFilePeakKb = Get-Maximum @($rows.rss_file_kb)
        rssShmemPeakKb = Get-Maximum @($rows.rss_shmem_kb)
        fileSharedRssPeakKb = Get-Maximum @($rows.file_shared_rss_kb)
        pssPeakKb = Get-Maximum @($rows.pss_kb)
        nativeHeapPssPeakKb = Get-Maximum @($rows.native_heap_pss_kb)
        nativeHeapRssPeakKb = Get-Maximum @($rows.native_heap_rss_kb)
        nativeHeapAllocatedPeakKb = Get-Maximum @($rows.native_heap_alloc_kb)
        graphicsPssPeakKb = Get-Maximum @($rows.graphics_pss_kb)
        graphicsRssPeakKb = Get-Maximum @($rows.graphics_rss_kb)
        gfxDevPssPeakKb = Get-Maximum @($rows.gfx_dev_pss_kb)
        glMtrackPssPeakKb = Get-Maximum @($rows.gl_mtrack_pss_kb)
        eglMtrackPssPeakKb = Get-Maximum @($rows.egl_mtrack_pss_kb)
        otherMtrackPssPeakKb = Get-Maximum @($rows.other_mtrack_pss_kb)
        bitmapPeakKb = Get-Maximum @($rows.bitmap_kb)
        trackedWorkloadBitmapPeakKb = Get-Maximum @($rows.tracked_workload_bitmap_kb)
        hardwareBufferPeakKb = Get-Maximum @($rows.hardware_buffer_kb)
        dmaBufRssPeakKb = Get-Maximum @($rows.dmabuf_rss_kb)
        dmaBufPssPeakKb = Get-Maximum @($rows.dmabuf_pss_kb)
    })
}

function Get-FirstLifecycleSequence {
    param(
        [string]$ProcessName,
        [string]$Bucket,
        [int]$After = -1,
        [string]$PhasePrefix = ''
    )
    $matches = @($samples | Where-Object {
        $_.process_name -eq $ProcessName -and
        $_.lifecycle_bucket -eq $Bucket -and
        [int]$_.sequence -gt $After -and
        ([string]::IsNullOrWhiteSpace($PhasePrefix) -or $_.phase -like "$PhasePrefix*")
    } | Sort-Object sequence)
    if ($matches.Count -eq 0) { return $null }
    return [int]$matches[0].sequence
}

function Get-PhaseDurationMs {
    param([string]$Phase)
    $match = @($phaseTimings | Where-Object { $_.phase -eq $Phase } | Select-Object -First 1)
    if ($match.Count -eq 0) { return $null }
    return $match[0].duration_ms
}

$topSequence = Get-FirstLifecycleSequence -ProcessName $PackageName -Bucket 'TOP'
$fgsSequence = if ($null -ne $topSequence) {
    Get-FirstLifecycleSequence -ProcessName "$PackageName`:ml" -Bucket 'FGS' -After $topSequence
} else { $null }
$backgroundSequence = if ($null -ne $fgsSequence) {
    Get-FirstLifecycleSequence `
        -ProcessName $PackageName `
        -Bucket 'BACKGROUND' `
        -After $fgsSequence `
        -PhasePrefix 'lifecycle_background'
} else { $null }
$cachedSequence = if ($null -ne $backgroundSequence) {
    Get-FirstLifecycleSequence -ProcessName $PackageName -Bucket 'CACHED' -After $backgroundSequence
} else { $null }
$lifecycleTransitionPassed =
    $null -ne $topSequence -and
    $null -ne $fgsSequence -and
    $null -ne $backgroundSequence -and
    $null -ne $cachedSequence

$processLifecycleSummaries = @($samples | Group-Object process_name | ForEach-Object {
    $rows = @($_.Group)
    [pscustomobject][ordered]@{
        processName = $rows[0].process_name
        pids = @($rows.pid | Select-Object -Unique)
        observedBuckets = @($rows.lifecycle_bucket | Select-Object -Unique)
        observedUidStates = @($rows.uid_state_name | Where-Object { $null -ne $_ } | Select-Object -Unique)
        minimumOomScoreAdj = ($rows.oom_score_adj | Where-Object { $null -ne $_ } | Measure-Object -Minimum).Minimum
        maximumOomScoreAdj = ($rows.oom_score_adj | Where-Object { $null -ne $_ } | Measure-Object -Maximum).Maximum
    }
})

$servicePidCount = @($pidTransitions | Where-Object {
    $_.process_name -eq "$PackageName`:ml"
} | Select-Object -ExpandProperty pid -Unique).Count
$contextResizeExpected = $MaxContextTokens -gt $DefaultContextTokens
$contextResizeRestartObserved = $servicePidCount -ge 2
$contextResizeTransitionPassed = -not $contextResizeExpected -or $contextResizeRestartObserved

$serviceRows = @($samples | Where-Object { $_.process_name -eq "$PackageName`:ml" })
$exactPlayAvailable = @($serviceRows | Where-Object { $null -ne $_.play_anon_rss_swap_kb }).Count -gt 0
$bitmapAvailable = @($samples | Where-Object { $_.bitmap_counter_available -eq $true }).Count -gt 0
$dmaAvailable = @($samples | Where-Object { $_.dmabuf_ok -eq $true }).Count -gt 0
$completedMarker = Get-PhaseMarker
$instrumentPassed = $instrument.ExitCode -eq 0 -and
    $instrumentTranscript -match 'OK \(1 test\)' -and
    $completedMarker.Phase -eq 'instrumentation_complete'
$backendObservations = @(Get-ActiveBackendObservations $phaseEvents)
$requiredBackendPhases = @('default_prewarm_ready', 'max_context_prewarm_ready')
$missingBackendPhases = @($requiredBackendPhases | Where-Object {
    $_ -notin @($backendObservations.phase)
})
$backendMismatches = @($backendObservations | Where-Object {
    $_.backend -ne $RequestedBackend
})
$backendIdentityPassed = $missingBackendPhases.Count -eq 0 -and
    ($AllowBackendFallback -or $backendMismatches.Count -eq 0)

$summary = [ordered]@{
    schemaVersion = 2
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
    instrumentationExitCode = $instrument.ExitCode
    instrumentationPassed = $instrumentPassed
    finalPhase = $completedMarker.Phase
    backendIdentity = [ordered]@{
        requested = $RequestedBackend
        fallbackAllowed = $AllowBackendFallback.IsPresent
        observations = $backendObservations
        missingRequiredPhases = $missingBackendPhases
        mismatches = $backendMismatches
        passed = $backendIdentityPassed
    }
    hostMilestones = [ordered]@{
        instrumentationToMainProcessMs = if ($firstSeenProcessMs.ContainsKey($PackageName)) { $firstSeenProcessMs[$PackageName] } else { $null }
        instrumentationToServiceProcessMs = if ($firstSeenProcessMs.ContainsKey("$PackageName`:ml")) { $firstSeenProcessMs["$PackageName`:ml"] } else { $null }
        instrumentationExitMs = $instrumentExitElapsedMs
        cachedReachedMs = $cachedReachedElapsedMs
        cachedTailTimedOut = $cachedTailTimedOut
        servicePidCount = $servicePidCount
        contextResizeRestartExpected = $contextResizeExpected
        contextResizeRestartObserved = $contextResizeRestartObserved
        contextResizeTransitionPassed = $contextResizeTransitionPassed
        idleServiceExitObserved = $idleServiceExitObserved
        idleServiceExitElapsedMs = $idleServiceExitElapsedMs
        idleServiceExitPhase = $idleServiceExitPhase
    }
    operationDurationsMs = [ordered]@{
        coldServiceConnect = Get-PhaseDurationMs 'service_connect_begin'
        defaultPrewarm = Get-PhaseDurationMs 'default_prewarm_begin'
        defaultFirstInference = Get-PhaseDurationMs 'first_inference_begin'
        multimodalInference = Get-PhaseDurationMs 'multimodal_inference_begin'
        embeddingFirstRequest = Get-PhaseDurationMs 'embedding_first_request_begin'
        ocrFirstRequest = Get-PhaseDurationMs 'ocr_first_request_begin'
        coexistenceActive = Get-PhaseDurationMs 'coexistence_active_begin'
        maxContextResizePrewarm = Get-PhaseDurationMs 'max_context_resize_begin'
        maxContextFirstInference = Get-PhaseDurationMs 'max_context_first_inference_begin'
        lifecycleInferenceToFgsCheckpoint = Get-PhaseDurationMs 'lifecycle_fgs_inference_begin'
    }
    lifecycle = [ordered]@{
        requiredSequence = @('TOP', 'FGS', 'BACKGROUND', 'CACHED')
        transitionPassed = $lifecycleTransitionPassed
        topSequence = $topSequence
        fgsSequence = $fgsSequence
        backgroundSequence = $backgroundSequence
        cachedSequence = $cachedSequence
        uidStateAvailable = @($samples | Where-Object { $null -ne $_.uid_state_code }).Count -gt 0
        topActivityEvidenceAvailable = @($samples | Where-Object { $null -ne $_.top_activity }).Count -gt 0
        foregroundServiceEvidenceAvailable = @($samples | Where-Object { $null -ne $_.foreground_service }).Count -gt 0
        processSummaries = $processLifecycleSummaries
    }
    coverage = [ordered]@{
        exactServiceRssAnonPlusVmSwap = $exactPlayAvailable
        bitmapNativeAllocationCounters = $bitmapAvailable
        dmaBufPerProcess = $dmaAvailable
        bitmapFallback = 'tracked_workload_bitmap_kb is only the benchmark-owned input bitmap, not the process-wide Play bitmap metric'
        dmaBufFallback = 'graphics/mtrack and HardwareBuffer counters remain separate; missing dmabuf_dump is never recorded as zero'
    }
    phaseTimings = $phaseTimings
    pidTransitions = $pidTransitions
    processSummaries = $processSummaries
    phaseProcessSummaries = $phaseSummaries
    packageAggregate = [ordered]@{
        diagnosticOnly = $true
        note = 'Play public documentation exposes processName but does not define package-sum compliance semantics.'
        anonRssSwapPeakKb = Get-Maximum @($aggregateSamples.package_anon_rss_swap_kb)
        anonRssSwapLocalP90Kb = Get-Percentile90 @($aggregateSamples.package_anon_rss_swap_kb)
    }
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8

function Format-MiB {
    param($Kilobytes)
    if ($null -eq $Kilobytes -or "$Kilobytes" -eq '') { return 'n/a' }
    return ('{0:N1}' -f ([double]$Kilobytes / 1024.0))
}

function Format-Milliseconds {
    param($Milliseconds)
    if ($null -eq $Milliseconds -or "$Milliseconds" -eq '') { return 'n/a' }
    return ('{0:N0}' -f [double]$Milliseconds)
}

$markdown = [System.Collections.Generic.List[string]]::new()
$markdown.Add('# Mindlayer Play-oriented memory benchmark')
$markdown.Add('')
$markdown.Add("- Device: $($metadata.manufacturer) $($metadata.model), API $apiLevel, MemTotal $memTotalMiB MiB")
$markdown.Add("- Workload: $RequestedBackend request, $DefaultContextTokens -> $MaxContextTokens tokens, $PromptChars prompt characters, ${BitmapWidth}x${BitmapHeight} ARGB_8888 bitmap")
$markdown.Add("- Instrumentation passed: $instrumentPassed; final phase: $($completedMarker.Phase)")
$markdown.Add("- Backend identity: requested=$RequestedBackend passed=$backendIdentityPassed fallbackAllowed=$($AllowBackendFallback.IsPresent)")
$markdown.Add('')
$markdown.Add('## Lifecycle and operation timings')
$markdown.Add('')
$markdown.Add("- Required TOP -> FGS -> background -> cached sequence: $lifecycleTransitionPassed")
$markdown.Add("- Default -> max context process restart: expected=$contextResizeExpected observed=$contextResizeRestartObserved")
$markdown.Add("- Final idle unbind released the isolated :ml process: $idleServiceExitObserved")
$markdown.Add("- Instrumentation start -> main/service PID: $(Format-Milliseconds $summary.hostMilestones.instrumentationToMainProcessMs) / $(Format-Milliseconds $summary.hostMilestones.instrumentationToServiceProcessMs) ms")
$markdown.Add('')
$markdown.Add('| Operation | Duration ms |')
$markdown.Add('|---|---:|')
$markdown.Add("| Cold service connect | $(Format-Milliseconds $summary.operationDurationsMs.coldServiceConnect) |")
$markdown.Add("| Default-context prewarm | $(Format-Milliseconds $summary.operationDurationsMs.defaultPrewarm) |")
$markdown.Add("| Default-context first inference | $(Format-Milliseconds $summary.operationDurationsMs.defaultFirstInference) |")
$markdown.Add("| Text + image inference | $(Format-Milliseconds $summary.operationDurationsMs.multimodalInference) |")
$markdown.Add("| First embedding | $(Format-Milliseconds $summary.operationDurationsMs.embeddingFirstRequest) |")
$markdown.Add("| First OCR | $(Format-Milliseconds $summary.operationDurationsMs.ocrFirstRequest) |")
$markdown.Add("| Concurrent chat + embedding + OCR | $(Format-Milliseconds $summary.operationDurationsMs.coexistenceActive) |")
$markdown.Add("| Max-context restart + prewarm | $(Format-Milliseconds $summary.operationDurationsMs.maxContextResizePrewarm) |")
$markdown.Add("| Max-context first inference | $(Format-Milliseconds $summary.operationDurationsMs.maxContextFirstInference) |")
$markdown.Add('')
$markdown.Add('| Process | PIDs | Observed lifecycle buckets | UID states | OOM adj range |')
$markdown.Add('|---|---|---|---|---:|')
foreach ($row in $processLifecycleSummaries) {
    $markdown.Add("| $($row.processName) | $($row.pids -join ', ') | $($row.observedBuckets -join ', ') | $($row.observedUidStates -join ', ') | $($row.minimumOomScoreAdj)..$($row.maximumOomScoreAdj) |")
}
$markdown.Add('')
$markdown.Add('## Per-process peaks')
$markdown.Add('')
$markdown.Add('| Process | Anon RSS + Swap peak MiB | Local-sample P90 MiB | File + shared RSS peak MiB | PSS peak MiB | Native heap alloc/PSS peak MiB | Graphics PSS/RSS peak MiB | Bitmap peak MiB | DMA-BUF RSS/PSS peak MiB |')
$markdown.Add('|---|---:|---:|---:|---:|---:|---:|---:|---:|')
foreach ($row in $processSummaries) {
    $markdown.Add("| $($row.processName) | $(Format-MiB $row.anonRssSwapPeakKb) | $(Format-MiB $row.anonRssSwapLocalP90Kb) | $(Format-MiB $row.fileSharedRssPeakKb) | $(Format-MiB $row.pssPeakKb) | $(Format-MiB $row.nativeHeapAllocatedPeakKb) / $(Format-MiB $row.nativeHeapPssPeakKb) | $(Format-MiB $row.graphicsPssPeakKb) / $(Format-MiB $row.graphicsRssPeakKb) | $(Format-MiB $row.bitmapPeakKb) | $(Format-MiB $row.dmaBufRssPeakKb) / $(Format-MiB $row.dmaBufPssPeakKb) |")
}
$markdown.Add('')
$markdown.Add('## Per-phase process peaks')
$markdown.Add('')
$markdown.Add('| Process | Phase | Samples | Anon RSS + Swap MiB | File + shared RSS MiB | PSS MiB | Native heap alloc/PSS MiB | Graphics PSS/RSS MiB | Bitmap MiB | DMA-BUF RSS/PSS MiB |')
$markdown.Add('|---|---|---:|---:|---:|---:|---:|---:|---:|---:|')
foreach ($row in ($phaseSummaries | Sort-Object phase, processName)) {
    $markdown.Add("| $($row.processName) | $($row.phase) | $($row.samples) | $(Format-MiB $row.anonRssSwapPeakKb) | $(Format-MiB $row.fileSharedRssPeakKb) | $(Format-MiB $row.pssPeakKb) | $(Format-MiB $row.nativeHeapAllocatedPeakKb) / $(Format-MiB $row.nativeHeapPssPeakKb) | $(Format-MiB $row.graphicsPssPeakKb) / $(Format-MiB $row.graphicsRssPeakKb) | $(Format-MiB $row.bitmapPeakKb) | $(Format-MiB $row.dmaBufRssPeakKb) / $(Format-MiB $row.dmaBufPssPeakKb) |")
}
$markdown.Add('')
$markdown.Add('## Coverage and interpretation')
$markdown.Add('')
$markdown.Add("- Exact service `RssAnon + VmSwap`: $exactPlayAvailable")
$markdown.Add("- Process-wide bitmap native-allocation counters: $bitmapAvailable")
$markdown.Add("- Per-process `dmabuf_dump`: $dmaAvailable")
$markdown.Add("- The local time-series P90 is a run diagnostic, not Play's field P90 over opted-in devices and app states.")
$markdown.Add('- Do not add graphics, HardwareBuffer, and DMA-BUF columns; they may overlap. Inspect them side by side.')
$markdown.Add('- The package aggregate is diagnostic only because public Play documentation does not define multi-process compliance aggregation.')
$markdown | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.md') -Encoding utf8

Write-Host ''
Write-Host "Samples: $samplesPath" -ForegroundColor Green
Write-Host "Summary: $(Join-Path $OutputDirectory 'summary.md')" -ForegroundColor Green
if (-not $bitmapAvailable) {
    Write-Warning 'This build did not expose Native Allocations bitmap counters in dumpsys meminfo -a. The workload-owned bitmap cross-check was captured, but process-wide Play bitmap parity is incomplete.'
}
if (-not $dmaAvailable) {
    Write-Warning 'dmabuf_dump did not expose a per-process PROCESS TOTAL. Raw output was retained and missing DMA-BUF is not treated as zero.'
}

if (-not $exactPlayAvailable) {
    throw 'Benchmark invalid: no exact RssAnon + VmSwap sample was captured for the :ml service process.'
}
if ($RequireDmaBuf -and -not $dmaAvailable) {
    throw 'Benchmark incomplete: -RequireDmaBuf was set but the device did not expose per-process dmabuf_dump output.'
}
if (-not $instrumentPassed) {
    throw "Benchmark workload did not complete successfully. See $(Join-Path $OutputDirectory 'instrumentation.txt')."
}
if (-not $backendIdentityPassed) {
    $observed = @($backendObservations | ForEach-Object { "$($_.phase)=$($_.backend)" }) -join ', '
    $missing = $missingBackendPhases -join ', '
    throw "Benchmark invalid: requested backend $RequestedBackend was not observed for every required prewarm phase. Observed=[$observed] missing=[$missing]. Pass -AllowBackendFallback only for an intentional fallback run."
}
if (-not $contextResizeTransitionPassed) {
    throw 'Benchmark invalid: the expected default-to-max context :ml process replacement was not observed.'
}
if (-not $lifecycleTransitionPassed) {
    throw 'Benchmark invalid: Android evidence did not show TOP -> FGS -> background -> cached in order.'
}
if (-not $idleServiceExitObserved) {
    throw 'Benchmark invalid: the isolated :ml process did not exit after the final hidden idle unbind.'
}

Write-Host 'Benchmark completed successfully.' -ForegroundColor Green
