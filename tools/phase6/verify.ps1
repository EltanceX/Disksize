param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase6'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load .local/android-env.ps1 first.' }
$normalApk = Join-Path $logs 'app-debug.apk'
$log = Join-Path $logs 'explorer-integration.log'
$screenshot = Join-Path $logs 'explorer.png'
$previous = Get-Location
$testJob = $null
try {
    Set-Location $repository
    & flutter build apk --debug --target-platform 'android-arm64,android-x64'
    if ($LASTEXITCODE -ne 0) { throw 'Dashboard APK build failed.' }
    Copy-Item -LiteralPath 'build/app/outputs/flutter-apk/app-debug.apk' -Destination $normalApk
    [IO.File]::WriteAllText($log, '')
    $testJob = Start-Job -ArgumentList $repository,$Serial,$log -ScriptBlock {
        param($repo,$device,$outputLog)
        $OutputEncoding = [Text.UTF8Encoding]::new($false)
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        Set-Location $repo
        & flutter test integration_test/explorer_test.dart -d $device --dart-define=PHASE6_CAPTURE=true 2>&1 |
            ForEach-Object { Add-Content -Encoding utf8 $outputLog $_.ToString() }
        return $LASTEXITCODE
    }
    $seen = 0
    $captured = $false
    do {
        $lines = @(Get-Content -LiteralPath $log)
        while ($seen -lt $lines.Count) {
            $line = $lines[$seen++]
            Write-Output $line
            if ($line -eq 'PHASE6_CAPTURE_READY') {
                & $adb -s $Serial exec-out run-as com.example.disksize cat files/phase6-explorer.png > $screenshot
                if ($LASTEXITCODE -ne 0) { throw 'Screenshot extraction failed.' }
                $signature = [BitConverter]::ToString([IO.File]::ReadAllBytes($screenshot)[0..7])
                if ($signature -ne '89-50-4E-47-0D-0A-1A-0A') { throw 'Invalid screenshot PNG.' }
                $captured = $true
            }
        }
        Start-Sleep -Milliseconds 100
    } while ($testJob.State -eq 'Running' -or $seen -lt @(Get-Content -LiteralPath $log).Count)
    $result = @(Receive-Job $testJob)
    Remove-Job $testJob
    $testJob = $null
    if ($result.Count -eq 0 -or $result[-1] -ne 0 -or -not $captured) { throw 'Explorer integration failed; inspect .local/phase6.' }
} finally {
    if ($testJob) { Stop-Job $testJob; Remove-Job $testJob -Force }
    if (Test-Path -LiteralPath $normalApk) {
        & $adb -s $Serial install -r $normalApk
        Copy-Item -LiteralPath $normalApk -Destination (Join-Path $repository 'build/app/outputs/flutter-apk/app-debug.apk')
        & $adb -s $Serial shell am start -n com.example.disksize/.MainActivity
    }
    Set-Location $previous
}
