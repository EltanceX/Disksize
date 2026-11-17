param([Parameter(Mandatory = $true)][string]$Serial, [switch]$RootInstall)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase7'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load .local/android-env.ps1 first.' }
$normalApk = Join-Path $logs 'app-debug.apk'
$forwardedPort = $null
$lifecycleJob = $null
$previous = Get-Location
function Install-Apk([string]$Apk) {
    & (Join-Path $repository 'tools/install-android-apk.ps1') -Serial $Serial -Apk $Apk -UseRoot:$RootInstall
}
try {
    Set-Location $repository
    & flutter build apk --debug --target-platform 'android-arm64,android-x64'
    if ($LASTEXITCODE -ne 0) { throw 'Normal APK build failed.' }
    Copy-Item -LiteralPath build/app/outputs/flutter-apk/app-debug.apk -Destination $normalApk
    & flutter build apk --profile -t integration_test/treemap_test.dart --target-platform 'android-arm64,android-x64' --dart-define=PHASE7_LIFECYCLE=true
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
    $androidUser = ([string](& $adb -s $Serial shell am get-current-user)).Trim()
    if ($androidUser -notmatch '^\d+$') { throw 'Invalid Android user ID.' }
    $driveLog = Join-Path $logs 'drive.log'
    [IO.File]::WriteAllText($driveLog, '')
    # Drive real focus loss and backgrounding after the app records selection.
    $lifecycleJob = Start-Job -ArgumentList $adb,$Serial,$driveLog,$androidUser -ScriptBlock {
        param($adbPath,$device,$logPath,$userId)
        $ErrorActionPreference = 'Stop'
        $PSNativeCommandUseErrorActionPreference = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(180)
        foreach ($action in @('shade', 'background')) {
            $marker = "PHASE7_$($action.ToUpperInvariant())_READY"
            while (([string](Get-Content -LiteralPath $logPath -Raw)) -notmatch $marker) {
                if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out waiting for $marker" }
                Start-Sleep -Milliseconds 200
            }
            if ($action -eq 'shade') {
                & $adbPath -s $device shell cmd statusbar expand-notifications | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Cannot expand notification shade.' }
                Start-Sleep -Milliseconds 800
                & $adbPath -s $device shell cmd statusbar collapse | Out-Null
            } else {
                & $adbPath -s $device shell input keyevent KEYCODE_HOME | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Cannot background the app.' }
                Start-Sleep -Milliseconds 800
                & $adbPath -s $device shell am start -n com.example.disksize/.MainActivity 2>&1 | Out-Null
            }
            if ($LASTEXITCODE -ne 0) { throw "Cannot finish $action action." }
            Start-Sleep -Milliseconds 500
            & $adbPath -s $device shell touch "/storage/emulated/$userId/Android/data/com.example.disksize/files/phase7-$action.done"
            if ($LASTEXITCODE -ne 0) { throw "Cannot acknowledge $action action." }
        }
    }
    # Attach to the manually installed process: Flutter never uninstalls the target app.
    & flutter drive --profile -d $Serial --target integration_test/treemap_test.dart `
        --driver test_driver/integration_test.dart --use-existing-app $vmUrl --no-dds --timeout 180 > (Join-Path $logs 'drive.log') 2>&1
    $driverExit = $LASTEXITCODE
    Get-Content -LiteralPath (Join-Path $logs 'drive.log') -Tail 45
    if ($driverExit -ne 0) { throw 'Treemap integration failed.' }
    $completed = Wait-Job $lifecycleJob -Timeout 5
    if (-not $completed -or $lifecycleJob.State -ne 'Completed') { throw 'Lifecycle device driver failed.' }
    Receive-Job $lifecycleJob
    Remove-Job $lifecycleJob
    $lifecycleJob = $null
    & $adb -s $Serial exec-out cat "/storage/emulated/$androidUser/Android/data/com.example.disksize/files/phase7-treemap.png" > (Join-Path $logs 'treemap.png')
    if ($LASTEXITCODE -ne 0) { throw 'Treemap screenshot extraction failed.' }
    Get-Content -LiteralPath (Join-Path $logs 'frame-report.json')
} catch {
    Write-Output $_.ScriptStackTrace
    throw
} finally {
    if ($lifecycleJob) {
        Stop-Job $lifecycleJob
        Remove-Job $lifecycleJob -Force
        & $adb -s $Serial shell cmd statusbar collapse | Out-Null
    }
    if ($forwardedPort) { & $adb -s $Serial forward --remove "tcp:$forwardedPort" }
    if (Test-Path -LiteralPath $normalApk) {
        Install-Apk $normalApk
        Copy-Item -LiteralPath $normalApk -Destination build/app/outputs/flutter-apk/app-debug.apk
        & $adb -s $Serial shell am start -n com.example.disksize/.MainActivity
    }
    Set-Location $previous
}
