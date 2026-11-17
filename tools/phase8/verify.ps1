param([Parameter(Mandatory = $true)][string]$Serial, [switch]$RootInstall, [string]$CleanupRoot)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase8'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load .local/android-env.ps1 first.' }
$normalApk = Join-Path $logs 'app-debug.apk'
$forwardedPort = $null
$previous = Get-Location
function Install-Apk([string]$Apk) {
    & (Join-Path $repository 'tools/install-android-apk.ps1') -Serial $Serial -Apk $Apk -UseRoot:$RootInstall
}
try {
    Set-Location $repository
    & flutter build apk --debug --target-platform 'android-arm64,android-x64'
    if ($LASTEXITCODE -ne 0) { throw 'Normal APK build failed.' }
    Copy-Item -LiteralPath build/app/outputs/flutter-apk/app-debug.apk -Destination $normalApk
    $defines = @()
    if ($CleanupRoot) { $defines += "--dart-define=PHASE8_CLEANUP_ROOT=$CleanupRoot" }
    & flutter build apk --profile -t integration_test/snapshots_test.dart --target-platform 'android-arm64,android-x64' @defines
    if ($LASTEXITCODE -ne 0) { throw 'Profile test APK build failed.' }
    Install-Apk (Join-Path $repository 'build/app/outputs/flutter-apk/app-profile.apk')
    & $adb -s $Serial shell am force-stop com.example.disksize
    & $adb -s $Serial shell am start -n com.example.disksize/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'Profile test launch failed.' }
    $vm = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $appPid = ([string]::Join(' ', @(& $adb -s $Serial shell pidof com.example.disksize))).Trim()
        if ($appPid -match '^\d+$') {
            $output = (& $adb -s $Serial logcat -d --pid=$appPid) -join "`n"
            if ($output -match 'Dart VM service is listening on http://127\.0\.0\.1:(\d+)(/\S*)') {
                $vm = @{ Port = $Matches[1]; Path = $Matches[2] }
            }
        }
        if (-not $vm) { Start-Sleep -Milliseconds 200 }
    } while (-not $vm -and [DateTime]::UtcNow -lt $deadline)
    if (-not $vm) { throw 'Dart VM service URL was not observed.' }
    $forwardedPort = ([string](& $adb -s $Serial forward tcp:0 "tcp:$($vm.Port)")).Trim()
    if ($LASTEXITCODE -ne 0 -or $forwardedPort -notmatch '^\d+$') { throw 'VM port forwarding failed.' }
    $vmUrl = "http://127.0.0.1:$forwardedPort$($vm.Path)"
    # Attach to the manually installed process: Flutter never uninstalls the target app.
    & flutter drive --profile -d $Serial --target integration_test/snapshots_test.dart `
        --driver test_driver/snapshots_test.dart --use-existing-app $vmUrl --no-dds --timeout 180 > (Join-Path $logs 'drive.log') 2>&1
    $driverExit = $LASTEXITCODE
    Get-Content -LiteralPath (Join-Path $logs 'drive.log') -Tail 45
    if ($driverExit -ne 0) { throw 'Snapshot integration failed.' }
    $androidUser = ([string](& $adb -s $Serial shell am get-current-user)).Trim()
    if ($androidUser -notmatch '^\d+$') { throw 'Invalid Android user ID.' }
    & $adb -s $Serial exec-out cat "/storage/emulated/$androidUser/Android/data/com.example.disksize/files/phase8-comparison.png" > (Join-Path $logs 'comparison.png')
    if ($LASTEXITCODE -ne 0) { throw 'Snapshot screenshot extraction failed.' }
    & $adb -s $Serial exec-out cat "/storage/emulated/$androidUser/Android/data/com.example.disksize/files/phase8-batch-selection.png" > (Join-Path $logs 'batch-selection.png')
    if ($LASTEXITCODE -ne 0) { throw 'Batch selection screenshot extraction failed.' }
    Get-Content -LiteralPath (Join-Path $logs 'comparison-report.json')
} catch {
    Write-Output $_.ScriptStackTrace
    throw
} finally {
    if ($forwardedPort) { & $adb -s $Serial forward --remove "tcp:$forwardedPort" }
    if (Test-Path -LiteralPath $normalApk) {
        Install-Apk $normalApk
        Copy-Item -LiteralPath $normalApk -Destination build/app/outputs/flutter-apk/app-debug.apk
        & $adb -s $Serial shell am start -n com.example.disksize/.MainActivity
    }
    Set-Location $previous
}
