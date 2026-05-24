<#
.SYNOPSIS
    Dev-only sideload of Mindlayer on-device AI models to a connected
    debuggable Android device via adb.

.DESCRIPTION
    Pushes one or more model files from a local cache directory into
    /data/local/tmp/ on a connected device. The runtime registries
    (ModelRegistry / EmbeddingModelRegistry / PaddleOcrModelRegistry)
    scan that directory on debuggable builds and load whatever they
    find, bypassing the multi-GB AI Asset Pack install path.

    This script does NOT download anything. It is a pure local-file +
    adb operation. See docs/DEV_MODELS.md for where to source models.

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
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Constants — must stay in sync with the registries under
# app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/.
# ---------------------------------------------------------------------------
$RemoteDir = '/data/local/tmp'

$GemmaFile = 'gemma-4-E2B-it.litertlm'
$GemmaManifest = 'gemma_model/src/main/assets/model_integrity.json'

$EmbeddingFiles = @(
    'embedding-gemma-300m-v1.tflite',
    'embedding-gemma-300m-v1.spm.model'
)
$EmbeddingManifest = 'embeddinggemma_model/src/main/assets/embedding_model_integrity.json'

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
    $debuggable = (Invoke-AdbCapture -AdbArgs @('shell', 'getprop', 'ro.debuggable')).Output.Trim()
    $buildType  = (Invoke-AdbCapture -AdbArgs @('shell', 'getprop', 'ro.build.type')).Output.Trim()
    if ($debuggable -ne '1' -and $buildType -notin @('userdebug', 'eng')) {
        throw "/data/local/tmp sideload requires a debuggable build/device. " +
              "Got ro.debuggable='$debuggable' ro.build.type='$buildType'. " +
              "Release ('user') builds also gate sideload via BuildConfig.DEBUG."
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
    $remote = "$RemoteDir/$Filename"
    $localSize = (Get-Item -LiteralPath $LocalPath).Length
    $argsPush = @('push', $LocalPath, $remote)
    $argsLs   = @('shell', 'ls', '-l', $remote)

    if ($DryRun) {
        Write-Host "  [dry-run] adb $((Get-AdbArgs) + $argsPush -join ' ')"
        Write-Host "  [dry-run] adb $((Get-AdbArgs) + $argsLs   -join ' ')"
        return $true
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
            $msg = "$Label : missing '$f' in cache '$CacheDir'. See docs/DEV_MODELS.md#sources."
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
