<#
.SYNOPSIS
    One-shot dev install loop for the Mindlayer service APK + on-device AI models.

.DESCRIPTION
    Combines the three steps a dev hits every time they iterate on the
    service while still depending on the (multi-GB) Gemma/EmbeddingGemma/
    PaddleOCR models:

      1. Build the debug APK **without** bundling the AI Asset Packs
         (`-Pmindlayer.bundle{Gemma,Embeddings,Paddleocr}=false`). This
         keeps the build under ~30 s and the resulting APK under ~80 MB.
      2. `adb install -r` the freshly-built APK. `-r` preserves the
         app's `externalFilesDir`, so models pushed earlier survive a
         code-only reinstall.
      3. Run ``tools/dev-models/push-models.ps1`` for any model group
         the runtime reports as missing on the device. Already-present
         files with a size match are skipped.

    The script is the canonical "just put it on my device" entry
    point. Use this instead of plain ``adb install -r app-debug.apk``
    (which silently leaves the device without models) or
    ``adb uninstall`` (which **wipes** the pushed models because
    uninstall deletes externalFilesDir).

    Requires:
      - ``adb`` on PATH (Android platform-tools)
      - JDK 21 on PATH for ``./gradlew`` (see
        ``.github/context/DEVELOPMENT.md`` for the JDK 21 gotcha)
      - A model cache directory either passed via ``-Cache`` or set
        in ``$env:MINDLAYER_MODEL_CACHE`` (see ``docs/models/DEV_MODELS.md``
        for what to put in it)

.PARAMETER Cache
    Local cache directory holding the model files. Forwarded to
    ``push-models.ps1``. Defaults to ``$env:MINDLAYER_MODEL_CACHE``.

.PARAMETER Device
    Optional adb device serial when multiple devices are connected.

.PARAMETER SkipBuild
    Don't run the Gradle build. Useful when you just want to re-push
    a freshly-uninstalled APK's models, or when the APK is already
    built by another process.

.PARAMETER SkipInstall
    Don't run ``adb install``. Useful for ``-Force`` model pushes onto
    a device whose APK is already current.

.PARAMETER Force
    Pass through to ``push-models.ps1`` so each file is re-pushed
    even when a same-named, same-size file already exists on device.
    push-models normally skips files whose remote size matches the
    local cache size (set after this script's first run).

.PARAMETER DryRun
    Forward ``-DryRun`` to ``push-models.ps1`` and skip the Gradle
    build + adb install. Prints exactly what the real run would do.

.EXAMPLE
    .\scripts\dev-install.ps1 -Cache D:\mindlayer-models

.EXAMPLE
    # The APK is already installed but you blew away externalFilesDir
    # by uninstalling. Just re-push the models.
    .\scripts\dev-install.ps1 -SkipBuild -SkipInstall

.EXAMPLE
    # Same as the default loop, but explicitly target one of several
    # connected emulators.
    .\scripts\dev-install.ps1 -Device emulator-5554
#>
[CmdletBinding()]
param(
    [string]$Cache,
    [string]$Device,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$Force,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Layout
# ---------------------------------------------------------------------------
$scriptDir = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path
$pushScript = Join-Path $repoRoot 'tools\dev-models\push-models.ps1'
$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'

# ---------------------------------------------------------------------------
# Phase 1 — build (code-only APK, AI Packs excluded)
# ---------------------------------------------------------------------------
if ($SkipBuild -or $DryRun) {
    if ($DryRun) {
        Write-Host '[dry-run] would build :app:assembleDebug without AI packs' -ForegroundColor Cyan
    } else {
        Write-Host '[skip-build] reusing existing APK at' $apkPath -ForegroundColor Yellow
    }
} else {
    Write-Host '== Building code-only debug APK (no AI Asset Packs bundled) ==' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        $gradle = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
        & $gradle ':app:assembleDebug' `
            '-Pmindlayer.bundleGemma=false' `
            '-Pmindlayer.bundleEmbeddings=false' `
            '-Pmindlayer.bundlePaddleocr=false' `
            '--console=plain'
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed (exit $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }
}

if (-not $SkipInstall -and -not $DryRun -and -not (Test-Path -LiteralPath $apkPath)) {
    throw "APK not found at $apkPath — pass -SkipBuild only when one already exists."
}

# ---------------------------------------------------------------------------
# Phase 2 — install (preserves externalFilesDir; never `adb uninstall`)
# ---------------------------------------------------------------------------
function Invoke-AdbCapture {
    param([Parameter(Mandatory)][string[]]$AdbArgs)
    $full = @()
    if (-not [string]::IsNullOrWhiteSpace($Device)) { $full += @('-s', $Device) }
    $full += $AdbArgs
    $out = & adb @full 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out.TrimEnd() }
}

if ($SkipInstall -or $DryRun) {
    if ($DryRun) {
        Write-Host "[dry-run] would: adb install -r -t $apkPath" -ForegroundColor Cyan
        Write-Host "[dry-run] would: adb shell am start -W -n <pkg>/...MainActivity" -ForegroundColor Cyan
    } else {
        Write-Host '[skip-install] not running adb install' -ForegroundColor Yellow
    }
} else {
    Write-Host '== Installing APK (adb install -r preserves externalFilesDir) ==' -ForegroundColor Cyan
    $installArgs = @('install', '-r', '-t', $apkPath)
    $r = Invoke-AdbCapture -AdbArgs $installArgs
    if ($r.ExitCode -ne 0) {
        throw "adb install failed (exit $($r.ExitCode)): $($r.Output)"
    }
    Write-Host $r.Output

    # Launch the dashboard once so the OS creates the service's externalFilesDir
    # under the right UID. Without this, push-models writes to a FUSE phantom
    # directory that the app's own `context.getExternalFilesDir(null)` never
    # sees — pushes report success and the registries report `Discovered 0
    # bundles` because they're looking at a different inode.
    # Order of pkg probes mirrors ConnectionManager's debug-suffix fallback.
    Write-Host '== Launching dashboard once so the OS creates externalFilesDir ==' -ForegroundColor Cyan
    $launched = $false
    foreach ($pkg in @('com.adsamcik.mindlayer.debug', 'com.adsamcik.mindlayer')) {
        $check = Invoke-AdbCapture -AdbArgs @('shell', 'pm', 'list', 'packages', $pkg)
        if ($check.ExitCode -eq 0) {
            $lines = $check.Output -split "`r?`n" | ForEach-Object { $_.Trim() }
            if ($lines -contains "package:$pkg") {
                $launchArgs = @(
                    'shell', 'am', 'start', '-W',
                    '-n', "$pkg/com.adsamcik.mindlayer.service.ui.MainActivity"
                )
                $lr = Invoke-AdbCapture -AdbArgs $launchArgs
                if ($lr.ExitCode -eq 0) {
                    Write-Host "  launched $pkg/.MainActivity (externalFilesDir is now real)"
                    $launched = $true
                } else {
                    Write-Warning "  failed to launch $pkg`: $($lr.Output)"
                }
                break
            }
        }
    }
    if (-not $launched) {
        Write-Warning 'Mindlayer service was not detected on the device. push-models will try anyway but is likely to land in a FUSE phantom directory the app cannot read.'
    }
    # Brief settle so the externalFilesDir mkdir + permissions are fully
    # observable to the next `adb push`. 1.5s is more than enough on real
    # devices; emulators are sometimes slower.
    Start-Sleep -Milliseconds 1500
}

# ---------------------------------------------------------------------------
# Phase 3 — push models (no-op when already on device + sizes match)
# ---------------------------------------------------------------------------
Write-Host '== Pushing missing model files (skips already-present, size-matched files) ==' -ForegroundColor Cyan

if (-not (Test-Path -LiteralPath $pushScript)) {
    throw "Expected $pushScript to exist — did the repo layout change?"
}

$pushParams = @{ All = $true }
if (-not [string]::IsNullOrWhiteSpace($Cache)) { $pushParams.Cache = $Cache }
if (-not [string]::IsNullOrWhiteSpace($Device)) { $pushParams.Device = $Device }
if ($Force) { $pushParams.Force = $true }
if ($DryRun) { $pushParams.DryRun = $true }

Write-Verbose ("push-models invocation: " + (($pushParams.GetEnumerator() | ForEach-Object { "-$($_.Key) $($_.Value)" }) -join ' '))
& $pushScript @pushParams
if ($LASTEXITCODE -ne 0) {
    throw "push-models.ps1 failed (exit $LASTEXITCODE). See its output above for which model group failed."
}

Write-Host ''
Write-Host 'Dev install loop complete.' -ForegroundColor Green
Write-Host 'Reminder: NEVER use ''adb uninstall com.adsamcik.mindlayer.debug'' —'
Write-Host '          uninstall wipes externalFilesDir and you will lose ~3 GB of pushed models.'
Write-Host '          Use ''.\scripts\dev-install.ps1'' or ''adb install -r'' instead.'
