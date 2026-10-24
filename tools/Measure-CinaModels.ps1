<#
.SYNOPSIS
Runs Cina's real Android work-control and continuation smoke tests across GGUFs.
.DESCRIPTION
Use -PlanOnly to validate files and print the experiment without touching a device.
An actual run requires an explicit serial, complete models and built debug/test APKs.
APKs are installed with -r; the script never uninstalls Cina or clears app data.
Each model transfer gets a unique temporary name and is removed after its runs.
Results describe these fixed fixtures, not a broad model quality rating.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$ModelPath,
    [string]$Serial,
    [string]$AdbPath = 'adb',
    [ValidateRange(1, 10)][int]$Repeats = 3,
    [string]$OutputDirectory,
    [string]$AppApk,
    [string]$TestApk,
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoPath ('.tools/model-evaluations/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
if (-not $AppApk) { $AppApk = Join-Path $repoPath 'app/build/outputs/apk/debug/app-debug.apk' }
if (-not $TestApk) { $TestApk = Join-Path $repoPath 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk' }
$models = @($ModelPath | ForEach-Object {
    $file = Get-Item -LiteralPath $_
    if ($file.PSIsContainer -or $file.Name -notmatch '^[A-Za-z0-9_.-]+\.gguf$') { throw "Use a GGUF basename containing letters, numbers, dots, underscores or hyphens: $($file.Name)" }
    $stream = [IO.File]::OpenRead($file.FullName)
    try {
        $header = New-Object byte[] 4
        if ($stream.Read($header, 0, 4) -ne 4 -or [Text.Encoding]::ASCII.GetString($header) -ne 'GGUF') { throw "Not a GGUF: $($file.FullName)" }
    } finally { $stream.Dispose() }
    [pscustomobject]@{ path = $file.FullName; name = $file.Name; bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
if (@($models | Group-Object name | Where-Object Count -gt 1).Count) { throw 'Each model must have a unique basename on the device.' }
if ($PlanOnly) {
    [pscustomobject]@{
        models = $models; repeats = $Repeats; serial = $Serial; output = $OutputDirectory
        fixtures = '8 fixed work-control cases plus one two-step continuation test, per repeat'
        installs = @($AppApk, $TestApk); note = 'Plan only. No installs, transfers or inference performed. GGUF magic is not proof of a complete or supported model.'
    } | ConvertTo-Json -Depth 6
    return
}
if (-not $Serial -or $Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Supply one explicit test-device serial with -Serial.' }
$adbCommand = Get-Command $AdbPath -ErrorAction Stop
$adbExecutable = $adbCommand.Source
$appFile = Get-Item -LiteralPath $AppApk
$testFile = Get-Item -LiteralPath $TestApk
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
function Invoke-Adb([string[]]$Arguments) {
    $lines = @(& $adbExecutable -s $Serial @Arguments 2>&1 | ForEach-Object { "$_" })
    $script:adbCode = $LASTEXITCODE
    return ($lines -join "`n")
}
function Require-Adb([string[]]$Arguments) {
    $result = Invoke-Adb $Arguments
    if ($script:adbCode -ne 0) { throw $result }
    return $result
}
if ((Require-Adb @('get-state')).Trim() -ne 'device') { throw 'The selected device is not ready.' }
$device = [ordered]@{
    serial = $Serial
    model = (Require-Adb @('shell', 'getprop', 'ro.product.model')).Trim()
    android = (Require-Adb @('shell', 'getprop', 'ro.build.version.release')).Trim()
    abi = (Require-Adb @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
}
Require-Adb @('install', '-r', $appFile.FullName) | Write-Host
Require-Adb @('install', '-r', $testFile.FullName) | Write-Host
$manifest = [ordered]@{
    schema = 1; startedUtc = [DateTime]::UtcNow.ToString('o'); device = $device
    appSha256 = (Get-FileHash -LiteralPath $appFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    testApkSha256 = (Get-FileHash -LiteralPath $testFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    suite = 'native-work-v2-and-two-step-continuation'; repeats = $Repeats; models = $models
    limits = 'Fixed smoke fixtures. No physical-phone battery/thermal or server measurements. Model failures do not imply app regression failures.'
}
$manifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'manifest.json') -Encoding utf8
$records = [Collections.Generic.List[object]]::new()
$remoteDirectory = '/sdcard/Android/data/dev.pocketmuse/files'
Require-Adb @('shell', 'mkdir', '-p', $remoteDirectory) | Out-Null
$classes = 'dev.pocketmuse.NativeWorkControlTest,dev.pocketmuse.NativeWorkPlanCycleTest'
foreach ($model in $models) {
    Write-Host "Preparing $($model.name)"
    $remoteName = 'cina-eval-' + [Guid]::NewGuid().ToString('N') + '-' + $model.name
    $remoteModel = "$remoteDirectory/$remoteName"
    try {
    # Failure to transfer aborts rather than attributing results from another model.
    Require-Adb @('push', $model.path, $remoteModel) | Write-Host
    $remoteHash = Require-Adb @('shell', 'sha256sum', $remoteModel)
    if (($remoteHash -split '\s+')[0] -ne $model.sha256) { throw "Transferred model hash mismatch: $($model.name)" }
    for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
        $runId = [Guid]::NewGuid().ToString('N')
        $runDirectory = Join-Path $OutputDirectory "$($model.name)-$repeat-$runId"
        New-Item -ItemType Directory -Path $runDirectory | Out-Null
        Write-Host "Evaluating $($model.name), repeat $repeat/$Repeats"
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $log = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $classes, '-e', 'work_model', $remoteName, '-e', 'evaluation_id', $runId, 'dev.pocketmuse.test/androidx.test.runner.AndroidJUnitRunner')
        $instrumentCode = $script:adbCode
        $watch.Stop()
        $log | Set-Content -LiteralPath (Join-Path $runDirectory 'instrumentation.txt') -Encoding utf8
        $controlName = "work-model-$remoteName-$runId.txt"
        $cycleName = "work-cycle-$remoteName-$runId.txt"
        # Unique run IDs prevent old evidence from being mistaken for a completed run.
        $controlPull = Invoke-Adb @('pull', "$remoteDirectory/$controlName", (Join-Path $runDirectory 'controls.txt'))
        $controlPresent = $script:adbCode -eq 0
        $cyclePull = Invoke-Adb @('pull', "$remoteDirectory/$cycleName", (Join-Path $runDirectory 'continuation.txt'))
        $cyclePresent = $script:adbCode -eq 0
        $controlText = if ($controlPresent) { Get-Content -LiteralPath (Join-Path $runDirectory 'controls.txt') -Raw } else { '' }
        $cycleText = if ($cyclePresent) { Get-Content -LiteralPath (Join-Path $runDirectory 'continuation.txt') -Raw } else { '' }
        # Only app-written terminal summaries count; a model can quote scoring text in its reply.
        $scoreMatch = [regex]::Match([string]$controlText, '(?m)^SCORE (\d+)/(\d+); no approved actions or notes/reminders created\.\s*\z')
        $suitePassed = $instrumentCode -eq 0 -and $log -match 'OK \(2 tests\)' -and $log -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg='
        $record = [ordered]@{
            model = $model.name; sha256 = $model.sha256; repeat = $repeat; runId = $runId; transferredAs = $remoteName
            suitePassed = $suitePassed; elapsedMs = $watch.ElapsedMilliseconds
            controlsPassed = $(if ($scoreMatch.Success) { [int]$scoreMatch.Groups[1].Value } else { $null })
            controlsTotal = $(if ($scoreMatch.Success) { [int]$scoreMatch.Groups[2].Value } else { $null })
            continuationPassed = [regex]::IsMatch([string]$cycleText, '(?m)^PASS: exactly two ordered draft steps, no execution\.\s*\z')
            evidenceComplete = $scoreMatch.Success -and $cyclePresent
            evidenceDirectory = $runDirectory
            transferErrors = @($(if (-not $controlPresent) { $controlPull }), $(if (-not $cyclePresent) { $cyclePull })) | Where-Object { $_ }
        }
        $records.Add([pscustomobject]$record)
        ConvertTo-Json -InputObject @($records.ToArray()) -Depth 7 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'results.json') -Encoding utf8
    }
    } finally {
        # This exact UUID-named file was created by this run; original device models are untouched.
        $cleanup = Invoke-Adb @('shell', 'rm', '-f', $remoteModel)
        if ($script:adbCode -ne 0) { Write-Warning "Could not remove this run's temporary transfer: $remoteModel. $cleanup" }
    }
}
$records | Format-Table model, repeat, controlsPassed, controlsTotal, continuationPassed, suitePassed, evidenceComplete
Write-Host "Evidence saved to $OutputDirectory"
if (@($records | Where-Object { -not $_.suitePassed -or -not $_.evidenceComplete }).Count) { exit 1 }
