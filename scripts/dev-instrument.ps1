<#
.SYNOPSIS
    Run Mindlayer instrumented (androidTest) tests on a dev device/emulator
    WITHOUT wiping the sideloaded AI models.

.DESCRIPTION
    `./gradlew :app:connectedDebugAndroidTest` (and Android Studio's "Run"
    gutter) drive AGP's install -> test -> **uninstall** cycle. That final
    uninstall deletes the service app's `externalFilesDir`, taking the ~3 GB of
    pushed Gemma / EmbeddingGemma / PaddleOCR models with it — so the next run
    fails with `MLERR:1003:Model file missing` until you re-push (minutes over a
    qemu pipe). CI is the only safe place for `connectedAndroidTest` because CI
    AVDs are throw-away.

    This script runs the same instrumented tests the model-preserving way:

      1. Build the code-only app APK (`-Pmindlayer.bundle*=false`, so models are
         expected to already be on the device) and the androidTest APK.
      2. `adb install -r` both APKs. `-r` reinstalls in place and PRESERVES
         `externalFilesDir`; it never uninstalls.
      3. `adb shell am instrument -w` the test package directly, optionally
         filtered to a class or package.

    It NEVER calls `adb uninstall` or `pm clear`, so the models survive every
    run. Mirrors `scripts/dev-install.ps1` in spirit (build code-only, install
    -r, leave models alone).

    For the `app` module the instrumentation target is the service app, so the
    app APK + test APK are installed. The `sdk` module's instrumented tests are
    self-instrumenting (their own test app) and never touch the service app's
    data, so only the sdk test APK is installed.

    Requires:
      - `adb` on PATH (Android platform-tools)
      - JDK 21 on PATH for `./gradlew` (see `.github/context/DEVELOPMENT.md`)
      - The AI models already pushed to the device (use `scripts/dev-install.ps1`
        or `tools/dev-models/push-models.ps1`) for model-dependent tests; tests
        that need a real model `assumeTrue`-skip when it is absent.

.PARAMETER Module
    Which module's instrumented tests to run: `app` (default) or `sdk`.

.PARAMETER Class
    Fully-qualified test class (or comma-separated list, or `Class#method`) to
    run. Forwarded as `am instrument -e class <value>`. Omit to run the whole
    module suite.

.PARAMETER Package
    Test package prefix to run, e.g. `com.adsamcik.mindlayer.service.engine`.
    Forwarded as `am instrument -e package <value>`. Mutually exclusive with
    -Class.

.PARAMETER Device
    Optional adb device serial when multiple devices are connected.

.PARAMETER SkipBuild
    Reuse the existing APKs instead of rebuilding.

.PARAMETER DryRun
    Print the exact build / adb commands without running them.

.EXAMPLE
    # Run one fast, model-independent class to prove the loop preserves models.
    .\scripts\dev-instrument.ps1 -Class com.adsamcik.mindlayer.service.security.DbKeyProviderTest

.EXAMPLE
    # Run the whole app instrumented suite against a specific emulator.
    .\scripts\dev-instrument.ps1 -Device emulator-5554

.EXAMPLE
    # Run the SDK's instrumented tests (self-instrumenting; model-safe anyway).
    .\scripts\dev-instrument.ps1 -Module sdk
#>
[CmdletBinding()]
param(
    [ValidateSet('app', 'sdk')]
    [string]$Module = 'app',
    [string]$Class,
    [string]$Package,
    [string]$Device,
    [switch]$SkipBuild,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Class -and $Package) {
    throw 'Pass at most one of -Class / -Package.'
}

$scriptDir = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path

# ---------------------------------------------------------------------------
# Module-specific layout
# ---------------------------------------------------------------------------
if ($Module -eq 'app') {
    $instrumentationPkg = 'com.adsamcik.mindlayer.service.debug.test'
    $appApk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    $buildTasks = @(
        ':app:assembleDebug',
        ':app:assembleDebugAndroidTest',
        '-Pmindlayer.bundleGemma=false',
        '-Pmindlayer.bundleEmbeddings=false',
        '-Pmindlayer.bundlePaddleocr=false'
    )
    $apksToInstall = @($appApk, $testApk)
} else {
    $instrumentationPkg = 'com.adsamcik.mindlayer.sdk.test'
    $testApk = Join-Path $repoRoot 'sdk\build\outputs\apk\androidTest\debug\sdk-debug-androidTest.apk'
    $buildTasks = @(':sdk:assembleDebugAndroidTest')
    $apksToInstall = @($testApk)
}
$runner = "$instrumentationPkg/androidx.test.runner.AndroidJUnitRunner"

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$AdbArgs)
    $full = @()
    if (-not [string]::IsNullOrWhiteSpace($Device)) { $full += @('-s', $Device) }
    $full += $AdbArgs
    if ($DryRun) {
        Write-Host "[dry-run] adb $($full -join ' ')" -ForegroundColor Cyan
        return [pscustomobject]@{ ExitCode = 0; Output = '' }
    }
    $out = & adb @full 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out.TrimEnd() }
}

# ---------------------------------------------------------------------------
# Phase 1 — build (code-only app APK + the androidTest APK)
# ---------------------------------------------------------------------------
if ($SkipBuild) {
    Write-Host '[skip-build] reusing existing APK(s)' -ForegroundColor Yellow
} else {
    Write-Host "== Building $Module instrumented-test APK(s) (code-only; models stay on device) ==" -ForegroundColor Cyan
    $gradle = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
    if ($DryRun) {
        Write-Host "[dry-run] $gradle $($buildTasks -join ' ') --console=plain" -ForegroundColor Cyan
    } else {
        Push-Location $repoRoot
        try {
            & $gradle @buildTasks '--console=plain'
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }
        } finally {
            Pop-Location
        }
    }
}

foreach ($apk in $apksToInstall) {
    if (-not $DryRun -and -not (Test-Path -LiteralPath $apk)) {
        throw "APK not found at $apk — run without -SkipBuild to build it."
    }
}

# ---------------------------------------------------------------------------
# Phase 2 — install (adb install -r preserves externalFilesDir; NEVER uninstall)
# ---------------------------------------------------------------------------
Write-Host '== Installing APK(s) with adb install -r (models preserved) ==' -ForegroundColor Cyan
foreach ($apk in $apksToInstall) {
    $r = Invoke-Adb -AdbArgs @('install', '-r', '-t', $apk)
    if ($r.ExitCode -ne 0) {
        throw "adb install failed for $apk (exit $($r.ExitCode)): $($r.Output)"
    }
    if ($r.Output) { Write-Host "  $($r.Output -split "`r?`n" | Select-Object -Last 1)" }
}

# ---------------------------------------------------------------------------
# Phase 3 — instrument (no uninstall, ever)
# ---------------------------------------------------------------------------
$instrumentArgs = @('shell', 'am', 'instrument', '-w')
if ($Class) { $instrumentArgs += @('-e', 'class', $Class) }
elseif ($Package) { $instrumentArgs += @('-e', 'package', $Package) }
$instrumentArgs += $runner

Write-Host "== Running instrumented tests: $runner ==" -ForegroundColor Cyan
$res = Invoke-Adb -AdbArgs $instrumentArgs
Write-Host $res.Output

Write-Host ''
Write-Host 'Done — externalFilesDir (and the ~3 GB of models) was never touched.' -ForegroundColor Green
Write-Host 'This script never runs ''adb uninstall'' / ''pm clear''. Do NOT use' -ForegroundColor DarkGray
Write-Host '''connectedDebugAndroidTest'' on a model-loaded device — it uninstalls and wipes them.' -ForegroundColor DarkGray

if ($DryRun) { exit 0 }

# `am instrument` exits 0 even when tests fail — the verdict is in the text.
$out = $res.Output
if ($out -match 'FAILURES!!!' -or $out -match 'INSTRUMENTATION_CODE: -1' -or
    $out -match 'Process crashed' -or $out -match '^Error:' -or
    $out -match 'INSTRUMENTATION_RESULT: shortMsg') {
    Write-Error 'Instrumented tests reported failures (see output above).'
    exit 1
}
if ($out -notmatch 'OK \(\d+ test') {
    Write-Error 'Could not confirm a passing run (no "OK (N tests)" marker). Treating as failure.'
    exit 1
}
Write-Host 'Instrumented tests passed.' -ForegroundColor Green
