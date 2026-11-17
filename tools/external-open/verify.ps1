param([Parameter(Mandatory = $true)][string]$Serial, [switch]$RootInstall)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/external-open'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load .local/android-env.ps1 first.' }
$normalApk = Join-Path $logs 'app-debug.apk'
$forwardedPort = $null
$chooserJob = $null
$previous = Get-Location
function Install-Apk([string]$Apk) {
    & (Join-Path $repository 'tools/install-android-apk.ps1') -Serial $Serial -Apk $Apk -UseRoot:$RootInstall
}
try {
    Set-Location $repository
    & flutter build apk --debug --target-platform 'android-arm64,android-x64'
    if ($LASTEXITCODE -ne 0) { throw 'Normal APK build failed.' }
    Copy-Item -LiteralPath build/app/outputs/flutter-apk/app-debug.apk -Destination $normalApk
    & flutter build apk --profile -t integration_test/external_open_test.dart --target-platform 'android-arm64,android-x64'
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
    $chooserJob = Start-Job -ArgumentList $adb,$Serial,$driveLog,$androidUser,$logs -ScriptBlock {
        param($adbPath,$device,$logPath,$userId,$outputDirectory)
        $ErrorActionPreference = 'Stop'
        $PSNativeCommandUseErrorActionPreference = $false
        foreach ($kind in @('directory', 'regular')) {
            $deadline = [DateTime]::UtcNow.AddSeconds(90)
            $marker = "EXTERNAL_OPEN_$($kind.ToUpperInvariant())_READY"
            while (([string](Get-Content -LiteralPath $logPath -Raw)) -notmatch $marker) {
                if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out waiting for $marker" }
                Start-Sleep -Milliseconds 200
            }
            # Wait for the foreground chooser, rather than matching old activity history.
            do {
                $activities = (& $adbPath -s $device shell dumpsys activity activities) -join "`n"
                $chooserVisible = $activities -match 'topResumedActivity[^\r\n]*ChooserActivity'
                if (-not $chooserVisible) { Start-Sleep -Milliseconds 100 }
            } while (-not $chooserVisible -and [DateTime]::UtcNow -lt $deadline)
            if (-not $chooserVisible) { throw "The $kind chooser was not foreground." }
            [IO.File]::WriteAllText((Join-Path $outputDirectory "$kind-chooser.txt"), $activities)
            & $adbPath -s $device shell input keyevent KEYCODE_BACK | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Cannot close the chooser.' }
            & $adbPath -s $device shell touch "/storage/emulated/$userId/Android/data/com.example.disksize/files/open-$kind.done"
            if ($LASTEXITCODE -ne 0) { throw "Cannot acknowledge $kind chooser." }
        }
    }
    # Attach to a manually installed process, preserving existing application data.
    & flutter drive --profile -d $Serial --target integration_test/external_open_test.dart `
        --driver test_driver/external_open_test.dart --use-existing-app $vmUrl --no-dds --timeout 180 > $driveLog 2>&1
    $driverExit = $LASTEXITCODE
    Get-Content -LiteralPath $driveLog -Tail 45
    if ($driverExit -ne 0) { throw 'External open integration failed.' }
    $completed = Wait-Job $chooserJob -Timeout 5
    if (-not $completed -or $chooserJob.State -ne 'Completed') { throw 'Chooser device driver failed.' }
    Receive-Job $chooserJob
    Remove-Job $chooserJob
    $chooserJob = $null
    & $adb -s $Serial exec-out cat "/storage/emulated/$androidUser/Android/data/com.example.disksize/files/external-open.png" > (Join-Path $logs 'external-open.png')
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot extraction failed.' }
    Get-Content -LiteralPath (Join-Path $logs 'report.json')
} finally {
    if ($chooserJob) {
        Stop-Job $chooserJob
        Remove-Job $chooserJob -Force
    }
    if ($forwardedPort) { & $adb -s $Serial forward --remove "tcp:$forwardedPort" }
    if (Test-Path -LiteralPath $normalApk) {
        Install-Apk $normalApk
        Copy-Item -LiteralPath $normalApk -Destination build/app/outputs/flutter-apk/app-debug.apk
        & $adb -s $Serial shell am start -n com.example.disksize/.MainActivity
    }
    Set-Location $previous
}
