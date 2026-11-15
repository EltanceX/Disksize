param([Parameter(Mandatory = $true)][string]$Serial)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase3'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$package = 'com.example.disksize'
$modeText = (& $adb -s $Serial shell appops get $package MANAGE_EXTERNAL_STORAGE 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Install and open the normal debug app first.' }
$originalMode = if ($modeText -match 'MANAGE_EXTERNAL_STORAGE: (allow|ignore|deny|default|foreground)') { $Matches[1] } else { 'default' }
$fixture = $null

function Shell([string]$Command) {
    $output = @(& $adb -s $Serial shell $Command 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Command`n$output" }
    return $output
}
function Ui {
    Shell 'uiautomator dump /data/local/tmp/disksize-phase3-ui.xml' | Out-Null
    $xmlText = (Shell 'cat /data/local/tmp/disksize-phase3-ui.xml') -join ''
    $xmlText | Set-Content -Encoding utf8 (Join-Path $logs 'revocation-ui.xml')
    return [xml]$xmlText
}
function TapNode($Node) {
    if (-not $Node -or $Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Missing UI element bounds.' }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Shell "input tap $x $y" | Out-Null
}
function ReadSession {
    $text = (& $adb -s $Serial shell run-as $package cat no_backup/scan-session.json 2>&1) -join ''
    if ($LASTEXITCODE -ne 0) { return $null }
    return $text | ConvertFrom-Json
}
function WaitSession([scriptblock]$Predicate) {
    $limit = [DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $limit) {
        $value = ReadSession
        if ($value -and (& $Predicate $value)) { return $value }
        Start-Sleep -Milliseconds 100
    }
    throw 'Session state did not reach the expected value.'
}

try {
    Shell 'mkdir -p /data/local/tmp/disksize-phase3-bin' | Out-Null
    & $adb -s $Serial push (Join-Path $PSScriptRoot 'create-shared-fixture.sh') /data/local/tmp/disksize-phase3-bin/
    if ($LASTEXITCODE -ne 0) { throw 'Fixture script push failed.' }
    $fixture = ((Shell 'sh /data/local/tmp/disksize-phase3-bin/create-shared-fixture.sh') -join '').Trim()
    if ($fixture -notmatch '^/storage/emulated/0/Download/disksize-phase3-revoke-[A-Za-z0-9]+$') { throw 'Unexpected fixture path.' }
    Shell "appops set $package MANAGE_EXTERNAL_STORAGE allow" | Out-Null
    Shell "am start -n $package/.MainActivity" | Out-Null
    $ui = Ui
    TapNode ($ui.SelectNodes('//node') | Where-Object { $_.'content-desc' -like "扫描入口*" } | Select-Object -First 1)
    $ui = Ui
    TapNode ($ui.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq '自定义真实路径' -or $_.text -eq '自定义真实路径' } | Select-Object -First 1)
    $ui = Ui
    $pathField = $ui.SelectNodes('//node') | Where-Object { $_.class -eq 'android.widget.EditText' } | Select-Object -First 1
    TapNode $pathField
    Shell 'input keyevent KEYCODE_MOVE_END' | Out-Null
    $deleteKeys = ('67 ' * ($pathField.text.Length + 2)).Trim()
    Shell "input keyevent $deleteKeys" | Out-Null
    Shell "input text $fixture" | Out-Null
    Shell 'input keyevent KEYCODE_BACK' | Out-Null
    $ui = Ui
    if (-not ($ui.SelectNodes('//node') | Where-Object { $_.text -eq $fixture })) { throw 'The selected scan path differs from the fixture.' }
    TapNode ($ui.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq '开始扫描' } | Select-Object -First 1)
    $running = WaitSession { param($value) $value.rootPath -eq $fixture -and $value.active -and $value.state -eq 'running' }
    $running | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 (Join-Path $logs 'revocation-before.json')
    Shell "appops set $package MANAGE_EXTERNAL_STORAGE ignore" | Out-Null
    # Some devices stop both application processes on AppOp revocation. The
    # host remains alive and reopens the real UI, without an integration runner.
    Start-Sleep -Milliseconds 500
    Shell "am start -n $package/.MainActivity" | Out-Null
    $recovered = WaitSession { param($value) $value.sessionId -eq $running.sessionId -and -not $value.active }
    if ($recovered.state -ne 'interrupted' -or $recovered.complete -or
        $recovered.reason -notin @('process-interrupted', 'permission-revoked')) { throw 'Revocation was not recorded as interrupted.' }
    $recovered | ConvertTo-Json -Depth 12 | Set-Content -Encoding utf8 (Join-Path $logs 'revocation-after.json')
    Write-Output "PHASE3_REVOCATION_OK state=$($recovered.state) reason=$($recovered.reason)"
} finally {
    Shell "appops set $package MANAGE_EXTERNAL_STORAGE $originalMode" | Out-Null
    # Only this exact, freshly created and validated path is removed.
    if ($fixture -and $fixture -match '^/storage/emulated/0/Download/disksize-phase3-revoke-[A-Za-z0-9]+$') {
        Shell "rm -r '$fixture'" | Out-Null
    }
    Shell "am start -n $package/.MainActivity" | Out-Null
}
