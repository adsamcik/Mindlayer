<#
.SYNOPSIS
    Dev-only sideload of Mindlayer on-device AI models to a connected
    debuggable Android device via adb.

.DESCRIPTION
    Pushes one or more model files from a local cache directory into the
    Mindlayer service's externalFilesDir on a connected device
    (``/sdcard/Android/data/<pkg>/files``). The runtime registries
    (ModelRegistry / EmbeddingModelRegistry / PaddleOcrModelRegistry)
    scan that directory on debuggable builds and load whatever they
    find, bypassing the multi-GB AI Asset Pack install path.

    The script detects whether ``com.adsamcik.mindlayer.service.debug``
    or ``com.adsamcik.mindlayer.service`` is installed and uses the
    matching externalFilesDir. If neither is installed yet, it falls
    back to ``/data/local/tmp/`` with a loud warning — that path used
    to work historically but apps cannot list it on Android 12+ (API
    31+), so push-before-install only works on older devices.

    This script does NOT download anything. It is a pure local-file +
    adb operation. See docs/models/DEV_MODELS.md for where to source models.

.PARAMETER Gemma
    Push the chat model (Gemma 4 E2B .litertlm).

.PARAMETER Embeddings
    Push the EmbeddingGemma weights + tokenizer pair.

.PARAMETER Paddleocr
    Push the four PaddleOCR PP-OCRv5 mobile files.

.PARAMETER All
    Equivalent to -Gemma -Embeddings -Paddleocr.

.PARAMETER Cache
    Local cache directory holding the model files (flat layout).
    Defaults to $env:MINDLAYER_MODEL_CACHE if set.

.PARAMETER Device
    Optional adb device serial (passed as 'adb -s <serial>') when
    multiple devices are connected.

.PARAMETER DryRun
    Print what would be pushed without actually invoking 'adb push'.
    In dry-run the script assumes the debug service package is
    installed (most common dev case) and prints the corresponding
    externalFilesDir target. Combine with -PreferLegacyTmp to preview
    the legacy ``/data/local/tmp`` path.

.PARAMETER PreferLegacyTmp
    Force the legacy ``/data/local/tmp`` push target regardless of
    whether the service is installed. Useful for testing the fallback
    branch and for old API-30-or-earlier devices where /data/local/tmp
    listing still works.

.PARAMETER Force
    Skip the "remote already has a file of the same size" optimization
    and push every file unconditionally. Useful when a model file has
    been re-built locally with the same name + size but different
    content (rare — model versions normally bump filenames). Without
    this flag the script `adb shell stat -c %s`-checks the remote and
    skips the multi-GB push when sizes match.

.EXAMPLE
    .\push-models.ps1 -All -Cache D:\mindlayer-models

.EXAMPLE
    .\push-models.ps1 -Gemma -Embeddings -DryRun
#>
[CmdletBinding()]
param(
    [switch]$Gemma,
    [switch]$Embeddings,
    [switch]$Paddleocr,
    [switch]$All,
    [string]$Cache,
    [string]$Device,
    [switch]$DryRun,
    [switch]$PreferLegacyTmp,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Constants — must stay in sync with the registries under
# app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/.
# ---------------------------------------------------------------------------
$LegacyRemoteDir = '/data/local/tmp'
$ServicePkgRelease = 'com.adsamcik.mindlayer.service'
$ServicePkgDebug = 'com.adsamcik.mindlayer.service.debug'
# Resolved at runtime via Resolve-RemoteDir; seeded with the legacy path
# so $script:RemoteDir always has a value even before resolution runs.
$script:RemoteDir = $LegacyRemoteDir
$script:UsingLegacyRemoteDir = $true

$GemmaFile = 'gemma-4-E2B-it.litertlm'
$GemmaManifest = 'gemma_model/src/main/assets/model_integrity.json'

$EmbeddingFiles = @(
    'embedding-gemma-300m-v1.tflite',
    'embedding-gemma-300m-v1.spm.model'
)
$EmbeddingManifest = 'gemma_embed_model/src/main/assets/embedding_model_integrity.json'

$PaddleFiles = @(
    'paddleocr-ppocrv5-mobile-det.tflite',
    'paddleocr-ppocrv5-mobile-rec.tflite',
    'paddleocr-ppocrv5-mobile-cls.tflite',
    'paddleocr-ppocrv5-mobile-dict.txt'
)
$PaddleManifest = 'paddleocr_model/src/main/assets/paddleocr_model_integrity.json'

$ZeroSha = '0000000000000000000000000000000000000000000000000000000000000000'

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Resolve-RepoRoot {
    # Script lives at tools/dev-models/push-models.ps1, repo root is two up.
    $here = Split-Path -Parent $PSCommandPath
    return (Resolve-Path (Join-Path $here '..\..')).Path
}

function Get-AdbArgs {
    if ([string]::IsNullOrWhiteSpace($Device)) { return @() }
    return @('-s', $Device)
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$AdbArgs)
    $full = (Get-AdbArgs) + $AdbArgs
    & adb @full
    return $LASTEXITCODE
}

function Invoke-AdbCapture {
    param([Parameter(Mandatory)][string[]]$AdbArgs)
    $full = (Get-AdbArgs) + $AdbArgs
    $out = & adb @full 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out.TrimEnd() }
}

function Assert-AdbAvailable {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "'adb' not found on PATH. Install Android platform-tools and retry."
    }
}

function Assert-DeviceConnected {
    $r = Invoke-AdbCapture -AdbArgs @('devices')
    if ($r.ExitCode -ne 0) {
        throw "adb devices failed: $($r.Output)"
    }
    # Lines after the header: "<serial>\t<state>"
    $lines = $r.Output -split "`r?`n" |
        Where-Object { $_ -and $_ -notmatch '^List of devices' }
    $devices = @()
    foreach ($l in $lines) {
        $parts = $l -split "`t"
        if ($parts.Count -ge 2 -and $parts[1].Trim() -eq 'device') {
            $devices += $parts[0].Trim()
        }
    }
    if ($devices.Count -eq 0) {
        throw 'No adb devices in state ''device''. Connect a device and re-run.'
    }
    if ($devices.Count -gt 1 -and [string]::IsNullOrWhiteSpace($Device)) {
        throw "Multiple devices connected ($($devices -join ', ')). Pass -Device <serial>."
    }
    if (-not [string]::IsNullOrWhiteSpace($Device) -and ($devices -notcontains $Device)) {
        throw "Device '$Device' not found in: $($devices -join ', ')"
    }
}

function Assert-DebuggableDevice {
    <#
        Sideload is only safe when the receiving service trusts the
        push target. There are two equivalent ways to express that
        trust:

         1. The DEVICE is debuggable
            (`ro.debuggable=1` or `ro.build.type in {userdebug, eng}`).
            On such a device, every installed app — even release
            builds — is `Debug.isDebuggable() == true`, so the
            runtime registries' `BuildConfig.DEBUG` gate would pass.

         2. The MINDLAYER SERVICE installed is the debug variant
            (`com.adsamcik.mindlayer.service.debug`). The `.debug`
            package suffix is only produced by Gradle's `debug`
            buildType, which sets `BuildConfig.DEBUG = true`. Whether
            the device itself is a Play Store user-build is
            irrelevant — the runtime gate is satisfied because the
            APK was built debug.

        Either condition is sufficient. The strict device-only check
        used to break the dev workflow on Play Store emulators (very
        common, since AOSP-only emulator images lack Play Services that
        Mindlayer needs for some flows).

        Release builds with the `.service` (no `.debug`) package
        installed on a non-debuggable device are still rejected
        because the runtime gate would refuse to load sideloaded
        models.
    #>
    $debuggable = (Invoke-AdbCapture -AdbArgs @('shell', 'getprop', 'ro.debuggable')).Output.Trim()
    $buildType  = (Invoke-AdbCapture -AdbArgs @('shell', 'getprop', 'ro.build.type')).Output.Trim()
    if ($debuggable -eq '1' -or $buildType -in @('userdebug', 'eng')) {
        return
    }
    # Device-level guard failed; fall back to package-level evidence.
    $r = Invoke-AdbCapture -AdbArgs @('shell', 'pm', 'list', 'packages', $ServicePkgDebug)
    if ($r.ExitCode -eq 0) {
        $lines = $r.Output -split "`r?`n" | ForEach-Object { $_.Trim() }
        if ($lines -contains "package:$ServicePkgDebug") {
            Write-Host (
                "Device is ro.debuggable='$debuggable' ro.build.type='$buildType' " +
                "(non-debuggable). Continuing anyway because the debug variant " +
                "'$ServicePkgDebug' is installed and its runtime BuildConfig.DEBUG " +
                "gate is the authoritative check."
            ) -ForegroundColor Yellow
            return
        }
    }
    throw "Mindlayer sideload requires either a debuggable device " +
          "(ro.debuggable=1 or ro.build.type in {userdebug, eng}) OR the " +
          "debug variant '$ServicePkgDebug' to be installed. Got " +
          "ro.debuggable='$debuggable' ro.build.type='$buildType' " +
          "and the debug service package is NOT installed. " +
          "Install the debug build of :app first (./gradlew :app:installDebug), " +
          "or run on a debuggable device."
}

function Resolve-RemoteDir {
    <#
        Resolve the on-device push target:

        1. -PreferLegacyTmp → /data/local/tmp/ (no device query).
        2. -DryRun without -PreferLegacyTmp → assume the debug variant
           is installed (most common dev case) and use its externalFilesDir.
        3. Otherwise: query 'pm list packages' for the debug variant
           first, then release. First match wins. If neither is found,
           fall back to /data/local/tmp/ with a loud warning.

        Returns a [pscustomobject] with: Dir, UsingLegacy, Pkg.
    #>
    if ($PreferLegacyTmp) {
        return [pscustomobject]@{
            Dir = $LegacyRemoteDir; UsingLegacy = $true; Pkg = $null
        }
    }
    if ($DryRun) {
        return [pscustomobject]@{
            Dir = "/sdcard/Android/data/$ServicePkgDebug/files"
            UsingLegacy = $false
            Pkg = $ServicePkgDebug
        }
    }
    foreach ($pkg in @($ServicePkgDebug, $ServicePkgRelease)) {
        $r = Invoke-AdbCapture -AdbArgs @('shell', 'pm', 'list', 'packages', $pkg)
        if ($r.ExitCode -ne 0) { continue }
        $lines = $r.Output -split "`r?`n" | ForEach-Object { $_.Trim() }
        if ($lines -contains "package:$pkg") {
            return [pscustomobject]@{
                Dir = "/sdcard/Android/data/$pkg/files"
                UsingLegacy = $false
                Pkg = $pkg
            }
        }
    }
    Write-Warning ("Mindlayer service not installed on device " +
        "($ServicePkgDebug / $ServicePkgRelease). Falling back to " +
        "$LegacyRemoteDir — this MAY FAIL on Android 12+ (API 31+) because " +
        "apps can no longer list /data/local/tmp/ even when files inside " +
        "are world-readable. Install a debug build of :app first, then re-run.")
    return [pscustomobject]@{
        Dir = $LegacyRemoteDir; UsingLegacy = $true; Pkg = $null
    }
}

function Initialize-RemoteDir {
    <#
        Ensure $script:RemoteDir exists on the device. externalFilesDir
        is normally created by the app on first launch; a freshly-
        installed APK may not have run yet, so mkdir -p is cheap and
        safe. Skipped in dry-run and for the legacy /data/local/tmp
        path (which always exists).
    #>
    if ($DryRun -or $script:UsingLegacyRemoteDir) { return }
    $r = Invoke-AdbCapture -AdbArgs @('shell', 'mkdir', '-p', $script:RemoteDir)
    if ($r.ExitCode -ne 0) {
        throw "Failed to create remote dir $($script:RemoteDir): $($r.Output)"
    }
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}

function Read-ManifestSha {
    <#
        Returns a hashtable: filename -> sha256 (lowercase).
        Supports both manifest schemas (single modelFile/sha256, or models[]).
    #>
    param([Parameter(Mandatory)][string]$ManifestPath)
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        Write-Warning "Manifest not found: $ManifestPath (skipping verification)."
        return @{}
    }
    $json = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
    $map = @{}
    if ($json.PSObject.Properties.Name -contains 'modelFile') {
        $map[[string]$json.modelFile] = ([string]$json.sha256).ToLowerInvariant()
    }
    if ($json.PSObject.Properties.Name -contains 'models') {
        foreach ($m in $json.models) {
            $map[[string]$m.filename] = ([string]$m.sha256).ToLowerInvariant()
        }
    }
    return $map
}

function Test-ShaAdvisory {
    <#
        Returns $true if the file is OK to push, $false if a populated
        manifest SHA mismatched the local file. Placeholder zero SHAs are
        treated as "not populated" and skipped with a notice.
    #>
    param(
        [Parameter(Mandatory)][string]$LocalPath,
        [Parameter(Mandatory)][string]$Filename,
        [Parameter(Mandatory)][hashtable]$ManifestMap
    )
    if (-not $ManifestMap.ContainsKey($Filename)) {
        Write-Host "  sha: no manifest entry for $Filename — skipping verification."
        return $true
    }
    $expected = $ManifestMap[$Filename]
    if ($expected -eq $ZeroSha -or [string]::IsNullOrWhiteSpace($expected)) {
        Write-Host "  sha: manifest SHA not populated (dev placeholder), skipping verification."
        return $true
    }
    $actual = Get-FileSha256 -Path $LocalPath
    if ($actual -ne $expected) {
        Write-Error "  sha MISMATCH for $Filename`n    expected: $expected`n    actual:   $actual"
        return $false
    }
    Write-Host "  sha: OK ($actual)"
    return $true
}

function Push-OneFile {
    param(
        [Parameter(Mandatory)][string]$LocalPath,
        [Parameter(Mandatory)][string]$Filename
    )
    $remote = "$($script:RemoteDir)/$Filename"
    $localSize = (Get-Item -LiteralPath $LocalPath).Length

    # Guard: refuse a stale / mis-converted PaddleOCR model that carries an
    # unresolved ONNX_LAYERNORMALIZATION custom op. Mirrors the build-time
    # guard in scripts/build-paddleocr-models/convert.sh: such a rec model
    # fails to invoke on every accelerator, so OCR silently returns 0 lines.
    # PaddleOCR .tflite files are <=16 MB, so a full read is cheap.
    if ($Filename -like 'paddleocr-*.tflite') {
        $bytes = [System.IO.File]::ReadAllBytes($LocalPath)
        if ([System.Text.Encoding]::ASCII.GetString($bytes).Contains('ONNX_LAYERNORMALIZATION')) {
            throw ("Refusing to push '$Filename': it contains an unresolved " +
                "ONNX_LAYERNORMALIZATION custom op (stale or mis-converted model that " +
                "fails to invoke on every accelerator). Rebuild via " +
                "scripts/build-paddleocr-models (or download the latest CI artifact) and " +
                "refresh your model cache before pushing.")
        }
    }

    $argsPush = @('push', $LocalPath, $remote)
    $argsLs   = @('shell', 'ls', '-l', $remote)
    $argsStat = @('shell', 'stat', '-c', '%s', $remote)

    if ($DryRun) {
        Write-Host "  [dry-run] adb $((Get-AdbArgs) + $argsPush -join ' ')"
        Write-Host "  [dry-run] adb $((Get-AdbArgs) + $argsLs   -join ' ')"
        return $true
    }

    # Skip already-pushed files whose size matches the local cache.
    # `adb push` is roughly 80 MB/s on a good USB-3 link — pushing a
    # 2.4 GB Gemma model is 30+ seconds you can avoid every iteration.
    # `stat -c %s` is the Android toybox stat invocation; missing-file
    # exits non-zero, which we treat as "needs pushing".
    if (-not $Force) {
        $s = Invoke-AdbCapture -AdbArgs $argsStat
        if ($s.ExitCode -eq 0) {
            $remoteSize = $null
            $stdout = $s.Output.Trim()
            if ($stdout -match '^\d+$') {
                $remoteSize = [int64]$stdout
            }
            if ($null -ne $remoteSize -and $remoteSize -eq $localSize) {
                Write-Host "  skip: already on device with matching size ($remoteSize bytes). Use -Force to override."
                return $true
            }
            if ($null -ne $remoteSize) {
                Write-Host "  size differs (remote=$remoteSize, local=$localSize); re-pushing."
            }
        }
    }

    Write-Host "  pushing $Filename ($localSize bytes) -> $remote"
    $code = Invoke-Adb -AdbArgs $argsPush
    if ($code -ne 0) {
        Write-Error "  adb push failed (exit $code) for $Filename"
        return $false
    }
    $r = Invoke-AdbCapture -AdbArgs $argsLs
    if ($r.ExitCode -ne 0) {
        Write-Error "  adb shell ls failed for $remote : $($r.Output)"
        return $false
    }
    # ls -l output: "perms links owner group SIZE date time name"
    $tokens = ($r.Output -split '\s+') | Where-Object { $_ -ne '' }
    $remoteSize = $null
    if ($tokens.Count -ge 5) {
        # On Android toybox ls -l, size is at index 4. Be tolerant.
        for ($i = 0; $i -lt $tokens.Count; $i++) {
            if ($tokens[$i] -match '^\d+$' -and [int64]$tokens[$i] -eq $localSize) {
                $remoteSize = [int64]$tokens[$i]; break
            }
        }
    }
    if ($null -eq $remoteSize) {
        Write-Warning "  could not parse remote size from: $($r.Output)"
        return $true   # push succeeded; size check best-effort
    }
    Write-Host "  verified size $remoteSize bytes on device."
    return $true
}

# ---------------------------------------------------------------------------
# Group processor
# ---------------------------------------------------------------------------
function Invoke-Group {
    param(
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string[]]$Files,
        [Parameter(Mandatory)][string]$ManifestRelPath,
        [Parameter(Mandatory)][string]$CacheDir,
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][AllowEmptyCollection()][System.Collections.Generic.List[string]]$Failures
    )
    Write-Host ""
    Write-Host "=== $Label ==="
    $manifestPath = Join-Path $RepoRoot $ManifestRelPath
    $manifest = Read-ManifestSha -ManifestPath $manifestPath

    foreach ($f in $Files) {
        $local = Join-Path $CacheDir $f
        if (-not (Test-Path -LiteralPath $local)) {
            $msg = "$Label : missing '$f' in cache '$CacheDir'. See docs/models/DEV_MODELS.md#sources."
            Write-Warning $msg
            $Failures.Add($msg) | Out-Null
            continue
        }
        Write-Host "- $f"
        if (-not (Test-ShaAdvisory -LocalPath $local -Filename $f -ManifestMap $manifest)) {
            $Failures.Add("$Label : SHA mismatch for $f") | Out-Null
            continue
        }
        if (-not (Push-OneFile -LocalPath $local -Filename $f)) {
            $Failures.Add("$Label : push failed for $f") | Out-Null
        }
    }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if ($All) { $Gemma = $true; $Embeddings = $true; $Paddleocr = $true }

if (-not ($Gemma -or $Embeddings -or $Paddleocr)) {
    throw 'Specify at least one of -Gemma, -Embeddings, -Paddleocr, or -All.'
}

if ([string]::IsNullOrWhiteSpace($Cache)) {
    $Cache = $env:MINDLAYER_MODEL_CACHE
}
if ([string]::IsNullOrWhiteSpace($Cache)) {
    throw 'No cache directory. Pass -Cache <path> or set $env:MINDLAYER_MODEL_CACHE.'
}
if (-not (Test-Path -LiteralPath $Cache -PathType Container)) {
    throw "Cache directory does not exist: $Cache"
}
$Cache = (Resolve-Path -LiteralPath $Cache).Path

$repoRoot = Resolve-RepoRoot
Write-Host "Mindlayer dev model sideload"
Write-Host "  repo:   $repoRoot"
Write-Host "  cache:  $Cache"
Write-Host "  device: $(if ($Device) { $Device } else { '(auto)' })"
Write-Host "  dryRun: $DryRun"

if (-not $DryRun) {
    Assert-AdbAvailable
    Assert-DeviceConnected
    Assert-DebuggableDevice
} else {
    Write-Host '  (skipping adb/device checks in dry-run mode)'
}

$resolved = Resolve-RemoteDir
$script:RemoteDir = $resolved.Dir
$script:UsingLegacyRemoteDir = $resolved.UsingLegacy
$remoteLabel = if ($resolved.UsingLegacy) {
    "$($resolved.Dir)  (LEGACY — /data/local/tmp is unlistable on API 31+)"
} else {
    "$($resolved.Dir)  (service pkg: $($resolved.Pkg))"
}
Write-Host "  remote: $remoteLabel"

Initialize-RemoteDir

$failures = New-Object 'System.Collections.Generic.List[string]'

if ($Gemma) {
    Invoke-Group -Label 'Gemma chat' -Files @($GemmaFile) `
        -ManifestRelPath $GemmaManifest -CacheDir $Cache -RepoRoot $repoRoot `
        -Failures $failures
}
if ($Embeddings) {
    Invoke-Group -Label 'EmbeddingGemma' -Files $EmbeddingFiles `
        -ManifestRelPath $EmbeddingManifest -CacheDir $Cache -RepoRoot $repoRoot `
        -Failures $failures
}
if ($Paddleocr) {
    Invoke-Group -Label 'PaddleOCR PP-OCRv5 mobile' -Files $PaddleFiles `
        -ManifestRelPath $PaddleManifest -CacheDir $Cache -RepoRoot $repoRoot `
        -Failures $failures
}

Write-Host ''
if ($failures.Count -eq 0) {
    Write-Host "Done. All requested files processed cleanly$(if ($DryRun) { ' (dry-run)' })."
    exit 0
} else {
    Write-Host "Done with $($failures.Count) failure(s):"
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}

