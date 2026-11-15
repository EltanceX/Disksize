param([Parameter(Mandatory = $true)][string]$Serial)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = Join-Path $repository '.local/phase3'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Load the Android environment first.' }
$package = 'com.example.disksize'
$normalApk = Join-Path $logs 'app-debug.apk'
$previous = Get-Location
try {
    Set-Location $repository
    & flutter build apk --debug --target-platform 'android-arm64,android-x64'
    if ($LASTEXITCODE -ne 0) { throw 'Normal app build failed.' }
    Copy-Item -LiteralPath (Join-Path $repository 'build/app/outputs/flutter-apk/app-debug.apk') -Destination $normalApk
    & $adb -s $Serial install -r $normalApk
    if ($LASTEXITCODE -ne 0) { throw 'Normal app installation failed.' }
} finally { Set-Location $previous }
$modeText = (& $adb -s $Serial shell appops get $package MANAGE_EXTERNAL_STORAGE 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Install the development app before running lifecycle verification.' }
$originalMode = if ($modeText -match 'MANAGE_EXTERNAL_STORAGE: (allow|ignore|deny|default|foreground)') { $Matches[1] } else { 'default' }
$originalTimeout = ((& $adb -s $Serial shell device_config get activity_manager data_sync_fgs_timeout_duration) -join '').Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read dataSync timeout configuration.' }
@{ allFilesMode = $originalMode; dataSyncTimeout = $originalTimeout } | ConvertTo-Json |
    Set-Content -Encoding utf8 (Join-Path $logs 'device-settings-before.json')

function Invoke-AdbShell([string]$Command) {
    $output = & $adb -s $Serial shell $Command 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Command`n$output" }
}

try {
    Set-Location $repository
    $integrationLog = Join-Path $logs 'lifecycle-integration.log'
    if (Test-Path -LiteralPath $integrationLog) {
        Copy-Item -LiteralPath $integrationLog -Destination (Join-Path $logs 'lifecycle-integration.previous.log')
    }
    [IO.File]::WriteAllText($integrationLog, '')
    & flutter test integration_test/scanning_test.dart -d $Serial --dart-define=PHASE3_DEVICE_DRIVER=true 2>&1 |
        ForEach-Object {
            $line = $_.ToString()
            Add-Content -Encoding utf8 (Join-Path $logs 'lifecycle-integration.log') $line
            Write-Output $line
            if ($line -match '^PHASE3_GRANT_STORAGE$') {
                Invoke-AdbShell "appops set $package MANAGE_EXTERNAL_STORAGE allow"
            } elseif ($line -match '^PHASE3_REVOKE_STORAGE$') {
                Invoke-AdbShell "appops set $package MANAGE_EXTERNAL_STORAGE ignore"
            } elseif ($line -match '^PHASE3_BACKGROUND_READY$') {
                Invoke-AdbShell 'input keyevent KEYCODE_HOME'
                Start-Sleep -Milliseconds 800
                Invoke-AdbShell "am start -n $package/.MainActivity" | Out-Null
            } elseif ($line -match '^PHASE3_PREPARE_TIMEOUT$') {
                Invoke-AdbShell 'device_config put activity_manager data_sync_fgs_timeout_duration 250'
            } elseif ($line -match '^PHASE3_TIMEOUT_READY$') {
                Invoke-AdbShell 'input keyevent KEYCODE_HOME'
                Start-Sleep -Milliseconds 1800
                Invoke-AdbShell "am start -n $package/.MainActivity" | Out-Null
            }
        }
    if ($LASTEXITCODE -ne 0) { throw 'Lifecycle integration failed; inspect .local/phase3/lifecycle-integration.log' }
} finally {
    # Flutter integration testing may uninstall its APK; restore the actual UI.
    & $adb -s $Serial install -r $normalApk
    Invoke-AdbShell "appops set $package MANAGE_EXTERNAL_STORAGE $originalMode"
    if ($originalTimeout -eq 'null') {
        Invoke-AdbShell 'device_config delete activity_manager data_sync_fgs_timeout_duration' | Out-Null
    } else {
        Invoke-AdbShell "device_config put activity_manager data_sync_fgs_timeout_duration $originalTimeout"
    }
    Copy-Item -LiteralPath $normalApk -Destination (Join-Path $repository 'build/app/outputs/flutter-apk/app-debug.apk')
    Invoke-AdbShell "am start -n $package/.MainActivity" | Out-Null
    Set-Location $previous
}
