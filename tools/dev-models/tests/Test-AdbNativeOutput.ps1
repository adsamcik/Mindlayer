<#
.SYNOPSIS
    Exercises deployment helpers with a real native process emitting stderr.
.DESCRIPTION
    Windows PowerShell 5.1 regression check. Uses a temporary fake adb command;
    never contacts a device, installs an APK, or reads the real model cache.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$fixtureRoot = Join-Path $repoRoot ('build/adb-native-output-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$fakeAdb = Join-Path $fixtureRoot 'adb.cmd'
$nativeStub = @'
@echo off
if "%~1"=="push" goto push
if "%~2"=="stat" goto stat
if "%~2"=="ls" goto ls
echo simulated native command failure 1>&2
exit /b 9
:push
if "%MINDLAYER_TEST_PUSH_FAIL%"=="1" goto pushFailed
echo 1 file pushed.
echo transfer progress 1>&2
echo pushed>>"%MINDLAYER_TEST_PUSH_LOG%"
exit /b 0
:pushFailed
echo simulated push failure 1>&2
echo transfer could not complete 1>&2
exit /b 7
:stat
if "%MINDLAYER_TEST_REMOTE_PRESENT%"=="1" goto present
echo stat: tokenizer: No such file or directory 1>&2
exit /b 1
:present
echo 4
exit /b 0
:ls
echo -rw-rw---- 1 shell shell 4 2026-09-30 tokenizer
exit /b 0
'@
[IO.File]::WriteAllText($fakeAdb, $nativeStub, [Text.Encoding]::ASCII)
$environmentNames = @('Path', 'MINDLAYER_TEST_PUSH_LOG', 'MINDLAYER_TEST_REMOTE_PRESENT', 'MINDLAYER_TEST_PUSH_FAIL')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:Path = "$fixtureRoot;$env:Path"
    function Assert-Result([bool]$Condition, [string]$Message) {
        if (-not $Condition) { throw $Message }
    }
    function Import-Helper([string]$Source, [string]$Name) {
        $tokens = $null
        $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile($Source, [ref]$tokens, [ref]$parseErrors)
        Assert-Result ($parseErrors.Count -eq 0) "Cannot parse $Source"
        $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name }, $true)
        Assert-Result ($null -ne $definition) "Missing helper $Name"
        # Return the actual source function for evaluation in the test's scope.
        $definition.Extent.Text
    }

    $Device = ''
    $DryRun = $false
    $Force = $false
    $script:RemoteDir = '/sdcard/Android/data/com.adsamcik.mindlayer.debug/files'
    $pushSource = Join-Path $repoRoot 'tools/dev-models/push-models.ps1'
    foreach ($name in @('Get-AdbArgs', 'Invoke-AdbCapture', 'Invoke-Adb', 'Push-OneFile')) {
        Invoke-Expression (Import-Helper $pushSource $name)
    }
    $env:MINDLAYER_TEST_PUSH_LOG = Join-Path $fixtureRoot 'pushes.txt'
    $env:MINDLAYER_TEST_REMOTE_PRESENT = '0'
    $env:MINDLAYER_TEST_PUSH_FAIL = '0'
    $modelPath = Join-Path $fixtureRoot 'embedding-gemma-300m-v1.spm.model'
    [IO.File]::WriteAllText($modelPath, 'test')

    $missing = Invoke-AdbCapture @('shell', 'stat', '-c', '%s', 'tokenizer')
    Assert-Result ($missing.ExitCode -eq 1) 'Missing-file probe lost its native exit code'
    Assert-Result ($missing.Output.Contains('No such file or directory')) 'Missing-file stderr was lost'
    Assert-Result ($ErrorActionPreference -eq 'Stop') 'Native capture changed the caller error policy'
    Assert-Result (Push-OneFile -LocalPath $modelPath -Filename 'embedding-gemma-300m-v1.spm.model') 'Missing tokenizer did not deploy'
    Assert-Result (@(Get-Content -LiteralPath $env:MINDLAYER_TEST_PUSH_LOG).Count -eq 1) 'Missing tokenizer was not pushed exactly once'

    $env:MINDLAYER_TEST_REMOTE_PRESENT = '1'
    Assert-Result (Push-OneFile -LocalPath $modelPath -Filename 'embedding-gemma-300m-v1.spm.model') 'Existing tokenizer did not skip cleanly'
    Assert-Result (@(Get-Content -LiteralPath $env:MINDLAYER_TEST_PUSH_LOG).Count -eq 1) 'Size-matched tokenizer was pushed again'

    $env:MINDLAYER_TEST_PUSH_FAIL = '1'
    $failedPush = Invoke-Adb @('push', $modelPath, 'tokenizer')
    Assert-Result ($failedPush -is [int] -and $failedPush -eq 7) "Push output contaminated the numeric exit code: $($failedPush.GetType().FullName) = $failedPush"
    $env:MINDLAYER_TEST_REMOTE_PRESENT = '0'
    $failed = $false
    try { Push-OneFile -LocalPath $modelPath -Filename 'embedding-gemma-300m-v1.spm.model' | Out-Null } catch { $failed = $true }
    Assert-Result $failed 'A real push failure was treated as success'

    # The app-install wrapper has its own capture helper; verify it independently.
    Invoke-Expression (Import-Helper (Join-Path $repoRoot 'scripts/dev-install.ps1') 'Invoke-AdbCapture')
    $installFailure = Invoke-AdbCapture @('install', '-r', 'fixture.apk')
    Assert-Result ($installFailure.ExitCode -eq 9) 'APK install wrapper lost the native failure code'
    Assert-Result ($installFailure.Output.Contains('simulated native command failure')) 'APK install wrapper lost stderr'
    Assert-Result ($ErrorActionPreference -eq 'Stop') 'APK capture changed the caller error policy'
    Write-Host 'PASS: missing tokenizer pushes, matching files skip, native output stays separate from exit codes, and real failures remain failures.'
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
