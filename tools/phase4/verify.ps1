param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase4'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$normalApk = Join-Path $logs 'app-debug.apk'
if (-not (Test-Path -LiteralPath $normalApk)) { throw 'Build and save the ordinary dashboard APK to .local/phase4/app-debug.apk first.' }
$log = Join-Path $logs 'root-integration.log'
$previous = Get-Location
function Invoke-Device([string]$Command) {
    $output = & $adb -s $Serial shell $Command 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Device command failed: $Command`n$output" }
    return $output
}
function Read-Ui {
    Invoke-Device 'uiautomator dump /sdcard/disksize-phase4-ui.xml' | Out-Null
    return [xml]((Invoke-Device 'cat /sdcard/disksize-phase4-ui.xml') -join '')
}
function Tap-Node($Node) {
    while ($Node -and $Node.clickable -ne 'true' -and $Node.ParentNode.Name -eq 'node') { $Node = $Node.ParentNode }
    if ($null -eq $Node -or $Node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'UI element unavailable.' }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Write-Host "KernelSU verification tap: $x,$y ($($Node.bounds))"
    Invoke-Device "input tap $x $y" | Out-Null
}
function Grant-DevelopmentPackage {
    # KernelSU 3.3 tablet UI only. Never changes another app or a global policy.
    Invoke-Device 'am force-stop me.weishu.kernelsu' | Out-Null
    Invoke-Device 'am start -n me.weishu.kernelsu/.ui.MainActivity' | Out-Null
    $navigation = $null
    for ($attempt = 0; $attempt -lt 6 -and $null -eq $navigation; $attempt++) {
        $ui = Read-Ui
        $navigation = $ui.SelectNodes('//node') | Where-Object { $_.text -eq '超级用户' } | Select-Object -First 1
    }
    Tap-Node $navigation
    $ui = Read-Ui
    if ($ui.SelectNodes('//node') | Where-Object { $_.text -eq 'KernelSU' }) {
        # The manager's initial transition may discard the first navigation tap.
        Invoke-Device 'input tap 100 620' | Out-Null
        $ui = Read-Ui
    }
    # The inactive Compose search bar omits accessibility nodes on this manager.
    # These coordinates are from the verified 3048x2032 tablet screenshot.
    Start-Sleep -Milliseconds 1500
    Invoke-Device 'input tap 800 420' | Out-Null
    Invoke-Device 'input keyevent KEYCODE_MOVE_END' | Out-Null
    Invoke-Device 'input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67' | Out-Null
    Invoke-Device 'input text disksize' | Out-Null
    $packageNode = $null
    for ($attempt = 0; $attempt -lt 12 -and $null -eq $packageNode; $attempt++) {
        $ui = Read-Ui
        $ui.Save((Join-Path $logs 'ksu-search.xml'))
        $packageNode = $ui.SelectNodes('//node') | Where-Object { $_.text -eq 'com.example.disksize' } | Select-Object -First 1
        if ($null -eq $packageNode) {
            # Re-evaluate the query after the manager's asynchronous package load.
            Invoke-Device 'input text x' | Out-Null
            Invoke-Device 'input keyevent KEYCODE_DEL' | Out-Null
        }
    }
    Tap-Node $packageNode
    $ui = Read-Ui
    $ui.Save((Join-Path $logs 'ksu-profile.xml'))
    $toggle = $ui.SelectNodes('//node') | Where-Object { $_.checkable -eq 'true' } | Select-Object -First 1
    if ($null -eq $toggle) { throw 'KernelSU package authorization switch unavailable.' }
    if ($toggle.checked -ne 'true') { Tap-Node $toggle }
    $ui = Read-Ui
    $toggle = $ui.SelectNodes('//node') | Where-Object { $_.checkable -eq 'true' } | Select-Object -First 1
    if ($toggle.checked -ne 'true') { throw 'KernelSU package grant failed.' }
    Invoke-Device 'am start -n com.example.disksize/.MainActivity' | Out-Null
    Invoke-Device 'run-as com.example.disksize touch files/phase4-root-ready' | Out-Null
}
try {
    Set-Location $repository
    & $adb -s $Serial push tools/phase4/create-fixture.sh /data/local/tmp/disksize-create-phase4.sh
    $reference = @(Invoke-Device 'su -c "sh /data/local/tmp/disksize-create-phase4.sh"')
    $reference | Set-Content -Encoding utf8 (Join-Path $logs 'fixture-stat-before.txt')
    [uint64]$referenceLogical = 0
    [uint64]$referenceAllocated = 0
    $seen = [Collections.Generic.HashSet[string]]::new()
    foreach ($record in $reference) {
        $fields = $record.Split(':')
        if ($fields.Count -ne 7) { throw 'Invalid independent stat reference.' }
        $kind = [Convert]::ToUInt32($fields[0], 16) -band 0xf000
        if ($kind -eq 0x4000 -or $seen.Add("$($fields[3]):$($fields[4])")) {
            $referenceAllocated += [uint64]$fields[2] * 512
            if ($kind -eq 0x8000) { $referenceLogical += [uint64]$fields[1] }
        }
    }
    [IO.File]::WriteAllText($log, '')
    # Keep draining Flutter output during manager navigation. Its VM/service
    # connection and the test log must not depend on this UI driver's latency.
    $rootTest = Start-Job -ArgumentList $repository,$Serial,$log -ScriptBlock {
        param($repo,$device,$outputLog)
        $OutputEncoding = [Text.UTF8Encoding]::new($false)
        [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        Set-Location $repo
        & flutter test integration_test/root_scanning_test.dart -d $device 2>&1 | ForEach-Object {
            Add-Content -Encoding utf8 $outputLog $_.ToString()
        }
        return $LASTEXITCODE
    }
    $seenLines = 0
    do {
        $incoming = @(Get-Content -LiteralPath $log)
        while ($seenLines -lt $incoming.Count) {
        $line = $incoming[$seenLines++]
        Write-Output $line
        if ($line -eq 'PHASE4_AUTHORIZE') {
            Grant-DevelopmentPackage
        } elseif ($line -match '^PHASE4_RAW_OK nodes=(\d+) logical=(\d+) allocated=(\d+)') {
            if ([int]$Matches[1] -ne $reference.Count -or [uint64]$Matches[2] -ne $referenceLogical -or
                [uint64]$Matches[3] -ne $referenceAllocated) { throw 'Root pipe result differs from independent stat metadata.' }
        } elseif ($line -match '^PHASE4_KILL_ROOT (\d+)$') {
            $rootPid = $Matches[1]
            $identity = (Invoke-Device "su -c 'cat /proc/$rootPid/status'") -join "`n"
            if ($identity -notmatch 'Uid:\s+0\s+0\s+0\s+0') { throw 'Refusing to kill a process without a verified root UID.' }
            $command = (Invoke-Device "su -c 'cat /proc/$rootPid/cmdline'") -join ''
            if ($command -notmatch 'disksize') { throw 'Refusing to kill an unrelated root process.' }
            Invoke-Device "su -c 'kill -9 $rootPid'" | Out-Null
        } elseif ($line -match '^PHASE4_CLIENT_DIED (\d+)$') {
            $rootPid = $Matches[1]
            $until = [DateTime]::UtcNow.AddSeconds(8)
            do {
                $remaining = (Invoke-Device "su -c 'test -d /proc/$rootPid && echo alive || echo gone'") -join ''
                if ($remaining -match 'gone') { break }
                Start-Sleep -Milliseconds 100
            } while ([DateTime]::UtcNow -lt $until)
            if ($remaining -notmatch 'gone') { throw 'Root service outlived its coordinator client.' }
            Add-Content -Encoding utf8 (Join-Path $logs 'client-cleanup.txt') "rootPid=$rootPid gone after client death"
        }
        }
        Start-Sleep -Milliseconds 100
    } while ($rootTest.State -eq 'Running' -or $seenLines -lt @(Get-Content -LiteralPath $log).Count)
    $rootExit = @(Receive-Job $rootTest)
    Remove-Job $rootTest
    $rootTest = $null
    if ($rootExit.Count -eq 0 -or $rootExit[-1] -ne 0) { throw 'Root integration failed; inspect .local/phase4/root-integration.log' }
    $after = @(Invoke-Device 'su -c "find /data/local/tmp/disksize-phase4 -exec stat -c %f:%s:%b:%d:%i:%u:%g {} +"')
    $after | Set-Content -Encoding utf8 (Join-Path $logs 'fixture-stat-after.txt')
    if (Compare-Object $reference $after) { throw 'Scanning changed fixture metadata or ownership.' }
    Invoke-Device 'su -c "test -f /data/local/tmp/disksize-phase4/.disksize-owned-fixture && chmod 700 /data/local/tmp/disksize-phase4/protected && rm -rf /data/local/tmp/disksize-phase4"' | Out-Null
    Invoke-Device 'rm -f /data/local/tmp/disksize-create-phase4.sh /sdcard/disksize-phase4-ui.xml' | Out-Null
} finally {
    if ($rootTest) { Stop-Job $rootTest; Remove-Job $rootTest -Force }
    & $adb -s $Serial install -r $normalApk
    Copy-Item -LiteralPath $normalApk -Destination (Join-Path $repository 'build/app/outputs/flutter-apk/app-debug.apk')
    Invoke-Device 'am start -n com.example.disksize/.MainActivity' | Out-Null
    Set-Location $previous
}
